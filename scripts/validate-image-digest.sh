#!/usr/bin/env bash
# Validates that an image reference is one of ours, pinned by digest.
#
# A manual redeploy/rollback takes image references as workflow_dispatch input
# (TI-DEPLOY-001 §29). Without this, "redeploy" is an arbitrary-code-execution
# primitive: anyone able to run the workflow could point staging at any image
# on any registry. Two independent properties are checked, and both matter:
#
#   1. OWNERSHIP — the reference is under the expected registry/owner and names
#      one of the four TestInbox images. Nothing else is deployable.
#   2. IMMUTABILITY — the reference is pinned by `@sha256:<64 hex>`. A tag can
#      be repointed after it was validated, so a tag is not an identity
#      (ADR-028). `:latest` in particular is never a deployment reference.
#
# Usage:  validate-image-digest.sh <image-ref> [<image-ref> ...]
#         EXPECTED_IMAGE_REPOSITORY=ghcr.io/owner validate-image-digest.sh ...
set -uo pipefail

EXPECTED_IMAGE_REPOSITORY="${EXPECTED_IMAGE_REPOSITORY:-ghcr.io/yannisyoussef}"
ALLOWED_NAMES=(testinbox-api testinbox-ingestion testinbox-web testinbox-migrator)

usage() {
  echo "usage: $(basename "$0") <image-ref> [<image-ref> ...]" >&2
  exit 2
}

validate_one() {
  local ref="$1"

  if [[ "$ref" != *"@"* ]]; then
    echo "REJECT: $ref is not pinned by digest (expected name@sha256:<64 hex>)"
    return 1
  fi

  local name="${ref%%@*}" digest="${ref#*@}"

  # Exactly one '@': a second would mean the name or digest is not what it looks like.
  if [[ "$digest" == *"@"* ]]; then
    echo "REJECT: $ref contains more than one '@'"
    return 1
  fi
  if [[ ! "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "REJECT: $ref has a malformed digest (expected sha256 followed by 64 lowercase hex)"
    return 1
  fi
  # A tag before the digest is redundant at best and misleading at worst
  # (`:v1@sha256:...` reads as v1 while deploying whatever the digest is).
  if [[ "${name##*/}" == *":"* ]]; then
    echo "REJECT: $ref carries a tag as well as a digest; use name@sha256:... only"
    return 1
  fi
  if [[ "$name" != "$EXPECTED_IMAGE_REPOSITORY/"* ]]; then
    echo "REJECT: $ref is not under $EXPECTED_IMAGE_REPOSITORY"
    return 1
  fi

  local short="${name#"$EXPECTED_IMAGE_REPOSITORY"/}"
  # No further path segments: ghcr.io/owner/evil/testinbox-api must not pass.
  if [[ "$short" == *"/"* ]]; then
    echo "REJECT: $ref has an unexpected path segment ('$short')"
    return 1
  fi
  local allowed
  for allowed in "${ALLOWED_NAMES[@]}"; do
    if [[ "$short" == "$allowed" ]]; then
      echo "OK:     $ref"
      return 0
    fi
  done
  echo "REJECT: $ref does not name a TestInbox image (${ALLOWED_NAMES[*]})"
  return 1
}

(( $# > 0 )) || usage

rejected=0
for ref in "$@"; do
  validate_one "$ref" || rejected=$((rejected + 1))
done

if (( rejected > 0 )); then
  echo "validate-image-digest: $rejected reference(s) rejected" >&2
  exit 1
fi
