# ADR-035 §8 — global admission contention benchmark

These are the evidence behind ADR-035 §8. They are committed with the ADR,
not with the implementation, because the choice of admission design rests on
them.

**What it is not.** It is not a CI gate. The absolute numbers come from Docker
Desktop on an arm64 laptop, where `COMMIT` measured about 0.12 ms, which
suggests no real `fsync` was paid. Only the comparison between modes carries
over. Before the global ceiling is enabled in production, the §8 pass
criterion must be re-run on the staging host class, and the result recorded
in `docs/dev/production-ops-acceptance.md` row G.

## Modes

| Mode | Admission | What maintains usage |
|---|---|---|
| `c` | no global ceiling: the workspace check only (reference) | a row-level trigger on `msg` updates the workspace row |
| `a` | **chosen.** `pg_advisory_xact_lock` in T1 only; global usage is derived in one statement (`Σ ws_acct.committed + Σ resv.bytes`) | the same trigger (workspace row only) |
| `b` | one hot global row: a conditional `UPDATE` in T1; T2 and retention also update it | the trigger updates both the global and the workspace rows |

The pass 1 and pass 2 `a` scripts use the one-`bigint` advisory key. The
`*_t1.sql` scripts and the ADR use the two-`int4` form. Both forms take the
same lock-manager path, so their cost is the same; only the key space differs.

## Passes

| Pass | Runner | Shape | Results |
|---|---|---|---|
| 1 | `run.sh` | closed loop at saturation; one script = T1 + T2 (`*_ingest.sql`) plus retention of 200 rows, weighted 20:1; 1/16/64/128 clients | `results/logs-*.summary.txt`. The `*-128` runs **failed** (Postgres `max_connections=100`) and are not used. |
| 1b | `run2.sh` | pass 1 with `-r` for mean per-statement latency, i.e. the lock and row waits; `max_connections=300` | `results/pass1-per-statement.log` |
| 2 | `run3.sh` | open loop (`--rate`) at 520/s and 1 040/s events; T1, T2 and retention as **separate** scripts (`*_t1.sql`, `*_t2.sql`); 16 clients; 5 000 stale `RELEASING` and 2 000 `RESERVED` reservations pre-seeded | `results/logs3-*.summary.txt` |
| 2c | `run3ctl.sh` | control: mode `a` at 1 040/s with no backlog | `results/logs3ctl-a-1040.summary.txt` |

`results/percentiles.txt` holds the p50 and p99 per mode and point. It was
computed by `pct.py` and `pct3.py` from the per-transaction logs (`pgbench
-l`), which are too large to commit. Pass 2 latencies include open-loop
schedule lag.

## Reproduce

`schema.sql` sets up 26 workspaces, and each runner seeds 200 000 standing
messages. Each runner is self-contained: it starts a throwaway
`postgres:16-alpine` container named `ti-gbench*`, mounts this directory, and
removes the container at the end.

```
./run.sh     # pass 1  (writes logs/)
./run2.sh    # pass 1b (stdout)
./run3.sh    # pass 2  (writes logs3/)
python3 pct.py . ; python3 pct3.py .
```
