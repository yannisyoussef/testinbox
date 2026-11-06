#!/usr/bin/env bash
# Regression evidence for the held 30 s terminal-completion allowance (owner decision
# 2026-11-06): runs gate D of scripts/check-storage-activation.sh as it was at OLD (before
# the window was held) and at NEW (with it) on every committed gate-D evidence file, the
# script's own benchmark fixtures, and four synthetic boundary fixtures, and prints the
# per-scenario sustained2x verdicts side by side. Read-only: the scripts, fixtures and
# evidence are read from git objects, everything else lives in a temporary directory.
#
#   git fetch origin
#   docs/adr/0035-benchmark/terminal-allowance-regression.sh [OLD] [NEW]
#
# The output is the table recorded in terminal-allowance-regression.md.
set -euo pipefail

OLD="${1:-0aa27fb}"
NEW="${2:-efab78a}"
REPO="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
export TESTINBOX_ACTIVATION_EVALUATED_AT="2026-10-07T00:00:00Z"
unset TESTINBOX_ACTIVATION_DB_URL

# Every committed gate-D evidence.json (commit:path); the commits are the Ops evidence branches' heads.
EVIDENCE=(
    "fdab4c3b7afa3f4dd5f3071af8e6c17ffd5afb8d:docs/adr/0035-benchmark/staging/2026-11-03-c10dd3d-FAIL/evidence.json"
    "9ffe85931d476b203e1a9817c067c25243b94fb3:docs/adr/0035-benchmark/staging/2026-11-05-9d06ab2-run1/evidence.json"
    "4927c206b4a6a9703873f60f7616507472418393:docs/adr/0035-benchmark/staging/2026-11-06-1023af5-run2-part1-ws10000/evidence.json"
    "59095045898cf25dac1e7ae805d5bee919aead48:docs/adr/0035-benchmark/staging/2026-11-06-6347627-run3-r31/evidence.json"
    "ad53898205da61cf345119f0e7844f797a4729e0:docs/adr/0035-benchmark/staging/2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence/evidence.json"
    "26f92956afd6e8314a6d601f01c6bdc8a112085d:docs/adr/0035-benchmark/staging/2026-11-06-80482e6-FOCUSED-P-ASSESSMENT-not-gate-D-evidence/evidence.json"
)
for run in run1-focused-cohort-82eeee3 run2-focused-cohort-7b09bcb run3-focused-cohort-82eeee3 run4-focused-cohort-7b09bcb \
           run5-focused-82eeee3 run6-focused-7b09bcb run7-focused-82eeee3 run8-focused-7b09bcb; do
    EVIDENCE+=("ebdad5897c89505548044da61cc47d3d3b14177b:docs/adr/0035-benchmark/staging/2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence/$run/evidence.json")
done

# --- the two gate scripts and NEW's fixtures, from git objects ---
for c in "$OLD" "$NEW"; do
    mkdir -p "$TMP/$c/scripts"
    git -C "$REPO" show "$c:scripts/check-storage-activation.sh" > "$TMP/$c/scripts/check-storage-activation.sh"
    chmod +x "$TMP/$c/scripts/check-storage-activation.sh"
done
mkdir -p "$TMP/fx"
git -C "$REPO" archive "$NEW" scripts/testdata/storage-activation | tar -x -C "$TMP/fx"
FIX="$TMP/fx/scripts/testdata/storage-activation"

# --- every other gate satisfied, exactly as scripts/check-storage-activation.test.sh sets it up ---
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
git -C "$TMP/repo" update-ref refs/remotes/origin/master "$HEAD_SHA"
M="$TMP/metrics"; mkdir -p "$M"
for f in "$FIX"/metrics/*.prom; do sed "s/@GIT_SHA@/$HEAD_SHA/g" "$f" > "$M/$(basename "$f")"; done

# run <script-commit> <evidence> → the gate's whole output, nodes declaring R_gated = the evidence's harness.expectedRate
run() {
    local rate
    rate="$(jq -r '.harness.expectedRate' "$2")"
    sed -E "s/^(testinbox_storage_gated_expected_rate\{[^}]*\}) .*/\1 $rate/" "$M/api-healthy.prom" > "$M/api-r.prom"
    sed -E "s/^(testinbox_storage_gated_expected_rate\{[^}]*\}) .*/\1 $rate/" "$M/ingestion-healthy.prom" > "$M/ingestion-r.prom"
    "$TMP/$1/scripts/check-storage-activation.sh" --mode ALL --repo "$TMP/repo" \
        --api-metrics "$M/api-r.prom" --ingestion-metrics "$M/ingestion-r.prom" \
        --expected-api-nodes api-1 --expected-ingestion-nodes ing-1 \
        --sessions-file "$FIX/sessions/healthy.tsv" --nodes-file "$FIX/nodes/healthy.tsv" \
        --qualification-dir "$FIX/qualification" --backend-identity "$FIX/identity/match.json" \
        --benchmark-evidence "$2" \
        --filesystem-evidence "$FIX/filesystem/evidence-good.json" --footprint-state "$FIX/filesystem/state-good.json" \
        --cohort-check-output "$FIX/cohort/check-pass.txt" --cohort-pool-file "$FIX/cohort/pool.json" \
        --population-state "$FIX/population/pre-plan-within.json" 2>&1 || true
}

