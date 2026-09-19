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

# What the edge will actually dial, read from its transport map.
relay_target_in_map() {
  local out
  out="$("${COMPOSE[@]}" exec -T mail-edge sh -c \
    "grep -oE 'relay:\[[0-9.]+\]' /etc/postfix/transport | head -1" 2>/dev/null | tr -d ' \r\n')"
  [[ -n "$out" ]] || return 1
  sed -E 's/.*\[([0-9.]+)\].*/\1/' <<<"$out"
}

# Where ingestion ACTUALLY is right now, from the runtime rather than from the
# file the edge rendered. Comparing the transport map against itself is true by
# construction and would report ok even if the container had moved — which is
# precisely the failure the stability assertion is supposed to catch.
ingestion_runtime_ip() {
  local cid out
  cid="$("${COMPOSE[@]}" ps -q ingestion 2>/dev/null | head -1)"
  [[ -n "$cid" ]] || return 1
  out="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' "$cid" 2>/dev/null | awk '{print $1}')"
  [[ -n "$out" ]] || return 1
  echo "$out"
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

MAP_TARGET="$(relay_target_in_map)" || { echo "could not read the edge's relay target" >&2; exit 1; }
RUNTIME_IP="$(ingestion_runtime_ip)" || { echo "could not read ingestion's runtime address" >&2; exit 1; }
echo "  relay target in the transport map: $MAP_TARGET"
echo "  ingestion's actual address:        $RUNTIME_IP"
[[ "$MAP_TARGET" == "$RUNTIME_IP" ]] && ok "the rendered relay target matches where ingestion actually is" \
  || bad "the edge would dial $MAP_TARGET but ingestion is at $RUNTIME_IP"

# Stop, do not remove: the container keeps its address, so what the retry test
# exercises is Postfix's queue behaviour and not Docker's address allocation.
"${COMPOSE[@]}" stop ingestion >/dev/null 2>&1
LIFETIME_A="$("${COMPOSE[@]}" exec -T mail-edge postconf -h maximal_queue_lifetime 2>/dev/null | tr -d ' \r\n')"
SUBJECT="retry-$(date +%s)"
SEND_REPLY="$(send_to "$ADDRESS" "$SUBJECT")"
echo "  edge: $SEND_REPLY"
# The queue id ties every later assertion to THIS message. A bare queue count
# would be satisfied by anything left over from the preceding suites.
QUEUE_ID="$(sed -nE 's/.*queued as ([0-9A-F]+).*/\1/p' <<<"$SEND_REPLY")"
[[ -n "$QUEUE_ID" ]] || { echo "could not read the queue id from the edge reply" >&2; exit 1; }
echo "  queue id: $QUEUE_ID (lifetime ${LIFETIME_A})"

# The message must be IN the queue while ingestion is down — otherwise the test
# proves nothing about queueing, only about eventual delivery.
queued=false
for _ in $(seq 1 30); do
  if "${COMPOSE[@]}" exec -T mail-edge sh -c "postqueue -p 2>/dev/null | grep -q '^$QUEUE_ID'" 2>/dev/null; then
    queued=true; break
  fi
  sleep 1
done
[[ "$queued" == true ]] && ok "THIS message ($QUEUE_ID) is held in the edge queue while ingestion is down" \
  || bad "message $QUEUE_ID never appeared in the queue"

"${COMPOSE[@]}" start ingestion >/dev/null 2>&1
recovered=false
for _ in $(seq 1 60); do
  "${COMPOSE[@]}" ps ingestion --format '{{.Status}}' 2>/dev/null | grep -q healthy && { recovered=true; break; }
  sleep 2
done
# Asserted rather than assumed: proceeding here on an unhealthy gateway would
# surface 90s later as "the queue never drained", blaming queue semantics for a
# startup failure.
[[ "$recovered" == true ]] && ok "ingestion recovered and reports healthy" \
  || { bad "ingestion did not become healthy after start; the retry result below would be meaningless"; }

# Compare against the RUNTIME address, not the file: stop/start is expected to
# preserve the container's address, and this is the assertion that proves it did
# rather than assuming it. If it ever moved, the retry below would fail on a
# stale address and look like a queue-semantics defect.
RUNTIME_AFTER="$(ingestion_runtime_ip)" || { bad "could not read ingestion's address after the restart"; RUNTIME_AFTER=""; }
[[ -n "$RUNTIME_AFTER" && "$RUNTIME_AFTER" == "$MAP_TARGET" ]] \
  && ok "ingestion kept its address across stop/start ($RUNTIME_AFTER), so the rendered target is still valid" \
  || bad "ingestion moved to ${RUNTIME_AFTER:-<unknown>} but the edge still dials $MAP_TARGET: a stale address would masquerade as a retry failure"

# Postfix retries on its own schedule; the CI profile shortens it so this is
# seconds rather than minutes. Flushing would prove the queue can be drained on
# demand, not that Postfix retries — so this waits for the retry.
#
# Crucially it reads the OUTCOME from the log rather than inferring it from an
# empty queue. An expired message also empties the queue, so a bare drain check
# cannot tell "delivered" from "gave up" — and would report the timing failure
# as a missing-message product defect three lines later.
outcome=""
for _ in $(seq 1 90); do
  log="$("${COMPOSE[@]}" exec -T mail-edge sh -c 'cat /var/log/mail.log' 2>/dev/null || true)"
  if grep -qE "$QUEUE_ID.*status=sent" <<<"$log"; then outcome="sent"; break; fi
  if grep -qE "$QUEUE_ID.*status=expired" <<<"$log"; then outcome="expired"; break; fi
  sleep 1
done
case "$outcome" in
  sent)    ok "Postfix retried on its own and delivered (status=sent for $QUEUE_ID)" ;;
  expired) bad "the message EXPIRED before ingestion recovered. This is a test-environment timing failure, not a product defect: maximal_queue_lifetime (${LIFETIME_A}) was shorter than the restart took. Raise EDGE_CI_QUEUE_LIFETIME." ;;
  *)       bad "no terminal status for $QUEUE_ID after 90s — neither delivered nor expired" ;;
