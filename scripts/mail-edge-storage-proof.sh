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

# --- helpers ------------------------------------------------------------------
# Deliberately not the SDK: this is an unauthenticated sender on the wire, which
# is what a real unknown-recipient delivery looks like.
send_via_edge() {
python3 - "$EDGE_PORT" "$1" "$2" <<'PY'
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
}

# Asserting "nothing was stored" before the relay has run would pass for the
# wrong reason, so this waits on the queue rather than sleeping. It fails CLOSED:
# an exec failure returns no count, and an unreadable queue must never be
# mistaken for an empty one.
wait_for_drain() {
  local queued
  for _ in $(seq 1 60); do
    queued="$("${COMPOSE[@]}" exec -T mail-edge sh -c 'postqueue -p 2>/dev/null | grep -cE "^[0-9A-F]" || true' 2>/dev/null | tr -d ' \r\n')"
    if [[ -z "$queued" ]]; then
      echo "  the edge queue could not be read; refusing to treat that as drained" >&2
      return 1
    fi
    [[ "$queued" == "0" ]] && return 0
    sleep 1
  done
  echo "  the edge queue did not drain; the relay never completed" >&2
  return 1
}

echo "  marker    $MARKER"
echo "  recipient $UNKNOWN (syntactically valid, resolves to no inbox)"
send_via_edge "$UNKNOWN" "$MARKER"
echo "  waiting for the edge queue to drain..."
wait_for_drain || exit 1
# The relay is asynchronous on the ingestion side too: give the delivery a
# bounded moment to be processed (and discarded) before looking for traces.
sleep 3

fail=0
ok()  { printf '  ok   %s\n' "$1"; }
bad() { printf '  FAIL %s\n' "$1" >&2; fail=1; }

# --- PostgreSQL ---------------------------------------------------------------
# FAIL CLOSED. Every probe here previously swallowed errors: psql writes to
# stderr, stdout comes back empty, and "no rows matched" is indistinguishable
# from "the query never ran". A renamed column or a wrong database name turned
# the whole ADR-025 storage half green.
psql_count() {
  local sql="$1" out status
  out="$("${COMPOSE[@]}" exec -T postgres psql -U "${TESTINBOX_DB_USER:?}" -d "${TESTINBOX_DB_NAME:?}" -tAc "$sql" 2>&1)"
  status=$?
  out="$(tr -d ' \r\n' <<<"$out")"
  if (( status != 0 )) || ! [[ "$out" =~ ^[0-9]+$ ]]; then
    echo "QUERY-FAILED: ${out:-exit $status}"
    return 1
  fi
  echo "$out"
}

assert_absent_pg() {
  local label="$1" sql="$2" count
  if ! count="$(psql_count "$sql")"; then
    bad "$label — the query itself failed, so absence proves nothing: $count"
    return
  fi
  [[ "$count" == "0" ]] && ok "$label" || bad "$label (found $count)"
}

# --- MinIO --------------------------------------------------------------------
# Also fail closed: `mc alias set … || exit 0` previously turned a missing
# client, a renamed bucket or an auth failure into "zero objects", reported ok.
minio_marker_hits() {
  local marker="$1" out status
  # The scan runs mc INSIDE the container and greps on the HOST. The minio image
  # ships sh, cat and mc but no grep and no awk, so an in-container pipeline
  # fails with "grep: command not found" — which the previous fail-open version
  # reported as "zero objects", i.e. as a pass. The positive control caught that
  # on its first run; without it this assertion had never once executed.
  out="$("${COMPOSE[@]}" exec -T minio sh -c '
    set -e
    mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc find "local/'"${TESTINBOX_S3_BUCKET:?}"'" --exec "mc cat {}"
  ' 2>/dev/null | grep -c "$marker" || true)"
  status="${PIPESTATUS[0]:-0}"
  out="$(tr -d ' \r\n' <<<"$out")"
  # grep -c prints 0 and exits 1 when nothing matches, which is a legitimate
  # result; only a failure of the mc side means the scan did not run.
  if (( status != 0 )) || ! [[ "$out" =~ ^[0-9]+$ ]]; then
    echo "SCAN-FAILED: mc exited $status"
    return 1
  fi
  echo "$out"
}

