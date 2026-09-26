#!/usr/bin/env bash
# Copies the MinIO image this repository already depends on into GHCR, byte for
# byte, and composes a two-platform index from the copies.
#
# WHY THIS EXISTS: MinIO withdrew its public images. `quay.io/minio/minio` now
# answers 401 for every tag including `latest`, Docker Hub's `minio/minio` is
# gone entirely ("object not found"), and neither mirror.gcr.io nor ECR Public
# has it. Six references in this repository pin
# `quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z`, so the rehearsal, the
# Testcontainers suites and the local compose stack all fail at image pull.
#
# This is an availability repair, NOT an upgrade. Nothing is rebuilt: each
# platform manifest published here is the *same* manifest the upstream index
# pointed at, so manifest, config and every layer are byte-identical by content
# addressing. If a digest ever differs, something re-encoded the image and this
# script stops rather than publishing it.
#
# WHERE THE BYTES COME FROM (they no longer exist in public):
#   linux/amd64 — the Infinity Ops estate mirror, which copied the same manifest
#                 on 2026-09-25 from the containers it was already running.
#   linux/arm64 — this machine's content store, which still holds the upstream
#                 index blob and the complete arm64 member.
# The Ops credential is used ONCE, here, interactively, and is logged out again
# at the end. It is never given to CI (see docs/dev/third-party-mirrors.md).
#
# Usage: scripts/mirror-minio-to-ghcr.sh [--skip-login]
#
# Credentials, in order of preference — never passed as an argument, never
# echoed, never written to this repository:
#
#   * already logged in     — run with `--skip-login`;
#   * a token file          — set MIRROR_SOURCE_USERNAME + MIRROR_SOURCE_TOKEN_FILE
#                             and MIRROR_TARGET_USERNAME + MIRROR_TARGET_TOKEN_FILE;
#                             the token is read from the file straight into
#                             `docker login --password-stdin`;
#   * an interactive prompt — only when this runs on a real terminal.
#
# A token file must live outside this repository; the script refuses otherwise,
# because the one place a credential must never end up is a commit.
set -euo pipefail

# --- identities ---------------------------------------------------------------
# The upstream release this repository pins, and the three digests that define
# it. All of them are verified below against content in the local store before
# anything is published; none of them is trusted because it is written here.
#
# Every value is overridable so the procedure itself can be rehearsed against a
# throwaway registry and a still-public image before it is run for real. The
# defaults are the real identities.
RELEASE="${MIRROR_RELEASE:-RELEASE.2025-04-22T22-12-26Z}"
UPSTREAM_INDEX="${MIRROR_UPSTREAM_INDEX:-sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e}"
UPSTREAM_AMD64="${MIRROR_UPSTREAM_AMD64:-sha256:3f97c5651cb6662b880c787a232b6b34fec8d8922e08d6617b25d241a21164bb}"
UPSTREAM_ARM64="${MIRROR_UPSTREAM_ARM64:-sha256:54d3d6a0a58fb25b4e9943d1db3828d3b4de44666f911381b4fda57175488194}"

# The Ops estate mirror holds the amd64 member at its upstream digest, so the
# pull below is content-addressed: the registry cannot hand us anything else.
OPS_REGISTRY="${MIRROR_SOURCE_REGISTRY:-registry.yvnn.is}"
OPS_SOURCE="${MIRROR_SOURCE_REPO:-$OPS_REGISTRY/infinity/mirror/minio/minio}"

# The local reference that still resolves to the upstream index in this machine's
# content store. Pulling it again would fail — that is the whole problem — so it
# is read, never fetched.
LOCAL_SOURCE="${MIRROR_LOCAL_SOURCE:-quay.io/minio/minio:$RELEASE}"

# Deliberately a third-party mirror namespace: these are MinIO's bytes, not a
# TestInbox build, and the name must not suggest otherwise.
TARGET_REGISTRY="${MIRROR_TARGET_REGISTRY:-ghcr.io}"
TARGET="${MIRROR_TARGET_REPO:-$TARGET_REGISTRY/yannisyoussef/testinbox-mirror/minio}"
# Provenance tags exist for discoverability and to keep the manifests out of
# reach of untagged-manifest garbage collection. Consumers pin the digest.
UPSTREAM_INDEX_HEX="${UPSTREAM_INDEX#sha256:}"
INDEX_TAG_RELEASE="$TARGET:$RELEASE"
INDEX_TAG_UPSTREAM="$TARGET:upstream-${UPSTREAM_INDEX_HEX:0:12}"
AMD64_TAG="$TARGET:$RELEASE-amd64"
ARM64_TAG="$TARGET:$RELEASE-arm64"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SKIP_LOGIN=0
[ "${1:-}" = "--skip-login" ] && SKIP_LOGIN=1

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

