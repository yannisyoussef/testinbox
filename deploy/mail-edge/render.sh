#!/usr/bin/env bash
# Renders ONE coherent Postfix configuration generation from the templates and
# deploy/mail-edge/contract.yaml.
#
# COHERENCE IS THE POINT. Ops hit a real defect where per-file rollback restored
# main.cf and master.cf from different generations, and an older stock master.cf
# reinstated a public 0.0.0.0:25 listener while the rest of the configuration
# was current. Everything here is rendered into a staging directory and moved
# into place as a unit, with a generation stamp, so no run can combine files
# from two renders.
#
# PROFILES. `production` renders the contract exactly. `ci` may override only
# the keys listed under `ci_overridable` in the manifest — anything else is
# silent drift and is refused here rather than discovered later.
#
#   render.sh --profile production|ci --out <dir> [--relay-target host:port]
#
# Exit 0 rendered, 2 usage/contract error.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTRACT="$HERE/contract.py"

PROFILE=""
OUT=""
RELAY_TARGET=""
MAIL_DOMAIN=""
MYHOSTNAME="edge.rehearsal.invalid"
SMTP_BIND=""
POSTMASTER_ACCOUNT="edgepm"

usage() { sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; exit 2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --relay-target) RELAY_TARGET="${2:-}"; shift 2 ;;
    --mail-domain) MAIL_DOMAIN="${2:-}"; shift 2 ;;
    --myhostname) MYHOSTNAME="${2:-}"; shift 2 ;;
    --smtp-bind) SMTP_BIND="${2:-}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done

[[ -n "$PROFILE" && -n "$OUT" ]] || usage
case "$PROFILE" in production|ci) ;; *) echo "unknown profile: $PROFILE" >&2; exit 2 ;; esac

c() { "$CONTRACT" get "$1"; }

# --- values that are identical in every profile ------------------------------
# These come from `required` and are never overridable. If a caller wants one of
# them changed, the contract is what changes, under review.
MESSAGE_SIZE_LIMIT="$(c required.message_size_limit)"
TENANT_DOMAIN="$(c tenant_domain)"
OPERATIONAL_RECIPIENT="$(c operational_recipient)"

# --- production baseline -----------------------------------------------------
RECIPIENT_LIMIT="$(c production.smtpd_recipient_limit)"
DEST_RECIPIENT_LIMIT="$(c production.smtp_destination_recipient_limit)"
CONN_LIMIT="$(c production.smtpd_client_connection_count_limit)"
CONN_RATE="$(c production.smtpd_client_connection_rate_limit)"
MSG_RATE="$(c production.smtpd_client_message_rate_limit)"
QUEUE_LIFETIME="$(c production.maximal_queue_lifetime)"
MIN_BACKOFF="$(c production.minimal_backoff_time)"
MAX_BACKOFF="$(c production.maximal_backoff_time)"
QUEUE_RUN_DELAY="$(c production.queue_run_delay)"
TLS_LEVEL="may"
HOST_LOOKUP="dns"
TLS_BLOCK="smtpd_tls_protocols = >=TLSv1.2
smtpd_tls_loglevel = 1
tls_preempt_cipherlist = no"

# --- CI overrides, each one allowlisted --------------------------------------
# Kept in one block so the diff against production is readable, and checked
# against the manifest so the allowlist cannot rot away from the code.
declare -a OVERRIDDEN=()
if [[ "$PROFILE" == "ci" ]]; then
  QUEUE_LIFETIME="${EDGE_CI_QUEUE_LIFETIME:-20s}";      OVERRIDDEN+=(maximal_queue_lifetime)
  MIN_BACKOFF="${EDGE_CI_MIN_BACKOFF:-2s}";             OVERRIDDEN+=(minimal_backoff_time)
  MAX_BACKOFF="${EDGE_CI_MAX_BACKOFF:-4s}";             OVERRIDDEN+=(maximal_backoff_time)
  QUEUE_RUN_DELAY="${EDGE_CI_QUEUE_RUN_DELAY:-2s}";     OVERRIDDEN+=(queue_run_delay)
  # Anvil limits are per client IP and the rehearsal has one sender container,
  # so ordinary tests would exhaust the production ceilings and fail for a
  # reason that is not the thing under test. The abuse tests render their own
  # profile at production values instead.
  CONN_LIMIT="${EDGE_CI_CONN_LIMIT:-200}";              OVERRIDDEN+=(smtpd_client_connection_count_limit)
  CONN_RATE="${EDGE_CI_CONN_RATE:-600}";                OVERRIDDEN+=(smtpd_client_connection_rate_limit)
  MSG_RATE="${EDGE_CI_MSG_RATE:-1000}";                 OVERRIDDEN+=(smtpd_client_message_rate_limit)
  # No certificate material in the rehearsal, and no TLS invariant to prove.
  TLS_LEVEL="none"; TLS_BLOCK="";                       OVERRIDDEN+=(smtpd_tls_security_level)
fi

