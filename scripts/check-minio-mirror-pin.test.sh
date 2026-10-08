#!/usr/bin/env bash
# Proves check-minio-mirror-pin.sh discriminates. The case it exists for is a
# PARTIAL re-pin — staging moved to a new mirror digest, the Testcontainers
# suites left behind — because that drift keeps CI green while the rehearsal and
# the test suites silently prove two different MinIO versions.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/check-minio-mirror-pin.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

MIRROR="ghcr.io/yannisyoussef/testinbox-mirror/minio"
A="sha256:bbac678936882e4033b6068efd0a19d103b219002d979087f7c5bd330172e00d"
B="sha256:0a7215f643cdaa475b6c4d674e8be6a06f71459158a677c98ff1a9f89d71853d"

pass=0
fail=0

# fixture <dir> <compose-ref> <staging-ref> <kt-ref-1..4 (one value, reused)> <doc-ref> [kt-count] [qual-digest] [index-mode]
#   qual-digest: the minio.imageIndexDigest of the one ADR-035 §9a qualification record (default: A)
#   index-mode:  listed (default) | unlisted (record present, index.txt omits it) | none (no qualification dir)
QUAL_DIR="backend/storage/src/main/resources/adr035-qualification"
fixture() {
    local root="$1" compose="$2" staging="$3" kt="$4" doc="$5" kt_count="${6:-4}" qual="${7:-$A}" index_mode="${8:-listed}"
    rm -rf "$root"
    mkdir -p "$root/deploy/staging" "$root/docs/dev" \
        "$root/backend/storage/src/test/kotlin" "$root/backend/ingestion/src/test/kotlin" \
        "$root/backend/api/src/test/kotlin" "$root/backend/e2e/src/test/kotlin"

    if [ "$index_mode" != none ]; then
        mkdir -p "$root/$QUAL_DIR"
        printf '{\n  "recordId": "fixture-laptop-arm64",\n  "minio": { "imageIndexDigest": "%s", "mode": "single-node-single-drive" },\n  "enablementEligible": false,\n  "ineligibilityReasons": ["fixture"]\n}\n' "$qual" \
            > "$root/$QUAL_DIR/laptop-arm64.json"
        if [ "$index_mode" = listed ]; then
            printf '# fixture index\nlaptop-arm64.json\n' > "$root/$QUAL_DIR/index.txt"
        else
            printf '# fixture index: the record exists but is not listed, so it does not ship\n' > "$root/$QUAL_DIR/index.txt"
        fi
    fi

    printf 'services:\n  minio:\n    image: %s\n' "$compose" > "$root/docker-compose.yml"
    printf 'services:\n  minio:\n    image: %s\n' "$staging" > "$root/deploy/staging/compose.data.yaml"

    local i=0
    for module in storage ingestion api e2e; do
        i=$((i + 1))
        if [ "$i" -le "$kt_count" ]; then
            cat > "$root/backend/$module/src/test/kotlin/Stack.kt" <<KT
val minio =
    MinIOContainer(
        DockerImageName
            .parse("$kt")
            .asCompatibleSubstituteFor("minio/minio"),
    )
KT
        else
            # A consumer that lost its pin entirely (e.g. someone "simplified" it
            # back to the Testcontainers default).
            cat > "$root/backend/$module/src/test/kotlin/Stack.kt" <<KT
val minio = MinIOContainer()
KT
        fi
    done

    printf '# Third-party image mirrors\n\nPinned: `%s`\n' "$doc" > "$root/docs/dev/third-party-mirrors.md"
}

check() {
    local name="$1" expected="$2" root="$3"
    local output status
    output="$(SCAN_ROOT="$root" "$GATE" 2>&1)"
    status=$?
    if [ "$status" = "$expected" ]; then
        echo "ok   — $name"
        pass=$((pass + 1))
    else
        echo "FAIL — $name (expected exit $expected, got $status)"
        printf '%s\n' "$output" | sed 's/^/       /'
        fail=$((fail + 1))
    fi
}

# The state this change establishes: six references, one digest, recorded.
fixture "$TMP/good" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A"
check "all six agree and the doc records it" 0 "$TMP/good"

# THE case: staging re-pinned, the suites left behind.
fixture "$TMP/partial" "$MIRROR@$A" "$MIRROR@$B" "$MIRROR@$A" "$MIRROR@$A"
check "a partial re-pin fails" 1 "$TMP/partial"

