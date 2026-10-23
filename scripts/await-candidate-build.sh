#!/usr/bin/env bash
# Waits for develop's build of a release candidate before anything resolves
# its digests (ADR-034 §3).
#
# THE RACE THIS CLOSES: a release pull request's head moves the instant
# develop does, so its `pull_request` run starts in the same second as the
# `push` run that builds and publishes that commit's images. The first real
# release pull request (#51) failed exactly this way — "no published image" —
# not because anything was wrong with the candidate, but because the
# artifacts were eight minutes from existing. A gate that fails on timing is
# a gate people learn to re-run without reading.
#
# It waits, bounded, for the push-triggered Staging workflow run of the exact
# candidate SHA to complete successfully. A failed or cancelled build is a
# refusal: there is nothing to promote. A feature-branch head is refused
# immediately rather than waited on — no push build will ever exist for it.
#
# Usage: await-candidate-build.sh --candidate <40-hex> --head-ref <ref>
#          [--timeout-seconds 1500] [--interval-seconds 30]
# Env: GITHUB_REPOSITORY, GH_BIN (stubbed by the self-test), BUILD_WORKFLOW
#      (default deploy-staging.yml).
set -uo pipefail

GH="${GH_BIN:-gh}"
REPO="${GITHUB_REPOSITORY:-yannisyoussef/testinbox}"
WORKFLOW="${BUILD_WORKFLOW:-deploy-staging.yml}"
candidate="" head_ref="" timeout=1500 interval=30
while [[ $# -gt 0 ]]; do
  case "$1" in
    --candidate) candidate="${2:-}"; shift 2 ;;
    --head-ref) head_ref="${2:-}"; shift 2 ;;
    --timeout-seconds) timeout="${2:-}"; shift 2 ;;
    --interval-seconds) interval="${2:-}"; shift 2 ;;
    *) echo "usage: $(basename "$0") --candidate <sha> --head-ref <ref> [--timeout-seconds N] [--interval-seconds N]" >&2; exit 2 ;;
  esac
done

refuse() { echo "CANDIDATE BUILD NOT AVAILABLE: $*" >&2; exit 1; }

[[ "$candidate" =~ ^[0-9a-f]{40}$ ]] || refuse "candidate '$candidate' is not a 40-character lowercase hex SHA"
[[ "$timeout" =~ ^[0-9]+$ && "$interval" =~ ^[1-9][0-9]*$ ]] || refuse "timeout and interval must be non-negative integers (interval > 0)"
# Only develop gets a push build; waiting for one that can never come would
# turn a clear refusal into a slow one.
[[ "$head_ref" == "develop" ]] || refuse "release pull requests promote 'develop'; '${head_ref:-<unset>}' has no push build to wait for"

deadline=$(( $(date +%s) + timeout ))
while :; do
  runs="$("$GH" run list --repo "$REPO" --workflow "$WORKFLOW" --event push --commit "$candidate" \
    --json databaseId,status,conclusion --limit 5 2>/dev/null)" || runs="[]"
  state="$(printf '%s' "$runs" | jq -r 'if length == 0 then "none" else (.[0] | "\(.status)/\(.conclusion // "")") end')"
  case "$state" in
    completed/success) echo "ok — develop's build of $candidate completed successfully"; exit 0 ;;
    completed/*) refuse "develop's build of $candidate ended '${state#completed/}'; there is nothing to promote" ;;
    none|queued/*|in_progress/*|waiting/*|pending/*|requested/*)
      if (( $(date +%s) >= deadline )); then
        refuse "develop's build of $candidate did not complete within ${timeout}s (last state: $state)"
      fi
      echo "waiting for develop's build of $candidate (state: $state)"
      sleep "$interval" ;;
    *) refuse "unexpected run state '$state' for $candidate" ;;
  esac
done
