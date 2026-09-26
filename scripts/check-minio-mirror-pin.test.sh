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

# fixture <dir> <compose-ref> <staging-ref> <kt-ref-1..4 (one value, reused)> <doc-ref> [kt-count]
fixture() {
    local root="$1" compose="$2" staging="$3" kt="$4" doc="$5" kt_count="${6:-4}"
    rm -rf "$root"
    mkdir -p "$root/deploy/staging" "$root/docs/dev" \
        "$root/backend/storage/src/test/kotlin" "$root/backend/ingestion/src/test/kotlin" \
        "$root/backend/api/src/test/kotlin" "$root/backend/e2e/src/test/kotlin"

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

echo "----"
echo "check-minio-mirror-pin.test.sh: $pass passed, $fail failed"
(( fail == 0 ))
