#!/usr/bin/env bash
# TI-005 §16 — the abuse ceilings, exercised rather than merely asserted.
#
# The static gate pins the production values; that proves they are written down,
# not that Postfix enforces them. These drive the edge to each ceiling and assert
# the SPECIFIC refusal, because "an SMTP error happened" is not evidence.
#
# NOT load testing. Each probe is the smallest interaction that crosses one
# ceiling by one: 51 recipients against a limit of 50, one connection more than
# 20, one connection more than the rate allows.
#
# FALSIFICATION IS BUILT IN. Every probe runs TWICE: once with the limit raised
# to the permissive CI value, where it must SUCCEED, and once at the production
# value, where it must be refused. The contrast is what proves the refusal is
# caused by the limit under test rather than by anything else in the topology —
# a test that only ever ran at the low value could be passing for any reason.
#
# The anvil limits are per client IP and the rehearsal has one sender, which is
# exactly why the CI profile raises them for the other suites. This script sets
# production values at runtime for its own probes and restores them afterwards,
# so the rendered contract the static gate checks is untouched.
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
CONTRACT="$REPO_ROOT/deploy/mail-edge/contract.py"

fail=0
ok()  { printf '  ok   %s\n' "$1"; }
bad() { printf '  FAIL %s\n' "$1" >&2; fail=1; }

PROD_RECIPIENTS="$("$CONTRACT" get production.smtpd_recipient_limit)"
PROD_CONNECTIONS="$("$CONTRACT" get production.smtpd_client_connection_count_limit)"
PROD_CONN_RATE="$("$CONTRACT" get production.smtpd_client_connection_rate_limit)"

# Restored at the end, and asserted — a later run against this edge must not
# inherit production ceilings that would make unrelated tests fail.
SAVED_RECIPIENTS="$("${COMPOSE[@]}" exec -T mail-edge postconf -h smtpd_recipient_limit 2>/dev/null | tr -d ' \r\n')"
SAVED_CONNECTIONS="$("${COMPOSE[@]}" exec -T mail-edge postconf -h smtpd_client_connection_count_limit 2>/dev/null | tr -d ' \r\n')"
SAVED_CONN_RATE="$("${COMPOSE[@]}" exec -T mail-edge postconf -h smtpd_client_connection_rate_limit 2>/dev/null | tr -d ' \r\n')"
for saved in "$SAVED_RECIPIENTS" "$SAVED_CONNECTIONS" "$SAVED_CONN_RATE"; do
  [[ "$saved" =~ ^[0-9]+$ ]] || { echo "could not read the edge's current ceilings; nothing below could be restored afterwards" >&2; exit 1; }
done

# Applies one set of ceilings and PROVES they took effect.
#
# The verification is not ceremony. `postconf -e` is fatal on a repeated `-e`
# flag (it takes several name=value operands after a single one), and an earlier
# version of this function both used the wrong form and sent its output to
# /dev/null. Every ceiling silently stayed at the rendered value, so each
# "control" ran against the same configuration as the probe it was supposed to
# falsify — the recipient probe then failed for the right-looking reason and the
# two connection probes passed nothing at all. A read-back is the only thing
# that distinguishes "the limit is enforced" from "the limit never moved".
set_limits() {
  local want_r="$1" want_c="$2" want_rate="$3" got_r got_c got_rate chatter
  # Captured rather than discarded: `postfix stop`/`start` write progress to
  # stderr, which is noise when it works and the only diagnosis when it does not.
  if ! chatter="$("${COMPOSE[@]}" exec -T mail-edge sh -c "
      set -e
      postconf -e 'smtpd_recipient_limit = $want_r' \
                  'smtpd_client_connection_count_limit = $want_c' \
                  'smtpd_client_connection_rate_limit = $want_rate'
      # anvil accumulates per client IP for anvil_rate_time_unit. Stopping and
      # starting discards that state so each probe begins from a known count
      # rather than inheriting the previous one's, and it is also what loads the
      # new values into every smtpd.
      postfix stop
      postfix start
    " 2>&1)"; then
    echo "  could not apply the abuse ceilings to the edge: $chatter" >&2
    return 1
  fi

  local up=1
  for _ in $(seq 1 30); do
    if "${COMPOSE[@]}" exec -T mail-edge sh -c 'printf "QUIT\r\n" | nc -w 3 127.0.0.1 25 2>/dev/null | head -1' 2>/dev/null | grep -q '^220'; then
      up=0; break
    fi
    sleep 1
  done
  (( up == 0 )) || { echo "  the edge did not answer 220 after a configuration change" >&2; return 1; }

  got_r="$("${COMPOSE[@]}" exec -T mail-edge postconf -h smtpd_recipient_limit 2>/dev/null | tr -d ' \r\n')"
  got_c="$("${COMPOSE[@]}" exec -T mail-edge postconf -h smtpd_client_connection_count_limit 2>/dev/null | tr -d ' \r\n')"
  got_rate="$("${COMPOSE[@]}" exec -T mail-edge postconf -h smtpd_client_connection_rate_limit 2>/dev/null | tr -d ' \r\n')"
  if [[ "$got_r" != "$want_r" || "$got_c" != "$want_c" || "$got_rate" != "$want_rate" ]]; then
    echo "  the edge did not adopt the requested ceilings: recipients=${got_r:-<unknown>} (want $want_r), connections=${got_c:-<unknown>} (want $want_c), rate=${got_rate:-<unknown>} (want $want_rate)" >&2
    return 1
  fi
  return 0
}

