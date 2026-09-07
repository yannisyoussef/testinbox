#!/usr/bin/env bash
# Installs a pinned Trivy to /tmp/trivy, verifying the download.
#
# The checksum matters more than usual here: this runs inside the image-build
# job, which by that point holds a live GHCR credential and an OIDC identity
# for attestation. GitHub release assets are mutable by the publisher, so a
# version pin alone does not pin the bytes — a substituted binary would execute
# with the ability to push images under the org and mint provenance for them.
set -euo pipefail

VERSION="${TRIVY_VERSION:-0.68.0}"
DEST="${TRIVY_DEST:-/tmp}"
ARCHIVE="trivy_${VERSION}_Linux-64bit.tar.gz"
BASE="https://github.com/aquasecurity/trivy/releases/download/v${VERSION}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

curl -fsSL -o "$work/$ARCHIVE" "$BASE/$ARCHIVE"
curl -fsSL -o "$work/checksums.txt" "$BASE/trivy_${VERSION}_checksums.txt"

# The publisher's own checksum file, fetched over TLS from the same release.
# This does not defend against a publisher who replaces both — that needs
# provenance verification — but it does catch a swapped asset, a truncated
# download and a CDN error, which are the realistic cases.
( cd "$work" && grep " ${ARCHIVE}\$" checksums.txt | sha256sum -c - )

tar xzf "$work/$ARCHIVE" -C "$DEST" trivy
"$DEST/trivy" --version
