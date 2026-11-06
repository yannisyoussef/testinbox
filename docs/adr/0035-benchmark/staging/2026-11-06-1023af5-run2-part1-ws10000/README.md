# ADR-035 §11 gate D run 2 part 1 (ws10000) on the staging host class: 1023af5, 2026-11-06 (raw evidence)

> **Probe output anomaly: app to explain.** Four probe `done` lines in `gate.log` print a negative T1 p99. They are reported here
> exactly as printed, with no interpretation:
>
> ```
> 03:33:12.273 ... scenario chosen-ws31-c100-r520-probe-rep1 done: offered 520.0 achieved 361.9 T1 p99 -1.530989770067168E9 ms, lock timeouts 0, deadlocks 0
> 04:23:47.539 ... scenario chosen-ws31-c100-r520-probe-rep2 done: offered 520.0 achieved 441.9 T1 p99 -1.534058771958922E9 ms, lock timeouts 0, deadlocks 0
> 04:54:59.085 ... scenario chosen-ws31-c100-r520-probe-rep3 done: offered 520.0 achieved 439.2 T1 p99 -1.535962763767605E9 ms, lock timeouts 0, deadlocks 0
> 05:47:30.545 ... scenario chosen-ws31-c100-r520-probe-rep4 done: offered 520.0 achieved 390.9 T1 p99 -1.539083355462514E9 ms, lock timeouts 0, deadlocks 0
> ```