# --- inputs: committed evidence, the script's fixtures, synthetic boundary fixtures ---
mkdir -p "$TMP/in"
INPUTS=()
for e in "${EVIDENCE[@]}"; do
    out="$TMP/in/$(printf '%s' "${e#*:}" | sed -e 's#docs/adr/0035-benchmark/staging/##' -e 's#/evidence.json##' -e 's#/#__#g').json"
    git -C "$REPO" show "$e" > "$out"
    INPUTS+=("$out|$e")
done
for f in pass fail pass-but-t1-violates missing-reference incomplete; do
    sed "s/@GIT_SHA@/$HEAD_SHA/g" "$FIX/benchmark/$f.json" > "$TMP/in/fixture-$f.json"
    INPUTS+=("$TMP/in/fixture-$f.json|$NEW:scripts/testdata/storage-activation/benchmark/$f.json")
done
# The closed-beta fixture (R = 31) exactly as the suite derives it, then the boundary: every
# scenario rewritten for a window of S s, chosen-ws10000-c100-r62 with terminal lag L ms and
# the achieved rate the harness's open loop reports for it, N / ((N - 1) / rate + L).
jq '.harness.expectedRate = 31.0 | .harness.rates = [31.0, 62.0]
    | .scenarios |= map(
        .achievedRate = (if .achievedRate == null then null else .achievedRate * (if .offeredRate == 260 then 31 / 260 else 62 / 520 end) end)
        | .offeredRate = (if .offeredRate == 260 then 31.0 else 62.0 end)
        | .name |= (sub("-r260$"; "-r31") | sub("-r520$"; "-r62")))' "$TMP/in/fixture-pass.json" > "$TMP/in/fixture-beta.json"
INPUTS+=("$TMP/in/fixture-beta.json|derived from fixture-pass.json as check-storage-activation.test.sh derives bench-beta.json")
LAG_MAX_MS="$(jq -n '((1 + 1860 * (1 / 0.995 - 1)) / 62) * 1000')"
for s in 30 60; do
    for side in minus plus; do
        lag="$(jq -n --argjson m "$LAG_MAX_MS" --arg side "$side" 'if $side == "minus" then $m - 0.001 else $m + 0.001 end')"
        jq --argjson s "$s" --argjson lag "$lag" '
            .scenarios |= map(
                ((.offeredRate * $s) | floor) as $n
                | .scheduledEvents = $n | .completedEvents = $n
                | .terminalLagMs = (if .name == "chosen-ws10000-c100-r62" then $lag else 8.0 end)
                | .achievedRate = ($n / (($n - 1) / .offeredRate + .terminalLagMs / 1000)))' "$TMP/in/fixture-beta.json" > "$TMP/in/boundary-${s}s-lagmax-$side-1us.json"
        if [ "$side" = minus ]; then sign="-"; else sign="+"; fi
        INPUTS+=("$TMP/in/boundary-${s}s-lagmax-$side-1us.json|synthetic: ${s} s window, c100 lag = lag_max(62/s) $sign 0.001 ms = $lag ms")
    done
done

echo "lag_max(62/s) as jq computes it: $LAG_MAX_MS ms"
echo
echo "## Inputs"
echo
echo "A committed input's sha256 is that of its git object's bytes (a fixture before @GIT_SHA@ is"
echo "rendered). A derived input carries the throwaway repository's commit as gitSha, which differs"
echo "on every run, so its sha256 is that of \`jq -S 'del(.gitSha)'\` of it."
echo
echo "| Input | Source | sha256 |"
echo "|---|---|---|"
for i in "${INPUTS[@]}"; do
    f="${i%%|*}"; src="${i#*|}"
    if git -C "$REPO" cat-file -e "$src" 2>/dev/null; then
        sum="\`$(git -C "$REPO" show "$src" | shasum -a 256 | cut -d' ' -f1)\`"
    else
        sum="\`$(jq -S 'del(.gitSha)' "$f" | shasum -a 256 | cut -d' ' -f1)\` (gitSha removed)"
    fi
    printf '| `%s` | %s | %s |\n' "$(basename "$f" .json)" "$src" "$sum"
done
echo
echo "## Gate D and the overall verdict"
echo
echo "| Input | D @$OLD | D @$NEW | overall @$OLD | overall @$NEW | whole output identical |"
echo "|---|---|---|---|---|---|"
for i in "${INPUTS[@]}"; do
    f="${i%%|*}"
    run "$OLD" "$f" > "$TMP/out-old"
    run "$NEW" "$f" > "$TMP/out-new"
    d_old="$(awk '$1 == "D-benchmark" { print $2; exit }' "$TMP/out-old")"
    d_new="$(awk '$1 == "D-benchmark" { print $2; exit }' "$TMP/out-new")"
    v_old="$(grep -E '^ACTIVATION ' "$TMP/out-old" | tail -1)"
    v_new="$(grep -E '^ACTIVATION ' "$TMP/out-new" | tail -1)"
    if cmp -s "$TMP/out-old" "$TMP/out-new"; then same=yes; else same=NO; fi
    printf '| `%s` | %s | %s | %s | %s | %s |\n' "$(basename "$f" .json)" "$d_old" "$d_new" "$v_old" "$v_new" "$same"
    cp "$TMP/out-old" "$TMP/out-old-$(basename "$f" .json)"
    cp "$TMP/out-new" "$TMP/out-new-$(basename "$f" .json)"
done
echo
echo "## Per-scenario sustained2x (CHOSEN, 2R, load-gated concurrency 10/25/50/100)"
echo
echo "N30 = floor(rate x 30); a scenario is a long window when scheduledEvents > N30. ratio = achievedRate / offeredRate;"
echo "over30 = (completed / scheduled) x N30 / ((N30 - 1) + rate x lag), read only for a long window."
echo
echo "| Input | Scenario | scheduled | N30 | window | lag ms | ratio | over30 | sustained2x @$OLD | sustained2x @$NEW | changed |"
echo "|---|---|---:|---:|---|---:|---:|---:|---|---|---|"
for i in "${INPUTS[@]}"; do
    f="${i%%|*}"; b="$(basename "$f" .json)"
    jq -r '
        (.harness.expectedRate) as $r
        | .scenarios[]
        | select(.mode == "CHOSEN" and .offeredRate != null and (.offeredRate | round) == ((2 * $r) | round)
                 and (.concurrency as $c | [10, 25, 50, 100] | index($c)) != null)
        | ((.offeredRate * 30) | floor) as $n
        | [.name, (.scheduledEvents // "absent"), $n,
           (if (.scheduledEvents // 0) > $n and $n >= 2 then "long" else "<= 30 s" end),
           (.terminalLagMs // "absent"),
           (if .achievedRate == null then "null" else .achievedRate / .offeredRate end),
           (if (.scheduledEvents // 0) > $n and $n >= 2 and .completedEvents != null and .terminalLagMs != null
            then ((.completedEvents / .scheduledEvents) * $n) / (($n - 1) + .offeredRate * .terminalLagMs / 1000) else "-" end)]
        | map(if . == null then "null" else tostring end) | join("|")' "$f" |
    while IFS='|' read -r name sched n win lag ratio over; do
        o="pass"; grep -qE "^D-benchmark[[:space:]].*sustained2x: $name " "$TMP/out-old-$b" && o="BLOCK"
        w="pass"; grep -qE "^D-benchmark[[:space:]].*sustained2x: $name " "$TMP/out-new-$b" && w="BLOCK"
        if [ "$o" = "$w" ]; then ch="no"; else ch="YES"; fi
        printf '| `%s` | `%s` | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n' "$b" "$name" "$sched" "$n" "$win" "$lag" "$ratio" "$over" "$o" "$w" "$ch"
    done
done
