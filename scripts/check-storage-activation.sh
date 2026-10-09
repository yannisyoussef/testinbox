#!/usr/bin/env bash
# ADR-035 §14 phase 3: the storage activation barrier, evaluated as evidence.
#
# WHY THIS EXISTS: `testinbox.storage.enforcement` is configuration on the same
# digests, so nothing in the build pipeline can know whether turning it on is
# safe. ADR-035 §14 makes activation a PROVEN state: (a) every database session
# carries the storage-v1 capability and the node inventory is exact, (b) a
# full orphan sweep has completed and physical bytes are within the covered
# total plus the finalize budget H, (c) every node's clock offset is bounded,
# (d) the §11 benchmark has passed before the GLOBAL ceiling, (e) the ADR-035
# rollback floors are on master and under the running artifact, and (§9a) the
# deployed MinIO combination has a committed qualification record. This script
# evaluates each of those from inputs Ops hand it and writes the verdict down.
#
# What it is NOT: it is not a deployment, not a health check, and a green
# Staging workflow is not evidence for any gate here (the GitHub handoff proves
# that Ops ACCEPTED a request, not the deployed state). Every gate that has no
# input reports NOT RUN, and NOT RUN blocks: an unevaluated gate is not a
# passed one.
#
# Usage:
#   check-storage-activation.sh --mode TENANT_LIMITS|ALL [inputs...] [--json out.json]
#
# Inputs (all optional; a gate with no input is NOT RUN):
#   --api-metrics <url|file>           repeatable; /actuator/prometheus of an API node
#   --ingestion-metrics <url|file>     repeatable; /actuator/prometheus of an ingestion node
#   --expected-api-nodes a,b           node ids that MUST be in storage_node
#   --expected-ingestion-nodes c,d
#   --sessions-file <tsv>              application_name<TAB>usename, replaces the pg_stat_activity query
#   --application-role <role>      evaluate only sessions of this database role (ADR-035 §14 (a) scope; default: every role)
#   --nodes-file <tsv>                 node_id<TAB>capability<TAB>heartbeat_age_seconds<TAB>clean_shutdown(t|f)
#   --qualification-dir <dir>          default backend/storage/src/main/resources/adr035-qualification
#   --backend-identity <json>          the Ops-declared backend identity (non-secret)
#   --qualification-valid-metric 0|1   the Ops qualification-check's testinbox_storage_qualification_valid
#   --benchmark-evidence <json>        the §11 benchmark result (docs/dev/storage-benchmark.md)
#   --repo <dir>                       the checkout whose history evaluates floors (default: this repository)
#   --floors-file <file>               default <repo>/deploy/rollback-floors.txt
#   --fetch                            `git fetch origin master` before evaluating gate E
#   --max-clock-offset-seconds <n>     ε_max, default 30
#   --filesystem-evidence <json>       gate F: the Ops filesystem evidence (docs/dev/filesystem-evidence.md)
#   --footprint-state <json>           gate F: the database state, for an offline evaluation; replaces the query
#   --json <file>                      write the evidence record
# Env:
#   TESTINBOX_ACTIVATION_DB_URL        psql conninfo/URL; replaces --sessions-file / --nodes-file. With it set,
#                                      --footprint-state is refused (gate F NOT RUN): the live database wins.
#                                      Only ever read from the environment so it never shows in `ps`.
#   TESTINBOX_ACTIVATION_EVALUATED_AT  overrides the evaluatedAt timestamp (tests)
# Exit: 0 ACTIVATION READY, 1 ACTIVATION BLOCKED, 2 usage
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TAB="$(printf '\t')"
US="$(printf '\037')"   # TSV rows are read with this separator: empty fields must survive, and tab is collapsing whitespace in IFS

MODE=""
API_SOURCES=""
ING_SOURCES=""
EXPECTED_API=""
EXPECTED_ING=""
SESSIONS_FILE=""
# ADR-035 §14 (a) allowlists the sessions of the APPLICATION database role; other roles
# (a backup role, an exporter, a DBA) are out of scope. Unset = every role is evaluated (stricter).
APP_ROLE=""
NODES_FILE=""
QUAL_DIR="$SCRIPT_DIR/../backend/storage/src/main/resources/adr035-qualification"
IDENTITY=""
QUAL_METRIC=""
BENCH=""
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
FLOORS_FILE=""
FETCH=false
FS_EVIDENCE=""
FOOTPRINT_STATE=""
JSON_OUT=""
MAX_OFFSET=30
HEARTBEAT_MAX_AGE=300
# ADR-035 §11 constants are PINNED here and never read from the evidence: an
# evidence file is data a harness produced, and a harness run with relaxed
# inputs must not be able to relax the gate (docs/dev/storage-benchmark.md).
EXPECTED_RATE="260"
SUSTAIN_TOLERANCE="0.995"
T1_P99_MS="50"
RETENTION_RATIO="2"
LOCK_TIMEOUT_RATE="0.001"
MIN_WORKSPACES="10000"
MIN_RESERVATION_BACKLOG="1000"
REQUIRED_CONCURRENCY="1,10,25,50,100"
# ADR-035 §11 as amended 2026-10-08: concurrency 1 is the uncontended baseline, measured and
# required for coverage, but the sustained-load and T1 criteria are read at these concurrencies only;
# the retention ratio (relative to a reference at the same offered load) and the integrity criteria
# are read at every scenario.
LOAD_GATED_CONCURRENCY="10,25,50,100"

usage() {
    sed -n '/^# Usage:/,/^# Exit:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

need_value() { [ $# -ge 2 ] && [ -n "$2" ] || { echo "error: $1 requires a value" >&2; usage; }; }

while [ $# -gt 0 ]; do
    case "$1" in
        --mode) need_value "$@"; MODE="$2"; shift 2 ;;
        --api-metrics) need_value "$@"; API_SOURCES="$API_SOURCES$2"$'\n'; shift 2 ;;
        --ingestion-metrics) need_value "$@"; ING_SOURCES="$ING_SOURCES$2"$'\n'; shift 2 ;;
        --expected-api-nodes) need_value "$@"; EXPECTED_API="$2"; shift 2 ;;
        --expected-ingestion-nodes) need_value "$@"; EXPECTED_ING="$2"; shift 2 ;;
        --sessions-file) need_value "$@"; SESSIONS_FILE="$2"; shift 2 ;;
        --application-role) need_value "$@"; APP_ROLE="$2"; shift 2 ;;
        --nodes-file) need_value "$@"; NODES_FILE="$2"; shift 2 ;;
        --qualification-dir) need_value "$@"; QUAL_DIR="$2"; shift 2 ;;
        --backend-identity) need_value "$@"; IDENTITY="$2"; shift 2 ;;
        --qualification-valid-metric) need_value "$@"; QUAL_METRIC="$2"; shift 2 ;;
        --benchmark-evidence) need_value "$@"; BENCH="$2"; shift 2 ;;
        --repo) need_value "$@"; REPO="$2"; shift 2 ;;
        --floors-file) need_value "$@"; FLOORS_FILE="$2"; shift 2 ;;
        --fetch) FETCH=true; shift ;;
        --max-clock-offset-seconds) need_value "$@"; MAX_OFFSET="$2"; shift 2 ;;
        --filesystem-evidence) need_value "$@"; FS_EVIDENCE="$2"; shift 2 ;;
        --footprint-state) need_value "$@"; FOOTPRINT_STATE="$2"; shift 2 ;;
        --json) need_value "$@"; JSON_OUT="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "error: unknown argument '$1'" >&2; usage ;;
    esac
done

case "$MODE" in
    TENANT_LIMITS|ALL) ;;
    "") echo "error: --mode TENANT_LIMITS|ALL is required" >&2; usage ;;
    *) echo "error: --mode must be TENANT_LIMITS or ALL, not '$MODE'" >&2; usage ;;
esac
case "$QUAL_METRIC" in ""|0|1) ;; *) echo "error: --qualification-valid-metric must be 0 or 1" >&2; usage ;; esac
case "$MAX_OFFSET" in ''|*[!0-9.]*) echo "error: --max-clock-offset-seconds must be a number" >&2; usage ;; esac
for f in "$SESSIONS_FILE" "$NODES_FILE" "$IDENTITY" "$BENCH" "$FLOORS_FILE" "$FS_EVIDENCE" "$FOOTPRINT_STATE"; do
    [ -z "$f" ] || [ -f "$f" ] || { echo "error: input file not found: $f" >&2; usage; }
done
[ -d "$REPO" ] || { echo "error: --repo $REPO is not a directory" >&2; usage; }
command -v jq >/dev/null 2>&1 || { echo "error: jq is required" >&2; exit 2; }
[ -n "$FLOORS_FILE" ] || FLOORS_FILE="$REPO/deploy/rollback-floors.txt"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------
# Result recording. One line per gate: gate<TAB>verdict<TAB>detail.
# ---------------------------------------------------------------------------
RESULTS=""
record() {
    local gate="$1" verdict="$2" detail
    detail="$(printf '%s' "$3" | tr '\t\n' '  ')"
    RESULTS="$RESULTS$gate$TAB$verdict$TAB$detail"$'\n'
}
# Accumulates "; "-separated failure messages for the gate being evaluated.
PROBLEMS=""
problem() { PROBLEMS="$PROBLEMS${PROBLEMS:+; }$*"; }
reset_problems() { PROBLEMS=""; }

# Never print a URL's userinfo, even though a metrics URL should not carry one.
redact() { printf '%s' "$1" | sed -E 's#//[^/@]*@#//<redacted>@#'; }

