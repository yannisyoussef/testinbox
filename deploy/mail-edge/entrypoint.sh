#!/usr/bin/env bash
# Brings the rehearsal edge up through the same three-layer validation the real
# host's reconcile.sh uses. Every layer is blocking.
#
#   A. PRE-START   render one generation, compile maps, `postfix check`,
#                  assert the rendered invariants
#   B. HEALTH      start Postfix, open a real SMTP session, require a 220 banner
#   C. RUNNING     assert the EFFECTIVE configuration via postconf
#
# The layers are separate on purpose. `postfix check` validates syntax, not
# parameter values: it passes a configuration that makes smtpd fatal at startup
# (a zero-length *_notice_recipient is the known example), leaving every file on
# disk looking correct while the MTA accepts nothing. A static gate alone would
# go green on that. The 220 banner is what makes layer A trustworthy.
#
# Environment:
#   EDGE_PROFILE        production | ci                 (default ci)
#   EDGE_RELAY_TARGET   host:port for tenant relay      (required for ci)
#   EDGE_MAIL_DOMAIN    tenant domain to accept
#   EDGE_MYHOSTNAME     this host's name
set -euo pipefail

CONF=/etc/postfix
PROFILE="${EDGE_PROFILE:-ci}"
RELAY_TARGET="${EDGE_RELAY_TARGET:-}"
MAIL_DOMAIN="${EDGE_MAIL_DOMAIN:-}"
MYHOSTNAME="${EDGE_MYHOSTNAME:-edge.rehearsal.invalid}"

say() { printf '\n\033[1m--- mail-edge: %s ---\033[0m\n' "$1"; }
die() { echo "mail-edge: $1" >&2; exit 1; }

# ---------------------------------------------------------------- A. PRE-START
say "A. render one coherent generation (profile=$PROFILE)"
# Resolve the relay host to an address here rather than leaving a name in the
# transport map. Postfix's smtp(8) runs chrooted, so it cannot read the
# container's /etc/hosts and cannot see a container runtime's embedded DNS; a
# service name would defer forever with "Temporary failure in name resolution"
# and every downstream proof would fail for a reason that is not under test.
# The real edge relays to an IP across WireGuard, so an address here is the
# faithful form, not a workaround — and it keeps the resolver settings identical
# to production.
if [[ -n "$RELAY_TARGET" ]]; then
  relay_host="${RELAY_TARGET%%:*}"
  relay_port="${RELAY_TARGET##*:}"
  if [[ ! "$relay_host" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    resolved="$(getent ahostsv4 "$relay_host" | awk '{print $1; exit}')"
    [[ -n "$resolved" ]] || die "cannot resolve relay host '$relay_host'"
    echo "  relay target $relay_host -> $resolved"
    RELAY_TARGET="$resolved:$relay_port"
  fi
fi

render_args=(--profile "$PROFILE" --out "$CONF" --myhostname "$MYHOSTNAME")
# The rehearsal binds all interfaces INSIDE its isolated compose network so the
# sender container can reach it; the port is published to loopback only. Asked
# for explicitly, because the renderer now defaults to the dormant listener and
# a container that forgot would simply not be reachable — a visible failure
# rather than an invisible exposure.
[[ "$PROFILE" == "ci" ]] && render_args+=(--smtp-bind "$(/opt/mail-edge/contract.py get listener.live)")
[[ -n "$RELAY_TARGET" ]] && render_args+=(--relay-target "$RELAY_TARGET")
[[ -n "$MAIL_DOMAIN" ]] && render_args+=(--mail-domain "$MAIL_DOMAIN")
/opt/mail-edge/render.sh "${render_args[@]}"
cat "$CONF/generation"

# A rehearsal edge with no relay target would discard tenant mail at the edge
# itself, which makes the ADR-025 proof meaningless: nothing would reach
# ingestion to be discarded there. That is the real host's dormant state and it
# is never valid here.
if [[ "$PROFILE" == "ci" && -z "$RELAY_TARGET" ]]; then
  die "the ci profile requires EDGE_RELAY_TARGET; a dormant discard edge cannot prove ADR-025"
fi

say "A. compile maps"
postmap "$CONF/transport"
# The alias map is declared as hash:/etc/aliases (the Ops canonical path), but
# the generation renders it inside $CONF. Without this install step `newaliases`
# compiles Ubuntu's STOCK /etc/aliases — which maps postmaster to root — and the
# rendered file is never read: postmaster@ mail silently lands in root's mailbox
# while the log says "delivered", and the unprivileged edgepm account exists for
# nothing. Installing it keeps the generation the single source and the declared
# path the real one.
install -m 0644 "$CONF/aliases" /etc/aliases
newaliases

say "A. postfix check"
postfix check || die "postfix check failed"

# ------------------------------------------------------------------- B. HEALTH
say "B. start postfix"
# postlogd writes maillog_file; create it first so tailing never races startup.
: > /var/log/mail.log
postfix start

say "B. require a 220 banner on a real SMTP session"
banner=""
for _ in $(seq 1 50); do
  banner="$(printf 'QUIT\r\n' | timeout 3 nc -w 3 127.0.0.1 25 2>/dev/null | head -1 || true)"
  [[ "$banner" == 220* ]] && break
  sleep 0.2
done
case "$banner" in
  220*) echo "  banner: ${banner%$'\r'}" ;;
  *)    echo "--- mail.log tail ---" >&2; tail -40 /var/log/mail.log >&2 || true
        die "smtpd did not answer 220 (got: '${banner:-<nothing>}'). The configuration may pass \`postfix check\` and still be fatal at startup." ;;
