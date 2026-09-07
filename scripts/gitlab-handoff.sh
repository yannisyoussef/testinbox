#!/usr/bin/env bash
# Hands a validated, immutable release candidate to the Infinity Ops platform
# (ADR-030). GitHub builds and proves artifacts; GitLab `infinity/infinity-core`
# deploys them. GitHub holds no credential for the staging host.
#
# WHAT THIS DOES AND DOES NOT MEAN
#
# A 2xx from the trigger API means the RELEASE HANDOFF WAS ACCEPTED. It does
# not mean the host deployed, the migration ran, readiness passed, or the
# synthetic suite passed — all of that happens asynchronously in Ops, and the
# authoritative verdict lives there. Callers must not translate this script's
# exit code into "deployment succeeded".
#
# The payload is deliberately minimal (TI-DEPLOY-002 §6): an environment name,
# a commit SHA and four image digests. No host, no SSH key, no database or
# object-store credential, no API key ever crosses this boundary — Ops already
# owns those, and duplicating them into GitHub would recreate the blast radius
# this handoff exists to remove.
#
# Usage:
#   GITLAB_TRIGGER_TOKEN=... scripts/gitlab-handoff.sh \
#       --commit <40-hex> \
#       --api <digest> --ingestion <digest> --migrator <digest> --web <digest>
set -euo pipefail

GITLAB_API_URL="${GITLAB_API_URL:-https://gitlab.com/api/v4}"
# URL-encoded namespaced path, or a numeric project id.
GITLAB_PROJECT="${GITLAB_PROJECT:-infinity%2Finfinity-core}"
GITLAB_REF="${GITLAB_REF:-develop}"
# Fixed, not an input: this script hands off to staging. A production promotion
# is a separate decision with its own pipeline, not a variable someone can flip.
TESTINBOX_ENVIRONMENT="staging"
EXPECTED_IMAGE_REPOSITORY="${EXPECTED_IMAGE_REPOSITORY:-ghcr.io/yannisyoussef}"

commit="" api="" ingestion="" migrator="" web=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --commit)    commit="${2:-}"; shift 2 ;;
    --api)       api="${2:-}"; shift 2 ;;
    --ingestion) ingestion="${2:-}"; shift 2 ;;
    --migrator)  migrator="${2:-}"; shift 2 ;;
    --web)       web="${2:-}"; shift 2 ;;
    *) echo "usage: $(basename "$0") --commit <sha> --api <digest> --ingestion <digest> --migrator <digest> --web <digest>" >&2; exit 2 ;;
  esac
done

fail() { echo "HANDOFF REFUSED: $*" >&2; exit 1; }

# --- validate before anything leaves the runner ------------------------------
# The three settings below are interpolated into a curl CONFIG FILE, which is
# line-oriented: a newline in any of them injects further curl options *after*
# the line carrying the token — including a second `url =`, which makes curl
# POST the whole form (token included) to an attacker's host. They come from
# repository variables, which are writable with a weaker permission than
# reading a secret, so leaving them unvalidated would turn "can edit a
# variable" into "can read GITLAB_TRIGGER_TOKEN".
[[ "$GITLAB_API_URL" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?$ ]] ||
  fail "GITLAB_API_URL is not a plain https URL"
[[ "$GITLAB_PROJECT" =~ ^([0-9]+|[A-Za-z0-9._~-]+(%2[Ff][A-Za-z0-9._~-]+)+)$ ]] ||
  fail "GITLAB_PROJECT must be a numeric id or a URL-encoded path (e.g. group%2Fproject)"
[[ "$GITLAB_REF" =~ ^[A-Za-z0-9._/-]+$ ]] ||
  fail "GITLAB_REF is not a plain git ref"

# Ops validates all of this again on receipt; doing it here too means a bad
# payload fails where the mistake was made, with a legible message, instead of
# surfacing as an opaque pipeline failure in another system.
[[ -n "${GITLAB_TRIGGER_TOKEN:-}" ]] || fail "GITLAB_TRIGGER_TOKEN is not set"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "commit '$commit' is not a 40-character lowercase hex SHA"

for pair in "api:$api" "ingestion:$ingestion" "migrator:$migrator" "web:$web"; do
  name="${pair%%:*}"
  ref="${pair#*:}"
  [[ -n "$ref" ]] || fail "no image reference supplied for $name"
  # Ownership AND digest-pinning, by the same validator the deployment path
  # uses — a tag here would let the deployed bytes change after this ran.
  EXPECTED_IMAGE_REPOSITORY="$EXPECTED_IMAGE_REPOSITORY" \
    "$(dirname "${BASH_SOURCE[0]}")/validate-image-digest.sh" "$ref" >/dev/null \
    || fail "$name reference '$ref' is not a digest-pinned image under $EXPECTED_IMAGE_REPOSITORY"
done