# Numeric helpers (Prometheus values may be floats or exponent notation).
num_le() { awk -v a="$1" -v b="$2" 'BEGIN { exit !((a + 0) <= (b + 0)) }'; }
num_gt() { awk -v a="$1" -v b="$2" 'BEGIN { exit !((a + 0) > (b + 0)) }'; }
num_eq() { awk -v a="$1" -v b="$2" 'BEGIN { exit !((a + 0) == (b + 0)) }'; }
num_abs() { awk -v a="$1" 'BEGIN { a += 0; if (a < 0) a = -a; print a }'; }
is_number() { case "$1" in ''|*[!0-9.eE+-]*) return 1 ;; esac; awk -v a="$1" 'BEGIN { exit !(a == a + 0 || a ~ /^[-+]?[0-9.]+([eE][-+]?[0-9]+)?$/) }'; }

# ---------------------------------------------------------------------------
# Metrics sources: each --api-metrics / --ingestion-metrics is a URL (fetched
# once) or a local file. METRICS_INDEX lines: role<TAB>label<TAB>path<TAB>error
# ---------------------------------------------------------------------------
METRICS_INDEX=""
resolve_sources() {
    local role="$1" sources="$2" n=0 src label path err
    while IFS= read -r src; do
        [ -n "$src" ] || continue
        n=$((n + 1))
        label="$(redact "$src")"
        path="$WORK/metrics-$role-$n.prom"
        err=""
        case "$src" in
            http://*|https://*)
                curl -fsS --max-time 20 -o "$path" "$src" 2>/dev/null || err="metrics unreachable at $label"
                ;;
            *)
                if [ -f "$src" ]; then cp "$src" "$path"; else echo "error: metrics file not found: $src" >&2; exit 2; fi
                ;;
        esac
        METRICS_INDEX="$METRICS_INDEX$role$TAB$label$TAB$path$TAB$err"$'\n'
    done <<EOF
$sources
EOF
}
resolve_sources api "$API_SOURCES"
resolve_sources ingestion "$ING_SOURCES"

# metric_value FILE NAME [LABEL_FRAGMENT] → the sample value, or empty when absent.
# Matched by metric NAME and the given label only: lines may carry extra labels.
metric_value() {
    local file="$1" name="$2" label="${3:-}"
    grep -E "^${name}(\{[^}]*\})?[[:space:]]" "$file" 2>/dev/null |
        { if [ -n "$label" ]; then grep -F -- "$label"; else cat; fi; } |
        head -1 | sed -E "s/^${name}(\{[^}]*\})?[[:space:]]+//" | awk '{ print $1 }'
}
build_git_sha() {
    grep -E '^testinbox_build\{' "$1" 2>/dev/null | grep -oE 'git_sha="[0-9a-f]{40}"' | head -1 | cut -d'"' -f2
}
count_lines() { printf '%s' "$1" | grep -c . 2>/dev/null; true; }
count_csv() { [ -n "$1" ] && printf '%s' "$1" | tr ',' '\n' | grep -c . 2>/dev/null; true; }
count_role() { printf '%s' "$METRICS_INDEX" | awk -F'\t' -v r="$1" '$1 == r' | grep -c . 2>/dev/null; true; }
have_metrics() { [ -n "$METRICS_INDEX" ]; }

# ---------------------------------------------------------------------------
# Gate A (a): session allowlist. Every client session of the database is
# testinbox-<service>:<node>:storage-v1, or a named exclusion. Old binaries
# show pgJDBC's default name and the old LISTEN connection is `testinbox-listen`.
# ---------------------------------------------------------------------------
gate_sessions() {
    local gate="A-sessions" src name user allowed=0 excluded=0 skipped=0 violations=""
    reset_problems
    if [ -n "$SESSIONS_FILE" ]; then
        src="$SESSIONS_FILE"
    elif [ -n "${TESTINBOX_ACTIVATION_DB_URL:-}" ]; then
        src="$WORK/sessions.tsv"
        # The checker's own session is tagged ops:% so it is an exclusion, not a violation.
        if ! PGAPPNAME="ops:storage-activation-check" psql "$TESTINBOX_ACTIVATION_DB_URL" -X -At -F "$TAB" -v ON_ERROR_STOP=1 \
            -c "SELECT application_name, usename FROM pg_stat_activity WHERE datname = current_database() AND backend_type = 'client backend'" \
            >"$src" 2>"$WORK/psql.err"; then
            record "$gate" BLOCKED "pg_stat_activity query failed (psql exit $?); the database could not be observed"
            return
        fi
    else
        record "$gate" NOT_RUN "no --sessions-file and TESTINBOX_ACTIVATION_DB_URL is unset"
        return
    fi
    while IFS="$US" read -r name user; do
        case "$name" in \#*) continue ;; esac
        [ -n "$name$user" ] || continue
        if [ -n "$APP_ROLE" ] && [ "$user" != "$APP_ROLE" ]; then
            skipped=$((skipped + 1))
            continue
        fi
        if [[ "$name" =~ ^testinbox-[^:]+:[^:]*:storage-v1$ ]]; then
            allowed=$((allowed + 1))
        elif [[ "$name" =~ ^testinbox-migrator: ]] || [[ "$name" =~ ^ops: ]]; then
            excluded=$((excluded + 1))
        else
            violations="$violations${violations:+, }application_name='$name' (role '${user:-?}')"
        fi
    done <<EOF
$(tr '\t' "$US" < "$src")
EOF
    [ -n "$violations" ] && problem "sessions outside the storage-v1 allowlist: $violations"
    [ "$allowed" -gt 0 ] || problem "no storage-v1 session observed at all; the ADR-035 binaries are not connected"
    if [ -n "$PROBLEMS" ]; then
        record "$gate" BLOCKED "$PROBLEMS"
    else
        if [ -n "$APP_ROLE" ]; then
            record "$gate" PASS "$allowed storage-v1 session(s) of role '$APP_ROLE', $excluded excluded (migrator/ops), 0 violations; $skipped session(s) of other roles not evaluated"
        else
            record "$gate" PASS "$allowed storage-v1 session(s), $excluded excluded (migrator/ops), 0 violations (every role evaluated; pass --application-role for the §14 (a) scope)"
        fi
    fi
}

# ---------------------------------------------------------------------------
# Gate A (a), positive inventory: the declared node set must EXACTLY equal the
# storage_node rows heartbeating storage-v1 within 5 minutes and not cleanly
# shut down. Each way of disagreeing is its own message.
# ---------------------------------------------------------------------------
gate_inventory() {
    local gate="A-inventory" src declared node cap age clean present qualified n
    reset_problems
    if [ -n "$NODES_FILE" ]; then
        src="$NODES_FILE"
    elif [ -n "${TESTINBOX_ACTIVATION_DB_URL:-}" ]; then
        src="$WORK/nodes.tsv"
        if ! PGAPPNAME="ops:storage-activation-check" psql "$TESTINBOX_ACTIVATION_DB_URL" -X -At -F "$TAB" -v ON_ERROR_STOP=1 \
            -c "SELECT node_id, capability, EXTRACT(EPOCH FROM (now() - heartbeat_at))::bigint, CASE WHEN clean_shutdown THEN 't' ELSE 'f' END FROM storage_node" \
            >"$src" 2>"$WORK/psql.err"; then
            record "$gate" BLOCKED "storage_node query failed (psql exit $?); the inventory could not be observed"
            return
        fi
    else
        record "$gate" NOT_RUN "no --nodes-file and TESTINBOX_ACTIVATION_DB_URL is unset"
        return
    fi
    declared="$(printf '%s,%s' "$EXPECTED_API" "$EXPECTED_ING" | tr ',' '\n' | grep . | sort -u)"
    if [ -z "$declared" ]; then
        record "$gate" BLOCKED "no nodes declared (--expected-api-nodes / --expected-ingestion-nodes); an inventory cannot be exact against nothing"
        return
    fi
    # Normalise the rows: node<TAB>capability<TAB>age<TAB>clean
    grep -v '^#' "$src" | grep . | tr '\t' "$US" > "$WORK/nodes.clean" 2>/dev/null
    while IFS= read -r node; do
        present="$(awk -F"$US" -v n="$node" '$1 == n' "$WORK/nodes.clean")"
        if [ -z "$present" ]; then
            problem "declared node '$node' has no storage_node row (missing)"
            continue
        fi
        qualified=false
        while IFS="$US" read -r _ cap age clean; do
            [ "$cap" = "storage-v1" ] || { problem "declared node '$node' has capability '${cap:-<empty>}', not storage-v1"; continue; }
            case "$clean" in t|true|T|TRUE) problem "declared node '$node' recorded a clean shutdown; it is not running"; continue ;; esac
            if ! is_number "$age" || num_gt "$age" "$HEARTBEAT_MAX_AGE"; then
                problem "declared node '$node' heartbeat is stale or absent (${age:+$age s old}${age:-no heartbeat recorded}, limit ${HEARTBEAT_MAX_AGE} s)"
                continue
            fi
            qualified=true
        done <<EOF
$present
EOF
        $qualified && continue
    done <<EOF
