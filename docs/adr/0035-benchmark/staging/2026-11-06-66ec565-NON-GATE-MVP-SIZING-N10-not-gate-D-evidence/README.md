# NON-GATE MVP SIZING: not gate D evidence — N=10, 66ec565, 2026-11-06 (raw)

**NON-GATE MVP SIZING: not gate D evidence.** Run 3 stays FAIL.

The request was #124 comment 6693742750, replaced by 6693997866. This N=10 run had already started, so it was finished; N=15
at 66ec565 had not started and was skipped. It is a staging-host LAB fact, and nothing was edited.

| Item | Value |
|---|---|
| Commit / jar | `66ec565dd358bcce5dff6cf913abe78d3b6bad4e` (clean worktree), jar sha256 `5d7e5510c13e46dca620d7c88287cb369e1e45379cd619299ffe4e783bd02aea` |
| PostgreSQL | Fresh disposable `postgres@sha256:cf78e766…`, only `-c max_connections=300`, `--internal`, database `ti_bench_n10` |
| Command | `--focused-cohort --cohort-participants 10 --expected-rate 11 --rates 11,22 --concurrency 10,25,50,100 --duration-seconds 60 --host-class "staging-host vmi2932906 (NON-GATE MVP SIZING, N=10)"`, no `--pool-max-lifetime` |
| Run | 2026-11-06 19:47:04Z to about 19:53Z. `VERDICT: INCOMPLETE`, `adrEvidence: false` |
| Host | Quiet. load1 max 4.5, steal 0 %, no contamination |

**Run mechanics.** The runner script (`commands/runs.sh`) was stopped (its own PID) during this run, so that N=15 would not
start. The harness container ran to completion unaffected. Its exit code was therefore not captured by the script, and the
printed VERDICT is in `runs.log`. The database was then torn down by hand.

Files: `FOCUSED.md`, `SUMMARY.md`, `evidence.json` and `diagnostics-cohort.txt`, verbatim; `runs.log`; `pg-settings.txt`;
`checks/`; `samples/`; `commands/`; `SHA256SUMS`.
