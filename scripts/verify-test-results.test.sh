#!/usr/bin/env bash
# Tests for verify-test-results.sh. The verifier is the gate that proves the
# rest of the suite actually ran, so its own failure modes are tested: a
# verifier that passes vacuously is worse than no verifier.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFIER="$SCRIPT_DIR/verify-test-results.sh"

pass=0
fail=0

# Writes a JUnit report for $module with the given counters.
fixture() {
  local root="$1" module="$2" tests="$3" skipped="${4:-0}" failures="${5:-0}" errors="${6:-0}"
  local dir="$root/$module/build/test-results/test"
  mkdir -p "$dir"
  cat >"$dir/TEST-Fixture.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Fixture" tests="$tests" skipped="$skipped" failures="$failures" errors="$errors" timestamp="2026-01-01T00:00:00">
</testsuite>
XML
}

# Every backend module, each comfortably above its minimum. The count is a
# single large number rather than per-module values so that raising a real
# minimum in the verifier never silently breaks this self-test — the
# below-minimum case is asserted explicitly elsewhere instead.
ABOVE_ANY_MINIMUM=1000

full_backend() {
  local root="$1"
  fixture "$root" backend/architecture "$ABOVE_ANY_MINIMUM"
  fixture "$root" backend/domain "$ABOVE_ANY_MINIMUM"
  fixture "$root" backend/application "$ABOVE_ANY_MINIMUM"
  fixture "$root" backend/persistence "$ABOVE_ANY_MINIMUM"
  fixture "$root" backend/storage "$ABOVE_ANY_MINIMUM"
  fixture "$root" backend/notification "$ABOVE_ANY_MINIMUM"
  fixture "$root" backend/observability "$ABOVE_ANY_MINIMUM"
  fixture "$root" backend/ingestion "$ABOVE_ANY_MINIMUM"
  fixture "$root" backend/api "$ABOVE_ANY_MINIMUM"
}

check() {
  local name="$1" expected_status="$2" scope="$3" root="$4"
  local output status
  output=$(VERIFY_ROOT="$root" VERIFY_SCOPE="$scope" "$VERIFIER" 2>&1)
  status=$?
  if [[ "$status" == "$expected_status" ]]; then
    echo "ok   — $name"
    pass=$((pass + 1))
  else
    echo "FAIL — $name (expected exit $expected_status, got $status)"
    echo "$output" | sed 's/^/       /'
    fail=$((fail + 1))
  fi
}

root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT

# backend scope: all backend modules present and healthy, no e2e results at all.
full_backend "$root/backend-only"
check "backend scope passes without any e2e results" 0 backend "$root/backend-only"
check "all scope fails when e2e results are missing" 1 all "$root/backend-only"
check "e2e scope fails when e2e results are missing" 1 e2e "$root/backend-only"

# e2e scope: only e2e results present — the shape of the e2e CI job.
fixture "$root/e2e-only" backend/e2e "$ABOVE_ANY_MINIMUM"
check "e2e scope passes with only e2e results" 0 e2e "$root/e2e-only"
check "backend scope fails when backend results are missing" 1 backend "$root/e2e-only"

# all scope: full local verification.
full_backend "$root/complete"
fixture "$root/complete" backend/e2e "$ABOVE_ANY_MINIMUM"
check "all scope passes when every module reported" 0 all "$root/complete"

# Missing module inside the scope.
full_backend "$root/missing-module"
rm -rf "$root/missing-module/backend/api"
check "missing module report fails" 1 backend "$root/missing-module"

# Report directory exists but contains no XML (task ran, produced nothing).
full_backend "$root/empty-reports"
rm -f "$root/empty-reports/backend/api/build/test-results/test/"*.xml
check "empty report directory fails" 1 backend "$root/empty-reports"

# Below the module minimum.
full_backend "$root/too-few"
fixture "$root/too-few" backend/api 3
check "test count below the module minimum fails" 1 backend "$root/too-few"

# Skips are not allowed (no allow-list entries exist).
full_backend "$root/skipped"
fixture "$root/skipped" backend/api 30 1
check "any skipped test fails" 1 backend "$root/skipped"

# Failures and errors.
full_backend "$root/failing"
fixture "$root/failing" backend/api 30 0 1 0
check "reported test failure fails" 1 backend "$root/failing"

full_backend "$root/erroring"
fixture "$root/erroring" backend/api 30 0 0 1
check "reported test error fails" 1 backend "$root/erroring"

# Scope misuse must be loud, never silently vacuous.
check "unknown scope is rejected" 2 nonsense "$root/complete"

output=$(VERIFY_ROOT="$root/complete" VERIFY_E2E=true "$VERIFIER" 2>&1)
if [[ $? == 2 && "$output" == *"VERIFY_E2E is no longer supported"* ]]; then
  echo "ok   — retired VERIFY_E2E flag is rejected"
  pass=$((pass + 1))
else
  echo "FAIL — retired VERIFY_E2E flag should be rejected"
  fail=$((fail + 1))
fi

echo "----"
echo "verify-test-results.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
