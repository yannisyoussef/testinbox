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
IMAGE="${MAIL_EDGE_IMAGE:-testinbox-mail-edge:rehearsal}"
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

# Ops #124 — a public edge on Postfix defaults lets ANY client force a flush of
# the tenant domain's deferred queue (`ETRN inbox.testinbox.email` answered
# `250 Queuing started`), and advertises DSN, through which a client can make
# the edge generate success notices. Each key is mutated both ways: removed
# (Postfix silently applies its default, which IS the exposure) and set back
# to the default value. Asserted in BOTH profiles — none of these is
# ci_overridable, so a CI render is no excuse.
for profile in ci production; do
  mutate "[$profile] removing smtpd_etrn_restrictions is rejected" 1 \
    "edit main.cf '/^smtpd_etrn_restrictions /d'" "$profile"
  mutate "[$profile] an empty smtpd_etrn_restrictions (the default) is rejected" 1 \
    "edit main.cf 's|^smtpd_etrn_restrictions = .*|smtpd_etrn_restrictions =|'" "$profile"
  mutate "[$profile] removing fast_flush_domains is rejected" 1 \
    "edit main.cf '/^fast_flush_domains /d'" "$profile"
  mutate "[$profile] fast_flush_domains = \$relay_domains (the default) is rejected" 1 \
    "edit main.cf 's|^fast_flush_domains =.*|fast_flush_domains = \$relay_domains|'" "$profile"
  mutate "[$profile] removing smtpd_discard_ehlo_keywords is rejected" 1 \
    "edit main.cf '/^smtpd_discard_ehlo_keywords /d'" "$profile"
  mutate "[$profile] discarding etrn but not dsn is rejected" 1 \
    "edit main.cf 's|^smtpd_discard_ehlo_keywords = .*|smtpd_discard_ehlo_keywords = silent-discard, etrn|'" "$profile"
  # No delivery-status notice of ANY class reaches a sender: delay warnings
  # included. 0h is Postfix's default, so removing the line changes nothing
  # today — it is rejected because the invariant must not rest on a default.
  mutate "[$profile] removing delay_warning_time is rejected" 1 \
    "edit main.cf '/^delay_warning_time /d'" "$profile"
  mutate "[$profile] delay_warning_time = 1h is rejected" 1 \
    "edit main.cf 's|^delay_warning_time = .*|delay_warning_time = 1h|'" "$profile"
done

# The mutations above compare the rendered file against contract.yaml, so
# deleting a key from the contract AND the template together moves both sides
# and the gate cannot object. The behavioural probe below catches that for
# smtpd_etrn_restrictions and smtpd_discard_ehlo_keywords, but NOT for
# fast_flush_domains: with `smtpd_etrn_restrictions = reject` in place its
# removal changes no reply (ETRN is 554 either way), because it is defence in
# depth. delay_warning_time is the same case: 0h is Postfix's default, so its
# removal changes no behaviour either. So the values are pinned here, independently of the manifest; changing
# one is then a deliberate edit to this suite, not a quiet contract diff.
pin_ok=1
while IFS='|' read -r key want; do
  got="$("$REPO_ROOT/deploy/mail-edge/contract.py" get "required.$key" 2>/dev/null)" || got="<absent>"
  if [[ "$got" != "$want" ]]; then
    printf 'FAIL — contract.yaml required.%s is [%s]; the ETRN/DSN invariant requires [%s]\n' "$key" "$got" "$want"
    pin_ok=0
  fi
done <<'PINS'
smtpd_etrn_restrictions|reject
fast_flush_domains|
smtpd_discard_ehlo_keywords|silent-discard, dsn, etrn
delay_warning_time|0h
PINS
if (( pin_ok )); then
  printf 'ok   — the contract still requires the ETRN, DSN and delay-warning keys (required:, not ci_overridable)\n'
  pass=$((pass + 1))
else
  fail=$((fail + 1))
fi
for key in smtpd_etrn_restrictions fast_flush_domains smtpd_discard_ehlo_keywords delay_warning_time; do
  if "$REPO_ROOT/deploy/mail-edge/contract.py" keys ci_overridable | grep -qx "$key"; then
    printf 'FAIL — %s is listed in ci_overridable; the ETRN/DSN keys hold in every profile\n' "$key"
    fail=$((fail + 1))
  fi
