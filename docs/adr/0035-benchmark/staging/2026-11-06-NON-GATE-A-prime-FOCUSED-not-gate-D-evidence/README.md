# NON-GATE A′ FOCUSED: not gate D evidence — 82eeee3 (BASE) / 7b09bcb (CANDIDATE), staging host class, 2026-11-06 (raw)

**NON-GATE A′ FOCUSED: not gate D evidence.** This is for the A′ checkpoint (6684712608, 2026-11-07 12:00Z). **Run 3 stays FAIL.**

Ops ran it at the app team's request in #124, comment 6687011418 (branch `app/fasttrack-gate-d-a-prime`). It is a staging-host
LAB fact. Nothing here was edited or recomputed. The only exception is `checks/host-contamination.md`, which Ops generated from
the run's logs.

| Item | Value |
|---|---|
| BASE | `82eeee339100d185b9d3d200d073d520b0d2cbb7`, jar sha256 `80e5b4ca471e3123f4ac75b6574cff9c91554df6667940502027b6304cefb479`, T1 `snapshotStatementSha256` `01352274…` |
| CANDIDATE | `7b09bcbb0bde59cd02957aba9a15cb58e3354beb`, jar sha256 `c6abb78489eff3cfe69aac03794b50413557eb6f6a960c98ea4a24a34fdf104d`, T1 `snapshotStatementSha256` `a422b636…` |
| Build | Each ref in its own clean detached worktree, `commands/build.sh` (`./gradlew --no-daemon -q :benchmark:installDist` in `eclipse-temurin@sha256:201fbb88…`) |
| PostgreSQL | `postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`, one fresh disposable container for all 8 runs, `--internal` network, no memory limit. For this throwaway DB only: `-c max_connections=300 -c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track_planning=on` (`pg-settings.txt`). Each run used its own database, `testinbox_bench_focused_<i>` |
| Runs | 2026-11-06 15:20:56Z to 15:49:14Z: 8 runs in the requested order (`runs.log`). Each prints `VERDICT: INCOMPLETE` (exit 3) with `adrEvidence: false`; the focused runs are non-gate by design. Each evidence `gitSha` matches its ref |
| t1Probe | `readsFootprint: true` in every scenario of every run (16 of 16) |
| Host | Quiet. Before the start, load1 stayed below 4 for 2 min with apt idle, and the lab container `etrn-lab-reload` was gone (`checks/settle.log`). During the runs, load1 max 9.8, steal 0 %, at most 118 backends. No contamination found (`checks/host-contamination.md`) |

## Command (`commands/runs.sh`)

```
for MODE in --focused-cohort --focused; do for REF in $BASE $CAND $BASE $CAND; do i=$((i+1))
  docker run --rm --name ws-b3-aprime-$i --network ws-b3-net --env-file <0600 env: ACK + DB password> -e TESTINBOX_GIT_SHA=$REF \
    -u <uid>:<gid> -v <worktree for $REF>:/src:ro -v <out>/run$i:/out -w /src $JDK \
    backend/benchmark/build/install/storage-benchmark/bin/storage-benchmark \
    --jdbc-url jdbc:postgresql://ws-b3-pg:5432/testinbox_bench_focused_$i --username bench --create-database --out /out \
    --host-class "staging-host vmi2932906 (NON-GATE A' focused, $MODE, ${REF:0:7})" $MODE
done; done
```

The password was generated for the disposable database and has been deleted. The container, network and volume were removed.

## Files

| Path | What it is |
|---|---|
| `run<i>-<mode>-<ref>/FOCUSED.md` | The harness's focused report, verbatim |
| `run<i>-<mode>-<ref>/SUMMARY.md`, `evidence.json`, `diagnostics-*.txt` | Harness output, verbatim |
| `runs.log` | Console log of all 8 runs, with start/end and rc per run |
| `pg-settings.txt` | Settings of the disposable database, taken after the runs |
| `checks/` | Contamination map (run 1 rules), settle log |
| `samples/` | vmstat and mpstat (steal), load, dstats, pgsample and conncount, for the whole session |
| `commands/` | The scripts as run |
| `SHA256SUMS` | sha256 of every other file |
