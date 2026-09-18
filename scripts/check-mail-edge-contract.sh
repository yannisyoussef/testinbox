#!/usr/bin/env bash
# The static half of the mail-edge gate: does a RENDERED configuration satisfy
# deploy/mail-edge/contract.yaml?
#
# This is deliberately narrow. It is not a Postfix linter — it asserts only the
# TestInbox invariants, because a generic checker accumulates opinions nobody
# decided and starts failing for reasons that are not decisions.
#
# It is also, on its own, NOT sufficient. `postfix check` passes configurations
# that make smtpd fatal at startup, and a rendered file proves nothing about
# what Postfix loaded. The running-configuration assertions in the edge
# entrypoint and the 220 banner gate are the other two layers; this one catches
# drift before anything starts.
#
#   check-mail-edge-contract.sh <rendered-config-dir> [--profile production|ci]
#
# Exit 0 conforms, 1 drift, 2 usage.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTRACT="${MAIL_EDGE_CONTRACT:-$REPO_ROOT/deploy/mail-edge/contract.py}"

DIR="${1:-}"
PROFILE="ci"
shift || true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="${2:-}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$DIR" && -d "$DIR" ]] || { echo "usage: check-mail-edge-contract.sh <rendered-config-dir> [--profile production|ci]" >&2; exit 2; }

MAIN="$DIR/main.cf"
TRANSPORT="$DIR/transport"
for f in "$MAIN" "$DIR/master.cf" "$TRANSPORT"; do
  [[ -f "$f" ]] || { echo "missing rendered file: $f" >&2; exit 2; }
done

fail=0
ok()  { printf '  ok   %s\n' "$1"; }
bad() { printf '  FAIL %s\n' "$1" >&2; fail=1; }
C()   { "$CONTRACT" get "$1"; }

# Reads the EFFECTIVE value of a main.cf parameter: the last assignment wins,
# exactly as Postfix resolves it. Grepping for the first match would let a
# second, later line quietly override an assertion that looked satisfied.
value_of() {
  local key="$1"
  awk -v k="$key" '
    $0 ~ "^"k"[[:space:]]*=" {
      sub("^"k"[[:space:]]*=[[:space:]]*", "")
      v = $0
    }
    END { print v }
  ' "$MAIN"
}

assert_value() {
  local key="$1" want="$2" got
  got="$(value_of "$key")"
  if [[ "$got" == "$want" ]]; then
    ok "$key = ${got:-<empty>}"
  else
    bad "$key = ${got:-<empty>} (contract requires: ${want:-<empty>})"
  fi
}

echo "mail-edge contract gate — $DIR (profile=$PROFILE)"

# --- the invariants that hold in EVERY profile -------------------------------
for key in relay_recipient_maps local_header_rewrite_clients always_add_missing_headers \
           default_transport notify_classes bounce_queue_lifetime relayhost \
           mynetworks smtpd_relay_restrictions disable_vrfy_command message_size_limit; do
  assert_value "$key" "$(C "required.$key")"
done

# relay_recipient_maps deserves its own check beyond equality: an assertion of
# the form `grep -q '^relay_recipient_maps'` passes happily on a defined map,
# which is the exact footgun ADR-004 forbids. Emptiness is the requirement.
if grep -qE '^relay_recipient_maps[[:space:]]*=[[:space:]]*[^[:space:]]' "$MAIN"; then
  bad "relay_recipient_maps is DEFINED — this reintroduces the recipient-enumeration oracle ADR-025 removes"
else
  ok "relay_recipient_maps is empty, not merely present"
fi

# A zero-length *_notice_recipient passes `postfix check` and then kills smtpd at
# startup. Static validation cannot see that, which is why the 220 banner gate
# exists — but the value should never be written in the first place.
if grep -qE '^[a-z2]*_?notice_recipient[[:space:]]*=[[:space:]]*$' "$MAIN"; then
  bad "a *_notice_recipient is set to an empty value: Postfix refuses a zero-length string and smtpd will not start"
else
  ok "no *_notice_recipient is set to an empty value"
fi

# --- the tenant domain and the apex ------------------------------------------
TENANT="$(value_of relay_domains)"
APEX="$(C apex_domain)"
[[ -n "$TENANT" ]] && ok "relay_domains = $TENANT" || bad "relay_domains is empty"
if [[ "$TENANT" == "$APEX" ]]; then
  bad "relay_domains is the APEX ($APEX): the apex stays on an ordinary mailbox provider"
else
  ok "relay_domains is not the apex"
fi

# --- the transport map -------------------------------------------------------
POSTMASTER="$(C operational_recipient)"
if grep -qE "^${POSTMASTER}@${TENANT}[[:space:]]+local:" "$TRANSPORT"; then
  ok "${POSTMASTER}@ is routed local: (a post-acceptance divert, not an SMTP branch)"
else
  bad "${POSTMASTER}@${TENANT} is not routed to local:"
fi

# Specific-address lookups must precede the domain lookup, or postmaster@ relays
# downstream like any tenant recipient and its local disposition never happens.
PM_LINE="$(grep -nE "^${POSTMASTER}@" "$TRANSPORT" | head -1 | cut -d: -f1)"
DOMAIN_LINE="$(grep -nE "^${TENANT}[[:space:]]" "$TRANSPORT" | head -1 | cut -d: -f1)"
if [[ -n "$PM_LINE" && -n "$DOMAIN_LINE" && "$PM_LINE" -lt "$DOMAIN_LINE" ]]; then
  ok "the ${POSTMASTER}@ entry precedes the tenant-domain entry"
elif [[ -z "$DOMAIN_LINE" ]]; then
  : # handled by the relay-form check below
else
  bad "the ${POSTMASTER}@ entry must precede the tenant-domain entry"
fi

if [[ "$PROFILE" == "ci" ]]; then
  # The rehearsal must render the PRODUCTION relay form. The dormant real edge
  # renders `<domain> discard:`, and an unknown-recipient test against that
  # proves nothing: the edge would discard the message itself instead of
  # relaying it for ingestion to discard, which is the actual ADR-025 claim.
  if grep -qE "^${TENANT}[[:space:]]+relay:\[[^]]+\]:[0-9]+" "$TRANSPORT"; then
    ok "the tenant domain relays to a bracketed address (production form)"
  else
    bad "the tenant domain does not relay: a dormant discard edge cannot prove ADR-025"
  fi
  if grep -qE "^${TENANT}[[:space:]]+discard:" "$TRANSPORT"; then
    bad "the tenant domain is set to discard: — that is the DORMANT form, never valid in the rehearsal"
  fi
fi

# --- production contract values ----------------------------------------------
# CI may differ only where the manifest sanctions it. The production values are
# asserted separately in every profile, so a drift to Postfix's multi-day queue
# defaults fails even while CI runs on short timers.
ALLOWED="$("$CONTRACT" keys ci_overridable)"
for key in $("$CONTRACT" keys production); do
  want="$(C "production.$key")"
  got="$(value_of "$key")"
  if [[ "$got" == "$want" ]]; then
    ok "$key = $got"
  elif [[ "$PROFILE" == "ci" ]] && grep -qx "$key" <<<"$ALLOWED"; then
    ok "$key = ${got:-<empty>} (allowlisted CI override; production is $want)"
  else
    bad "$key = ${got:-<empty>} (production contract: $want, and it is not CI-overridable)"
  fi
done

echo "----"
if (( fail )); then
  echo "mail-edge contract gate: FAILED" >&2
  exit 1
fi
echo "mail-edge contract gate: the rendered configuration satisfies the contract"
