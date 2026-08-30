#!/usr/bin/env bash
# CI guard (docs/quality/strategy.md): a green build exit code is not
# sufficient evidence that tests executed. Verify that every expected module
# produced JUnit XML reports, that they contain a sane number of tests, and
# that nothing was skipped outside an explicit allow-list (currently empty).
set -euo pipefail

cd "$(dirname "$0")/.."

# module:minimum-expected-test-count
EXPECTED=(
  "backend/architecture:5"
  "backend/domain:10"
  "backend/application:25"
  "backend/persistence:12"
  "backend/storage:3"
  "backend/notification:5"
  "backend/ingestion:14"
  "backend/api:18"
)
# e2e is verified separately when the e2e job runs (backend/e2e:9).
if [[ "${VERIFY_E2E:-false}" == "true" ]]; then
  EXPECTED+=("backend/e2e:9")
fi

failures=0
total=0
for entry in "${EXPECTED[@]}"; do
  module="${entry%%:*}"
  minimum="${entry##*:}"
  dir="$module/build/test-results/test"
  if [[ ! -d "$dir" ]]; then
    echo "FAIL: no test results directory for $module ($dir missing)"
    failures=$((failures + 1))
    continue
  fi
  tests=0
  skipped=0
  test_failures=0
  errors=0
  for xml in "$dir"/TEST-*.xml; do
    [[ -e "$xml" ]] || continue
    t=$(sed -n 's/.*<testsuite[^>]* tests="\([0-9]*\)".*/\1/p' "$xml" | head -1)
    s=$(sed -n 's/.*<testsuite[^>]* tests="[0-9]*" skipped="\([0-9]*\)".*/\1/p' "$xml" | head -1)
    f=$(sed -n 's/.*failures="\([0-9]*\)".*/\1/p' "$xml" | head -1)
    e=$(sed -n 's/.*errors="\([0-9]*\)".*/\1/p' "$xml" | head -1)
    tests=$((tests + ${t:-0}))
    skipped=$((skipped + ${s:-0}))
    test_failures=$((test_failures + ${f:-0}))
    errors=$((errors + ${e:-0}))
  done
  if (( tests < minimum )); then
    echo "FAIL: $module ran $tests tests, expected at least $minimum (silently skipped suite?)"
    failures=$((failures + 1))
  elif (( skipped > 0 )); then
    echo "FAIL: $module skipped $skipped tests (no skip allow-list entries exist)"
    failures=$((failures + 1))
  elif (( test_failures > 0 || errors > 0 )); then
    echo "FAIL: $module reported $test_failures failures / $errors errors"
    failures=$((failures + 1))
  else
    echo "OK:   $module — $tests tests, 0 skipped"
  fi
  total=$((total + tests))
done

echo "----"
echo "verified $total backend tests across ${#EXPECTED[@]} modules"
if (( failures > 0 )); then
  echo "verify-test-results: $failures module(s) failed verification"
  exit 1
fi
