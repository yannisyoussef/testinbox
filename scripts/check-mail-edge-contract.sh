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
  if ! grep -qE "^${key}[[:space:]]*=" "$MAIN"; then
    bad "$key is ABSENT from main.cf — Postfix would apply its own default, which is not the contract's value"
    return
  fi
  got="$(value_of "$key")"
  if [[ "$got" == "$want" ]]; then
    ok "$key = ${got:-<empty>}"
  else
    bad "$key = ${got:-<empty>} (contract requires: ${want:-<empty>})"
  fi
}

echo "mail-edge contract gate — $DIR (profile=$PROFILE)"

# --- the invariants that hold in EVERY profile -------------------------------
# Derived from the manifest, never hardcoded: a fixed list here silently stops
# gating anything later added to the contract, which is not a failure anyone
# would notice — the gate would keep passing and simply check less.
for key in $("$CONTRACT" keys required); do
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
# `|| true` on both: a dormant transport map has NO tenant-domain line, and
# under `set -e` a non-matching grep aborted the gate silently — no FAIL line,
# no summary, exit 1. That made the gate unusable against the real edge's actual
# dormant configuration, which is the one state Ops would most want to check.
PM_LINE="$(grep -nE "^${POSTMASTER}@" "$TRANSPORT" | head -1 | cut -d: -f1 || true)"
DOMAIN_LINE="$(grep -nE "^${TENANT}[[:space:]]" "$TRANSPORT" | head -1 | cut -d: -f1 || true)"
if [[ -n "$PM_LINE" && -n "$DOMAIN_LINE" && "$PM_LINE" -lt "$DOMAIN_LINE" ]]; then
  ok "the ${POSTMASTER}@ entry precedes the tenant-domain entry"
elif [[ -z "$DOMAIN_LINE" ]]; then
  # No tenant-domain line at all: the dormant form. Valid for production (the
  # real edge is dormant today), and refused for ci by the relay-form check.
  ok "no tenant-domain transport entry (dormant); ordering is not applicable"
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

# --- the listener -------------------------------------------------------------
# master.cf was previously opened only to check it EXISTS. It carries the single
# switch that decides whether the edge is reachable from the Internet, and it is
# the file in the mixed-generation defect that motivated the whole renderer, so
# leaving it unread made the gate blind to the one change that matters most.
MASTER="$DIR/master.cf"
LISTENER="$(awk '$NF == "smtpd" && $2 == "inet" { print $1; exit }' "$MASTER")"
DORMANT="$(C listener.dormant)"
LIVE="$(C listener.live)"
case "$PROFILE" in
  production)
    if [[ "$LISTENER" == "$DORMANT" ]]; then
      ok "smtpd listens on $LISTENER (dormant)"
    else
      bad "smtpd listens on ${LISTENER:-<none>} in a production render — the contract's dormant listener is $DORMANT. A public listener is how 'no public SMTP' stops being true."
    fi
    ;;
  ci)
    # The rehearsal legitimately binds all interfaces inside an isolated network.
    if [[ "$LISTENER" == "$LIVE" || "$LISTENER" == "$DORMANT" ]]; then
      ok "smtpd listens on $LISTENER"
    else
      bad "smtpd listens on an unexpected address: ${LISTENER:-<none>}"
    fi
    ;;
esac

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
