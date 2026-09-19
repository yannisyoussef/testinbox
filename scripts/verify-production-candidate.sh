#!/usr/bin/env bash
# Decides whether a source commit is a production candidate (ADR-034).
#
# Images are built for the DEVELOP tip, and the commit that lands on `master`
# may be a merge or a squash with a different SHA for which no image exists.
# So the identity a production promotion carries is not "whatever master points
# at"; it is:
#
#     approved source SHA  <->  the four digests its :<sha> tags resolve to
#                          <->  a provenance attestation binding each digest
#                               to that SHA, this repository, and the
#                               build-images workflow
#                          <->  (promotion mode) a MERGED pull request into
#                               master whose head was that SHA, from develop
#
# The attestation is the load-bearing link. A tag can be repointed by anything
# holding packages:write; a digest cannot be forged to attest to a commit it
# was not built from. Every check is against artifacts that already exist —
# nothing here builds, pushes or rebuilds.
#
# Two modes:
#   --mode pr         on the release pull request (before merge): the head must
#                     be `develop`; there is no merged PR yet.
#   --mode promotion  on master, before a production handoff: the candidate
#                     must be the head of a merged release PR from develop.
#
# Rollback floors: a candidate that predates a commit listed in
# deploy/rollback-floors.txt is a rollback across a behavioural break and is
# refused unless --acknowledge-rollback-hazard is given.
#
# Usage:
#   verify-production-candidate.sh --candidate <40-hex> --mode pr --head-ref <ref>
#   verify-production-candidate.sh --candidate <40-hex> --mode promotion
#       [--acknowledge-rollback-hazard] [--manifest <file>]
# Env: GITHUB_REPOSITORY (owner/repo), EXPECTED_IMAGE_REPOSITORY (ghcr.io/owner),
#      GH_BIN, DOCKER_BIN (overridable, for the self-test), ROLLBACK_FLOORS.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GH="${GH_BIN:-gh}"
DOCKER="${DOCKER_BIN:-docker}"
REPO="${GITHUB_REPOSITORY:-yannisyoussef/testinbox}"
OWNER="${REPO%%/*}"
REGISTRY="${EXPECTED_IMAGE_REPOSITORY:-ghcr.io/$OWNER}"
FLOORS="${ROLLBACK_FLOORS:-$SCRIPT_DIR/../deploy/rollback-floors.txt}"
SIGNER_WORKFLOW="${SIGNER_WORKFLOW:-$REPO/.github/workflows/build-images.yml}"
SOURCE_REF="${SOURCE_REF:-refs/heads/develop}"
IMAGES=(api ingestion migrator web)

candidate="" mode="" head_ref="" manifest="" acknowledge=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --candidate) candidate="${2:-}"; shift 2 ;;
    --mode) mode="${2:-}"; shift 2 ;;
    --head-ref) head_ref="${2:-}"; shift 2 ;;
    --manifest) manifest="${2:-}"; shift 2 ;;
    --acknowledge-rollback-hazard) acknowledge=true; shift ;;
    *) echo "usage: $(basename "$0") --candidate <sha> --mode pr|promotion [--head-ref <ref>] [--manifest <file>] [--acknowledge-rollback-hazard]" >&2; exit 2 ;;
  esac
done

refuse() { echo "CANDIDATE REFUSED: $*" >&2; exit 1; }
note() { echo "ok — $*"; }

# --- 1. the candidate is a commit, named exactly ----------------------------
[[ "$candidate" =~ ^[0-9a-f]{40}$ ]] || refuse "candidate '$candidate' is not a 40-character lowercase hex SHA"
case "$mode" in
  pr|promotion) ;;
  *) refuse "mode must be pr or promotion (got '$mode')" ;;
esac

# --- 2. the candidate is approved for what this mode claims -----------------
if [[ "$mode" == "pr" ]]; then
  # A release pull request promotes the develop tip and nothing else. A PR
  # from a feature branch carries images (CI builds them) and would otherwise
  # look identical here.
  [[ "$head_ref" == "develop" ]] || refuse "release pull requests promote 'develop'; this one is from '${head_ref:-<unset>}'"
  note "release pull request head is develop"
