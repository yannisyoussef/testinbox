# V6 migration: real-implementation measurement (TI-STORAGE-001 §21)

`run.sh` applies V1–V5 to a throwaway `postgres:16-alpine` container and seeds the dataset below. It then runs
**exactly the committed `V6__storage_accounting_foundation.sql`** in one transaction, as Flyway does, and times each
statement with `\timing`.

| | |
|---|---|
| Measured | 2026-09-29, PostgreSQL 16.15 (aarch64), Docker Desktop VM with 16 vCPU, Apple Silicon laptop, `--shm-size=1g` |
| Dataset | 50 workspaces, 5 000 inboxes, **1 000 000 messages** (`message` table 459 MB), **300 000 attachments** |
| Lock acquisition (uncontended) | `LOCK TABLE workspace, inbox, message, attachment IN SHARE ROW EXCLUSIVE MODE`: **0.075 ms** (warm) / **0.048 ms** (cold) |
| Total V6, warm | **542 ms**: 26 statements, including the table/trigger DDL and the full backfill |
| Total V6, cold | **576 ms**, after restarting PostgreSQL (shared buffers empty). The Docker VM's OS page cache is not dropped, so this is "cold PostgreSQL", not a cold disk. |
| Backfill exactness | Σ `workspace_storage_account.base_bytes` = 78 999 500 000 = Σ `message.raw_size_bytes` + Σ `attachment.size_bytes`. 5 000 `inbox_storage` rows, 0 deltas left over. |
| ADR-035 planning bound | ≤ 10 s cold, once the locks are acquired: **met, with about 16× margin** |

The lock *wait* is not part of this number. It is bounded separately by V6's `SET LOCAL lock_timeout = '30s'`, and
`StorageV6MigrationTest` proves that an unavailable lock fails the migration cleanly.

Large cascade, measured by the implementation: `StorageLedgerTriggerTest` deletes an inbox holding 50 000 messages
and 20 000 attachments in one `DELETE FROM inbox`. The ledger stays exact and gains **2** delta rows (one grouped
message delta, one grouped attachment delta), not 70 000. Measured at 341 ms on the same laptop; the timing is not a
CI threshold, the grouped shape is what the test asserts. ADR-035's design experiment measured about 0.25 s.
