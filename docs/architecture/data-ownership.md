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

**Implemented internally, NOT wired to ingress (TI-STORAGE-002):** the atomic
admission and reservation core (ADR-035 §4, T1).

- `StorageAdmission` decides one event's recipient copies, in envelope order,
  against three ceilings:
  - the inbox, at `floor(workspace × share)`;
  - the workspace;
  - the global admission cap `G − H`.
- `JdbcStorageAdmission` runs it as one transaction:
  - READ COMMITTED, with `synchronous_commit = on` and a 5 s lock timeout;
  - the advisory lock `(35, 1)`;
  - **one** statement that reads base, deltas and reservations for every scope;
  - one insert of the admitted copies' `RESERVED` rows.
- It has an enforcement-OFF mode, which reserves without refusing.
- It is proven in the persistence suite, including under concurrency.
- **Nothing calls it.** The adapter is not a Spring bean, and an ArchUnit rule
  forbids any deployable from depending on it. Normal SMTP and provider traffic
  therefore creates no reservation, and no mail is refused because of
  storage.

**Not implemented yet** (later ADR-035 slices):
- the live guarded ingest path: write slots, fenced (presigned) object writes,
  and the T2 commit that consumes a reservation;
- reservation cleanup and release;
- refusal counters;
- the storage breaker, ambiguity tracking and the admission latch;
- API and SDK visibility;
- any enforcement.

V6's `storage_ambiguity`, `storage_node` and `storage_admission_latch` tables
are still never written. Outside the persistence tests, nothing writes
`storage_reservation` either.

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
