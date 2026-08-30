#!/usr/bin/env bash
# CI guard (docs/quality/strategy.md): a green build exit code is not
# sufficient evidence that tests executed. Verify that every module expected
# *in the current verification scope* produced JUnit XML reports, that they
# contain a sane number of tests, and that nothing failed, errored, or was
# skipped outside an explicit allow-list (currently empty).
#
# Scopes exist because CI jobs run disjoint sets of suites: the backend job
# never runs :e2e:test, and the e2e job never runs the other modules. A
# single "all modules" expectation therefore fails whichever job it is not
# describing.
#
#   VERIFY_SCOPE=backend   modules built by `./gradlew build -x :e2e:test`
#   VERIFY_SCOPE=e2e       modules built by `./gradlew :e2e:test`
#   VERIFY_SCOPE=all       every module (full local verification; default)
#
# Equivalent: --scope <backend|e2e|all>. VERIFY_ROOT overrides the directory
# the module paths are resolved against (used by the script's own tests).
set -euo pipefail

SCOPE="${VERIFY_SCOPE:-all}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --scope)
      SCOPE="${2:-}"
      shift 2
      ;;
    --scope=*)
      SCOPE="${1#*=}"
      shift
      ;;
    *)
      echo "usage: $(basename "$0") [--scope backend|e2e|all]" >&2
      exit 2
      ;;
  esac
done

if [[ -n "${VERIFY_E2E:-}" ]]; then
  echo "FAIL: VERIFY_E2E is no longer supported; use VERIFY_SCOPE=backend|e2e|all" >&2
  exit 2
fi

ROOT="${VERIFY_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

# module:minimum-expected-test-count
BACKEND_MODULES=(
  "backend/architecture:7"
  "backend/domain:25"
  "backend/application:35"
  "backend/persistence:25"
  "backend/storage:3"
  "backend/notification:5"
  "backend/observability:5"
  "backend/ingestion:20"
  "backend/api:28"
)
E2E_MODULES=(
  "backend/e2e:12"
)

case "$SCOPE" in
  backend) EXPECTED=("${BACKEND_MODULES[@]}") ;;
  e2e) EXPECTED=("${E2E_MODULES[@]}") ;;
  all) EXPECTED=("${BACKEND_MODULES[@]}" "${E2E_MODULES[@]}") ;;
  *)
    echo "FAIL: unknown verification scope '$SCOPE' (expected backend, e2e or all)" >&2
    exit 2
    ;;
esac

# Reads one attribute off the <testsuite> element of a JUnit XML report.
suite_attr() {
  local xml="$1" attr="$2" value
  value=$(tr '\n' ' ' <"$xml" | sed -n "s/.*<testsuite [^>]*${attr}=\"\([0-9]*\)\".*/\1/p" | head -1)
  echo "${value:-0}"
}

failures=0
total=0
for entry in "${EXPECTED[@]}"; do
  module="${entry%%:*}"
  minimum="${entry##*:}"
  dir="$ROOT/$module/build/test-results/test"
  if [[ ! -d "$dir" ]]; then
    echo "FAIL: no test results directory for $module ($module/build/test-results/test missing)"
    failures=$((failures + 1))
    continue
  fi
  tests=0
  skipped=0
  test_failures=0
  errors=0
  reports=0
  for xml in "$dir"/TEST-*.xml; do
    [[ -e "$xml" ]] || continue
    reports=$((reports + 1))
    tests=$((tests + $(suite_attr "$xml" tests)))
    skipped=$((skipped + $(suite_attr "$xml" skipped)))
    test_failures=$((test_failures + $(suite_attr "$xml" failures)))
    errors=$((errors + $(suite_attr "$xml" errors)))
  done
  if (( reports == 0 )); then
    echo "FAIL: $module produced no TEST-*.xml reports (suite did not execute)"
    failures=$((failures + 1))
  elif (( tests < minimum )); then
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
echo "scope=$SCOPE: verified $total tests across ${#EXPECTED[@]} module(s)"
if (( failures > 0 )); then
  echo "verify-test-results: $failures module(s) failed verification"
  exit 1
fi
