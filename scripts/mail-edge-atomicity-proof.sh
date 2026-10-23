#!/usr/bin/env bash
# ADR-026, the part a network client cannot see: one SMTP DATA transaction
# addressed to several recipients must reach ingestion as ONE inbound event.
#
# The edge suite asserts that both recipients end up with a message. That is
# necessary and not sufficient: two SEPARATE downstream deliveries also put a
# message in each inbox, so the assertion passes just as happily on a split —
# which is the exact failure `relay_destination_recipient_limit` exists to
# prevent. Final state cannot distinguish them; only the count of inbound events
# can.
#
# `testinbox_smtp_accept_total` counts accepted DATA transactions — the code
# that increments it says so explicitly ("this counter measures accepted
# TRANSACTIONS, not delivered mail"). A delta of exactly 1 across a
# two-recipient send is the proof.
#
# Run from the rehearsal with the stack up and COMPOSE_ENV_FILES set.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose
  -f "$REPO_ROOT/deploy/staging/compose.yaml"
  -f "$REPO_ROOT/deploy/staging/compose.data.yaml"
  -f "$REPO_ROOT/deploy/staging/compose.mail-edge.yaml")

EDGE_PORT="${TESTINBOX_EDGE_SMTP_PORT:-2526}"
API="https://localhost:${REHEARSAL_HTTPS_PORT:-8443}"
KEY="${TESTINBOX_EDGE_API_KEY:?set by the rehearsal}"
CA="${TESTINBOX_TLS_DIR:?}/ca.pem"
MGMT_PORT="${TESTINBOX_MANAGEMENT_PORT:-9091}"

fail=0
ok()  { printf '  ok   %s\n' "$1"; }
bad() { printf '  FAIL %s\n' "$1" >&2; fail=1; }
api() { curl -fsS --cacert "$CA" -H "Authorization: Bearer $KEY" "$@"; }

# Reads the accepted-transaction counter from the deployed gateway. Fails CLOSED:
# an unreadable counter must never be mistaken for "no transactions", which would
# make the delta assertion below pass for the wrong reason.
accept_total() {
  local raw value
  raw="$("${COMPOSE[@]}" exec -T ingestion wget -qO- "http://127.0.0.1:${MGMT_PORT}/actuator/prometheus" 2>/dev/null || true)"
  [[ -n "$raw" ]] || { echo "SCRAPE-FAILED"; return 1; }
  # The counter may legitimately be absent before the first accepted delivery.
  value="$(awk '/^testinbox_smtp_accept_total(\{|[[:space:]])/ { print $NF; exit }' <<<"$raw")"
  [[ -n "$value" ]] || value=0
  # Micrometer renders counters as floats.
  printf '%.0f\n' "$value"
}

echo "--- ADR-026: one DATA with two recipients is ONE inbound event ---"

A_JSON="$(api -X POST "$API/v1/inboxes" -H 'content-type: application/json' -d '{"ttlSeconds":600}')"
B_JSON="$(api -X POST "$API/v1/inboxes" -H 'content-type: application/json' -d '{"ttlSeconds":600}')"
A_ADDR="$(sed -n 's/.*"address":"\([^"]*\)".*/\1/p' <<<"$A_JSON")"
B_ADDR="$(sed -n 's/.*"address":"\([^"]*\)".*/\1/p' <<<"$B_JSON")"
A_ID="$(sed -n 's/.*"id":"\([^"]*\)".*/\1/p' <<<"$A_JSON")"
B_ID="$(sed -n 's/.*"id":"\([^"]*\)".*/\1/p' <<<"$B_JSON")"
[[ -n "$A_ADDR" && -n "$B_ADDR" ]] || { echo "could not provision two inboxes" >&2; exit 1; }
echo "  recipients: $A_ADDR"
echo "              $B_ADDR"

BEFORE="$(accept_total)" || { echo "  could not read the accepted-transaction counter" >&2; exit 1; }
echo "  testinbox_smtp_accept_total before: $BEFORE"

SUBJECT="atomic-$(date +%s)"
python3 - "$EDGE_PORT" "$A_ADDR" "$B_ADDR" "$SUBJECT" <<'PY'
import socket, sys

port, a, b, subject = int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
body = (f"From: sut@example.net\r\nTo: {a}, {b}\r\nSubject: {subject}\r\n\r\n{subject}\r\n").encode()
s = socket.create_connection(("127.0.0.1", port), timeout=30); s.settimeout(30)

def expect(prefix):
    d = b""
    while not d.endswith(b"\r\n"):
        c = s.recv(4096)
        if not c:
            raise SystemExit("edge closed the connection")
        d += c
    t = d.decode("latin1").strip()
    if not t.startswith(prefix):
        raise SystemExit(f"expected {prefix}, got {t}")
    return t

expect("220")
# ONE transaction, TWO RCPTs, ONE DATA. If the sender split these the test would
# be measuring its own behaviour rather than the relay's.
for line, code in [
    (b"EHLO atomicity.invalid\r\n", "250"),
    (b"MAIL FROM:<sut@example.net>\r\n", "250"),
    (f"RCPT TO:<{a}>\r\n".encode(), "250"),
    (f"RCPT TO:<{b}>\r\n".encode(), "250"),
    (b"DATA\r\n", "354"),
]:
    s.sendall(line); expect(code)
s.sendall(body + b".\r\n")
print(f"  edge reply {expect('250')}")
s.sendall(b"QUIT\r\n")
PY

# Wait for the relay to complete before reading the counter, or the delta is
# simply "not yet".
for _ in $(seq 1 60); do
  queued="$("${COMPOSE[@]}" exec -T mail-edge sh -c 'postqueue -p 2>/dev/null | grep -cE "^[0-9A-F]" || true' 2>/dev/null | tr -d ' \r\n')"
  [[ -n "$queued" && "$queued" == "0" ]] && break
  sleep 1
done
sleep 3

AFTER="$(accept_total)" || { echo "  could not read the accepted-transaction counter" >&2; exit 1; }
DELTA=$(( AFTER - BEFORE ))
echo "  testinbox_smtp_accept_total after:  $AFTER (delta $DELTA)"

if (( DELTA == 1 )); then
  ok "the two-recipient send reached ingestion as exactly ONE inbound event"
elif (( DELTA > 1 )); then
  bad "the relay SPLIT one DATA into $DELTA downstream transactions — ADR-026 atomicity is broken (check relay_destination_recipient_limit)"
else
  bad "no inbound transaction was observed (delta $DELTA); the message never reached ingestion"
fi

# And both recipients still received it — a single event that only delivered to
# one of them would be a different, equally serious failure.
for pair in "$A_ID:A" "$B_ID:B"; do
  id="${pair%%:*}"; label="${pair##*:}"
  count="$(api "$API/v1/inboxes/$id/messages" | grep -c "\"subject\":\"$SUBJECT\"" || true)"
  [[ "$count" == "1" ]] && ok "recipient $label received it exactly once" \
    || bad "recipient $label has $count messages matching the subject"
done

for id in "$A_ID" "$B_ID"; do
  api -X DELETE "$API/v1/inboxes/$id" >/dev/null 2>&1 || true
done

echo "----"
(( fail == 0 )) || { echo "ADR-026 atomicity proof FAILED" >&2; exit 1; }
echo "ADR-026 atomicity proof: one DATA, two recipients, one inbound event"
