# ADR-035: Physical Storage Bound at Ingest

**Status:** Proposed (2026-09-28, revised after architecture, security,
quality and API/SDK review). It is awaiting the owner's review. Nothing below
may be implemented or relied on until it is Accepted, and four owner decisions
remain open (§0).

On acceptance it amends:

- [ADR-027](0027-rate-limiting-and-resource-quotas.md) §2 (the storage-quota
  paragraph and the overshoot bound), §4 (the sentence naming `INGEST` as the
  only place mail for a live inbox is dropped), §5 (usage derived, never
  accounted), and the matching Alternatives and Consequences entries;
- [ADR-020](0020-wait-reliability-and-timeout-semantics.md) §3 (a wait gains
  one terminal, authenticated-only outcome);
- [ADR-024](0024-application-layer-and-dependency-rule.md), with one narrow
  carve-out for database-maintained accounting (§7);
- [ADR-015](0015-rest-compatibility-versioning.md), with one explicit behaviour
  change within the experimental v1 (§11c).

It does **not** amend [ADR-025](0025-unknown-recipient-handling.md),
[ADR-026](0026-recipient-scoped-provider-delivery-identity.md),
[ADR-019](0019-inbound-deduplication-semantics.md) or
[ADR-009](0009-retention-lifecycle.md): no SMTP reply changes, the event stays
the atomic unit of commit, nothing is deduplicated, and no lifecycle transition
is added. ADR-027's text is left as written, and its superseded passages carry
a pointer here.

> Owner decisions this ADR is written against (TI-DOC-001, 2026-09-28):
> - authenticated refusal visibility **yes**, including a typed wait outcome;
> - **no** grandfathering and **no** eviction;
> - `maxStoredBytes` stays **2 GiB**;
> - a **global application ceiling** (40 GiB initial production value) under a
>   50 GiB bucket quota;
> - the 120 s reservation TTL is a **write deadline, not a release**;
> - accounting is **physical**;
> - ceilings answer `250`, and physical storage failure answers `451`.

## 0. Open decisions for the owner

Review surfaced four questions that the TI-DOC-001 decisions do not settle.
Each has a recommendation, and the ADR is written as if the recommendation is
taken. None of the four changes the invariants of §1.

| # | Question | Recommendation | Where |
|---|---|---|---|
| **O1** | A workspace-wide ceiling lets one guessed `EXACT` address deny mail to *every* inbox in the workspace. About 80 large messages fill 2 GiB, which fits inside the edge's per-client rate. This is the property ADR-027 keyed `INGEST` per inbox to prevent. Add a **per-inbox storage sub-ceiling**? | **Yes**: `maxStoredBytesPerInbox` = 25 % of the workspace limit (512 MiB), checked narrow scope first, mirroring `ingestPerInbox`. It is a new product limit, which is why it is the owner's call. | §3a |
| **O2** | "Terminate if a refusal occurs *after the wait begins*" misses the most common test flow: create the inbox, trigger the send, then wait. A copy refused before the first wait call would still time out silently. Widen "begins" to "after the inbox state the caller last observed"? | **Yes**: SDKs seed the first call's cursor from the `Inbox` they hold (0 for an inbox they just created). | §11c |
| **O3** | Disclose `SERVICE_CAPACITY` to tenants as a refusal reason? It is a one-bit, incident-time cross-tenant signal. | **Yes**, with the residual recorded in §11b. Without it, a tenant with visible headroom has a refusal nobody can explain. | §11b |
| **O4** | Accept the additions made for the invariants, all beyond the literal request? They are: explicit gateway connection and recipient bounds; per-node write permits; single-attempt puts with a real timeout; a physical-failure circuit breaker; chunked retention deletes. | **Yes**. Each is load-bearing for a stated invariant or bound, and each section says which. | §4, §5, §6a |

## Context

ADR-027 §2 deliberately did not enforce `maxStoredBytes` on inbound mail. A
workspace at its quota could not create inboxes, but mail to the inboxes it
already held was stored. The bound was therefore `maxStoredBytes` plus an
overshoot of `INGEST` rate × the ADR-009 TTL ceiling. ADR-027 gave three
reasons, and each one still has to be answered rather than set aside:

1. **The SMTP reply must not carry quota state** (§1). A `452` for an
   over-quota workspace next to a `250` for an unknown address is a
   workspace-membership oracle. Deferring the whole event is a cross-tenant
   denial of service under ADR-026.
2. **Accept-and-drop manufactures a false negative** (Alternatives). Mail to a
   live inbox that silently disappears makes the system under test look as if
   it never sent.
3. **A counter cannot be maintained** (§5). ADR-009 hard-deletes through
   `ON DELETE CASCADE`, which runs no application code, so a usage counter would
   drift upward until every workspace wedged at `409`.

Three facts have changed since ADR-027 was accepted, and each is measured or
observed rather than argued:

- **The overshoot is not small.** At the ADR-027 defaults (`INGEST` 10/s
  sustained per workspace, a 24 h TTL ceiling, 15 MiB messages) the "bounded"
  overshoot is about 12.4 TiB per workspace. The production bucket is 50 GiB,
  so that bound constrains nothing, and 26 workspaces legitimately at 2 GiB
  already fill it.
- **Objection 3 is refuted by measurement** (TI-STORAGE-BOUND, 2026-09-26,
  `postgres:16-alpine`). Row-level `AFTER INSERT`/`AFTER DELETE` triggers **do**
  fire for rows removed by `ON DELETE CASCADE`: an aggregate maintained by
  triggers went `0 → 1250 → 0` across a `DELETE FROM inbox` that cascaded
  through `message` to `attachment`. The cascade runs no *application* code,
  but it does run *database* code, and that is where the maintenance now
  lives. The cost is about 88 µs per row.
- **Deriving usage per delivery is too slow at the rate it would run.** With
  864k `message` rows in one workspace (plus 100k elsewhere and 300k
  attachments), the ADR-027 derivation took 27.7–45.5 ms. With covering
  indexes it was still 26.2–30.1 ms, as an index-only scan over about 1.16M
  entries. At 10 deliveries/s under a serialization point, that is a 26–30 %
  duty cycle. An aggregate row plus the live reservations read in
  0.17–1.07 ms.
- **Objection 2 has an answer that objection 1 permits.** The sender is still
  told nothing. The *tenant*, who is authenticated, can be told everything
  about their own workspace, including a wait that ends early with the reason.

## Decision

### 1. Invariants

These are normative. Everything after this section is mechanism.

- **I1: accounting never under-counts physical bytes.** For every workspace
  *W*, the application-written object bytes under *W*'s prefix that have not
  been *proven absent* are at most `committed(W) + reserved(W)`. Summed over
  workspaces, the same holds globally. The database may over-count: briefly,
  during retention, and while a crash is cleaned up. It must never under-count,
  except for the residue enumerated in §9.
- **I2: admission is exact against every ceiling.** A reservation for a
  candidate recipient copy of `f` bytes is created only if the following holds
  in **one** database snapshot, under the global admission lock:
  - the inbox ceiling (§3a, if O1 is taken);
  - `committed(W) + reserved(W) + f ≤ workspaceLimit`;
  - `Σcommitted + Σreserved + f ≤ globalLimit`.
- **I3: no object without a live reservation.** `BlobStore.put` is never
  called for a recipient copy unless a `RESERVED` reservation covering its
  exact bytes exists. A put is never *started* after that reservation's write
  cutoff (§4).
- **I4: commit consumes the reservation or fails closed.** A message row
  commits only in the transaction that deletes its own `RESERVED`
  reservation, and only if the reserved bytes equal the bytes committed.
  Otherwise the whole event rolls back and the gateway answers `451`.
- **I5: reserved bytes are released only on proof.** Release happens only
  through one of:
  - (A) the commit of I4;
  - (B) proof that no object exists under the copy's prefix;
  - (C) deletion of those objects, followed by the same proof;
  - (D) a reconciliation that establishes one of these states.

  **Time alone never releases capacity.**
- **I6: no eviction.** Stored content stays readable until ADR-009 retention
  or explicit deletion removes it. A workspace over its ceiling loses
  nothing; it only stops admitting.
- **I7: SMTP never reveals tenant state.** A syntactically valid recipient
  gets the uniform `250` of ADR-025 in all three cases: it is unknown, its
  inbox or workspace is at its ceiling, or the global ceiling is reached. The
  refused copy is discarded in-process, and `BlobStore` is never called for
  it. A `451` is **never a function of tenant state** (membership, usage,
  ceilings). It reports physical storage failure, database failure, or load
  (§4 cutoff), and it covers the whole `DATA` with nothing committed.
- **I8: the tenant can see what the sender cannot.** Workspace storage state,
  per-inbox refusal counts, and a typed terminal wait outcome are exposed on
  the authenticated API only, workspace-scoped. Cross-tenant lookups stay
  `404`.
- **I9: no tenant identifier becomes a metric label.** Every new label is a
  closed enum.
- **I10: drift is detectable and repairable.** Database triggers maintain
  `committed`. A reconciliation job proves it against the rows and repairs
  it. A non-zero repair is metered and alarmed, because it is evidence of a
  defect and never a normal mode.

### 2. Physical accounting

The unit is the number of bytes that physically exist in object storage.

- **Per recipient copy**, the charge is the `raw.eml` bytes plus the bytes of
  **every** extracted attachment object. Attachments therefore count twice:
  once inside `raw.eml` and once as the extracted object, because both exist
  under ADR-005's per-message key layout. This is ADR-027 §5's existing
  derivation, kept unchanged, so reconciliation (§7) can compare the two
  exactly.
