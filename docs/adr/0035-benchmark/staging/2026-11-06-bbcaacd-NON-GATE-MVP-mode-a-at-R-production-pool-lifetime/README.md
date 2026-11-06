# NON-GATE MVP: mode a at R, production pool lifetime — bbcaacd, 2026-11-06 (raw)

**NON-GATE: not gate D evidence.** It feeds the owner's MVP decision. Run 3 stays FAIL.

Ops ran it at the app team's request in #124, comment 6693538783 ("please run it"). It is a staging-host LAB fact. Nothing here
was edited or recomputed. The only exception is `checks/host-contamination.md`.

| Item | Value |
|---|---|
| Commit / jar | `bbcaacd7fab03d4819f89523deae77f37fc6876d`, rebuilt in a clean worktree. Jar sha256 `150a1de953bc6e44c01ed033189f510a2b5078cddbabf46ee91b5ca110514528`, identical to #152 |
| PostgreSQL | Fresh disposable `postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`, **only** `-c max_connections=300`, `--internal` network, database `ti_sle_bench_r31_prod` |
| Command | `--focused-cohort --rates 31 --concurrency 10,25,50,100 --duration-seconds 60 --host-class "staging-host vmi2932906 (NON-GATE MVP: mode a at R, production pool lifetime)"`. No escrow flag and no `--pool-max-lifetime`; the evidence records poolMaxLifetime PT30M (`commands/runs.sh`) |
| Run | 2026-11-06 19:36:07Z to 19:41:32Z. `VERDICT: INCOMPLETE`, rc 3, `adrEvidence: false` |
| Host | Quiet. Before the start, load1 stayed below 4 for 2 min with apt idle (`checks/settle.log`). During the run, load1 max 5.4 and steal 0 %. No contamination found |

Files: `FOCUSED.md`, `SUMMARY.md`, `evidence.json` and `diagnostics-cohort.txt`, verbatim; `runs.log`; `pg-settings.txt`;
`checks/`; `samples/`; `commands/`; `SHA256SUMS`.