esac

# Exactly one delivery: a retry must not produce two rows, and dedup must not
# have suppressed the first (ADR-019 — neither Message-ID nor content hash).
sleep 3
# Counted by subject, not by a bare `"id":"` grep: AttachmentMeta also carries
# an id, so that shape silently double-counts the moment a fixture gains an
# attachment.
COUNT="$(api "$API/v1/inboxes/$INBOX_ID/messages" | grep -c "\"subject\":\"$SUBJECT\"" || true)"
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

# Shorten the lifetime for THIS proof only. One value cannot serve both parts:
# part A needs a message to outlive an ingestion restart, part B needs one to
# expire while the test is still watching. Sharing a single short value is what
# made part A race, and a single long one would make part B take minutes.
#
# Changed at runtime and restored afterwards, so the RENDERED contract — which
# the static gate checks against production — is untouched.
RESTORE_LIFETIME="$("${COMPOSE[@]}" exec -T mail-edge postconf -h maximal_queue_lifetime 2>/dev/null | tr -d ' \r\n')"
"${COMPOSE[@]}" exec -T mail-edge sh -c 'postconf -e "maximal_queue_lifetime = 10s" && postfix reload' >/dev/null 2>&1
LIFETIME="$("${COMPOSE[@]}" exec -T mail-edge postconf -h maximal_queue_lifetime 2>/dev/null | tr -d ' \r\n')"
[[ "$LIFETIME" == "10s" ]] && ok "queue lifetime shortened to $LIFETIME for the expiry proof (was $RESTORE_LIFETIME)" \
  || bad "could not shorten the queue lifetime for the expiry proof (got ${LIFETIME:-<unknown>})"

EXPIRE_SUBJECT="expire-$(date +%s)"
send_to "nobody-expire@${MAIL_DOMAIN}" "$EXPIRE_SUBJECT" | sed 's/^/  edge: /'

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
INGESTION_ADDR="$MAP_TARGET"
# An empty value here would make the glob below `**`, matching every
# destination including a real external MX — the allowlist would accept
# anything while appearing to check.
[[ -n "$INGESTION_ADDR" ]] || bad "the ingestion address is unknown, so the destination allowlist cannot be trusted"
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

# Restore the contract's lifetime: a --keep run, or anything else pointed at
# this edge afterwards, must not inherit the 10s value this proof needed.
"${COMPOSE[@]}" exec -T mail-edge sh -c "postconf -e 'maximal_queue_lifetime = ${RESTORE_LIFETIME}' && postfix reload" >/dev/null 2>&1 || true
RESTORED="$("${COMPOSE[@]}" exec -T mail-edge postconf -h maximal_queue_lifetime 2>/dev/null | tr -d ' \r\n')"
[[ "$RESTORED" == "$RESTORE_LIFETIME" ]] && ok "queue lifetime restored to $RESTORED" \
  || bad "queue lifetime was left at ${RESTORED:-<unknown>}, not the contract's $RESTORE_LIFETIME"

"${COMPOSE[@]}" start ingestion >/dev/null 2>&1
for _ in $(seq 1 60); do
  "${COMPOSE[@]}" ps ingestion --format '{{.Status}}' 2>/dev/null | grep -q healthy && break
  sleep 2
done

echo "----"
(( fail == 0 )) || { echo "mail-edge queue proofs FAILED" >&2; exit 1; }
echo "mail-edge queue proofs: retry delivers exactly once; expiry discards with no DSN"
