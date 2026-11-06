# ADR-035 §11 gate D run 1 on the staging host class: 9d06ab2, 2026-11-05 (raw evidence)

This directory holds the raw evidence of gate D run 1. Ops ran it at the app team's request in #124, comment 6667610970
(branch `app/fasttrack-gate-d-step1`, draft #135). The setup is the same as the c10dd3d run (#129). Only the worktree changes,
and `--partitioned-variant` is added.

This is a staging-host run, so it is a LAB fact and not production evidence. It does not change the ADR text, the thresholds or
the harness. Nothing in this directory was edited or recomputed. The only exceptions are the two `checks/` files, which are
Ops-generated from the run's own logs and evidence.

| Item | Value |
|---|---|
| Candidate | `9d06ab213b9e07bc25ea2477d87078135f4f2a94` (`app/fasttrack-gate-d-step1`). The harness was built from a clean detached worktree at that commit. The evidence `gitSha` is the same |
| Host | staging host `vmi2932906` (Contabo VPS, 8 vCPU, 23 GiB), Linux 6.8.0-147-generic, amd64 |
| Run | 2026-11-05 20:47:48Z to 2026-11-06 00:55:55Z (248 min 7 s): 40 gate scenarios and 20 PARTITIONED variant scenarios |
| Harness verdict (as printed) | `VERDICT: FAIL` / `ADR REVIEW REQUIRED (per-node escrow is the named fallback, not implemented)`. Exit 1, `adrEvidence: true`, no `adrEvidenceReasons`, no `incompleteReasons` |
| Gate D (`check-storage-activation.sh --mode ALL`, same commit) | `D-benchmark BLOCKED`, `ACTIVATION BLOCKED` (`gate-d/gate-all.out`) |
| t1Probe check | `readsFootprint: true` in 60 of 60 scenarios (40 gate + 20 variant). The run is not void on this check (`checks/readsfootprint-check.txt`) |
| CPU steal | 0 % in every 5 s interval of the run, from both `vmstat` (`st`) and `mpstat` (`%steal`) |
| Host contamination | Yes. See "Host contamination" below and `checks/host-contamination.md`. 11 of 60 scenarios are marked NOT REPRESENTATIVE |

## Exact gate command

The run went through `commands/gate.sh`, which calls `commands/run.sh`:

```
docker run --rm --name ws-b3-gate --network ws-b3-net --env-file <0600 env file> \
  -u <uid>:<gid> -v <9d06ab2 worktree>:/src:ro -v <out dir>:/out -w /src \
  eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea \
  backend/benchmark/build/install/storage-benchmark/bin/storage-benchmark \
  --jdbc-url jdbc:postgresql://ws-b3-pg:5432/testinbox_bench_gate --username bench \
  --create-database --out /out \
  --workspaces 200,10000 --partitioned-variant \
  --host-class "staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB)"
```

- **Env file.** It set `TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK`, `TESTINBOX_BENCHMARK_DB_PASSWORD` and `TESTINBOX_GIT_SHA`. Only
  the names are recorded here. The password was generated for the disposable database and has been deleted.
- **Build.** The harness was built with `commands/build.sh`: `./gradlew --no-daemon -q :benchmark:installDist` in the same
  JDK image, JDK 25.0.3. `benchmark-0.1.0-SNAPSHOT.jar` has sha256 `5b31954cf2c293e39f821399f08d3a8ad199b31d82c616502da5f00e6d8328df`.
- **Gate D.** It ran at 2026-11-06T00:56:43Z, from the same worktree:
  `scripts/check-storage-activation.sh --mode ALL --benchmark-evidence evidence.json --json gate-all.json`.

## Database

- **Image.** `postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685` (PostgreSQL 16.15). It was
  started by `commands/pgup.sh`.
- **Settings.** The only command-line setting was `-c max_connections=300`. There was no `shared_preload_libraries` change,
  so the diagnostics record `pg_stat_statements` as unavailable, as the request expected. Every other setting is an image
  default; `pg-settings.txt` lists them.
- **Isolation.** There was no memory limit. The database was a disposable container `ws-b3-pg` with volume `ws-b3-pgdata`,
  on the `--internal` network `ws-b3-net`. The container, network and volume were removed after the run.

## Host contamination

The coordinator confirmed that the host was **not** quiet for part of the run. The pre-check at 20:47:38Z had passed: load 1.06,
no apt/dpkg, no Gradle or test JVMs, no lab containers.

- **Known external cause.** Ops selftest mutant shards, two in parallel, ran on this host from **2026-11-05 22:43:40Z to
  23:18:23Z**. Shorter test and review runs by other agents also occurred at other times. The coordinator kept the host quiet
  from about 23:20Z to the end.
- **Profile.** load1 mean was 10.7, median 7.5, p90 22.4, max 72.4. For c10dd3d, which was quiet, it was mean 8.1 (8.2 in its
  RESULT), median 6.2, p90 15.1, max 40.1. The CPU busy that is not the benchmark was median 1.88 cores, p95 4.33. c10dd3d had
  median 1.75 and p95 3.69. "Not the benchmark" means host busy minus the `docker stats` CPU of `ws-b3-pg` and `ws-b3-gate`.
- **Method.** `commands/contamination.py`, documented at the top of `checks/host-contamination.md`.
  - A 30 s sample is flagged when the non-benchmark CPU stays above the c10dd3d p95 for about 1 min or more.
  - A sample is also flagged when load1 is more than the larger of 1.25× and +3 over c10dd3d's maximum for the same scenario.
  - A scenario is NOT REPRESENTATIVE when it overlaps the known window or contains a flagged sample.
- **Every load1 > 15.1 window.** Each one is listed with its attribution. Inside the known window the attribution is
  EXTERNAL. Outside it, every window coincides with the benchmark's own c25/c50/c100 scenarios, or with the minute after them.
  The PARTITIONED c100 scenarios reached load1 53.9 to 58.7 on their own, including at 00:52–00:55Z when the host was quiet.
- **Flagged windows outside the known one.** 22:35:02–22:35:59Z and 22:42:38–22:43:36Z.

These scenarios are marked **NOT REPRESENTATIVE**. Their latency figures were measured while the host carried external load.
Their raw figures stay in `evidence.json` unchanged:

| Scenario | Start–done (UTC) | Reason |
|---|---|---|
| partitioned-ws200-c10-r520 | 22:34:59–22:38:16 | flagged window 22:35:02–22:35:59 (non-benchmark CPU up to 4.3 cores) |
| partitioned-ws200-c50-r520 | 22:41:52–22:45:38 | known window; flagged window 22:42:38–22:43:36 |
| partitioned-ws200-c100-r520 | 22:45:57–22:50:40 | known window |
| chosen-ws10000-c1-r260 | 22:51:29–22:57:16 | known window |
| reference-ws10000-c1-r260 | 22:57:22–23:03:32 | known window |
| chosen-ws10000-c10-r260 | 23:03:38–23:06:30 | known window |
| reference-ws10000-c10-r260 | 23:06:36–23:08:09 | known window |
| chosen-ws10000-c25-r260 | 23:08:16–23:11:29 | known window |
| reference-ws10000-c25-r260 | 23:11:35–23:13:27 | known window |
| chosen-ws10000-c50-r260 | 23:13:33–23:16:57 | known window |
| reference-ws10000-c50-r260 | 23:17:08–23:18:43 | known window (overlap to 23:18:23) |

The other 49 scenarios are marked clean by this rule. Ops does not decide whether the marked scenarios must be repeated.

## Files

| Path | What it is | Origin |
|---|---|---|
| `evidence.json` | Harness evidence, verbatim | harness `--out` |
| `SUMMARY.md` | Harness summary, verbatim | harness `--out` |
| `diagnostics-ws200.txt`, `diagnostics-ws10000.txt` | Harness diagnostics per population, verbatim | harness `--out` |
| `gate.log` | Harness console log of the run, ending `rc=1` | `commands/gate.sh` |
| `gate-d/gate-all.out`, `gate-d/gate-all.json` | `scripts/check-storage-activation.sh --mode ALL` of this evidence, at 9d06ab2 | the app's gate |
| `pg-settings.txt` | PostgreSQL version and settings of `ws-b3-pg`, taken after the run | psql |
| `checks/readsfootprint-check.txt` | `t1Probe` fields of all 60 scenarios, and the result | `commands/readsfootprint_check.py evidence.json` |
| `checks/host-contamination.md` | Contamination map: reference profiles, flagged windows, every load1 > 15.1 window, the scenario map | `commands/contamination.py` (needs the c10dd3d logs of #129) |
| `samples/vmstat.log` | `vmstat -t -w -n 5`, UTC, for the whole run (column `st` = steal) | `commands/steal.sh` |
| `samples/mpstat.log` | `mpstat 5`, all CPUs, for the whole run (`%steal`) | `commands/steal.sh` |
| `samples/load.log` | Host load every 30 s: loadavg, CPU busy %, top 4 processes, apt/dpkg and Gradle counts | `commands/loadlog.sh` |
| `samples/dstats.log` | `docker stats` of `ws-b3-pg` and `ws-b3-gate` every 30 s | `commands/dstats.sh` |
| `samples/pgsample.log` | Every 2 s: advisory-lock waiters and holders, backends by state, wait event and statement prefix | `commands/pgsample.sh` |
| `commands/*` | The scripts as run. Absolute paths are the Ops workspace on the staging host | |
| `SHA256SUMS` | sha256 of every other file in this directory | |

Verify with `sha256sum -c SHA256SUMS` from this directory.

## Not included

- The build log, nohup files, PID and created-object lists, the pre-check output and stop markers are run mechanics, not
  evidence. The pre-check lists every container on the host.
- The Ops working notes (STATUS.md, RESULT.md).
