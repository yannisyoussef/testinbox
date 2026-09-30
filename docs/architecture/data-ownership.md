# Data Ownership

## Tenancy model

Every domain table (except global/system tables) carries a `workspace_id`.
There is no schema-per-tenant and no database-per-tenant for MVP — a shared
schema with mandatory tenant-scoped queries, enforced at the repository layer
(never trust a caller-supplied ID without a workspace check), keeps
operational complexity low while the tenant count and compliance requirements
are still small. Revisit if a customer requires physical data isolation.

## Ownership by module

| Data | Owning module | Store |
|---|---|---|
| Workspace, Project, User, ApiKey | `auth`/`domain` | Postgres |
| Inbox (reservation, address, TTL, state) | `domain` (written by both `api` and `ingestion-gateway`) | Postgres |
| Message metadata (headers, parsed text/HTML pointer, links, parse status) | `domain` (written by `ingestion-gateway`, read by `api`) | Postgres |
| Raw MIME bytes | `storage` | S3/MinIO, keyed by message ID |
| Attachment bytes | `storage` | S3/MinIO, keyed by message ID + attachment ID |
| Attachment metadata (filename, content-type, size, scan status) | `domain` | Postgres |
| AuditEvent *(post-MVP)* | `domain` | Postgres, append-only |

## Object storage layout (proposed)

```
s3://testinbox-mime/{workspace_id}/{inbox_id}/{message_id}/raw.eml
s3://testinbox-mime/{workspace_id}/{inbox_id}/{message_id}/attachments/{attachment_id}
```

Workspace-prefixed keys make bulk lifecycle deletion (TTL expiry, workspace
offboarding) a prefix operation rather than a per-object lookup.

Object keys are **per-message ownership keys, never content-addressed**:
every message owns its own `raw.eml` object even if byte-identical to
another message's (consistent with ADR-019 — content identity is metadata,
not storage identity). This keeps deletion semantics trivially correct:
deleting an inbox's prefix can never remove data referenced by another
inbox, and retention deletion is a deterministic prefix delete. Global
content deduplication (shared blobs + reference counting) is deliberately
rejected absent a demonstrated storage-cost need — it would couple every
delete to a refcount that must be transactionally consistent with the DB.
An orphan sweep reclaims blobs whose DB write never committed
(storage-first write order, ADR-005): objects older than a threshold with
no referencing `Message` row are deleted.

## Storage accounting and admission (ADR-035)

**Implemented (TI-STORAGE-001):** the accounting foundation. The physical bytes
a workspace or inbox holds are `raw_size_bytes` plus every extracted
attachment's `size_bytes`: an attachment counts twice, because both objects
exist.

- **The ledger.** V6's statement-level triggers on `message` and `attachment`
  append those bytes to the append-only `storage_delta` ledger in the writing
  transaction, including for `ON DELETE CASCADE`. No application code
  maintains a counter (the ADR-024 carve-out).
- **Compaction.** An API-side job folds the deltas into
  `workspace_storage_account` and `inbox_storage` every 5 s, under the advisory
  lock `(35, 2)`.
- **Reconciliation.** Every 6 h it proves `base + Σdelta` against the source
  rows and repairs, meters and logs any drift.
- **The source of truth.** The rows themselves remain the authority. The
  ledger is derived, reconcilable and rebuilt after a restore.

**Live, with enforcement OFF (TI-STORAGE-002 core, TI-STORAGE-003 wiring):**
the guarded ingest protocol of ADR-035 §4–§9. It is ADR-035 Phase 2: the
whole protocol runs, every ceiling is observed, and nothing is refused for
capacity.

- **Write slots.** Each ingestion node has 16. One workspace holds at most 4,
  and a slot waits at most 10 s. An event takes one slot BEFORE its
  reservations, so waiting never spends a write deadline. Unresolved
  ambiguity for the node occupies its slots, across restarts.
- **T1 (`JdbcStorageAdmission`).** The advisory lock `(35, 1)`, one snapshot
  of base, deltas and reservations, then the event's `RESERVED` rows. The
  live path constructs it with enforcement **OFF**, as a literal; no
  property, environment variable or profile can change it.
- **Fenced uploads.** One presigned PUT per exact key, signed with T1's
  database clock. The storage server binds the key, the length, create-only
  and a start deadline. Each upload is attempted once, with `T_put` and an
  RST abort. These are the only payload writes: an ArchUnit rule allows no
  other S3 `putObject` than the storage witness.
- **T2.** Reservations `FOR UPDATE`, then inboxes `FOR KEY SHARE`, then the
  refusal records, the messages with their `pg_notify`, and the reservations
  consumed, all in ONE transaction. An ADR-026 duplicate's reservation goes
  to `RELEASING`.
- **Failure.** Any physical failure abandons the whole event (`451`):
  - a definitive one releases its reservations now;
  - an ambiguous one keeps them charged, persists `storage_ambiguity`, and
    opens the node's breaker.
- **Cleanup** (API, every 30 s). It expires overdue reservations to
  `RELEASING` (`release_not_before = deadline + S`). It releases one only
  after all of these:
  - the clock-offset check;
  - a storage witness issued after `release_not_before`, plus `C_drain`;
  - a per-exact-key proof that both the object and any multipart upload are
    absent.

  Anything that reappears is a late object: the admission latch
  (`storage_admission_latch`) is set and committed, then the object is
  deleted. Only an operator clears the latch. Each row is claimed and released
  in its own short transaction.
- **Verification** (API, every 60 s). Ambiguity is verified at `T_verify`
  (60 min). Each node heartbeats in `storage_node`, and a node that died with
  uploads in flight leaves keyless ambiguity for them (one per slot, under its
  node id), plus one keyed coverage row per key it had started (under
  `recovered:<node id>`, holding no slot), each proved at `T_verify`.
- **Refusal records** (`inbox_storage.refusal_count`) commit inside T2, or in
  a short transaction of their own when nothing was admitted. With
  enforcement OFF there are none.

**Not implemented yet** (later ADR-035 slices):
- authenticated storage visibility: the workspace storage endpoint, the
  inbox `StorageUsage` fields, and the refusal counts;
- the opt-in wait `409` and its cursor;
- JVM and TypeScript SDK behaviour;
- enabling any enforcement. That needs the §14 activation barrier and the
  §18 enablement gates (the §11 staging benchmark, the storage
  qualification, the Ops prerequisites).

## Backup scope

Ownership decides what a backup may hold (ADR-034 §5). Durable control-plane
tables — `workspace`, `project`, `api_key`, `exact_address_reservation`,
`flyway_schema_history` — are backed up; tenant content (`inbox`, `message`,
`attachment`), the idempotency projections, the limiter tables and the seven
ADR-035 accounting and admission tables are never, and neither is any object in
storage. `deploy/backup/scope.txt` classifies
every table and `scripts/check-backup-scope.sh` refuses a dump that breaks the
classification, so a backup cannot quietly outlive the TTL and deletion
promises above.

## Why Postgres is the sole system of record

Redis is explicitly *not* a system of record (ADR-006): it is used only for
ephemeral coordination (wait notification fan-out, rate-limit counters) that
can be lost and rebuilt without data loss. If Redis is unavailable, the
system should degrade (e.g., wait falls back to short-interval polling)
rather than lose correctness.