- An event with *N* admitted recipients costs *N* copies. Keys are
  per-message (ADR-005/026), so identical bytes are stored and counted *N*
  times.
- A parse failure stores `raw.eml` only (`parseStatus=FAILED`), so its
  footprint is the raw size.
- PostgreSQL metadata does **not** count toward `maxStoredBytes`.
- Every copy's footprint is exact **before the first byte is written**. The
  raw size is known when `DATA` ends, and the gateway bounds it to 15 MiB. The
  attachment sizes are known after the single per-event parse, which already
  precedes any write.

`workspaceLimit` stays **2 GiB** (`testinbox.limits.max-stored-bytes`,
unchanged). Making it plan-dependent is a later, separate decision.

### 3. The admission transaction (T1)

T1 runs once per inbound event. It runs after the one parse and after the
per-recipient ADR-025 resolution and ADR-027 `INGEST` charge, both of which
are unchanged. It runs before any object write. **T1 is skipped entirely when
no candidate survives those two steps**, so an unknown-recipient or
rate-limited flood never touches the global lock.

```
BEGIN;                                               -- READ COMMITTED, synchronous commit
SET LOCAL lock_timeout = '5s';                       -- waiting past this → 451 (load, not tenant state)
SELECT pg_advisory_xact_lock(:storageClass, :global);   -- first lock-taking statement
INSERT INTO workspace_storage_account (workspace_id)    -- ensure rows exist; DO NOTHING
     SELECT unnest(:workspaceIds) ON CONFLICT DO NOTHING;
SELECT                                               -- ONE statement = ONE snapshot
  (SELECT coalesce(sum(committed_bytes),0) FROM workspace_storage_account)
+ (SELECT coalesce(sum(bytes),0)          FROM storage_reservation)         AS global_used,
  <per involved workspace: committed_bytes + Σ its reservations>            AS ws_used,
  <per involved inbox (O1): inbox committed + Σ its reservations>           AS inbox_used;
-- application: walk candidates in envelope order; admit while every ceiling holds,
--              adding each admitted f to the running totals
INSERT INTO storage_reservation (...) VALUES (...admitted copies...);
COMMIT;                                              -- releases the global lock
```

- **One global admission lock, in the two-key form.** PostgreSQL keeps the
  `pg_advisory_xact_lock(int4, int4)` key space disjoint from the
  one-`bigint` space used by the per-workspace `guardAdmission` (ADR-027 §6),
  so no workspace key can collide with it.
  - Only T1 takes this lock.
  - Nothing that *decreases* usage needs it: not commit, retention, cleanup or
    reconciliation. A decrease the admission snapshot does not yet see only
    makes the admission more conservative.
  - The `lock_timeout` stops a queue of waiters from draining the ingestion
    pool (8 connections per node). A waiter that exceeds it answers `451`.
    That is load, and it is the same `451` for every recipient.
- **Global usage is derived, not stored.** `Σcommitted` is a sum over one row
  per workspace. `Σreserved` is a sum over live reservations, bounded by
  in-flight copies plus stale ones (§6a bounds the stale ones). No row is
  written by every ingest, commit and retention delete. §8 measures that
  alternative and rejects it.
- **One statement, one snapshot.** Under READ COMMITTED, each statement takes
  its own snapshot. T2 (§5) moves bytes from `reserved` to `committed`
  atomically. If `Σcommitted` and `Σreserved` were read by two separate
  statements, a T2 committing between them would be seen in neither place.
  That is an under-count by exactly its bytes, which means over-admission.
  Reading both in one statement makes the move either invisible or fully
  visible. The adapter is required to issue a single statement, and a test
  asserts this (§14, test 12).
- **Refusal precedence**, narrowest first: the inbox ceiling (O1), then the
  workspace ceiling (both recorded as `WORKSPACE_LIMIT`, which the tenant can
  act on), then the global ceiling (`SERVICE_CAPACITY`). A copy that several
  ceilings would refuse is recorded under the narrowest one.
- **Refusing one recipient is a discard, not a partial commit.** A refused copy
  is dropped in-process, exactly like the ADR-025 unknown-recipient discard
  and the ADR-027 rate-limited discard. Those already coexist with committed
  siblings in the same event. ADR-026's all-or-nothing rule governs what
  *commits*, and it is unchanged.
- **`ON CONFLICT DO NOTHING` locks nothing when the row already exists.** It
  *can* wait on another transaction's uncommitted insert of the same key,
  which in practice means a trigger upsert for a brand-new workspace. That is
  a rare wait, once per workspace lifetime, and `lock_timeout` covers it.

### 3a. Per-inbox sub-ceiling (O1; pending owner decision)

This section applies only if O1 is taken.

- **Limit.** `testinbox.limits.max-stored-bytes-per-inbox`, default 25 % of
  the workspace limit, and required to be `≤ max-stored-bytes`.
- **Committed bytes per inbox.** They live in `inbox_storage.committed_bytes`.
  The same triggers maintain it on message and attachment **insert**. An
  attachment finds its inbox through its message, which exists in the same
  transaction.
- **No decrement path.** Messages are deleted only when their inbox is torn
  down, and the `inbox_storage` row goes with the inbox.
- **Reserved bytes per inbox** are `Σ storage_reservation.bytes` for that
  `inbox_id`.
- **Effect.** A flood against one guessed address fills its own inbox's
  allowance and then stops charging the workspace. Denying the whole workspace
  then requires as many known addresses as the ratio: four at the default.
- **Residual.** The residual is recorded in `docs/security/abuse-model.md` §4:
  a party that knows *every* address can still deny the workspace. That is the
  ADR-027 per-inbox `INGEST` posture, applied to storage.

### 4. Writing under a reservation

- **What a reservation records.** Each reservation carries:
  - `message_id`, pre-generated, which is the primary key;
  - `workspace_id`, `inbox_id`, and `object_prefix` (`{workspace}/{inbox}/{message}/`);
  - `bytes`, `state` and `created_at`;
  - `write_deadline_at = now() + 120 s`, computed from **database time** (the
    ADR-027 §4 rule).
- **`inbox_id` has no foreign key, and nothing cascades into this table.** An
  inbox that is hard-deleted while a copy is in flight must not take with it
  the only record of objects that may already exist. This is the same reason
  `exact_address_reservation.inbox_id` has none (V1).
- **The write cutoff is measured without trusting node clocks.**
  - The writer starts a monotonic `Ticker` *before* sending T1's `BEGIN`. Its
    elapsed time therefore over-estimates the database time elapsed since T1's
    `now()`.
  - It may start a put only while `elapsed + T_put + 5 s ≤ 120 s`. Otherwise it
    aborts the event with `451`.
  - The `Ticker` is injectable, and tests drive it (§14).
- **A put is a single attempt with a hard timeout `T_put`** (default 30 s,
  exposed on the `BlobStore` port so the use case can apply the cutoff).
  - SDK retries are **disabled** for `put`. With retries, a put could return a
    clean error while an earlier attempt that timed out was still in flight.
    Nothing could then be treated as finished.
  - Today `S3BlobStore` sets no `apiCallTimeout` at all, which makes a put
    unbounded and a deadline decorative. The implementation sets it.
  - A put's outcome is **definitive** if it succeeded or the storage server
    answered with an error. It is **ambiguous** on a timeout or an I/O failure.
  - Puts stay **single-part** (`putObject`; 15 MiB is far below any multipart
    threshold), so no abandoned multipart parts exist.
- **Per-node write permits.** `max-concurrent-writes`, default 16, is an
  application-side semaphore (framework-free, so ADR-024 is respected).
  - A writer holds at most **one** permit at a time, and one event's puts are
    sequential. A single large event therefore cannot hold permits that other
    events need.
  - Time spent waiting for a permit counts against the cutoff.
  - The permits are what make the §9 residue a small number.
- **Gateway bounds become explicit instead of inherited.** SubEthaSMTP's
  defaults are 1000 connections and 1000 recipients.
  - `maxRecipients` is set to exactly the edge's
    `relay_destination_recipient_limit` (50). If it were lower, the edge would
    split a transaction and silently weaken ADR-026's atomicity.
  - The value is pinned in `deploy/mail-edge/contract.yaml` under `ingestion:`.
  - `maxConnections` is configured explicitly as well.
- **Inline release.** A writer that fails may mark its reservations
  `RELEASING` and run §6 immediately, without waiting for the settle window,
  only when **every** put it started ended definitively. After any ambiguous
  put, it must leave the reservation to the settle window.

### 5. The commit transaction (T2)

```
BEGIN;
SELECT … FROM workspace_storage_account
 WHERE workspace_id = :w FOR UPDATE;             -- one row per statement, ascending workspace_id
SELECT message_id, bytes, state FROM storage_reservation
 WHERE message_id = ANY(:ids) FOR UPDATE;        -- all present, all RESERVED, bytes = footprint,
                                                 --   else ROLLBACK → 451 (commit fenced)
INSERT INTO inbox_storage (...) … ON CONFLICT …  -- ensure a row for EVERY involved inbox (admitted and
SELECT … FROM inbox_storage … FOR UPDATE;        --   refused), then lock them in ascending inbox_id;
                                                 --   refusal records of THIS event (§5a); WHERE EXISTS (inbox)
INSERT INTO message … ON CONFLICT … DO NOTHING;  -- triggers: committed += raw
INSERT INTO attachment …;                        -- triggers: committed += size
SELECT pg_notify(…);                             -- messages, and refused inboxes (unchanged channel)
DELETE FROM storage_reservation WHERE message_id = ANY(:appended);
UPDATE storage_reservation SET state = 'RELEASING', release_not_before = now()
 WHERE message_id = ANY(:duplicates);            -- ADR-026 reprocessed event
COMMIT;
-- after commit: the duplicates' blobs are deleted (as today); §6 then releases them
```

