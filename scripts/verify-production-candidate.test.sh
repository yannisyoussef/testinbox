#!/usr/bin/env bash
# Negative evidence for the production-candidate verifier (ADR-034 §3, §7). Every refusal below is a way production could otherwise run bytes
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
# gh: `pr list` prints the fixture — but only when asked for MERGED pull
# requests into MASTER, because an open PR or a PR into develop must never
# count as approval. `attestation verify` succeeds only when every binding the
# real command is documented to enforce is present and matches the fixture:
# source digest, signer workflow, source ref, and the self-hosted denial. A
# stub that read only the digest would let the other three flags vanish.
cat >"$WORK/gh" <<'STUB'
#!/usr/bin/env bash
echo "gh $*" >> "$STUB_LOG"
case "$1 $2" in
  "pr list")
    [[ " $* " == *" --base master "* && " $* " == *" --state merged "* ]] || { echo "pr list without --base master --state merged" >&2; exit 99; }
    cat "$GH_PR_LIST"; exit 0 ;;
  "attestation verify")
    subject="$3"
    for unattested in ${GH_UNATTESTED:-}; do [[ "$subject" == *"testinbox-$unattested@"* ]] && { echo "Error: no attestations found" >&2; exit 1; }; done
    want="$(cat "$GH_ATTESTED_SHA")"
    got="" signer="" ref="" deny=0
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --source-digest) got="$2"; shift 2 ;;
        --signer-workflow) signer="$2"; shift 2 ;;
        --source-ref) ref="$2"; shift 2 ;;
        --deny-self-hosted-runners) deny=1; shift ;;
        *) shift ;;
      esac
    done
    [[ "$got" == "$want" ]] || { echo "Error: expected SourceRepositoryDigest to be $got, got $want" >&2; exit 1; }
    [[ "$signer" == "$GH_SIGNER" ]] || { echo "Error: signer workflow '$signer' does not match" >&2; exit 1; }
    [[ "$ref" == "$GH_SOURCE_REF" ]] || { echo "Error: source ref '$ref' does not match" >&2; exit 1; }
    (( deny == 1 )) || { echo "Error: self-hosted runners were not denied" >&2; exit 1; }
    exit 0 ;;
  *) echo "unexpected gh $*" >&2; exit 99 ;;
esac
STUB
# docker: resolves any testinbox-* tag to a digest unless the image name is in
# DOCKER_MISSING, or DOCKER_MALFORMED asks it to return a tag-shaped value.
# Every invocation is logged so the suite can prove nothing but an inspect ran.
cat >"$WORK/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "$STUB_LOG"
[[ "$1 $2 $3" == "buildx imagetools inspect" ]] || { echo "unexpected docker $*" >&2; exit 98; }
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
  : > "$WORK/stub.log"
  (
    cd "$WORK/repo" &&
    GH_BIN="$WORK/gh" DOCKER_BIN="$WORK/docker" STUB_LOG="$WORK/stub.log" \
    GH_PR_LIST="$WORK/prs.json" GH_ATTESTED_SHA="$WORK/attested.txt" \
    GH_SIGNER="${GH_SIGNER:-testowner/testinbox/.github/workflows/build-images.yml}" GH_SOURCE_REF="${GH_SOURCE_REF:-refs/heads/develop}" \
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
[[ ! -s "$WORK/stub.log" ]] \
  && { echo "ok   — and neither gh nor docker was invoked for it"; pass=$((pass + 1)); } \
  || { echo "FAIL — a malformed candidate reached a stub: $(cat "$WORK/stub.log")"; fail=$((fail + 1)); }
check "an unknown mode is refused" 1 --candidate "$CANDIDATE" --mode canary
DOCKER_MISSING="web" check "one of the four artifacts missing refuses the set" 1 \
  --candidate "$CANDIDATE" --mode promotion
DOCKER_MALFORMED=1 check "a registry answering with a tag instead of a digest is refused" 1 \
  --candidate "$CANDIDATE" --mode promotion
echo "$BASE" > "$WORK/attested.txt"
check "artifacts attested to a DIFFERENT commit are refused (repointed tag / rebuild)" 1 \
  --candidate "$CANDIDATE" --mode promotion
echo "$CANDIDATE" > "$WORK/attested.txt"
GH_UNATTESTED="web" check "one of four digests without an attestation refuses the whole set" 1 \
  --candidate "$CANDIDATE" --mode promotion
# The bindings the verifier must pass: the stub refuses when any is missing or
# different, so a verifier that dropped one would fail the positive control —
# and these prove the stub itself discriminates on each.
GH_SIGNER="testowner/fork/.github/workflows/build-images.yml" check "an attestation signed by another repository's workflow is refused" 1 \
  --candidate "$CANDIDATE" --mode promotion
GH_SOURCE_REF="refs/heads/feature" check "an attestation from a ref other than develop is refused" 1 \
  --candidate "$CANDIDATE" --mode promotion
printf '[{"number":79,"headRefOid":"%s","headRefName":"develop","isCrossRepository":true}]' "$CANDIDATE" > "$WORK/prs.json"
check "a fork's develop is not this repository's develop" 1 --candidate "$CANDIDATE" --mode promotion
merged_from_develop "$CANDIDATE"

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
rm -f "$WORK/floors.txt"
check "a missing floors file refuses rather than meaning 'no floors'" 1 \
  --candidate "$BASE" --mode promotion --acknowledge-rollback-hazard

# --- it never rebuilds -------------------------------------------------------
echo "$FLOOR credential lifecycle break (fixture)" > "$WORK/floors.txt"
echo "$CANDIDATE" > "$WORK/attested.txt"; merged_from_develop "$CANDIDATE"
run --candidate "$CANDIDATE" --mode promotion --acknowledge-rollback-hazard --manifest "$WORK/manifest2.json"
if ! grep -qvE '^(docker buildx imagetools inspect |gh (pr list|attestation verify) )' "$WORK/stub.log"; then
  echo "ok   — verification invoked only imagetools inspect, pr list and attestation verify (no build, no push)"; pass=$((pass + 1))
else
  echo "FAIL — verification invoked something else:"; grep -vE '^(docker buildx imagetools inspect |gh (pr list|attestation verify) )' "$WORK/stub.log"; fail=$((fail + 1))
fi
grep -q '"acknowledgedRollbackHazard": true' "$WORK/manifest2.json" \
  && { echo "ok   — the manifest records that the rollback hazard was acknowledged"; pass=$((pass + 1)); } \
  || { echo "FAIL — manifest does not record the acknowledgement"; fail=$((fail + 1)); }

echo "----"
echo "verify-production-candidate.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
