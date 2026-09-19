#!/usr/bin/env bash
# Negative evidence for the production-candidate verifier (ADR-034, §29 of the
# increment). Every refusal below is a way production could otherwise run bytes
# that staging never proved. `gh` and `docker` are stubbed; git is real, on a
# throwaway repository, because the rollback-floor logic is ancestry.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY="$SCRIPT_DIR/verify-production-candidate.sh"
pass=0; fail=0
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

DIGEST="sha256:$(printf 'a%.0s' {1..64})"

# --- a git history: base -> floor -> candidate ------------------------------
git init -q "$WORK/repo"
git -C "$WORK/repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m base
BASE=$(git -C "$WORK/repo" rev-parse HEAD)
git -C "$WORK/repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m floor
FLOOR=$(git -C "$WORK/repo" rev-parse HEAD)
git -C "$WORK/repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m candidate
CANDIDATE=$(git -C "$WORK/repo" rev-parse HEAD)
echo "$FLOOR credential lifecycle break (fixture)" > "$WORK/floors.txt"

# --- stubs ------------------------------------------------------------------
# gh: `pr list` prints the fixture; `attestation verify` succeeds only when the
# --source-digest equals the SHA the fixture says the images were built from.
cat >"$WORK/gh" <<'STUB'
#!/usr/bin/env bash
case "$1 $2" in
  "pr list") cat "$GH_PR_LIST"; exit 0 ;;
  "attestation verify")
    want="$(cat "$GH_ATTESTED_SHA")"
    got=""
    while [[ $# -gt 0 ]]; do case "$1" in --source-digest) got="$2"; shift 2 ;; *) shift ;; esac; done
    [[ "$got" == "$want" ]] && exit 0
    echo "Error: expected SourceRepositoryDigest to be $got, got $want" >&2; exit 1 ;;
  *) echo "unexpected gh $*" >&2; exit 99 ;;
esac
STUB
# docker: resolves any testinbox-* tag to a digest unless the image name is in
# DOCKER_MISSING, or DOCKER_MALFORMED asks it to return a tag-shaped value.
cat >"$WORK/docker" <<'STUB'
#!/usr/bin/env bash
ref="$4"
for missing in ${DOCKER_MISSING:-}; do [[ "$ref" == *"testinbox-$missing:"* ]] && exit 1; done
if [[ -n "${DOCKER_MALFORMED:-}" ]]; then echo "latest"; exit 0; fi
echo "$DOCKER_DIGEST"
STUB
chmod +x "$WORK/gh" "$WORK/docker"

merged_from_develop() { printf '[{"number":77,"headRefOid":"%s","headRefName":"develop"}]' "$1" > "$WORK/prs.json"; }
merged_from_feature() { printf '[{"number":78,"headRefOid":"%s","headRefName":"feat/sneaky"}]' "$1" > "$WORK/prs.json"; }
none_merged()         { printf '[]' > "$WORK/prs.json"; }

run() {
  (
    cd "$WORK/repo" &&
    GH_BIN="$WORK/gh" DOCKER_BIN="$WORK/docker" \
    GH_PR_LIST="$WORK/prs.json" GH_ATTESTED_SHA="$WORK/attested.txt" \
    DOCKER_DIGEST="${DOCKER_DIGEST:-$DIGEST}" \
    GITHUB_REPOSITORY="testowner/testinbox" EXPECTED_IMAGE_REPOSITORY="ghcr.io/testowner" \
    ROLLBACK_FLOORS="$WORK/floors.txt" GITHUB_OUTPUT="$WORK/gh_output" \
    "$VERIFY" "$@" >"$WORK/stdout" 2>"$WORK/stderr"
  )
}

check() {
  local name="$1" expected="$2"; shift 2
  rm -f "$WORK/gh_output"
  run "$@"
  local status=$?
  if [[ "$status" == "$expected" ]]; then
    echo "ok   — $name"; pass=$((pass + 1))
  else
    echo "FAIL — $name (expected exit $expected, got $status)"
    sed 's/^/       /' "$WORK/stderr" | tail -3
    fail=$((fail + 1))
  fi
}

# --- positive control: a real candidate --------------------------------------
echo "$CANDIDATE" > "$WORK/attested.txt"; merged_from_develop "$CANDIDATE"
check "a merged develop candidate with attested artifacts is verified (promotion)" 0 \
  --candidate "$CANDIDATE" --mode promotion --manifest "$WORK/manifest.json"
if grep -q "\"candidate\": \"$CANDIDATE\"" "$WORK/manifest.json" \
   && grep -q "ghcr.io/testowner/testinbox-web@$DIGEST" "$WORK/manifest.json" \
   && grep -q "^api=ghcr.io/testowner/testinbox-api@$DIGEST$" "$WORK/gh_output"; then
  echo "ok   — the manifest and outputs carry the exact digests and candidate"; pass=$((pass + 1))
else
  echo "FAIL — manifest/outputs incomplete"; cat "$WORK/manifest.json" "$WORK/gh_output"; fail=$((fail + 1))
fi
check "on the release pull request the develop head is verified (pr)" 0 \
  --candidate "$CANDIDATE" --mode pr --head-ref develop

# --- approval evidence -------------------------------------------------------
none_merged
check "a commit with no merged pull request into master is not production-approved" 1 \
  --candidate "$CANDIDATE" --mode promotion
merged_from_feature "$CANDIDATE"
check "a commit merged from a feature branch is refused even with artifacts" 1 \
  --candidate "$CANDIDATE" --mode promotion
merged_from_develop "$CANDIDATE"
check "a release pull request from a feature branch is refused" 1 \
  --candidate "$CANDIDATE" --mode pr --head-ref feat/sneaky
check "a release pull request with no head ref is refused" 1 \
  --candidate "$CANDIDATE" --mode pr

# --- artifact identity -------------------------------------------------------
check "a short or non-hex candidate is refused before anything is queried" 1 \
  --candidate "abc123" --mode promotion
DOCKER_MISSING="web" check "one of the four artifacts missing refuses the set" 1 \
  --candidate "$CANDIDATE" --mode promotion
DOCKER_MALFORMED=1 check "a registry answering with a tag instead of a digest is refused" 1 \
  --candidate "$CANDIDATE" --mode promotion
echo "$BASE" > "$WORK/attested.txt"
check "artifacts attested to a DIFFERENT commit are refused (repointed tag / rebuild)" 1 \
  --candidate "$CANDIDATE" --mode promotion
echo "$CANDIDATE" > "$WORK/attested.txt"

# --- rollback floors ---------------------------------------------------------
echo "$BASE" > "$WORK/attested.txt"; merged_from_develop "$BASE"
check "a candidate that predates a rollback floor is refused" 1 \
  --candidate "$BASE" --mode promotion
check "the same candidate passes only with the hazard acknowledged" 0 \
  --candidate "$BASE" --mode promotion --acknowledge-rollback-hazard
grep -q "WARNING: candidate predates rollback floor" "$WORK/stderr" \
  && { echo "ok   — the acknowledged hazard is still shouted"; pass=$((pass + 1)); } \
  || { echo "FAIL — acknowledged hazard was silent"; fail=$((fail + 1)); }
printf 'not-a-sha some reason\n' > "$WORK/floors.txt"
check "a malformed floor entry refuses rather than being skipped" 1 \
  --candidate "$BASE" --mode promotion --acknowledge-rollback-hazard
printf '%s unknown floor\n' "$(printf 'f%.0s' {1..40})" > "$WORK/floors.txt"
check "a floor not present in the checkout refuses (shallow clone)" 1 \
  --candidate "$BASE" --mode promotion --acknowledge-rollback-hazard

# --- it never rebuilds -------------------------------------------------------
echo "$FLOOR credential lifecycle break (fixture)" > "$WORK/floors.txt"
echo "$CANDIDATE" > "$WORK/attested.txt"; merged_from_develop "$CANDIDATE"
run --candidate "$CANDIDATE" --mode promotion
if ! grep -qiE 'build|push' "$WORK/stdout" 2>/dev/null || ! grep -qE '(^| )(build|push) ' "$WORK/stdout"; then
  echo "ok   — verification issues no build or push"; pass=$((pass + 1))
else
  echo "FAIL — verification appears to build or push"; fail=$((fail + 1))
fi

echo "----"
echo "verify-production-candidate.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