done

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
  # Always (re)build unless the caller supplied an image. The image COPYs the
  # templates, render.sh and contract.yaml, so a stale local image would run the
  # runtime cases below against an OLD contract — the behavioural probe would
  # then fail (or pass) for a reason that is not the working tree. The layer
  # cache keeps an unchanged rebuild to a few seconds.
  if [[ -z "${MAIL_EDGE_IMAGE:-}" ]]; then
    docker build -q -f "$REPO_ROOT/deploy/mail-edge/Dockerfile" -t "$IMAGE" "$REPO_ROOT" >/dev/null || {
      printf 'FAIL — the mail-edge image could not be built\n'
      fail=$((fail + 1))
    }
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

# --- the version mutation -----------------------------------------------------
# CI must not silently start exercising a different Postfix while the contract
# still claims 3.8.6 parity. The recorded version is only meaningful if a
# mismatch stops the edge.
note ""
note "--- runtime mutation: a Postfix version mismatch must refuse to start ---"
if ! docker info >/dev/null 2>&1; then
  printf 'FAIL — the version mutation could not run: Docker is unavailable.\n'
  fail=$((fail + 1))
else
  VNAME="mail-edge-version-$$"
  docker rm -f "$VNAME" >/dev/null 2>&1
  # Only the CONTRACT is altered; the binary is untouched. That is the drift
  # being simulated: the recorded version no longer matches what is installed.
  docker run --rm --name "$VNAME" \
    -e EDGE_PROFILE=ci -e EDGE_RELAY_TARGET=127.0.0.1:2525 \
    -e EDGE_MAIL_DOMAIN=inbox.testinbox.email \
    --entrypoint /bin/bash "$IMAGE" -c '
      sed -i "s/^  postfix_version:.*/  postfix_version: \"9.9.9\"/" /opt/mail-edge/contract.yaml
      exec /opt/mail-edge/entrypoint.sh
    ' >"$TMP/version.out" 2>&1
  vstatus=$?
  if (( vstatus != 0 )) && grep -q "mail_version" "$TMP/version.out"; then
    printf 'ok   — a Postfix version that does not match the contract refuses to start\n'
    pass=$((pass + 1))
  else
    printf 'FAIL — the edge started despite a version mismatch (exit %s)\n' "$vstatus"
    tail -8 "$TMP/version.out" | sed 's/^/       /'
    fail=$((fail + 1))
  fi
fi

# --- the ETRN / DSN behaviour --------------------------------------------------
# Ops #124: the static gate proves the keys are rendered; this proves what an
# OUTSIDE client actually gets from a running 3.8.6 edge, and that the same
# assertion fails when the keys are gone. The client connects to the
# container's own non-loopback address, so it is outside mynetworks exactly as
# an Internet client is. Relay goes to a local sink that answers 250 and does
# NOT advertise DSN — the shape of ingestion, and the case in which Postfix
# itself generates the "relayed" success notice.
#
# Variants, all in one container:
#   contract           the render as-is: must refuse ETRN (5xx), advertise
#                      neither ETRN nor DSN, refuse RET=/NOTIFY= (5xx, including
#                      NOTIFY=SUCCESS,DELAY), and emit no DSN
#   postfix-defaults   all three keys removed: must reproduce the exposure
#                      (ETRN 250 Queuing started, DSN advertised, a success
#                      DSN generated) — or the probe is vacuous
#   no-etrn-restrictions, no-discard-keywords
#                      one key removed each: the contract assertion must fail
#   delay-contract, delay-warning-1s
#                      a message held deferred by the sink: no delay notice
#                      with the contract, notices with the 1s control
# fast_flush_domains is pinned statically above: with the restriction present
# its removal changes no reply.
note ""
note "--- runtime: ETRN is refused and no DSN can be requested, from an outside client ---"
if ! docker info >/dev/null 2>&1; then
  printf 'FAIL — the ETRN/DSN probe could not run: Docker is unavailable.\n'
  fail=$((fail + 1))
else
  PROBE_OUT="$TMP/etrn-dsn.out"
  docker run --rm -i --entrypoint /bin/bash "$IMAGE" -s >"$PROBE_OUT" 2>&1 <<'PROBE'