restore_limits() {
  set_limits "$SAVED_RECIPIENTS" "$SAVED_CONNECTIONS" "$SAVED_CONN_RATE" || true
}
trap restore_limits EXIT

# The reply code alone cannot tell the two anvil ceilings apart: Postfix answers
# BOTH with `421 4.7.0 ... too many connections from <ip>`. The maillog does
# distinguish them — "Connection concurrency limit exceeded" versus "Connection
# rate limit exceeded" — so each connection probe asserts the reply AND the
# warning that names the limit under test, and the controls assert that neither
# warning appeared at all.
maillog_mark() { "${COMPOSE[@]}" exec -T mail-edge sh -c 'wc -l < /var/log/mail.log' 2>/dev/null | tr -d ' \r\n'; }
maillog_since() {
  # Fails CLOSED. An unreadable mark would default to 0 and hand back the WHOLE
  # log, so a warning left by an EARLIER probe would satisfy the attribution
  # assertion of a later one. Empty output makes that assertion fail instead.
  local from="${1:-}"
  [[ "$from" =~ ^[0-9]+$ ]] || { echo "  could not mark the maillog; refusal attribution is not possible" >&2; return 0; }
  "${COMPOSE[@]}" exec -T mail-edge sh -c "tail -n +$(( from + 1 )) /var/log/mail.log" 2>/dev/null || true
}

# --- probe 1: recipients in one transaction ----------------------------------
# Returns the reply to the (limit+1)-th RCPT.
probe_recipients() {
  local limit="$1"
  python3 - "$EDGE_PORT" "$MAIL_DOMAIN" "$limit" <<'PY'
import socket, sys
port, domain, limit = int(sys.argv[1]), sys.argv[2], int(sys.argv[3])
s = socket.create_connection(("127.0.0.1", port), timeout=30); s.settimeout(30)

def reply():
    d = b""
    while not d.endswith(b"\r\n"):
        c = s.recv(4096)
        if not c: return "CLOSED"
        d += c
    return d.decode("latin1").strip().splitlines()[-1]

reply()
for line in (b"EHLO abuse.invalid\r\n", b"MAIL FROM:<sut@example.net>\r\n"):
    s.sendall(line); reply()
last = ""
for i in range(limit + 1):
    s.sendall(f"RCPT TO:<r{i}@{domain}>\r\n".encode())
    last = reply()
print(last)
s.sendall(b"QUIT\r\n")
PY
}

# --- probe 2: concurrent connections -----------------------------------------
# Holds `limit` connections open, then asks for one more.
probe_connections() {
  local limit="$1"
  python3 - "$EDGE_PORT" "$limit" <<'PY'
import socket, sys
port, limit = int(sys.argv[1]), int(sys.argv[2])
held = []

def banner(sock):
    sock.settimeout(10)
    d = b""
    try:
        while not d.endswith(b"\r\n"):
            c = sock.recv(4096)
            if not c: break
            d += c
    except Exception as exc:
        return f"ERROR {exc}"
    return d.decode("latin1").strip().splitlines()[-1] if d else "CLOSED"

try:
    for _ in range(limit):
        s = socket.create_connection(("127.0.0.1", port), timeout=10)
        banner(s)
        held.append(s)
    extra = socket.create_connection(("127.0.0.1", port), timeout=10)
    print(banner(extra))
    extra.close()
finally:
    for s in held:
        try: s.close()
        except Exception: pass
PY
}

