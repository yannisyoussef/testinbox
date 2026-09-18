#!/usr/bin/env bash
# TI-005 §12 and §17 — the two proofs that need control of the ingestion process
# rather than only a network client, which is why they live here and not in the
# synthetic suite.
#
#   A. transient failure -> Postfix queues -> ingestion recovers -> delivered once
#   B. queue lifetime expires -> discarded with NO DSN, and an operational signal
#
# STOP/START, NEVER RECREATE. The relay target is resolved to an address when the
# edge renders its configuration, so recreating the ingestion container could
# hand it a new IP and the retry test would fail on service-discovery churn while
# looking like a queue-semantics failure. `compose stop` and `compose start`
# preserve the container and its address; this script asserts that rather than
# assuming it.
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
API="https://localhost:${REHEARSAL_HTTPS_PORT:-8443}"
# Not the bootstrap credential: it retires itself during the product
# synthetics, which run before this.
KEY="${TESTINBOX_EDGE_API_KEY:?set by the rehearsal}"
CA="${TESTINBOX_TLS_DIR:?}/ca.pem"

fail=0
ok()   { printf '  ok   %s\n' "$1"; }
bad()  { printf '  FAIL %s\n' "$1" >&2; fail=1; }

ingestion_ip() {
  "${COMPOSE[@]}" exec -T mail-edge sh -c \
    "postconf -h transport_maps >/dev/null; grep -oE 'relay:\[[0-9.]+\]' /etc/postfix/transport | head -1" 2>/dev/null
}

queue_count() {
  "${COMPOSE[@]}" exec -T mail-edge sh -c 'postqueue -p 2>/dev/null | grep -cE "^[0-9A-F]" || true' 2>/dev/null | tr -d ' \r\n'
}

send_to() {
  local recipient="$1" subject="$2"
  python3 - "$EDGE_PORT" "$recipient" "$subject" <<'PY'
import socket, sys
port, recipient, subject = int(sys.argv[1]), sys.argv[2], sys.argv[3]
body = (f"From: sut@example.net\r\nTo: {recipient}\r\nSubject: {subject}\r\n\r\n{subject}\r\n").encode()
s = socket.create_connection(("127.0.0.1", port), timeout=30); s.settimeout(30)
def expect(p):
    d = b""
    while not d.endswith(b"\r\n"):
        c = s.recv(4096)
        if not c: raise SystemExit("closed")
        d += c
    t = d.decode("latin1").strip()
    if not t.startswith(p): raise SystemExit(f"expected {p}, got {t}")
    return t
expect("220")
for line, code in [(b"EHLO queue-proof.invalid\r\n","250"),(b"MAIL FROM:<sut@example.net>\r\n","250"),
                   (f"RCPT TO:<{recipient}>\r\n".encode(),"250"),(b"DATA\r\n","354")]:
    s.sendall(line); expect(code)
s.sendall(body + b".\r\n")
print(expect("250"))
s.sendall(b"QUIT\r\n")
PY
}

api() { curl -fsS --cacert "$CA" -H "Authorization: Bearer $KEY" "$@"; }

