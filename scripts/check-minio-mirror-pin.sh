#!/usr/bin/env bash
# Every consumer of the MinIO image must pin the SAME mirror digest, and the
# provenance document must record that same digest.
#
# WHY THIS EXISTS: MinIO withdrew its public images, so the image now comes from
# a mirror we own (docs/dev/third-party-mirrors.md) and is referenced from six
# places — two compose files and four Testcontainers suites. Nothing stops
# someone updating one and leaving the others behind, and that drift is silent:
# CI would stay green while the rehearsal proved one MinIO version and the test
# suites proved another. A mixture is also the exact state the repair was
# required not to leave. The only loud moment is a re-pin, so this gate makes
# that moment loud.
#
# It deliberately does NOT hardcode the expected digest. The digest changes
# whenever the mirror is legitimately refreshed; what must never change is that
# all references agree and none reaches for a withdrawn upstream.
#
# Usage: scripts/check-minio-mirror-pin.sh
# Fixtures: SCAN_ROOT=<dir> to check a tree other than this repository.
set -uo pipefail

SCAN_ROOT="${SCAN_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
# Raised deliberately when a consumer is added; a silently shrinking gate is one
# of the failures this number exists to catch.
MINIMUM_REFERENCES="${MINIMUM_REFERENCES:-6}"
# The mirror we publish to. Anything else claiming to be MinIO is a finding.
EXPECTED_REPO="ghcr.io/yannisyoussef/testinbox-mirror/minio"
# Where the digest must also be written down, so code and provenance cannot drift.
PROVENANCE_DOC="docs/dev/third-party-mirrors.md"

status=0
finding() { printf '%s\n' "$*" >&2; status=1; }

# Only files that can actually *run* an image are scanned. Prose and the
# mirroring script itself must be free to name the withdrawn references — that
# is their subject matter.
consumers() {
    find "$SCAN_ROOT" \
        \( -name .git -o -name node_modules -o -name build -o -name .gradle -o -name .rehearsal \) -prune -o \
        \( -name '*.yaml' -o -name '*.yml' -o -name '*.kt' \) -type f -print 2>/dev/null |
        grep -vE '/(\.github/workflows|docs)/' | sort
}

# A reference is any MinIO image reference, however spelled — that is the point:
# a withdrawn or unpinned one must be found, not skipped.
references() {
    consumers | while read -r file; do
        grep -oE '(quay\.io/minio/[a-z]+|docker\.io/minio/[a-z]+|minio/(minio|mc)|ghcr\.io/[A-Za-z0-9._/-]*/minio)(@sha256:[0-9a-f]+|:[A-Za-z0-9._-]+)?' "$file" 2>/dev/null |
            while read -r ref; do printf '%s\t%s\n' "$file" "$ref"; done
    done
}

found=0
digests=""
while IFS=$'\t' read -r file ref; do
    [ -n "$ref" ] || continue
    # `asCompatibleSubstituteFor("minio/minio")` names the substituted image, not
    # a registry to pull from; Testcontainers requires it. It carries no tag or
    # digest, which is exactly how it is told apart from a real reference.
    case "$ref" in
        minio/minio|minio/mc) continue ;;
    esac
    rel="${file#"$SCAN_ROOT"/}"
    case "$ref" in
        "$EXPECTED_REPO"@sha256:*)
            digest="${ref#*@}"
            case "$digest" in
                sha256:????????????????????????????????????????????????????????????????) ;;
                *) finding "$rel: malformed digest in '$ref'" ;;
            esac
            digests="$digests$digest"$'\n'
            found=$((found + 1))
            ;;
        "$EXPECTED_REPO":*|"$EXPECTED_REPO")
            finding "$rel: the mirror is referenced by TAG, not by digest ('$ref'). Tags are mutable; pin the index digest."
            ;;
        *)
            finding "$rel: '$ref' is not the mirror. MinIO's public images are withdrawn — see $PROVENANCE_DOC."
            ;;
    esac
done <<EOF
$(references)
EOF

if [ "$found" -lt "$MINIMUM_REFERENCES" ]; then
    finding "only $found mirror reference(s) found, expected at least $MINIMUM_REFERENCES. Either a consumer lost its pin, or the floor in this gate is stale."
fi

unique="$(printf '%s' "$digests" | grep -c . 2>/dev/null; true)"
distinct="$(printf '%s' "$digests" | sort -u | grep -c . 2>/dev/null; true)"
if [ "${distinct:-0}" -gt 1 ]; then
    finding "consumers disagree about which mirror digest to use — a partial re-pin:"
    printf '%s' "$digests" | sort | uniq -c | sed 's/^/    /' >&2
fi

# Code and provenance must not drift apart: the document is what a future reader
# trusts to know what these bytes are.
if [ "${distinct:-0}" -eq 1 ] && [ -f "$SCAN_ROOT/$PROVENANCE_DOC" ]; then
    pinned="$(printf '%s' "$digests" | sort -u | grep . )"
    grep -q "$EXPECTED_REPO@$pinned" "$SCAN_ROOT/$PROVENANCE_DOC" ||
        finding "$PROVENANCE_DOC does not record the digest the code pins ($pinned). Update the provenance record."
fi

if [ "$status" -eq 0 ]; then
    echo "MinIO mirror pin: $unique reference(s), one digest, recorded in $PROVENANCE_DOC"
fi
exit "$status"
