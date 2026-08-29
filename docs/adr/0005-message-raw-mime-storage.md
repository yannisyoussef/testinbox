# ADR-005: Message/Raw MIME Storage

**Status:** Accepted

## Context

Raw MIME can be large (attachments) and is needed for debugging parse
failures, but doesn't belong in a relational row.

## Decision

Raw MIME and attachment bytes are stored in S3-compatible object storage
(MinIO locally), keyed by workspace/inbox/message/attachment IDs (see
`docs/architecture/data-ownership.md`). Postgres stores only structured
metadata and parsed fields, plus a pointer to the object storage key. Raw
MIME is written **before** parsing is attempted, so a parser failure never
loses the original bytes.

## Alternatives considered

- **Store raw MIME as a `bytea`/large-object column in Postgres**: rejected
  — bloats the primary database, complicates backup/restore sizing, and
  object storage is a better fit for large, rarely-queried blobs.

## Consequences

- Two systems must be kept consistent (DB row + object storage key);
  handled by writing object storage first, then the DB row referencing it,
  so an orphaned object-storage blob (write succeeded, DB write failed) is
  the only possible inconsistency — never a DB row pointing at missing data.
- TTL-based cleanup must delete from both stores (`docs/architecture/message-lifecycle.md`).