$declared
EOF
    # Anything qualifying that was not declared is an extra, undeclared node.
    while IFS="$US" read -r node cap age clean; do
        [ -n "$node" ] || continue
        [ "$cap" = "storage-v1" ] || continue
        case "$clean" in t|true|T|TRUE) continue ;; esac
        is_number "$age" && num_le "$age" "$HEARTBEAT_MAX_AGE" || continue
        printf '%s\n' "$declared" | grep -qx -- "$node" || problem "undeclared node '$node' is heartbeating storage-v1 (extra node)"
    done < "$WORK/nodes.clean"
    n="$(count_lines "$declared")"
    if [ -n "$PROBLEMS" ]; then
        record "$gate" BLOCKED "$PROBLEMS"
    else
        record "$gate" PASS "$n declared node(s) all heartbeating storage-v1 within ${HEARTBEAT_MAX_AGE} s; no extra node"
    fi
}

# ---------------------------------------------------------------------------
# Barrier B: physical baseline. On every API node: a full orphan sweep has
# COMPLETED since this process started, and physical_listed ≤ covered + H.
# ---------------------------------------------------------------------------
gate_physical() {
    local gate="B-physical-baseline" role label path err sweep start physical committed reserved budget bound checked=0
    reset_problems
    if [ "$(count_role api)" -eq 0 ]; then
        record "$gate" NOT_RUN "no --api-metrics supplied"
        return
    fi
    while IFS="$TAB" read -r role label path err; do
        [ "$role" = api ] || continue
        [ -z "$err" ] || { problem "$err"; continue; }
        checked=$((checked + 1))
        sweep="$(metric_value "$path" testinbox_storage_orphan_sweep_completed_at_seconds)"
        start="$(metric_value "$path" process_start_time_seconds)"
        physical="$(metric_value "$path" testinbox_storage_physical_listed_bytes)"
        committed="$(metric_value "$path" testinbox_storage_covered_bytes 'kind="committed"')"
        reserved="$(metric_value "$path" testinbox_storage_covered_bytes 'kind="reserved"')"
        budget="$(metric_value "$path" testinbox_storage_finalize_budget_bytes)"
        for pair in "sweep:testinbox_storage_orphan_sweep_completed_at_seconds" "start:process_start_time_seconds" \
            "physical:testinbox_storage_physical_listed_bytes" "committed:testinbox_storage_covered_bytes{kind=committed}" \
            "reserved:testinbox_storage_covered_bytes{kind=reserved}" "budget:testinbox_storage_finalize_budget_bytes"; do
            eval "v=\"\$${pair%%:*}\""
            is_number "$v" || problem "$label: metric ${pair#*:} absent"
        done
        is_number "$sweep" && is_number "$start" || continue
        if num_eq "$sweep" 0; then
            problem "$label: no full orphan sweep has completed since process start (orphan_sweep_completed_at_seconds=0)"
        elif ! num_gt "$sweep" "$start"; then
            problem "$label: last completed orphan sweep ($sweep) predates process start ($start)"
        fi
        is_number "$physical" && is_number "$committed" && is_number "$reserved" && is_number "$budget" || continue
        bound="$(awk -v a="$committed" -v b="$reserved" -v h="$budget" 'BEGIN { printf "%.0f", a + b + h }')"
        num_le "$physical" "$bound" ||
            problem "$label: physical_listed_bytes $physical > covered committed $committed + reserved $reserved + finalize budget H $budget = $bound"
    done <<EOF
$METRICS_INDEX
EOF
    if [ -n "$PROBLEMS" ]; then
        record "$gate" BLOCKED "$PROBLEMS"
    else
        record "$gate" PASS "$checked API node(s): full orphan sweep completed since start; physical_listed ≤ covered + H"
    fi
}

# ---------------------------------------------------------------------------
# Barrier C: clock offset within ε_max on EVERY declared node, measured, and no
# node latched or with its breaker open.
# ---------------------------------------------------------------------------
gate_clock() {
    local gate="C-clock-offset" role label path err offset latched breaker checked=0 n_api n_ing m_api m_ing
    reset_problems
    if ! have_metrics; then
        record "$gate" NOT_RUN "no --api-metrics / --ingestion-metrics supplied"
        return
    fi
    n_api="$(count_csv "$EXPECTED_API")"; m_api="$(count_role api)"
    n_ing="$(count_csv "$EXPECTED_ING")"; m_ing="$(count_role ingestion)"
    [ "${n_api:-0}" -le "$m_api" ] || problem "$n_api API node(s) declared but only $m_api API metrics endpoint(s) supplied; every node must be measured"
    [ "${n_ing:-0}" -le "$m_ing" ] || problem "$n_ing ingestion node(s) declared but only $m_ing ingestion metrics endpoint(s) supplied; every node must be measured"
    while IFS="$TAB" read -r role label path err; do
        [ -n "$role" ] || continue
        [ -z "$err" ] || { problem "$err"; continue; }
        checked=$((checked + 1))
        offset="$(metric_value "$path" testinbox_storage_clock_offset_seconds)"
        if ! is_number "$offset"; then
            problem "$label ($role): no offset measured (testinbox_storage_clock_offset_seconds absent)"
        elif ! num_le "$(num_abs "$offset")" "$MAX_OFFSET"; then
            problem "$label ($role): clock offset ${offset} s exceeds ε_max ${MAX_OFFSET} s"
        fi
        latched="$(metric_value "$path" testinbox_storage_admission_latched)"
        breaker="$(metric_value "$path" testinbox_storage_breaker_open)"
        is_number "$latched" && num_eq "$latched" 1 && problem "$label ($role): admission latch is SET (testinbox_storage_admission_latched=1)"
        is_number "$breaker" && num_eq "$breaker" 1 && problem "$label ($role): storage breaker is OPEN (testinbox_storage_breaker_open=1)"
    done <<EOF
$METRICS_INDEX
EOF
    if [ -n "$PROBLEMS" ]; then
        record "$gate" BLOCKED "$PROBLEMS"
    else
        record "$gate" PASS "$checked node(s): |offset| ≤ ${MAX_OFFSET} s, no latch, no open breaker"
    fi
}

