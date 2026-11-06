# M3: frozen-candidate evidence (first cohort N=10, owner D1) — not gate D §11 evidence — ce925d7, 2026-11-06 (raw)

This run was **run once**. It is a staging-host LAB fact. The harness labels it `adrEvidence: false` (focused). Nothing was
edited. The only exceptions are the `checks/` files.

| Item | Value |
|---|---|
| Commit | `ce925d7e3091fda18ae8292ca489af4f839198a3` (`app/fasttrack-m3-harness`), clean detached worktree; evidence `gitSha` is the same |
| Scope versus the frozen tree | `2f3dc59` is an ancestor. `git diff 2f3dc59 ce925d7 --stat`: 15 files, all under `backend/benchmark/**` plus `docs/dev/storage-benchmark.md`; nothing outside (`checks/diff-stat-2f3dc59-ce925d7.txt`, `checks/diff-names.txt`). Commits: 19f7a91 (`--cohort-participants`) and ce925d7 (`--pool-size`) |
| Harness jar | `benchmark-0.1.0-SNAPSHOT.jar` sha256 `b9eb74c57501c1ef6bb411bc093bb66f8057c61bf64a1c2f953ad3fdc315558a`, built with `commands/build.sh` in `eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea` |
| PostgreSQL | Fresh disposable `postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`, **only** `-c max_connections=300` (`pg-settings.txt`), `--internal` network, database `ti_bench_m3_n10`; removed after the run |
| Command | `--focused-cohort --cohort-participants 10 --expected-rate 11 --rates 11,22 --pool-size 8 --concurrency 10,25,50,100 --duration-seconds 60 --host-class "staging-host vmi2932906 (M3: frozen-candidate evidence, first cohort N=10, owner D1)"`. Production pool lifetime: no `--pool-max-lifetime` (`commands/runs.sh`) |
| Run | 2026-11-06 22:33:43Z to 22:43:06Z. `VERDICT: INCOMPLETE`, rc 3, `adrEvidence: false` |
| Host | Before the start, load1 stayed below 4 for 2 min with apt idle, and nothing else of Ops was running (`checks/settle.log`; load1 3.23 at the 22:33:29Z pre-check). During the run: load1 mean 4.8, max 6.3, steal 0 %. Non-benchmark CPU was median 3.02 cores, p95 4.22. No sample was flagged by the run 1 rules (0 of 8 scenarios marked; `checks/host-contamination.md`) |

Files: `FOCUSED.md`, `SUMMARY.md`, `evidence.json` and `diagnostics-cohort.txt`, verbatim; `runs.log`; `pg-settings.txt`;
`checks/`; `samples/`; `commands/`; `SHA256SUMS`.