- **T2 is atomic with respect to any snapshot.** The reservation delete and the
  `committed` increment happen in one transaction, so any snapshot sees those
  bytes in exactly one of the two places.
- **Duplicates are released immediately.** A duplicate's puts all succeeded
  (they are definitive), so it may be released as soon as its prefix is proven
  empty.
- **The lock order is total, and every path takes its locks in this order:**
  - `idempotency claim (ADR-033, CreateInbox only)`
  - `≺ guardAdmission advisory (ADR-027 §6, CreateInbox only)`
  - `≺ global admission (T1 only)`
  - `≺ workspace_storage_account rows (ascending)`
  - `≺ storage_reservation rows`
  - `≺ inbox_storage rows (ascending inbox_id, all taken up front: the O1
    trigger on message insert then finds them already held)`
  - `≺ inbox ≺ message ≺ attachment`
- **What each path takes:**
  - **T1** takes the global lock and otherwise only *creates* rows. It reads
    existing account rows through its snapshot and waits on no row lock (with
    the one `ON CONFLICT` exception of §3).
  - **T2** takes the account rows first.
  - **Cleanup (§6)** touches only reservation rows.
  - **The retention hard delete** changes shape. Today it is one autocommit
    `DELETE FROM inbox` (`JdbcInboxRepository.kt:136`), whose cascade runs one
    row trigger per message. Holding the account row for a whole large cascade
    would stall every T2 of that workspace (≈ 88 µs × rows: about 90 s for a
    million rows). It therefore becomes:
    1. Delete the inbox prefix (blob-first, unchanged).
    2. Delete messages in bounded transactions. Each one takes the account row
       `FOR UPDATE`, then runs `DELETE FROM message WHERE inbox_id = :i` over
       at most 1 000 ids.
    3. Delete the now-empty inbox row, again after taking the account row.

    The account row is never held longer than one chunk, and blob-first
    ordering still makes retention over-count only.
  - **Without the account lock, retention would deadlock with T2.** A T2
    holding the account row and waiting for `FOR KEY SHARE` on a second inbox
    of the same workspace would deadlock against a retention transaction
    holding that inbox and waiting for the account row.
- **Committing after the deadline is fine.** The deadline makes a reservation
  *eligible* for cleanup, but the reservation's state is what fences it:
  - If cleanup claimed the row first, T2 sees `RELEASING` and fails closed.
  - If T2 locked it first, cleanup's `UPDATE … WHERE state = 'RESERVED'`
    re-evaluates against the deleted row after the lock wait and does nothing.

#### 5a. Refusal records commit with the event's outcome

Recording a refusal before the event's fate is known would fire a waiter's
`409` for a copy that a `451` retry may later admit. It would also inflate the
count on every retry. So:

- **Where the refusal is recorded.**
  - If the event has admitted copies, its refusal records are written **inside
    T2**. They commit or roll back together with the event.
  - If every copy was refused, they are written in one short transaction after
    T1.
- **How the record is written.** For each refused inbox, in ascending
  `inbox_id`:
  - one upsert into `inbox_storage` (`refusal_count + 1`,
    `last_refusal_at = now()`, `last_refusal_reason`);
  - one `pg_notify` on the existing channel, with the inbox id as payload.
    ADR-020 atomicity holds: a woken waiter always sees the refusal it was
    woken for.
- **An inbox that has vanished** because retention removed it after
  resolution is skipped (`INSERT … SELECT … WHERE EXISTS`), not failed.
- **Failure is treated as database failure.** The records ride in T2, or in a
  transaction with nothing else to do, so their failure is a failing database.
  The event answers `451`, and its reservations go stale and are cleaned (§6,
  path B).

### 6. Reservation states, crash and orphan accounting

The state machine has two states and one deletion:

```
            T1 admits                         T2 commits (I4)
  (none) ───────────────▶ RESERVED ─────────────────────────────▶ (row deleted; bytes now committed)   [A]
                             │
                             │ write_deadline_at < now()  (cleanup claim; release_not_before = deadline + settle)
                             │ | T2 marks an ADR-026 duplicate      (release_not_before = now())
                             │ | writer's inline abort, all puts definitive (release_not_before = now())
                             ▼
                         RELEASING ── delete prefix · LIST prefix = ∅ · now() ≥ release_not_before ──▶ (row deleted) [B/C]
                             │                     │
                             │                     └─ LIST ≠ ∅ → delete again, release_not_before = now() + settle,
                             │                                   testinbox_storage_late_object_total++
                             └─ a message row with this id exists (impossible by I4) → release WITHOUT
                                deleting anything, alarm                                           [D]
```

- **`RESERVED → RELEASING` after the deadline.** This is a guarded
  `UPDATE … WHERE state = 'RESERVED' AND write_deadline_at < now()`. Batches
  are claimed with `FOR UPDATE SKIP LOCKED`, so several API nodes can run it.
  `settle` defaults to 10 min and must exceed `T_put`.
- **The first pass deletes objects immediately**, so physical usage falls at
  once. The *charge* stays until a later pass, at or after
  `release_not_before`, lists the prefix and finds it empty.
- **The settle window absorbs late writes.** It covers two cases: a request
  the storage server completes after the client gave up, and a process frozen
  between its cutoff check and its put. Either write lands before the
  verifying listing, is found, is deleted, and restarts the window.
- **Paths B and C are one procedure.** Path B is "no object was ever written",
  for example a crash straight after T1, so the first listing is empty. Path C
  is "orphans exist", so the first listing is not. There is no separate code
  path to get wrong.
- **Cleanup can never delete committed content.** A reservation row exists
  only while no message row with its id has committed: I4 deletes the row in
  the committing transaction, and an ADR-026 duplicate's id never gets a row.
  Cleanup still checks `NOT EXISTS (SELECT 1 FROM message WHERE id = :id)`
  before deleting, as defence in depth. The impossible case is released
  *without* deleting anything, which over-counts (the safe direction), and
  raises an alarm.
- **A storage outage during cleanup keeps the capacity charged.** The row stays
  `RELEASING` and is retried on every pass. Under-availability is the accepted
  failure; under-accounting is not.
- **`OrphanBlobSweep` stays as the unconditional backstop** for objects that
  have no message row and no reservation: pre-ADR-035 orphans, the residue of
  §9, and anything a defect leaves behind. Its bucket listing, which already
  runs every 30 min, is extended to return sizes, and that feeds the physical
  gauge (§7).

**Worked example (the case the owner named).**

| Time | What happens | Workspace and global charge |
|---|---|---|
| *t* = 0 | 10 MiB is reserved, both objects are written, and the process crashes before T2 | includes the 10 MiB |
| *t* = 120 s | the reservation becomes `RELEASING` and the objects are deleted | still includes the 10 MiB |
| *t* ≈ 12 min | the listing is empty and the 10 MiB is released | no longer includes it |

At no instant does the database hold less than the bucket. The
orphan-retention exposure described in the prompt cannot arise: stale
reservations stay charged, so a workspace in a crash loop is refused at its
ceiling, not after it.

#### 6a. Physical-failure circuit breaker

Suppose storage is down while the edge keeps retrying (every few minutes, for
up to 4 h). Every retry would reserve again under fresh message ids: the SMTP
adapter has no `providerMessageId`, so nothing is deduplicated. Every ambiguous
failure would leave a reservation charged for 120 s plus the settle window.
Stale charge would pile up until it filled the ceilings. After recovery, queued
mail would then be *refused* (`250` and discard, a permanent loss) instead of
admitted. A `451` retry would have been the right outcome.

The fix is a per-node breaker:

- **Opening.** Any physical failure (`quota`, `unavailable`, `timeout`) opens
  it.
- **While open**, ingestion answers `451` **before T1**. It creates no
  reservation and takes no lock.
- **Half-open.** After a backoff (default 15 s, doubling to 2 min), exactly
  one event is let through. Its outcome closes or re-opens the breaker.
- **Resulting bound.** Live reservations are at most in-flight copies plus one
  outage's worth of ambiguous copies per node. That in turn bounds the
  `Σreserved` scan inside T1.
- The breaker is per node and in memory. It is load shedding, not an
  invariant, so it needs no shared state.

### 7. Committed accounting and reconciliation

- **Triggers are the only routine writers of `committed_bytes`.**
  - They are row-level triggers on `message` (`raw_size_bytes`) and
    `attachment` (`size_bytes`).
  - They fire on `INSERT`, on `DELETE` (including cascades), and on an
    `UPDATE` of those columns or of `workspace_id` (applied as a delta).
  - They **upsert** (`INSERT … ON CONFLICT (workspace_id) DO UPDATE`), so a
    missing account row heals itself instead of losing an increment. A
    workspace created by an older binary is one case.
  - The only other writer is the reconciliation *repair* below.
  - There is **no `TRUNCATE` trigger**: `scripts/check-migration-safety.sh`
    refuses any statement containing `TRUNCATE`, and production never
    truncates. Test fixtures call `storage_account_recompute()` after
    truncating instead.
- **`reserved` is not a column.** It is `Σ storage_reservation.bytes` per
  workspace. So there is no second counter to drift, and releasing a
  reservation is simply deleting a row.