# --- build the request -------------------------------------------------------
# The token goes in a 0600 config file rather than argv: process arguments are
# readable by anything else on the machine, and a trigger token is a credential
# for another system entirely.
config="$(mktemp)"
trap 'rm -f "$config"' EXIT
chmod 600 "$config"
{
  echo "url = \"${GITLAB_API_URL}/projects/${GITLAB_PROJECT}/trigger/pipeline\""
  echo 'request = "POST"'
  echo 'silent'
  echo 'show-error'
  # https only, and no redirect following: a 3xx must never carry the token to
  # another host, and neither must a downgraded scheme.
  echo 'proto = "=https"'
  # Bounded. Without these a blackholed connection burns the whole job timeout
  # and prints nothing.
  echo 'connect-timeout = 10'
  echo 'max-time = 60'
  echo 'retry = 2'
  echo 'retry-connrefused'
  echo 'write-out = "\nHTTP_STATUS:%{http_code}\n"'
  echo "form-string = \"token=${GITLAB_TRIGGER_TOKEN}\""
  echo "form-string = \"ref=${GITLAB_REF}\""
  echo "form-string = \"variables[TESTINBOX_ENVIRONMENT]=${TESTINBOX_ENVIRONMENT}\""
  echo "form-string = \"variables[TESTINBOX_COMMIT_SHA]=${commit}\""
  echo "form-string = \"variables[TESTINBOX_API_DIGEST]=${api}\""
  echo "form-string = \"variables[TESTINBOX_INGESTION_DIGEST]=${ingestion}\""
  echo "form-string = \"variables[TESTINBOX_MIGRATOR_DIGEST]=${migrator}\""
  echo "form-string = \"variables[TESTINBOX_WEB_DIGEST]=${web}\""
} > "$config"

echo "handing off to ${GITLAB_PROJECT} (ref ${GITLAB_REF})"
echo "  environment: ${TESTINBOX_ENVIRONMENT}"
echo "  commit:      ${commit}"
echo "  api:         ${api}"
echo "  ingestion:   ${ingestion}"
echo "  migrator:    ${migrator}"
echo "  web:         ${web}"

response="$(curl --config "$config" || true)"
status="$(printf '%s' "$response" | sed -n 's/^HTTP_STATUS:\([0-9]*\)$/\1/p' | tail -1)"
body="$(printf '%s' "$response" | sed '/^HTTP_STATUS:[0-9]*$/d')"

if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
  # The body can echo request parameters, so it is not printed verbatim; the
  # status and a bounded message are enough to diagnose without risking the
  # token appearing in a log.
  echo "HANDOFF FAILED: GitLab returned HTTP ${status:-<no response>}" >&2
  exit 1
fi

# These identify where the authoritative verdict will appear; they are not the
# verdict. Both are REMOTE data, so both are shape-checked before they go
# anywhere near a shell or a step summary.
# Anchored to the TOP-LEVEL object. A real GitLab trigger response nests
# `"user":{"id":…,"web_url":…}` and `"detailed_status"`, and a greedy `.*"id"`
# matches the LAST occurrence — so the unanchored form returned the trigger
# bot's user id and a link to its profile page. That link is the one a human
# follows to find the authoritative deployment verdict.
if command -v jq >/dev/null 2>&1; then
  pipeline_id="$(printf '%s' "$body" | jq -r '.id // empty' 2>/dev/null)"
  pipeline_url="$(printf '%s' "$body" | jq -r '.web_url // empty' 2>/dev/null)"
else
  pipeline_id="$(printf '%s' "$body" | sed -n 's/^{[^{]*"id"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -1)"
  pipeline_url="$(printf '%s' "$body" | sed -n 's|^{[^{]*"web_url"[[:space:]]*:[[:space:]]*"\([^"]*\)".*|\1|p' | head -1)"
fi

# GitLab's trigger endpoint answers 201 with a pipeline object. A 2xx carrying
# no pipeline id means something that is not GitLab answered — a proxy, a WAF,
# a captive portal, a mistyped GITLAB_API_URL — and reporting that as an
# accepted handoff would be the same class of lie this whole script is written
# to avoid.
[[ "$pipeline_id" =~ ^[0-9]+$ ]] ||
  fail "GitLab returned HTTP $status but no pipeline id; the release was NOT handed over"
# Deliberately an allow-list of URL characters with every shell metacharacter
# excluded. The `sed` capture above stops at `"` but happily accepts `$`,
# backticks and parentheses, and this value is written to $GITHUB_OUTPUT and
# then rendered into a step summary — i.e. into a shell. Anything unexpected is
# dropped rather than sanitised.
[[ "$pipeline_url" =~ ^https://[A-Za-z0-9._~:/?#@=\&+%-]+$ ]] || pipeline_url=""

echo "RELEASE HANDOFF ACCEPTED (HTTP $status)"
[[ -n "$pipeline_id" ]] && echo "  gitlab pipeline id:  $pipeline_id"
[[ -n "$pipeline_url" ]] && echo "  gitlab pipeline url: $pipeline_url"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "pipeline_id=$pipeline_id"
    echo "pipeline_url=$pipeline_url"
  } >> "$GITHUB_OUTPUT"
fi

echo
echo "NOTE: acceptance means the release candidate reached Ops. Deployment," >&2
echo "migration, readiness and the post-deployment synthetic suite run" >&2
echo "asynchronously in infinity-core; their verdict lives there." >&2
