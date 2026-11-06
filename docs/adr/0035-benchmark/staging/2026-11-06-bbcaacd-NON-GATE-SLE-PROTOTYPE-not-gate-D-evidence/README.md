# NON-GATE SLE PROTOTYPE: not gate D evidence — bbcaacd, staging host class, 2026-11-06 (raw)

**NON-GATE SLE PROTOTYPE: not gate D evidence.** Run 3 stays FAIL. The 124/s run is labelled **STRESS DIAGNOSTIC: not a
criterion**.

Ops ran it at the app team's request in #124, comment 6691877477 (`app/fasttrack-sle-prototype`). It is a staging-host LAB
fact. Nothing here was edited or recomputed. The only exception is `checks/host-contamination.md`, which Ops generated from the
logs.

| Item | Value |
|---|---|
| Commit | `bbcaacd7fab03d4819f89523deae77f37fc6876d`. Clean detached worktree; evidence `gitSha` is the same in every run |
| Harness jar | `benchmark-0.1.0-SNAPSHOT.jar` sha256 `150a1de953bc6e44c01ed033189f510a2b5078cddbabf46ee91b5ca110514528`, built with `commands/build.sh` in `eclipse-temurin@sha256:201fbb88…` |
| PostgreSQL | **A fresh disposable container per run** (`commands/runs.sh`): `postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`, `--internal` network, no memory limit, `-c max_connections=300`. **Run lt30 only** added `-c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track_planning=on`. The configuration per run is in `runs.log` ("pg up … command-line settings") and in each run's `pg-settings.txt` |
| Database names | `ti_sle_bench_lt30`, `ti_sle_bench_prod`, `ti_sle_bench_124`, `ti_sle_bench_warm`. The request's names (`ti_sle_lt30`, …) lack `bench`, which the harness preflight requires (`SafetyPreflight.kt:48`), so `bench` was added. Nothing else differs |
| Host | Quiet. Before the start, load1 stayed below 4 for 2 min, apt was idle, and no other ws/lab/n1 container was running (`checks/settle.log`). During the runs, load1 max 33.1, steal 0 %, at most 119 backends. No contamination found (`checks/host-contamination.md`) |

## Runs (in order, `runs.log`)

| Dir | Window (UTC) | Harness arguments (after `--jdbc-url … --create-database --out /out/sle-<tag>`) | Printed |
|---|---|---|---|
| `run-lt30-decision` | 18:13:22–18:36:26 | `--focused-cohort --escrow-variant --rates 31,62 --concurrency 10,25,50,100 --duration-seconds 60 --pool-max-lifetime 30s` | `VERDICT: INCOMPLETE`, rc 3, `adrEvidence: false` |
| `run-prod` | 18:36:27–18:48:11 | as lt30 with `--rates 62` and no `--pool-max-lifetime` | `VERDICT: INCOMPLETE`, rc 3 |
| `run-124-STRESS-DIAGNOSTIC-not-a-criterion` | 18:48:13–19:09:59 | as lt30 with `--rates 124`, host-class "… (NON-GATE SLE prototype, STRESS DIAGNOSTIC)" | `VERDICT: INCOMPLETE`, rc 3 |
| `run-warm-control` | 19:10:01–19:24:30 | as lt30 with `--warm-pool --rates 62` | `VERDICT: INCOMPLETE`, rc 3 |

Every run used host-class "staging-host vmi2932906 (NON-GATE SLE prototype)", except run 124 as noted. The env file set
`TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK`, `TESTINBOX_BENCHMARK_DB_PASSWORD` and `TESTINBOX_GIT_SHA`. The password has been deleted,
and every container, network and volume was removed.

## Files

| Path | What it is |
|---|---|
| `run-*/ESCROW.md`, `FOCUSED.md`, `SUMMARY.md`, `evidence.json`, `diagnostics-cohort.txt` | Harness output, verbatim |
| `run-*/pg-settings.txt` | That run's database settings, taken after the run |
| `runs.log` | Console log of all runs, including each database's command-line settings |
| `checks/` | Contamination map (run 1 rules), settle log |
| `samples/` | vmstat and mpstat (steal), load, dstats, pgsample and conncount, for the whole session. The pg samplers re-attach to each fresh database (`# attach` / `# detach` lines) |
| `commands/` | The scripts as run |
| `SHA256SUMS` | sha256 of every other file |