This directory holds the raw evidence of gate D run 2 part 1. Ops ran it at the app team's request in #124, comment 6673356486
(branch `app/fasttrack-gate-d-partitioned`, draft #136).

- **Split.** Run 2 was split by population, and this part ran `--workspaces 10000` only.
  - The full run would have overlapped the scheduled unattended-upgrade window (`apt-daily-upgrade.timer` at 05:24:08Z,
    `apt-daily.timer` at 05:40:36Z).
  - Running ws10000 alone is the app's own fallback in 6673356486. The apt timers were not changed.
  - The app team later cancelled part 2 (ws200) (#124 6677644710).
- **Setup.** Otherwise the setup is identical to runs 1 and c10dd3d.
- **Scope.** This is a staging-host run, a LAB fact and not production evidence.
- **No edits.** Nothing here was edited or recomputed. The only exceptions are the `checks/` files, which Ops generated from
  the run's own logs, the journal and the evidence.

| Item | Value |
|---|---|
| Candidate | `1023af5dc7c9216a166ea3a629cbee661292e3d7`. Clean detached worktree; evidence `gitSha` is the same. `benchmark-0.1.0-SNAPSHOT.jar` sha256 `8caf17981f4276f19bb77f119e6ddac469225dc5dd26cd9bdc646292b7e5a489` |
| Host | staging host `vmi2932906` (Contabo VPS, 8 vCPU, 23 GiB), Linux 6.8.0-147-generic, amd64 |
| Run | 2026-11-06 01:05:43Z to 06:04:31Z (298 min 48 s). 47 scenarios: 20 gate, then the variant's 27 (10 PARTITIONED, 1 hot, 16 timing probes) |
| Harness verdict, as printed | `VERDICT: FAIL` / `ADR REVIEW REQUIRED (per-node escrow is the named fallback, not implemented)`. Exit 1, `adrEvidence: true`, no `adrEvidenceReasons`, no `incompleteReasons` |
| Variant `informationalVerdict` | `INCOMPLETE` (PARTITIONED, reported apart, never gate D evidence) |
| Gate D (`check-storage-activation.sh --mode ALL`, same commit, 06:05:11Z) | `D-benchmark BLOCKED`, `ACTIVATION BLOCKED` (`gate-d/gate-all.out`) |
| t1Probe check | `readsFootprint: true` in 47 of 47 scenarios (20 gate + 27 variant). The run is not void on this check (`checks/readsfootprint-check.txt`) |
| Connections | At most 219 backends of `max_connections` 300 (3 reserved), during the timing probes. No refusal and no exhaustion (`samples/conncount.log`, every 5 s) |
| CPU steal | 0 % in every 5 s interval of the run (`vmstat` `st`, `mpstat` `%steal`) |
| Host contamination | Yes, 4 of 47 scenarios are marked NOT REPRESENTATIVE. See below |

## Exact gate command

The run went through `commands/gate.sh`, which calls `commands/run.sh`; `commands/start.sh` starts it with the samplers.

```
docker run --rm --name ws-b3-gate --network ws-b3-net --env-file <0600 env file> \
  -u <uid>:<gid> -v <1023af5 worktree>:/src:ro -v <out dir>:/out -w /src \
  eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea \
  backend/benchmark/build/install/storage-benchmark/bin/storage-benchmark \
  --jdbc-url jdbc:postgresql://ws-b3-pg:5432/testinbox_bench_gate --username bench \
  --create-database --out /out \
  --workspaces 10000 --partitioned-variant --probe-repetitions 4 --probe-duration-seconds 60 \
  --host-class "staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB)"
```

- **Env file.** It set `TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK`, `TESTINBOX_BENCHMARK_DB_PASSWORD` and `TESTINBOX_GIT_SHA`. Only
  the names are recorded here. The password was generated for the disposable database and has been deleted.
- **Database.** `postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685` (PostgreSQL 16.15), started
  by `commands/pgup.sh`.
  - The only command-line setting was `-c max_connections=300`. There was no `shared_preload_libraries` change, and no memory
    limit.
  - It used the `--internal` network `ws-b3-net` and volume `ws-b3-pgdata`. The container, network and volume were removed
    after the run.
- **Build.** The harness was built with `commands/build.sh` (`./gradlew --no-daemon -q :benchmark:installDist`, same JDK image,
  JDK 25.0.3).

## Host contamination

- **Pre-check.** At 01:05:30Z the host was quiet: load1 1.8, 88 % idle, no apt/dpkg, no Gradle or test JVMs, no lab containers.
- **Known external windows** (each extended by a 2-minute tail, as the coordinator asked):
  - **02:29:30Z–02:31:40Z:** the coordinator ran `sudo grep -r` over `/home`, `/etc`, `/root`, `/srv` and `/usr/local` on
    this host (disk I/O plus one CPU). This was reported by the coordinator.
  - **05:24:13Z–05:24:53Z:** `apt-daily-upgrade.service`, from the journal. The unattended-upgrades log reports "No packages
    found that can be upgraded unattended" (`checks/apt-journal-systemd.txt`, `checks/unattended-upgrades-20261106.txt`;
    the unattended-upgrades log is in host local time, CET = UTC+1).
  - **05:40:46Z–05:41:38Z:** `apt-daily.service`, from the journal.
- **Method.** The same rules as run 1, run with `commands/contamination.py`:

  ```
  contamination.py run2 bench2 --day 2026-11-06 --label "gate D run 2 part 1 (ws10000, 1023af5)" \
    --known 2026-11-06T02:29:30,2026-11-06T02:33:40,... --known 2026-11-06T05:24:13,2026-11-06T05:26:53,... \
    --known 2026-11-06T05:40:46,2026-11-06T05:43:38,...
  ```

  `bench2` is the c10dd3d run's logs (#129). No sample was flagged by the measured rules (A/B) in this run.
- **Load.** load1 mean 20.7, median 10.5, p90 63.8, max 109.3. The max came during the timing probes, which run two event
  streams at concurrency 100 each. The CPU that is not the benchmark was median 1.09 cores, p95 2.72; for c10dd3d it was
  1.75 and 3.69.

**NOT REPRESENTATIVE.** These scenarios overlap a known window or its tail. Their raw figures are unchanged in `evidence.json`,
and the app team decides whether any must be repeated:

| Scenario | Start–done (UTC) | Window |
|---|---|---|
| reference-ws10000-c100-r520 | 02:27:24–02:31:39 | coordinator `grep -r` |
| partitioned-ws31-c1-r260 | 02:31:54–02:37:27 | coordinator `grep -r`, 2-minute tail |
| partitioned-ws31-c100-r520-probe-rep4 | 05:22:13–05:31:29 | apt-daily-upgrade |
| chosen-ws31-c100-r520-probe-rep4 | 05:31:49–05:47:30 | apt-daily |

The other 43 scenarios are clean by this rule.

## Files

| Path | What it is | Origin |
|---|---|---|
| `evidence.json`, `SUMMARY.md`, `diagnostics-ws10000.txt` | Harness output, verbatim | harness `--out` (`20261106-010547/`) |
| `gate.log` | Harness console log, ending `rc=1` | `commands/gate.sh` |
| `gate-d/gate-all.out`, `gate-d/gate-all.json` | `scripts/check-storage-activation.sh --mode ALL` of this evidence, at 1023af5 | the app's gate |
| `pg-settings.txt` | PostgreSQL version and settings of `ws-b3-pg`, taken after the run | psql |
| `checks/readsfootprint-check.txt` | `t1Probe` fields of all 47 scenarios and the result | `commands/readsfootprint_check.py` |
| `checks/host-contamination.md` | Contamination map: profiles, flagged windows, load1 > 15.1 windows, scenario map | `commands/contamination.py` |
| `checks/apt-journal-systemd.txt`, `checks/unattended-upgrades-20261106.txt` | apt service start/end lines (UTC) and the unattended-upgrades log lines of the run | journalctl, `/var/log/unattended-upgrades` |
| `samples/vmstat.log`, `samples/mpstat.log` | Steal, every 5 s, for the whole run | `commands/start.sh` |
| `samples/load.log`, `samples/dstats.log`, `samples/pgsample.log` | Host load, container stats, lock samples | `commands/loadlog.sh`, `dstats.sh`, `pgsample.sh` |
| `samples/conncount.log` | Backends and `max_connections`, every 5 s | `commands/conncount.sh` |
| `commands/*` | The scripts as run. Absolute paths are the Ops workspace on the staging host | |
| `SHA256SUMS` | sha256 of every other file in this directory | |

Verify with `sha256sum -c SHA256SUMS` from this directory.

## Not included

The build log, nohup files, PID and created-object lists, the pre-check output (it lists every container on the host) and
the Ops working notes.
