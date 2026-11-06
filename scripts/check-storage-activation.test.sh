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
CONTAINMENT="$(commit "TI-STORAGE-006E containment (fixture floor)")"
mkdir -p "$TMP/repo/deploy"
{
    printf '# fixture floors\n'
    printf '%s V4 managed credentials (fixture, not an ADR-035 floor)\n' "$BASE"
    printf '%s ADR-035 guarded ingest protocol (TI-STORAGE fixture): an earlier artifact writes outside the fence\n' "$FLOOR"
    printf '%s TI-STORAGE-006E filesystem containment (fixture): an earlier artifact admits without rule C and deletes without debt\n' "$CONTAINMENT"
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
        --benchmark-evidence "$TMP/bench-pass.json" \
        --filesystem-evidence "$FIX/filesystem/evidence-good.json" --footprint-state "$FIX/filesystem/state-good.json" \
        --cohort-check-output "$FIX/cohort/check-pass.txt" --cohort-pool-file "$FIX/cohort/pool.json" \
        --population-state "$FIX/population/pre-plan-within.json"
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
# swap <flag> <value>: a filter form of `with`, so several flags can be replaced in one argument list.
swap() {
    awk -v flag="$1" -v value="$2" '
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
        and (.gates | length) == 12 and all(.gates[]; .verdict == "PASS" and (.gate | length) > 0 and (.detail | length) > 0)
        and (.cohortGate | startswith("storage-budget check"))' "$TMP/evidence.json" >/dev/null 2>&1; then
    ok "the evidence JSON has {mode, evaluatedAt, gates[{gate, verdict, detail}], verdict, cohortGate} with 12 gates (gates L and W included)"
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
# Gate L: T1's lock protocol. Every node must PROVE GLOBAL; absent = unknown = blocked.
# ---------------------------------------------------------------------------
sed 's/mode="global"} 1.0/mode="global"} 0.0/; s/mode="cohort_partitioned"} 0.0/mode="cohort_partitioned"} 1.0/' "$M/ingestion-healthy.prom" > "$M/ingestion-partitioned.prom"
grep -v 'testinbox_storage_admission_locking' "$M/ingestion-healthy.prom" > "$M/ingestion-no-locking.prom"
with --ingestion-metrics "$M/ingestion-partitioned.prom" > "$TMP/l1"
only "L: a node running the share-partitioned T1 blocks, naming it" L-lock-protocol "runs the share-partitioned T1" "$TMP/l1"
with --ingestion-metrics "$M/ingestion-no-locking.prom" > "$TMP/l2"
only "L: a node whose lock protocol is not exported is UNKNOWN and blocks, never read as mode a" L-lock-protocol "T1 lock protocol UNKNOWN" "$TMP/l2"
with --ingestion-metrics "$M/ingestion-partitioned.prom" | sed 's/^ALL$/TENANT_LIMITS/' > "$TMP/l3"
case_ "L: ...and blocks under TENANT_LIMITS too" 1 '^L-lock-protocol[[:space:]]+BLOCKED[[:space:]].*runs the share-partitioned T1' "$TMP/l3"
awk -F'\t' 'BEGIN { OFS = "\t" } /^#/ || NF < 4 { print; next } $1 == "ing-1" { print $0, "COHORT_PARTITIONED"; next } { print $0, "GLOBAL" }' \
    "$FIX/nodes/healthy.tsv" > "$TMP/nodes-partitioned.tsv"
with --nodes-file "$TMP/nodes-partitioned.tsv" > "$TMP/l4"
only "L: a live storage_node row recording COHORT_PARTITIONED blocks (the database, not only the metric)" L-lock-protocol "storage_node 'ing-1' records admission_locking COHORT_PARTITIONED" "$TMP/l4"
awk -F'\t' 'BEGIN { OFS = "\t" } /^#/ || NF < 4 { print; next } { print $0, "GLOBAL" }' "$FIX/nodes/healthy.tsv" > "$TMP/nodes-global.tsv"
with --nodes-file "$TMP/nodes-global.tsv" > "$TMP/l5"
case_ "L: GLOBAL metrics and GLOBAL node rows pass, and so does the whole barrier" 0 '^L-lock-protocol[[:space:]]+PASS' "$TMP/l5"

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
# ADR-035 §11 as amended 2026-10-08: concurrency 1 is the uncontended baseline. The PASS fixture's
# c1 run cannot sustain 520/s (achieved 301.5, T1 p99 412.7 ms) and that alone must not block;
# the same shortfall at a load-gated concurrency still blocks.
good > "$TMP/d1x"
case_ "D: concurrency 1 failing the load criteria does not block (baseline, reported only)" 0 '^D-benchmark[[:space:]]+PASS' "$TMP/d1x"
jq '(.scenarios[] | select(.name == "chosen-ws10000-c25-r520") | .achievedRate) = 301.5' "$TMP/bench-pass.json" > "$TMP/bench-c25.json"
with --benchmark-evidence "$TMP/bench-c25.json" > "$TMP/d13"
only "D: the same shortfall at concurrency 25 still blocks" D-benchmark "sustained2x: chosen-ws10000-c25-r520 achieved 301.5" "$TMP/d13"
jq '(.scenarios[] | select(.name == "chosen-ws10000-c1-r520") | .errors.deadlocks) = 1 | (.scenarios[] | select(.name == "chosen-ws10000-c1-r520") | .deadlocks) = 1' "$TMP/bench-pass.json" > "$TMP/bench-c1dl.json"
with --benchmark-evidence "$TMP/bench-c1dl.json" > "$TMP/d14"
only "D: an integrity failure at concurrency 1 (a deadlock) still blocks" D-benchmark "deadlocks: 1 across all scenarios" "$TMP/d14"
jq '(.scenarios[] | select(.name == "chosen-ws10000-c100-r520") | .achievedRate) = 301.5' "$TMP/bench-pass.json" > "$TMP/bench-c100.json"
with --benchmark-evidence "$TMP/bench-c100.json" > "$TMP/d15"
only "D: the same shortfall at concurrency 100 still blocks" D-benchmark "sustained2x: chosen-ws10000-c100-r520 achieved 301.5" "$TMP/d15"
# Coverage is not exempted at concurrency 1: its reference and samples are still required, and the
# retention ratio (relative to a reference at the same offered load) is still judged there.
jq 'del(.scenarios[] | select(.name == "reference-ws10000-c1-r520"))' "$TMP/bench-pass.json" > "$TMP/bench-c1noref.json"
with --benchmark-evidence "$TMP/bench-c1noref.json" > "$TMP/d16"
only "D: a missing NO_LOCK reference at concurrency 1 still blocks" D-benchmark "retentionP99VsReference: no REFERENCE scenario for chosen-ws10000-c1-r520" "$TMP/d16"
jq '(.scenarios[] | select(.name == "chosen-ws10000-c1-r520") | .percentiles.retention.p99Ms) = 999' "$TMP/bench-pass.json" > "$TMP/bench-c1ret.json"
with --benchmark-evidence "$TMP/bench-c1ret.json" > "$TMP/d17"
only "D: retention above twice the reference at concurrency 1 still blocks" D-benchmark "retentionP99VsReference: chosen-ws10000-c1-r520 retention p99 999" "$TMP/d17"
jq '(.scenarios[] | select(.name == "chosen-ws10000-c1-r520") | .percentiles.t1.p99Ms) = null' "$TMP/bench-pass.json" > "$TMP/bench-c1not1.json"
with --benchmark-evidence "$TMP/bench-c1not1.json" > "$TMP/d18"
only "D: concurrency 1 without T1 samples still blocks (coverage, not a load criterion)" D-benchmark "t1P99: chosen-ws10000-c1-r520 has no T1 samples" "$TMP/d18"
with --benchmark-evidence "$TMP/bench-unknown-sha.json" > "$TMP/d6"
case_ "D: a benchmark gitSha that cannot be related to the running API warns but passes" 0 'WARNING: cannot relate benchmark gitSha ffffffffffff' "$TMP/d6"
printf '{"verdict":"PASS","gitSha":"x"}' > "$TMP/bench-shapeless.json"
with --benchmark-evidence "$TMP/bench-shapeless.json" > "$TMP/d7"
only "D: a PASS with no scenarios does not pass (fail closed)" D-benchmark "no scenarios recorded" "$TMP/d7"

# ---------------------------------------------------------------------------
# Gate D, ADR-035 amendment D (2026-11-06): §11's expected rate R is R_gated,
# the rate every node declares (testinbox_storage_gated_expected_rate). The
# evidence must have run at exactly it; every other criterion is unchanged.
# ---------------------------------------------------------------------------
# The closed-beta evidence: the PASS fixture re-run at R = 31 and 2R = 62 (r = 1, 30 + L = 31),
# over the same 30 s window: floor(rate x 30) events scheduled, the same completion fraction.
jq '.harness.expectedRate = 31.0 | .harness.rates = [31.0, 62.0]
    | .scenarios |= map(
        .achievedRate = (if .achievedRate == null then null else .achievedRate * (if .offeredRate == 260 then 31 / 260 else 62 / 520 end) end)
        | .offeredRate = (if .offeredRate == 260 then 31.0 else 62.0 end)
        | ((.offeredRate * 30) | floor) as $n
        | .completedEvents = ((.completedEvents * $n / .scheduledEvents) | round) | .scheduledEvents = $n
        | .name |= (sub("-r260$"; "-r31") | sub("-r520$"; "-r62")))' "$TMP/bench-pass.json" > "$TMP/bench-beta.json"
jq -e '[.scenarios[] | .scheduledEvents == ((.offeredRate * 30) | floor)] | all' "$TMP/bench-beta.json" >/dev/null \
    && ok "D: the closed-beta fixture schedules floor(rate x 30) events, a 30 s window like the run it stands for" \
    || bad "D: the closed-beta fixture's scheduledEvents is not a 30 s window"
declare_rate() { sed -E "s/^(testinbox_storage_gated_expected_rate\{[^}]*\}) .*/\1 $3/" "$M/$1" > "$M/$2"; }
declare_rate api-healthy.prom api-r31.prom 31.0
declare_rate ingestion-healthy.prom ingestion-r31.prom 31.0
grep -v '^testinbox_storage_gated_expected_rate' "$M/ingestion-healthy.prom" > "$M/ingestion-no-rgated.prom"
beta() { good | sed -e "s#$M/api-healthy.prom#$M/api-r31.prom#" -e "s#$M/ingestion-healthy.prom#$M/ingestion-r31.prom#" -e "s#$TMP/bench-pass.json#$TMP/bench-beta.json#"; }

beta > "$TMP/dr1"
case_ "D: nodes declaring R_gated 31 and evidence run at 31 and 62 → ACTIVATION READY" 0 '^ACTIVATION READY$' "$TMP/dr1"
printf '%s\n' "$OUT" | grep -qE '^D-benchmark[[:space:]]+PASS[[:space:]].*declared R_gated 31' && ok "D: the PASS names the declared R_gated it was judged at" || bad "D did not judge at the declared R_gated 31" "$OUT"
beta | sed "s#$TMP/bench-beta.json#$TMP/bench-pass.json#" > "$TMP/dr2"
only "D: evidence run at 260 does not count where the nodes declare R_gated 31" D-benchmark "ran at expected rate 260(\.0)? events/s but the nodes declare R_gated 31" "$TMP/dr2"
with --benchmark-evidence "$TMP/bench-beta.json" > "$TMP/dr3"
only "D: evidence run at 31 does not count where the nodes declare R_gated 260" D-benchmark "ran at expected rate 31(\.0)? events/s but the nodes declare R_gated 260" "$TMP/dr3"
jq '(.scenarios[] | select(.name == "chosen-ws10000-c50-r62") | .achievedRate) = 55.0' "$TMP/bench-beta.json" > "$TMP/bench-beta-short.json"
beta | sed "s#$TMP/bench-beta.json#$TMP/bench-beta-short.json#" > "$TMP/dr4"
only "D: at R_gated 31 the 2× criterion is still sustained 62/s at the pinned 0.995" D-benchmark "sustained2x: chosen-ws10000-c50-r62 achieved 55" "$TMP/dr4"
jq '(.scenarios[] | select(.name == "chosen-ws10000-c25-r62") | .percentiles.t1.p99Ms) = 50.5' "$TMP/bench-beta.json" > "$TMP/bench-beta-t1.json"
beta | sed "s#$TMP/bench-beta.json#$TMP/bench-beta-t1.json#" > "$TMP/dr5"
only "D: at R_gated 31 T1 p99 is still ≤ 50 ms" D-benchmark "t1P99: chosen-ws10000-c25-r62 T1 p99 50.5 ms > 50 ms" "$TMP/dr5"
jq 'del(.scenarios[] | select(.name == "chosen-ws10000-c100-r62"))' "$TMP/bench-beta.json" > "$TMP/bench-beta-nocov.json"
beta | sed "s#$TMP/bench-beta.json#$TMP/bench-beta-nocov.json#" > "$TMP/dr6"
only "D: at R_gated 31 the full concurrency matrix is still required" D-benchmark "coverage: no CHOSEN 2× scenario at concurrency 100" "$TMP/dr6"
with --ingestion-metrics "$M/ingestion-no-rgated.prom" > "$TMP/dr7"
only "D: a node that declares no R_gated blocks (UNKNOWN, never the old 260)" D-benchmark "R_gated UNKNOWN \(testinbox_storage_gated_expected_rate absent\)" "$TMP/dr7"
with --ingestion-metrics "$M/ingestion-r31.prom" > "$TMP/dr8"
only "D: nodes that disagree on R_gated block" D-benchmark "declares R_gated 31(\.0)? but another node declares 260" "$TMP/dr8"
with --expected-rate 31 > "$TMP/dr9"
only "D: --expected-rate may only confirm the declaration, never replace it" D-benchmark "--expected-rate 31 differs from the R_gated 260(\.0)? the nodes declare" "$TMP/dr9"
with --expected-rate 260 > "$TMP/dr10"
case_ "D: --expected-rate equal to the declared R_gated confirms it" 0 '^ACTIVATION READY$' "$TMP/dr10"
beta > "$TMP/dr11"
case_ "D: --expected-rate 31 confirms a declared 31" 0 '^ACTIVATION READY$' "$TMP/dr11" --expected-rate 31
with --expected-rate fast > "$TMP/dr12"
case_ "D: a non-numeric --expected-rate is a usage error" 2 'expected-rate must be a positive number' "$TMP/dr12"
with --expected-rate 0 > "$TMP/dr13"
case_ "D: a zero --expected-rate is a usage error" 2 'expected-rate must be a positive number' "$TMP/dr13"
jq 'del(.harness.expectedRate)' "$TMP/bench-pass.json" > "$TMP/bench-norate.json"
with --benchmark-evidence "$TMP/bench-norate.json" > "$TMP/dr14"
only "D: evidence that records no expected rate cannot be bound to R_gated" D-benchmark "records no harness.expectedRate" "$TMP/dr14"
good | sed -e 's/^ALL$/TENANT_LIMITS/' -e "s#$M/ingestion-healthy.prom#$M/ingestion-no-rgated.prom#" > "$TMP/dr15"
case_ "D: TENANT_LIMITS needs no R_gated (the gate is NOT REQUIRED there)" 0 '^ACTIVATION READY$' "$TMP/dr15"

# ---------------------------------------------------------------------------
# Gate D, the measured window (owner decision 2026-11-06, #124 6684712608): at
# R = 31 the harness measures >= 33 s so the 2R scenarios reach 100 retention
# samples, and sustained2x keeps the terminal-lag allowance of a 30 s window
# whatever the window. Every case below is the beta evidence with each
# scenario's events, completions, terminal lag and achieved rate rewritten as
# the harness's open loop reports them for a window of $2 s: durationSeconds =
# (N - 1) / rate + lag (the measured span, terminal lag included) and achieved =
# completed / durationSeconds. The c100 2R scenario carries lag $3 ms.
# ---------------------------------------------------------------------------
window_bench() {
    jq --argjson s "$2" --argjson lag "$3" '
        .scenarios |= map(
            ((.offeredRate * $s) | floor) as $n
            | .scheduledEvents = $n | .completedEvents = $n
            | .terminalLagMs = (if .name == "chosen-ws10000-c100-r62" then $lag else 8.0 end)
            | .durationSeconds = (($n - 1) / .offeredRate + .terminalLagMs / 1000)
            | .achievedRate = ($n / .durationSeconds))' "$TMP/bench-beta.json" > "$1"
}
window_case() { beta | sed "s#$TMP/bench-beta.json#$1#"; }
# At 30 s nothing changes: the reading is achieved/offered, so each lag passes exactly when that ratio is
# >= 0.995 (166.9 ms at 62/s with every event in), the rule before the window was held.
same=0
for lag in 0 50 120 150 160 165 166 167 168 175 205 300; do
    window_bench "$TMP/bench-w30-$lag.json" 30 "$lag"
    window_case "$TMP/bench-w30-$lag.json" > "$TMP/dw30-$lag"
    run_args "$TMP/dw30-$lag"
    raw_passes="$(jq -r '[.scenarios[] | select(.name == "chosen-ws10000-c100-r62") | .achievedRate / .offeredRate >= 0.995] | first' "$TMP/bench-w30-$lag.json")"
    if [ "$raw_passes" = true ]; then expected=0; else expected=1; fi
    [ "$STATUS" = "$expected" ] || { bad "D: at 30 s, lag $lag ms: exit $STATUS, but achieved/offered alone says $raw_passes" "$OUT"; same=1; }
done
[ "$same" = 0 ] && ok "D: at a 30 s window every terminal lag is judged exactly as achieved/offered >= 0.995 judges it (unchanged)"
window_bench "$TMP/bench-w30-205.json" 30 205
window_case "$TMP/bench-w30-205.json" > "$TMP/dw1"
only "D: at 30 s run 3's 205 ms terminal lag still blocks, on the achieved rate" D-benchmark "sustained2x: chosen-ws10000-c100-r62 achieved .* of 62(\.0)?/s offered \(ratio below 0.995\)" "$TMP/dw1"
window_bench "$TMP/bench-w33-160.json" 33 160
window_case "$TMP/bench-w33-160.json" > "$TMP/dw2"
case_ "D: a 33 s window with a terminal lag inside the 30 s allowance passes" 0 '^ACTIVATION READY$' "$TMP/dw2"
window_bench "$TMP/bench-w33-175.json" 33 175
jq -e '[.scenarios[] | select(.name == "chosen-ws10000-c100-r62") | .achievedRate / .offeredRate >= 0.995] | first' "$TMP/bench-w33-175.json" >/dev/null \
    && ok "D: (175 ms over 33 s is >= 0.995 read alone: the case below is the loosening the held allowance stops)" \
    || bad "D: the 33 s / 175 ms fixture does not pass achieved/offered alone, so it proves nothing"
window_case "$TMP/bench-w33-175.json" > "$TMP/dw3"
only "D: a 33 s window does not loosen the allowance: 175 ms blocks" D-benchmark "sustained2x: chosen-ws10000-c100-r62 terminal lag 175 ms is past the 30 s allowance" "$TMP/dw3"
window_bench "$TMP/bench-w60-300.json" 60 300
window_case "$TMP/bench-w60-300.json" > "$TMP/dw4"
only "D: a 60 s window does not loosen it either: 300 ms (0.9953 read alone) blocks" D-benchmark "sustained2x: chosen-ws10000-c100-r62 terminal lag 300 ms is past the 30 s allowance" "$TMP/dw4"
window_bench "$TMP/bench-w60-160.json" 60 160
window_case "$TMP/bench-w60-160.json" > "$TMP/dw5"
case_ "D: a 60 s window with a terminal lag inside the 30 s allowance passes" 0 '^ACTIVATION READY$' "$TMP/dw5"
jq '(.scenarios[] | select(.name == "chosen-ws10000-c100-r62")) |= del(.terminalLagMs)' "$TMP/bench-w33-160.json" > "$TMP/bench-w33-nolag.json"
window_case "$TMP/bench-w33-nolag.json" > "$TMP/dw6"
only "D: a window longer than 30 s without its terminal lag cannot be read over 30 s and blocks" D-benchmark "records no completedEvents or terminalLagMs" "$TMP/dw6"

# ---------------------------------------------------------------------------
# Gate D, the terminal-completion allowance at its exact boundary (owner decision
# 2026-11-06: the historical allowance is kept on condition that the 30 s outcomes are
# unchanged, with no widening through rounding or longer windows; docs/dev/storage-benchmark.md
# "Terminal-completion allowance"). With every event in, the allowance is
# lag_max(rate) = (1 + N30 x (1/0.995 - 1)) / rate, N30 = floor(30 x rate): 166.8828 ms at 62/s.
# One microsecond either side of it decides: at 30 s exactly as achieved/offered read alone
# decided before the window was held, and the same lag over any longer window the same way.
# ---------------------------------------------------------------------------
LAG_MAX_62="$(jq -n '((1 + 1860 * (1 / 0.995 - 1)) / 62) * 1000')"
LAG_BELOW="$(jq -n --argjson m "$LAG_MAX_62" '$m - 0.001')"
LAG_ABOVE="$(jq -n --argjson m "$LAG_MAX_62" '$m + 0.001')"
alone() { jq -r '[.scenarios[] | select(.name == "chosen-ws10000-c100-r62") | .achievedRate / .offeredRate >= 0.995] | first' "$1"; }
jq -en --argjson m "$LAG_MAX_62" '$m > 166.8828 and $m < 166.8829' >/dev/null \
    && ok "D: lag_max(62/s) = (1 + 1860 x (1/0.995 - 1)) / 62 = $LAG_MAX_62 ms (166.88)" \
    || bad "D: lag_max(62/s) is $LAG_MAX_62 ms, not 166.88"
window_bench "$TMP/bench-b30-below.json" 30 "$LAG_BELOW"
window_case "$TMP/bench-b30-below.json" > "$TMP/db1"
[ "$(alone "$TMP/bench-b30-below.json")" = true ] || bad "D: the 30 s / lag_max - 1 us fixture fails achieved/offered alone, so it proves nothing"
case_ "D: at 30 s, lag_max - 1 us passes, as achieved/offered alone passed it before the window was held" 0 '^ACTIVATION READY$' "$TMP/db1"
window_bench "$TMP/bench-b30-above.json" 30 "$LAG_ABOVE"
window_case "$TMP/bench-b30-above.json" > "$TMP/db2"
[ "$(alone "$TMP/bench-b30-above.json")" = false ] || bad "D: the 30 s / lag_max + 1 us fixture passes achieved/offered alone, so it proves nothing"
only "D: at 30 s, lag_max + 1 us blocks on the achieved rate, as before the window was held" D-benchmark \
    "sustained2x: chosen-ws10000-c100-r62 achieved .* of 62(\.0)?/s offered \(ratio below 0.995\)" "$TMP/db2"
# Its measured span is 30.15 s (> 30, terminal lag included), so the 30 s reading also runs, and
# agrees: at a 30 s schedule it IS achieved/offered, recomputed from completedEvents and terminalLagMs.
jq -e '[.scenarios[] | select(.name == "chosen-ws10000-c100-r62") | .durationSeconds > 30] | first' "$TMP/bench-b30-above.json" >/dev/null \
    || bad "D: the 30 s / lag_max + 1 us fixture does not measure more than 30 s, so it does not exercise the durationSeconds reading"
printf '%s\n' "$OUT" | grep -q 'past the 30 s allowance' \
    && bad "D: at 30 s the verdict came from the 30 s reading, not achieved/offered; they must agree at a 30 s schedule" "$OUT" \
    || ok "D: at 30 s the verdict and its message are achieved/offered's (the durationSeconds-triggered 30 s reading agrees)"
for s in 31 33 45 60 120; do
    window_bench "$TMP/bench-b$s-above.json" "$s" "$LAG_ABOVE"
    window_case "$TMP/bench-b$s-above.json" > "$TMP/db$s-above"
    [ "$(alone "$TMP/bench-b$s-above.json")" = true ] \
        || bad "D: the $s s / lag_max + 1 us fixture fails achieved/offered alone, so it does not show the widening it must stop"
    only "D: a $s s window does not widen the allowance: lag_max + 1 us (>= 0.995 read alone) blocks" D-benchmark \
        "sustained2x: chosen-ws10000-c100-r62 terminal lag [0-9.]+ ms is past the 30 s allowance" "$TMP/db$s-above"
    window_bench "$TMP/bench-b$s-below.json" "$s" "$LAG_BELOW"
    window_case "$TMP/bench-b$s-below.json" > "$TMP/db$s-below"
    case_ "D: a $s s window does not narrow it either: lag_max - 1 us passes" 0 '^ACTIVATION READY$' "$TMP/db$s-below"
done
# Partial completion shrinks the allowance. Over a long window the completion FRACTION is carried
# into the 30 s reading: one event missing of 3720 at 60 s blocks a lag (lag_max - 1 us) that every
# event in passes, although achieved/offered alone still passes it.
jq '(.scenarios[] | select(.name == "chosen-ws10000-c100-r62"))
        |= (.completedEvents = .scheduledEvents - 1
            | .achievedRate = (.completedEvents / ((.scheduledEvents - 1) / .offeredRate + .terminalLagMs / 1000)))' \
    "$TMP/bench-b60-below.json" > "$TMP/bench-b60-below-partial.json"
window_case "$TMP/bench-b60-below-partial.json" > "$TMP/dbp"
[ "$(alone "$TMP/bench-b60-below-partial.json")" = true ] || bad "D: the 60 s partial fixture fails achieved/offered alone, so it proves nothing"
only "D: at 60 s one missing event of 3720 shrinks the held allowance below lag_max - 1 us" D-benchmark \
    "sustained2x: chosen-ws10000-c100-r62 terminal lag [0-9.]+ ms is past the 30 s allowance" "$TMP/dbp"

# The measured window is read from scheduledEvents OR durationSeconds, each of which can only
# make the reading stricter (owner condition: "no widening ... through longer windows").
# Without scheduledEvents nothing can say how long the window was: fail closed, whatever
# durationSeconds says. (Until the gate read it this way, a 60 s scenario that omitted
# scheduledEvents kept the 60 s allowance and 300 ms passed.)
jq '(.scenarios[] | select(.name == "chosen-ws10000-c100-r62")) |= del(.scheduledEvents, .durationSeconds)' \
    "$TMP/bench-w60-300.json" > "$TMP/bench-w60-300-unscheduled.json"
window_case "$TMP/bench-w60-300-unscheduled.json" > "$TMP/dbg1"
only "D: a 60 s window that omits scheduledEvents (and durationSeconds) does not keep the 60 s allowance: it blocks" D-benchmark \
    "sustained2x: chosen-ws10000-c100-r62 records no scheduledEvents, so the window it measured cannot be read over 30 s \(fail closed\)" "$TMP/dbg1"
jq '.harness.durationSeconds = 60 | (.scenarios[] | select(.name == "chosen-ws10000-c100-r62")) |= del(.scheduledEvents)' \
    "$TMP/bench-w60-300.json" > "$TMP/bench-w60-300-duration-only.json"
jq -e '[.scenarios[] | select(.name == "chosen-ws10000-c100-r62") | .durationSeconds > 60] | first' "$TMP/bench-w60-300-duration-only.json" >/dev/null \
    || bad "D: the 60 s duration-only fixture does not record a 60 s durationSeconds"
window_case "$TMP/bench-w60-300-duration-only.json" > "$TMP/dbg2"
only "D: 60 s with durationSeconds but without scheduledEvents blocks" D-benchmark \
    "sustained2x: chosen-ws10000-c100-r62 records no scheduledEvents" "$TMP/dbg2"
# durationSeconds marks a long window scheduledEvents understates: 1860 scheduled (a 30 s count)
# but 60 s measured, with the achieved rate a 60 s run reports (0.9953 read alone) - blocks.
jq '(.scenarios[] | select(.name == "chosen-ws10000-c100-r62")) |= (.scheduledEvents = 1860 | .completedEvents = 1860)' \
    "$TMP/bench-w60-300.json" > "$TMP/bench-w60-300-understated.json"
[ "$(alone "$TMP/bench-w60-300-understated.json")" = true ] || bad "D: the understated fixture fails achieved/offered alone, so it proves nothing"
window_case "$TMP/bench-w60-300-understated.json" > "$TMP/dbg3"
only "D: a 60 s durationSeconds is read over 30 s even when scheduledEvents says 30 s: 300 ms blocks" D-benchmark \
    "sustained2x: chosen-ws10000-c100-r62 terminal lag 300 ms is past the 30 s allowance" "$TMP/dbg3"
# ...and it never relaxes: a durationSeconds that understates a 60 s schedule changes nothing.
jq '(.scenarios[] | select(.name == "chosen-ws10000-c100-r62")) |= (.durationSeconds = 29.0)' \
    "$TMP/bench-w60-300.json" > "$TMP/bench-w60-300-shortdur.json"
window_case "$TMP/bench-w60-300-shortdur.json" > "$TMP/dbg4"
only "D: a durationSeconds of 29 s does not shorten a 60 s schedule: 300 ms still blocks" D-benchmark \
    "sustained2x: chosen-ws10000-c100-r62 terminal lag 300 ms is past the 30 s allowance" "$TMP/dbg4"
# A run of at most 30 s whose measured span exceeds 30 s and that records no terminalLagMs cannot
# be read over 30 s either: fail closed (the harness always records it).
jq '(.scenarios[] | select(.name == "chosen-ws10000-c100-r62")) |= del(.terminalLagMs)' \
    "$TMP/bench-b30-below.json" > "$TMP/bench-b30-nolag.json"
window_case "$TMP/bench-b30-nolag.json" > "$TMP/dbg5"
only "D: a 30 s schedule measured over more than 30 s without its terminal lag blocks (fail closed)" D-benchmark \
    "records no completedEvents or terminalLagMs" "$TMP/dbg5"

# ---------------------------------------------------------------------------
# Gate W, ADR-035 amendment D: before a cohort plan exists every workspace
# receives mail, so under ALL the database holds at most N + L of them (and, with
# a lowered N, after the plan too). The bound comes from every node
# (testinbox_storage_envelope_max_workspaces), N too (testinbox_storage_envelope_participants).
# ---------------------------------------------------------------------------
with --population-state "$FIX/population/pre-plan-over.json" > "$TMP/w1"
only "W: no plan and one workspace over 30 + L blocks, with the count" W-workspace-envelope "no cohort plan exists and the database holds more than 31 workspaces \(counted 32\)" "$TMP/w1"
with --population-state "$FIX/population/plan-exists.json" > "$TMP/w2"
case_ "W: once a cohort plan exists the pool bounds the population (gate G), so the count does not block" 0 '^W-workspace-envelope[[:space:]]+PASS[[:space:]].*a cohort plan exists' "$TMP/w2"
printf '{"cohortPlanExists": false, "workspaces": 31}\n' > "$TMP/pop-31.json"
with --population-state "$TMP/pop-31.json" > "$TMP/w3"
case_ "W: exactly 30 + L passes" 0 '^W-workspace-envelope[[:space:]]+PASS[[:space:]].*31 workspace\(s\), within the envelope.s 31' "$TMP/w3"
with --population-state "" > "$TMP/w4"
only "W: no population input under ALL → NOT RUN, and NOT RUN blocks" W-workspace-envelope "no population: set TESTINBOX_ACTIVATION_DB_URL or pass --population-state" "$TMP/w4"
grep -v '^testinbox_storage_envelope_max_workspaces' "$M/ingestion-healthy.prom" > "$M/ingestion-no-envelope.prom"
with --ingestion-metrics "$M/ingestion-no-envelope.prom" > "$TMP/w5"
only "W: a node that declares no envelope blocks (UNKNOWN, never assumed)" W-workspace-envelope "envelope bound is UNKNOWN \(testinbox_storage_envelope_max_workspaces absent\)" "$TMP/w5"
sed -E 's/^(testinbox_storage_envelope_max_workspaces\{[^}]*\}) .*/\1 30.0/' "$M/ingestion-healthy.prom" > "$M/ingestion-envelope-30.prom"
with --ingestion-metrics "$M/ingestion-envelope-30.prom" > "$TMP/w6"
only "W: nodes that disagree on the envelope block" W-workspace-envelope "declares an envelope of 30(\.0)? workspaces but another node declares 31" "$TMP/w6"
# M0: a lowered N. The pool's thirty participant shares bound the population only at N = 30,
# so with N < 30 the count applies after the plan too, exactly as on the nodes (WorkspaceEnvelopeCheck).
for node in api ingestion; do
    sed -E -e 's/^(testinbox_storage_envelope_max_workspaces\{[^}]*\}) .*/\1 11.0/' \
        -e 's/^(testinbox_storage_envelope_participants\{[^}]*\}) .*/\1 10.0/' "$M/$node-healthy.prom" > "$M/$node-n10.prom"
done
printf '{"cohortPlanExists": true, "workspaces": 12}\n' > "$TMP/pop-plan-12.json"
printf '{"cohortPlanExists": true, "workspaces": 11}\n' > "$TMP/pop-plan-11.json"
good | swap --api-metrics "$M/api-n10.prom" | swap --ingestion-metrics "$M/ingestion-n10.prom" | swap --population-state "$TMP/pop-plan-12.json" > "$TMP/w9"
only "W: a plan with N = 10 and 12 workspaces blocks (admissions under a larger N; the pool does not bound a lowered N)" W-workspace-envelope \
    "a cohort plan exists but N = 10 is below 30.*more than 11 workspaces \(counted 12\)" "$TMP/w9"
good | swap --api-metrics "$M/api-n10.prom" | swap --ingestion-metrics "$M/ingestion-n10.prom" | swap --population-state "$TMP/pop-plan-11.json" > "$TMP/w10"
case_ "W: a plan with N = 10 and 11 workspaces (N + L) passes" 0 '^W-workspace-envelope[[:space:]]+PASS[[:space:]].*N = 10 is below 30; 11 workspace\(s\), within the envelope.s 11' "$TMP/w10"
with --population-state "$TMP/pop-plan-12.json" > "$TMP/w11"
case_ "W: N = 30 with a plan is unchanged - the pool bounds the population" 0 '^W-workspace-envelope[[:space:]]+PASS[[:space:]].*a cohort plan exists; the pool bounds the population' "$TMP/w11"
sed -E 's/^(testinbox_storage_envelope_participants\{[^}]*\}) .*/\1 10.0/' "$M/ingestion-healthy.prom" > "$M/ingestion-n-disagrees.prom"
with --ingestion-metrics "$M/ingestion-n-disagrees.prom" > "$TMP/w12"
only "W: nodes that disagree on N block" W-workspace-envelope "declares a participant cap N of 10(\.0)? but another node declares 30" "$TMP/w12"
grep -v '^testinbox_storage_envelope_participants' "$M/ingestion-healthy.prom" > "$M/ingestion-no-n.prom"
with --ingestion-metrics "$M/ingestion-no-n.prom" > "$TMP/w13"
only "W: a node that exports no N blocks (UNKNOWN, never assumed 30)" W-workspace-envelope "participant cap N is UNKNOWN" "$TMP/w13"
printf '{"cohortPlanExists": "no", "workspaces": 2}\n' > "$TMP/pop-bad.json"
with --population-state "$TMP/pop-bad.json" > "$TMP/w7"
only "W: a malformed population state blocks" W-workspace-envelope "population-state must be" "$TMP/w7"
good | sed -e 's/^ALL$/TENANT_LIMITS/' -e "s#$FIX/population/pre-plan-within.json#$FIX/population/pre-plan-over.json#" > "$TMP/w8"
case_ "W: TENANT_LIMITS is NOT REQUIRED, whatever the population" 0 '^ACTIVATION READY$' "$TMP/w8"
printf '%s\n' "$OUT" | grep -qE '^W-workspace-envelope[[:space:]]+NOT REQUIRED[[:space:]]' && ok "W: the TENANT_LIMITS verdict is literally NOT REQUIRED" || bad "W is not NOT REQUIRED for TENANT_LIMITS" "$OUT"

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
case_ "Q: a matching but ineligible record blocks with its reasons" 1 '^Q-qualification[[:space:]]+BLOCKED[[:space:]].*record production-amd64-candidate.json matches but is not enablement-eligible: slow-W scenario not executed' "$TMP/q-inel"
# Gate F re-checks the record by the id the filesystem evidence names: a disagreement blocks it too.
printf '%s\n' "$OUT" | grep -qE '^F-filesystem[[:space:]]+BLOCKED[[:space:]].*gate Q matched production-amd64-candidate.json, the evidence names laptop-arm64-reference.json' \
    && ok "F: a filesystem evidence naming another record than gate Q matched blocks" || bad "F did not notice the record disagreement" "$OUT"
case_ "Q: Ops qualification-check valid=0 blocks even with a matching eligible record" 1 '^Q-qualification[[:space:]]+BLOCKED[[:space:]].*qualification_valid=0' "$TMP/args-good" --qualification-valid-metric 0
case_ "Q: Ops qualification-check valid=1 with a matching eligible record passes" 0 '^Q-qualification[[:space:]]+PASS[[:space:]].*valid=1' "$TMP/args-good" --qualification-valid-metric 1
case_ "Q: without the Ops value the detail says the real backend is unobserved" 0 '^Q-qualification[[:space:]]+PASS[[:space:]].*real backend is unobserved' "$TMP/args-good"
mkdir -p "$TMP/qual-empty"; : > "$TMP/qual-empty/index.txt"
with --qualification-dir "$TMP/qual-empty" > "$TMP/q-empty"
case_ "Q: an empty qualification index blocks (nothing is qualified)" 1 '^Q-qualification[[:space:]]+BLOCKED[[:space:]].*no qualification record matches' "$TMP/q-empty"
printf '%s\n' "$OUT" | grep -qE '^F-filesystem[[:space:]]+BLOCKED[[:space:]].*not in the qualification index' \
    && ok "F: a record absent from the index blocks gate F too" || bad "F accepted a record absent from the index" "$OUT"
# Issue #102: a shipped record claiming platformClass "lab" is never eligible, in the checker as in the application.
mkdir -p "$TMP/qual-lab"
jq '.qualification.platformClass = "lab"' "$FIX/qualification/production-amd64-candidate.json" > "$TMP/qual-lab/production-amd64-candidate.json"
printf 'production-amd64-candidate.json\n' > "$TMP/qual-lab/index.txt" # the only record: the identity can match nothing else
with --qualification-dir "$TMP/qual-lab" | sed "s#identity/match.json#identity/ineligible-match.json#" > "$TMP/q-lab"
case_ "Q: a shipped record with platformClass lab is not enablement-eligible" 1 '^Q-qualification[[:space:]]+BLOCKED[[:space:]].*platformClass is lab' "$TMP/q-lab"

# ---------------------------------------------------------------------------
# Gate F (filesystem-containment contract §9): each row fails on its own, and
# every missing, malformed, stale or contradictory input is NOT RUN.
# ---------------------------------------------------------------------------
FSE="$FIX/filesystem/evidence-good.json"
FSS="$FIX/filesystem/state-good.json"
ev() { jq "$2" "$FSE" > "$TMP/ev-$1.json"; with --filesystem-evidence "$TMP/ev-$1.json" > "$TMP/f-ev-$1"; }
st() { jq "$2" "$FSS" > "$TMP/st-$1.json"; with --footprint-state "$TMP/st-$1.json" > "$TMP/f-st-$1"; }
fe() { ev "$1" "$2"; only "F: $3" F-filesystem "$4" "$TMP/f-ev-$1"; }
fs() { st "$1" "$2"; only "F: $3" F-filesystem "$4" "$TMP/f-st-$1"; }

with --filesystem-evidence "" > "$TMP/f0"
only "F: no filesystem evidence → NOT RUN" F-filesystem "no --filesystem-evidence supplied" "$TMP/f0"
with --footprint-state "" > "$TMP/f00"
only "F: no database state and no DB URL → NOT RUN" F-filesystem "no database state" "$TMP/f00"
good | sed 's/^ALL$/TENANT_LIMITS/' > "$TMP/f-tl"
case_ "F: TENANT_LIMITS runs the isolation preflight, and its PASS says containment does NOT hold" 0 \
    '^F-filesystem[[:space:]]+PASS[[:space:]]+TENANT_LIMITS isolation preflight held.*physical isolation ONLY.*containment theorem does NOT hold.*never public traffic' "$TMP/f-tl"

# Malformed and missing.
printf 'not json' > "$TMP/ev-garbage.json"; with --filesystem-evidence "$TMP/ev-garbage.json" > "$TMP/f-g"
only "F: evidence that is not JSON → NOT RUN" F-filesystem "filesystem evidence is not a JSON object" "$TMP/f-g"
printf '[]' > "$TMP/st-garbage.json"; with --footprint-state "$TMP/st-garbage.json" > "$TMP/f-sg"
only "F: state that is not a JSON object → NOT RUN" F-filesystem "footprint state is not a JSON object" "$TMP/f-sg"
fe schema '.schema = "testinbox.filesystem-evidence/0"' "an unknown evidence schema → NOT RUN" "not testinbox.filesystem-evidence/1"
fe no-uuid 'del(.filesystem.uuid)' "a missing filesystem UUID → NOT RUN" "malformed input: evidence.filesystem.uuid missing"
fe neg-blocks '.statvfs.blocks = -1' "a negative block count → NOT RUN" "evidence.statvfs.blocks is not a non-negative integer"
fe str-capacity '.declared.capacityBytes = "64G"' "a capacity given as a string → NOT RUN" "evidence.declared.capacityBytes is not a positive integer"
fe bad-b '.declared.blockSizeBytes = 8192' "an unsupported block size → NOT RUN" "blockSizeBytes 8192 is not a supported block size"
fe omax-shape '.declared.objectOverheadBytes = 8191' "an O_max no deployment can declare → NOT RUN" "objectOverheadBytes 8191 is not a multiple of B covering at least 6 blocks"
fe roles '.declared.applicationRoles = []' "no application role declared → NOT RUN" "applicationRoles is not a non-empty list of names"
fe image-sizes 'del(.preallocation.allocatedBytes)' "a backing image without its allocated size → NOT RUN" "preallocation.allocatedBytes missing"
fe bad-time '.collectedAt = "yesterday"' "an unreadable collection time → NOT RUN" "evidence.collectedAt is not an ISO-8601 UTC instant"
fs no-trust 'del(.trust.distrustEpoch)' "a state without the trust row → NOT RUN" "state.trust.distrustEpoch missing"
fs nodes '.liveNodes = "api-1"' "a state whose node list is not a list → NOT RUN" "state.liveNodes is not a list"
fs obs-shape 'del(.observation.usedBytes)' "an observation without used bytes → NOT RUN" "state.observation.usedBytes missing"

# Stale and contradictory.
fe stale '.collectedAt = "2026-10-06T23:40:00Z"' "evidence older than A_obs → NOT RUN" "stale input: the evidence was collected 1500 s ago, more than A_obs = 900 s"
fe future '.collectedAt = "2026-10-07T01:00:00Z"' "evidence from the future of the database clock → NOT RUN" "stale input: evidence.collectedAt is in the future"
fe frsize-vs-obs '.statvfs.frsize = 1024 | .declared.blockSizeBytes = 1024 | .statvfs.blocks = 67108864 | .declared.inodes = 67108864 | .statvfs.files = 67108864' \
    "statvfs disagreeing with the monitor's block size → NOT RUN" "contradictory input: the newest observation block size 4096 differs from statvfs f_frsize 1024"
fs cap-vs-obs '.observation.capacityBytes = 1' "a monitor capacity disagreeing with statvfs → NOT RUN" "contradictory input: the newest observation capacity 1 differs"
fs inodes-vs-obs '.observation.inodesTotal = 5' "a monitor inode total disagreeing with statvfs → NOT RUN" "contradictory input: the newest observation inode total 5 differs"
fe dev-vs-image '.preallocation.blockDevice = true' "a block device that isolation calls an image → NOT RUN" "contradictory input: preallocation says block device"

# Every rule, alone.
fe identity '.filesystem.uuid = "11111111-2222-3333-4444-555555555555"' "a filesystem UUID other than the record's blocks" "identity: filesystem uuid differ from record fixture-laptop-arm64-reference"
fe identity-src '.filesystem.mountSource = "/dev/sdb"' "a mount source other than the record's blocks" "identity: filesystem mountSource differ"
jq 'del(.filesystem)' "$FIX/qualification/laptop-arm64-reference.json" > "$TMP/rec-nofs.json"
mkdir -p "$TMP/qual-nofs"; cp "$FIX/qualification/"*.json "$FIX/qualification/index.txt" "$TMP/qual-nofs/"; cp "$TMP/rec-nofs.json" "$TMP/qual-nofs/laptop-arm64-reference.json"
with --qualification-dir "$TMP/qual-nofs" > "$TMP/f-nofs"
only "F: a record without filesystem elements is never reused silently" F-filesystem "carries no filesystem elements" "$TMP/f-nofs"
fe mounts '.mount.mountsOfSource = 2' "a source mounted twice blocks" "dedicated mount: /proc/mounts lists 2 mounts"
fe other-data '.mount.otherDataOnMount = true' "other data on the mount blocks" "something other than MinIO data is on the mount"
fe datadir '.mount.minioDataDirOnMount = false' "MinIO's data directory off the mount blocks" "MinIO data directory is not on the mount"
# both() changes the evidence and the monitor's observation together, so the input stays consistent.
both() {
    jq "$2" "$FSE" > "$TMP/ev-$1.json"; jq "$3" "$FSS" > "$TMP/st-$1.json"
    with --filesystem-evidence "$TMP/ev-$1.json" | sed "s#$FSS#$TMP/st-$1.json#" > "$TMP/f-both-$1"
    only "F: $4" F-filesystem "$5" "$TMP/f-both-$1"
}
both small '.statvfs.blocks = 16777215' '.observation.capacityBytes = 68719472640' "fewer usable blocks than declared C_fs blocks" "capacity: f_blocks × f_frsize 68719472640 < declared C_fs 68719476736"
fe frsize '.declared.blockSizeBytes = 2048' "f_frsize other than declared B blocks" "capacity: f_frsize 4096 ≠ declared B 2048"
both inodes '.statvfs.files = 1000' '.observation.inodesTotal = 1000' "fewer inodes than declared blocks" "inodes: f_files 1000 < declared I_fs 16777216"
both inode-ratio '.declared.inodes = 1000 | .statvfs.files = 1000' '.observation.inodesTotal = 1000' "declared I_fs below C_fs / B blocks" "inodes: declared I_fs 1000 < C_fs / B 16777216"
fe sparse '.preallocation.allocatedBytes = 4096' "a sparse backing image blocks" "preallocation: backing image allocated 4096 ≠ apparent size 68719476736 \(sparse\)"
ev tree-overhead '.preallocation.allocatedBytes = 68719509504 | .preallocation.holeBytes = 0'
case_ "F: a fully allocated image whose extent tree adds blocks (allocated > apparent, 0 FIEMAP holes) passes" 0 '^F-filesystem[[:space:]]+PASS' "$TMP/f-ev-tree-overhead"
fe tree-no-fiemap '.preallocation.allocatedBytes = 68719509504' "without holeBytes, allocated ≠ apparent still blocks (fail closed)" "preallocation: backing image allocated 68719509504 ≠ apparent size 68719476736 \(sparse\)"
fe holes '.preallocation.allocatedBytes = 68719509504 | .preallocation.holeBytes = 4096' "a FIEMAP hole blocks even when the sum exceeds the size" "preallocation: backing image has 4096 B of holes \(FIEMAP\)"
fe under-no-holes '.preallocation.allocatedBytes = 4096 | .preallocation.holeBytes = 0' "allocated below apparent blocks even with holeBytes 0" "preallocation: backing image has 0 B of holes \(FIEMAP\), allocated 4096"
fe holes-fraction '.preallocation.allocatedBytes = 68719509504 | .preallocation.holeBytes = 0.5' "a non-integer holeBytes falls back to the exact rule" "preallocation: backing image allocated 68719509504 ≠ apparent size"
fe thin '.preallocation.thinPool = true' "a thin pool blocks" "preallocation: the filesystem is on a thin pool"
fe fstrim '.preallocation.fstrimExcluded = false' "no fstrim exclusion blocks" "no fstrim exclusion is recorded"
fe isolation '.isolation.hostFreeAtCreationBytes = 1' "a host filesystem that could not hold the image blocks" "isolation: the host filesystem had 1 free at creation"
fs used '.observation.usedBytes = 3000000000' "used above Φ + H_F + M blocks, with the figures" "headroom: used 3000000000 > Φ 1414816089 \+ H_F 506200576 \+ M 268435456"
fs avail '.observation.availBytes = 1' "avail below R_ops blocks" "headroom: avail 1 < R_ops 2147483648"
fs no-obs '.observation = null' "no monitor observation blocks" "headroom: no monitor observation exists"
fs debt '.footprint.debtBytes = 9000000000 | .footprint.debtObjects = 1 | .observation.usedBytes = 9000000000' "D_est above D_budget blocks" "deletion debt: D_est 9085184938 > D_budget 8589934592"
fs untrusted '.trust.trustedEpoch = 2' "untrusted counts block" "trusted counts: trusted_epoch 2 ≠ distrust_epoch 3"
fs distrust '.trust.distrustedSeq = 120' "an observation that began before the last distrust event blocks" "base case: the newest observation began at seq 120, not after the last distrust event \(seq 120\)"
# The base case comes from what the database recorded (V10), never from an asserted time.
fs no-mark '.trust.trustedSeq = null' "counts never marked by the verifying function block" "base case: the counts carry no verified trust mark"
fs no-sweep '.sweep = null' "no completed sweep blocks" "base case: no full orphan sweep has completed"
fs sweep-before-trust '.sweep.startedSeq = 105' "a sweep that began at or before the trust mark's order blocks" "base case: the newest complete sweep began at seq 105, not after trust was marked \(seq 105\)"
fs sweep-before-lower '.lowerSeq = 115' "a sweep that began at or before the last lower-capability activity blocks (a durable order, reaping included)" "not after the last activity of a node below containment level 1 \(seq 115\)"
fs no-lower-seq 'del(.lowerSeq)' "a state without the containment watermark → NOT RUN" "state.lowerSeq missing"
fs listed-over '.sweep.physicalListedBytes = 1073741825' "a sweep listing more than it covered blocks - covered, not covered + H" "base case: the newest complete sweep listed 1073741825 bytes, more than the 1073741824 covered"
fs lower-live '.uncleanLowerNodes = ["ing-old"]' "a node below containment level 1 not shut down cleanly or reaped blocks" "mixed versions: node\(s\) below containment level 1 neither shut down cleanly nor reaped: ing-old"
fs sweep-shape '.sweep.startedAt = "yesterday"' "an unreadable sweep start → NOT RUN" "state.sweep.startedAt is not an ISO-8601 UTC instant"
fs inode-headroom '.observation.inodesUsed = 16000000' "fewer free inodes than free blocks blocks" "starting headroom: 777216 free inodes < avail / B = 15728640"
fe meta-inodes '.minio.metadataInodes = 70000' "metadata inodes over their budget block" "MinIO metadata: 70000 metadata inodes > the inode budget 65536"
fe meta-budget '.declared.metadataBudgetInodes = 10000 | .minio.metadataInodes = 5000' "an inode budget below the E7 measurement blocks" "MinIO metadata: the inode budget 10000 is below the E7 measurement 20000"
fe no-meta-inodes 'del(.minio.metadataInodes)' "evidence without the metadata inode count → NOT RUN" "evidence.minio.metadataInodes missing"
qrec() { jq "$2" "$FIX/qualification/laptop-arm64-reference.json" > "$TMP/rec-$1.json"; mkdir -p "$TMP/qual-$1"
    cp "$FIX/qualification/"*.json "$FIX/qualification/index.txt" "$TMP/qual-$1/"; cp "$TMP/rec-$1.json" "$TMP/qual-$1/laptop-arm64-reference.json"; }
qrec no-e7 'del(.filesystem.metadataInodesMeasured)'
with --qualification-dir "$TMP/qual-no-e7" > "$TMP/f-no-e7"
only "F: a record without the E7 metadata inode measurement blocks" F-filesystem "carries no E7 metadata inode measurement" "$TMP/f-no-e7"
# One counting definition (amendment note, metadata inodes): .minio.sys only, on both sides.
fe meta-scope-all '.minio.metadataInodesScope = "minio-sys+bucket-directories"' "evidence counting bucket directories as metadata inodes blocks" "MinIO metadata: the evidence counts metadataInodes over scope minio-sys\\+bucket-directories, not minio-sys"
fe no-meta-scope 'del(.minio.metadataInodesScope)' "evidence that does not state its metadata inode scope → NOT RUN" "evidence.minio.metadataInodesScope missing"
qrec no-scope 'del(.filesystem.metadataInodesScope)'
with --qualification-dir "$TMP/qual-no-scope" > "$TMP/f-no-scope"
only "F: a record whose E7 figure does not state scope minio-sys blocks" F-filesystem "record fixture-laptop-arm64-reference does not state its E7 metadataInodesMeasured as scope minio-sys" "$TMP/f-no-scope"
# One counting definition for metadata BYTES (amendment note): .minio.sys without .trash + unattributed allocations.
fe bytes-scope-all '.minio.metadataBytesScope = "minio-sys+bucket-directories"' "evidence counting every bucket directory block as metadata bytes blocks" "MinIO metadata: the evidence counts bucketDirectoryBytes over scope minio-sys\\+bucket-directories, not minio-sys\\+unattributed"
fe no-bytes-scope 'del(.minio.metadataBytesScope)' "evidence that does not state its metadata bytes scope → NOT RUN" "evidence.minio.metadataBytesScope missing"
qrec no-bytes-scope 'del(.filesystem.metadataBytesScope)'
with --qualification-dir "$TMP/qual-no-bytes-scope" > "$TMP/f-no-bytes-scope"
only "F: a record whose metadata bytes figure does not state scope minio-sys+unattributed blocks" F-filesystem "record fixture-laptop-arm64-reference does not state its metadataBytesMeasured as scope minio-sys\\+unattributed" "$TMP/f-no-bytes-scope"
qrec no-bytes 'del(.filesystem.metadataBytesMeasured)'
with --qualification-dir "$TMP/qual-no-bytes" > "$TMP/f-no-bytes"
only "F: a record without the E2/E7 metadata bytes measurement blocks" F-filesystem "carries no E2/E7 metadata bytes measurement" "$TMP/f-no-bytes"
# The inode scope without .trash only with the armed inode-headroom latch; a missing bucket never passes.
# The inode scope without .trash only when PROVEN (record: PASS E10 drill of the evidence's fs-monitor build); one
# scope on both sides; a missing bucket never passes.
FSM="$(printf 'f%.0s' $(seq 1 64))"
qrec excl '.filesystem.metadataInodesScope = "minio-sys-excl-trash" | .filesystem.inodeHeadroomLatchDrill = {"result": "PASS", "fsMonitorSha256": "'"$FSM"'"}'
xev() {  # xev <name> <jq over the proven evidence> <case> <regex|PASS>
    jq '.minio.metadataInodesScope = "minio-sys-excl-trash" | .monitor = {"inodeHeadroomLatchArmed": true, "fsMonitorSha256": "'"$FSM"'"} | '"$2" "$FSE" > "$TMP/ev-x-$1.json"
    with --filesystem-evidence "$TMP/ev-x-$1.json" | sed "s#$FIX/qualification\$#$TMP/qual-$5#" > "$TMP/f-x-$1"
    if [ "$4" = PASS ]; then case_ "F: $3" 0 '^F-filesystem[[:space:]]+PASS' "$TMP/f-x-$1"; else only "F: $3" F-filesystem "$4" "$TMP/f-x-$1"; fi
}
xev proven '.' "minio-sys-excl-trash with a PASS E10 drill of the evidence's fs-monitor build passes" PASS excl
xev sha-mismatch '.monitor.fsMonitorSha256 = "'"$(printf 'e%.0s' $(seq 1 64))"'"' "minio-sys-excl-trash with another fs-monitor build than E10 drilled blocks" "no PASS E10 inode-headroom latch drill of the fs-monitor build the evidence names" excl
xev no-sha 'del(.monitor.fsMonitorSha256)' "minio-sys-excl-trash with no fs-monitor build named blocks" "monitor.fsMonitorSha256 null\): only minio-sys is acceptable" excl
xev unarmed 'del(.monitor.inodeHeadroomLatchArmed)' "minio-sys-excl-trash proven but not stated armed blocks" "monitor.inodeHeadroomLatchArmed is not true" excl
qrec excl-nodrill '.filesystem.metadataInodesScope = "minio-sys-excl-trash"'
xev no-drill '.' "minio-sys-excl-trash with a record carrying no E10 drill blocks (a declared flag is not proof)" "record carries no PASS E10 inode-headroom latch drill" excl-nodrill
qrec excl-fail '.filesystem.metadataInodesScope = "minio-sys-excl-trash" | .filesystem.inodeHeadroomLatchDrill = {"result": "FAIL", "fsMonitorSha256": "'"$FSM"'"}'
xev drill-fail '.' "minio-sys-excl-trash with a failed E10 drill blocks" "record carries no PASS E10 inode-headroom latch drill" excl-fail
fe scope-vs-record '.minio.metadataInodesScope = "minio-sys-excl-trash" | .monitor = {"inodeHeadroomLatchArmed": true, "fsMonitorSha256": "'"$FSM"'"}' "an evidence inode scope that differs from the record's blocks" "but record fixture-laptop-arm64-reference measured scope minio-sys: one scope on both sides"
with --qualification-dir "$TMP/qual-excl" > "$TMP/f-rec-excl-ev-sys"
only "F: a record measured without .trash against evidence counting .trash blocks (one scope on both sides)" F-filesystem "counts inodes over scope minio-sys but record fixture-laptop-arm64-reference measured scope minio-sys-excl-trash" "$TMP/f-rec-excl-ev-sys"
fe no-bucket '.minio.bucketPresent = false' "evidence collected with no bucket directory blocks" "MinIO metadata: the evidence was collected with no bucket directory \\(bucketPresent false\\)"
fe no-bucket-field 'del(.minio.bucketPresent)' "evidence that does not state bucketPresent → NOT RUN" "evidence.minio.bucketPresent missing"
# Production M = 1 GiB (owner decision 2026-11-05): a production record whose measured metadata exceeds the staging
# 256 MiB passes against M = 1 GiB, and is blocked against 256 MiB. Gate F compares; it pins nothing.
qrec m768 '.filesystem.metadataBytesMeasured = 805306368'
jq '.declared.metadataBudgetBytes = 1073741824' "$FSE" > "$TMP/ev-m1g.json"
with --qualification-dir "$TMP/qual-m768" | sed "s#$FSE#$TMP/ev-m1g.json#" > "$TMP/f-m768-1g"
case_ "F: a record measuring 768 MiB of metadata passes against the production M of 1 GiB" 0 '^F-filesystem[[:space:]]+PASS' "$TMP/f-m768-1g"
with --qualification-dir "$TMP/qual-m768" > "$TMP/f-m768-256"
only "F: the same record against M = 256 MiB blocks" F-filesystem "MinIO metadata: M 268435456 is below the E2/E7 measurement 805306368" "$TMP/f-m768-256"
fe bytes-budget '.declared.metadataBudgetBytes = 120000000' "an M below the record's measured metadata bytes blocks" "MinIO metadata: M 120000000 is below the E2/E7 measurement 125829120"
qrec no-e3 'del(.filesystem.experiments.E3)'
with --qualification-dir "$TMP/qual-no-e3" > "$TMP/f-no-e3"
only "F: ALL needs every experiment E1–E11 recorded PASS" F-filesystem "experiments: record fixture-laptop-arm64-reference does not record PASS for E3" "$TMP/f-no-e3"

# TENANT_LIMITS preflight: isolation rows only, and its own experiment set.
good | sed 's/^ALL$/TENANT_LIMITS/' | awk 'skip { skip = 0; next } $0 == "--benchmark-evidence" { skip = 1; next } { print }' > "$TMP/tl-base"
sed "s#$FIX/qualification\$#$TMP/qual-no-e3#" "$TMP/tl-base" > "$TMP/tl-e3"
case_ "F: TENANT_LIMITS does not need E1–E7 (an E3 gap passes the preflight)" 0 '^F-filesystem[[:space:]]+PASS[[:space:]]+TENANT_LIMITS isolation preflight held' "$TMP/tl-e3"
qrec no-e9 '.filesystem.experiments.E9 = "FAIL"'
sed "s#$FIX/qualification\$#$TMP/qual-no-e9#" "$TMP/tl-base" > "$TMP/tl-e9"
case_ "F: TENANT_LIMITS needs the STORAGE_FULL drill (E9) recorded PASS" 1 '^F-filesystem[[:space:]]+BLOCKED[[:space:]]+TENANT_LIMITS isolation preflight: experiments: .* E9 .*containment theorem does NOT hold' "$TMP/tl-e9"
jq '.preallocation.allocatedBytes = 4096' "$FSE" > "$TMP/ev-tl-sparse.json"; sed "s#$FSE#$TMP/ev-tl-sparse.json#" "$TMP/tl-base" > "$TMP/tl-sparse"
case_ "F: TENANT_LIMITS refuses a sparse backing image" 1 '^F-filesystem[[:space:]]+BLOCKED[[:space:]]+TENANT_LIMITS isolation preflight: preallocation: backing image allocated 4096' "$TMP/tl-sparse"
jq '.roleViolations = ["testinbox_app: owns message"] | .trust.trustedEpoch = 1 | .sweep = null' "$FSS" > "$TMP/st-tl-staging.json"; sed "s#$FSS#$TMP/st-tl-staging.json#" "$TMP/tl-base" > "$TMP/tl-staging"
case_ "F: TENANT_LIMITS ignores the containment-only rows (staging's owner connection, untrusted counts, no sweep)" 0 '^F-filesystem[[:space:]]+PASS[[:space:]]+TENANT_LIMITS isolation preflight held' "$TMP/tl-staging"
jq '.globalBase.rowBytes = 5' "$FSS" > "$TMP/st-tl-global.json"; sed "s#$FSS#$TMP/st-tl-global.json#" "$TMP/tl-base" > "$TMP/tl-global"
case_ "F: TENANT_LIMITS ignores the global base row (no global potential is enforced)" 0 '^F-filesystem[[:space:]]+PASS[[:space:]]+TENANT_LIMITS isolation preflight held' "$TMP/tl-global"
jq '.observation = null' "$FSS" > "$TMP/st-tl-noobs.json"; sed "s#$FSS#$TMP/st-tl-noobs.json#" "$TMP/tl-base" > "$TMP/tl-noobs"
jq '.uncleanLowerNodes = ["ing-old"]' "$FSS" > "$TMP/st-tl-lower.json"; sed "s#$FSS#$TMP/st-tl-lower.json#" "$TMP/tl-base" > "$TMP/tl-lower"
case_ "F: TENANT_LIMITS refuses while an artifact below the containment level may run (no STORAGE_FULL/containment code)" 1 '^F-filesystem[[:space:]]+BLOCKED[[:space:]]+TENANT_LIMITS isolation preflight: mixed versions: node\(s\) below containment level 1' "$TMP/tl-lower"
case_ "F: TENANT_LIMITS needs a monitor observation for its starting headroom" 1 '^F-filesystem[[:space:]]+BLOCKED[[:space:]]+TENANT_LIMITS isolation preflight: starting headroom: no monitor observation exists' "$TMP/tl-noobs"
with --filesystem-evidence "" | sed 's/^ALL$/TENANT_LIMITS/' > "$TMP/tl-none"
case_ "F: TENANT_LIMITS without filesystem evidence is NOT RUN, and NOT RUN blocks" 1 '^F-filesystem[[:space:]]+NOT RUN[[:space:]]+no --filesystem-evidence' "$TMP/tl-none"
grep -v 'TI-STORAGE-006E' "$REPO_OK/deploy/rollback-floors.txt" > "$TMP/floors-nocontain.txt"
with --floors-file "$TMP/floors-nocontain.txt" > "$TMP/f-nofloor"
only "F: no TI-STORAGE-006E containment floor declared blocks (mixed versions)" F-filesystem "no TI-STORAGE-006E containment rollback floor is declared" "$TMP/f-nofloor"
fe procs '.declared.procs = 0' "procs below the live ingestion nodes → NOT RUN (procs must be positive)" "declared.procs is not a positive integer"
st procs-all '.liveNodes = ["api-1", "ing-1", "ing-2"]'
good | awk 'skip { skip = 0; next } $0 == "--expected-ingestion-nodes" { skip = 1; next } { print }' | sed "s#$FSS#$TMP/st-procs-all.json#" | \
    sed "s#$FSE#$TMP/ev-procs1.json#" > "$TMP/f-procs"; jq '.declared.procs = 2' "$FSE" > "$TMP/ev-procs1.json"
run_args "$TMP/f-procs"
printf '%s\n' "$OUT" | grep -qE '^F-filesystem[[:space:]]+BLOCKED[[:space:]].*procs: declared procs 2 < 3 live node\(s\)' \
    && ok "F: without a declared ingestion list, every live node counts against procs" || bad "F: procs was not checked against the live nodes" "$OUT"
fs writer '.observation.writtenBy = "testinbox_app"' "an observation the monitor did not write blocks" "observation source: newest observation written by testinbox_app, not the declared monitor testinbox_monitor"
fe monitor-app '.declared.applicationRoles = ["testinbox_app", "testinbox_monitor"]' "a monitor role that is also an application role blocks" "observation writers: the monitor role is an application role"
fs inserters '.observationInserters = ["testinbox_app", "testinbox_monitor"]' "another role holding INSERT on observations blocks" "INSERT on storage_filesystem_observation is held by \[testinbox_app, testinbox_monitor\]"
fs executors '.beginObservationExecutors = []' "nobody holding EXECUTE on storage_begin_observation() blocks" "EXECUTE on storage_begin_observation\(\) is held by \[\]"
fs privileges '.roleViolations = ["testinbox_app: owns storage_deletion_debt"]' "an application role owning a V8 table blocks - staging fails by design" "privileges \(roles checked: declared \+ connected \[testinbox_app\]\): 1 violation\(s\), first testinbox_app: owns storage_deletion_debt .*fails by design"
fs cache '.sequenceCacheSize = 20' "a cached ordering sequence blocks" "ordering: storage_debt_order_seq has CACHE 20, not 1"
fs replica '.inRecovery = true' "a gate connected to a replica blocks" "ordering: a reader or writer is not on the primary"
fe primary '.database.allConnectionsToPrimary = false' "a reader or writer off the primary blocks" "ordering: a reader or writer is not on the primary"
fe metadata '.minio.bucketDirectoryBytes = 200000000' "MinIO metadata above M blocks" "MinIO metadata: .minio.sys 104857600 \+ unattributed 200000000 > M 268435456"
fe qual-invalid '.qualificationValid = 0' "an Ops qualification-check reporting invalid blocks" "qualification identity: Ops qualification-check reports valid=0"
fs obs-age '.observation.observedAt = "2026-10-06T23:40:00Z"' "an observation older than A_obs blocks" "observation liveness: the newest observation is 1500 s old, more than A_obs 900 s"
fs obs-future '.observation.observedAt = "2026-10-07T00:10:00Z"' "an observation from the future blocks" "observation liveness: the newest observation is in the future"
fs watermark '.watermark = 121' "an observation below the compaction watermark blocks, as T1 refuses it" "below the compaction watermark 121"
fs overflow '.footprint.liveBytes = 9300000000000000000' "a footprint total beyond 64 bits blocks, as T1 refuses it" "indeterminate: a footprint total does not fit"
fs no-watermark 'del(.watermark)' "a state without the watermark → NOT RUN" "state.watermark missing"
fs global-missing '.globalBase.rowBytes = null | .globalBase.rowObjects = null' "a missing V24 global base row blocks ALL (T1 would sum the population)" "global base: the storage_global_base row is missing"
fs global-drift '.globalBase.rowBytes = 5' "a global base row that differs from the sum of workspace bases blocks ALL" "global base: storage_global_base 5 B / 10000 objects ≠ Σ workspace bases 1073741824 B / 10000 objects"
fs no-global 'del(.globalBase)' "a state without the global base (a pre-V24 schema) → NOT RUN" "state.globalBase.sumBytes missing"
fe age-cap '.declared.observationMaxAgeSeconds = 86400' "an A_obs above an hour → NOT RUN (a large age cannot launder stale evidence)" "observationMaxAgeSeconds is not a whole number of seconds in \(0, 3600\]"
fe age-exp '.declared.observationMaxAgeSeconds = 1e300' "an A_obs bash cannot compare → NOT RUN, never fresh" "observationMaxAgeSeconds is not a whole number"
TESTINBOX_ACTIVATION_DB_URL="postgresql://unused.invalid/x" run_args "$TMP/args-good"
printf '%s\n' "$OUT" | grep -qE '^F-filesystem[[:space:]]+NOT RUN[[:space:]].*--footprint-state and TESTINBOX_ACTIVATION_DB_URL both given' \
    && ok "F: an offline state file is refused while the live database URL is set" || bad "F let an offline file override the live database" "$OUT"
run_args "$TMP/args-good"
printf '%s\n' "$OUT" | grep -qE '^F-filesystem[[:space:]]+PASS[[:space:]]+database state from an OFFLINE --footprint-state file' \
    && ok "F: a PASS on an offline state file says so" || bad "F did not mark the offline source" "$OUT"

# ε and H_F are derived, never read: evidence fields claiming a larger budget or a smaller ε change nothing.
ev launder '.declared.finalizeBudgetBytes = 4000000000 | .declared.fragmentationEpsilon = 0'
jq '.observation.usedBytes = 3000000000' "$FSS" > "$TMP/st-launder.json"
sed "s#$FSS#$TMP/st-launder.json#" "$TMP/f-ev-launder" > "$TMP/f-launder"
only "F: an H_F or ε written into the evidence is ignored; the derived values judge" F-filesystem "headroom: used 3000000000 > Φ 1414816089 \+ H_F 506200576" "$TMP/f-launder"
fe mounts0 '.mount.mountsOfSource = 0' "a source mounted nowhere blocks" "dedicated mount: /proc/mounts lists 0 mounts"
jq '.filesystem.objectOverheadMaxBytes = 28672' "$FIX/qualification/laptop-arm64-reference.json" > "$TMP/rec-omax.json"
mkdir -p "$TMP/qual-omax"; cp "$FIX/qualification/"*.json "$FIX/qualification/index.txt" "$TMP/qual-omax/"; cp "$TMP/rec-omax.json" "$TMP/qual-omax/laptop-arm64-reference.json"
with --qualification-dir "$TMP/qual-omax" > "$TMP/f-omax"
only "F: a declared O_max below the record's qualified maximum blocks" F-filesystem "O_max: declared 24576 < the qualified maximum 28672" "$TMP/f-omax"
jq 'del(.filesystem.objectOverheadMaxBytes)' "$FIX/qualification/laptop-arm64-reference.json" > "$TMP/rec-noomax.json"
mkdir -p "$TMP/qual-noomax"; cp "$FIX/qualification/"*.json "$FIX/qualification/index.txt" "$TMP/qual-noomax/"; cp "$TMP/rec-noomax.json" "$TMP/qual-noomax/laptop-arm64-reference.json"
with --qualification-dir "$TMP/qual-noomax" > "$TMP/f-noomax"
only "F: a record without a qualified O_max blocks" F-filesystem "carries no qualified objectOverheadMaxBytes" "$TMP/f-noomax"

# --environment production: the matched record must be a production-host record.
with --environment production > "$TMP/f-env-prod"
only "F: --environment production with a staging-host record blocks" F-filesystem "identity: record .* has platformClass staging-host, production requires production-host" "$TMP/f-env-prod"
jq '.qualification.platformClass = "production-host"' "$FIX/qualification/laptop-arm64-reference.json" > "$TMP/rec-prodhost.json"
mkdir -p "$TMP/qual-prodhost"; cp "$FIX/qualification/"*.json "$FIX/qualification/index.txt" "$TMP/qual-prodhost/"; cp "$TMP/rec-prodhost.json" "$TMP/qual-prodhost/laptop-arm64-reference.json"
with --qualification-dir "$TMP/qual-prodhost" > "$TMP/f-env-prodhost"
case_ "F: --environment production with a production-host record is READY" 0 '^ACTIVATION READY$' "$TMP/f-env-prodhost" --environment production
with --environment staging > "$TMP/f-env-staging"
case_ "F: --environment staging does not require production-host" 0 '^ACTIVATION READY$' "$TMP/f-env-staging"
printf '%s\n' "$OUT" | grep -q 'E1–E8' && bad "F: a message still names E1–E8" "$OUT" || ok "F: no message names the stale E1–E8 range"

# A declared value is never an observation: raising the declaration alone cannot pass.
fe declared-only '.declared.capacityBytes = 137438953472' "declaring more capacity than statvfs observes blocks" "capacity: f_blocks × f_frsize 68719476736 < declared C_fs 137438953472"

# ---------------------------------------------------------------------------
# Gate G (ADR-037): the cohort gate is `storage-budget check`; this script only
# records its verdict, and says so when it has none.
# ---------------------------------------------------------------------------
with --cohort-check-output "" > "$TMP/g-none"
case_ "G-cohort: under ALL, without the check output the gate is DEFERRED (exit 3), never passing, and names storage-budget check" 3 \
    '^G-cohort[[:space:]]+DEFERRED[[:space:]]+not yet evidenced: the cohort gate is `storage-budget check' "$TMP/g-none" --json "$TMP/g-none.json"
printf '%s\n' "$OUT" | grep -q '^ACTIVATION DEFERRED$' \
    && ok "G-cohort: a deferred gate G makes the overall verdict DEFERRED, never READY" \
    || bad "G-cohort: overall verdict is not ACTIVATION DEFERRED" "$OUT"
jq -e '.verdict == "DEFERRED" and (.cohortGate | startswith("storage-budget check"))' "$TMP/g-none.json" >/dev/null 2>&1 \
    && ok "G-cohort: the evidence JSON records DEFERRED and states the cohort gate" \
    || bad "G-cohort: evidence JSON verdict/cohortGate wrong" "$(cat "$TMP/g-none.json" 2>/dev/null)"
# A deferred G never hides another failure: with gate A also failing the run is BLOCKED.
with --cohort-check-output "" | sed "s#$FIX/sessions/healthy.tsv#$FIX/sessions/empty-name.tsv#" > "$TMP/g-none-blocked"
case_ "G-cohort: DEFERRED plus another failing gate is ACTIVATION BLOCKED (exit 1)" 1 '^ACTIVATION BLOCKED$' "$TMP/g-none-blocked"
printf 'cohort storage check: PASS; admissions unchanged\n' > "$TMP/check-no-digest.txt"
with --cohort-check-output "$TMP/check-no-digest.txt" > "$TMP/g-nodigest"
only "G-cohort: a PASS without the reviewed-file digest cannot be bound and blocks" G-cohort "names no reviewed-file digest" "$TMP/g-nodigest"
with --cohort-pool-file "" > "$TMP/g-nopool"
only "G-cohort: a PASS without --cohort-pool-file cannot be bound and blocks" G-cohort "no --cohort-pool-file" "$TMP/g-nopool"
{ cat "$FIX/cohort/pool.json"; printf ' '; } > "$TMP/other-pool.json"
with --cohort-pool-file "$TMP/other-pool.json" > "$TMP/g-otherpool"
only "G-cohort: a PASS for a different pool file blocks, naming both digests" G-cohort "ran against a different file: printed sha256 [0-9a-f]{64}, other-pool.json is [0-9a-f]{64}" "$TMP/g-otherpool"
with --cohort-check-output "$FIX/cohort/check-not-ready.txt" > "$TMP/g-fail"
only "G-cohort: a failed storage-budget check blocks, naming its error" G-cohort "did not report PASS; error '.*IllegalStateException: cohort storage is not ready'" "$TMP/g-fail"
with --cohort-check-output "$FIX/cohort/reserve-pass.txt" > "$TMP/g-reserve"
only "G-cohort: a reserve PASS is not a check PASS" G-cohort "did not report PASS.*cohort storage reserve: PASS" "$TMP/g-reserve"
with --cohort-check-output "$FIX/cohort/pass-then-failure.txt" > "$TMP/g-later"
only "G-cohort: a PASS followed by a failure blocks (the last line decides)" G-cohort "operator file differs from installed pool" "$TMP/g-later"
with --cohort-check-output "$FIX/cohort/empty.txt" > "$TMP/g-empty"
only "G-cohort: an empty output blocks" G-cohort "output is empty" "$TMP/g-empty"
with --mode TENANT_LIMITS > "$TMP/g-tl"
run_args "$TMP/g-tl"
printf '%s\n' "$OUT" | grep -qE '^G-cohort[[:space:]]+NOT REQUIRED[[:space:]]+NOT REQUIRED FOR TENANT_LIMITS' \
    && ok "G-cohort: TENANT_LIMITS has no cohort pool, so the gate is NOT REQUIRED there even with an output" \
    || bad "G-cohort: TENANT_LIMITS should not evaluate the cohort gate" "$OUT"

# ---------------------------------------------------------------------------
# Usage.
# ---------------------------------------------------------------------------
: > "$TMP/empty-args"
case_ "usage: --mode is required (exit 2)" 2 'mode TENANT_LIMITS\|ALL is required' "$TMP/empty-args"
case_ "usage: an unknown mode is a usage error" 2 'must be TENANT_LIMITS or ALL' "$TMP/empty-args" --mode ON
case_ "usage: an unknown argument is a usage error" 2 'unknown argument' "$TMP/empty-args" --mode ALL --bogus
case_ "usage: a missing input file is a usage error, not a verdict" 2 'input file not found' "$TMP/empty-args" --mode ALL --sessions-file "$TMP/does-not-exist.tsv"
case_ "usage: a missing cohort check output is a usage error, not a verdict" 2 'input file not found' "$TMP/empty-args" --mode ALL --cohort-check-output "$TMP/does-not-exist.txt"
for env in Production PRODUCTION prod "production " " production" staging-host ""; do
    [ -n "$env" ] || continue
    case_ "usage: --environment '$env' is not an allowed value (exact lowercase production|staging|lab)" 2 'must be exactly production, staging or lab' "$TMP/empty-args" --mode ALL --environment "$env"
done
case_ "usage: an empty --environment value is a usage error" 2 '' "$TMP/empty-args" --mode ALL --environment ""
case_ "usage: a missing cohort pool file is a usage error, not a verdict" 2 'input file not found' "$TMP/empty-args" --mode ALL --cohort-pool-file "$TMP/does-not-exist.json"

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
