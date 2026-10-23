#!/usr/bin/env bash
# Rollback floors (ADR-034; ADR-035 §14 (e) and §18 gate 5): refuses a
# candidate commit that predates any commit in deploy/rollback-floors.txt,
# unless the hazard is explicitly acknowledged.
#
# The single implementation of the check. It is shared by the production gate
# (verify-production-candidate.sh) and staging's deploy.sh, so the two cannot
# drift apart. A floor is ancestry: the candidate must CONTAIN every floor
# commit, so the check needs the full history of this checkout.
#
# Fails closed: a missing floors file, a malformed entry, a floor or candidate
# absent from the checkout, all refuse. None of them means "no floors".
#
# Usage: check-rollback-floors.sh --candidate <40-hex> [--acknowledge-rollback-hazard]
# Env:   ROLLBACK_FLOORS (default: deploy/rollback-floors.txt next to this script)
#        ROLLBACK_FLOORS_REPO (the checkout whose history is evaluated; default: the current directory)
# Exit:  0 allowed (a WARNING on stderr when acknowledged), 1 refused, 2 usage
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FLOORS="${ROLLBACK_FLOORS:-$SCRIPT_DIR/../deploy/rollback-floors.txt}"
GIT=(git)
[[ -n "${ROLLBACK_FLOORS_REPO:-}" ]] && GIT=(git -C "$ROLLBACK_FLOORS_REPO")

candidate="" acknowledge=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --candidate) candidate="${2:-}"; shift 2 ;;
    --acknowledge-rollback-hazard) acknowledge=true; shift ;;
    *) echo "usage: $(basename "$0") --candidate <sha> [--acknowledge-rollback-hazard]" >&2; exit 2 ;;
  esac
done

refuse() { echo "REFUSED: $*" >&2; exit 1; }

[[ "$candidate" =~ ^[0-9a-f]{40}$ ]] || refuse "candidate '$candidate' is not a 40-character lowercase hex SHA"
[[ -f "$FLOORS" ]] || refuse "rollback floors file not found at $FLOORS; the checkout cannot evaluate rollback hazards"
while IFS= read -r line; do
  line="${line%%#*}"; [[ -n "${line// /}" ]] || continue
  floor="${line%% *}"; why="${line#* }"
  [[ "$floor" =~ ^[0-9a-f]{40}$ ]] || refuse "rollback floor '$floor' in $FLOORS is not a commit SHA"
  "${GIT[@]}" cat-file -e "${floor}^{commit}" 2>/dev/null \
    || refuse "rollback floor $floor is not in this checkout; fetch full history (fetch-depth: 0) so floors can be evaluated"
  "${GIT[@]}" cat-file -e "${candidate}^{commit}" 2>/dev/null \
    || refuse "candidate $candidate is not in this checkout; fetch full history so rollback floors can be evaluated"
  if ! "${GIT[@]}" merge-base --is-ancestor "$floor" "$candidate"; then
    if [[ "$acknowledge" == true ]]; then
      echo "WARNING: candidate predates rollback floor $floor ($why); proceeding on explicit acknowledgement" >&2
    else
      refuse "candidate $candidate predates rollback floor $floor: $why. Re-run with --acknowledge-rollback-hazard only if that outage is understood and announced"
    fi
  fi
done < "$FLOORS"
echo "ok — rollback floors evaluated"
