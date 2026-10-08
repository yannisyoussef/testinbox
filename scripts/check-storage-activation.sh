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
#   --json <file>                      write the evidence record
# Env:
#   TESTINBOX_ACTIVATION_DB_URL        psql conninfo/URL; replaces --sessions-file / --nodes-file.
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
for f in "$SESSIONS_FILE" "$NODES_FILE" "$IDENTITY" "$BENCH" "$FLOORS_FILE"; do
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
                     --argjson minres "$MIN_RESERVATION_BACKLOG" --arg conc "$REQUIRED_CONCURRENCY" '
        (.scenarios // []) as $s
        | ($conc | split(",") | map(tonumber)) as $required
        | [ $s[] | select(.mode == "CHOSEN" and .offeredRate != null and (.offeredRate | round) == ((2 * $expected) | round)) ] as $chosen2x
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
        + [ $chosen2x[] | select(.achievedRate == null or .achievedRate / .offeredRate < $tol)
              | "sustained2x: \(.name) achieved \(.achievedRate // "nothing") of \(.offeredRate)/s offered (ratio below \($tol))" ]
        + [ $chosen2x[] | select(.percentiles.t1.p99Ms == null) | "t1P99: \(.name) has no T1 samples" ]
        + [ $chosen2x[] | select(.percentiles.t1.p99Ms != null and .percentiles.t1.p99Ms > $t1max)
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

gate_sessions
gate_inventory
gate_physical
gate_clock
gate_benchmark
gate_floors_staging
gate_floors_production
gate_qualification

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