step() { printf '\n\033[1m=== %s ===\033[0m\n' "$*"; }
note() { printf '  %s\n' "$*"; }
die() { printf '\n\033[31mSTOP: %s\033[0m\n' "$*" >&2; exit 1; }

# macOS ships `shasum`, Linux `sha256sum`; this runs on a developer machine.
if command -v shasum >/dev/null 2>&1; then
    sha256_of() { shasum -a 256 | cut -d' ' -f1; }
elif command -v sha256sum >/dev/null 2>&1; then
    sha256_of() { sha256sum | cut -d' ' -f1; }
else
    echo "STOP: neither shasum nor sha256sum is available" >&2
    exit 1
fi

# --- 0. preflight -------------------------------------------------------------
step "0/8 preflight"
command -v docker >/dev/null || die "docker is required"
command -v python3 >/dev/null || die "python3 is required (manifest verification)"
docker buildx version >/dev/null 2>&1 || die "docker buildx is required (index composition)"
docker push --help 2>&1 | grep -q -- '--platform' ||
    die "this docker cannot push a single platform (\`docker push --platform\`). Docker 27+ with the containerd image store is required."
docker image inspect "$LOCAL_SOURCE" >/dev/null 2>&1 ||
    die "$LOCAL_SOURCE is not in this machine's image store. The arm64 bytes exist nowhere else — upstream is withdrawn — so they cannot be recovered by pulling. Run this on the machine that still has them."
note "docker $(docker version --format '{{.Server.Version}}'), buildx present, local source present"

