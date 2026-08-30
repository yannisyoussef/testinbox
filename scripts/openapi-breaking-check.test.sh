#!/usr/bin/env bash
# Proves the compatibility gate actually discriminates: a gate that passes
# everything is indistinguishable from no gate at all.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/openapi-breaking-check.sh"
DATA="$SCRIPT_DIR/testdata/openapi"

pass=0
fail=0

check() {
  local name="$1" expected="$2" base="$3" revision="$4"
  local output status
  output=$("$GATE" "$base" "$revision" 2>&1)
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

check "identical specs pass" 0 "$DATA/base.yaml" "$DATA/base.yaml"
check "additive change passes" 0 "$DATA/base.yaml" "$DATA/additive.yaml"
check "breaking change fails" 1 "$DATA/base.yaml" "$DATA/breaking.yaml"
check "missing revision spec is a usage error" 2 "$DATA/base.yaml" "$DATA/nope.yaml"

echo "----"
echo "openapi-breaking-check.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
