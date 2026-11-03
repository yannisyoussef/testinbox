#!/usr/bin/env bash
# Preflight for the account-lifecycle operator image (TI-DELIVERY-002b,
# docs/delivery/ti-delivery-002/LIFECYCLE.md "Operator procedure").
#
#   scripts/check-lifecycle-image.sh <registry>/testinbox-api@sha256:<digest>
#
# Passes only when `account-lifecycle` with no arguments exits 2 AND prints the
# exact marker line. An image below the floor ignores the subcommand and boots a
# full API: it runs with --network none so it reaches nothing, and is bounded by
# `timeout` where available. JAVA_TOOL_OPTIONS is cleared because the image sets
# it and the JVM would otherwise print "Picked up JAVA_TOOL_OPTIONS: ..." first.
#
# Exit: 0 the image is at or above the floor, 1 it is not, 2 usage.
set -uo pipefail

MARKER="account-lifecycle-cli v2 subcommands=suspend,resume,delete,status,process-deletions,restore-reconcile"
DOCKER="${DOCKER:-docker}"
[ "$#" -eq 1 ] && [ -n "$1" ] || { echo "usage: $(basename "$0") <image-reference>" >&2; exit 2; }
image="$1"

run=("$DOCKER" run --rm --network none -e JAVA_TOOL_OPTIONS= "$image" account-lifecycle)
if command -v timeout >/dev/null 2>&1; then run=(timeout 120 "${run[@]}"); fi

out="$("${run[@]}" 2>&1)"; rc=$?
if [ "$rc" -ne 2 ]; then
    echo "FAIL: $image: account-lifecycle exited $rc, not 2 (below the TI-DELIVERY-002b floor, or it booted a full API)" >&2
    exit 1
fi
if ! printf '%s\n' "$out" | grep -qxF "$MARKER"; then
    echo "FAIL: $image: exit 2 but no marker line '$MARKER' (below the TI-DELIVERY-002b floor)" >&2
    exit 1
fi
echo "OK: $image carries account-lifecycle with process-deletions"
