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

## Why Postgres is the sole system of record

Redis is explicitly *not* a system of record (ADR-006): it is used only for
ephemeral coordination (wait notification fan-out, rate-limit counters) that
can be lost and rebuilt without data loss. If Redis is unavailable, the
system should degrade (e.g., wait falls back to short-interval polling)
rather than lose correctness.
