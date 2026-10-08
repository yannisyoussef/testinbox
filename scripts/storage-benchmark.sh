#!/usr/bin/env bash
# ADR-035 §11 global-admission enablement gate: runs the formal benchmark
# harness (backend/benchmark) and exits with its verdict.
#
#   0  PASS         every §11 criterion held on the full matrix
#   1  FAIL         a criterion failed → ADR REVIEW REQUIRED (per-node escrow is
#                   the named fallback, not implemented)
#   3  INCOMPLETE   the matrix did not fully run (a smoke, a missing level, a
#                   missing reference); never evidence of a pass
#   2  usage, or the safety preflight refused the target
#
# The harness is DESTRUCTIVE to its target database. It refuses to start
# unless TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK is set to exactly
#   I_UNDERSTAND_THIS_DESTROYS_THE_TARGET_DATABASE
# the database name contains "bench", and the database is empty or created by
# the run itself (--create-database). See docs/dev/storage-benchmark.md.
#
# Usage:
#   scripts/storage-benchmark.sh --local --concurrency 1,10 --rates 50 --duration-seconds 10 --workspaces 50
#   scripts/storage-benchmark.sh --jdbc-url jdbc:postgresql://db:5432/testinbox_bench --create-database \
#       --host-class <host class> --workspaces <staging count>,10000
#
# Every flag is passed through to the harness (`--help` lists them). Gradle is
# used only to build the start script; the harness runs as its own process so
# its exit code is the verdict, not Gradle's.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_DIR="$ROOT/backend/benchmark/build/install/storage-benchmark"
LAUNCHER="$INSTALL_DIR/bin/storage-benchmark"

if [[ $# -eq 0 ]]; then
  echo "usage: $(basename "$0") (--local | --jdbc-url <url> [--create-database]) [harness flags...]" >&2
  echo "       $(basename "$0") --help   # every harness flag" >&2
  exit 2
fi

for arg in "$@"; do
  if [[ "$arg" == "--help" || "$arg" == "-h" ]]; then
    (cd "$ROOT/backend" && ./gradlew -q :benchmark:installDist) || exit 2
    "$LAUNCHER" --help || true
    exit 2
  fi
done

if [[ "${TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK:-}" != "I_UNDERSTAND_THIS_DESTROYS_THE_TARGET_DATABASE" ]]; then
  echo "REFUSED: TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK must be set to exactly I_UNDERSTAND_THIS_DESTROYS_THE_TARGET_DATABASE" >&2
  echo "         The harness truncates and reseeds every tenant table of its target database." >&2
  exit 2
fi

echo "building the harness (./gradlew :benchmark:installDist)..." >&2
(cd "$ROOT/backend" && ./gradlew -q :benchmark:installDist) || {
  echo "FAIL: the harness did not build" >&2
  exit 2
}

# Default evidence directory: under the module's build/, which is gitignored.
# A result must be copied into docs/dev/production-ops-acceptance.md row G by
# a human; the harness never writes into the repository.
OUT_SET=0
for arg in "$@"; do [[ "$arg" == "--out" ]] && OUT_SET=1; done
EXTRA=()
if [[ $OUT_SET -eq 0 ]]; then
  EXTRA=(--out "$ROOT/backend/benchmark/build/benchmark-evidence")
fi
export TESTINBOX_GIT_SHA="${TESTINBOX_GIT_SHA:-$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}"

set +e
"$LAUNCHER" "$@" "${EXTRA[@]}"
STATUS=$?
set -e

case "$STATUS" in
  0) echo "VERDICT: PASS (exit 0)" >&2 ;;
  1) echo "VERDICT: FAIL (exit 1) — ADR REVIEW REQUIRED (per-node escrow is the named fallback, not implemented)" >&2 ;;
  3) echo "VERDICT: INCOMPLETE (exit 3) — not evidence of a pass" >&2 ;;
  2) echo "usage error or safety refusal (exit 2)" >&2 ;;
  *) echo "the harness ended abnormally (exit $STATUS)" >&2 ;;
esac
exit "$STATUS"