# Refuse an override the manifest does not sanction. This is what keeps "CI may
# differ" from becoming "CI differs however it likes".
ALLOWED="$("$CONTRACT" keys ci_overridable)"
for key in "${OVERRIDDEN[@]:-}"; do
  [[ -z "$key" ]] && continue
  grep -qx "$key" <<<"$ALLOWED" || {
    echo "render: '$key' is overridden by the $PROFILE profile but is not in ci_overridable" >&2
    exit 2
  }
done

# --- runtime substitutions ---------------------------------------------------
: "${MAIL_DOMAIN:=$TENANT_DOMAIN}"
: "${SMTP_BIND:=0.0.0.0:25}"

# The postmaster line must precede the domain line: a specific-address lookup
# has to win over the tenant-domain lookup, or postmaster@ relays downstream
# like any tenant recipient and its local disposition never happens.
POSTMASTER_LINE="${OPERATIONAL_RECIPIENT}@${MAIL_DOMAIN}	local:"
if [[ -n "$RELAY_TARGET" ]]; then
  # Brackets suppress MX lookup — the target is an address, not a mail domain.
  RELAY_LINE="${MAIL_DOMAIN}	relay:[${RELAY_TARGET%%:*}]:${RELAY_TARGET##*:}"
else
  # No relay target renders NO line at all, which leaves the domain falling
  # through to default_transport = discard. That is the real host's dormant
  # state; it is never what the rehearsal wants, and the contract gate refuses
  # it for the ci profile.
  RELAY_LINE="# (dormant: no relay target configured)"
fi

# --- render as one generation ------------------------------------------------
GENERATION="$(date -u +%Y%m%dT%H%M%SZ)-$$"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

subst() {
  sed \
    -e "s|__EDGE_MYHOSTNAME__|${MYHOSTNAME}|g" \
    -e "s|__EDGE_MAIL_DOMAIN__|${MAIL_DOMAIN}|g" \
    -e "s|__EDGE_MESSAGE_SIZE_LIMIT__|${MESSAGE_SIZE_LIMIT}|g" \
    -e "s|__EDGE_RECIPIENT_LIMIT__|${RECIPIENT_LIMIT}|g" \
    -e "s|__EDGE_DEST_RECIPIENT_LIMIT__|${DEST_RECIPIENT_LIMIT}|g" \
    -e "s|__EDGE_CONN_LIMIT__|${CONN_LIMIT}|g" \
    -e "s|__EDGE_CONN_RATE__|${CONN_RATE}|g" \
    -e "s|__EDGE_MSG_RATE__|${MSG_RATE}|g" \
    -e "s|__EDGE_QUEUE_LIFETIME__|${QUEUE_LIFETIME}|g" \
    -e "s|__EDGE_MIN_BACKOFF__|${MIN_BACKOFF}|g" \
    -e "s|__EDGE_MAX_BACKOFF__|${MAX_BACKOFF}|g" \
    -e "s|__EDGE_QUEUE_RUN_DELAY__|${QUEUE_RUN_DELAY}|g" \
    -e "s|__EDGE_TLS_LEVEL__|${TLS_LEVEL}|g" \
    -e "s|__EDGE_SMTP_BIND__|${SMTP_BIND}|g" \
    -e "s|__EDGE_POSTMASTER_ACCOUNT__|${POSTMASTER_ACCOUNT}|g" \
    "$1"
}

subst "$HERE/templates/main.cf.tmpl" > "$STAGE/main.cf"
# The TLS block is multi-line, so it is substituted after the single-line pass.
python3 - "$STAGE/main.cf" "$TLS_BLOCK" <<'PY'
import sys, pathlib
path, block = pathlib.Path(sys.argv[1]), sys.argv[2]
path.write_text(path.read_text().replace("__EDGE_TLS_BLOCK__", block))
PY
subst "$HERE/templates/master.cf.tmpl" > "$STAGE/master.cf"
subst "$HERE/templates/aliases.tmpl" > "$STAGE/aliases"
subst "$HERE/templates/transport.tmpl" \
  | python3 -c '
import sys
post, relay = sys.argv[1], sys.argv[2]
sys.stdout.write(sys.stdin.read().replace("__EDGE_POSTMASTER_LINE__", post).replace("__EDGE_RELAY_LINE__", relay))
' "$POSTMASTER_LINE" "$RELAY_LINE" > "$STAGE/transport"

# The stamp is what lets the gate prove every file came from one render.
cat > "$STAGE/generation" <<STAMP
generation=$GENERATION
profile=$PROFILE
mail_domain=$MAIL_DOMAIN
relay_target=${RELAY_TARGET:-none}
message_size_limit=$MESSAGE_SIZE_LIMIT
overridden=${OVERRIDDEN[*]:-none}
STAMP

mkdir -p "$OUT"
# Move as a unit: a half-written generation must never be loadable.
for f in main.cf master.cf transport aliases generation; do
  mv "$STAGE/$f" "$OUT/$f"
done

echo "rendered generation $GENERATION (profile=$PROFILE) into $OUT"
