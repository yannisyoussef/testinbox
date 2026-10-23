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

# A cache holding a file that is executable but not a working binary — a
# truncated download, or a Linux build fetched on macOS by an older revision of
# the gate — must be replaced rather than reused. Before this was checked by
# execution, that state was sticky: every run failed with a blank version banner
# until $CACHE_DIR was deleted by hand. Uses its own cache directory so the
# poison cannot escape into the shared one, which costs one extra download.
poison_cache="$(mktemp -d)"
printf 'not-a-real-binary' > "$poison_cache/oasdiff"
chmod +x "$poison_cache/oasdiff"
export OASDIFF_CACHE_DIR="$poison_cache"
check "a poisoned cache is replaced, not reused" 0 "$DATA/base.yaml" "$DATA/base.yaml"
unset OASDIFF_CACHE_DIR
rm -rf "$poison_cache"

echo "----"
echo "openapi-breaking-check.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
