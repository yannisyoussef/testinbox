# NON-GATE MVP SIZING: not gate D evidence — pool 8, f59466f, 2026-11-06 (raw)

**NON-GATE MVP SIZING: not gate D evidence.** Run 3 stays FAIL.

Ops ran these at the app team's request in #124, comment 6693997866 (production's pool size 8). It is a staging-host LAB fact.
Nothing was edited. The only exceptions are the `checks/` files.

| Item | Value |
|---|---|
| Commit / jar | `f59466f7537784c1257f1fe73e7e925a42e579cb` (clean worktree), jar sha256 `94c5d213b678e59e3f4edebd8af2f20e9fd348fc8340615f1ef48de37e003b76` |
| PostgreSQL | A fresh disposable `postgres@sha256:cf78e766…` per run, only `-c max_connections=300`, `--internal`. Databases `ti_bench_p8_n30`, `ti_bench_p8_n15`, `ti_bench_p8_n10`. Each run's `pg-settings.txt` was taken after the run. The p8-n30 "pg up" line in `runs.log` shows empty settings: the readiness probe raced the image's init restart. The run's `pg-settings.txt` records `max_connections = 300 (command line)` |
| Runs (`runs.log`) | p8-n30 20:00:33–20:11:15Z; p8-n15 20:11:17–20:20:50Z; p8-n10 20:20:52–20:30:13Z. Each printed `VERDICT: INCOMPLETE`, rc 3, `adrEvidence: false` |
| Commands | Exactly as in the request (`commands/runs.sh`), with `--pool-size 8` and no `--pool-max-lifetime` |
| p8-n10 decision | Run, because p8-n30 failed at R under the coordinator's rule: FOCUSED.md `T1 p50 / p95 / p99` p99 at r31 = 533.41 / 518.68 / 508.36 / 548.71 ms, above 250 ms (`checks/n10-decision.txt`) |
| Host | Quiet. load1 max 6.1, steal 0 %, no contamination (`checks/host-contamination.md`) |

Per run: `FOCUSED.md`, `SUMMARY.md`, `evidence.json` and `diagnostics-cohort.txt`, verbatim. Also `samples/`, `commands/` and
`SHA256SUMS`.