- **Reconciliation of `committed` (repair).** Periodically (default every 6 h),
  one `GROUP BY` pass reads every account's `committed_bytes` beside the
  ADR-027 §5 derivation, **in one statement**.
  - Triggers update both sides in the same transaction, so any difference is
    real drift, and detecting it needs no lock.
  - Only a differing account is locked `FOR UPDATE`, recomputed and written.
  - A repair emits `testinbox_storage_accounting_drift_total{direction}` and an
    operator log line. The log line carries the workspace id, as existing ops
    logs do (`api/ops/Ops.kt`); the metric never does.
- **Reconciliation of physical bytes (proof, not repair).** The orphan sweep's
  listing is summed as `testinbox_storage_physical_listed_bytes`. It is
  exported next to `testinbox_storage_accounted_bytes{kind}`. An alert fires
  if `physical > committed + reserved` persists beyond one sweep, which by I1
  should never happen.
- **ADR-024 carve-out.** ADR-024 requires every invariant-bearing write to go
  through exactly one application use case. Database triggers are such writes,
  deliberately outside any use case, because the cascade objection applies
  precisely to application code.
  - The carve-out is narrow: *derived accounting columns may be maintained by
    database triggers, provided an application use case (here
    `ReconcileStorageAccounting`) proves them against the source rows.*
  - ArchUnit cannot see triggers, so persistence tests 1–6 (§14) are their
    enforcement.
- **Placement (ADR-024).** The work is split as follows:
  - Ports in `application`: `StorageAdmission` (T1, the T2 reservation fence,
    and refusal records) and `StorageReservations` (the cleanup claim, listing
    and release).
  - Use cases: `ReceiveInboundDelivery` (extended), `ReleaseStaleReservations`
    and `ReconcileStorageAccounting`.
  - The permits and the `Ticker` live in `application`. `T_put` is a property
    of the `BlobStore` port.
  - The JDBC and S3 adapters implement the ports, and `ingestion` still only
    calls the use case.

### 8. Global ceiling: configuration and measured contention

- **`testinbox.storage.global-limit-bytes` is operational configuration.** It
  is not an API constant and not a contract value.
  - The initial production value is 40 GiB, under a 50 GiB bucket quota.
  - The 10 GiB margin must exceed the §9 residue bound.
- **`DeploymentSafety` (the ADR-034 fail-closed pattern) guards it.** It
  refuses to start a `production` ingestion node unless all three hold:
  - `global-limit-bytes` is set explicitly;
  - `testinbox.storage.declared-bucket-quota-bytes` is declared (Ops-supplied,
    as the edge ceiling is);
  - `global-limit ≤ declared-quota − max(1 GiB, 10 %)`.

  Staging uses the same rule with its own values. Local and test profiles
  default to effectively unlimited, as in ADR-027 §9.
- **Only ingestion enforces the global ceiling.** `CreateInbox` does not.
  Creating an inbox consumes no storage, and refusing it on a service-wide
  condition would disclose that condition on a surface that needs no such
  answer.
- **Node limits must be equal.** If ingestion nodes were configured with
  different global limits, the largest would be the effective bound. Ops must
  keep them equal. This is recorded but not enforced.
- **T1 commits synchronously.** Setting `synchronous_commit = off` for T1
  would shorten the serial section, and it is forbidden. The writer starts
  writing on the commit acknowledgement. A database crash that lost an
  acknowledged but unflushed reservation would leave objects that no row
  accounts for, a direct violation of I1.

**Contention, measured on 2026-09-28.** Setup:

- `postgres:16-alpine` 16.15 on Docker Desktop, on a 16-vCPU arm64 laptop;
- 26 workspaces and 200k standing messages;
- `pgbench` with 20 s per point.

The absolute numbers are not the production host's. The comparisons between
modes are the evidence. The scripts, the raw logs and a README are committed
beside this ADR in `docs/adr/0035-benchmark/`.

*Pass 1: closed loop at saturation, one event = T1 + T2 in one script, plus
retention deleting 200 rows once per 21 events (mean lock and row waits from
pass 2, run with `-r`):*

| Mode | Clients | Events/s | Event p50 / p99 | Lock / row wait (mean) | Retention p99 | Failed / deadlocks |
|---|---:|---:|---|---:|---:|---|
| **c**: no global ceiling (reference) | 16 | 11 341 | 1.2 / 2.6 ms | — | 3.9 ms | 0 |
| | 64 | 12 877 | 4.0 / 16.0 ms | — | 15.1 ms | 0 |
| **a**: global advisory lock in T1 only, derived sums (**chosen**) | 1 | 1 299 | 0.7 / 1.0 ms | — | 2.9 ms | 0 |
| | 8 | 2 019 | — | 3.1 ms | — | 0 |
| | 16 | 2 308 | 6.8 / 9.7 ms | 5.9 ms | 3.1 ms | 0 |
| | 64 | 1 968 | 31.0 / 45.9 ms | 32.8 ms | 3.7 ms | 0 |
| | 128 | 1 610 | — | 78.3 ms | — | 0 |
| **b**: one hot global row (T1 conditional `UPDATE`; T2 and retention triggers) | 1 | 1 529 | 0.5 / 0.8 ms | — | 4.2 ms | 0 |
| | 8 | 1 388 | — | 2.4 ms (T1), 2.5 ms (T2) | — | 0 |
| | 16 | 1 091 | 11.5 / 49.1 ms | 6.3 ms | 39.4 ms | 0 |
| | 64 | 706 | 70.4 / 322.5 ms | 41.9 ms | 207.6 ms | 0 |
| | 128 | 445 | — | 145.0 ms | — | 0 |

