#!/usr/bin/env bash
# Proves check-node-types-floor.sh actually discriminates. A gate that passes
# everything is indistinguishable from no gate at all (docs/quality/strategy.md),
# and this one exists precisely to catch a bump that looks routine — Dependabot
# proposing @types/node 26 against a Node 22 floor (#33, #36).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/check-node-types-floor.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

# $1 name, $2 expected exit, $3 sdk types spec, $4 sdk engines, $5 web types, $6 web docker major
check() {
  local name="$1" expected="$2" sdk_types="$3" sdk_engines="$4" web_types="$5" web_node="$6"
  cat > "$TMP/sdk.json" <<EOF
{ "name": "sdk", "engines": { "node": "$sdk_engines" },
  "devDependencies": { "@types/node": "$sdk_types" } }
EOF
  cat > "$TMP/web.json" <<EOF
{ "name": "web", "devDependencies": { "@types/node": "$web_types" } }
EOF
  printf 'ARG NODE_VERSION=%s\nFROM node:${NODE_VERSION}-alpine\n' "$web_node" > "$TMP/web.Dockerfile"

  local output status
  output=$(SDK_MANIFEST="$TMP/sdk.json" WEB_MANIFEST="$TMP/web.json" \
    WEB_DOCKERFILE="$TMP/web.Dockerfile" "$GATE" 2>&1)
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

# The state this change establishes: types on the 22 line, floor at 22.
check "types equal to the floor pass"            0 "^22.10.0" ">=22" "^22.15.0" "22"
# The exact bumps this gate exists to stop.
check "SDK types above the floor fail (#33)"     1 "^26.5.1"  ">=22" "^22.15.0" "22"
check "web types above the container fail (#36)" 1 "^22.10.0" ">=22" "^26.5.1"  "22"
check "both above their floor fail"              1 "^26.5.1"  ">=22" "^26.5.1"  "22"
# Types BEHIND the floor are safe: they describe less than the runtime offers.
check "types below the floor pass"               0 "^20.1.0"  ">=22" "^20.1.0"  "22"
# Raising the floor is what unlocks the ceiling — the intended upgrade path.
check "raising the floor unlocks the ceiling"    0 "^26.5.1"  ">=26" "^26.5.1"  "26"
# A minor ahead is not a major ahead.
check "a higher minor within the floor passes"   0 "^22.99.0" ">=22" "^22.99.0" "22"
# Malformed input must abort, not silently pass.
check "a missing engines.node is an error"       2 "^22.10.0" ""     "^22.15.0" "22"

echo "----"
echo "check-node-types-floor.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
