#!/usr/bin/env bash
# Proves the mail-edge gates can actually fail.
#
# Every case here is a mutation Ops asked for by name, because each one is a
# plausible future "Postfix hardening" change that would look reasonable in a
# diff and would silently remove an invariant. A gate that passes all of them is
# indistinguishable from no gate — which is the failure this repository treats
# as worse than having no gate at all.
#
# The static mutations run against rendered fixtures. The 220-banner mutation
# needs a live Postfix, because its whole point is that static validation cannot
# see it: `postfix check` passes a configuration that leaves smtpd throttled and
# answering nothing. It is skipped only when Docker is unavailable, and says so
# loudly rather than quietly passing.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATE="$REPO_ROOT/scripts/check-mail-edge-contract.sh"
RENDER="$REPO_ROOT/deploy/mail-edge/render.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

note() { printf '%s\n' "$1"; }

# Portable in-place edit. `sed -i` differs between GNU and BSD: BSD consumes the
# expression as the backup suffix and applies nothing, which would make every
# mutation silently succeed and every case pass for the wrong reason. A test
# whose job is catching gates that cannot fail must not be one itself.
edit() {
  local file="$1" expr="$2"
  sed "$expr" "$file" > "$file.mutated" && mv "$file.mutated" "$file"
}

# Renders a good CI generation, applies a mutation, and requires the gate to
# reject it. The unmutated render is checked first in `the baseline passes`, so
# a gate that rejected everything would be caught too.
mutate() {
  local name="$1" expected="$2" mutation="$3" profile="${4:-ci}"
  local dir="$TMP/$(echo "$name" | tr -cd '[:alnum:]')"
  rm -rf "$dir"
  "$RENDER" --profile "$profile" --out "$dir" --relay-target 10.0.0.5:2525 --mail-domain inbox.testinbox.email >/dev/null 2>&1
  ( cd "$dir" && eval "$mutation" )
  local output status
  output="$("$GATE" "$dir" --profile "$profile" 2>&1)"
  status=$?
  if [[ "$status" == "$expected" ]]; then
    printf 'ok   — %s\n' "$name"
    pass=$((pass + 1))
  else
    printf 'FAIL — %s (expected exit %s, got %s)\n' "$name" "$expected" "$status"
    sed 's/^/       /' <<<"$output"
    fail=$((fail + 1))
  fi
}

note "--- static mutations: each must make the contract gate fail ---"

mutate "the baseline passes" 0 "true"

# Ops §P.1 — the open-relay false positive. permit_mynetworks is evaluated
# first, so a container-derived mynetworks makes the sender a trusted client and
# the foreign-domain probe returns 250 instead of 554.
mutate "a Docker subnet in mynetworks is rejected" 1 \
  "edit main.cf 's|^mynetworks = .*|mynetworks = 127.0.0.0/8 [::1]/128 172.16.0.0/12|'"

# Ops §P.2 — always_add_missing_headers is not the switch. Restoring the default
# lets Postfix stamp Message-ID/Date/From for locally submitted mail, which
# ADR-019 forbids and which a remote-sender behavioural test cannot detect.
mutate "a default local_header_rewrite_clients is rejected" 1 \
  "edit main.cf 's|^local_header_rewrite_clients =.*|local_header_rewrite_clients = permit_inet_interfaces|'"

# Ops §P.6 — the footgun ADR-004 forbids by name. Note the assertion must test
# EMPTINESS: a check for the key's presence passes on a defined map.
mutate "a defined relay_recipient_maps is rejected" 1 \
  "edit main.cf 's|^relay_recipient_maps =.*|relay_recipient_maps = hash:/etc/postfix/recipients|'"

# Ops §P.7 — the drift that would make queue expiry take days and turn the short
# CI timers into the only thing anyone ever verifies.
#
# Asserted against the PRODUCTION profile deliberately. Mutating these in a CI
# render is answered "allowlisted override", which is correct — they ARE
# CI-overridable — so the case would pass while testing nothing. What must never
# drift is the production rendering.
#
# Residual, stated rather than papered over: this compares the rendered
# configuration against the contract, so editing `production:` in contract.yaml
# moves both sides at once and the gate cannot object. That change is a reviewed
# diff to a committed file, which is the control that covers it; CODEOWNERS is
# the place to strengthen that, not a self-consistent gate.
mutate "a multi-day queue lifetime in the production rendering is rejected" 1 \
  "edit main.cf 's|^maximal_queue_lifetime = .*|maximal_queue_lifetime = 5d|' && \
   edit main.cf 's|^queue_run_delay = .*|queue_run_delay = 1d|'" \
  production

# Ops §P.5 — the dormant form. An ADR-025 test against a discarding edge proves
# nothing, because the message never reaches ingestion to be discarded there.
mutate "the dormant discard: transport is rejected in the ci profile" 1 \
  "edit transport 's|^inbox.testinbox.email[[:space:]]*relay:.*|inbox.testinbox.email\tdiscard:|'"

