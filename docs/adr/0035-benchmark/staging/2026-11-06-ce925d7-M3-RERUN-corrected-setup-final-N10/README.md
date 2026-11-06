# M3 rerun (corrected setup, final): frozen-candidate evidence N=10 — ce925d7, 2026-11-06 (raw)

This is exactly **one** complete run of all 8 scenarios. It was authorized by the owner and the review disposition (private #12,
1591–1593) and run under the binding frozen spec of the rerun preconditions review. **The first M3 run (PR #154) stays FAIL and is
untouched.** This rerun is final: no tuning, no partial reruns, no second attempt. It is not gate D §11 evidence, and the harness
labels it `adrEvidence: false` (focused). Nothing here was edited.

| Item | Value |
|---|---|
| Commit | `ce925d7e3091fda18ae8292ca489af4f839198a3`, a fresh clean detached worktree. `git diff 2f3dc59 ce925d7 --stat` is unchanged from M3 (`checks/diff-stat-2f3dc59-ce925d7.txt`) |
| Jar | Rebuilt with the same `commands/build.sh` (fresh gradle-home, `eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea`). sha256 **b9eb74c57501c1ef6bb411bc093bb66f8057c61bf64a1c2f953ad3fdc315558a**, **identical** to M3. All `lib/*.jar` sha256 are in `checks/lib-jars-sha256.txt` (application `f95d0132…`, persistence `64e6af2e…`, domain `cab93c79…`) |
| PostgreSQL | Fresh disposable `postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`, **only** `-c max_connections=300` (`pg-settings.txt`), `--internal` network, no CPU or memory limits, database `ti_bench_m3_n10_rerun` |
| Command | The frozen command (`commands/runs.sh`): `--focused-cohort --cohort-participants 10 --expected-rate 11 --rates 11,22 --pool-size 8 --concurrency 10,25,50,100 --duration-seconds 60`, production pool lifetime. The host-class is the frozen text plus " RERUN" |
| Harness parameters (evidence.json) | workspaces [10000], seed 35, rates [11, 22], concurrency [10, 25, 50, 100], 60 s + 3 s warm-up, mix `1:60,3:25,10:12,50:3`, 24 576 B/copy, 16 slots (4/workspace), backlog 1000 + 500, 10 inboxes/workspace, retention 200, compaction 5 s. All as frozen |
| Run | 2026-11-06 **23:08:13Z → 23:17:36Z**: all 8 scenarios, one process, in the frozen order. `VERDICT: INCOMPLETE`, rc 3, `adrEvidence: false`. Not interrupted |
| Window | Started after the 23:00Z timers; ended before the 00:30Z backup timer |
| Attestation | `ATTESTATION.md`: no other Ops workload during 23:08:13–23:17:36Z. Evidence in `baselines/`: pre and post baselines, process trees, and `ps`/`docker ps` every 60 s. **These files contain the host's full process list and container set** |
| Host | load1 mean 3.5, max 4.2; steal 0 %; non-benchmark CPU median 1.92 cores, p95 2.64; 0 of 8 scenarios flagged (`checks/host-contamination.md`). Pre-window host busy was 1.62 cores |

Files:
- Harness output, verbatim: `FOCUSED.md`, `SUMMARY.md`, `evidence.json` and `diagnostics-cohort.txt`.
- `runs.log`, `pg-settings.txt`, `ATTESTATION.md`.
- `checks/`, `baselines/`, `samples/` (load, vmstat, mpstat, dstats, pgsample, conncount), `commands/`, `SHA256SUMS`.