# --- 1. prove the local arm64 member really is the upstream one ---------------
# The chain that matters: the index blob hashes to the digest the registries
# served, that index names the arm64 member, and every blob the member points at
# hashes correctly. Without this the local cache would be an assertion, not
# evidence — and the prompt's stop condition applies.
verify_platform() {
    # verify_platform <oci-layout-dir> <expected-manifest-digest> <expect-index|"">
    python3 - "$1" "$2" "${3:-}" <<'PY'
import hashlib, json, os, sys

layout, want_manifest, want_index = sys.argv[1], sys.argv[2], sys.argv[3]

def path(d):
    algo, hexd = d.split(":")
    return os.path.join(layout, "blobs", algo, hexd)

def digest(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return "sha256:" + h.hexdigest()

def verified(label, d):
    p = path(d)
    if not os.path.exists(p):
        print(f"  MISSING {label}: {d}")
        return False
    got = digest(p)
    if got != d:
        print(f"  CORRUPT {label}: expected {d}, hashed {got}")
        return False
    print(f"  ok {label}: {d}")
    return True

ok = True
manifest_digest = want_manifest

# When the layout carries the upstream index, verify it and that it names this
# member — that is what ties local bytes to the original publication.
if want_index:
    ok &= verified("upstream index", want_index)
    if ok:
        idx = json.load(open(path(want_index)))
        members = [m["digest"] for m in idx.get("manifests", [])]
        if want_manifest not in members:
            print(f"  NOT IN INDEX: {want_manifest} is not a member of {want_index}")
            ok = False
        else:
            print(f"  ok index names member: {want_manifest}")

ok &= verified("platform manifest", manifest_digest)
if not ok:
    sys.exit(1)

man = json.load(open(path(manifest_digest)))
cfg_digest = man["config"]["digest"]
ok &= verified("config", cfg_digest)
for i, layer in enumerate(man["layers"]):
    ok &= verified(f"layer {i:02d}", layer["digest"])
if not ok:
    sys.exit(1)

cfg = json.load(open(path(cfg_digest)))
record = {
    "platform": f"{cfg.get('os')}/{cfg.get('architecture')}",
    "manifest": manifest_digest,
    "config": cfg_digest,
    "created": cfg.get("created"),
    "entrypoint": cfg.get("config", {}).get("Entrypoint"),
    "cmd": cfg.get("config", {}).get("Cmd"),
    "layers": [l["digest"] for l in man["layers"]],
}
print("PROVENANCE " + json.dumps(record))
PY
}

save_layout() {
    # save_layout <ref> <platform> <dir>
    local ref="$1" platform="$2" dir="$3"
    mkdir -p "$dir"
    docker save --platform "$platform" -o "$dir.tar" "$ref" ||
        die "could not export $platform from $ref (its blobs are not in this store)"
    tar -xf "$dir.tar" -C "$dir"
}

step "1/8 verify the cached linux/arm64 member against the upstream index"
# Exported WITHOUT --platform so the upstream index blob comes with it.
docker save -o "$WORK/arm64.tar" "$LOCAL_SOURCE" || die "could not export $LOCAL_SOURCE"
mkdir -p "$WORK/arm64" && tar -xf "$WORK/arm64.tar" -C "$WORK/arm64"
ARM64_RECORD="$(verify_platform "$WORK/arm64" "$UPSTREAM_ARM64" "$UPSTREAM_INDEX" | tee /dev/stderr | grep '^PROVENANCE ' | cut -d' ' -f2-)" ||
    die "the cached arm64 member is NOT cryptographically tied to upstream index $UPSTREAM_INDEX — do not treat it as authoritative"
[ -n "$ARM64_RECORD" ] || die "arm64 verification produced no provenance record"
note "arm64 chain complete: index -> member -> config -> every layer"

# The same index proves what the amd64 member's digest must be, independently of
# any documentation from Ops.
python3 - "$WORK/arm64" "$UPSTREAM_INDEX" "$UPSTREAM_AMD64" <<'PY' || die "the upstream index does not name the expected amd64 member"
import json, os, sys
layout, index, want = sys.argv[1], sys.argv[2], sys.argv[3]
algo, hexd = index.split(":")
idx = json.load(open(os.path.join(layout, "blobs", algo, hexd)))
members = {m["digest"]: m.get("platform", {}) for m in idx["manifests"]}
p = members.get(want)
if not p or (p.get("os"), p.get("architecture")) != ("linux", "amd64"):
    print(f"  {want} is not the linux/amd64 member of {index}")
    sys.exit(1)
print(f"  ok index names linux/amd64 member: {want}")
PY

# --- 2. authenticate (nothing lands in argv, in the output, or in this repo) ---
# A token is either already in docker's credential store, or read from a file
# directly into `--password-stdin`, or typed at a prompt. It is never an
# argument: argv is visible to every process on the machine.
login_to() {
    local registry="$1" username="$2" token_file="$3" hint="$4"
    if [ -n "$token_file" ]; then
        [ -f "$token_file" ] || die "token file for $registry not found: $token_file"
        case "$(cd "$(dirname "$token_file")" && pwd)" in
            "$REPO_ROOT"|"$REPO_ROOT"/*)
                die "the token file for $registry is inside this repository ($token_file). Move it out — a credential must never be committable." ;;
        esac
        [ -n "$username" ] || die "a username is required alongside the token file for $registry"
        docker login "$registry" --username "$username" --password-stdin < "$token_file" >/dev/null ||
            die "login to $registry failed for user $username"
        note "$registry: logged in as $username (token read from a file, never from argv)"
    elif [ -t 0 ]; then
        note "$registry — $hint"
        docker login "$registry"
    else
        die "no terminal and no token file for $registry.
    Choose one:
      * log in yourself, then re-run with --skip-login; or
      * export ${5}_USERNAME and ${5}_TOKEN_FILE (a file outside this repository) and re-run."
    fi
}

step "2/8 authenticate"
if [ "$SKIP_LOGIN" -eq 1 ]; then
    note "--skip-login: using the credentials already in docker's store"
else
    login_to "$OPS_REGISTRY" "${MIRROR_SOURCE_USERNAME:-}" "${MIRROR_SOURCE_TOKEN_FILE:-}" \
        "the Ops estate mirror (used once, logged out in step 8)" "MIRROR_SOURCE"
    login_to "$TARGET_REGISTRY" "${MIRROR_TARGET_USERNAME:-}" "${MIRROR_TARGET_TOKEN_FILE:-}" \
        "username is your GitHub login; the password is a token with write:packages" "MIRROR_TARGET"
fi

# --- 3. copy the amd64 member from the Ops mirror ----------------------------
step "3/8 pull the linux/amd64 member from the Ops mirror, by digest"
# By digest, so the registry cannot substitute content: what arrives either
# hashes to the upstream amd64 member or the pull fails.
docker pull --platform linux/amd64 "$OPS_SOURCE@$UPSTREAM_AMD64" ||
    die "could not pull $OPS_SOURCE@$UPSTREAM_AMD64 (is this machine logged in to $OPS_REGISTRY?)"

step "4/8 verify the pulled amd64 bytes BEFORE publishing them"
save_layout "$OPS_SOURCE@$UPSTREAM_AMD64" linux/amd64 "$WORK/amd64"
AMD64_RECORD="$(verify_platform "$WORK/amd64" "$UPSTREAM_AMD64" "" | tee /dev/stderr | grep '^PROVENANCE ' | cut -d' ' -f2-)" ||
    die "the bytes from the Ops mirror do not verify against $UPSTREAM_AMD64"
[ -n "$AMD64_RECORD" ] || die "amd64 verification produced no provenance record"
note "amd64 manifest, config and every layer hash to the upstream member"

# --- 5. publish the two platform manifests -----------------------------------
step "5/8 publish both platform manifests to $TARGET"
docker tag "$OPS_SOURCE@$UPSTREAM_AMD64" "$AMD64_TAG"
docker push --platform linux/amd64 "$AMD64_TAG" >/dev/null
docker tag "$LOCAL_SOURCE" "$ARM64_TAG"
docker push --platform linux/arm64 "$ARM64_TAG" >/dev/null

pushed_digest() {
    # The digest of what the registry now serves, computed from the bytes it
    # returns — not read from the push output.
    docker buildx imagetools inspect --raw "$1" | sha256_of
}
GOT_AMD64="sha256:$(pushed_digest "$AMD64_TAG")"
GOT_ARM64="sha256:$(pushed_digest "$ARM64_TAG")"
note "amd64 published as $GOT_AMD64"
note "arm64 published as $GOT_ARM64"
[ "$GOT_AMD64" = "$UPSTREAM_AMD64" ] ||
    die "amd64 digest changed on publication ($GOT_AMD64 != $UPSTREAM_AMD64) — something re-encoded the image"
[ "$GOT_ARM64" = "$UPSTREAM_ARM64" ] ||
    die "arm64 digest changed on publication ($GOT_ARM64 != $UPSTREAM_ARM64) — something re-encoded the image"
note "both platform manifests are byte-identical to the originals"

# --- 6. compose the two-platform index ---------------------------------------
step "6/8 compose a two-platform index from exactly those manifests"
# A new index is expected to have a new digest: it names two members where the
# original named three. The members themselves are unchanged, which is the
# property that matters and is asserted in step 7.
docker buildx imagetools create \
    --tag "$INDEX_TAG_RELEASE" \
    --tag "$INDEX_TAG_UPSTREAM" \
    "$TARGET@$UPSTREAM_AMD64" \
    "$TARGET@$UPSTREAM_ARM64"

step "7/8 verify the published index"
docker buildx imagetools inspect --raw "$INDEX_TAG_RELEASE" > "$WORK/ghcr-index.json"
GHCR_INDEX="sha256:$(sha256_of < "$WORK/ghcr-index.json")"
python3 - "$WORK/ghcr-index.json" "$UPSTREAM_AMD64" "$UPSTREAM_ARM64" <<'PY' || die "the published index does not reference exactly the two verified manifests"
import json, sys
idx = json.load(open(sys.argv[1]))
want = {sys.argv[2]: ("linux", "amd64"), sys.argv[3]: ("linux", "arm64")}
got = {}
for m in idx.get("manifests", []):
    p = m.get("platform", {})
    got[m["digest"]] = (p.get("os"), p.get("architecture"))
extra = set(got) - set(want)
missing = set(want) - set(got)
if extra or missing:
    print(f"  members differ — missing {sorted(missing)}, unexpected {sorted(extra)}")
    sys.exit(1)
for d, platform in want.items():
    if got[d] != platform:
        print(f"  {d} is declared {got[d]}, expected {platform}")
        sys.exit(1)
    print(f"  ok member {platform[0]}/{platform[1]}: {d}")
PY
note "index digest: $GHCR_INDEX"

# --- 8. clean up the Ops credential and the local references -----------------
step "8/8 clean up"
docker rmi "$AMD64_TAG" "$ARM64_TAG" >/dev/null 2>&1 || true
# The Ops-sourced reference must not linger: nothing in this repository may
# depend on registry.yvnn.is.
docker rmi "$OPS_SOURCE@$UPSTREAM_AMD64" >/dev/null 2>&1 || true
if [ "$SKIP_LOGIN" -eq 0 ]; then
    docker logout "$OPS_REGISTRY" >/dev/null
    note "logged out of $OPS_REGISTRY (the GHCR login is kept: contributors pull the private mirror with it)"
fi

# --- report -------------------------------------------------------------------
printf '\n\033[1m=== mirror published ===\033[0m\n'
printf 'index    %s@%s\n' "$TARGET" "$GHCR_INDEX"
printf 'amd64    %s  (upstream member, unchanged)\n' "$UPSTREAM_AMD64"
printf 'arm64    %s  (upstream member, unchanged)\n' "$UPSTREAM_ARM64"
printf 'tags     %s, %s, %s, %s\n' "$INDEX_TAG_RELEASE" "$INDEX_TAG_UPSTREAM" "$AMD64_TAG" "$ARM64_TAG"
printf '\nprovenance records (for docs/dev/third-party-mirrors.md):\n'
printf 'amd64 %s\n' "$AMD64_RECORD"
printf 'arm64 %s\n' "$ARM64_RECORD"
printf '\nSTILL MANUAL — the package is private, so grant CI read access:\n'
printf '  https://github.com/users/yannisyoussef/packages/container/testinbox-mirror%%2Fminio/settings\n'
printf '  -> Manage Actions access -> Add repository -> yannisyoussef/testinbox -> Read\n'
printf '  -> confirm visibility is Private, and enable deletion protection if offered\n'