*Pass 2 (review follow-up): open loop at a fixed offered rate. T1, T2 and
retention run as separate scripts, so T1's latency is read on its own. 16
clients (two nodes' pools). 5 000 stale `RELEASING` reservations are
pre-seeded, which is the incident state, because `Σreserved` is scanned under
the lock:*

| Offered rate | Mode | Achieved/s | T1 p50 / p99 | T2 p99 | Retention p99 | Failed |
|---|---|---:|---|---:|---:|---:|
| 520/s (2×) | **c** reference | 521 | 2.3 / 8.9 ms | 9.9 ms | 13.2 ms | 0 |
| | **a** chosen | 527 | 3.0 / 10.1 ms | 8.4 ms | 11.5 ms | 0 |
| | **b** hot row | 520 | 2.5 / 14.4 ms | 14.6 ms | 18.9 ms | 0 |
| 1 040/s (4×) | **c** reference | 1 053 | 1.9 / 6.0 ms | 6.3 ms | 9.0 ms | 0 |
| | **a** chosen | 1 014 | 4.2 / **867.6 ms** | 857.9 ms | 921.7 ms | 0 |
| | **b** hot row | 1 036 | 3.7 / 296.7 ms | 291.4 ms | 284.5 ms | 0 |
| 1 040/s, **no backlog** (control) | **a** chosen | 1 037 | 2.5 / 7.2 ms | 6.1 ms | 8.7 ms | 0 |

(Latencies include open-loop schedule lag, i.e. what a caller waits.)

**Reading.**

- **One serialization point costs throughput.** At saturation, mode **a**
  plateaus near 2 000–2 400 events/s whatever the client count, which implies
  a serial section of about 0.4–0.5 ms. Mode **c**, with no global ceiling,
  reaches about 12 000/s.
- **Mode b degrades on every axis.** Throughput *falls* as clients are added,
  and retention serializes behind admissions, because commits and cascades
  queue on the same row.
- **The comparison is fair at an equal offered rate.** Pass 1 compared the
  modes at different throughputs, which confounds the retention comparison.
  Pass 2 fixes the offered rate. At 2× the expected load, even with 7 000 live
  reservations, mode **a** meets every part of the criterion below: T1 p99 is
  10.1 ms, and retention p99 is 11.5 ms against the reference's 13.2 ms.
- **Admission cost grows with the number of live reservations, and that is
  the design's real limit.** At 4× load, mode **a** saturates with the
  backlog (T1 p99 868 ms, worse than mode **b**), but not without it: the
  control run gives T1 p99 7.2 ms at the same rate. `Σreserved` is scanned
  under the global lock, so the serial section is linear in live reservation
  rows. What keeps that count at in-flight scale is §6's cleanup and §6a's
  breaker, so they are load-bearing for throughput as well as for
  correctness. A `storage_reservations` gauge above 1 000 is an alert (§13).
  If a real backlog must be admitted against at high rate, the remedy is a
  per-workspace reserved sum maintained by triggers on `storage_reservation`.
  That was not adopted because T1 would then wait on row locks while holding
  the global lock, and it is listed with the escrow fallback below.
- **The expected load sits well inside the plateau.**
  - Concurrency is capped by the ingestion pool (`TESTINBOX_DB_POOL_SIZE`, 8
    per node deployed).
  - The ADR-027 sustained `INGEST` rate of 10/s for each of 26 full
    workspaces is 260 events/s.
- **Caveat: this machine understates commit cost.** `COMMIT` measured about
  0.12 ms, which suggests the Docker Desktop VM is not paying a real `fsync`.
  The lock is held through commit, so on a production disk the serial section
  scales with real commit latency. At 1 ms per commit, the prediction is
  roughly 700 events/s.
- **Pass criterion, to be re-run on the staging host class before the global
  ceiling is enabled in production:**
  - at least **520 events/s offered** (2 × 260) is sustained open-loop;
  - T1 p99 is ≤ 50 ms;
  - retention p99 is within 2× the reference at the same offered rate;
  - there are zero deadlocks;
  - all of this holds at 1× and 2× the deployed pool, with a backlog of
    1 000 live reservations (the alert threshold) present;
  - the result is recorded in `docs/dev/production-ops-acceptance.md`, row G.

  The target is 2× and not more on purpose. The 1 ms prediction is about 2.7×,
  and a criterion the design is expected to miss would only get waived.
- **Fallback, if the criterion fails on real hardware:** per-node *escrow*
  slices of the global allowance, held in PostgreSQL. Each node pre-reserves a
  slice under the global lock and admits against it under a per-node lock.
  That takes the global lock off the per-event path, at the cost of at most
  one slice of stranded headroom per node. It is not built unless the
  measurement demands it.

### 9. Maximum provable physical overshoot

Let *G* be the global limit, *B* the committed total at the moment V6 is
applied, and *P(t)* the bytes of application-written objects in the bucket at
time *t*. Under I1–I5:

**P(t) ≤ max(G, B) + R**

The `max(G, B)` term exists because there is no grandfathering and no
eviction: content already above *G* stays readable until it expires, and
nothing new is admitted while the total is above *G*. Once that content has
expired, the bound is **G + R**.

The residue *R* has exactly these sources:

| # | Residue | Bound | Why it is outside I1 |
|---|---|---|---|
| R1 | A put that lands after its reservation was released. This needs either a storage server that completes an abandoned request more than *settle* after the client gave up, or a process frozen for longer than the rest of the deadline plus *settle* between its cutoff check and sending the request. | ≤ `ingestionNodes × max-concurrent-writes × 15 MiB`. Puts are single-attempt, so each permit covers one request; a resumed writer re-checks the cutoff before its *next* put. At the defaults: 1 × 16 × 15 MiB = **240 MiB**. | The application cannot fence a request it has already handed to the network. The writer's own abort path deletes its prefix. If that is lost too, `OrphanBlobSweep` removes the object within 1 h 30 min. |
| R2 | A pre-ADR-035 ingestion binary running against V6 (a rollback) | Not bounded by this ADR. It equals the ADR-027 bound for as long as that binary runs. | It does not reserve. Its commits are still counted, so enforcement resumes exactly when the binary is rolled forward. |
| R3 | A database restored from backup (ADR-034 §5) while the bucket is kept | Up to the bucket's pre-restore contents. After a restore, every object is an orphan: no `message` row survives a restore, by design. | Accounting restarts at zero. **Restore procedure:** the bucket is emptied, or ingestion's global limit is held at 0, until one full `OrphanBlobSweep` pass has completed. This becomes a production-ops acceptance item. |
| R4 | Accounting defects | Detected by §7 within one reconciliation interval | A defect is not a design bound. It is alarmed. |

**Explicitly not residue**, because each is charged until proven gone: crash
orphans (§6), duplicate-event blobs, retention (blob-first deletion only
over-counts), failed events, and breaker-shed events.

**Outside *P(t)*: infrastructure bytes.** MinIO's own metadata, erasure parity
and filesystem overhead are not application objects, and neither figure counts
them.

**Two storage preconditions** make *P(t)* meaningful, and Ops acceptance must
evidence both:

- bucket **versioning is off**, so a delete frees bytes instead of adding a
  delete marker over a retained version;
- there is **no object-lock or retention rule** on the bucket.

MinIO evaluates its bucket quota against its own periodically refreshed usage
figure, so it can itself be overshot. That is why the application ceiling, not
the bucket quota, is the bound this ADR proves, and why the margin exists.

With the initial values, **40 GiB + 240 MiB < 50 GiB**. That leaves a 9.76 GiB
margin for R1 and for MinIO's quota lag.

### 10. SMTP and provider behaviour

| Situation | Gateway reply to the edge | What is stored | Tenant sees | Operator sees |
|---|---|---|---|---|
| Syntactically invalid recipient or foreign domain | `553` at `RCPT` (unchanged) | nothing | — | `smtp_reject_total{reason}` |
| Raw message > 15 MiB | `552` at `DATA` (unchanged; a property of the sender's own message) | nothing | — | `smtp_reject_total` |
| Unknown, expired or deleted recipient | `250` (unchanged, ADR-025) | nothing | — | `unknown_recipient_discard_total` |
| `INGEST` rate exhausted | `250` (unchanged, ADR-027 §4) | nothing for that copy | — | `rate_decision_total{INGEST,refused}` |
| **Inbox or workspace ceiling** | **`250`** | nothing for that copy; `BlobStore` not called | refusal count, time and reason `WORKSPACE_LIMIT`; a wait ends with `409` | `storage_admission_total{refused_inbox\|refused_workspace}` |
| **Global application ceiling** | **`250`** | nothing for that copy; `BlobStore` not called | the same, with reason `SERVICE_CAPACITY` | `storage_admission_total{refused_global}` + **page** |
| Mixed event (A refused, B admitted) | `250` | B's copy only, committed atomically with B's siblings | A's inbox records the refusal, in B's T2 | both counters |
| Bucket quota reached or storage full | **`451`** for the whole `DATA`; nothing committed; breaker opens | stale reservations are cleaned (§6) | nothing: the edge retries | `storage_physical_failure_total{kind=quota}` + **page** |
| Storage outage, put timeout, breaker open | `451` for the whole `DATA` | the same | nothing | `{kind=unavailable\|timeout}`, `storage_breaker_open` |
| Write cutoff exhausted, or admission `lock_timeout` hit (load) | `451` for the whole `DATA` | the same | nothing | `{kind=cutoff}`, `admission_lock_wait_seconds` |
| Reservation lost before T2 (I4) | `451` for the whole `DATA` | the same | nothing | `storage_commit_fenced_total` |
| Database down or schema incompatible | `451` (unchanged) | nothing | — | existing metrics |
| ADR-026 reprocessed provider event | `250` / provider ack | no new row; its blobs are deleted, then its reservation released | — | `duplicate_event_noop_total` |

- **Ceilings answer `250`, never `452`.** The reasons are those of ADR-027 §1,
  in full. A `452` for a known recipient in a full workspace, beside a `250`
  for an unknown address, is a membership oracle, and an attacker can
  *drive* it by filling a workspace through one guessed `EXACT` address. It
  would also push tenant policy into the edge's queue. The global ceiling is
  the same kind of decision, application admission control, and gets the same
  answer. A `451` there would not reveal membership, but it would let one
  tenant's consumption delay every sender through edge retries.
- **Physical failure answers `451`.** It is independent of tenant state, and a
  retry is the right outcome for mail that may still be storable. It is not
  identical at the ingestion hop for every event: an event with only unknown
  recipients never reaches storage, so it gets `250`. That is pre-existing, and
  it is not observable from outside (next bullet).
- **In the deployed topology, the external sender only ever sees the edge.**
  - Postfix relays store-and-forward (ADR-004), so a `451` from ingestion
    becomes an edge queue retry.
  - The edge contract sends **no DSNs at all** (`notify_classes=""`,
    `bounce_queue_lifetime=0`). Nothing an ingestion reply says can reach the
    sender, and neither can the latency difference between refused and
    accepted copies.
- **A `451` that outlives the edge queue is silent loss.**
  `maximal_queue_lifetime` is 4 h, and the message then disappears with no
  DSN. The tenant sees nothing for these, because the copy never reached
  admission. Ops therefore needs an **edge-queue-age alert** (a new
  production-ops acceptance row). This is a pre-existing property of the edge
  that the breaker makes more likely to be exercised, so it is stated here.
- **Discard logging.** A discarded refusal uses the existing `smtp_discard`
  line, metadata only, with a closed-enum reason:
  `storage_inbox_limit | storage_workspace_limit | storage_service_capacity`.
- **Provider adapters** (future, ADR-003/004) map the same classification.
  Ceiling refusals *acknowledge* the event, because redelivery cannot help.
  Physical failures *do not acknowledge* it, so the provider redelivers.

### 11. Authenticated visibility contract

Everything here is specified first in `backend/api/contract/openapi.yaml`, in
the implementing PR (ADR-022). Field names follow the owner's
(`storageRefusalCount`, `lastStorageRefusalAt`).

**11a. Workspace storage state: `GET /v1/workspace/storage`.**

- It requires scope `messages:read`, like `getInbox`.
- It is charged to rate category `READ`, through an **explicit** pattern in
  `RateCategories`. Without one, the default-deny fallback would silently
  charge it as `INBOX_CREATE`.
- The workspace is the authenticated key's (ADR-027 §3). There is no path
  parameter to get wrong.

```json
{ "limitBytes": 2147483648, "storedBytes": 1990000000, "reservedBytes": 15728640,
  "availableBytes": 141754008, "overLimit": false }
```

- `storedBytes` is `committed`.
- `reservedBytes` includes stale and releasing reservations.
- `availableBytes = max(0, limit − stored − reserved)`.
- `overLimit` is `stored + reserved > limit`. For example, a workspace
  backfilled above its ceiling.
- **`availableBytes` is workspace headroom only, never global.** Global
  headroom moves with *other* tenants' traffic, so exposing it would give every
  tenant a live side channel on everyone else's ingest.

**11b. Inbox fields.** `Inbox` gains three fields, **always present**:

- `storageRefusalCount` (int64, starts at 0, monotonic for the inbox's
  lifetime);
- `lastStorageRefusalAt` (date-time or null);
- `lastStorageRefusalReason` (`WORKSPACE_LIMIT | SERVICE_CAPACITY` or null;
  clients must tolerate unknown values).

**Residual of O3.** A tenant with room can probe the global ceiling with its
own copies. Near *G*, that measures global headroom to within one copy's size.
It requires the service to already be at a paged incident, and workspaces are
operator-provisioned. It is recorded in the abuse model rather than engineered
away.

**11c. Wait termination.** On `POST /v1/inboxes/{id}/messages/wait`:

- **Contract changes.**
  - The request gains an optional `storageRefusalsSeen` (int64 ≥ 0).
  - Every `200` (`MATCHED` and `TIMEOUT`) gains `storageRefusalCount`: the
    baseline value that the server confirmed was *not* exceeded.
  - `TIMEOUT`, together with `TestInboxTimeoutError` and
    `TestInboxTimeoutException`, also carries it and `lastStorageRefusalAt`,
    beside `arrivedButUnmatchedCount`.
- **The baseline.**
  - It is `min(storageRefusalsSeen, current)` when the field is present. A
    value from the future cannot suppress a real refusal.
  - Otherwise it is the inbox's count as read at the call's initial check.
- **The check.** Whenever the wait finds no match (at the initial check, the
  recheck, and every wake), it reads the count. The count is read **before**
  the wait slot is claimed, so a refusal already present returns without
  consuming a slot. If the count exceeds the baseline, the wait ends:

```
409 application/problem+json
type: https://testinbox.email/problems/storage-limit-exceeded
{ "inboxId", "refusalReason": "WORKSPACE_LIMIT|SERVICE_CAPACITY",
  "refusalsDuringWait": n, "storageRefusalCount": c, "lastStorageRefusalAt": t,
  "quota": "storedBytes", "limit": …, "current": … }      ← the last three only for WORKSPACE_LIMIT
```

  - There is no `Retry-After`: waiting does not recover a discarded copy
    (ADR-027 §8).
  - `detail` names the cause in plain words. A Kotlin SDK built before this
    change drops `problemType` on unknown 409s (`Errors.kt:53-58`), so `detail`
    is all it will show.
- **Precedence**, first match wins:
  1. request-rate `429` (charged before routing);
  2. `404` (not the caller's inbox);
  3. `410` (not `ACTIVE`);
  4. a match: `200 MATCHED` wins even if refusals also happened;
  5. `409 storage-limit-exceeded`;
  6. `429` slot refusal;
  7. `200 TIMEOUT`.
- **The SDK seeds the cursor (O2).** The first call sends
  `storageRefusalsSeen` from the `Inbox` object the SDK holds: 0 for an inbox
  it just created, otherwise the last value it saw. Later calls send the value
  returned by the previous response.
  - A copy refused between "trigger the send" and "wait" therefore ends the
    wait. The same holds for a refusal between two chained calls.
  - No node or client clock is compared, and no server state is kept, so the
    cursor survives SDK chaining, node failover and client restarts.
  - Raw-API callers may omit it and get "since this call began".
- **Why a `409` problem and not a new `200` status.** Both SDKs treat any
  unknown `status` as `TIMEOUT` and chain again, as ADR-020 told them to
  (`client.ts:238-277`, `TestInboxClient.kt:248-255`). A new status would be
  swallowed silently by any SDK build already in a consumer's hands: none is
  published yet (`docs/sdk/distribution.md`), but snapshot builds exist. A
  `409` surfaces at once as the generic conflict error in those builds, and
  neither SDK retries a `409`. New SDKs throw a typed error, and they
  discriminate on the problem `type`, never on the status code.
- **Compatibility (amends ADR-015 explicitly).** Everything else in this
  section is additive: new optional request and response fields, a new
  endpoint, and new optional members. This one is not, because the same
  request that used to end `200 TIMEOUT` can now end `409`. It is accepted as a
  deliberate behaviour change under `X-API-Stability: experimental`.
  `docs/api/versioning.md` gains the rule it follows: *a new problem type for a
  new failure condition on an existing operation; clients must handle unknown
  4xx generically.*
- **Granularity is the inbox, deliberately.** A refused copy is discarded
  before anything could evaluate it against a waiter's matcher. Storing the
  fields a matcher needs (subject, sender) would store part of the content
  this ADR just refused to keep.
  - So every waiter on the inbox, whatever its matcher, ends on any refusal
    after its baseline, and the problem says the refused copy *may* have been
    the awaited one.
  - The failure mode this accepts is an unrelated copy refused and the awaited
    one admitted later within the same wait. That requires the workspace to
    cross its ceiling and recover within one wait, and it fails a test with an
    exact cause instead of passing it.
  - The failure mode it removes is a test that silently times out on a refused
    copy.
- **Notification.** The refusal record `pg_notify`s the inbox's channel
  (§5a), so parked waiters wake. Under LISTEN degradation, the ADR-020 bounded
  re-query reads the counter as well.

**11d. SDK surface** (ADR-014: hand-designed; the transport stays internal).

| TypeScript | Kotlin / JVM |
|---|---|
| `TestInboxStorageLimitExceededError` extends `TestInboxError` directly, like `TestInboxQuotaExceededError` | `TestInboxStorageLimitExceededException` extends the base directly, and passes `problemType` and status 409 |
| Error fields: `refusalReason`, `refusalsDuringWait`, `storageRefusalCount`, `lastStorageRefusalAt`, `inboxId` | the same fields |
| `client.getWorkspaceStorage(): Promise<WorkspaceStorage>` | `getWorkspaceStorage()` / `getWorkspaceStorageBlocking()`, returning `WorkspaceStorage` |
| `Inbox` gains `storageRefusalCount`, `lastStorageRefusalAt`, `lastStorageRefusalReason` | the same |

- The cursor is threaded inside the existing chaining loop and is not public
  API.
- The error is terminal: the SDK does not retry it.
- Both problem parsers (`asProblemDetails`, `ProblemDto`) learn the new
  members, and the shared `Problem` schema declares them.

### 12. Migration V6 and backfill

`V6__physical_storage_accounting.sql` is **expand-only** and carries no
`-- testinbox:rollback-unsafe:` declaration. It contains, in order:

1. **`workspace_storage_account`.**

   ```
   workspace_id   uuid PRIMARY KEY REFERENCES workspace (id)
   committed_bytes bigint NOT NULL DEFAULT 0
   reconciled_at  timestamptz
   ```

   There is no `CHECK (committed_bytes >= 0)`. A negative value is drift, and
   a constraint violation there would abort retention deletes and wedge the
   sweep. Reconciliation reports it instead.
2. **`storage_reservation`.**

   ```
   message_id         uuid PRIMARY KEY
   workspace_id       uuid NOT NULL REFERENCES workspace (id)
   inbox_id           uuid NOT NULL                -- no FK, §4
   object_prefix      text NOT NULL
   bytes              bigint NOT NULL CHECK (bytes > 0)
   state              text NOT NULL CHECK (state IN ('RESERVED', 'RELEASING'))
   created_at         timestamptz NOT NULL
   write_deadline_at  timestamptz NOT NULL
   release_not_before timestamptz
   ```

   Indexes:
   - `(workspace_id) INCLUDE (bytes)`
   - `(inbox_id) INCLUDE (bytes)`
   - `(state, write_deadline_at)`

   Deadlines drive transitions; they never appear in index predicates (the
   ADR-021 rule).
3. **`inbox_storage`.**

   ```
   inbox_id                  uuid PRIMARY KEY REFERENCES inbox (id) ON DELETE CASCADE
   workspace_id              uuid NOT NULL
   committed_bytes           bigint NOT NULL DEFAULT 0   -- O1
   refusal_count             bigint NOT NULL DEFAULT 0
   last_refusal_at           timestamptz
   last_refusal_reason       text
   ```

   It is a side table, so the hot `inbox` row and older binaries' mapping of
   it are untouched. The cascade is correct here, because this table holds no
   capacity.
4. **The explicit lock, then the triggers, then the backfill.**
   - `LOCK TABLE message, attachment IN SHARE ROW EXCLUSIVE MODE`.
   - The trigger functions and triggers (§7), and
     `storage_account_recompute()`.
   - The backfill: `INSERT INTO workspace_storage_account SELECT w.id,
     <ADR-027 §5 derivation> FROM workspace w`, plus the O1 per-inbox backfill.

   The explicit lock blocks concurrent inserts and deletes for the length of
   the migration transaction. That makes the backfill exact against anything
   an old binary does meanwhile, instead of relying on the lock `CREATE
   TRIGGER` takes as a side effect. While the backfill runs (one sequential
   sum, seconds at today's volumes), ingestion commits wait. A commit that
   times out answers `451`, and the edge retries.

**No grandfathering, no grace, no eviction.** A workspace whose backfilled
`committed_bytes` exceeds its limit starts refusing new copies the moment an
ADR-035 ingestion binary runs. Its content stays readable, and capacity
returns only through ADR-009 expiry and explicit deletion. `CreateInbox`'s
`409 quota-exceeded` rule is unchanged, but it now reads `committed +
reserved` from the account row instead of deriving it.

**Old binaries while V6 is present** (ADR-029: every V6 rollout, not only a
rollback):

- They neither read nor write the new tables.
- Their inserts and deletes are counted by the triggers, so accounting stays
  exact.
- They do not reserve, so their traffic is not ceiling-enforced (R2).
- The triggers make them take account rows in envelope order and inside their
  own inbox-locking transactions. That adds lock-order cycles that PostgreSQL's
  deadlock detector resolves by aborting one side:
  - old ingest vs old ingest (two workspaces in opposite orders);
  - old ingest vs old retention (inbox then account, against account then
    inbox);
  - old retention vs new T2.

  An aborted ingest answers `451` and the edge retries. An aborted sweep
  retries on its next interval. This is liveness during a rollout window, not
  correctness. It ends when both deployables run ADR-035 code.

**Backups (ADR-034 §5).** All three tables are classified `-` in
`deploy/backup/scope.txt`. They are derived or transient. After a restore,
`message` is empty, so an empty account table is *correct*; the §9 R3 procedure
covers the bucket.

### 13. Observability

All labels are closed enums. No workspace, inbox, key or address ever appears
in a label.

| Metric | Type | Labels |
|---|---|---|
| `testinbox_storage_admission_total` | counter | `outcome` = `admitted \| refused_inbox \| refused_workspace \| refused_global` |
| `testinbox_storage_admission_lock_wait_seconds` | timer | — |
| `testinbox_storage_accounted_bytes` | gauge | `kind` = `committed \| reserved` (global sums) |
| `testinbox_storage_global_limit_bytes` | gauge | — |
| `testinbox_storage_reservations` | gauge | `state` = `reserved \| releasing` |
| `testinbox_storage_reservation_released_total` | counter | `path` = `committed \| absent \| deleted \| reconciled` |
| `testinbox_storage_late_object_total` | counter | — |
| `testinbox_storage_commit_fenced_total` | counter | — |
| `testinbox_storage_physical_failure_total` | counter | `kind` = `quota \| unavailable \| timeout \| cutoff \| lock_timeout` |
| `testinbox_storage_breaker_open` | gauge | — |
| `testinbox_storage_accounting_drift_total` | counter | `direction` = `under \| over` |
| `testinbox_storage_physical_listed_bytes` | gauge | — |

`WaitOutcome` gains `STORAGE_LIMIT_EXCEEDED`.

Alerts fire on any of:

- accounted bytes ≥ 90 % of *G*;
- any `refused_global`;
- any `physical_failure{quota}`;
- the breaker open for more than 5 min;
- any `late_object`;
- any drift;
- a `RELEASING` reservation older than 1 h;
- more than 1 000 live reservations (admission cost is linear in them, §8);
- `physical_listed > committed + reserved` beyond one sweep;
- edge queue age (Ops, §10).

### 14. Test plan

The layers follow `docs/quality/strategy.md`:

- unit and property tests for pure logic;
- Testcontainers (Postgres, MinIO) for storage;
- real SMTP to the gateway for SMTP behaviour;
- concurrency tests that are **deterministic**, using the seams below and never
  sleeps.

Every module this touches ratchets its minimum in `verify-test-results.sh` to
its new count in the same PR.

**Seams.** These are required by the implementation. Without them the crash
tests cannot be built.

- **DB time.** Tests move time by back-dating `write_deadline_at` and
  `release_not_before` with SQL. That is equivalent to advancing the database's
  `now()`. Production SQL keeps `now()`, and *settle* and the deadline keep
  their production values.
- **Monotonic time.** An injectable `Ticker` drives the write cutoff.
- **`IngestSyncHook`** (production no-op, like `WaitSyncHook`), with the
  points `afterAdmission`, `beforePut`, `afterPuts`, `beforeCommit`, and
  `inCommitAfterReservationLock`.
- **`CleanupSyncHook`**, with the points `afterClaim` and
  `afterDeleteBeforeList`.
- **"Is blocked"** is proven by polling `pg_blocking_pids`, following the
  `JdbcIdempotencyRecordsTest` pattern, never by sleeping.

**Accounting**

1. **Triggers.** After an insert, a delete, an `ON DELETE CASCADE` from
   `inbox`, an attachment insert or delete, a column update, and a
   truncate followed by `storage_account_recompute()`, `committed` equals the
   ADR-027 derivation.
2. **Property test.** Random interleavings of append, duplicate append,
   inbox delete and chunked hard delete keep `committed == derivation` for
   every workspace, and for every inbox under O1.
3. **Attachments are counted twice.** Two attachments charge
   `raw + a1 + a2`. A parse failure charges `raw` only.
4. **V6 backfill.**
   - Start from the V5 schema with data. Latch old-style inserts, run V6 while
     they are held, then release them.
   - Every account equals its derivation.
   - A second connection is shown blocked by `pg_blocking_pids` during the
     backfill.
5. **Reconciliation.**
   - Corrupt one account up and one down, then reconcile: both are repaired,
     and `drift_total{over}` and `{under}` each rise by 1.
   - A clean run emits nothing and takes no row lock.
6. **Restore simulation.** A missing account row heals on the next insert.

**Admission**

7. **Workspace boundary.** `used + f == limit` is admitted; `limit + 1` is
   refused.
8. **Global boundary.** `Σused + f == G` is admitted; `G + 1` is refused.
9. **Mixed event.** An event with W1 full and W2 with room: W1's copy is
   refused and never `put` (asserted with a recording `BlobStore`), W2's copy
   commits, and the reply is one `250`.
10. **Owner's global-cap test.** W1 and W2 are each below quota, but the
    combined candidate exceeds *G*. The candidate is refused with
    `SERVICE_CAPACITY`, and zero puts happen for it.
11. **Owner's concurrent test.**
    - (a) Deterministic: the test itself holds
      `pg_advisory_xact_lock(storageClass, global)`, starts T1 (shown blocked),
      commits a competing reservation, then releases. T1 must refuse.
    - (b) Load: 32 events across 8 workspaces with *G* sized below total
      demand. Refusals must actually occur. A sampler reading `Σcommitted +
      Σreserved` in one statement never observes more than *G*, and neither
      does the final real MinIO listing.
12. **Single snapshot.**
    - A *demonstration*: a T2 latched between two separate reads shows the
      two-statement form under-counts.
    - An *adapter assertion*: T1's usage read is exactly one statement.
13. **Precedence.** A copy refused by both the workspace and global ceilings
    is recorded as `WORKSPACE_LIMIT`. Under O1, the inbox ceiling is checked
    first, and a flood of one inbox leaves a sibling inbox admitting.
14. **Over-limit after backfill.**
    - Every message stays readable (`GET`, `/raw`, attachments).
    - New copies are refused, and no row is deleted.
    - After a retention hard delete frees space, the next copy is admitted.
    - `CreateInbox` returns `409 quota-exceeded` at `committed + reserved ≥
      limit`.
15. **T1 skipped.** An event with only unknown recipients never takes the
    global lock. The test holds the lock and the event still completes.
16. **`lock_timeout`.** With the lock held beyond the timeout, the event
    answers `451` and `physical_failure{lock_timeout}` rises by 1.
17. **Synchronous commit.** T1 asserts
    `current_setting('synchronous_commit') = 'on'` inside the transaction.

**Crash accounting and the state machine**

18. **Owner: reservation, blobs, crash.**
    - The writer's puts complete, then a crash is simulated at `beforeCommit`,
      and the deadline is back-dated past 120 s.
    - Cleanup marks the reservation `RELEASING` and deletes the objects.
    - Still charged: proven through *admission*. A candidate that would fit
      only if those bytes were free is refused.
    - After back-dating `release_not_before`: the listing is empty, the
      reservation is released (`released_total{deleted}`), and the same
      candidate is admitted.
19. **Owner: expiry before any write.** A crash at `afterAdmission`. After the
    settle window, the listing proves absence and the reservation is released
    (`released_total{absent}`).
20. **Late object.** At `afterDeleteBeforeList`, the test puts an object
    under the prefix. The listing finds it, deletes it, pushes
    `release_not_before` forward and increments `late_object_total`. The
    charge is still held.
21. **T2 fenced.** The writer is held at `beforeCommit`, the deadline is
    back-dated, and cleanup claims the reservation. The writer's T2 then
    fails with `451`, no row is written for any recipient of the event
    (ADR-026), and `commit_fenced_total` rises by 1.
22. **T2 against cleanup, both orders.** At `inCommitAfterReservationLock`,
    cleanup is shown blocked and then does nothing. In the reverse order, T2
    fails. Exactly one wins, never both and never neither.
23. **Write cutoff.** The `Ticker` is advanced past the cutoff at `beforePut`.
    The next put is not started, the event answers `451`, and the waiting
    time for a permit is counted against the cutoff.
24. **Real timeout.** A put against a TCP proxy that stalls MinIO ends within
    `T_put`, is classified as ambiguous, and there is no inline release.
25. **Definitive errors.** Inline release after definitive errors is
    immediate.
26. **ADR-026 duplicate event.** The reservation is released only after its
    blobs are deleted. A crash between the commit and the blob deletion is
    recovered by cleanup.
27. **Storage down during cleanup.** The reservation stays `RELEASING` and
    charged across passes, and is released after storage returns.
28. **Inbox hard-deleted while `RESERVED`.** The reservation survives (no
    cascade), and its bytes stay charged until the prefix is proven empty.
29. **Two cleaners.** Both are held at `afterClaim`. Each reservation is
    released exactly once.
30. **Lock order, deterministically.** T2 holds W's account row at
    `inCommitAfterReservationLock`. A retention chunk on a second inbox of W
    is shown blocked on the account row, not on the inbox. Both then
    complete, with no deadlock.
31. **Path D.** A message row with a reservation's id is forced to exist.
    Cleanup releases the reservation without deleting any object and raises
    the alarm.
32. **Chunked retention.** A 50k-message inbox is deleted in chunks. The
    account row is never held longer than one chunk (a concurrent T2 is shown
    to progress between chunks), and the final count equals the derivation.
33. **Breaker.** On a physical failure the breaker opens. The next event
    answers `451` without T1 and without a reservation row. The half-open
    trial closes it.
34. **Security review follow-up (outage and edge retries).** An outage plus
    repeated retries of the same queued message: after recovery, the message
    is *admitted*, not refused.

**SMTP and anti-oracle**

35. **Owner's anti-oracle test.**
    - The full SMTP transcript (codes and text for `RCPT` and `DATA`) is
      byte-identical for four recipients: an *admitted* known recipient, an
      unknown recipient, an over-ceiling workspace, and the global ceiling.
      Each is tested alone and each mixed with an admitted recipient.
    - Non-vacuity: for each refused case, the refusal row with its reason,
      the `refused_*` counter, zero objects under the prefix and no message
      row are all asserted.
36. **`451` classification.** A fault-injecting S3 endpoint returns MinIO's
    quota-exceeded error. The result is `451` for the whole `DATA`, nothing
    committed, the stale reservations cleaned, and the breaker open. A real
    `mc quota` check lives in the rehearsal only, because MinIO's quota lags.
37. **MinIO stopped.** Using its own container, not the shared one: `451`.
    After a restart and a retry: `250`, and the message is committed.
38. **Regression.** `> 15 MiB` still answers `552`, unknown still `250`, and
    invalid still `553`. The gateway's `maxRecipients` equals the value pinned
    in `contract.yaml`.

**Wait UX**

39. **Owner's test.**
    - A refusal lands at `afterSubscribe`, and separately at
      `afterInitialCheck`. Each is driven through real ingestion, so notify
      atomicity is exercised.
    - The wait window is well above the test's `future.get` timeout.
    - The result is `409 storage-limit-exceeded`, not `TIMEOUT`.
40. **Baseline.**
    - A refusal before the baseline does not end the wait when
      `storageRefusalsSeen` equals the current count.
    - It does end it when the SDK sends a cursor of 0.
    - A cursor larger than the count is clamped.
41. **Between chained calls.** A refusal between two chained calls ends the
    next call, via the cursor.
42. **Outcome ordering.** A match and a refusal together give `MATCHED`. A
    refusal plus exhausted slots gives `409`, with no slot consumed.
43. **Scope of termination.** A refusal on another inbox does not end the
    wait. A refusal ends every concurrent waiter on its own inbox.
44. **Degraded LISTEN.** The refusal is still detected with the LISTEN
    connection killed.
45. **Unchanged errors.** A cross-tenant wait is still `404`, and a
    non-active inbox is still `410`.
46. **SDKs (TypeScript and JVM).**
    - The typed error and its fields.
    - The cursor is threaded and seeded from the `Inbox`.
    - An old-SDK fixture surfaces a generic conflict, not a timeout.
    - The SDK CI jobs gain a count floor.

**Visibility, metrics, deployment**

47. **Workspace storage endpoint.** `GET /v1/workspace/storage` equals the
    accounting. `availableBytes` clamps at 0 and `overLimit` is set. The
    endpoint is isolated per workspace, charged `READ` (`RouteCoverageTest`),
    and requires `messages:read`.
48. **Inbox fields.** They are always present and they update.
49. **Metric cardinality.** The test is extended to every new metric and to
    `WaitOutcome.STORAGE_LIMIT_EXCEEDED`: closed enums only, and no
    identifiers.
50. **`DeploymentSafety`.** Production refuses to start with a missing global
    limit, a missing declared quota, or `G > quota − margin`.
51. **ArchUnit.** The reservation, cleanup, reconciliation, permit and
    `Ticker` types live in `application`, and `ingestion` reaches storage only
    through the use case.
52. **Gates.** The migration-safety gate passes (no `TRUNCATE`), and the
    backup-scope gate classifies the three new tables.

**Physical proof** (ephemeral rehearsal only, never `deploy/synthetic`
against deployed staging, because it would fill staging to *G* and page)

53. **Owner's physical-proof test.**
    - Setup: a dedicated database and bucket, a small *G* and a declared
      quota satisfying `DeploymentSafety`, and versioning asserted off.
    - Drive ingestion across several workspaces until both
      `refused_workspace > 0` and `refused_global > 0`.
    - Check `listed ≤ committed + reserved` (I1) at three checkpoints: after
      the fill, after a crash injected at `beforeCommit` with cleanup paused,
      and after cleanup.
    - Finally, check `G − largest copy ≤ listed ≤ G`. R1 is zero because no
      freeze is injected, so the fill provably reached the ceiling and never
      passed it.

**Explicitly untested (stated, not implied):**

- the mixed-version deadlock cycles of §12 (resolved by PostgreSQL's
  detector);
- ingestion nodes configured with different values of *G* (§8);
- provider-adapter mapping (no provider adapter exists yet);
- alert rules (Ops-owned);
- the R1 frozen-process case (not injectable deterministically; bounded by
  arithmetic, not by a test).

## Amendments to Accepted ADRs (effective on acceptance)

- **ADR-027 §2.** The paragraph "Storage quota is admission control on
  tenant-initiated growth, not on inbound mail", and the overshoot bound that
  follows it, are superseded by §3 and §9. `CreateInbox`'s admission rule
  stands.
- **ADR-027 §4.** "This is the one place mail addressed to a live inbox is
  dropped" no longer holds. A storage-ceiling refusal is a second such
  place, and unlike `INGEST` it is visible to the tenant.
- **ADR-027 §5.** "Quota usage is derived, never accounted" is superseded for
  stored bytes by trigger-maintained accounting with reconciliation (§7).
  `maxActiveInboxes` remains derived.
- **ADR-027 Alternatives and Consequences.** "Accept-and-drop" and "maintained
  usage counters" are adopted in a form that answers their rejection reasons.
  "Evicting the oldest messages" stays rejected (I6). "No usage table" and the
  overshoot bound are superseded.
- **ADR-020 §3.** A wait gains `409 storage-limit-exceeded` (§11c).
- **ADR-024.** One carve-out: derived accounting columns may be maintained by
  database triggers, provided a use case proves them (§7).
- **ADR-015 / `docs/api/versioning.md`.** A new problem type for a new failure
  condition on an existing operation is permitted within v1 while experimental
  (§11c).
- **CLAUDE.md invariant 8.** "Quota usage is derived from real rows, never a
  counter" becomes: *derived or database-maintained and reconciled; never an
  application-maintained counter*.

## Alternatives considered

- **`452` for tenant quota.** Rejected for the reasons in ADR-027 §1 (the
  oracle and the cross-tenant denial of service). Nothing in this ADR weakens
  them.
- **`451` for the global ceiling.** Rejected (§10). It would convert
  application admission control into edge retry traffic that one tenant can
  impose on every sender.
- **Freeing a reservation when its 120 s TTL elapses.** Rejected by the owner
  and by I1. The database would forget bytes that may physically exist.
- **Evicting the oldest messages to make room.** Rejected (I6).
- **Grandfathering, or a grace period.** Rejected by the owner.
- **A single hot global accounting row.** Measured in §8 and rejected.
- **Deriving stored bytes per delivery** (ADR-027 §5 as written). Measured at
  26–45 ms per large workspace.
- **Application-maintained counters.** Rejected: the cascade objection applies
  to them.
- **Statement-level triggers with transition tables** in place of row-level
  triggers plus chunked deletes. Not adopted. Row-level firing on cascade is
  measured; statement-level firing on cascade is not. Chunking bounds lock
  hold time regardless of trigger shape.
- **Redis, or any in-memory global counter.** Rejected (ADR-006, and the owner's
  instruction).
- **Counting PostgreSQL metadata.** Rejected by the owner.
- **Evaluating the waiter's matcher against the refused copy.** Rejected
  (§11c).
- **A new `200` wait status instead of a `409`.** Rejected (§11c).
- **Exposing global headroom to tenants.** Rejected (§11a).
- **Hysteresis on the global ceiling to blunt probing.** Not adopted (§11b
  residual). It adds shared state for an incident-only signal.

## Consequences

- **Stored bytes are bounded at ingest** by the inbox (O1), workspace and
  global ceilings. The ADR-027 overshoot of about 12.4 TiB per workspace
  becomes *G* + 240 MiB service-wide, once any backfilled excess has expired.
- **Mail to a live inbox can now be dropped because of storage, but never
  silently.** The tenant sees it on the inbox, on the workspace, and as a
  typed, immediate wait failure. The sender still sees only `250`.
- **A crash costs temporary capacity, never accounting accuracy.** The cost is
  up to 120 s + *settle* per stale copy.
- **Code changes by component:**
  - `ReceiveInboundDelivery` gains T1, the write cutoff, a fenced T2, the
    refusal records and the breaker.
  - The retention hard delete becomes chunked and transactional.
  - The API scheduler gains cleanup and reconciliation.
  - `S3BlobStore` gains a real timeout and single-attempt puts.
  - The gateway gains explicit connection and recipient bounds.
- **Schema.** Three tables, the trigger functions, and one expand-only
  migration.
- **Documents to update in the implementing PR:**
  - `docs/api/v1-design.md` and `docs/api/versioning.md`;
  - `docs/architecture/wait-semantics.md`, `inbound-mail-flow.md`,
    `failure-modes.md`, `observability.md` and `data-ownership.md`;
  - `docs/security/abuse-model.md` §4. The anti-enumeration statement is
    **preserved**; the O1 and O3 residuals are added.
  - `docs/dev/production.md` and `production-ops-acceptance.md`: row G (the
    bucket quota above *G* + residue, versioning off, no object lock, and the
    benchmark result), plus new rows for the restore procedure (R3) and the
    edge-queue-age alert;
  - `deploy/backup/scope.txt`;
  - `deploy/mail-edge/contract.yaml` (`maxRecipients`);
  - CLAUDE.md invariant 8;
  - the SDK READMEs.
- **Multiple workspaces remain a bypass.** Every workspace-scoped limit can
  still be bypassed with a second workspace (ADR-027 Consequences). The
  global ceiling is what now bounds the service however many workspaces
  exist.