# The mixture the repair was required not to leave.
fixture "$TMP/quay" "$MIRROR@$A" "$MIRROR@$A" \
    "quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z" "$MIRROR@$A"
check "a withdrawn quay.io reference fails" 1 "$TMP/quay"

fixture "$TMP/hub" "docker.io/minio/minio:latest" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A"
check "a withdrawn Docker Hub reference fails" 1 "$TMP/hub"

# Mutable tags defeat the entire point of pinning bytes.
fixture "$TMP/tag" "$MIRROR:RELEASE.2025-04-22T22-12-26Z" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A"
check "referencing the mirror by tag fails" 1 "$TMP/tag"

# Code and provenance drifting apart: the document is what a future reader trusts.
fixture "$TMP/doc" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$B"
check "a provenance doc recording another digest fails" 1 "$TMP/doc"

# A consumer that lost its pin: fewer references than the floor.
fixture "$TMP/missing" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A" 3
check "a consumer that lost its pin fails the floor" 1 "$TMP/missing"

# Someone else's mirror is not this mirror.
fixture "$TMP/foreign" "ghcr.io/someone/their-mirror/minio@$A" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A"
check "an unrecognised mirror fails" 1 "$TMP/foreign"

fixture "$TMP/short" "$MIRROR@sha256:abc123" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A"
check "a truncated digest fails" 1 "$TMP/short"

# False-positive guards. `asCompatibleSubstituteFor("minio/minio")` is required
# by Testcontainers and names the substituted image, not a registry to pull from;
# flagging it would make the gate unusable. Prose must stay free to name the
# withdrawn references, since that is what it documents.
fixture "$TMP/prose" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A"
cat >> "$TMP/prose/docs/dev/third-party-mirrors.md" <<'DOC'
quay.io/minio/minio answers 401 for every tag, and docker.io/minio/minio is gone.
DOC
mkdir -p "$TMP/prose/.github/workflows"
printf 'jobs:\n  x:\n    steps:\n      - run: echo quay.io/minio/minio\n' > "$TMP/prose/.github/workflows/ci.yml"
check "prose and workflows may name the withdrawn images" 0 "$TMP/prose"

# --- ADR-035 §9a: the pinned digest must be the digest of a SHIPPED qualification record ---
# A re-mirror is a new MinIO combination. Six consumers agreeing on the new
# digest, the provenance doc updated, and no qualification record: that is
# exactly the state this extension exists to refuse.
check_finding() {
    local name="$1" root="$2" text="$3"
    local output
    output="$(SCAN_ROOT="$root" "$GATE" 2>&1)"
    if printf '%s\n' "$output" | grep -qF -- "$text"; then
        echo "ok   — $name"
        pass=$((pass + 1))
    else
        echo "FAIL — $name (output lacks '$text')"
        printf '%s\n' "$output" | sed 's/^/       /'
        fail=$((fail + 1))
    fi
}

fixture "$TMP/repin" "$MIRROR@$B" "$MIRROR@$B" "$MIRROR@$B" "$MIRROR@$B" 4 "$A"
check "a consistent re-pin with no qualification record for the new digest fails" 1 "$TMP/repin"
check_finding "the finding names the §9a rule" "$TMP/repin" "has no qualification record — a re-mirror cannot silently preserve qualification (ADR-035 §9a)"

fixture "$TMP/other-record" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A" 4 "$B"
check "a qualification record for ANOTHER digest does not qualify this pin" 1 "$TMP/other-record"

fixture "$TMP/unlisted" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A" 4 "$A" unlisted
check "a record omitted from index.txt does not ship and does not count" 1 "$TMP/unlisted"
check_finding "the unlisted record is named" "$TMP/unlisted" "laptop-arm64.json is not listed in index.txt"

fixture "$TMP/noqual" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A" 4 "$A" none
check "no qualification directory at all fails (nothing is qualified)" 1 "$TMP/noqual"

fixture "$TMP/dangling" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A" "$MIRROR@$A"
printf 'missing-record.json\n' >> "$TMP/dangling/$QUAL_DIR/index.txt"
check "an index entry whose record file is missing fails" 1 "$TMP/dangling"

check_finding "a clean tree reports which record qualifies the pin" "$TMP/good" "qualified by $QUAL_DIR/laptop-arm64.json"

echo "----"
echo "check-minio-mirror-pin.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