set -u
cat > /tmp/sink.py <<'PY'
import asyncio
async def handle(r, w):
    w.write(b"220 sink ESMTP\r\n"); await w.drain()
    env = []
    while True:
        line = await r.readline()
        if not line: break
        up = line.decode(errors="replace").strip().upper()
        if up.startswith("EHLO"):
            w.write(b"250-sink\r\n250 8BITMIME\r\n")   # deliberately no DSN
        elif up.startswith("DATA"):
            w.write(b"354 go\r\n"); await w.drain()
            while (await r.readline()) not in (b".\r\n", b""): pass
            with open("/tmp/sink.log", "a") as f: f.write(" | ".join(env) + "\n")
            env = []; w.write(b"250 2.0.0 sunk\r\n")
        elif up.startswith("RCPT") and "DEFER@" in up:
            w.write(b"451 4.3.0 try later\r\n")   # the delay-warning case
        elif up.startswith("QUIT"):
            w.write(b"221 bye\r\n"); await w.drain(); break
        else:
            if up.startswith(("MAIL", "RCPT")): env.append(up)
            w.write(b"250 ok\r\n")
        await w.drain()
    w.close()
async def main():
    async with await asyncio.start_server(handle, "127.0.0.1", 2525) as s: await s.serve_forever()
asyncio.run(main())
PY
cat > /tmp/client.py <<'PY'
import socket, sys
host, domain = sys.argv[1], "inbox.testinbox.email"
def session(lines):
    s = socket.create_connection((host, 25), timeout=15); f = s.makefile("rb")
    def reply():
        out = []
        while True:
            l = f.readline().decode(errors="replace").rstrip("\r\n"); out.append(l)
            if len(l) < 4 or l[3] != "-": return out
    reply(); res = []
    for l in lines:
        if l == "<BODY>":
            s.sendall(b"Subject: dsn probe\r\n\r\nbody\r\n.\r\n")
        else:
            s.sendall(l.encode() + b"\r\n")
        r = reply(); res.append(r)
        if not r[-1][:1] in ("2", "3"): break   # stop the transaction at the first refusal
    s.sendall(b"QUIT\r\n"); s.close(); return res
ehlo = session(["EHLO outside.example"])[0]
kw = {l[4:].split()[0].upper() for l in ehlo[1:] if len(l) > 4}
etrn = session(["EHLO outside.example", "ETRN " + domain])
mail_ret = session(["EHLO outside.example", f"MAIL FROM:<dsn-probe@{domain}> RET=HDRS ENVID=probe",
                    f"RCPT TO:<rcpt-a@{domain}> NOTIFY=SUCCESS", "DATA", "<BODY>"])
rcpt_notify = session(["EHLO outside.example", f"MAIL FROM:<dsn-probe@{domain}>",
                       f"RCPT TO:<rcpt-b@{domain}> NOTIFY=SUCCESS,DELAY,FAILURE", "DATA", "<BODY>"])
rcpt_notify_delay = session(["EHLO outside.example", f"MAIL FROM:<dsn-probe@{domain}>",
                             f"RCPT TO:<rcpt-c@{domain}> NOTIFY=SUCCESS,DELAY", "DATA", "<BODY>"])
code = lambda res, i: res[i][-1][:3] if len(res) > i else "---"
print(f"ehlo_etrn={int('ETRN' in kw)} ehlo_dsn={int('DSN' in kw)} etrn={code(etrn, 1)} "
      f"mail_ret={code(mail_ret, 1)} rcpt_notify={code(rcpt_notify, 2)} "
      f"rcpt_notify_delay={code(rcpt_notify_delay, 2)}")
