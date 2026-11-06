# FOCUSED P ASSESSMENT: not gate D evidence — 80482e6, staging host class, 2026-11-06 (raw)

**FOCUSED P ASSESSMENT: not gate D evidence.** This is never to be cited as a P PASS. The P criteria are not adopted, and the
probe output is not read for the timing criterion.

Ops ran it at the app team's request in #124, comment 6685821689 (P paper item (e), preparation and assessment only;
6684712608). It is a staging-host LAB fact. Nothing here was edited or recomputed. The only exceptions are the `checks/` files,
which Ops generated from the run's logs and evidence.

| Item | Value |
|---|---|
| Commit | `80482e663eb8a8eed9a412357d3b67939af6f787`. Clean detached worktree; evidence `gitSha` is the same |
| Harness jar | `benchmark-0.1.0-SNAPSHOT.jar` sha256 `8e5c376905644d8efcf387ed567eea013c48267d9c8fe5603cf681dfe3e3367d` (the same build as the diagnostic run) |
| PostgreSQL | `postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`, fresh and disposable, **only** `-c max_connections=300`. No diagnostic settings; `shared_preload_libraries` is empty (`pg-settings.txt`). No memory limit, `--internal` network |
| Run | 2026-11-06 13:29:46Z to 13:47:06Z. 11 scenarios: 4 gate (CHOSEN and NO_LOCK at r62, c10 and c100) and 7 variant (PARTITIONED c10 and c100, hot, 4 probe runs) |
| Harness verdict, as printed | `VERDICT: INCOMPLETE`, exit 3, `adrEvidence: false` (a reduced matrix, as expected). Variant `informationalVerdict`: `INCOMPLETE` |
| t1Probe check | `readsFootprint: true` in 11 of 11 (`checks/readsfootprint-check.txt`) |
| Host | Quiet. Before the start, load1 stayed below 4 with apt idle for 2 min (`checks/settle.log`). During the run, load1 max 19.1 and steal 0 %. At most 218 backends of 300. No contamination found (`checks/host-contamination.md`) |

## Command

```
docker run --rm --name ws-b3-gate --network ws-b3-net --env-file <0600 env file> \
  -u <uid>:<gid> -v <80482e6 worktree>:/src:ro -v <out dir>:/out -w /src \
  eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea \
  backend/benchmark/build/install/storage-benchmark/bin/storage-benchmark \
  --jdbc-url jdbc:postgresql://ws-b3-pg:5432/testinbox_bench_pfocus --username bench \
  --create-database --out /out \
  --workspaces 10000 --expected-rate 31 --rates 62 --concurrency 10,100 \
  --duration-seconds 60 --warmup-seconds 5 \
  --partitioned-variant --probe-repetitions 1 --probe-duration-seconds 60 \
  --host-class "staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB) FOCUSED-P"
```

The env file set `TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK`, `TESTINBOX_BENCHMARK_DB_PASSWORD` and `TESTINBOX_GIT_SHA`. Only the
names are recorded here. The password has been deleted. The container, network and volume were removed.

## Files

| Path | What it is |
|---|---|
| `evidence.json`, `SUMMARY.md`, `diagnostics-ws10000.txt` | Harness output, verbatim (`20261106-132951/`) |
| `gate.log` | Harness console log, ending `rc=3` |
| `pg-settings.txt` | Settings of the disposable database, taken after the run |
| `checks/` | t1Probe check, contamination map (run 1 rules), settle log |
| `samples/` | vmstat and mpstat (steal), load, dstats, pgsample and conncount, for the whole run |
| `commands/` | The scripts as run |
| `SHA256SUMS` | sha256 of every other file |
