# ADR-035 §11 gate D on the staging host class: c10dd3d, 2026-11-03, FAIL (raw evidence)

Ops-prepared raw evidence of the gate D run, committed at the app team's request
(PR #120, comment 6631813062, §2.3 "Raw evidence: yes, commit it"). The Ops report and
the request for ADR review are PR #124, comment 6613516903.

This directory records a staging-host run, which is a LAB fact. It is not production
evidence. It does not change the ADR text, the thresholds or the harness.

| Item | Value |
|---|---|
| Candidate | `c10dd3d50ffee7f226caee453ae9e5e3379852ae` (`feature/ti-delivery-002b`). The harness was built at that commit; evidence `gitSha` is the same |
| Host | staging host `vmi2932906` (Contabo VPS, 8 vCPU, 23 GiB), Linux 6.8.0-147-generic, amd64 |
| Run | 2026-11-03 16:31:04Z to 19:22:22Z (171 min 18 s), 40 scenarios |
| Harness verdict | `VERDICT: FAIL`, exit 1, `adrEvidence: true`, no `adrEvidenceReasons`, no `incompleteReasons` |
| Failed criteria | `sustained2x` (worst 0.0801), `t1P99` (worst 324 737.7 ms), `slotQueueingDeadlineMisses` (1410) |
| Gate D (`check-storage-activation.sh --mode ALL`) | `D-benchmark BLOCKED` (`gate-d/gate-all.out`) |

## Exact gate command

Run through `commands/gate.sh`, which calls `commands/run.sh`
(docs/dev/storage-benchmark.md, "The gate, on the staging host class"):

```
docker run --rm --name ws-b2-gate --network ws-b2-net --env-file <0600 env file> \
  -u <uid>:<gid> -v <c10dd3d worktree>:/src:ro -v <out dir>:/out -w /src \
  eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea \
  backend/benchmark/build/install/storage-benchmark/bin/storage-benchmark \
  --jdbc-url jdbc:postgresql://ws-b2-pg:5432/testinbox_bench_gate --username bench \
  --create-database --out /out \
  --workspaces 200,10000 --host-class "staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB)"
```

- The env file set `TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK`, `TESTINBOX_BENCHMARK_DB_PASSWORD` and
  `TESTINBOX_GIT_SHA`. Only the names are recorded here. The password was generated for the
  disposable database and has been deleted.
- Every other harness parameter is the §11 default. The `harness` block of `evidence.json` records them.
- The harness was built with `commands/build.sh` (`./gradlew --no-daemon -q :benchmark:installDist`
  in the same JDK image), which is the step `scripts/storage-benchmark.sh` performs.

## Database

- `postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`
  (PostgreSQL 16.15, the production image).
- The only setting given on the command line was `-c max_connections=300`. Production uses 100;
  the harness requires more than the maximum concurrency plus 12. Every other setting is an image default.
  `pg-settings.txt` lists the non-default settings and the main defaults.
- No memory limit. A disposable container `ws-b2-pg` on an `--internal` network. The container,
  network and volume were removed after the run.

## Files

| Path | What it is | Origin |
|---|---|---|
| `evidence.json` | Harness evidence, verbatim | harness `--out` |
| `SUMMARY.md` | Harness summary, verbatim | harness `--out` |
| `tables.md` | Per-scenario table and criteria table, generated from `evidence.json` | `commands/tables.sh evidence.json` |
| `gate.log` | Harness console log of the run, ending `rc=1` | `commands/gate.sh` |
| `gate-d/gate-all.out`, `gate-d/gate-all.json` | `scripts/check-storage-activation.sh --mode ALL --benchmark-evidence evidence.json` at c10dd3d, run at 19:27:26Z | the app's gate |
| `pg-settings.txt` | PostgreSQL version and settings of `ws-b2-pg` | psql |
| `explain/postdiag.txt` | After the run, single client: table stats; EXPLAIN (ANALYZE, BUFFERS) of the T1 snapshot as the run left the database (3 runs), after VACUUM (3), after VACUUM FULL of `storage_reservation` (3), after VACUUM FULL of `storage_delta` too (2); pgbench probes after each step; autovacuum settings | `commands/postdiag.sh` |
| `explain/postdiag-extra.txt` | Extra pgbench probes (simple vs prepared) and EXPLAIN (ANALYZE, TIMING OFF), after VACUUM FULL | interactive psql/pgbench; no script was kept |
| `explain/explain-plain-during-ws200.txt` | Plain EXPLAIN of the snapshot taken during the ws200 scenarios | `explain/explain-plain.sql` |
| `explain/snapshot-query.sql`, `explain/snapshot-sql.template` | The T1 snapshot statement, and the template `postdiag.sh` fills in | from the candidate's code |
| `samples/pgsample.log` | Lock samples v1, 16:31 to 16:36Z: every 2 s, advisory-lock waiters and holders, backends by state and wait event | `commands/pgsample.sh` (v1, script not kept) |
| `samples/pgsample2.log` | Lock samples v2, from 16:36Z to the end: as v1, plus the first 32 characters of each active statement | `commands/pgsample.sh` |
| `samples/load.log` | Host load every 30 s: loadavg, CPU busy %, top 4 processes, apt/dpkg and Gradle counts | `commands/loadlog.sh` |
| `samples/dstats.log` | `docker stats` of `ws-b2-pg` and `ws-b2-gate` every 30 s | `commands/dstats.sh` |
| `commands/*.sh` | The scripts as run. Absolute paths are the Ops workspace on the staging host | |
| `SHA256SUMS` | sha256 of every other file in this directory | |

The eleven files that also appear in the run's own checksum list (`evidence.json`, `SUMMARY.md`,
`tables.md`, `pg-settings.txt`, the five `explain/` files other than `explain-plain.sql`, and both
`gate-d/` files) are byte-identical to it. Nothing in this directory was redacted or edited.

Verify with `sha256sum -c SHA256SUMS` from this directory.

## Not included

- Build log, `nohup` files, PID and created-object lists, stop markers: these are run mechanics, not evidence.
- GitLab runner and job JSON (`gl-runners.json`, `gl-r1-running.json`, `gl-r1-jobs-after.json`):
  internal CI details. They were used only to confirm that no CI job ran on the host during the run.
- The smoke run (`smoke-20261103-162917`): a pre-run check that is INCOMPLETE by design. It is not gate evidence.
- The Ops working notes (STATUS.md, RESULT.md): analysis, not raw evidence. The analysis is on #124.