PY
python3 /tmp/sink.py & sleep 0.5
IP="$(hostname -i | awk '{print $1}')"
case "$IP" in 127.*|"") echo "PROBE-ERROR no non-loopback address ($IP)"; exit 1 ;; esac
for variant in contract postfix-defaults no-etrn-restrictions no-discard-keywords; do
  postfix stop >/dev/null 2>&1; sleep 1
  /opt/mail-edge/render.sh --profile ci --out /etc/postfix --relay-target 127.0.0.1:2525 \
    --mail-domain inbox.testinbox.email --smtp-bind 0.0.0.0:25 >/dev/null
  case "$variant" in
    postfix-defaults)     sed -i -E '/^(smtpd_etrn_restrictions|fast_flush_domains|smtpd_discard_ehlo_keywords) /d' /etc/postfix/main.cf ;;
    no-etrn-restrictions) sed -i '/^smtpd_etrn_restrictions /d' /etc/postfix/main.cf ;;
    no-discard-keywords)  sed -i '/^smtpd_discard_ehlo_keywords /d' /etc/postfix/main.cf ;;
  esac
  postmap /etc/postfix/transport; install -m 0644 /etc/postfix/aliases /etc/aliases; newaliases
  postfix check || { echo "PROBE-ERROR postfix check failed for $variant"; exit 1; }
  : > /var/log/mail.log; : > /tmp/sink.log
  postfix start >/dev/null 2>&1
  for _ in $(seq 1 50); do
    case "$(printf 'QUIT\r\n' | timeout 3 nc -w 3 "$IP" 25 2>/dev/null | head -1)" in 220*) break ;; esac
    sleep 0.2
  done
  result="$(python3 /tmp/client.py "$IP")" || { echo "PROBE-ERROR client failed for $variant"; exit 1; }
  # A generated notice is logged by bounce(8) as "sender delivery status
  # notification" whatever happens to it next; the sink sees it only when the
  # envelope sender is in the tenant domain, which this probe arranges.
  dsn=0
  for _ in $(seq 1 30); do
    if grep -q "sender delivery status notification" /var/log/mail.log || grep -q "MAIL FROM:<>" /tmp/sink.log; then dsn=1; break; fi
    sleep 0.3
  done
  echo "RESULT $variant $result dsn_emitted=$dsn"
done

# --- delay warnings: a deferred message must produce NO notice ---------------
# The sink answers 451 to defer@, so these messages sit deferred and are
# retried on the CI timers (2s/4s backoff, 2s queue runs) for the whole window.
# One sender is in the tenant domain (its notice would relay INTO ingestion),
# one is foreign (its notice would be generated and then discarded). The
# control renders delay_warning_time = 1s, the shortest interval Postfix can
# express, and must produce the notices within the same window, or the
# contract variant's silence proves nothing.
cat > /tmp/defer.py <<'DEFER'
import socket, sys
host = sys.argv[1]
for sender in ("delay-probe@inbox.testinbox.email", "delay-probe@example.org"):
    s = socket.create_connection((host, 25), timeout=15); f = s.makefile("rb")
    def reply():
        while True:
            l = f.readline().decode(errors="replace")
            if len(l) < 4 or l[3] != "-": return l
    reply()
    for l in ("EHLO outside.example", f"MAIL FROM:<{sender}>", "RCPT TO:<defer@inbox.testinbox.email>", "DATA"):
        s.sendall(l.encode() + b"\r\n"); r = reply()
        if r[:1] not in ("2", "3"): sys.exit(f"refused at {l}: {r}")
    s.sendall(b"Subject: delay probe\r\n\r\nbody\r\n.\r\n"); r = reply()
    if not r.startswith("250"): sys.exit(f"not queued: {r}")
    s.sendall(b"QUIT\r\n"); s.close()
DEFER
for variant in delay-contract delay-warning-1s; do
  postfix stop >/dev/null 2>&1; sleep 1
  postsuper -d ALL >/dev/null 2>&1   # nothing from an earlier variant may age into this one
  /opt/mail-edge/render.sh --profile ci --out /etc/postfix --relay-target 127.0.0.1:2525 \
    --mail-domain inbox.testinbox.email --smtp-bind 0.0.0.0:25 >/dev/null
  [[ "$variant" == delay-warning-1s ]] && sed -i 's/^delay_warning_time = .*/delay_warning_time = 1s/' /etc/postfix/main.cf
  postmap /etc/postfix/transport; install -m 0644 /etc/postfix/aliases /etc/aliases; newaliases
  : > /var/log/mail.log; : > /tmp/sink.log
  postfix start >/dev/null 2>&1
  for _ in $(seq 1 50); do
    case "$(printf 'QUIT\r\n' | timeout 3 nc -w 3 "$IP" 25 2>/dev/null | head -1)" in 220*) break ;; esac
    sleep 0.2
  done
  python3 /tmp/defer.py "$IP" || { echo "PROBE-ERROR delay messages not queued for $variant"; exit 1; }
  # A fixed window, identical for both variants: the contract's silence counts
  # only because the control speaks inside the same window.
  sleep 15
  deferred="$(grep -c 'to=<defer@inbox.testinbox.email>.*status=deferred' /var/log/mail.log)"
  notices="$(grep -c 'sender delay notification' /var/log/mail.log)"
  relayed="$(grep -c 'MAIL FROM:<>' /tmp/sink.log)"
  echo "RESULT $variant deferred=$deferred delay_notices=$notices relayed_to_ingestion=$relayed"
