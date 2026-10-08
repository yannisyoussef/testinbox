#!/usr/bin/env bash
# Proves check-storage-activation.sh discriminates: every ADR-035 §14 gate can
# fail INDEPENDENTLY of the others, an unevaluated gate blocks, and the gate
# that is not required for TENANT_LIMITS never decides the verdict. Fixtures
# live in scripts/testdata/storage-activation/; a throwaway git repository gives
# the rollback-floor gate real ancestry; no database or network is needed.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/check-storage-activation.sh"
FIX="$SCRIPT_DIR/testdata/storage-activation"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
export TESTINBOX_ACTIVATION_EVALUATED_AT="2026-10-07T00:00:00Z"
unset TESTINBOX_ACTIVATION_DB_URL

pass=0
fail=0
ok() { echo "ok   — $1"; pass=$((pass + 1)); }
bad() { echo "FAIL — $1"; shift; [ $# -eq 0 ] || printf '%s\n' "$@" | sed 's/^/       /'; fail=$((fail + 1)); }

# --- a throwaway repository: base -> floor -> head (head commits the floors file) ---
git -C "$TMP" init -q repo
commit() { git -C "$TMP/repo" -c user.name=t -c user.email=t@t add -A >/dev/null 2>&1; git -C "$TMP/repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "$1"; git -C "$TMP/repo" rev-parse HEAD; }
BASE="$(commit base)"
FLOOR="$(commit "ADR-035 guarded ingest protocol (fixture floor)")"
mkdir -p "$TMP/repo/deploy"
{
    printf '# fixture floors\n'
    printf '%s V4 managed credentials (fixture, not an ADR-035 floor)\n' "$BASE"
    printf '%s ADR-035 guarded ingest protocol (TI-STORAGE fixture): an earlier artifact writes outside the fence\n' "$FLOOR"
} > "$TMP/repo/deploy/rollback-floors.txt"
HEAD_SHA="$(commit "floors file")"
cp -R "$TMP/repo" "$TMP/repo-behind"
git -C "$TMP/repo" update-ref refs/remotes/origin/master "$HEAD_SHA"     # master has the floor and the file
git -C "$TMP/repo-behind" update-ref refs/remotes/origin/master "$BASE"  # master predates the floor
REPO_OK="$TMP/repo"
REPO_BEHIND="$TMP/repo-behind"

# Metrics fixtures carry @GIT_SHA@; render them against the throwaway history.
M="$TMP/metrics"; mkdir -p "$M"
render() { sed "s/@GIT_SHA@/$2/g" "$FIX/metrics/$1" > "$M/$1"; }
for f in "$FIX"/metrics/*.prom; do render "$(basename "$f")" "$HEAD_SHA"; done
sed "s/@GIT_SHA@/$BASE/g" "$FIX/metrics/api-healthy.prom" > "$M/api-at-base.prom"
sed "s/@GIT_SHA@/$HEAD_SHA/g" "$FIX/benchmark/pass.json" > "$TMP/bench-pass.json"
sed "s/@GIT_SHA@/$HEAD_SHA/g" "$FIX/benchmark/fail.json" > "$TMP/bench-fail.json"
sed "s/@GIT_SHA@/$HEAD_SHA/g" "$FIX/benchmark/pass-but-t1-violates.json" > "$TMP/bench-t1.json"
sed "s/@GIT_SHA@/$HEAD_SHA/g" "$FIX/benchmark/missing-reference.json" > "$TMP/bench-noref.json"
sed "s/@GIT_SHA@/$HEAD_SHA/g" "$FIX/benchmark/incomplete.json" > "$TMP/bench-incomplete.json"
sed "s/@GIT_SHA@/$(printf 'f%.0s' $(seq 1 40))/g" "$FIX/benchmark/pass.json" > "$TMP/bench-unknown-sha.json"

# Identity variants are derived from the matching one, so each case changes exactly one element.
variant() { jq "$2" "$FIX/identity/match.json" > "$TMP/id-$1.json"; }
variant digest '.minio.imageIndexDigest = "sha256:0a7215f643cdaa475b6c4d674e8be6a06f71459158a677c98ff1a9f89d71853d"'
variant mode '.minio.mode = "distributed-erasure-4-drives" | .minio.driveCount = 4'
variant timeout-env '.minio.timeoutEnvironment.MINIO_IDLE_TIMEOUT = "5m"'
variant timeout-cli '.minio.timeoutCliFlags = ["--idle-timeout=5m"]'
variant hash-null '.minio.runtimeConfig.hash = null'
variant hash-mismatch '.minio.runtimeConfig.hash = "sha256:9999999999999999999999999999999999999999999999999999999999999999"'
variant kernel '.host.kernelRelease = "6.1.0-other"'
variant fs '.host.filesystemType = "nfs4"'
variant mount '.host.mountOptions = "rw,noatime"'
variant proxy '.network.directPath = false | .network.proxy = "traefik"'
variant upload '.uploadImplementationVersion = "adr035-upload-v2"'

# The inputs that satisfy every gate for --mode ALL.
good() {
    printf '%s\n' --mode ALL --repo "$REPO_OK" \
        --api-metrics "$M/api-healthy.prom" --ingestion-metrics "$M/ingestion-healthy.prom" \
        --expected-api-nodes api-1 --expected-ingestion-nodes ing-1 \
        --sessions-file "$FIX/sessions/healthy.tsv" --nodes-file "$FIX/nodes/healthy.tsv" \
        --qualification-dir "$FIX/qualification" --backend-identity "$FIX/identity/match.json" \
        --benchmark-evidence "$TMP/bench-pass.json"
}
# with <flag> <value>: the good inputs with one flag's value replaced (removed when
# value is ""), or the flag appended when the good inputs do not carry it.
with() {
    local flag="$1" value="$2"
    good | awk -v flag="$flag" -v value="$value" '
        skip { skip = 0; next }
        $0 == flag { skip = 1; seen = 1; if (value != "") { print flag; print value }; next }
        { print }
        END { if (!seen && value != "") { print flag; print value } }'
}
# run_args <file-with-args> [extra args...] → runs the gate; OUT and STATUS are set.
OUT=""; STATUS=0
run_args() {
    local argfile="$1"; shift
    local args=""
    # bash 3.2: no mapfile; rebuild argv through "$@" by reading the file.
    set -- "$@"
    while IFS= read -r line; do set -- "$@" "$line"; done < "$argfile"
    OUT="$("$GATE" "$@" 2>&1)"; STATUS=$?
}

# case <name> <expected-exit> <regex-the-output-must-match> <argfile> [extra args...]
case_() {
    local name="$1" expected="$2" pattern="$3" argfile="$4"; shift 4
    run_args "$argfile" "$@"
    if [ "$STATUS" != "$expected" ]; then bad "$name (expected exit $expected, got $STATUS)" "$OUT"; return 1; fi
    if [ -n "$pattern" ] && ! printf '%s\n' "$OUT" | grep -qE -- "$pattern"; then bad "$name (output does not match /$pattern/)" "$OUT"; return 1; fi
    ok "$name"
}
# only <name> <gate> <detail-regex> <argfile>: exit 1, that gate BLOCKED/NOT RUN with the detail,
# and EVERY other gate PASS or NOT REQUIRED — the failure is independent.
only() {
    local name="$1" gate="$2" pattern="$3" argfile="$4" others
    run_args "$argfile"
    if [ "$STATUS" != 1 ]; then bad "$name (expected exit 1, got $STATUS)" "$OUT"; return 1; fi
    if ! printf '%s\n' "$OUT" | grep -qE -- "^$gate[[:space:]]+(BLOCKED|NOT RUN)[[:space:]].*$pattern"; then
        bad "$name ($gate is not the blocked gate, or the detail does not match /$pattern/)" "$OUT"; return 1
    fi
    others="$(printf '%s\n' "$OUT" | grep -E '^[A-Z]-[a-z-]+[[:space:]]' | grep -vE "^$gate[[:space:]]" | grep -vE '^[A-Z]-[a-z-]+[[:space:]]+(PASS|NOT REQUIRED)[[:space:]]')"
    if [ -n "$others" ]; then bad "$name (another gate also failed; the gates are not independent)" "$others"; return 1; fi
    printf '%s\n' "$OUT" | grep -q '^ACTIVATION BLOCKED$' || { bad "$name (overall verdict is not ACTIVATION BLOCKED)" "$OUT"; return 1; }
    ok "$name"
}

# ---------------------------------------------------------------------------
# All healthy, and the evidence record.
# ---------------------------------------------------------------------------
good > "$TMP/args-good"
case_ "all gates satisfied → ACTIVATION READY" 0 '^ACTIVATION READY$' "$TMP/args-good" --json "$TMP/evidence.json"
if [ -f "$TMP/evidence.json" ] && jq -e '.mode == "ALL" and .evaluatedAt == "2026-10-07T00:00:00Z" and .verdict == "READY"
        and (.gates | length) == 8 and all(.gates[]; .verdict == "PASS" and (.gate | length) > 0 and (.detail | length) > 0)' "$TMP/evidence.json" >/dev/null 2>&1; then
    ok "the evidence JSON has {mode, evaluatedAt, gates[{gate, verdict, detail}], verdict} with 8 gates"
else
    bad "the evidence JSON shape is wrong" "$(cat "$TMP/evidence.json" 2>/dev/null)"
fi
run_args "$TMP/args-good" --json "$TMP/evidence-2.json"
if cmp -s "$TMP/evidence.json" "$TMP/evidence-2.json"; then ok "identical inputs produce identical evidence (deterministic)"; else bad "two runs on identical inputs differ" "$(diff "$TMP/evidence.json" "$TMP/evidence-2.json")"; fi
printf '%s\n' "$OUT" | grep -qE '^A-sessions[[:space:]]+PASS[[:space:]]' && ok "the table lists gates with PASS verdicts" || bad "the human-readable table is missing" "$OUT"

# ---------------------------------------------------------------------------
# Gate A (a): session allowlist.
# ---------------------------------------------------------------------------
with --sessions-file "$FIX/sessions/pgjdbc-default.tsv" > "$TMP/a1"
only "A-sessions: pgJDBC's default application_name (an old binary) blocks" A-sessions "PostgreSQL JDBC Driver" "$TMP/a1"
with --sessions-file "$FIX/sessions/listen-old.tsv" > "$TMP/a2"
only "A-sessions: the old LISTEN name 'testinbox-listen' blocks" A-sessions "application_name='testinbox-listen' " "$TMP/a2"
with --sessions-file "$FIX/sessions/empty-name.tsv" > "$TMP/a3"
only "A-sessions: an empty application_name blocks" A-sessions "application_name=''" "$TMP/a3"
with --sessions-file "$FIX/sessions/ops-and-migrator.tsv" > "$TMP/a4"
case_ "A-sessions: testinbox-migrator:% and ops:% sessions are allowed exclusions" 0 '^A-sessions[[:space:]]+PASS[[:space:]].*3 storage-v1 session\(s\), 3 excluded' "$TMP/a4"
# ADR-035 §14 (a) scopes the allowlist to the APPLICATION role: a backup role's pg_dump,
# an exporter and a DBA's psql are out of scope with --application-role, and violations without it.
with --sessions-file "$FIX/sessions/other-roles.tsv" > "$TMP/a5"
case_ "A-sessions: sessions of other roles are out of scope with --application-role" 0 '^A-sessions[[:space:]]+PASS[[:space:]].*3 session\(s\) of other roles not evaluated' "$TMP/a5" --application-role testinbox_app
with --sessions-file "$FIX/sessions/other-roles.tsv" > "$TMP/a6"
only "A-sessions: without --application-role every role is evaluated, so pg_dump blocks" A-sessions "application_name='pg_dump'" "$TMP/a6"

with --sessions-file "" > "$TMP/a5"
only "A-sessions: no sessions input → NOT RUN, and NOT RUN blocks" A-sessions "no --sessions-file" "$TMP/a5"
printf 'ops:only\ttestinbox_ops\n' > "$TMP/no-app.tsv"
with --sessions-file "$TMP/no-app.tsv" > "$TMP/a6"
only "A-sessions: no storage-v1 session at all blocks (the binaries are not connected)" A-sessions "no storage-v1 session observed" "$TMP/a6"

# ---------------------------------------------------------------------------
# Gate A (a): node inventory — each disagreement is its own message.
# ---------------------------------------------------------------------------
with --nodes-file "$FIX/nodes/missing-api.tsv" > "$TMP/i1"
only "A-inventory: a declared API node without a storage_node row blocks" A-inventory "declared node 'api-1' has no storage_node row" "$TMP/i1"
with --nodes-file "$FIX/nodes/missing-ingestion.tsv" > "$TMP/i2"
only "A-inventory: a declared ingestion node without a storage_node row blocks" A-inventory "declared node 'ing-1' has no storage_node row" "$TMP/i2"
with --nodes-file "$FIX/nodes/stale-heartbeat.tsv" > "$TMP/i3"
only "A-inventory: a stale heartbeat blocks" A-inventory "'ing-1' heartbeat is stale or absent \(911 s old" "$TMP/i3"
with --nodes-file "$FIX/nodes/wrong-capability.tsv" > "$TMP/i4"
only "A-inventory: a wrong capability blocks" A-inventory "'ing-1' has capability 'storage-v0'" "$TMP/i4"
with --nodes-file "$FIX/nodes/extra-node.tsv" > "$TMP/i5"
only "A-inventory: an extra undeclared node blocks" A-inventory "undeclared node 'ing-2' is heartbeating storage-v1" "$TMP/i5"
with --nodes-file "$FIX/nodes/clean-shutdown.tsv" > "$TMP/i6"
only "A-inventory: a cleanly shut-down node is not running" A-inventory "'ing-1' recorded a clean shutdown" "$TMP/i6"
with --nodes-file "" > "$TMP/i7"
only "A-inventory: no nodes input → NOT RUN" A-inventory "no --nodes-file" "$TMP/i7"

# ---------------------------------------------------------------------------
# Barrier B: physical baseline.
# ---------------------------------------------------------------------------
with --api-metrics "$M/api-sweep-never.prom" > "$TMP/b1"
only "B: no full orphan sweep completed since start (0) blocks" B-physical-baseline "no full orphan sweep has completed since process start" "$TMP/b1"
with --api-metrics "$M/api-sweep-before-start.prom" > "$TMP/b2"
only "B: a sweep that completed before this process started blocks" B-physical-baseline "predates process start" "$TMP/b2"
with --api-metrics "$M/api-physical-exceeds.prom" > "$TMP/b3"
only "B: physical_listed > covered + H blocks, with the figures" B-physical-baseline "physical_listed_bytes 4.0E8 > covered committed 9.437184E7 \+ reserved 1.572864E7 \+ finalize budget H 2.5165824E8 = 361758720" "$TMP/b3"
with --api-metrics "" > "$TMP/b4"
case_ "B: no API metrics → NOT RUN (and the declared API node is then unmeasured for C)" 1 '^B-physical-baseline[[:space:]]+NOT RUN[[:space:]]' "$TMP/b4"
printf '%s\n' "$OUT" | grep -qE '^C-clock-offset[[:space:]]+BLOCKED[[:space:]].*1 API node\(s\) declared but only 0' && ok "C: a declared node with no metrics endpoint is unmeasured, so C blocks" || bad "C did not notice the unmeasured declared node" "$OUT"

# ---------------------------------------------------------------------------
# Barrier C: clock offset, latch, breaker — on EVERY node.
# ---------------------------------------------------------------------------
with --api-metrics "$M/api-offset-high.prom" > "$TMP/c1"
only "C: |offset| > 30 s on the API node blocks" C-clock-offset "clock offset -31.5 s exceeds ε_max 30 s" "$TMP/c1"
with --ingestion-metrics "$M/ingestion-offset-absent.prom" > "$TMP/c2"
only "C: an absent offset metric on the ingestion node blocks ('no offset measured')" C-clock-offset "\(ingestion\): no offset measured" "$TMP/c2"
with --api-metrics "$M/api-latched.prom" > "$TMP/c3"
only "C: a set admission latch blocks, naming the node" C-clock-offset "\(api\): admission latch is SET" "$TMP/c3"
with --ingestion-metrics "$M/ingestion-breaker-open.prom" > "$TMP/c4"
only "C: an open breaker blocks, naming the node" C-clock-offset "\(ingestion\): storage breaker is OPEN" "$TMP/c4"
case_ "C: ε_max is configurable (--max-clock-offset-seconds 60 admits -31.5 s)" 0 '^C-clock-offset[[:space:]]+PASS' "$TMP/c1" --max-clock-offset-seconds 60

# ---------------------------------------------------------------------------
# Gate D: the §11 benchmark, required only for ALL.
# ---------------------------------------------------------------------------
with --benchmark-evidence "" > "$TMP/d1"
only "D: --mode ALL without benchmark evidence → NOT RUN" D-benchmark "no --benchmark-evidence" "$TMP/d1"
good | sed 's/^ALL$/TENANT_LIMITS/' | awk 'BEGIN{skip=0} skip{skip=0; next} /^--benchmark-evidence$/{skip=1; next} {print}' > "$TMP/d2"
case_ "D: --mode TENANT_LIMITS without benchmark → NOT REQUIRED and ACTIVATION READY" 0 '^ACTIVATION READY$' "$TMP/d2"
printf '%s\n' "$OUT" | grep -qE '^D-benchmark[[:space:]]+NOT REQUIRED[[:space:]].*NOT REQUIRED FOR TENANT_LIMITS' && ok "D: the TENANT_LIMITS verdict is literally NOT REQUIRED, never PASS" || bad "D is not NOT REQUIRED for TENANT_LIMITS" "$OUT"
good | sed 's/^ALL$/TENANT_LIMITS/' | sed "s#$TMP/bench-pass.json#$TMP/bench-fail.json#" > "$TMP/d3"
case_ "D: --mode TENANT_LIMITS with a FAILED benchmark is still READY (the gate does not decide)" 0 '^ACTIVATION READY$' "$TMP/d3"
with --benchmark-evidence "$TMP/bench-fail.json" > "$TMP/d4"
only "D: a benchmark whose verdict is FAIL blocks" D-benchmark "benchmark verdict is 'FAIL', not PASS" "$TMP/d4"
with --benchmark-evidence "$TMP/bench-t1.json" > "$TMP/d5"
only "D: a recorded PASS whose numbers violate T1 p99 ≤ 50 ms is caught by recomputation" D-benchmark "recomputation of the §11 criteria disagrees with the recorded PASS: t1P99: chosen-ws10000-c10-r520 T1 p99 61.3 ms > 50 ms" "$TMP/d5"
with --benchmark-evidence "$TMP/bench-noref.json" > "$TMP/d5b"
only "D: a CHOSEN 2× scenario with no matching REFERENCE run blocks (retention cannot be judged)" D-benchmark "retentionP99VsReference: no REFERENCE scenario for chosen-ws10000-c10-r520 at \(workspaces 10000, concurrency 10, 520/s\)" "$TMP/d5b"
with --benchmark-evidence "$TMP/bench-incomplete.json" > "$TMP/d5c"
only "D: an INCOMPLETE run blocks, naming its incompleteReasons" D-benchmark "benchmark verdict is INCOMPLETE: only one population was run \(laptop smoke\); concurrency matrix 1,10 instead of 1,10,25,50,100" "$TMP/d5c"
# The gate pins §11 itself: evidence that says it is not ADR evidence, lacks a concurrency,
# judged against a same-lock reference, or carries other errors, blocks whatever its verdict says.
jq '.adrEvidence = false | .adrEvidenceReasons = ["not ADR evidence: expectedRate 25 ≠ 260"]' "$TMP/bench-pass.json" > "$TMP/bench-nonadr.json"
with --benchmark-evidence "$TMP/bench-nonadr.json" > "$TMP/d8"
only "D: a run the harness marked as not ADR evidence blocks, naming the departure" D-benchmark "not ADR evidence.*expectedRate 25" "$TMP/d8"
jq 'del(.scenarios[] | select(.name == "chosen-ws10000-c100-r520"))' "$TMP/bench-pass.json" > "$TMP/bench-nocov.json"
with --benchmark-evidence "$TMP/bench-nocov.json" > "$TMP/d9"
only "D: a missing concurrency in the 2× matrix blocks (coverage is recomputed, not trusted)" D-benchmark "coverage: no CHOSEN 2× scenario at concurrency 100" "$TMP/d9"
jq '(.scenarios[] | select(.mode == "REFERENCE") | .referenceMode) = "CEILING_OFF"' "$TMP/bench-pass.json" > "$TMP/bench-ceilingoff.json"
with --benchmark-evidence "$TMP/bench-ceilingoff.json" > "$TMP/d10"
only "D: a same-lock (CEILING_OFF) reference is not the no-ceiling mode c and blocks" D-benchmark "is CEILING_OFF, not the no-ceiling mode c" "$TMP/d10"
jq '(.scenarios[] | select(.name == "chosen-ws10000-c25-r520") | .errors.other) = 3' "$TMP/bench-pass.json" > "$TMP/bench-other.json"
with --benchmark-evidence "$TMP/bench-other.json" > "$TMP/d11"
only "D: other errors in any scenario block" D-benchmark "otherErrors: 3 across all scenarios" "$TMP/d11"
jq '.harness.sustainedTolerance = 0.5 | (.scenarios[] | select(.name == "chosen-ws10000-c50-r520") | .achievedRate) = 300' "$TMP/bench-pass.json" > "$TMP/bench-loose.json"
with --benchmark-evidence "$TMP/bench-loose.json" > "$TMP/d12"
only "D: a tolerance written into the evidence is ignored; the pinned 0.995 judges" D-benchmark "sustained2x: chosen-ws10000-c50-r520 achieved 300" "$TMP/d12"
with --benchmark-evidence "$TMP/bench-unknown-sha.json" > "$TMP/d6"
case_ "D: a benchmark gitSha that cannot be related to the running API warns but passes" 0 'WARNING: cannot relate benchmark gitSha ffffffffffff' "$TMP/d6"
printf '{"verdict":"PASS","gitSha":"x"}' > "$TMP/bench-shapeless.json"
with --benchmark-evidence "$TMP/bench-shapeless.json" > "$TMP/d7"
only "D: a PASS with no scenarios does not pass (fail closed)" D-benchmark "no scenarios recorded" "$TMP/d7"

# ---------------------------------------------------------------------------
# Gate E: rollback floors (throwaway history).
# ---------------------------------------------------------------------------
with --repo "$REPO_BEHIND" > "$TMP/e1"
only "E-production: master lacking the ADR-035 floor blocks, naming the floor" E-floor-production "floor ${FLOOR:0:12} is not an ancestor of origin/master.*floor ${FLOOR:0:12} is not listed in origin/master:deploy/rollback-floors.txt" "$TMP/e1"
with --api-metrics "$M/api-at-base.prom" > "$TMP/e2"
only "E-staging: a running artifact that predates the floor blocks" E-floor-staging "running git_sha ${BASE:0:12} predates floor ${FLOOR:0:12}" "$TMP/e2"
printf '%s V4 managed credentials (fixture, nothing about storage)\n' "$BASE" > "$TMP/floors-none.txt"
with --floors-file "$TMP/floors-none.txt" > "$TMP/e3"
case_ "E: a floors file with no ADR-035 / TI-STORAGE floor blocks both floor gates (fail closed)" 1 '^E-floor-production[[:space:]]+BLOCKED[[:space:]].*no ADR-035 / TI-STORAGE rollback floor' "$TMP/e3"
printf '%s\n' "$OUT" | grep -qE '^E-floor-staging[[:space:]]+BLOCKED[[:space:]].*no ADR-035' && ok "E-staging also refuses when no ADR-035 floor is declared" || bad "E-staging passed with no floor declared" "$OUT"
git -C "$TMP" init -q repo-nomaster >/dev/null 2>&1; git -C "$TMP/repo-nomaster" -c user.name=t -c user.email=t@t commit -q --allow-empty -m x
with --repo "$TMP/repo-nomaster" > "$TMP/e4"
case_ "E-production: a checkout without origin/master blocks rather than assuming" 1 '^E-floor-production[[:space:]]+BLOCKED[[:space:]].*origin/master is not present' "$TMP/e4" --floors-file "$REPO_OK/deploy/rollback-floors.txt"

# ---------------------------------------------------------------------------
# Gate Q: §9a qualification.
# ---------------------------------------------------------------------------
with --backend-identity "" > "$TMP/q0"
only "Q: no backend identity → NOT RUN" Q-qualification "no --backend-identity" "$TMP/q0"
qcase() { with --backend-identity "$TMP/id-$1.json" > "$TMP/q-$1"; only "Q: $2 mismatch blocks, naming the element" Q-qualification "no qualification record matches.*$3" "$TMP/q-$1"; }
qcase digest "image index digest" "minio.imageIndexDigest"
qcase mode "server mode" "minio.mode"
qcase timeout-env "timeout environment" "minio.timeoutEnvironment"
qcase timeout-cli "timeout CLI flags" "minio.timeoutCliFlags"
qcase hash-null "runtime-config hash null" "minio.runtimeConfig.hash \(declared identity has no hash\)"
qcase hash-mismatch "runtime-config hash" "minio.runtimeConfig.hash"
qcase kernel "kernel release" "host.kernelRelease"
qcase fs "filesystem type" "host.filesystemType"
qcase mount "mount options" "host.mountOptions"
qcase proxy "network path / proxy" "network.directPath, network.proxy"
qcase upload "upload implementation version" "uploadImplementationVersion"
with --backend-identity "$FIX/identity/ineligible-match.json" > "$TMP/q-inel"
only "Q: a matching but ineligible record blocks with its reasons" Q-qualification "record production-amd64-candidate.json matches but is not enablement-eligible: slow-W scenario not executed" "$TMP/q-inel"
case_ "Q: Ops qualification-check valid=0 blocks even with a matching eligible record" 1 '^Q-qualification[[:space:]]+BLOCKED[[:space:]].*qualification_valid=0' "$TMP/args-good" --qualification-valid-metric 0
case_ "Q: Ops qualification-check valid=1 with a matching eligible record passes" 0 '^Q-qualification[[:space:]]+PASS[[:space:]].*valid=1' "$TMP/args-good" --qualification-valid-metric 1
case_ "Q: without the Ops value the detail says the real backend is unobserved" 0 '^Q-qualification[[:space:]]+PASS[[:space:]].*real backend is unobserved' "$TMP/args-good"
mkdir -p "$TMP/qual-empty"; : > "$TMP/qual-empty/index.txt"
with --qualification-dir "$TMP/qual-empty" > "$TMP/q-empty"
only "Q: an empty qualification index blocks (nothing is qualified)" Q-qualification "no qualification record matches" "$TMP/q-empty"

# ---------------------------------------------------------------------------
# Usage.
# ---------------------------------------------------------------------------
: > "$TMP/empty-args"
case_ "usage: --mode is required (exit 2)" 2 'mode TENANT_LIMITS\|ALL is required' "$TMP/empty-args"
case_ "usage: an unknown mode is a usage error" 2 'must be TENANT_LIMITS or ALL' "$TMP/empty-args" --mode ON
case_ "usage: an unknown argument is a usage error" 2 'unknown argument' "$TMP/empty-args" --mode ALL --bogus
case_ "usage: a missing input file is a usage error, not a verdict" 2 'input file not found' "$TMP/empty-args" --mode ALL --sessions-file "$TMP/does-not-exist.tsv"

# ---------------------------------------------------------------------------
# THIS repository's floors (deploy/rollback-floors.txt) against origin/master.
# The ADR-035 floors predate master's adoption of ADR-035: until master
# contains them AND lists them in its floors file, E-production must be
# BLOCKED. The expectation is computed, not hardcoded, so the case stays
# honest after master catches up.
# ---------------------------------------------------------------------------
expected_real=0; real_detail=""; master_present=true
if git -C "$REPO" rev-parse --verify -q origin/master >/dev/null 2>&1; then
    master_floors="$(git -C "$REPO" show origin/master:deploy/rollback-floors.txt 2>/dev/null)"
    while IFS= read -r line; do
        line="${line%%#*}"; [ -n "${line// /}" ] || continue
        floor="${line%% *}"; why="${line#* }"
        case "$why" in *ADR-035*|*TI-STORAGE*) ;; *) continue ;; esac
        git -C "$REPO" merge-base --is-ancestor "$floor" origin/master 2>/dev/null || { expected_real=1; real_detail="$real_detail ${floor:0:12}(not-ancestor)"; }
        printf '%s\n' "$master_floors" | grep -q "^$floor " || { expected_real=1; real_detail="$real_detail ${floor:0:12}(unlisted)"; }
    done < "$REPO/deploy/rollback-floors.txt"
else
    # Without origin/master the gate still blocks (fail closed) but cannot name floors.
    expected_real=1; master_present=false; real_detail=" origin/master absent from this checkout"
fi
: > "$TMP/real-args"
run_args "$TMP/real-args" --mode TENANT_LIMITS --repo "$REPO"
if [ "$expected_real" = 1 ]; then
    if [ "$STATUS" = 1 ] && printf '%s\n' "$OUT" | grep -qE '^E-floor-production[[:space:]]+BLOCKED[[:space:]]'; then
        ok "real repository: E-production reports BLOCKED today (master predates ADR-035:$real_detail)"
    else
        bad "real repository: E-production should be BLOCKED today ($real_detail)" "$OUT"
    fi
    if $master_present; then
        for floor in $(grep -E 'ADR-035|TI-STORAGE' "$REPO/deploy/rollback-floors.txt" | grep -oE '^[0-9a-f]{40}'); do
            printf '%s\n' "$OUT" | grep -qE "^E-floor-production[[:space:]]+BLOCKED[[:space:]].*${floor:0:12}" \
                && ok "real repository: the blocked verdict names floor ${floor:0:12}" \
                || bad "real repository: floor ${floor:0:12} is not named in the E-production detail" "$OUT"
        done
    else
        printf '%s\n' "$OUT" | grep -qE '^E-floor-production[[:space:]]+BLOCKED[[:space:]].*origin/master is not present' \
            && ok "real repository: without origin/master the gate blocks rather than assuming" \
            || bad "real repository: origin/master is absent but the gate did not say so" "$OUT"
    fi
else
    if printf '%s\n' "$OUT" | grep -qE '^E-floor-production[[:space:]]+PASS[[:space:]]'; then
        ok "real repository: master now carries every ADR-035 floor, E-production PASS"
    else
        bad "real repository: master carries the floors but E-production is not PASS" "$OUT"
    fi
fi
printf '%s\n' "$OUT" | grep -qE '^A-sessions[[:space:]]+NOT RUN' && printf '%s\n' "$OUT" | grep -q '^ACTIVATION BLOCKED$' \
    && ok "real repository: with no runtime inputs every other gate is NOT RUN and the verdict is BLOCKED" \
    || bad "real repository: unevaluated gates did not block" "$OUT"

echo "----"
echo "check-storage-activation.test.sh: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