else
  # Merged, into master, from develop, with THIS commit as its head. Read from
  # the merged-PR list rather than from git ancestry: a squash or rebase merge
  # leaves the head SHA out of master's history, and this must not depend on
  # which merge method a human picked.
  merged="$("$GH" pr list --repo "$REPO" --base master --state merged --limit 200 \
    --json number,headRefOid,headRefName 2>/dev/null)" || refuse "could not list merged pull requests for $REPO"
  match="$(printf '%s' "$merged" | jq -r --arg sha "$candidate" \
    '[.[] | select(.headRefOid == $sha)] | first // empty | "\(.number) \(.headRefName)"')"
  [[ -n "$match" ]] || refuse "no merged pull request into master has $candidate as its head — it is not production-approved"
  pr_number="${match%% *}"; pr_head_ref="${match#* }"
  [[ "$pr_head_ref" == "develop" ]] || refuse "pull request #$pr_number was merged from '$pr_head_ref', not develop"
  note "approved by merged release pull request #$pr_number (head develop)"
fi

# --- 3. all four artifacts exist, pinned by digest --------------------------
declare -a refs=()
for image in "${IMAGES[@]}"; do
  tag="${REGISTRY}/testinbox-${image}:${candidate}"
  digest="$("$DOCKER" buildx imagetools inspect "$tag" --format '{{.Manifest.Digest}}' 2>/dev/null)" \
    || refuse "no published image for testinbox-${image} at ${candidate}; production promotes what develop published, and this commit has no artifact"
  [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || refuse "the registry resolved testinbox-${image}:${candidate} to '$digest', which is not a digest"
  refs+=("${REGISTRY}/testinbox-${image}@${digest}")
done
EXPECTED_IMAGE_REPOSITORY="$REGISTRY" "$SCRIPT_DIR/validate-image-digest.sh" "${refs[@]}" >/dev/null \
  || refuse "a resolved reference failed digest/ownership validation"
note "four artifacts resolved by digest under $REGISTRY"

# --- 4. each digest attests to THIS source commit ---------------------------
# The only link a repointed tag cannot fake. Enforced: source repository digest
# (the commit), the signing workflow (our build-images.yml, not a fork's), the
# source ref (develop — the branch whose merges publish), and a GitHub-hosted
# runner (a self-hosted runner could sign anything).
for ref in "${refs[@]}"; do
  "$GH" attestation verify "oci://${ref}" \
      --owner "$OWNER" \
      --source-digest "$candidate" \
      --signer-workflow "$SIGNER_WORKFLOW" \
      --source-ref "$SOURCE_REF" \
      --deny-self-hosted-runners >/dev/null 2>&1 \
    || refuse "$ref does not carry a provenance attestation for source commit $candidate from $SIGNER_WORKFLOW — the tag may have been repointed or the artifact rebuilt"
done
note "every digest attests to source commit $candidate via $SIGNER_WORKFLOW"

# --- 5. rollback floors -----------------------------------------------------
if [[ -f "$FLOORS" ]]; then
  while IFS= read -r line; do
    line="${line%%#*}"; [[ -n "${line// /}" ]] || continue
    floor="${line%% *}"; why="${line#* }"
    [[ "$floor" =~ ^[0-9a-f]{40}$ ]] || refuse "rollback floor '$floor' in $FLOORS is not a commit SHA"
    git cat-file -e "${floor}^{commit}" 2>/dev/null \
      || refuse "rollback floor $floor is not in this checkout; fetch full history (fetch-depth: 0) so floors can be evaluated"
    git cat-file -e "${candidate}^{commit}" 2>/dev/null \
      || refuse "candidate $candidate is not in this checkout; fetch full history so rollback floors can be evaluated"
    if ! git merge-base --is-ancestor "$floor" "$candidate"; then
      if [[ "$acknowledge" == true ]]; then
        echo "WARNING: candidate predates rollback floor $floor ($why); proceeding on explicit acknowledgement" >&2
      else
        refuse "candidate $candidate predates rollback floor $floor: $why. Re-run with --acknowledge-rollback-hazard only if that outage is understood and announced"
      fi
    fi
  done < "$FLOORS"
  note "rollback floors evaluated"
fi

# --- 6. record the identity --------------------------------------------------
json="$(jq -n --arg sha "$candidate" --arg mode "$mode" \
  --arg api "${refs[0]}" --arg ingestion "${refs[1]}" --arg migrator "${refs[2]}" --arg web "${refs[3]}" \
  '{candidate: $sha, mode: $mode, images: {api: $api, ingestion: $ingestion, migrator: $migrator, web: $web}}')"
[[ -n "$manifest" ]] && printf '%s\n' "$json" > "$manifest"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "candidate=$candidate"
    echo "api=${refs[0]}"
    echo "ingestion=${refs[1]}"
    echo "migrator=${refs[2]}"
    echo "web=${refs[3]}"
  } >> "$GITHUB_OUTPUT"
fi
echo "PRODUCTION CANDIDATE VERIFIED ($mode)"
printf '%s\n' "$json"
