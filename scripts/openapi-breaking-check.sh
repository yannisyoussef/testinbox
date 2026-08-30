#!/usr/bin/env bash
# ADR-022 requires the contract gate to be two things: a structural/style lint
# (Spectral) and a backwards-compatibility check. This is the second one.
#
#   openapi-breaking-check.sh <base-spec> <revision-spec>
#
# A breaking change inside v1 fails the build (ADR-015: additive-only within a
# major version). Warnings — e.g. removing an optional request parameter — are
# reported but do not fail, matching that policy.
#
# oasdiff is pinned; OASDIFF_BIN overrides the binary (used by CI caches and by
# this script's own tests).
set -euo pipefail

OASDIFF_VERSION="${OASDIFF_VERSION:-1.29.1}"
CACHE_DIR="${OASDIFF_CACHE_DIR:-${TMPDIR:-/tmp}/oasdiff-$OASDIFF_VERSION}"

usage() {
  echo "usage: $(basename "$0") <base-spec> <revision-spec>" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
BASE="$1"
REVISION="$2"
[[ -f "$BASE" ]] || { echo "base spec not found: $BASE" >&2; exit 2; }
[[ -f "$REVISION" ]] || { echo "revision spec not found: $REVISION" >&2; exit 2; }

resolve_oasdiff() {
  if [[ -n "${OASDIFF_BIN:-}" ]]; then
    echo "$OASDIFF_BIN"
    return
  fi
  local binary="$CACHE_DIR/oasdiff"
  if [[ ! -x "$binary" ]]; then
    local arch
    case "$(uname -m)" in
      x86_64) arch=amd64 ;;
      aarch64 | arm64) arch=arm64 ;;
      *) echo "unsupported architecture: $(uname -m)" >&2; exit 2 ;;
    esac
    mkdir -p "$CACHE_DIR"
    curl -fsSL \
      "https://github.com/oasdiff/oasdiff/releases/download/v${OASDIFF_VERSION}/oasdiff_${OASDIFF_VERSION}_linux_${arch}.tar.gz" |
      tar xz -C "$CACHE_DIR" oasdiff
  fi
  echo "$binary"
}

OASDIFF="$(resolve_oasdiff)"

# githubactions format renders findings as inline annotations on the PR; it
# rejects --color, which is only meaningful for the text formats.
FORMAT_ARGS=(--format text --color never)
[[ -n "${GITHUB_ACTIONS:-}" ]] && FORMAT_ARGS=(--format githubactions)

echo "$("$OASDIFF" --version) — comparing:"
echo "  base:     $BASE"
echo "  revision: $REVISION"
"$OASDIFF" breaking "$BASE" "$REVISION" --fail-on ERR "${FORMAT_ARGS[@]}"
