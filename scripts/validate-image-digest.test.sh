#!/usr/bin/env bash
# Tests for validate-image-digest.sh. This script is the only thing standing
# between a workflow_dispatch input and `docker pull`, so its rejections are
# tested explicitly: a validator that accepts everything is worse than none,
# because it looks like a control.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VALIDATOR="$SCRIPT_DIR/validate-image-digest.sh"
export EXPECTED_IMAGE_REPOSITORY="ghcr.io/testowner"

DIGEST="sha256:$(printf 'a%.0s' {1..64})"
pass=0
fail=0

check() {
  local name="$1" expected="$2"; shift 2
  local output status
  output=$("$VALIDATOR" "$@" 2>&1)
  status=$?
  if [[ "$status" == "$expected" ]]; then
    echo "ok   — $name"
    pass=$((pass + 1))
  else
    echo "FAIL — $name (expected exit $expected, got $status)"
    echo "$output" | sed 's/^/       /'
    fail=$((fail + 1))
  fi
}

# --- accepted ---------------------------------------------------------------
check "a digest-pinned TestInbox image is accepted" 0 "ghcr.io/testowner/testinbox-api@$DIGEST"
check "all four deployables are accepted together" 0 \
  "ghcr.io/testowner/testinbox-api@$DIGEST" \
  "ghcr.io/testowner/testinbox-ingestion@$DIGEST" \
  "ghcr.io/testowner/testinbox-web@$DIGEST" \
  "ghcr.io/testowner/testinbox-migrator@$DIGEST"

# --- rejected ---------------------------------------------------------------
check "a mutable tag is rejected"                1 "ghcr.io/testowner/testinbox-api:latest"
check "a tag that looks like a version is rejected" 1 "ghcr.io/testowner/testinbox-api:v1.2.3"
check "a tag alongside a digest is rejected"     1 "ghcr.io/testowner/testinbox-api:v1@$DIGEST"
check "another owner is rejected"                1 "ghcr.io/attacker/testinbox-api@$DIGEST"
check "another registry is rejected"             1 "docker.io/testowner/testinbox-api@$DIGEST"
check "an extra path segment is rejected"        1 "ghcr.io/testowner/evil/testinbox-api@$DIGEST"
check "an image that is not ours is rejected"    1 "ghcr.io/testowner/postgres@$DIGEST"
check "a short digest is rejected"               1 "ghcr.io/testowner/testinbox-api@sha256:abc123"
check "an uppercase digest is rejected"          1 "ghcr.io/testowner/testinbox-api@sha256:$(printf 'A%.0s' {1..64})"
check "a non-sha256 algorithm is rejected"       1 "ghcr.io/testowner/testinbox-api@md5:$(printf 'a%.0s' {1..32})"
check "a double '@' is rejected"                 1 "ghcr.io/testowner/testinbox-api@$DIGEST@$DIGEST"
check "one bad reference fails the whole set"    1 \
  "ghcr.io/testowner/testinbox-api@$DIGEST" "ghcr.io/attacker/testinbox-web@$DIGEST"
check "no arguments is a usage error"            2

echo "----"
echo "validate-image-digest.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
