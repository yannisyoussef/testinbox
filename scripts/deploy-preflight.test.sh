#!/usr/bin/env bash
# Tests the preflight behaviour of deploy/staging/deploy.sh.
#
# The ordering in that script is a safety property, not a convenience: image
# references are validated BEFORE anything is pulled, and a missing variable
# aborts before any container is touched. Both are invisible in review and
# easy to reorder by accident, so they are asserted here against a stubbed
# `docker` that records whether it was ever reached.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY="$REPO_ROOT/deploy/staging/deploy.sh"

pass=0
fail=0
DIGEST="sha256:$(printf 'b%.0s' {1..64})"
STUB_DIR=$(mktemp -d)
trap 'rm -rf "$STUB_DIR"' EXIT

# A `docker` that never does anything but leaves a trace that it was invoked.
cat >"$STUB_DIR/docker" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$DOCKER_STUB_MARKER"
exit 97
STUB
chmod +x "$STUB_DIR/docker"

run_deploy() {
  rm -f "$STUB_DIR/marker"
  DOCKER_STUB_MARKER="$STUB_DIR/marker" \
  PATH="$STUB_DIR:$PATH" \
  EXPECTED_IMAGE_REPOSITORY="ghcr.io/testowner" \
    "$@" "$DEPLOY" >/dev/null 2>&1
  echo $?
}

check() {
  local name="$1" expected_status="$2" expect_docker="$3" status
  status=$(run_deploy "${@:4}")
  local reached=false
  [[ -f "$STUB_DIR/marker" ]] && reached=true
  if [[ "$status" == "$expected_status" && "$reached" == "$expect_docker" ]]; then
    echo "ok   — $name"
    pass=$((pass + 1))
  else
    echo "FAIL — $name (exit $status, docker reached=$reached; expected exit $expected_status, docker reached=$expect_docker)"
    fail=$((fail + 1))
  fi
}

valid_env=(
  env
  "TESTINBOX_API_IMAGE=ghcr.io/testowner/testinbox-api@$DIGEST"
  "TESTINBOX_INGESTION_IMAGE=ghcr.io/testowner/testinbox-ingestion@$DIGEST"
  "TESTINBOX_WEB_IMAGE=ghcr.io/testowner/testinbox-web@$DIGEST"
  "TESTINBOX_MIGRATOR_IMAGE=ghcr.io/testowner/testinbox-migrator@$DIGEST"
)

# A missing image reference must stop the script before it touches anything.
check "a missing image reference aborts before docker is invoked" 1 false env -u TESTINBOX_API_IMAGE \
  "TESTINBOX_INGESTION_IMAGE=ghcr.io/testowner/testinbox-ingestion@$DIGEST" \
  "TESTINBOX_WEB_IMAGE=ghcr.io/testowner/testinbox-web@$DIGEST" \
  "TESTINBOX_MIGRATOR_IMAGE=ghcr.io/testowner/testinbox-migrator@$DIGEST"

# An untrusted or unpinned reference must be refused BEFORE the pull.
check "an image from another owner is refused before the pull" 1 false env \
  "TESTINBOX_API_IMAGE=ghcr.io/attacker/testinbox-api@$DIGEST" \
  "TESTINBOX_INGESTION_IMAGE=ghcr.io/testowner/testinbox-ingestion@$DIGEST" \
  "TESTINBOX_WEB_IMAGE=ghcr.io/testowner/testinbox-web@$DIGEST" \
  "TESTINBOX_MIGRATOR_IMAGE=ghcr.io/testowner/testinbox-migrator@$DIGEST"

check "a mutable tag is refused before the pull" 1 false env \
  "TESTINBOX_API_IMAGE=ghcr.io/testowner/testinbox-api:latest" \
  "TESTINBOX_INGESTION_IMAGE=ghcr.io/testowner/testinbox-ingestion@$DIGEST" \
  "TESTINBOX_WEB_IMAGE=ghcr.io/testowner/testinbox-web@$DIGEST" \
  "TESTINBOX_MIGRATOR_IMAGE=ghcr.io/testowner/testinbox-migrator@$DIGEST"

# The positive control: with a valid set the script proceeds to docker, and
# still fails non-zero because the stub does. Without this the three tests
# above would also pass for a script that aborted for the wrong reason.
check "a valid digest set proceeds to docker and propagates its failure" 97 true "${valid_env[@]}"

echo "----"
echo "deploy-preflight.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