echo "--- A. transient downstream failure produces a queue and a retry ---"
INBOX_JSON="$(api -X POST "$API/v1/inboxes" -H 'content-type: application/json' -d '{"ttlSeconds":900}')"
INBOX_ID="$(printf '%s' "$INBOX_JSON" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"
ADDRESS="$(printf '%s' "$INBOX_JSON" | sed -n 's/.*"address":"\([^"]*\)".*/\1/p')"
test -n "$ADDRESS" || { echo "could not provision an inbox" >&2; exit 1; }
echo "  inbox $ADDRESS"

IP_BEFORE="$(ingestion_ip)"
echo "  relay target before: ${IP_BEFORE:-<unresolved>}"

# Stop, do not remove: the container keeps its address, so what the retry test
# exercises is Postfix's queue behaviour and not Docker's address allocation.
"${COMPOSE[@]}" stop ingestion >/dev/null 2>&1
SUBJECT="retry-$(date +%s)"
send_to "$ADDRESS" "$SUBJECT" | sed 's/^/  edge: /'

# The message must be IN the queue while ingestion is down — otherwise the test
# proves nothing about queueing, only about eventual delivery.
queued=false
for _ in $(seq 1 30); do
  [[ "$(queue_count)" -ge 1 ]] && { queued=true; break; }
  sleep 1
done
[[ "$queued" == true ]] && ok "the message is held in the edge queue while ingestion is down" \
  || bad "the message never appeared in the queue"

"${COMPOSE[@]}" start ingestion >/dev/null 2>&1
for _ in $(seq 1 60); do
  "${COMPOSE[@]}" ps ingestion --format '{{.Status}}' 2>/dev/null | grep -q healthy && break
  sleep 2
done

IP_AFTER="$(ingestion_ip)"
[[ "$IP_BEFORE" == "$IP_AFTER" ]] && ok "the resolved relay target is unchanged across the restart (${IP_AFTER:-?})" \
  || bad "the relay target moved ($IP_BEFORE -> $IP_AFTER): a stale address would masquerade as a retry failure"

# Postfix retries on its own schedule; the CI profile shortens it so this is
# seconds rather than minutes. Flushing would prove the queue can be drained on
# demand, not that Postfix retries — so this waits for the retry.
delivered=false
for _ in $(seq 1 90); do
  if [[ "$(queue_count)" == "0" ]]; then delivered=true; break; fi
  sleep 1
done
[[ "$delivered" == true ]] && ok "Postfix retried on its own and the queue drained" \
  || bad "the queue never drained after ingestion recovered"

# Exactly one delivery: a retry must not produce two rows, and dedup must not
# have suppressed the first (ADR-019 — neither Message-ID nor content hash).
sleep 3
COUNT="$(api "$API/v1/inboxes/$INBOX_ID/messages" | grep -o '"id":"' | wc -l | tr -d ' ')"
[[ "$COUNT" == "1" ]] && ok "the retried message was delivered exactly once" \
  || bad "expected exactly one delivered message, found $COUNT"
api -X DELETE "$API/v1/inboxes/$INBOX_ID" >/dev/null 2>&1 || true

echo
echo "--- B. queue expiry discards with no DSN and no backscatter ---"
# The edge is inbound-only. A message that can never be delivered must expire
# and be discarded, never returned to the envelope sender: a DSN would be
# backscatter to an unverified address, would need outbound 25 which is blocked,
# and would leak downstream disposition through the uniform 250.
"${COMPOSE[@]}" stop ingestion >/dev/null 2>&1
EXPIRE_SUBJECT="expire-$(date +%s)"
send_to "nobody-expire@${MAIL_DOMAIN}" "$EXPIRE_SUBJECT" | sed 's/^/  edge: /'

LIFETIME="$("${COMPOSE[@]}" exec -T mail-edge postconf -h maximal_queue_lifetime 2>/dev/null | tr -d ' \r\n')"
echo "  maximal_queue_lifetime = $LIFETIME (CI profile; production is asserted separately)"

expired=false
for _ in $(seq 1 120); do
  if [[ "$(queue_count)" == "0" ]]; then expired=true; break; fi
  sleep 1
done
[[ "$expired" == true ]] && ok "the undeliverable message left the queue" \
  || bad "the message never expired out of the queue"

LOG="$("${COMPOSE[@]}" exec -T mail-edge sh -c 'cat /var/log/mail.log' 2>/dev/null || true)"

# The operational signal: expiry must be observable, or a silently vanishing
# queue is indistinguishable from a working one.
grep -q "status=expired" <<<"$LOG" && ok "queue expiry emits an observable operational signal (status=expired)" \
  || bad "no status=expired signal was logged; expiry would be invisible to operators"

# The hard invariant: the bounce Postfix generates must never LEAVE.
#
# Read the log carefully here, because its vocabulary is misleading. On expiry
# qmgr logs "returned to sender", and the resulting bounce is then logged by
# postfix/discard as `status=sent`. That "sent" means DROPPED BY THE DISCARD
# TRANSPORT, not transmitted: `relay=none` is the part that says nothing went
# anywhere. Asserting merely that no line mentions the sender would fail on
# correct behaviour, which is what an earlier version of this check did.
#
# So the question is not "is the sender mentioned" but "did anything addressed
# to the sender's domain reach a real relay".
SENDER_DOMAIN="example.net"
LEAKED="$(grep -E "postfix/(smtp|lmtp)\[" <<<"$LOG" \
  | grep -E "to=<[^>]*@${SENDER_DOMAIN}>" \
  | grep -vE "relay=none" || true)"
if [[ -n "$LEAKED" ]]; then
  bad "a DSN was transmitted to the envelope sender — this is backscatter:"
  sed 's/^/       /' <<<"$LEAKED" >&2
else
  ok "no bounce was transmitted to the envelope sender (no DSN, no backscatter)"
fi

# And positively: the bounce must have been handled by the discard transport,
# not merely absent from the smtp log. "Nothing matched my pattern" is also what
# a typo produces.
if grep -qE "postfix/discard\[[0-9]+\]: .*to=<[^>]*@${SENDER_DOMAIN}>.*relay=none" <<<"$LOG"; then
  ok "the expiry bounce was dropped by the discard transport before it could leave"
else
  bad "no discard-transport record for the expiry bounce: the structural no-DSN mechanism did not engage"
fi

# Destinations actually used. Three are legitimate: `none` (nothing was
# transmitted), the configured ingestion target, and `local` (postmaster@, whose
# post-acceptance divert is the whole point of the transport map). Anything else
# means the edge talked to something it should not know about.
RELAY_TARGETS="$(grep -oE 'relay=[^,]+' <<<"$LOG" | sed 's/^relay=//' | sort -u)"
INGESTION_ADDR="$(sed -E 's/.*\[([0-9.]+)\].*/\1/' <<<"$IP_BEFORE")"
UNEXPECTED=""
while IFS= read -r target; do
  [[ -z "$target" ]] && continue
  case "$target" in
    none|local) ;;
    *"$INGESTION_ADDR"*) ;;
    *) UNEXPECTED="$UNEXPECTED $target" ;;
  esac
done <<<"$RELAY_TARGETS"
if [[ -n "$UNEXPECTED" ]]; then
  bad "the edge used an unexpected destination:$UNEXPECTED"
else
  ok "the edge used only expected destinations (none, local, ingestion at $INGESTION_ADDR)"
fi

"${COMPOSE[@]}" start ingestion >/dev/null 2>&1
for _ in $(seq 1 60); do
  "${COMPOSE[@]}" ps ingestion --format '{{.Status}}' 2>/dev/null | grep -q healthy && break
  sleep 2
done

echo "----"
(( fail == 0 )) || { echo "mail-edge queue proofs FAILED" >&2; exit 1; }
echo "mail-edge queue proofs: retry delivers exactly once; expiry discards with no DSN"