# Ops §P.4 — splitting one logical transaction into several downstream
# deliveries, so the ADR-026 atomicity proof passes while testing nothing.
#
# The mutation targets relay_* because that is what governs the transport tenant
# mail actually uses. An earlier version mutated smtp_*, whose stated premise was
# simply false: smtp_destination_recipient_limit=1 leaves
# relay_destination_recipient_limit at 50 and splits nothing.
mutate "relay_destination_recipient_limit = 1 is rejected" 1 \
  "edit main.cf 's|^relay_destination_recipient_limit = .*|relay_destination_recipient_limit = 1|'"

# And the indirection itself: with relay_* pinned explicitly, a default_* change
# can no longer reach the relay path — but if the explicit pin were ever removed,
# relay_* would fall back to $default_destination_recipient_limit. This asserts
# the pin is present rather than trusting it.
mutate "removing the explicit relay recipient limit is rejected" 1 \
  "edit main.cf '/^relay_destination_recipient_limit /d'"

# Ops §P.3, static half — the value that passes `postfix check` and then kills
# smtpd. The runtime half is below.
mutate "an empty *_notice_recipient is rejected" 1 \
  "printf 'bounce_notice_recipient =\n' >> main.cf"

# A public listener in a production render. This is the defect the renderer was
# built for — Ops' per-file rollback restored a stock master.cf with a public
# 0.0.0.0:25 listener while the rest of the configuration was current — and
# until now master.cf was opened by the gate only to check it existed.
mutate "a public listener in a production render is rejected" 1 \
  "edit master.cf 's|^127.0.0.1:25 |0.0.0.0:25 |'" \
  production

# The production profile must also PASS cleanly. Without this the production
# assertions are only ever exercised in the must-fail direction, so a clean
# production render that stopped conforming would go unnoticed.
mutate "the production baseline passes" 0 "true" production

# The real edge's ACTUAL current state: production profile, no relay target, so
# the transport map carries no tenant-domain line. This is the configuration Ops
# would point the gate at before activation, and it must be checkable — the gate
# previously aborted on it with no output at all.
dormant_dir="$TMP/dormantproduction"
rm -rf "$dormant_dir"
"$RENDER" --profile production --out "$dormant_dir" --mail-domain inbox.testinbox.email >/dev/null 2>&1
if out="$("$GATE" "$dormant_dir" --profile production 2>&1)"; then
  printf 'ok   — the dormant production render is checkable and conforms\n'
  pass=$((pass + 1))
else
  printf 'FAIL — the dormant production render was rejected or aborted (exit %s)\n' "$?"
  sed 's/^/       /' <<<"$out"
  fail=$((fail + 1))
fi

# The size ceiling is the one the Ops handoff still records as 25 MiB. A drift
# back to it would let the edge accept mail ingestion always refuses, answered
# 250 and then silently discarded because the edge emits no DSN.
mutate "a 25 MiB message_size_limit is rejected" 1 \
  "edit main.cf 's|^message_size_limit = .*|message_size_limit = 26214400|'"

# An override outside the allowlist must be refused even though it is a value CI
# legitimately changes for other parameters.
mutate "a non-allowlisted production drift is rejected" 1 \
  "edit main.cf 's|^smtpd_recipient_limit = .*|smtpd_recipient_limit = 5000|'"

# --- the allowlist, checked structurally -------------------------------------
# render.sh only verifies the list it wrote itself, so it enforces "CI may
# override what CI says it overrides" — which cannot catch a parameter dropped
# without being declared. This renders both profiles and diffs them: every
# differing key must be sanctioned by the manifest. It is the assertion that
# makes "only ci_overridable may differ" true rather than merely stated.
note ""
note "--- the CI profile may differ ONLY where the manifest sanctions it ---"
prod_dir="$TMP/diffprod"; ci_dir="$TMP/diffci"
rm -rf "$prod_dir" "$ci_dir"
"$RENDER" --profile production --out "$prod_dir" --relay-target 10.0.0.5:2525 --mail-domain inbox.testinbox.email >/dev/null 2>&1
"$RENDER" --profile ci --out "$ci_dir" --relay-target 10.0.0.5:2525 --mail-domain inbox.testinbox.email >/dev/null 2>&1
allowed="$("$REPO_ROOT/deploy/mail-edge/contract.py" keys ci_overridable)"
undeclared=""
# Keys present in one rendering and not the other count too: dropping a
# parameter is a divergence, and it is the shape that slipped through before.
for key in $( { grep -oE '^[a-z_0-9]+ *=' "$prod_dir/main.cf"; grep -oE '^[a-z_0-9]+ *=' "$ci_dir/main.cf"; } \
              | tr -d ' =' | sort -u ); do
  pv="$(awk -v k="$key" '$0 ~ "^"k"[[:space:]]*=" { sub("^"k"[[:space:]]*=[[:space:]]*",""); v=$0 } END { print v }' "$prod_dir/main.cf")"
  cv="$(awk -v k="$key" '$0 ~ "^"k"[[:space:]]*=" { sub("^"k"[[:space:]]*=[[:space:]]*",""); v=$0 } END { print v }' "$ci_dir/main.cf")"
  pp="$(grep -cE "^${key}[[:space:]]*=" "$prod_dir/main.cf" || true)"
  cp="$(grep -cE "^${key}[[:space:]]*=" "$ci_dir/main.cf" || true)"
  if [[ "$pv" != "$cv" || "$pp" != "$cp" ]]; then
    grep -qx "$key" <<<"$allowed" || undeclared="$undeclared $key"
  fi
