#!/usr/bin/env bash
# Container vulnerability policy (ADR-028 §6, as amended).
#
# TWO MODES, and the difference is the whole point:
#
#   informational (develop, pull requests) — findings are printed, nothing is
#   blocked. A CVE disclosed in a base image overnight is not a regression
#   introduced by the change that happens to build next, and blocking unrelated
#   work on it is the noisy-gate failure mode that ends with the scanner
#   switched off.
#
#   blocking (promotion to master) — HIGH/CRITICAL fail the run. Promotion is a
#   deliberate, infrequent, human act: the right moment to require a decision
#   rather than a shrug.
#
# `--ignore-unfixed` applies in BOTH modes, and that is the load-bearing half.
# Refusing to promote over a vulnerability nobody can remediate does not make
# the release safer; it makes the release not happen, which is how a blocking
# scanner earns a permanent bypass.
#
# Extracted from the workflow so the policy is testable: `scan-images.test.sh`
# drives it with a stubbed `trivy` and asserts each mode's exit behaviour, the
# flags, and that every image was actually scanned.
#
# Usage: [ENFORCE=true] scan-images.sh <image-ref> [<image-ref> ...]
set -uo pipefail

ENFORCE="${ENFORCE:-false}"
TRIVY="${TRIVY_BIN:-trivy}"
SEVERITY="${TRIVY_SEVERITY:-HIGH,CRITICAL}"

(( $# > 0 )) || { echo "usage: $(basename "$0") <image-ref> [<image-ref> ...]" >&2; exit 2; }

# The scanner must be present and runnable. Without this, a failed download
# leaves every scan erroring, and in informational mode the whole thing goes
# quietly green having checked nothing.
if ! "$TRIVY" --version >/dev/null 2>&1; then
  echo "::error::trivy is not runnable ($TRIVY); the vulnerability scan did not run" >&2
  exit 2
fi

if [[ "$ENFORCE" == "true" ]]; then
  echo "policy: BLOCKING on $SEVERITY with a fix available"
  exit_code=1
else
  echo "policy: informational — findings are reported, the build is not blocked"
  exit_code=0
fi

findings=0
scanned=0
for ref in "$@"; do
  echo "::group::trivy $ref"
  "$TRIVY" image \
    --severity "$SEVERITY" \
    --ignore-unfixed \
    --exit-code "$exit_code" \
    --format table \
    --no-progress \
    "$ref" || findings=1
  echo "::endgroup::"
  scanned=$((scanned + 1))
done

echo "scanned $scanned image(s)"

if [[ "$ENFORCE" == "true" && "$findings" == "1" ]]; then
  echo "::error::HIGH/CRITICAL vulnerabilities with available fixes block promotion (ADR-028 §6)"
  exit 1
fi
exit 0