esac

# ------------------------------------------------------------------ C. RUNNING
# The file being rendered correctly is not evidence that Postfix loaded it.
say "C. running-configuration invariants"
fail=0
assert_running() {
  local key="$1" want="$2" got
  got="$(postconf -h "$key" 2>/dev/null || true)"
  if [[ "$got" == "$want" ]]; then
    printf '  ok   %-38s = %s\n' "$key" "${got:-<empty>}"
  else
    printf '  FAIL %-38s = %s (want: %s)\n' "$key" "${got:-<empty>}" "${want:-<empty>}" >&2
    fail=1
  fi
}

C() { /opt/mail-edge/contract.py get "$1"; }
assert_running relay_recipient_maps          "$(C required.relay_recipient_maps)"
assert_running local_header_rewrite_clients  "$(C required.local_header_rewrite_clients)"
assert_running always_add_missing_headers    "$(C required.always_add_missing_headers)"
assert_running default_transport             "$(C required.default_transport)"
assert_running notify_classes                "$(C required.notify_classes)"
assert_running bounce_queue_lifetime         "$(C required.bounce_queue_lifetime)"
assert_running relayhost                     "$(C required.relayhost)"
assert_running mynetworks                    "$(C required.mynetworks)"
assert_running message_size_limit            "$(C required.message_size_limit)"
assert_running smtpd_relay_restrictions      "$(C required.smtpd_relay_restrictions)"
# The ADR-026 control, asserted HERE specifically: postconf expands
# $default_destination_recipient_limit, so this is the only layer that sees the
# value Postfix will actually apply to the relay transport.
assert_running relay_destination_recipient_limit "$(C required.relay_destination_recipient_limit)"
# The Postfix version itself. The behaviours this rehearsal proves are
# version-sensitive by design — local_header_rewrite_clients defaults, the
# zero-length *_notice_recipient fatal, anvil accounting, and the size reserve
# the equal-ceilings proof rests on. Recording 3.8.6 in the contract while
# silently exercising another version would keep the parity claim while
# invalidating the evidence behind it.
want_version="$(/opt/mail-edge/contract.py get provenance.postfix_version)"
got_version="$(postconf -h mail_version 2>/dev/null || true)"
if [[ "$got_version" == "$want_version" ]]; then
  printf '  ok   %-38s = %s\n' "mail_version" "$got_version"
else
  printf '  FAIL %-38s = %s (contract records: %s)\n' "mail_version" "${got_version:-<unknown>}" "$want_version" >&2
  fail=1
fi

# The COMPILED alias map, not the file: postmaster@ is delivered through
# alias expansion, and a rendered file that was never compiled looks identical
# on disk to one that was. This is the assertion that catches the stock-aliases
# shadowing above.
expected_pm="$(/opt/mail-edge/contract.py get operational_recipient_account 2>/dev/null || echo edgepm)"
resolved_pm="$(postalias -q postmaster hash:/etc/aliases 2>/dev/null || true)"
if [[ "$resolved_pm" == "$expected_pm" ]]; then
  printf '  ok   %-38s -> %s\n' "alias postmaster (compiled)" "$resolved_pm"
else
  printf '  FAIL %-38s -> %s (want: %s)\n' "alias postmaster (compiled)" "${resolved_pm:-<unresolved>}" "$expected_pm" >&2
  fail=1
fi

(( fail == 0 )) || die "running configuration does not match the contract"

say "ready"
# Hand the log to the foreground so `docker logs` shows the mail log, and keep
# the container alive as long as Postfix is running.
exec tail -F /var/log/mail.log