# --- probe 3: connection rate ------------------------------------------------
# Sequential connect/close, so concurrency stays at 1 and only the RATE counter
# moves — otherwise this would be measuring the count limit again.
probe_connection_rate() {
  local limit="$1"
  python3 - "$EDGE_PORT" "$limit" <<'PY'
import socket, sys
port, limit = int(sys.argv[1]), int(sys.argv[2])

def once():
    s = socket.create_connection(("127.0.0.1", port), timeout=10)
    s.settimeout(10)
    d = b""
    try:
        while not d.endswith(b"\r\n"):
            c = s.recv(4096)
            if not c: break
            d += c
    finally:
        s.close()
    return d.decode("latin1").strip().splitlines()[-1] if d else "CLOSED"

last = ""
for _ in range(limit + 5):
    last = once()
    if last.startswith("421") or last.startswith("450"):
        break
print(last)
PY
}

echo "--- abuse ceilings: production values, with a permissive control ---"

# ===== recipients =============================================================
CI_RECIPIENTS=$(( PROD_RECIPIENTS + 20 ))
set_limits "$CI_RECIPIENTS" "$SAVED_CONNECTIONS" "$SAVED_CONN_RATE" \
  || { bad "the edge did not come back after a configuration change"; exit 1; }
CONTROL="$(probe_recipients "$PROD_RECIPIENTS" || true)"
if [[ "$CONTROL" == 250* ]]; then
  ok "control: with the ceiling raised, $(( PROD_RECIPIENTS + 1 )) recipients are accepted"
else
  bad "control failed — the probe could not deliver $(( PROD_RECIPIENTS + 1 )) recipients even with the ceiling raised (got: $CONTROL). The refusal below would not be attributable to the limit."
fi

set_limits "$PROD_RECIPIENTS" "$SAVED_CONNECTIONS" "$SAVED_CONN_RATE" \
  || { bad "the edge did not come back after a configuration change"; exit 1; }
REPLY="$(probe_recipients "$PROD_RECIPIENTS" || true)"
if [[ "$REPLY" == 452*"too many recipients"* ]]; then
  ok "smtpd_recipient_limit=$PROD_RECIPIENTS refuses recipient $(( PROD_RECIPIENTS + 1 )) with: $REPLY"
else
  bad "expected a 452 ... too many recipients refusal at $PROD_RECIPIENTS, got: $REPLY"
fi

# ===== concurrent connections =================================================
CI_CONNECTIONS=$(( PROD_CONNECTIONS + 20 ))
set_limits "$SAVED_RECIPIENTS" "$CI_CONNECTIONS" "$SAVED_CONN_RATE" \
  || { bad "the edge did not come back after a configuration change"; exit 1; }
MARK="$(maillog_mark)"
CONTROL="$(probe_connections "$PROD_CONNECTIONS" || true)"
CONTROL_LOG="$(maillog_since "$MARK")"
if [[ "$CONTROL" == 220* ]] && ! grep -q "Connection concurrency limit exceeded" <<<"$CONTROL_LOG"; then
  ok "control: with the ceiling raised, connection $(( PROD_CONNECTIONS + 1 )) is served and no concurrency warning is logged"
else
  bad "control failed — connection $(( PROD_CONNECTIONS + 1 )) was not served cleanly even with the ceiling raised (got: $CONTROL)"
fi

set_limits "$SAVED_RECIPIENTS" "$PROD_CONNECTIONS" "$SAVED_CONN_RATE" \
  || { bad "the edge did not come back after a configuration change"; exit 1; }
MARK="$(maillog_mark)"
REPLY="$(probe_connections "$PROD_CONNECTIONS" || true)"
WARN="$(grep -o "Connection concurrency limit exceeded: [0-9]*" <<<"$(maillog_since "$MARK")" | tail -1 || true)"
if [[ "$REPLY" == 421* && -n "$WARN" ]]; then
  ok "smtpd_client_connection_count_limit=$PROD_CONNECTIONS refuses connection $(( PROD_CONNECTIONS + 1 )) with: $REPLY [$WARN]"