done
if [[ -z "$undeclared" ]]; then
  printf 'ok   — every difference between the profiles is declared in ci_overridable\n'
  pass=$((pass + 1))
else
  printf 'FAIL — the profiles differ in keys the manifest does not sanction:%s\n' "$undeclared"
  fail=$((fail + 1))
fi

# --- the runtime mutation ----------------------------------------------------
note ""
note "--- runtime mutation: the 220 banner gate must fail on a fatal config ---"
if ! docker info >/dev/null 2>&1; then
  printf 'FAIL — the 220 banner mutation could not run: Docker is unavailable.\n'
  printf '       This case is a BLOCKER by Ops instruction and must not be skipped silently.\n'
  fail=$((fail + 1))
else
  IMAGE="${MAIL_EDGE_IMAGE:-testinbox-mail-edge:rehearsal}"
  if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    docker build -q -f "$REPO_ROOT/deploy/mail-edge/Dockerfile" -t "$IMAGE" "$REPO_ROOT" >/dev/null
  fi
  NAME="mail-edge-mutation-$$"
  docker rm -f "$NAME" >/dev/null 2>&1
  # A zero-length bounce_notice_recipient: `postfix check` passes it, master
  # starts, and smtpd then throttles with "bad string length 0 < 1" and accepts
  # nothing. If the entrypoint reported ready anyway, every behavioural test
  # after it would be running against an MTA that answers no mail.
  docker run -d --name "$NAME" \
    -e EDGE_PROFILE=ci -e EDGE_RELAY_TARGET=127.0.0.1:2525 \
    -e EDGE_MAIL_DOMAIN=inbox.testinbox.email \
    --entrypoint /bin/bash "$IMAGE" -c '
      /opt/mail-edge/render.sh --profile ci --out /etc/postfix \
        --relay-target 127.0.0.1:2525 --mail-domain inbox.testinbox.email >/dev/null
      printf "bounce_notice_recipient =\n" >> /etc/postfix/main.cf
      postmap /etc/postfix/transport && newaliases
      postfix check || { echo "MUTATION-NOTE: postfix check rejected it"; exit 9; }
      echo "MUTATION-NOTE: postfix check PASSED this configuration"
      : > /var/log/mail.log
      postfix start
      for _ in $(seq 1 25); do
        banner="$(printf "QUIT\r\n" | timeout 3 nc -w 3 127.0.0.1 25 2>/dev/null | head -1 || true)"
        case "$banner" in 220*) echo "BANNER-OK"; exit 0 ;; esac
        sleep 0.2
      done
      echo "NO-BANNER"
      exit 1
    ' >/dev/null 2>&1
  # Wait for the probe container to finish rather than guessing: `nc` against a
  # throttled smtpd blocks for its whole timeout on every attempt, because the
  # port is open and simply never answers, so the loop takes far longer than the
  # arithmetic suggests.
  for _ in $(seq 1 60); do
    [[ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" == "false" ]] && break
    sleep 2
  done
  OUT="$(docker logs "$NAME" 2>&1 || true)"
  docker rm -f "$NAME" >/dev/null 2>&1

  if grep -q "MUTATION-NOTE: postfix check PASSED" <<<"$OUT"; then
    note "       (confirmed: \`postfix check\` accepts the fatal configuration)"
  fi
  if grep -q "NO-BANNER" <<<"$OUT"; then
    printf 'ok   — a config that passes `postfix check` but kills smtpd produces no 220\n'
    pass=$((pass + 1))
  elif grep -q "BANNER-OK" <<<"$OUT"; then
    printf 'FAIL — smtpd answered 220 on a configuration that should have been fatal;\n'
    printf '       the health gate cannot fail and is therefore not a gate (BLOCKER)\n'
    fail=$((fail + 1))
  else
    printf 'FAIL — the 220 mutation produced no verdict:\n'
    sed 's/^/       /' <<<"$OUT" | tail -12
    fail=$((fail + 1))
  fi
fi

echo "----"
echo "check-mail-edge-contract.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