# ---------------------------------------------------------------------------
# Gate D: the §11 global-ceiling benchmark. Required only for --mode ALL. The
# file's own verdict is not trusted: the pass criteria are recomputed from the
# scenarios as docs/dev/storage-benchmark.md defines them, and a disagreement
# blocks. A CHOSEN 2× scenario with no matching REFERENCE run cannot be judged
# and blocks; an INCOMPLETE run blocks with its reasons.
# ---------------------------------------------------------------------------
gate_benchmark() {
    local gate="D-benchmark" verdict recomputed bench_sha api_sha warn=""
    reset_problems
    if [ "$MODE" = TENANT_LIMITS ]; then
        record "$gate" NOT_REQUIRED "NOT REQUIRED FOR TENANT_LIMITS: the global ceiling is not enabled in this mode (§14 phase 4)"
        return
    fi
    if [ -z "$BENCH" ]; then
        record "$gate" NOT_RUN "no --benchmark-evidence supplied; the global ceiling needs the §11 staging-host-class benchmark"
        return
    fi
    jq -e . "$BENCH" >/dev/null 2>&1 || { record "$gate" BLOCKED "benchmark evidence is not valid JSON"; return; }
    verdict="$(jq -r '.verdict // "MISSING"' "$BENCH")"
    if [ "$verdict" = INCOMPLETE ]; then
        problem "benchmark verdict is INCOMPLETE: $(jq -r '(.incompleteReasons // []) | join("; ") | if . == "" then "no reason recorded" else . end' "$BENCH")"
    elif [ "$verdict" != PASS ]; then
        problem "benchmark verdict is '$verdict', not PASS"
    fi
    # The §11 criteria, recomputed from the scenarios exactly as
    # docs/dev/storage-benchmark.md defines them (schemaVersion 1).
    recomputed="$(jq -r --argjson expected "$EXPECTED_RATE" --argjson tol "$SUSTAIN_TOLERANCE" --argjson t1max "$T1_P99_MS" \
                     --argjson ratio "$RETENTION_RATIO" --argjson ltr "$LOCK_TIMEOUT_RATE" --argjson minws "$MIN_WORKSPACES" \
                     --argjson minres "$MIN_RESERVATION_BACKLOG" --arg conc "$REQUIRED_CONCURRENCY" --arg gated "$LOAD_GATED_CONCURRENCY" '
        (.scenarios // []) as $s
        | ($conc | split(",") | map(tonumber)) as $required
        | ($gated | split(",") | map(tonumber)) as $loadgated
        | [ $s[] | select(.mode == "CHOSEN" and .offeredRate != null and (.offeredRate | round) == ((2 * $expected) | round)) ] as $chosen2x
        | [ $chosen2x[] | select(.concurrency as $c | $loadgated | index($c) != null) ] as $gated2x
        | def ref($c): [ $s[] | select(.mode == "REFERENCE" and .workspaceCount == $c.workspaceCount and .concurrency == $c.concurrency
                                        and .offeredRate != null and (.offeredRate | round) == ($c.offeredRate | round)) ] | first;
          def dl: (.errors.deadlocks // .deadlocks // 0);
          def misses: (.errors.deadlineMissesFromSlotQueueing // .deadlineMissesFromSlotQueueing // 0);
          def others: (.errors.other // 0);
          def starved: (.retentionStarvedTicks // 0);
        ( if .adrEvidence != true then ["not ADR evidence (the harness departed from §11): " + ((.adrEvidenceReasons // []) | join("; ") | if . == "" then "no reason recorded" else . end)] else [] end )
        + ( if ($s | length) == 0 then ["no scenarios recorded"] else [] end )
        + ( if ($chosen2x | length) == 0 then ["no CHOSEN scenario offered 2× the expected rate (\(2 * $expected)/s)"] else [] end )
        + [ ($required - ([ $chosen2x[] | select(.workspaceCount >= $minws and (.reservationBacklog // 0) >= $minres) | .concurrency ]))[]
              | "coverage: no CHOSEN 2× scenario at concurrency \(.) with ≥ \($minws) workspaces and ≥ \($minres) live reservations" ]
        + [ $gated2x[] | select(.achievedRate == null or .achievedRate / .offeredRate < $tol)
              | "sustained2x: \(.name) achieved \(.achievedRate // "nothing") of \(.offeredRate)/s offered (ratio below \($tol))" ]
        + [ $chosen2x[] | select(.percentiles.t1.p99Ms == null) | "t1P99: \(.name) has no T1 samples" ]
        + [ $gated2x[] | select(.percentiles.t1.p99Ms != null and .percentiles.t1.p99Ms > $t1max)
              | "t1P99: \(.name) T1 p99 \(.percentiles.t1.p99Ms) ms > \($t1max) ms" ]
        + [ $chosen2x[] | . as $c | ref($c) as $r
              | if $r == null then "retentionP99VsReference: no REFERENCE scenario for \($c.name) at (workspaces \($c.workspaceCount), concurrency \($c.concurrency), \($c.offeredRate | round)/s)"
                elif ($r.referenceMode // "") != "NO_LOCK" then "retentionP99VsReference: reference \($r.name) is \($r.referenceMode // "unspecified"), not the no-ceiling mode c (NO_LOCK)"
                elif $c.percentiles.retention.p99Ms == null or $r.percentiles.retention.p99Ms == null then "retentionP99VsReference: \($c.name) or \($r.name) has no retention samples"
                elif $c.percentiles.retention.p99Ms > $ratio * $r.percentiles.retention.p99Ms then "retentionP99VsReference: \($c.name) retention p99 \($c.percentiles.retention.p99Ms) ms > \($ratio)× reference \($r.percentiles.retention.p99Ms) ms (\($r.name))"
                else empty end ]
        + ( ([ $s[] | dl ] | add // 0) as $d | if $d != 0 then ["deadlocks: \($d) across all scenarios"] else [] end )
        + ( ([ $s[] | misses ] | add // 0) as $m | if $m != 0 then ["slotQueueingDeadlineMisses: \($m) across all scenarios"] else [] end )
        + ( ([ $s[] | others ] | add // 0) as $o | if $o != 0 then ["otherErrors: \($o) across all scenarios"] else [] end )
        + ( ([ $s[] | starved ] | add // 0) as $t | if $t != 0 then ["retentionStarvedTicks: \($t) across all scenarios"] else [] end )
        + [ $s[] | select(.mode == "CHOSEN" and (.lockTimeoutRate // 0) >= $ltr)
              | "lockTimeoutRate: \(.name) \(.lockTimeoutRate) ≥ \($ltr) (0.1 %)" ]
        + [ (.criteria // {}) | to_entries[] | select(.value.pass != true)
              | "criterion \(.key) reported pass=\(.value.pass) (observed \(.value.observed), threshold \(.value.threshold))" ]
        | join("; ")' "$BENCH" 2>/dev/null)" || { record "$gate" BLOCKED "benchmark evidence does not have the documented shape (docs/dev/storage-benchmark.md)"; return; }
    if [ -n "$recomputed" ]; then
        if [ "$verdict" = PASS ]; then
            problem "recomputation of the §11 criteria disagrees with the recorded PASS: $recomputed"
        else
            problem "$recomputed"
        fi
    fi
    # Provenance: the benchmark should have been run on an ancestor of what is running.
    bench_sha="$(jq -r '.gitSha // ""' "$BENCH")"
    api_sha="$(printf '%s' "$METRICS_INDEX" | awk -F'\t' '$1 == "api" && $4 == "" { print $3; exit }')"
    [ -n "$api_sha" ] && api_sha="$(build_git_sha "$api_sha")"
    if [ -n "$api_sha" ]; then
        if [[ ! "$bench_sha" =~ ^[0-9a-f]{40}$ ]]; then
            warn="WARNING: benchmark gitSha '$bench_sha' is not a commit SHA"
        elif ! git -C "$REPO" cat-file -e "${bench_sha}^{commit}" 2>/dev/null || ! git -C "$REPO" cat-file -e "${api_sha}^{commit}" 2>/dev/null; then
            warn="WARNING: cannot relate benchmark gitSha $bench_sha to the running API $api_sha (commit not in $REPO)"
        elif ! git -C "$REPO" merge-base --is-ancestor "$bench_sha" "$api_sha" 2>/dev/null; then
            warn="WARNING: benchmark gitSha $bench_sha is not an ancestor of the running API's git_sha $api_sha"
        fi
    fi
    [ -z "$warn" ] || echo "$warn" >&2
    if [ -n "$PROBLEMS" ]; then
        record "$gate" BLOCKED "$PROBLEMS${warn:+; $warn}"
    else
        record "$gate" PASS "recorded PASS and the §11 criteria recompute from the numbers${warn:+; $warn}"
    fi
}

# ---------------------------------------------------------------------------
# Gate E: rollback floors. The ADR-035 floors are parsed from the floors file,
# never hardcoded. Production: every floor is on origin/master AND listed in
# master's floors file (production-handoff.yml reads the floors from master).
# Staging: the running artifacts (git_sha from testinbox_build) contain every floor.
# ---------------------------------------------------------------------------
adr035_floors() {
    # Prints sha<TAB>why for floors whose rationale names ADR-035 / TI-STORAGE.
    [ -f "$FLOORS_FILE" ] || return 0
    while IFS= read -r line; do
        line="${line%%#*}"
        [ -n "${line// /}" ] || continue
        floor="${line%% *}"; why="${line#* }"
        [[ "$floor" =~ ^[0-9a-f]{40}$ ]] || continue
        case "$why" in *ADR-035*|*TI-STORAGE*) printf '%s\t%s\n' "$floor" "$why" ;; esac
    done < "$FLOORS_FILE"
}

gate_floors_production() {
    local gate="E-floor-production" floors master_floors floor why n=0
    reset_problems
    [ -f "$FLOORS_FILE" ] || { record "$gate" BLOCKED "floors file not found at $FLOORS_FILE"; return; }
    floors="$(adr035_floors)"
    [ -n "$floors" ] || { record "$gate" BLOCKED "no ADR-035 / TI-STORAGE rollback floor is declared in $FLOORS_FILE"; return; }
    if $FETCH; then
        git -C "$REPO" fetch -q origin master 2>/dev/null || { record "$gate" BLOCKED "git fetch origin master failed in $REPO"; return; }
    fi
    if ! git -C "$REPO" rev-parse --verify -q origin/master >/dev/null 2>&1; then
        record "$gate" BLOCKED "origin/master is not present in $REPO (fetch full history, or pass --fetch)"
        return
    fi
    master_floors="$(git -C "$REPO" show origin/master:deploy/rollback-floors.txt 2>/dev/null)"
    [ -n "$master_floors" ] || problem "origin/master has no deploy/rollback-floors.txt"
    while IFS="$TAB" read -r floor why; do
        [ -n "$floor" ] || continue
        n=$((n + 1))
        if ! git -C "$REPO" cat-file -e "${floor}^{commit}" 2>/dev/null; then
            problem "floor ${floor:0:12} is not in the checkout $REPO (fetch full history)"
            continue
        fi
        git -C "$REPO" merge-base --is-ancestor "$floor" origin/master 2>/dev/null ||
            problem "floor ${floor:0:12} is not an ancestor of origin/master (master predates it)"
        printf '%s\n' "$master_floors" | grep -q "^$floor " ||
            problem "floor ${floor:0:12} is not listed in origin/master:deploy/rollback-floors.txt"
    done <<EOF
$floors
EOF
    if [ -n "$PROBLEMS" ]; then
        record "$gate" BLOCKED "$PROBLEMS"
    else
        record "$gate" PASS "$n ADR-035 floor(s) on origin/master and listed in master's floors file"
    fi
}

gate_floors_staging() {
    local gate="E-floor-staging" floors role label path err sha floor why checked=0
    reset_problems
    if ! have_metrics; then
        record "$gate" NOT_RUN "no --api-metrics / --ingestion-metrics supplied (the running git_sha comes from testinbox_build)"
        return
    fi
    [ -f "$FLOORS_FILE" ] || { record "$gate" BLOCKED "floors file not found at $FLOORS_FILE"; return; }
    floors="$(adr035_floors)"
    [ -n "$floors" ] || { record "$gate" BLOCKED "no ADR-035 / TI-STORAGE rollback floor is declared in $FLOORS_FILE"; return; }
    while IFS="$TAB" read -r role label path err; do
        [ -n "$role" ] || continue
        [ -z "$err" ] || { problem "$err"; continue; }
        checked=$((checked + 1))
        sha="$(build_git_sha "$path")"
        if [ -z "$sha" ]; then
            problem "$label ($role): testinbox_build carries no 40-hex git_sha"
            continue
        fi
        if ! git -C "$REPO" cat-file -e "${sha}^{commit}" 2>/dev/null; then
            problem "$label ($role): running git_sha ${sha:0:12} is not in the checkout $REPO"
            continue
        fi
        while IFS="$TAB" read -r floor why; do
            [ -n "$floor" ] || continue
            git -C "$REPO" cat-file -e "${floor}^{commit}" 2>/dev/null || { problem "floor ${floor:0:12} is not in the checkout $REPO"; continue; }
            git -C "$REPO" merge-base --is-ancestor "$floor" "$sha" 2>/dev/null ||
                problem "$label ($role): running git_sha ${sha:0:12} predates floor ${floor:0:12}"
        done <<EOF
$floors
EOF
    done <<EOF
$METRICS_INDEX
EOF
    if [ -n "$PROBLEMS" ]; then
        record "$gate" BLOCKED "$PROBLEMS"
    else
        record "$gate" PASS "$checked running artifact(s) contain every ADR-035 floor"
    fi
}

# ---------------------------------------------------------------------------
# Gate Q (§9a): the declared backend identity equals a committed qualification
# record, that record is enablement-eligible, and (when Ops supply it) their
# qualification-check reports valid on the REAL backend.
# ---------------------------------------------------------------------------
QUAL_MATCH=""
gate_qualification() {
    local gate="Q-qualification" index file rec mismatches match="" best="" best_count=-1 count eligible reasons
    reset_problems
    if [ -z "$IDENTITY" ]; then
        record "$gate" NOT_RUN "no --backend-identity supplied"
        return
    fi
    jq -e . "$IDENTITY" >/dev/null 2>&1 || { record "$gate" BLOCKED "backend identity is not valid JSON"; return; }
    index="$QUAL_DIR/index.txt"
    [ -f "$index" ] || { record "$gate" BLOCKED "no qualification index at $index; nothing is qualified"; return; }
    while IFS= read -r file; do
        file="${file%%#*}"; file="${file// /}"
        [ -n "$file" ] || continue
        rec="$QUAL_DIR/$file"
        [ -f "$rec" ] || { problem "index lists $file but the record is missing"; continue; }
        mismatches="$(jq -r --slurpfile id "$IDENTITY" '
            $id[0] as $id
            | ( [ (["minio","imageIndexDigest"], ["minio","platformMemberDigest"], ["minio","release"], ["minio","commitId"],
                   ["minio","mode"], ["minio","driveCount"], ["minio","timeoutEnvironment"], ["minio","storageClassInlineDefaults"],
                   ["host","kernelRelease"], ["host","filesystemType"], ["host","mountOptions"],
                   ["network","directPath"], ["network","proxy"], ["uploadImplementationVersion"]) as $p
                  | select(getpath($p) != ($id | getpath($p))) | ($p | join(".")) ]
                + ( if ((.minio.timeoutCliFlags // []) | sort) != (($id.minio.timeoutCliFlags // []) | sort) then ["minio.timeoutCliFlags"] else [] end )
                + ( if .minio.runtimeConfig.hash == null then ["minio.runtimeConfig.hash (record has no hash)"]
                    elif $id.minio.runtimeConfig.hash == null then ["minio.runtimeConfig.hash (declared identity has no hash)"]
                    elif .minio.runtimeConfig.hash != $id.minio.runtimeConfig.hash then ["minio.runtimeConfig.hash"]
                    else [] end ) )
            | join(", ")' "$rec" 2>/dev/null)" || { problem "record $file is not valid JSON"; continue; }
        if [ -z "$mismatches" ]; then
            match="$file"
            QUAL_MATCH="$file"
            break
        fi
        count="$(printf '%s' "$mismatches" | tr ',' '\n' | grep -c .)"
        if [ "$best_count" -lt 0 ] || [ "$count" -lt "$best_count" ]; then
            best_count="$count"; best="$file: $mismatches"
        fi
    done < "$index"
    if [ -z "$match" ]; then
        if [ -n "$best" ]; then
            problem "no qualification record matches the declared identity; closest is $best"
        else
            problem "no qualification record matches the declared identity"
        fi
        record "$gate" BLOCKED "$PROBLEMS"
        return
    fi
    # Eligibility is RE-DERIVED from the record's content, as the application does
    # (QualificationRecord.eligibility): the flag alone is never trusted.
    reasons="$(jq -r '
        [ (if .enablementEligible != true then ((.ineligibilityReasons // []) | join("; ")) else empty end),
          (if .qualification.slowWExecuted != true then "slow-W was not executed (ADR-035 §9a)" else empty end),
          (if .qualification.slowWExecuted == true and .qualification.scenarios != null
              and ((.qualification.scenarios | map(select(startswith("slow-"))) | length) == 0)
             then "slow-W is claimed executed but no slow-* scenario is listed" else empty end),
          (if .minio.runtimeConfig.hash == null then "the runtime admin-configuration hash is absent" else empty end),
          (if ((.qualification.platformClass // "") | ascii_downcase) == "laptop"
             then "platformClass is laptop: ADR-035 §18 gate 7a requires the deployed host'"'"'s own combination" else empty end)
        ] | map(select(length > 0)) | join("; ")' "$QUAL_DIR/$match")"
    if [ -n "$reasons" ]; then
        problem "record $match matches but is not enablement-eligible: $reasons"
    fi
    [ "$QUAL_METRIC" = 0 ] && problem "Ops qualification-check reports testinbox_storage_qualification_valid=0 on the real backend"
    if [ -n "$PROBLEMS" ]; then
        record "$gate" BLOCKED "$PROBLEMS"
    elif [ -n "$QUAL_METRIC" ]; then
        record "$gate" PASS "identity equals record $match (eligible); Ops qualification-check reports valid=1"
    else
        record "$gate" PASS "identity equals record $match (eligible); no Ops qualification-check value supplied — the real backend is unobserved by this script"
    fi
}

# ---------------------------------------------------------------------------
# Gate F (filesystem-containment contract §9, TI-STORAGE-006E PR E): the real
# filesystem, observed, against the declared storage combination. Inputs:
#   - the Ops evidence file (--filesystem-evidence): what Ops OBSERVED on the
#     host (statvfs, /proc/mounts, the backing image) and the values the
#     deployment DECLARES (the `testinbox.storage.footprint.*` settings);
#   - the database state (TESTINBOX_ACTIVATION_DB_URL, or --footprint-state
#     for an offline evaluation): T1's own footprint figures, the newest
#     monitor observation, the trust row, the privileges and the sequence.
# A declared value is never taken as an observation: every rule compares an
# observed figure with a declared one. A missing, malformed, stale or
# internally inconsistent input is NOT RUN; a rule that fails is BLOCKED.
# Under TENANT_LIMITS the global footprint rules are observational, so the
# containment theorem does not hold and gate F cannot make it hold: the mode
# is dark staging qualification only, and the gate says so.
# ---------------------------------------------------------------------------
FOOTPRINT_STATE_SQL="$(cat <<'SQL'
WITH newest AS (SELECT * FROM storage_filesystem_observation ORDER BY started_seq DESC, id DESC LIMIT 1),
     debt AS (SELECT coalesce(sum(bytes), 0) AS bytes, coalesce(sum(objects), 0) AS objects FROM storage_deletion_debt
               WHERE incurred_at = 'infinity'::timestamptz OR seq >= coalesce((SELECT started_seq FROM newest), 0)),
     obs_owner AS (SELECT relowner FROM pg_class WHERE oid = 'storage_filesystem_observation'::regclass),
     -- The roles the boundary is checked for: those Ops declare, AND every role a TestInbox
     -- deployable is connected as right now, so an omission from the evidence hides nothing.
     sessions AS (SELECT DISTINCT usename::text AS rolname FROM pg_stat_activity
                   WHERE application_name LIKE 'testinbox-%' AND application_name NOT LIKE 'testinbox-migrator%'
                     AND usename IS NOT NULL),
     app AS (SELECT r.oid, r.rolname, r.rolsuper FROM pg_roles r
              WHERE r.rolname = ANY (string_to_array(:'app_roles', ',')) OR r.rolname IN (SELECT rolname FROM sessions)),
     -- Every role a role can act as: itself, inherited grants, and SET ROLE targets (NOINHERIT included).
     reach AS (SELECT a.rolname AS app, g.oid AS goid FROM app a JOIN pg_roles g ON pg_has_role(a.oid, g.oid, 'MEMBER')),
     -- Writers of observations: grants held by any role, login or not, column grants included,
     -- expanded to every login role that can act as the holder.
     grantees AS (SELECT r.oid, has_any_column_privilege(r.oid, 'storage_filesystem_observation', 'INSERT') AS ins,
                         has_function_privilege(r.oid, 'storage_begin_observation()', 'EXECUTE') AS exec FROM pg_roles r),
     holders AS (SELECT l.rolname, bool_or(g.ins) AS ins, bool_or(g.exec) AS exec
                   FROM pg_roles l JOIN grantees g ON pg_has_role(l.oid, g.oid, 'MEMBER')
                  WHERE l.rolcanlogin AND NOT l.rolsuper AND NOT pg_has_role(l.oid, (SELECT relowner FROM obs_owner), 'MEMBER')
                  GROUP BY l.rolname),
     -- Relations whose triggers write the ledger or the debt: their owner can disable them.
     triggered AS (SELECT DISTINCT t.tgrelid AS rel FROM pg_trigger t JOIN pg_proc f ON f.oid = t.tgfoid
                    WHERE NOT t.tgisinternal AND f.pronamespace = 'public'::regnamespace AND f.proname LIKE 'storage\_%')
SELECT json_build_object(
  'now', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
  'inRecovery', pg_is_in_recovery(),
  'trust', (SELECT json_build_object('distrustEpoch', distrust_epoch, 'trustedEpoch', trusted_epoch, 'distrustedSeq', distrusted_seq)
              FROM storage_footprint_trust WHERE id = 1),
  'watermark', coalesce((SELECT compacted_through_seq FROM storage_debt_watermark WHERE id = 1), 0),
  'footprint', json_build_object(
      'liveBytes', (SELECT coalesce(sum(base_bytes), 0) FROM workspace_storage_account)
                 + (SELECT coalesce(sum(bytes), 0) FROM storage_delta) + (SELECT coalesce(sum(bytes), 0) FROM storage_reservation),
      'liveObjects', (SELECT coalesce(sum(base_objects), 0) FROM workspace_storage_account)
                 + (SELECT coalesce(sum(objects), 0) FROM storage_delta)
                 + (SELECT coalesce(sum(cardinality(object_keys)), 0) FROM storage_reservation),
      'debtBytes', (SELECT bytes FROM debt), 'debtObjects', (SELECT objects FROM debt)),
  'observation', (SELECT json_build_object('startedSeq', started_seq, 'writtenBy', written_by::text,
                     'observedAt', to_char(observed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                     'blockSizeBytes', block_size_bytes, 'capacityBytes', capacity_bytes, 'usedBytes', used_bytes,
                     'availBytes', avail_bytes, 'inodesTotal', inodes_total, 'trashBytes', trash_bytes, 'minioSysBytes', minio_sys_bytes)
                    FROM newest),
  'liveNodes', (SELECT coalesce(json_agg(DISTINCT node_id), '[]') FROM storage_node
                 WHERE NOT clean_shutdown AND heartbeat_at > now() - interval '5 minutes'),
  'sessionRoles', (SELECT coalesce(json_agg(rolname ORDER BY rolname), '[]') FROM sessions),
  'observationInserters', (SELECT coalesce(json_agg(rolname ORDER BY rolname), '[]') FROM holders WHERE ins),
  'beginObservationExecutors', (SELECT coalesce(json_agg(rolname ORDER BY rolname), '[]') FROM holders WHERE exec),
  'roleViolations', (SELECT coalesce(json_agg(DISTINCT v), '[]') FROM (
      SELECT a.rolname || ': is a superuser' AS v FROM app a WHERE a.rolsuper
      UNION ALL
      SELECT r.app || ': ' || p.what
        FROM reach r
        JOIN (VALUES ('INSERT on storage_filesystem_observation', 'storage_filesystem_observation', 'INSERT'),
                     ('INSERT on storage_deletion_debt', 'storage_deletion_debt', 'INSERT'),
                     ('UPDATE on storage_deletion_debt', 'storage_deletion_debt', 'UPDATE'),
                     ('INSERT on storage_debt_watermark', 'storage_debt_watermark', 'INSERT'),
                     ('UPDATE on storage_debt_watermark', 'storage_debt_watermark', 'UPDATE')) AS p(what, tbl, priv)
          ON has_any_column_privilege(r.goid, p.tbl, p.priv)
      UNION ALL
      SELECT r.app || ': DELETE on ' || p.tbl
        FROM reach r JOIN (VALUES ('storage_deletion_debt'), ('storage_debt_watermark')) AS p(tbl)
          ON has_table_privilege(r.goid, p.tbl, 'DELETE')
      UNION ALL
      SELECT r.app || ': EXECUTE on storage_begin_observation()' FROM reach r
       WHERE has_function_privilege(r.goid, 'storage_begin_observation()', 'EXECUTE')
      UNION ALL
      SELECT r.app || ': SET on session_replication_role (disables the ledger triggers)' FROM reach r
       WHERE has_parameter_privilege(r.goid, 'session_replication_role', 'SET')
      UNION ALL
      SELECT a.rolname || ': owns ' || c.relname FROM app a JOIN pg_class c ON pg_has_role(a.oid, c.relowner, 'MEMBER')
       WHERE c.relnamespace = 'public'::regnamespace
         AND ((c.relkind IN ('r', 'S') AND c.relname LIKE 'storage\_%') OR c.oid IN (SELECT rel FROM triggered))
      UNION ALL
      SELECT a.rolname || ': owns ' || f.proname || '()' FROM app a JOIN pg_proc f ON pg_has_role(a.oid, f.proowner, 'MEMBER')
       WHERE f.pronamespace = 'public'::regnamespace AND f.proname LIKE 'storage\_%') AS violations),
  'sequenceCacheSize', (SELECT cache_size FROM pg_sequences WHERE schemaname = 'public' AND sequencename = 'storage_debt_order_seq'))
SQL
)"

FS_STATE_FILE="$WORK/footprint-state.json"
FS_OFFLINE=false
collect_footprint_state() {
    # Returns 1 when no database state is available, 3 when two sources conflict. The live
    # database always wins: an offline file is refused while a URL is set, and is named in the verdict.
    if [ -n "$FOOTPRINT_STATE" ] && [ -n "${TESTINBOX_ACTIVATION_DB_URL:-}" ]; then
        return 3
    elif [ -n "$FOOTPRINT_STATE" ]; then
        cp "$FOOTPRINT_STATE" "$FS_STATE_FILE"
        FS_OFFLINE=true
    elif [ -n "${TESTINBOX_ACTIVATION_DB_URL:-}" ]; then
        local roles
        roles="$(jq -r '(.declared.applicationRoles // []) | join(",")' "$FS_EVIDENCE" 2>/dev/null)"
        # Through stdin, not -c: psql interpolates :'app_roles' (quoted, so a role name cannot inject) only there.
        printf '%s\n' "$FOOTPRINT_STATE_SQL;" | PGAPPNAME="ops:storage-activation-check" psql "$TESTINBOX_ACTIVATION_DB_URL" \
            -X -At -v ON_ERROR_STOP=1 -v app_roles="$roles" -f - >"$FS_STATE_FILE" 2>"$WORK/psql-f.err" || return 2
    else
        return 1
    fi
}

# epoch seconds of an ISO-8601 UTC instant (…Z), or empty.
# The containment floor: the rollback floor whose rationale names TI-STORAGE-006E. Gate F
# requires it DECLARED; gate E-floor-staging already proves every running artifact contains
# every TI-STORAGE floor, this one included, so a pre-PR-D node (one that admits without
# rule C, or deletes without debt) cannot be live while both pass.
containment_floor() { adr035_floors | awk -F'\t' 'index($2, "TI-STORAGE-006E") { print $1; exit }'; }

iso_seconds() { jq -rn --arg t "$1" '$t | try fromdateiso8601 catch empty' 2>/dev/null; }

gate_filesystem() {
    local gate="F-filesystem" record_file="" malformed missing stale now_s collected_s observed_s a_obs verdicts rc
    reset_problems
    if [ "$MODE" = TENANT_LIMITS ]; then
        record "$gate" NOT_REQUIRED "TENANT_LIMITS: global footprint admission is observational, so the containment theorem does not hold and filesystem exhaustion stays reachable; this mode is approved for dark staging qualification only, never public traffic"
        return
    fi
    if [ -z "$FS_EVIDENCE" ]; then
        record "$gate" NOT_RUN "no --filesystem-evidence supplied"
        return
    fi
    jq -e 'type == "object"' "$FS_EVIDENCE" >/dev/null 2>&1 || { record "$gate" NOT_RUN "filesystem evidence is not a JSON object"; return; }
    collect_footprint_state; rc=$?
    case "$rc" in
        1) record "$gate" NOT_RUN "no database state: set TESTINBOX_ACTIVATION_DB_URL or pass --footprint-state"; return ;;
        2) record "$gate" NOT_RUN "footprint state query failed; the database could not be observed"; return ;;
        3) record "$gate" NOT_RUN "--footprint-state and TESTINBOX_ACTIVATION_DB_URL both given; the live database is the only source when it is reachable"; return ;;
    esac
    jq -e 'type == "object"' "$FS_STATE_FILE" >/dev/null 2>&1 || { record "$gate" NOT_RUN "footprint state is not a JSON object"; return; }

    # Shape: every field the rules read, with its type. Missing or mistyped is NOT RUN.
    malformed="$(jq -r -n --slurpfile e "$FS_EVIDENCE" --slurpfile s "$FS_STATE_FILE" '
        def need($doc; $name; $at; $kind):
            ($doc | getpath($at)) as $v
            | if $v == null then "\($name).\($at | join(".")) missing"
              elif $kind == "count" and (($v | type) != "number" or $v < 0 or ($v | floor) != $v) then "\($name).\($at | join(".")) is not a non-negative integer"
              elif $kind == "positive" and (($v | type) != "number" or $v <= 0 or ($v | floor) != $v) then "\($name).\($at | join(".")) is not a positive integer"
              elif $kind == "age" and (($v | type) != "number" or $v <= 0 or ($v | floor) != $v or $v > 3600) then "\($name).\($at | join(".")) is not a whole number of seconds in (0, 3600]"
              elif $kind == "ratio" and (($v | type) != "number" or $v < 0 or $v >= 1) then "\($name).\($at | join(".")) is not in [0, 1)"
              elif $kind == "string" and (($v | type) != "string" or $v == "") then "\($name).\($at | join(".")) is not a non-empty string"
              elif $kind == "boolean" and ($v | type) != "boolean" then "\($name).\($at | join(".")) is not a boolean"
              elif $kind == "strings" and (($v | type) != "array" or ($v | length) == 0 or any($v[]; type != "string" or . == "")) then "\($name).\($at | join(".")) is not a non-empty list of names"
              else empty end;
        $e[0] as $e | $s[0] as $s
        | [ ( [["schema"], "string"], [["collectedAt"], "string"], [["qualificationRecordId"], "string"], [["qualificationValid"], "count"],
              [["filesystem","uuid"], "string"], [["filesystem","type"], "string"], [["filesystem","mountOptions"], "string"],
              [["filesystem","mountSource"], "string"],
              [["mount","mountsOfSource"], "count"], [["mount","minioDataDirOnMount"], "boolean"], [["mount","otherDataOnMount"], "boolean"],
              [["statvfs","frsize"], "positive"], [["statvfs","blocks"], "count"], [["statvfs","files"], "count"],
              [["preallocation","blockDevice"], "boolean"], [["preallocation","thinPool"], "boolean"], [["preallocation","fstrimExcluded"], "boolean"],
              [["isolation","dedicatedDevice"], "boolean"],
              [["minio","bucketDirectoryBytes"], "count"],
              [["baseCase","trustConfirmedAt"], "string"], [["baseCase","sweepStartedAt"], "string"],
              [["database","allConnectionsToPrimary"], "boolean"],
              [["declared","capacityBytes"], "positive"], [["declared","blockSizeBytes"], "positive"], [["declared","inodes"], "positive"],
              [["declared","globalFootprintLimitBytes"], "positive"], [["declared","finalizeBudgetBytes"], "count"],
              [["declared","metadataBudgetBytes"], "count"], [["declared","operationalReserveBytes"], "count"],
              [["declared","objectOverheadBytes"], "count"], [["declared","fragmentationEpsilon"], "ratio"],
              [["declared","deletionDebtBudgetBytes"], "positive"], [["declared","observationMaxAgeSeconds"], "age"],
              [["declared","monitorRole"], "string"], [["declared","applicationRoles"], "strings"], [["declared","procs"], "positive"]
            ) | need($e; "evidence"; .[0]; .[1]) ]
          + ( if $e.preallocation.blockDevice == false then
                [ need($e; "evidence"; ["preallocation","allocatedBytes"]; "count"), need($e; "evidence"; ["preallocation","apparentBytes"]; "count") ]
              else [] end )
          + ( if $e.isolation.dedicatedDevice == false then
                [ need($e; "evidence"; ["isolation","hostFreeAtCreationBytes"]; "count"), need($e; "evidence"; ["isolation","imageBytes"]; "count") ]
              else [] end )
          + [ ( [["now"], "string"], [["inRecovery"], "boolean"], [["trust","distrustEpoch"], "count"], [["trust","distrustedSeq"], "count"], [["watermark"], "count"],
                [["footprint","liveBytes"], "count"], [["footprint","liveObjects"], "count"], [["footprint","debtBytes"], "count"],
                [["footprint","debtObjects"], "count"], [["sequenceCacheSize"], "positive"] ) | need($s; "state"; .[0]; .[1]) ]
          + ( if ($s.liveNodes | type) != "array" then ["state.liveNodes is not a list"] else [] end )
          + ( if ($s.observationInserters | type) != "array" then ["state.observationInserters is not a list"] else [] end )
          + ( if ($s.beginObservationExecutors | type) != "array" then ["state.beginObservationExecutors is not a list"] else [] end )
          + ( if ($s.roleViolations | type) != "array" then ["state.roleViolations is not a list"] else [] end )
          + ( if ($s.sessionRoles | type) != "array" then ["state.sessionRoles is not a list"] else [] end )
          + ( if $s.observation == null then []
              else [ ( [["startedSeq"], "count"], [["writtenBy"], "string"], [["observedAt"], "string"], [["blockSizeBytes"], "positive"],
                       [["capacityBytes"], "count"], [["usedBytes"], "count"], [["availBytes"], "count"], [["inodesTotal"], "count"],
                       [["trashBytes"], "count"], [["minioSysBytes"], "count"] ) | need($s.observation; "state.observation"; .[0]; .[1]) ]
              end )
        | map(select(. != null)) | join("; ")' 2>"$WORK/shape.err")" ||
        malformed="evidence or state could not be evaluated ($(head -c 200 "$WORK/shape.err" | tr '\n' ' '))"
    if [ -n "$malformed" ]; then
        record "$gate" NOT_RUN "malformed input: $malformed"
        return
    fi
    if [ "$(jq -r .schema "$FS_EVIDENCE")" != "testinbox.filesystem-evidence/1" ]; then
        record "$gate" NOT_RUN "evidence schema is '$(jq -r .schema "$FS_EVIDENCE")', not testinbox.filesystem-evidence/1"
        return
    fi

    # Freshness, on the DATABASE clock (the observation is stamped by it too).
    now_s="$(iso_seconds "$(jq -r .now "$FS_STATE_FILE")")"
    collected_s="$(iso_seconds "$(jq -r .collectedAt "$FS_EVIDENCE")")"
    a_obs="$(jq -r .declared.observationMaxAgeSeconds "$FS_EVIDENCE")"
    for pair in "now_s:state.now" "collected_s:evidence.collectedAt"; do
        eval "v=\"\$${pair%%:*}\""
        [ -n "$v" ] || { record "$gate" NOT_RUN "malformed input: ${pair#*:} is not an ISO-8601 UTC instant (…Z)"; return; }
    done
    for at in baseCase.trustConfirmedAt baseCase.sweepStartedAt; do
        [ -n "$(iso_seconds "$(jq -r ".$at" "$FS_EVIDENCE")")" ] ||
            { record "$gate" NOT_RUN "malformed input: evidence.$at is not an ISO-8601 UTC instant (…Z)"; return; }
    done
    if [ "$collected_s" -gt "$((now_s + 60))" ]; then
        record "$gate" NOT_RUN "stale input: evidence.collectedAt is in the future of the database clock"
        return
    fi
    if [ "$((now_s - collected_s))" -gt "$a_obs" ]; then
        record "$gate" NOT_RUN "stale input: the evidence was collected $((now_s - collected_s)) s ago, more than A_obs = $a_obs s"
        return
    fi

    # Internal consistency: what Ops saw on the host and what the monitor wrote agree.
    stale="$(jq -r -n --slurpfile e "$FS_EVIDENCE" --slurpfile s "$FS_STATE_FILE" '
        $e[0] as $e | $s[0].observation as $o
        | [ ( if $o != null and $o.blockSizeBytes != $e.statvfs.frsize
                then "the newest observation block size \($o.blockSizeBytes) differs from statvfs f_frsize \($e.statvfs.frsize)" else empty end ),
            ( if $o != null and $o.capacityBytes != ($e.statvfs.frsize * $e.statvfs.blocks)
                then "the newest observation capacity \($o.capacityBytes) differs from statvfs f_blocks × f_frsize \($e.statvfs.frsize * $e.statvfs.blocks)" else empty end ),
            ( if $o != null and $o.inodesTotal != $e.statvfs.files
                then "the newest observation inode total \($o.inodesTotal) differs from statvfs f_files \($e.statvfs.files)" else empty end ),
            ( if $e.preallocation.blockDevice == true and $e.isolation.dedicatedDevice == false
                then "preallocation says block device, isolation says backing image" else empty end ) ]
        | join("; ")')"
    if [ -n "$stale" ]; then
        record "$gate" NOT_RUN "contradictory input: $stale"
        return
    fi

    # The qualification record the evidence names: listed, and carrying filesystem elements.
    local rid
    rid="$(jq -r .qualificationRecordId "$FS_EVIDENCE")"
    if [ -f "$QUAL_DIR/index.txt" ]; then
        while IFS= read -r file; do
            file="${file%%#*}"; file="${file// /}"
            [ -n "$file" ] || continue
            if [ -f "$QUAL_DIR/$file" ] && [ "$(jq -r '.recordId // .id // empty' "$QUAL_DIR/$file" 2>/dev/null)" = "$rid" ]; then
                record_file="$QUAL_DIR/$file"
                break
            fi
        done < "$QUAL_DIR/index.txt"
    fi

    # Every rule, on observed figures against declared ones. φ arithmetic in jq's
    # doubles: exact below 2^53 bytes (8 PiB), far above any declared capacity.
    verdicts="$(jq -r -n --slurpfile e "$FS_EVIDENCE" --slurpfile s "$FS_STATE_FILE" \
        --slurpfile r <(if [ -n "$record_file" ]; then cat "$record_file"; else echo null; fi) \
        --arg qmatch "${QUAL_MATCH:-}" --arg qfile "$(basename "${record_file:-}")" \
        --arg floor "$(containment_floor)" \
        --argjson expectedIng "$(jq -cn --arg v "$EXPECTED_ING" '$v | split(",") | map(select(length > 0))')" \
        --argjson now "$now_s" '
        $e[0] as $e | $s[0] as $s | $r[0] as $rec | $e.declared as $d | $s.observation as $o
        | ($d.blockSizeBytes) as $B | ($d.fragmentationEpsilon) as $eps | ($d.objectOverheadBytes) as $O
        | def F($p; $n): ($p + $n * ($B - 1)) * (1 + $eps) + $n * ($O + 1);
          def ceilnum: if . == floor then . else floor + 1 end;
          def secs: try fromdateiso8601 catch null;
        ($s.footprint) as $fp
        | (F($fp.liveBytes + $fp.debtBytes; $fp.liveObjects + $fp.debtObjects) + ($o.trashBytes // 0) | ceilnum) as $phi
        | (F($fp.liveBytes; $fp.liveObjects) | ceilnum) as $fl
        | [
            ( if $rec == null then "identity: qualification record \($e.qualificationRecordId) is not in the qualification index"
              elif $rec.filesystem == null then "identity: record \($e.qualificationRecordId) carries no filesystem elements (contract §9: re-issue it after E1–E8 on this filesystem)"
              else ( [ "uuid", "type", "mountOptions", "mountSource" ]
                     | map(select($rec.filesystem[.] != $e.filesystem[.]))
                     | if length > 0 then "identity: filesystem \(join(", ")) differ from record \($e.qualificationRecordId)" else empty end )
              end ),
            ( if $e.mount.mountsOfSource != 1 then "dedicated mount: /proc/mounts lists \($e.mount.mountsOfSource) mounts of the source, not exactly 1" else empty end ),
            ( if $e.mount.minioDataDirOnMount != true then "dedicated mount: MinIO data directory is not on the mount" else empty end ),
            ( if $e.mount.otherDataOnMount != false then "dedicated mount: something other than MinIO data is on the mount" else empty end ),
            ( if $e.statvfs.frsize != $B then "capacity: f_frsize \($e.statvfs.frsize) ≠ declared B \($B)" else empty end ),
            ( if $e.statvfs.blocks * $e.statvfs.frsize < $d.capacityBytes
                then "capacity: f_blocks × f_frsize \($e.statvfs.blocks * $e.statvfs.frsize) < declared C_fs \($d.capacityBytes)" else empty end ),
            ( if $e.statvfs.files < $d.inodes then "inodes: f_files \($e.statvfs.files) < declared I_fs \($d.inodes)" else empty end ),
            ( if $d.inodes < ($d.capacityBytes / $B | ceilnum) then "inodes: declared I_fs \($d.inodes) < C_fs / B \($d.capacityBytes / $B | ceilnum)" else empty end ),
            ( if $e.preallocation.blockDevice == false and $e.preallocation.allocatedBytes != $e.preallocation.apparentBytes
                then "preallocation: backing image allocated \($e.preallocation.allocatedBytes) ≠ apparent size \($e.preallocation.apparentBytes) (sparse)" else empty end ),
            ( if $e.preallocation.thinPool then "preallocation: the filesystem is on a thin pool" else empty end ),
            ( if $e.preallocation.fstrimExcluded != true then "preallocation: no fstrim exclusion is recorded" else empty end ),
            ( if $e.isolation.dedicatedDevice == false and $e.isolation.hostFreeAtCreationBytes < $e.isolation.imageBytes
                then "isolation: the host filesystem had \($e.isolation.hostFreeAtCreationBytes) free at creation, less than the image \($e.isolation.imageBytes)" else empty end ),
            ( if $o == null then "headroom: no monitor observation exists"
              else ( ( if $o.usedBytes > $phi + $d.finalizeBudgetBytes + $d.metadataBudgetBytes
                         then "headroom: used \($o.usedBytes) > Φ \($phi) + H_F \($d.finalizeBudgetBytes) + M \($d.metadataBudgetBytes)" else empty end ),
                     ( if $o.availBytes < $d.operationalReserveBytes
                         then "headroom: avail \($o.availBytes) < R_ops \($d.operationalReserveBytes)" else empty end ) ) end ),
            ( if $o != null and ($phi - $fl) > $d.deletionDebtBudgetBytes
                then "deletion debt: D_est \($phi - $fl) > D_budget \($d.deletionDebtBudgetBytes)" else empty end ),
            ( if $o != null and $o.startedSeq < $s.watermark
                then "observation validity: the newest observation began at seq \($o.startedSeq), below the compaction watermark \($s.watermark) (T1 refuses it)" else empty end ),
            ( if ([$fp.liveBytes, $fp.liveObjects, $fp.debtBytes, $fp.debtObjects, $fp.liveBytes + $fp.debtBytes] | max) > 9223372036854775807
                then "indeterminate: a footprint total does not fit a signed 64-bit figure (T1 refuses it)" else empty end ),
            ( if $s.trust == null or $s.trust.trustedEpoch != $s.trust.distrustEpoch
                then "trusted counts: trusted_epoch \($s.trust.trustedEpoch // "null") ≠ distrust_epoch \($s.trust.distrustEpoch // "null")" else empty end ),
            ( if $o != null and $o.startedSeq <= $s.trust.distrustedSeq
                then "base case: the newest observation began at seq \($o.startedSeq), not after the last distrust event (seq \($s.trust.distrustedSeq))" else empty end ),
            ( if ($e.baseCase.sweepStartedAt | secs) <= ($e.baseCase.trustConfirmedAt | secs)
                then "base case: the orphan sweep began \($e.baseCase.sweepStartedAt), not after the counts were last trusted \($e.baseCase.trustConfirmedAt)" else empty end ),
            ( if $floor == "" then "mixed versions: no TI-STORAGE-006E containment rollback floor is declared (it is added when PR D merges)" else empty end ),
            ( ( if ($expectedIng | length) > 0 then $expectedIng else $s.liveNodes end ) as $nodes
              | if ($nodes | length) > $d.procs then "procs: declared procs \($d.procs) < \($nodes | length) live ingestion node(s)" else empty end ),
            ( if $o != null and $o.writtenBy != $d.monitorRole
                then "observation source: newest observation written by \($o.writtenBy), not the declared monitor \($d.monitorRole)" else empty end ),
            ( if ($d.applicationRoles | index($d.monitorRole)) != null then "observation source: the monitor role is an application role" else empty end ),
            ( if $s.observationInserters != [$d.monitorRole]
                then "observation source: INSERT on storage_filesystem_observation is held by [\($s.observationInserters | join(", "))], not only \($d.monitorRole)" else empty end ),
            ( if $s.beginObservationExecutors != [$d.monitorRole]
                then "observation source: EXECUTE on storage_begin_observation() is held by [\($s.beginObservationExecutors | join(", "))], not only \($d.monitorRole)" else empty end ),
            ( if ($s.roleViolations | length) > 0
                then "privileges (roles checked: declared + connected [\($s.sessionRoles | join(", "))]): \($s.roleViolations | length) violation(s), first \($s.roleViolations[0:5] | join(", ")) (where the application connects as the owner, as staging does, gate F fails by design)" else empty end ),
            ( if $s.sequenceCacheSize != 1 then "ordering: storage_debt_order_seq has CACHE \($s.sequenceCacheSize), not 1" else empty end ),
            ( if $s.inRecovery or $e.database.allConnectionsToPrimary != true
                then "ordering: a reader or writer is not on the primary" else empty end ),
            ( if $o != null and ($o.minioSysBytes + $e.minio.bucketDirectoryBytes) > $d.metadataBudgetBytes
                then "MinIO metadata: .minio.sys \($o.minioSysBytes) + bucket directories \($e.minio.bucketDirectoryBytes) > M \($d.metadataBudgetBytes)" else empty end ),
            ( if $e.qualificationValid != 1 then "qualification identity: Ops qualification-check reports valid=\($e.qualificationValid)" else empty end ),
            ( if $qmatch != "" and $qfile != "" and $qmatch != $qfile
                then "qualification identity: gate Q matched \($qmatch), the evidence names \($qfile)" else empty end ),
            ( if $o == null then empty
              else ( ($o.observedAt | secs) as $at
                     | if $at == null then "observation liveness: observedAt is unreadable"
                       elif $at > $now then "observation liveness: the newest observation is in the future of the database clock"
                       elif ($now - $at) > $d.observationMaxAgeSeconds
                         then "observation liveness: the newest observation is \($now - $at) s old, more than A_obs \($d.observationMaxAgeSeconds) s"
                       else empty end ) end )
          ] | join("; ")' 2>"$WORK/gate-f.err")" || { record "$gate" NOT_RUN "the evidence could not be evaluated: $(head -c 300 "$WORK/gate-f.err")"; return; }
    if [ -n "$verdicts" ]; then
        record "$gate" BLOCKED "$verdicts"
    else
        local source="live database"
        $FS_OFFLINE && source="an OFFLINE --footprint-state file, not observed by this run"
        record "$gate" PASS "database state from $source; filesystem $(jq -r .filesystem.uuid "$FS_EVIDENCE") equals record $rid; capacity, inodes, preallocation, isolation, headroom, deletion debt, trust, base case, versions, procs, observation source, privileges, ordering, metadata and liveness hold"
    fi
}

gate_sessions
gate_inventory
gate_physical
gate_clock
gate_benchmark
gate_floors_staging
gate_floors_production
gate_qualification
gate_filesystem

# ---------------------------------------------------------------------------
# Verdict and output.
# ---------------------------------------------------------------------------
OVERALL=READY
while IFS="$TAB" read -r gate verdict detail; do
    [ -n "$gate" ] || continue
    case "$verdict" in PASS|NOT_REQUIRED) ;; *) OVERALL=BLOCKED ;; esac
done <<EOF
$RESULTS
EOF

EVALUATED_AT="${TESTINBOX_ACTIVATION_EVALUATED_AT:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
echo "ADR-035 activation barrier — mode $MODE — evaluated $EVALUATED_AT"
printf '%-22s %-13s %s\n' GATE VERDICT DETAIL
printf '%-22s %-13s %s\n' ---------------------- ------------- ------
printf '%s' "$RESULTS" | awk -F'\t' '{ v = $2; gsub(/_/, " ", v); printf "%-22s %-13s %s\n", $1, v, $3 }'
echo "----"
echo "ACTIVATION $OVERALL"

if [ -n "$JSON_OUT" ]; then
    printf '%s' "$RESULTS" | jq -R -s --arg mode "$MODE" --arg at "$EVALUATED_AT" --arg verdict "$OVERALL" '
        split("\n") | map(select(length > 0) | split("\t") | { gate: .[0], verdict: .[1], detail: .[2] })
        | { mode: $mode, evaluatedAt: $at, gates: ., verdict: $verdict }' > "$JSON_OUT" ||
        { echo "error: could not write $JSON_OUT" >&2; exit 2; }
fi

[ "$OVERALL" = READY ]
