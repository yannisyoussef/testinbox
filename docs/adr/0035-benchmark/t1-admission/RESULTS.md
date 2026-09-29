# T1 admission: local sanity benchmark (TI-STORAGE-002 §39)

`run.sh` drives the **real** `StorageAdmission` use case over the **real** `JdbcStorageAdmission` adapter, through a
pooled (Hikari) connection, against `postgres:16-alpine` in Testcontainers. Every scenario starts from a fresh database
with a standing ledger:
- a base row for every inbox;
- 500 unfolded deltas (about one compaction interval);
- where stated, 1 000 live reservations, one in five `RELEASING`.

Each event's own reservations are removed after it, untimed, so the backlog stays at the scenario's size. The policy is
generous, so every copy is admitted and every T1 inserts. 100 warm-up events are followed by 800 measured ones.

**This is not the ADR-035 §11 enablement gate.** Laptop numbers are not production evidence. The staging-host run of §11
remains a blocking gate before the global ceiling is enforced. This run exists to catch a pathology: per-recipient
queries, or a collapse under backlog.

Measured on 2026-09-29, on a 16-vCPU arm64 laptop with Docker Desktop:

| Scenario | T1 p50 (ms) | p95 | p99 | T1/s |
|---|---:|---:|---:|---:|
| 1 recipient, 1 workspace | 1.15 | 1.32 | 1.48 | 765 |
| 10 recipients, 1 workspace | 1.54 | 1.73 | 2.03 | 575 |
| 50 recipients, 1 workspace | 2.85 | 4.95 | 6.78 | 298 |
| 1 recipient, 200 workspaces | 1.07 | 1.17 | 1.30 | 850 |
| 10 recipients, 200 workspaces | 1.67 | 3.66 | 5.30 | 446 |
| 50 recipients, 200 workspaces | 3.02 | 5.66 | 7.88 | 263 |
| 1 recipient, 200 ws, 1 000 live reservations | 1.24 | 1.90 | 3.84 | 654 |
| 10 recipients, 200 ws, 1 000 live reservations | 1.62 | 2.34 | 4.10 | 511 |
| 50 recipients, 200 ws, 1 000 live reservations | 3.01 | 5.15 | 7.86 | 269 |
| 10 recipients, 200 ws, 1 000 reservations, **16 clients** | 27.22 | 43.64 | 66.24 | 532 (≈ 5 300 copies/s) |
| 1 recipient, **10 000 ws**, 1 000 live reservations | 1.51 | 2.82 | 4.27 | 530 |
| 10 recipients, **10 000 ws**, 1 000 live reservations | 1.79 | 2.11 | 3.35 | 488 |
| 50 recipients, **10 000 ws**, 1 000 live reservations | 3.21 | 4.03 | 5.50 | 270 |

## Reading

- **No per-recipient queries.** Every T1 that admits something issues the same six statements, whatever its recipient
  count:
  - isolation, `synchronous_commit` and the lock timeout;
  - the statement and idle timeouts;
  - the lock;
  - one read;
  - one insert.

  A T1 that admits nothing skips the insert. `StorageAdmissionTransactionTest` asserts both, for 1, 10 and 50
  recipients. The cost grows by about 0.04 ms per recipient. That growth comes from the single read's per-scope
  aggregation and the multi-row insert, not from extra round trips.
- **Comparable to, not the same as, ADR-035 §11.** A 1-recipient T1 takes about 1.1 ms at p50 here. The ADR's laptop
  pass measured T1+T2 at 0.7 / 1.0 ms p50 / p99 with one client, on a different harness (pgbench scripts, no JDBC or
  Spring layer). At 16 concurrent clients, T1s serialize on the global lock as designed: throughput holds at about 530
  ten-recipient events per second, and the wait appears as latency.
- **Not a pass of the §11 gate.** That 16-client p99, 66 ms, is **above** the gate's T1 p99 ≤ 50 ms criterion. It is a
  closed loop at saturation, not the gate's open-loop expected rate, and it runs on a laptop. So it is not the gate
  either way, but nobody should read it as a pass. The staging-host run of §11 stays blocking before the global
  ceiling is enforced.
- **Workspace count is a real scaling term, and small.** The global figure sums every workspace's base row under the
  lock. Going from 200 to 10 000 workspaces adds about 0.3–0.5 ms at p50. The §11 staging gate should include a
  realistic workspace count. A compactor-maintained global base row would make the term constant, if it is ever
  needed.
- **The backlog costs little.** 1 000 live reservations move the p50 by under 0.5 ms.
- **An earlier harness artifact.** A first run used an unpooled data source, which opens a physical connection per T1.
  It measured about 10 ms of connection setup, not T1. The pooled figures above are the ones that count.