done
PROBE
  pstatus=$?
  field() { grep "^RESULT $1 " "$PROBE_OUT" | tr ' ' '\n' | awk -F= -v k="$2" '$1 == k { print $2 }'; }
  # The ONE assertion the contract must satisfy, applied unchanged to every
  # variant: a mutation is caught exactly when this fails for it.
  conforms() {
    [[ "$(field "$1" ehlo_etrn)" == 0 && "$(field "$1" ehlo_dsn)" == 0 \
       && "$(field "$1" etrn)" == 5* && "$(field "$1" mail_ret)" == 5* \
       && "$(field "$1" rcpt_notify)" == 5* && "$(field "$1" rcpt_notify_delay)" == 5* \
       && "$(field "$1" dsn_emitted)" == 0 ]]
  }
  if (( pstatus != 0 )) || ! grep -q '^RESULT ' "$PROBE_OUT"; then
    printf 'FAIL — the ETRN/DSN probe produced no verdict (exit %s):\n' "$pstatus"
    tail -15 "$PROBE_OUT" | sed 's/^/       /'
    fail=$((fail + 1))
  else
    grep '^RESULT ' "$PROBE_OUT" | sed 's/^RESULT /       /'
    if conforms contract; then
      printf 'ok   — with the keys, an outside client gets ETRN 5xx, no ETRN/DSN keyword, RET=/NOTIFY= 5xx (SUCCESS,DELAY too), and no DSN\n'
      pass=$((pass + 1))
    else
      printf 'FAIL — the contract render does not refuse ETRN / DSN for an outside client\n'
      fail=$((fail + 1))
    fi
    # The probe must be able to see the exposure, or its pass above means nothing.
    if [[ "$(field postfix-defaults etrn)" == 250 && "$(field postfix-defaults ehlo_etrn)" == 1 \
          && "$(field postfix-defaults ehlo_dsn)" == 1 && "$(field postfix-defaults dsn_emitted)" == 1 ]]; then
      printf 'ok   — on Postfix defaults the probe sees the exposure (ETRN 250, DSN offered, success DSN generated)\n'
      pass=$((pass + 1))
    else
      printf 'FAIL — the probe did not reproduce the default-configuration exposure; it cannot fail and is not a gate\n'
      fail=$((fail + 1))
    fi
    for variant in no-etrn-restrictions no-discard-keywords; do
      if conforms "$variant"; then
        printf 'FAIL — removing a key (%s) left the behaviour conforming; the probe cannot see it\n' "$variant"
        fail=$((fail + 1))
      else
        printf 'ok   — the behavioural assertion fails on %s\n' "$variant"
        pass=$((pass + 1))
      fi
    done
    # Delay warnings. "deferred >= 4" means each of the two messages was
    # retried at least once more after its first deferral, past the control's
    # 1s warning interval, so silence is not a timing artefact.
    dc_deferred="$(field delay-contract deferred)"; c1_notices="$(field delay-warning-1s delay_notices)"
    c1_relayed="$(field delay-warning-1s relayed_to_ingestion)"
    if (( ${dc_deferred:-0} >= 4 )) && [[ "$(field delay-contract delay_notices)" == 0 \
          && "$(field delay-contract relayed_to_ingestion)" == 0 ]]; then
      printf 'ok   — a message deferred past any warning interval produces no delay notice (tenant or foreign sender)\n'
      pass=$((pass + 1))
    else
      printf 'FAIL — the contract render emitted a delay notice, or the messages were not kept deferred\n'
      fail=$((fail + 1))
    fi
    if (( ${c1_notices:-0} >= 2 && ${c1_relayed:-0} >= 1 )); then
      printf 'ok   — with delay_warning_time = 1s the probe sees the notices (one relayed into ingestion)\n'
      pass=$((pass + 1))
    else
      printf 'FAIL — the delay control produced no notices; the delay probe cannot fail and is not a gate\n'
      fail=$((fail + 1))
    fi
  fi
fi

echo "----"
echo "check-mail-edge-contract.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
