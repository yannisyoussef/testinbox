#!/usr/bin/env bash
# ADR-023: the @types/node major must never exceed the minimum supported Node
# major. Types describe the API surface the compiler will accept; the floor is
# what a consumer may actually run. When the types run ahead, TypeScript
# cheerfully accepts a global or built-in that does not exist on the oldest
# runtime we promise to support, and nothing fails until a consumer on that
# runtime calls it. A compile-time ceiling above the runtime floor is a
# published compatibility claim we have not tested.
#
# The two projects anchor their floor differently, because they promise
# different things:
#
#   sdk/typescript  a PUBLISHED package — the floor is its `engines.node`,
#                   which is the contract consumers read.
#   web             a DEPLOYED app — it has no consumers and no `engines`, so
#                   the floor is the Node major its container actually runs
#                   (deploy/docker/web.Dockerfile, ARG NODE_VERSION).
#
# Raising either ceiling therefore requires raising the floor first — deliberately,
# and in the SDK's case as an amendment to the published support contract.
#
#   check-node-types-floor.sh            # check every project
#
# Exit 0 pass, 1 a ceiling exceeds its floor, 2 usage/parse error.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Overridable so the self-test can point at fixtures instead of the real tree.
SDK_MANIFEST="${SDK_MANIFEST:-$ROOT/sdk/typescript/package.json}"
WEB_MANIFEST="${WEB_MANIFEST:-$ROOT/web/package.json}"
WEB_DOCKERFILE="${WEB_DOCKERFILE:-$ROOT/deploy/docker/web.Dockerfile}"

fail=0

# Majors only. A types minor ahead of the floor is normal and harmless; it is
# the major that moves the API surface.
types_major() {
  local manifest="$1"
  python3 - "$manifest" <<'PY'
import json, re, sys
with open(sys.argv[1]) as fh:
    pkg = json.load(fh)
spec = (pkg.get("devDependencies", {}) or {}).get("@types/node") \
    or (pkg.get("dependencies", {}) or {}).get("@types/node")
if not spec:
    print("none")
    sys.exit(0)
m = re.search(r"(\d+)", spec)
if not m:
    sys.exit(f"cannot parse @types/node spec: {spec}")
print(m.group(1))
PY
}

engines_floor() {
  local manifest="$1"
  python3 - "$manifest" <<'PY'
import json, re, sys
with open(sys.argv[1]) as fh:
    pkg = json.load(fh)
spec = (pkg.get("engines", {}) or {}).get("node")
if not spec:
    sys.exit("no engines.node declared")
m = re.search(r"(\d+)", spec)
if not m:
    sys.exit(f"cannot parse engines.node: {spec}")
print(m.group(1))
PY
}

dockerfile_floor() {
  local dockerfile="$1"
  local major
  major="$(sed -n 's/^ARG NODE_VERSION=\([0-9][0-9]*\).*/\1/p' "$dockerfile" | head -1)"
  [[ -n "$major" ]] || { echo "no ARG NODE_VERSION in $dockerfile" >&2; exit 2; }
  echo "$major"
}

compare() {
  local label="$1" ceiling="$2" floor="$3" floor_desc="$4"
  if [[ "$ceiling" == "none" ]]; then
    echo "skip: $label declares no @types/node"
    return
  fi
  if (( ceiling > floor )); then
    echo "FAIL: $label — @types/node ^${ceiling} exceeds $floor_desc (Node ${floor})." >&2
    echo "      Compiling against Node ${ceiling} declarations while supporting Node ${floor}" >&2
    echo "      claims a compatibility we do not test. Raise the floor first, or pin" >&2
    echo "      @types/node to the ^${floor} line." >&2
    fail=1
  else
    echo "ok:   $label — @types/node ^${ceiling} <= $floor_desc (Node ${floor})"
  fi
}

# Resolved into variables first, deliberately. Inside a function's argument list
# a failing command substitution does not trip `set -e`, so a manifest we cannot
# parse would reach compare() as an empty string and be reported as a version
# mismatch rather than as the parse error it is. The self-test pins this.
sdk_types="$(types_major "$SDK_MANIFEST")" || exit 2
sdk_floor="$(engines_floor "$SDK_MANIFEST")" || exit 2
web_types="$(types_major "$WEB_MANIFEST")" || exit 2
web_floor="$(dockerfile_floor "$WEB_DOCKERFILE")" || exit 2

compare "sdk/typescript" "$sdk_types" "$sdk_floor" "its engines.node floor"
compare "web" "$web_types" "$web_floor" "its deployed container"

echo "----"
if (( fail )); then
  echo "check-node-types-floor: FAILED"
  exit 1
fi
echo "check-node-types-floor: all projects within their floor"
