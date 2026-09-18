#!/usr/bin/env bash
# ADR-025, the half a network test cannot see: an unknown recipient's content is
# discarded IN INGESTION and never retained anywhere.
#
# "GET returns 404" is not that proof. A message could be stored and merely
# unreachable — quarantined, orphaned, written to object storage before the
# recipient was resolved, or left in an attachment row. This sends a message
# carrying a unique marker to a syntactically valid but nonexistent recipient,
# lets the relay complete, and then looks directly at PostgreSQL and MinIO for
# any trace of it.
#
# It matters that the message is RELAYED rather than discarded at the edge: the
# dormant real edge discards its tenant domain, which is why its evidence is
# necessary but not sufficient. The rehearsal renders the production relay line,
# so ingestion is genuinely the component that decides to discard.
#
# Run from the rehearsal with the stack up and COMPOSE_ENV_FILES set.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose
  -f "$REPO_ROOT/deploy/staging/compose.yaml"
  -f "$REPO_ROOT/deploy/staging/compose.data.yaml"
  -f "$REPO_ROOT/deploy/staging/compose.mail-edge.yaml")

EDGE_PORT="${TESTINBOX_EDGE_SMTP_PORT:-2526}"
MAIL_DOMAIN="${TESTINBOX_MAIL_DOMAIN:?set by the rehearsal}"
# Bounded at the READ rather than by closing the pipe: `tr … | head -c` leaves
# tr killed by SIGPIPE, which `pipefail` reports as 141 and `set -e` treats as
# fatal. Reading a fixed slice of /dev/urandom has no such edge.
MARKER="ADR025-$(head -c 400 /dev/urandom | LC_ALL=C tr -dc 'A-Z0-9' | cut -c1-20)"
UNKNOWN="nobody-$(head -c 400 /dev/urandom | LC_ALL=C tr -dc 'a-z0-9' | cut -c1-10)@${MAIL_DOMAIN}"

echo "  marker    $MARKER"
echo "  recipient $UNKNOWN (syntactically valid, resolves to no inbox)"

# --- send through the edge ---------------------------------------------------
# Deliberately not via the SDK: this is an unauthenticated sender on the wire,
# which is what a real unknown-recipient delivery looks like.
python3 - "$EDGE_PORT" "$UNKNOWN" "$MARKER" <<'PY'
import socket, sys, time

port, recipient, marker = int(sys.argv[1]), sys.argv[2], sys.argv[3]
message = (
    f"From: sut@example.net\r\nTo: {recipient}\r\n"
    f"Subject: {marker}\r\n\r\n{marker}\r\n"
).encode()

sock = socket.create_connection(("127.0.0.1", port), timeout=30)
sock.settimeout(30)
def expect(prefix):
    data = b""
    while not data.endswith(b"\r\n"):
        chunk = sock.recv(4096)
        if not chunk:
            raise SystemExit("edge closed the connection")
        data += chunk
    text = data.decode("latin1").strip()
    if not text.startswith(prefix):
        raise SystemExit(f"expected {prefix}, got: {text}")
    return text

expect("220")
for line, code in [
    (b"EHLO storage-proof.invalid\r\n", "250"),
    (b"MAIL FROM:<sut@example.net>\r\n", "250"),
    (f"RCPT TO:<{recipient}>\r\n".encode(), "250"),
    (b"DATA\r\n", "354"),
]:
    sock.sendall(line)
    expect(code)
sock.sendall(message + b".\r\n")
accepted = expect("250")
print(f"  edge reply {accepted}")
sock.sendall(b"QUIT\r\n")
PY

# --- wait for the relay to actually complete ---------------------------------
# Asserting "nothing was stored" before the relay has run would pass for the
# wrong reason. Wait for the queue to drain instead of sleeping blindly.
echo "  waiting for the edge queue to drain..."
drained=false
for _ in $(seq 1 60); do
  queued="$("${COMPOSE[@]}" exec -T mail-edge sh -c 'postqueue -p 2>/dev/null | grep -cE "^[0-9A-F]" || true' 2>/dev/null | tr -d ' \r\n')"
  if [[ "${queued:-0}" == "0" ]]; then
    drained=true
    break
  fi
  sleep 1
done
[[ "$drained" == true ]] || { echo "the edge queue did not drain; the relay never completed" >&2; exit 1; }
# The relay is asynchronous on the ingestion side too: give the delivery a
# bounded moment to be processed (and discarded) before looking for traces.
sleep 3

fail=0
report() {
  if [[ -z "$2" ]]; then
    printf '  ok   %s\n' "$1"
  else
    printf '  FAIL %s -> %s\n' "$1" "$2" >&2
    fail=1
  fi
}

# --- PostgreSQL --------------------------------------------------------------
# Every table that could plausibly retain content, not just `message`.
psql() { "${COMPOSE[@]}" exec -T postgres psql -U "${TESTINBOX_DB_USER:?}" -d "${TESTINBOX_DB_NAME:?}" -tAc "$1"; }

report "no message row mentions the marker" \
  "$(psql "SELECT count(*) FROM message WHERE envelope_to LIKE '%${UNKNOWN%%@*}%';" | tr -d ' \n' | grep -v '^0$' || true)"
report "no inbox was created for the unknown recipient" \
  "$(psql "SELECT count(*) FROM inbox WHERE address = '${UNKNOWN}';" | tr -d ' \n' | grep -v '^0$' || true)"
report "no parsed content carries the marker" \
  "$(psql "SELECT count(*) FROM message WHERE subject LIKE '%${MARKER}%' OR text_body LIKE '%${MARKER}%' OR html_body LIKE '%${MARKER}%';" 2>/dev/null | tr -d ' \n' | grep -v '^0$' || true)"
report "no attachment row was created for it" \
  "$(psql "SELECT count(*) FROM attachment a JOIN message m ON m.id = a.message_id WHERE m.envelope_to LIKE '%${UNKNOWN%%@*}%';" 2>/dev/null | tr -d ' \n' | grep -v '^0$' || true)"

# --- MinIO -------------------------------------------------------------------
# Raw MIME is written BEFORE the database row (ADR-005), so an object with no
# row is exactly the residue this proof exists to exclude. Scan object bodies,
# not just keys: a discarded message would not be keyed by anything guessable.
objects_with_marker="$(
  "${COMPOSE[@]}" exec -T minio sh -c '
    mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1 || exit 0
    mc find "local/'"${TESTINBOX_S3_BUCKET:?}"'" --exec "mc cat {}" 2>/dev/null | grep -c "'"$MARKER"'" || true
  ' 2>/dev/null | tr -d ' \r\n' || echo 0
)"
report "no object in the raw bucket contains the marker" \
  "$(echo "${objects_with_marker:-0}" | grep -v '^0$' || true)"

echo "----"
if (( fail )); then
  echo "ADR-025 storage proof FAILED: content from an unknown recipient was retained" >&2
  exit 1
fi
echo "ADR-025 storage proof: the relayed unknown-recipient message left no row and no object"
