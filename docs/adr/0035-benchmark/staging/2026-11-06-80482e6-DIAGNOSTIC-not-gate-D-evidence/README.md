# DIAGNOSTIC: not gate D evidence — 80482e6, staging host class, 2026-11-06 (raw)

**DIAGNOSTIC: not gate D evidence.** This run used diagnostic-only PostgreSQL settings and a reduced matrix, so it can never be
gate evidence. **Run 3 stays FAIL.**

Ops ran it at the app team's request in #124, comment 6685467213 (owner-approved in 6684712608): explain run 3's lock tail and
its stalls. It is a staging-host LAB fact. Nothing here was edited or recomputed. The only exceptions are the `checks/` files,
which Ops generated from the run's logs and evidence.

| Item | Value |
|---|---|
| Commit | `80482e663eb8a8eed9a412357d3b67939af6f787` (#137 candidate). Clean detached worktree; evidence `gitSha` is the same |
| Harness jar | `benchmark-0.1.0-SNAPSHOT.jar` sha256 `8e5c376905644d8efcf387ed567eea013c48267d9c8fe5603cf681dfe3e3367d`, byte-identical to run 3's (6347627) build |
| Host | staging host `vmi2932906` (Contabo VPS, 8 vCPU, 23 GiB) |
| Run | 2026-11-06 13:12:33Z to 13:24:19Z, 8 scenarios |
| Harness verdict, as printed | `VERDICT: INCOMPLETE`, exit 3, `adrEvidence: false`. The run departs from the §11 matrix, as expected (`SUMMARY.md`) |
| t1Probe check | `readsFootprint: true` in 8 of 8 (`checks/readsfootprint-check.txt`) |
| Host | Quiet. Before the start, load1 stayed below 4 with apt idle for 2 min (`checks/settle.log`). During the run, load1 max 9.1 and steal 0 %. No contamination found (`checks/host-contamination.md`). At most 118 backends |

## Command

```
docker run --rm --name ws-b3-gate --network ws-b3-net --env-file <0600 env file> \
  -e JAVA_OPTS='-Xlog:gc*,safepoint:file=/out/gc.log:time,uptime,level,tags' \
  -u <uid>:<gid> -v <80482e6 worktree>:/src:ro -v <out dir>:/out -w /src \
  eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea \
  backend/benchmark/build/install/storage-benchmark/bin/storage-benchmark \
  --jdbc-url jdbc:postgresql://ws-b3-pg:5432/testinbox_bench_diag --username bench \
  --create-database --out /out \
  --workspaces 200,10000 --expected-rate 31 --rates 62 --concurrency 10,100 \
  --duration-seconds 60 --warmup-seconds 5 \
  --host-class "staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB) DIAGNOSTIC"
```

The env file set `TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK`, `TESTINBOX_BENCHMARK_DB_PASSWORD` and `TESTINBOX_GIT_SHA`; only the
names are recorded here. The password has been deleted, and a scan for it found nothing in any file here.

## PostgreSQL (diagnostic-only settings)

- **Image and settings.** `postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`, fresh and
  disposable, started by `commands/pgup.sh` with `-c max_connections=300` plus exactly the settings listed in 6685467213:
  - `shared_preload_libraries=pg_stat_statements,auto_explain`, `pg_stat_statements.track=all`, `track_planning=on`;
  - `auto_explain.log_min_duration=40ms`, with `log_analyze`, `log_buffers`, `log_timing` and `log_nested_statements` on;
  - `track_io_timing=on`, `log_checkpoints=on`, `log_autovacuum_min_duration=0`, `log_lock_waits=on`, `deadlock_timeout=50ms`;
  - `log_min_duration_statement=40ms`, `log_line_prefix='%m [%p] %a '`.

  `pg-settings.txt` confirms them.
- **Server log.** `postgres-server.log` is the full server log: `docker logs -t` of the container, which carried a one-file
  2 GB log limit so nothing rotated away.
- **Isolation.** There was no memory limit. The database used the `--internal` network `ws-b3-net`. The container, network and
  volume were removed.

## pg_stat dumps (`pg-stat-dumps/`)

`commands/statdump.sh` takes each dump. A dump holds three things:
- `SELECT * FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 40`;
- `pg_stat_user_tables` of `testinbox_bench_diag`;
- `pg_stat_bgwriter`.

When the dumps were taken:
- **After each population**, as requested: `population-end-ws200-c100-r62.txt` and `population-end-ws10000-c100-r62.txt`.
  Each is taken at the population's last `done` line.
- **Extra:** one dump after every scenario (`after-<scenario>.txt`), and one after the run ended (`after-run-end.txt`).
- **Reset caveat.** The harness resets `pg_stat_statements` before each scenario's measured phase (`ScenarioRunner.kt:154`).
  Each dump therefore covers the statements since the last reset: that scenario, plus whatever ran after it up to the dump.

## Files

| Path | What it is |
|---|---|
| `evidence.json`, `SUMMARY.md`, `diagnostics-ws200.txt`, `diagnostics-ws10000.txt` | Harness output, verbatim |
| `gc.log` | JVM GC and safepoint log of the harness (`JAVA_OPTS` above) |
| `postgres-server.log` | Full PostgreSQL server log (stderr), with docker timestamps |
| `pg-stat-dumps/*.txt` | The pg_stat dumps above |
| `gate.log` | Harness console log, ending `rc=3` |
| `pg-settings.txt` | Settings of the disposable database, taken after the run |
| `checks/` | t1Probe check, contamination map (run 1 rules), settle log |
| `samples/` | vmstat and mpstat (steal), load, dstats, pgsample and conncount, for the whole run |
| `commands/` | The scripts as run |
| `SHA256SUMS` | sha256 of every other file |