elif [[ "$REPLY" == 421* ]]; then
  bad "connection $(( PROD_CONNECTIONS + 1 )) was refused with $REPLY, but no concurrency-limit warning was logged — the refusal is not attributable to smtpd_client_connection_count_limit"
else
  bad "expected a 421 too-many-connections refusal at $PROD_CONNECTIONS, got: $REPLY"
fi

# ===== connection rate ========================================================
CI_CONN_RATE=$(( PROD_CONN_RATE + 100 ))
set_limits "$SAVED_RECIPIENTS" "$SAVED_CONNECTIONS" "$CI_CONN_RATE" \
  || { bad "the edge did not come back after a configuration change"; exit 1; }
MARK="$(maillog_mark)"
CONTROL="$(probe_connection_rate "$PROD_CONN_RATE" || true)"
CONTROL_LOG="$(maillog_since "$MARK")"
if [[ "$CONTROL" == 220* ]] && ! grep -q "Connection rate limit exceeded" <<<"$CONTROL_LOG"; then
  ok "control: with the rate raised, $(( PROD_CONN_RATE + 5 )) sequential connections are all served and no rate warning is logged"
else
  bad "control failed — sequential connections were refused even with the rate raised (got: $CONTROL)"
fi

set_limits "$SAVED_RECIPIENTS" "$SAVED_CONNECTIONS" "$PROD_CONN_RATE" \
  || { bad "the edge did not come back after a configuration change"; exit 1; }
MARK="$(maillog_mark)"
REPLY="$(probe_connection_rate "$PROD_CONN_RATE" || true)"
PROBE_LOG="$(maillog_since "$MARK")"
WARN="$(grep -o "Connection rate limit exceeded: [0-9]*" <<<"$PROBE_LOG" | tail -1 || true)"
if [[ ( "$REPLY" == 421* || "$REPLY" == 450* ) && -n "$WARN" ]] \
   && ! grep -q "Connection concurrency limit exceeded" <<<"$PROBE_LOG"; then
  ok "smtpd_client_connection_rate_limit=$PROD_CONN_RATE throttles the excess connection with: $REPLY [$WARN]"
elif [[ "$REPLY" == 421* || "$REPLY" == 450* ]]; then
  bad "the excess connection was refused with $REPLY, but the log does not attribute it to the RATE limit (concurrency would refuse identically): ${WARN:-no rate warning}"
else
  bad "expected a 421/450 rate refusal beyond $PROD_CONN_RATE connections, got: $REPLY"
fi

# ===== the invariant abuse must not break =====================================
# Recipient spraying must never cause unknown-recipient bodies to be stored. The
# refusals above are protocol-level and happen before DATA, so nothing should
# have been relayed at all.
SPRAY_ROWS="$("${COMPOSE[@]}" exec -T postgres psql -U "${TESTINBOX_DB_USER:?}" -d "${TESTINBOX_DB_NAME:?}" \
  -tAc "SELECT count(*) FROM message WHERE envelope_to LIKE 'r%@${MAIL_DOMAIN}';" 2>&1 | tr -d ' \r\n')"
if [[ "$SPRAY_ROWS" == "0" ]]; then
  ok "recipient spraying stored nothing"
elif [[ "$SPRAY_ROWS" =~ ^[0-9]+$ ]]; then
  bad "recipient spraying left $SPRAY_ROWS message rows"
else
  bad "could not check for sprayed rows, so absence proves nothing: $SPRAY_ROWS"
fi

restore_limits
RESTORED="$("${COMPOSE[@]}" exec -T mail-edge postconf -h smtpd_client_connection_count_limit 2>/dev/null | tr -d ' \r\n')"
[[ "$RESTORED" == "$SAVED_CONNECTIONS" ]] && ok "abuse ceilings restored to the rehearsal profile ($RESTORED)" \
  || bad "connection limit left at ${RESTORED:-<unknown>}, not the rehearsal's $SAVED_CONNECTIONS"

echo "----"
(( fail == 0 )) || { echo "mail-edge abuse proofs FAILED" >&2; exit 1; }
echo "mail-edge abuse proofs: recipient, connection-count and connection-rate ceilings enforced"