# --- the positive control -----------------------------------------------------
# Before trusting these probes to find NOTHING for an unknown recipient, prove
# they can find SOMETHING for a known one. Without this the strongest ADR-025
# gate is indistinguishable from a gate that examines nothing at all.
echo
echo "  positive control: the same probes must FIND a message that was stored"
CONTROL_MARKER="ADR025CTL-$(head -c 400 /dev/urandom | LC_ALL=C tr -dc 'A-Z0-9' | cut -c1-16)"
CONTROL_LOCAL="ctl-$(head -c 400 /dev/urandom | LC_ALL=C tr -dc 'a-z0-9' | cut -c1-10)"
CONTROL_ADDR="${CONTROL_LOCAL}@${MAIL_DOMAIN}"
CONTROL_INBOX="$(
  curl -fsS --cacert "${TESTINBOX_TLS_DIR:?}/ca.pem" \
    -X POST "https://localhost:${REHEARSAL_HTTPS_PORT:-8443}/v1/inboxes" \
    -H "Authorization: Bearer ${TESTINBOX_EDGE_API_KEY:?}" \
    -H 'content-type: application/json' -d '{"ttlSeconds":600}'
)"
CONTROL_ADDR="$(sed -n 's/.*"address":"\([^"]*\)".*/\1/p' <<<"$CONTROL_INBOX")"
CONTROL_ID="$(sed -n 's/.*"id":"\([^"]*\)".*/\1/p' <<<"$CONTROL_INBOX")"
[[ -n "$CONTROL_ADDR" ]] || { echo "could not provision the control inbox" >&2; exit 1; }
send_via_edge "$CONTROL_ADDR" "$CONTROL_MARKER"
wait_for_drain
sleep 3

CTL_ROWS="$(psql_count "SELECT count(*) FROM message WHERE subject LIKE '%${CONTROL_MARKER}%';")" || {
  echo "  FAIL the control query failed: $CTL_ROWS" >&2; exit 1; }
[[ "$CTL_ROWS" == "1" ]] && ok "the Postgres probe finds a stored message (control)" \
  || bad "the Postgres probe did NOT find a message that was definitely stored (found $CTL_ROWS) — every absence assertion below is worthless"

CTL_OBJ="$(minio_marker_hits "$CONTROL_MARKER")" || {
  echo "  FAIL the control object scan failed: $CTL_OBJ" >&2; exit 1; }
(( CTL_OBJ >= 1 )) && ok "the MinIO probe finds the stored raw object (control)" \
  || bad "the MinIO probe did NOT find an object that was definitely stored — the object-storage assertion below is worthless"

curl -fsS --cacert "${TESTINBOX_TLS_DIR}/ca.pem" -X DELETE \
  -H "Authorization: Bearer ${TESTINBOX_EDGE_API_KEY}" \
  "https://localhost:${REHEARSAL_HTTPS_PORT:-8443}/v1/inboxes/${CONTROL_ID}" >/dev/null 2>&1 || true

# --- the assertions that matter -----------------------------------------------
echo
echo "  the unknown recipient must have left nothing"
assert_absent_pg "no message row mentions the unknown recipient" \
  "SELECT count(*) FROM message WHERE envelope_to LIKE '%${UNKNOWN%%@*}%';"
assert_absent_pg "no inbox was created for the unknown recipient" \
  "SELECT count(*) FROM inbox WHERE address = '${UNKNOWN}';"
assert_absent_pg "no parsed content carries the marker" \
  "SELECT count(*) FROM message WHERE subject LIKE '%${MARKER}%' OR text_body LIKE '%${MARKER}%' OR html_body LIKE '%${MARKER}%';"
# Attachments are keyed by file_name and object_key; a discarded message would
# leave an orphan in either. Joining through `message` would be circular — the
# assertion above already proves no such message row exists — so this looks at
# the attachment table on its own terms.
assert_absent_pg "no attachment row carries the marker" \
  "SELECT count(*) FROM attachment WHERE file_name LIKE '%${MARKER}%' OR object_key LIKE '%${MARKER}%';"

OBJ_HITS="$(minio_marker_hits "$MARKER")" || {
  bad "the object-storage scan failed, so absence proves nothing: $OBJ_HITS"
  OBJ_HITS="scan-failed"
}
[[ "$OBJ_HITS" == "0" ]] && ok "no object in the raw bucket contains the marker" \
  || { [[ "$OBJ_HITS" == "scan-failed" ]] || bad "an object in the raw bucket contains the marker ($OBJ_HITS)"; }

echo "----"
if (( fail )); then
  echo "ADR-025 storage proof FAILED: content from an unknown recipient was retained" >&2
  exit 1
fi
echo "ADR-025 storage proof: the relayed unknown-recipient message left no row and no object"
