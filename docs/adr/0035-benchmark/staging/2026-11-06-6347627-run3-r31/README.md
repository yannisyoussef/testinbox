# ADR-035 §11 gate D run 3 (route D + A, R = 31) on the staging host class: 6347627, 2026-11-06 (raw evidence)

This directory holds the raw evidence of gate D run 3. Ops ran it at the app team's request in #124, comment 6677644710. The
candidate is the #137 head, and the run replaces run 2 part 2.

- **Setup.** Identical to runs 1 and 2. There was no `--partitioned-variant` and there were no probes.
- **Scope.** This is a staging-host run, a LAB fact and not production evidence.
- **No edits.** Nothing here was edited or recomputed. The only exceptions are the `checks/` files, which Ops generated from
  the run's own logs and evidence.

| Item | Value |
|---|---|
| Candidate | `634762769d64ee9a8f72a8c119ab171f41d29817`. Clean detached worktree; evidence `gitSha` is the same. `benchmark-0.1.0-SNAPSHOT.jar` sha256 `8e5c376905644d8efcf387ed567eea013c48267d9c8fe5603cf681dfe3e3367d` (built 06:05:31Z) |
| T1 statement | `t1Probe.snapshotStatementSha256` = `0135227453cb5afa256e598593e7d421f36a1c90174d816b7c98b92e59fd6b2a` in all 40 scenarios (CHOSEN `JdbcStorageAdmission`, NO_LOCK `NoLockAdmissionStore`) |
| Host | staging host `vmi2932906` (Contabo VPS, 8 vCPU, 23 GiB), Linux 6.8.0-147-generic, amd64 |
| Run | 2026-11-06 06:10:28Z to 06:39:11Z (28 min 43 s), 40 scenarios |
| Harness verdict, as printed | `VERDICT: INCOMPLETE`, exit 3, `adrEvidence: true`, no `adrEvidenceReasons`. Ten `incompleteReasons`: "only 93 retention samples for chosen-ws{200,10000}-c{1,10,25,50,100}-r62 (minimum 100)" (`SUMMARY.md`) |
| Gate D (`check-storage-activation.sh --mode ALL --expected-rate 31`, same commit, 06:39:53Z) | `D-benchmark BLOCKED`: "no metrics endpoint: R_gated (testinbox_storage_gated_expected_rate) is unobserved, so §11's expected rate is unknown". `ACTIVATION BLOCKED` (`gate-d/gate-all.out`) |
| t1Probe check | `readsFootprint: true` in 40 of 40 scenarios. The run is not void on this check (`checks/readsfootprint-check.txt`) |
| Connections | At most 118 backends of `max_connections` 300 (`samples/conncount.log`) |
| CPU steal | 0 % in every 5 s interval (`vmstat` `st`, `mpstat` `%steal`) |
| Host contamination | None found. 0 of 40 scenarios are marked (`checks/host-contamination.md`) |

## Exact gate command

The run went through `commands/gate.sh`, which calls `commands/run.sh`; `commands/start.sh` starts it with the samplers.

```
docker run --rm --name ws-b3-gate --network ws-b3-net --env-file <0600 env file> \
  -u <uid>:<gid> -v <6347627 worktree>:/src:ro -v <out dir>:/out -w /src \
  eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea \
  backend/benchmark/build/install/storage-benchmark/bin/storage-benchmark \
  --jdbc-url jdbc:postgresql://ws-b3-pg:5432/testinbox_bench_gate --username bench \
  --create-database --out /out \
  --workspaces 200,10000 --expected-rate 31 --rates 31,62 \
  --host-class "staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB)"
```

- **Gate D.** At the same commit: `scripts/check-storage-activation.sh --mode ALL --benchmark-evidence evidence.json
  --expected-rate 31 --json gate-all.json`.
- **Env file.** It set `TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK`, `TESTINBOX_BENCHMARK_DB_PASSWORD` and `TESTINBOX_GIT_SHA`. Only
  the names are recorded here. The password was generated for the disposable database and has been deleted.
- **Database.** `postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685` (PostgreSQL 16.15), started
  by `commands/pgup.sh` at 06:08Z.
  - The only command-line setting was `-c max_connections=300`. There was no `shared_preload_libraries` change, so
    `pg_stat_statements` is unavailable. There was no memory limit.
  - It used the `--internal` network `ws-b3-net` and volume `ws-b3-pgdata`. All three were removed after the run.
- **Build.** The harness was built with `commands/build.sh` (`./gradlew --no-daemon -q :benchmark:installDist`, same JDK image,
  JDK 25.0.3).

## Quiet host

- **Settling.** Before the start, load1 stayed below 4 and apt-daily/apt-daily-upgrade were inactive for 2 minutes
  (`checks/settle.log`).
- **Pre-check at 06:10:21Z.** load1 1.9, no apt/dpkg, no Gradle or test JVMs, no lab containers.
- **During the run.** load1 mean 2.4, median 2.4, p90 3.0, max 3.8. The CPU that is not the benchmark was median 1.00 cores,
  p95 2.73.
- **Contamination.** No sample was flagged by the run 1 rules, and no external window was reported.

## Files

| Path | What it is | Origin |
|---|---|---|
| `evidence.json`, `SUMMARY.md`, `diagnostics-ws200.txt`, `diagnostics-ws10000.txt` | Harness output, verbatim | harness `--out` (`20261106-061032/`) |
| `gate.log` | Harness console log, ending `rc=3` | `commands/gate.sh` |
| `gate-d/gate-all.out`, `gate-d/gate-all.json` | `scripts/check-storage-activation.sh --mode ALL --expected-rate 31` of this evidence, at 6347627 | the app's gate |
| `pg-settings.txt` | PostgreSQL version and settings of `ws-b3-pg`, taken after the run | psql |
| `checks/readsfootprint-check.txt` | `t1Probe` fields of all 40 scenarios and the result | `commands/readsfootprint_check.py` |
| `checks/host-contamination.md` | Contamination map with the run 1 rules (reference: the c10dd3d logs, #129) | `commands/contamination.py run3 bench2 --day 2026-11-06 --label "gate D run 3 (6347627, R=31)"` |
| `checks/settle.log` | load1 and apt service state every 10 s before the start | inline loop |
| `samples/vmstat.log`, `samples/mpstat.log` | Steal, every 5 s, for the whole run | `commands/start.sh` |
| `samples/load.log`, `samples/dstats.log`, `samples/pgsample.log`, `samples/conncount.log` | Host load, container stats, lock samples, backend counts | `commands/*.sh` |
| `commands/*` | The scripts as run. Absolute paths are the Ops workspace on the staging host | |
| `SHA256SUMS` | sha256 of every other file in this directory | |

Verify with `sha256sum -c SHA256SUMS` from this directory.

## Not included

The build log, nohup files, PID and created-object lists, the pre-check output (it lists every container on the host) and
the Ops working notes.
