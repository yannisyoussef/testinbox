# ADR-035: Physical Storage Bound at Ingest

**Status:** Accepted (2026-09-29, owner acceptance TI-DEC-001b). This is revision 5 (2026-09-29).

> **Acceptance authorizes implementation, not enablement.** It does not turn
> on `testinbox.storage.enforcement`, global capacity enforcement in staging
> or production, a production TestInbox deployment, or SMTP/MX. Every gate in
> §18 remains binding: the implementation gates, the enablement gates
> (activation barrier, staging-host-class benchmark, valid storage
> qualification), the production Ops prerequisites (including a separately
> qualified amd64 backend with `slow-W`), and edge queue alerting before any
> public MX.

This revision incorporates the owner's decisions O1–O4 (TI-DEC-001), the facts
measured to settle them, and the four focused re-reviews that followed:
storage and failure, database and rollout, API and SDK, and security.

Revision 5 answers the final owner review (TI-DEC-001a). It replaces the
unproven `C_max = 9 min` with a basis, a qualification and a compatibility
contract (§7, §9, §9a).

**Amends** (effective 2026-09-29):

- [ADR-027](0027-rate-limiting-and-resource-quotas.md) §2 (the storage-quota
  paragraph and its overshoot bound), §4 (the sentence naming `INGEST` as the
  only place mail for a live inbox is dropped), §5 (usage derived, never
  accounted), and the matching Alternatives and Consequences entries.
- [ADR-020](0020-wait-reliability-and-timeout-semantics.md) §3, by adding one
  opt-in, authenticated-only wait outcome.
- [ADR-024](0024-application-layer-and-dependency-rule.md), with one narrow
  carve-out for accounting maintained by the database (§10).

**Leaves unchanged:**

- [ADR-015](0015-rest-compatibility-versioning.md): every contract change is
  additive, and the new wait outcome is opt-in (§13c).
- [ADR-025](0025-unknown-recipient-handling.md): no SMTP reply changes.
- [ADR-026](0026-recipient-scoped-provider-delivery-identity.md): the event is
  still the unit of commit.
- [ADR-019](0019-inbound-deduplication-semantics.md): nothing is deduplicated.
- [ADR-009](0009-retention-lifecycle.md): no lifecycle transition is added.
- ADR-027's own text. Its superseded passages only carry a pointer here.

**Evidence** (all in [`0035-benchmark/`](0035-benchmark/)):

- the contention benchmark, run on a laptop and not re-run for this revision;
- `minio-probes/RESULTS.md`, 25 behaviour probes against the pinned MinIO
  release.

## 0. Owner decisions (TI-DOC-001, TI-DEC-001)

| # | Decision | Where |
|---|---|---|
| — | Authenticated refusal visibility. No grandfathering, no eviction. `maxStoredBytes` stays at 2 GiB. A global application ceiling of 40 GiB, under a 50 GiB bucket quota. The 120 s TTL is a **write deadline, not a release**. Accounting is physical. Ceilings answer `250`; physical failure answers `451`. | throughout |
| **O1** | **Accepted with refinement.** A per-inbox hard ceiling, expressed as a *policy share* of the workspace limit: default 25 %, which is 512 MiB today. Authenticated clients can discover it. Refusal reason `INBOX_LIMIT`. | §3, §13b |
| **O2** | **Accepted with an explicit protocol.** The boundary is a cursor on the wire (`afterStorageRefusalCount`), not hidden SDK state. | §13c |
| **O3** | **Accepted.** `SERVICE_CAPACITY` discloses one bit and no global figure. | §13d |
| **O4** | **Accepted in principle.** Each supporting control is kept only where it protects a named invariant or bound. | §15 |

No design question remains open. The implementation gates, enablement gates
and Ops prerequisites are listed in §18.

## Context

ADR-027 §2 deliberately did not enforce `maxStoredBytes` on inbound mail. Its
storage bound was therefore `maxStoredBytes` plus the `INGEST` rate × the TTL
ceiling. It gave three reasons:

1. **The SMTP reply must not carry quota state.** A distinct reply would be a
   membership oracle, and a deferral would be a cross-tenant denial of service.
2. **Accept-and-drop manufactures a false negative.**
3. **A counter cannot be maintained through `ON DELETE CASCADE`.**

What has changed is measured, not argued:

- **The overshoot is not small.** At the ADR-027 defaults it is about
  12.4 TiB per workspace, against a 50 GiB bucket.
- **Objection 3 is refuted.** Both kinds of trigger fire for rows that
  `ON DELETE CASCADE` removes:
  - row-level triggers (2026-09-26);
  - statement-level triggers with transition tables (2026-09-29).
    PostgreSQL batches these per cascade: a cascade over 50 000 messages
    wrote one ledger row per (workspace, inbox) in 0.25 s, and the ledger
    matched the source rows exactly.
- **Deriving usage per delivery is too slow**: 26–45 ms per large workspace.
- **Objection 2 has an answer that objection 1 permits.** The sender is told
  nothing. The authenticated tenant is told everything about its own scopes.

## Decision

### 1. Invariants (normative)

**Definitions.**

- A **TestInbox-owned payload object** is an object under a key
  `{workspace}/{inbox}/{message}/…` created by the ADR-035 write path (§5).
  - Objects written by pre-activation binaries are brought under accounting
    by the activation barrier (§14).
  - MinIO's own metadata and parity are not payload objects.
- **Covered** means counted in committed accounting or in an unreleased
  reservation.
- **Assumption A_F (finalize), a qualified storage contract, not a proof.**
  A storage server that has received a single-part PUT's complete body either
  commits it or discards it within `C_max` of the client's abortive close.
  - **Why it must be an assumption.** The pinned MinIO enforces no such bound.
    - A commit begins only while the request is live, and the client's
      abortive close cancels it. Once the body has ended, though, the server
      has **no** backstop: a silent client that stays connected keeps the
      request alive indefinitely.
    - A commit that has begun is a `rename(2)` that nothing cancels
      (`0035-benchmark/minio-probes/FINALIZE-SOURCE.md`).
    - ADR-035 therefore gates *release* on a storage liveness witness (§7).
      The witness proves that storage is not **stalled**. It does not prove
      that earlier commits have **drained**.
    - **A_F's residual** is therefore any commit of an ambiguous upload that is
      still pending when the witness completes (plus `C_drain`). That pending
      commit may still be before its context check, or already at its
      `rename(2)`.
      - Before the check, it can land only if its client is silent and still
        connected: a frozen writer, or a dead host until MinIO's
        `TCP_USER_TIMEOUT` ends the connection.
      - This happens only under slow-but-not-stalled storage.
  - **What the empirical part rests on.** It is qualified by trial
    (`QUALIFICATION.md`) and is valid **only** for the qualified storage
    combination of §9a.
  - **Where it is handled.** §9 explains why a physical bound cannot be
    unconditional, and how A_F is contained, monitored and fail-closed.

**The invariants.**

- **I1 (physical coverage).** Under the qualified A_F contract (§9a), every TestInbox-owned payload byte
  that exists, or can still come to exist, is covered. There is no exception.
  - If A_F is violated by up to `T_verify` (60 min), the uncovered bytes stay
    inside the finalize budget **H** that admission reserves out of *G*. The
    owned bytes still never exceed *G*.
  - A detected violation latches admission closed (§9).
- **I2 (atomic ceilings).** The inbox, workspace and global ceilings are
  evaluated in **one** database snapshot, under one admission lock. A copy is
  admitted only if all three hold. The global admission cap is `G − H`.
- **I3 (fenced writes).** Every object write is authorized by a live
  reservation, through a presigned URL. The storage server itself enforces
  four limits on that URL:
  - it refuses any upload that *starts* after the reservation's write
    deadline;
  - it refuses any upload of a size other than the reserved one;
  - it refuses any upload to a different key;
  - it refuses any upload to a key that already exists.
- **I4 (commit consumes).** A message becomes visible only in the transaction
  that consumes its `RESERVED` reservation, for exactly the reserved bytes.
  Otherwise the whole event rolls back.
- **I5 (stale ≠ free).** Age alone never releases capacity. Release requires
  two things:
  - `release_not_before` has passed. After that point no request can still
    create an object (§7).
  - A per-exact-key proof shows no object and no incomplete upload.
- **I6 (exactly-once decrement).** Deleting content, including through a
  cascade, decrements committed accounting exactly once, in the deleting
  transaction.
- **I7 (ownership).** Cleanup deletes only its own reservation's exact keys,
  never a committed message's objects.
- **I8 (auditability).** Committed accounting is an append-only ledger folded
  into base rows. It is reconciled against the source rows, and any drift is
  alarmed.
- **I9 (no disclosure over SMTP).** Every syntactically valid recipient gets
  the uniform `250` of ADR-025. That covers an unknown recipient and a reached
  inbox, workspace or service ceiling. A `451` never *discloses* tenant state:
  it reports storage, database or load failure for the whole `DATA`, and it is
  never chosen per tenant.
- **I10 (relevant refusals visible).** An authenticated waiter that presents
  its observation cursor can tell whether a storage refusal occurred for its
  inbox after that cursor.
- **I11 (no eviction, no grandfathered growth).** Content stays readable until
  ADR-009 retention removes it. A scope above its ceiling, including content
  already above it at rollout, admits nothing.
- **I12 (closed-enum labels).** No tenant identifier is ever a metric label.

### 2. Physical accounting

- **What one copy costs.** A recipient copy costs its `raw.eml` bytes plus
  every extracted attachment object, so attachments are counted twice (the
  ADR-027 §5 derivation, unchanged).
  - A parse failure costs the raw bytes only.
  - PostgreSQL metadata does not count.
- **All copies of one event cost the same.** They share the bytes and the
  single parse, so they have the same footprint *f*. *f* is exact before any
  write.
- **No attachment object exceeds the raw message.** The MIME parser
  (`JakartaMimeParser`) takes attachment bytes from `part.inputStream`, which
  only reverses the transfer encoding (base64, QP, uuencode, 7/8-bit).
  `message/rfc822` parts are kept whole, not expanded. So
  `maxObjectBytes` = the 15 MiB raw cap.

### 3. Scopes and ceilings (O1)

| Scope | Limit | Configuration | Visible to |
|---|---|---|---|
| Inbox | `floor(workspaceLimit × inboxShare)`, share default **0.25** | `testinbox.limits.max-stored-bytes-per-inbox-share`, `0 < share ≤ 1`, so the inbox limit is never above the workspace limit | the owning workspace (§13b) |
| Workspace | default **2 GiB** | `testinbox.limits.max-stored-bytes` (unchanged) | that workspace (§13a) |
| Global | admission cap `G − H`; *G* = 40 GiB in production | `testinbox.storage.global-limit-bytes` (operational) | operators; tenants get one bit (§13d) |

- **The inbox limit is a policy value, not a constant.** 512 MiB is today's
  result of the share. If the workspace limit becomes plan-dependent, the
  inbox limit follows. A per-inbox override would be a later additive
  change, and the API already reports the *effective* limit.
- **A full inbox keeps everything it has.** Further copies to it are refused
  with `INBOX_LIMIT`. Its siblings are unaffected unless the workspace itself
  is full.
- **Residual (recorded in `abuse-model.md`).** An attacker who knows an
  inbox's address can fill it with about 20 large copies. The per-inbox
  `INGEST` burst of 60 does not stop that. The inbox then refuses mail for the
  rest of its TTL, and an `EXACT` local-part stays unusable through its 24 h
  cooldown.
  - The tenant sees `INBOX_LIMIT`, so there is no false negative.
  - Denying the whole workspace needs four known addresses at the default
    share.
  - The named follow-up is an additive, tenant-initiated "clear inbox"
    operation. That is deletion by the tenant, not eviction.

### 4. Admission (T1) and multi-recipient semantics

**The admission unit is one recipient copy.** For one SMTP `DATA` or one
provider event:

1. Recipients are normalized and deduplicated, then kept in **envelope
   order**. That order is deterministic, and the sender chose it.
2. Each recipient is resolved (ADR-025). Unknown recipients are discarded, and
   nothing else happens for them.
3. `INGEST` is charged per inbox, then per workspace (ADR-027). A refused
   charge means a discard.
4. **If no candidate survives, stop.** There is no slot, no lock and no T1.
5. **Take one write slot for the whole event before T1** (§5). The slot queue
   is fair: at most `max-concurrent-events-per-workspace` (default
   `slots/4`) events of one workspace may hold slots on a node, and waiting
   is bounded at `W_slot` (10 s). A wait longer than that answers `451`, a
   load failure.
   - No reservation or deadline exists while the event waits, so queueing
     can never consume a write deadline.
   - One tenant's large events can delay, but not fail, other tenants'
     events.
6. **T1:**

```
BEGIN;                                             -- READ COMMITTED, synchronous_commit = on
SET LOCAL lock_timeout = '5s';                     -- exceeded → 451 (load)
SELECT pg_advisory_xact_lock(:storageClass, :global);
SELECT now() AS t0,
  (SELECT coalesce(sum(base_bytes),0) FROM workspace_storage_account)
+ (SELECT coalesce(sum(bytes),0)      FROM storage_delta)
+ (SELECT coalesce(sum(bytes),0)      FROM storage_reservation)                AS global_used,
  <per involved workspace: base + Σ deltas + Σ reservations>                   AS ws_used,
  <per involved inbox:     base + Σ deltas + Σ reservations>                   AS inbox_used;
-- ONE statement. Missing base rows read as 0 (their deltas are still summed).
-- For each candidate in envelope order: admit iff every ceiling holds (inbox, workspace, G − H);
--   an admitted copy adds f to the running totals.
INSERT INTO storage_reservation (...) VALUES (...admitted copies, with their exact keys...);
COMMIT;
```

**Which ceiling is reported.** The narrowest one: `INBOX_LIMIT`, then
`WORKSPACE_LIMIT`, then `SERVICE_CAPACITY`.

**Partial success is independent per recipient.** A full inbox, a full
workspace, or a full workspace belonging to another tenant discards only its
own copies. Valid mail for tenant B is never discarded because tenant A in the
same `DATA` is full.

**What happens when the global cap is reached mid-event.** Every copy has the
same *f*, so after the first `SERVICE_CAPACITY` refusal every later candidate
is refused too. The admitted set is an *envelope-order prefix* of the eligible
candidates. It is deterministic.

**What commits.** Every admitted copy commits together, or none does (ADR-026;
§6).

**Refusal counters.** Each refused copy adds one to its inbox's count (§6a).
An event refuses at most one copy per inbox.

**What the SMTP reply reveals.** Nothing. It is `250` for every recipient
(§12).

**Infrastructure failure after admission.** A failed or ambiguous upload, a
missed deadline or a lock timeout all answer `451` for the whole `DATA`.
- T2 does not run, and nothing becomes visible.
- The event's reservations go to `RELEASING`. They go there *immediately* only
  if every upload the event started ended definitively (§5). Otherwise they go
  there at their deadline, and release still requires the §7 proof.
- The refusal records roll back with the event (§6a). The sender's retry
  therefore re-runs admission from scratch.

**Why one statement.** Under READ COMMITTED each statement takes its own
snapshot. Two things move bytes between the three sums, each atomically: T2
moves reserved bytes to delta, and the compactor moves delta to base. A
second statement could observe a move the first statement missed, which
would under-count exactly those bytes and so over-admit. One statement sees
each move entirely or not at all. The adapter must issue one statement, and a
test asserts it (§17, test 12).

**The lock.**
- It uses the two-`int4` advisory key space, which is disjoint from ADR-027
  §6's one-`bigint` guard.
- Only T1 takes it. Every other path only decreases or moves usage, which can
  only make admission conservative.
- T1 creates reservation rows. It waits on no row lock.

### 5. The write fence: presigned, create-only, size-bound uploads

For each admitted copy, the use case obtains one **presigned PUT URL per exact
key**. The keys are stored on the reservation: `raw.eml`, plus
`attachments/{id}` for each attachment id, and those ids are pre-generated
before T1. Each URL is:

| Property | How it is enforced | Probe |
|---|---|---|
| Signed with the DB clock `t0` from T1, never the node clock | the adapter signs | — |
| Valid for `E` = 120 s, so `write_deadline_at = t0 + E` | a PUT that **starts** later gets `403` | E3, E8 |
| Bound to the exact key | the signature covers the path | — |
| Bound to the exact size | `content-length` is a signed header: N±1 gets `403`, chunked gets `411` | E9b–d |
| **Create-only** | a signed `If-None-Match: *` makes a replay get `412`, leaving the object unchanged; omitting the header gets `400` | I1–I6 |

Because the URL is create-only, a leaked or replayed URL can never overwrite
committed content. URLs are also **never logged**: they are redacted from
every log line and exception message, since the query string carries the
signature.

**Timeouts and closing the connection.**
- `T_put` (30 s) is the **total wall-clock** deadline of an upload. The
  application measures it; a socket inactivity timeout does not count.
- When `T_put` expires, the connection is **aborted with RST**
  (`SO_LINGER 0`), not closed with FIN. A FIN would let the kernel keep
  delivering a buffered body tail after the deadline; an RST discards it.
- Implementation gate: the upload client must provably do both. The
  candidate is Apache HttpClient 5 with `CloseMode.IMMEDIATE`, verified by a
  TCP-proxy test that observes the RST.

**One attempt, and which outcomes are definitive.** Each upload is attempted
once. The SDK's transport-level retries are off: 2.54.18's Apache5 client
disables automatic retries. An outcome is **definitive** only in these cases:

- a received `2xx`;
- `403` (`AccessDenied` or `SignatureDoesNotMatch`), meaning refused at
  authorization;
- `411`;
- `412`;
- `400 XMinioAdminBucketQuotaExceeded`.

Every other outcome is **ambiguous**: a timeout, a connection reset, EOF, or
any `5xx`. Probe E5 shows why: a fully received body can finalize even though
the client saw a failure.

**Write slots.**
- A node has `max-concurrent-writes` slots (default 16). An event holds one
  slot, taken before T1, and uploads its keys sequentially.
- A slot whose upload ended *ambiguously* is **not returned**. It stays
  occupied by a persisted ambiguity record (§9) until that key is resolved.
- Effective write concurrency therefore shrinks under storage trouble, and
  the H budget holds across breaker cycles and restarts.

**Shape of the upload.**
- It is single-part only. Presigned PUTs cannot become multipart.
- Nothing in TestInbox initiates multipart. The AWS SDK dependency is
  `software.amazon.awssdk:s3` 2.54.18 only, with no transfer manager and no
  CRT.
- The `BlobStore` port loses its unfenced `put`, and the only write is
  `putReserved(url, bytes)`. ArchUnit and an adapter test assert that no
  other payload write path exists.

**Clock offset.**
- MinIO accepts a signing time up to its 15 min skew constant into the
  future: probe E6 accepted +10 min and E7 refused +20 min. The DB↔MinIO
  offset therefore matters.
- Measured error: MinIO's `Date` header against DB `now()`, plus 1 s
  resolution, plus the round trip.
- It is checked at readiness, before **every** release decision, and on every
  cleanup pass.
- While `|offset| > ε_max` (30 s): the breaker opens (§8), and releases are
  **suspended**. On resumption, `release_not_before` is pushed back by the
  observed offset.
- No sender can influence either clock.

**Efficiency cutoff.** The monotonic per-event cutoff only avoids starting
uploads that the fence would refuse. Correctness does not depend on it.

### 6. Commit (T2)

```
BEGIN;
SELECT message_id, bytes, state FROM storage_reservation
 WHERE message_id = ANY(:ids) ORDER BY message_id FOR UPDATE;   -- all present, RESERVED, bytes = f,
                                                                --   else ROLLBACK → 451 (fenced)
SELECT 1 FROM inbox WHERE id = ANY(:involvedInboxes)
 ORDER BY id FOR KEY SHARE;                                     -- admitted + refused inboxes, ascending
INSERT INTO inbox_storage … ON CONFLICT DO UPDATE …             -- refusal records of THIS event (§6a),
 (ascending inbox_id; WHERE EXISTS (inbox))
INSERT INTO message … ON CONFLICT … DO NOTHING;                 -- statement triggers append deltas
INSERT INTO attachment …;                                       -- (skipped conflict rows are not in the
                                                                --  transition table — verified)
SELECT pg_notify(…);                                            -- messages and refused inboxes (ADR-020)
DELETE FROM storage_reservation WHERE message_id = ANY(:appended);
UPDATE storage_reservation SET state = 'RELEASING', release_not_before = now()
 WHERE message_id = ANY(:duplicates);                           -- ADR-026 reprocessed event
COMMIT;
```

**Lock order.** Every path acquires locks in this order:

1. the ADR-033 claim;
2. ADR-027 `guardAdmission`;
3. the admission lock (T1 only);
4. `storage_reservation` rows, ascending;
5. `inbox` `FOR KEY SHARE`, ascending;
6. `inbox_storage` rows, ascending;
7. `message`;
8. `attachment`.

**How the other paths fit that order.**

- **`storage_delta` never takes part in a lock cycle.** It is insert-only.
- **Retention is unchanged.** It is one autocommit `DELETE FROM inbox` per
  inbox: exclusive on the inbox, then cascades in constraint order (`message`,
  `attachment`, `inbox_storage`), then delta inserts. It locks no account
  row.
- **T2 cannot deadlock with retention.** T2 takes `FOR KEY SHARE` on its
  inboxes before any `inbox_storage` row. If it waits on a retention delete,
  it holds only reservation rows, which retention never touches.
- **Retention stays fast.** A cascade over 50 000 messages cost 0.24–0.25 s
  of accounting, against 19.8 s for rev 2's row-level account updates.
- **Old binaries gain no lock-order cycle.** Their only new effect is delta
  inserts. This was verified: retention against T2, the refusal-only upsert,
  the compactor, and old ingestion formed no cycle.
- **A commit after the deadline is fine.** The reservation state is the
  fence. Cleanup's guarded `UPDATE … WHERE state = 'RESERVED'` and T2's
  `FOR UPDATE` serialize on the row.

#### 6a. Refusal records

For each refused copy's inbox, in ascending `inbox_id`, the event writes:

- one upsert into `inbox_storage`: `refusal_count + 1`, `last_refusal_at`,
  `last_refusal_reason`;
- one `pg_notify`.

Where they are written:

- **inside T2**, when the event admitted any copy, so they commit or roll back
  with it;
- **in one short transaction after T1**, when nothing was admitted.

A vanished inbox is skipped, not an error. A woken waiter always sees the
refusal it was woken for (ADR-020).

### 7. Reservation lifecycle, reclaim timing and ownership

```
 (none) ──T1──▶ RESERVED ──T2──▶ (row deleted; bytes now in the ledger)                          [A]
                    │
                    │ write_deadline_at < now()              → release_not_before = write_deadline_at + S
                    │ | ADR-026 duplicate in T2              → release_not_before = now()
                    │ | event abort, every upload definitive → release_not_before = now()
                    ▼
                RELEASING ── offset ≤ ε_max · for EVERY reserved key: delete, then
                    │        ListObjectsV2(key) = ∅ ∧ ListMultipartUploads(prefix = exact key) = ∅
                    │        · now() ≥ release_not_before · witness done at w′ ≥ release_not_before · now() ≥ w′ + C_drain ──▶ released [B/C]
                    │                  └ anything found → delete it, release_not_before = now() + S, late_object++
                    └ a committed message row has this id (impossible by I4) → release, delete nothing, alarm      [D]
```

**When can a reservation's object last come into existence?** Measured on
MinIO's clock, for the qualified combination of §9a:

1. **No upload can start** after `t0 + E + ε_max`. That is the presigned
   fence (probes E3, E8).
2. **An upload that started earlier is aborted with RST at `T_put`.** The upload
   client enforces this, and §5 gate 2 proves it. A body cut off mid-way
   produces no object (E2), and an RST discards any buffered tail.
3. **A body stalled *by the client* before EOF produces nothing.** MinIO keeps
   a sliding read deadline (the idle timeout, 30 s + 250 ms) while it reads the
   body. It bounds **inactivity**, not total time. A writer that freezes
   mid-body therefore has its request fail after about 30.25 s of inactivity,
   and nothing commits. The 30.25 s term in `S` covers that case, and it is
   why the idle timeout stays in the §9a contract, as a **pre-EOF** control.
   - **It does not bound a stall on the server's side.** If MinIO itself stops
     reading because encoding is blocked on storage, the client's tail waits in
     kernel buffers, and reads after the stall return at once. EOF can then
     come arbitrarily late. That case belongs to the storage witness and to
     A_F's residual below, not to this term.
4. **Once the body is complete, the commit no longer depends on the client.**
   No step after EOF waits on the client (`FINALIZE-SOURCE.md`).
   - An RST before the context check cancels the commit. Qualification
     `v3-freeze*-R2s` confirms this directly on disk.
   - After EOF the server keeps **no** read deadline (`server.go:685`,
     `deadlineconn.go:56,133`), so a silent connected client keeps the context
     alive. That matters only if the commit is *itself* delayed.
   - A commit is delayed only by storage, CPU or lock progress. The
     **storage witness** below gates *stalls*. *Slowness* is A_F's residual,
     described below.
5. **A commit that began before the RST must still finish.** This is `C_max`,
   and it is **qualified**, not enforced. The final commit is a `rename(2)`
   that ignores cancellation, and the 30 s drive timeout abandons it without
   cancelling it.

```
C_max = 15 min                                  (qualified; §9a)
S     = ε_max + T_put + 30.25 s + C_max  =  30 s + 30 s + 30.25 s + 15 min  →  17 min (rounded up)
release_not_before = write_deadline_at + S        (when the last upload's outcome was ambiguous or unknown)
C_drain = 60 s     (after a completed witness; qualification saw pending commits land ≤ 0.34 s after storage resumed)
T_verify = 60 min  (≥ S; the window within which a late object is still inside H)
```

**Storage liveness witness: a second, fail-closed condition for releasing an
ambiguous reservation.** Before any ambiguous reservation is released, the
cleaner needs a successful witness: a small probe commit (`PUT` of
`_probe/{node}/{uuid}`), issued **at or after** that reservation's
`release_not_before`, that completed at time `w′` and was listed. The release
itself happens only at or after `w′ + C_drain`.

- **What the witness proves: storage is not stalled.** Probe objects are
  infrastructure, outside payload accounting. Under a stalled filesystem the
  witness cannot complete: it was blocked in 51 of 51 qualification freezes.
  So *no release happens while storage is stalled*.
- **What it does not prove: earlier commits have drained.** The witness is a
  1-byte, inline `PUT`. Nothing orders it behind other uploads' pending 15 MiB
  `fdatasync`, lock or `RenameData` steps.
  - Under slow-but-not-stalled storage (a sick disk, or slow write-back after a
    stall), the witness can complete while an ambiguous upload's commit is
    still pending.
  - If that upload's client is silent and still connected, its context is live
    and it can commit **after** release. That client is either a frozen
    writer, which can hold the connection indefinitely, or a dead host, until
    MinIO's `TCP_USER_TIMEOUT` plus keepalive idle (about 10.25 min).
- **`C_drain` = 60 s is empirical, not derived.** Qualification saw pending
  commits land within 0.34 s after a freeze, at healthy throughput. It says
  nothing about slow storage.
- **What is left to A_F.** Any ambiguous upload's commit that is still pending
  at `w′ + C_drain`, whether it is still before its context check or already at
  its `rename(2)`.
  - That is A_F's content, together with `C_max`.
  - It is **contained** rather than prevented:
    - a frozen writer's in-flight slot stays inside H while its crash-ambiguity
      rows are unresolved (§9);
    - a late object is detected and latches admission.
  - §9 lists what escapes even that.

**How each number is derived.**

- **`E` = 120 s** is the time to write the largest admissible event
  (50 recipients, capped by both edge and gateway, × about 26 MiB, so about
  1.3 GiB) at the lowest storage throughput still called healthy
  (20 MiB/s, about 65 s). That leaves roughly 2× margin.
  - Since the slot is taken *before* T1, only the event's own uploads spend
    `E`.
  - An event that still misses its deadline answers `451`: the storage is too
    slow for the event's size. That is not a tenant decision.
- **`ε_max`** is enforced by the breaker and the release suspension.
  **`T_put`** is enforced by the abortive close.
- **No server-side commit-start bound after EOF is claimed.** MinIO's idle
  timeout bounds a body that stalls *before* EOF: that is the 30.25 s term in
  `S`. After EOF it bounds nothing. Once the body is complete, a commit is
  delayed only by storage progress, which the witness gates.
- **`C_max` = 15 min is the only assumed term**, and it is the content of A_F.
  - **Where the number comes from.** It is not a server bound, and it is not
    kept at 9 min to hold S at 10 min.
  - **Qualification evidence** (`QUALIFICATION.md`): 111 uploads of 15 MiB
    against the pinned binary.
    - 51 of them froze the data filesystem for 45 or 120 s, which is longer
      than MinIO's 30 s drive deadline, with an RST or with a silent connected
      client.
    - Direct on-disk checks found **0 late commits**. A commit either happened
      before the stall, or, if the stall caught it earlier, never happened.
    - A strict sweep at +21.5 min found 0 objects that appeared after their
      poll window.
    - The witness was blocked during all 51 freezes.
    - The latest observed commit was 0.06 s after the full body was
      acknowledged. 15 min is a margin of about 15 000× over that.
    - One window was **not** exercised: a `rename(2)` already in flight when
      storage stalls, which lasts microseconds. That window is what A_F still
      assumes.
  - **Why the margin is operationally acceptable.** A `rename(2)` stalled for
    minutes is a storage incident. MinIO's own drive monitor treats an
    operation over 30 s as a drive fault.
  - **The price of the margin.** Stale capacity for an *ambiguous* copy stays
    charged for about `E + S + C_drain` ≈ 20 min instead of about 12. That is correctness
    bought with capacity during a storage failure, the trade the owner asked
    for.
- **What a writer does after `RELEASING` does not matter.** A frozen or
  partitioned writer cannot *start* an upload after the fence, and only
  uploads that started before it count.
- **Absence is proven per exact key.** Probe M2 shows that MinIO's
  `ListMultipartUploads` with a *parent* prefix returns nothing even while an
  upload is open, so a prefix-based proof would pass vacuously. The proof
  therefore lists every reserved key individually (M3).

**Ownership (I7).**

- The reservation's primary key is `message_id`, a random UUIDv4 generated
  before T1. Its keys sit under `{workspace}/{inbox}/{message_id}/` and are
  stored on the row.
  - Two reservations cannot share a key: the primary key is unique, and
    UUIDv4 collision (2⁻¹²²) is not a design case.
  - A `message_id` is never reissued, so a key is never reassigned.
  - A late upload can only target its own signed key, and only create it
    (`If-None-Match: *`).
- A reservation row exists only while no message with its id has committed.
  I4 deletes the row in the commit, and a duplicate's id never gets a message
  row.
- Cleanup deletes only the exact keys of its own reservation. It first checks
  `NOT EXISTS (message.id = :id)`; case [D] deletes nothing.

**`OrphanBlobSweep`.** It deletes a key only when a **single statement**
confirms three things: no message row, no reservation for its message id,
and no unresolved ambiguity record for the key.
- The single statement matters. With a check for the message followed by a
  check for the reservation, a T2 could commit between the two and the sweep
  would delete committed blobs.
- The sweep also runs `ListMultipartUploads` across the whole bucket. That
  works bucket-wide (probe M1), and any incomplete upload it finds is a
  defect: it is aborted and alarmed.

**Concurrency and outages.** Cleanup claims due rows with
`FOR UPDATE SKIP LOCKED`. A storage outage during cleanup leaves rows
`RELEASING` and still charged.

### 8. Storage circuit breaker (infrastructure, never capacity)

The breaker is per node and held in memory. It opens on any of these:

- an ambiguous upload outcome (§5);
- the quota response `400 XMinioAdminBucketQuotaExceeded` (probe Q1, a 4xx
  that is still physical);
- a clock offset above `ε_max`.

**While it is open**, the node answers `451` *before* the slot and before T1.
No reservation is made and no lock is taken.

**Half-open trial.** Backoff starts at 15 s and doubles up to 2 min.
- For `unavailable`, `timeout` or `5xx`, the trial is a zero-byte probe to
  `_probe/{node}`.
- For `quota`, the trial is one **real** event, because MinIO's lagging quota
  can accept a zero-byte probe while real uploads still fail (probes Q2–Q7).
  A quota outcome is definitive, so the trial risks nothing in H.

**Why the breaker exists** (§15):
- It prevents reservations piling up while the edge retries. Such a pile
  would later turn queued mail into refusals.
- It bounds the ambiguous uploads started per stall.
- It keeps T1's cost, which is linear in live reservations, at in-flight
  scale.

**Capacity and infrastructure stay distinct:**

| Outcome | SMTP | Tenant sees |
|---|---|---|
| `INBOX_LIMIT`, `WORKSPACE_LIMIT`, `SERVICE_CAPACITY` (application admission) | `250` and discard | the reason |
| Open breaker, storage failure, clock offset, latch (infrastructure) | `451` for the whole `DATA` | nothing (the edge retries) |

An open breaker is never reported as `SERVICE_CAPACITY`.

### 9. Physical bound, the finalize budget H, and why it is conditional

Let *P_owned(t)* be the TestInbox-owned payload bytes in the bucket.

**An unconditional bound is impossible.** Once a storage server has
authorized an upload and received its complete body, no S3 API can fence that
request. No API reports whether the server has finished requests the client
abandoned.

The pinned MinIO does not bound it either:

- **When a commit may begin.** Before EOF, a stalled body errors out. After
  EOF, a commit may begin for as long as the client keeps the connection
  open: the client's RST cancels it, and nothing on the server does.
- **When a begun commit must finish.** It is a `rename(2)`, and nothing bounds
  it (`FINALIZE-SOURCE.md`).

ADR-035 therefore:

- gates every release on a **storage liveness witness**, plus a drain margin
  (§7). A stalled filesystem blocks releases. The witness does **not** prove
  that earlier commits drained, and that residual is A_F's (§7);
- names the one remaining latency (A_F, `C_max` = 15 min, measured from the
  writer's RST);
- qualifies it for one exact storage combination (§9a);
- contains it in three ways. **Under the
qualified A_F contract, P_owned ≤ G.** This is an empirical qualification, not
a mathematical proof.

**1. Ambiguity occupies write slots, persistently.**
- Every ambiguous upload writes a row to
  `storage_ambiguity(id, node_id, object_key, bytes, ambiguous_at,
  verify_at, resolved_at)` and keeps its slot occupied.
- **After a crash:** each node heartbeats in `storage_node(node_id,
  generation, capability, heartbeat_at, clean_shutdown)`. When a generation
  ends uncleanly, or its heartbeat goes stale, the uploads it might have had
  in flight are recorded as ambiguity rows. There are at most one per slot,
  keyless, and covering the keys of that generation's started but
  unconsumed reservations. Each reservation records `first_upload_at` once,
  on its first upload.
- **Resolution** happens at `verify_at = ambiguous_at + T_verify`, with
  `T_verify` = 60 min and always ≥ S (17 min). A per-key proof must show either an
  absent object or a committed one. A late object found then is deleted,
  counted in `late_object`, and **latched** (point 3).

Per node process, the uploads that are in flight plus the unresolved
ambiguities never exceed `max-concurrent-writes`, across breaker cycles and
restarts. That gives:

```
H = declaredMaxIngestionProcesses × max-concurrent-writes × maxObjectBytes
  = 1 × 16 × 15 MiB = 240 MiB  (a rolling deploy that briefly runs 2 processes must declare 2 → 480 MiB)
admission cap = G − H
```

Here is what H means. Admission already reserves every byte in flight, so H
is not in-flight bytes. H covers only bytes that can surface *after* their
reservation's release. Under A_F that set is empty. If A_F is violated by up
to `T_verify`, the set is bounded by the slots still occupied by unresolved
ambiguity, so owned bytes stay ≤ *G*. That is the precise sense of I1.

**2. Detection.** A late object is detected at the ambiguity verification,
within `T_verify`. It is also detected by `OrphanBlobSweep`, which matches
deleted orphans against ambiguity rows retained for 24 h, and by
`physical_listed > covered` beyond one sweep.

**3. Containment, which fails closed.** The first detected late object sets a
database **admission latch**, `storage_admission_latch`.
- While the latch is set, every node answers `451` before T1. The edge queues
  the mail for up to 4 h, and nothing is lost unless the latch outlives the
  queue (§12).
- An operator clears the latch after investigating.
- **What remains unbounded:** objects that finalize *later than `T_verify`*
  and *before the first detection*. There are two ways to get there:
  - **A begun commit takes more than 60 min**, a `rename(2)` stalled for an
    hour.
  - **A commit *begins* late.** The commit of an ambiguous upload whose client
    is silent and still connected (a frozen writer) is held back by
    slow-but-not-stalled storage until after its crash-ambiguity rows expired
    at `verify_at` (+60 min). It then lands outside H.

  Both are removed by `OrphanBlobSweep` within 1 h 30 min of appearing, and
  they latch admission when detected. This is stated rather than claimed
  away.

**The bound.**

```
P_owned(t) ≤ max(G, B)
```

- **B** is the covered total at activation (§14). Pre-existing excess drains
  only through expiry, because there is no eviction and nothing is admitted
  above the cap.
- After that, **P_owned ≤ G** under the qualified A_F contract, or under A_F
  violated by up to `T_verify`.
- **Outside the qualified combination, no bound is claimed at all.** §9a then
  forbids enforcement, so the question never arises in an enforced
  deployment.

**Other exclusions, each handled elsewhere:**

| Exclusion | Handled by |
|---|---|
| Pre-activation objects | the barrier (§14) |
| Rollback to a pre-ADR-035 ingestion binary | the rollback floor (§14) |
| Restore from backup with the bucket kept | Ops procedure: empty the bucket, or keep the latch set, until one full orphan sweep completes |
| Accounting defects | reconciliation (§10) |

**Multipart.** TestInbox only ever issues single-part PUTs of at most
15 MiB. An aborted single PUT leaves no incomplete upload (E2). The per-key
proof and the bucket-wide sweep guard (§7) catch anything else.

**Ops preconditions** (§18):
- bucket **versioning off**;
- **no object-lock or retention rule**;
- **no retrying proxy or load balancer** between ingestion and MinIO, since a
  proxy retry would create a second request per slot;
- quota `Q ≥ G + max(1 GiB, 10 %, H + the bytes MinIO can accept during one
  usage-refresh lag)`, where Ops measures that last term.

The bucket quota is a fuse, never the bound. It lags: probes Q2–Q7 stored
2.4 MiB in a 1 MiB-quota bucket.

### 9a. Storage compatibility contract (A_F qualification)

A_F is not a property of MinIO in general. It is a property of **one
qualified combination**, recorded in
`0035-benchmark/minio-probes/QUALIFICATION.md` and, at implementation, in a
machine-readable record shipped inside the artifact.

**The qualified combination is exactly all of this:**

| Element | Qualified value |
|---|---|
| MinIO image | the GHCR mirror index `sha256:bbac6789…e00d`: amd64 member `sha256:3f97c565…64bb`, arm64 member `sha256:54d3d6a0…8194`. Both are `RELEASE.2025-04-22T22-12-26Z`, index `a1ea29fa…015e` upstream. |
| Server mode | single node, single drive (erasure "SD" mode), `minio server /data` |
| Timeouts, from environment **and** CLI flags | `MINIO_IDLE_TIMEOUT` / `--idle-timeout` at the default of 30 s: a pre-EOF inactivity control (a client-stalled body errors out, and nothing commits). `MINIO_CONN_USER_TIMEOUT` (10 min) and the TCP keepalive at their defaults: they are what ends the request context for a **dead peer host** after EOF, which bounds the A_F residual for that case. `MINIO_DRIVE_MAX_TIMEOUT` at its default. |
| Runtime admin configuration | `mc admin config get` for the `api`, `drive`, `storage_class` and `scanner` subsystems equal to their release defaults. This configuration persists in `.minio.sys/config`, not in the environment, so it must be checked separately. |
| Host | the kernel release and the data filesystem's mount options, as recorded at qualification |
| Storage class / inline threshold | release defaults |
| Data filesystem | local ext4 or XFS. No network filesystem, no FUSE. |
| Network path | ingestion connects directly to MinIO. No retrying proxy, no load balancer, no TLS terminator in between. |
| Upload implementation | ADR-035 §5: presigned single-part PUT, signed `content-length` and `If-None-Match: *`, one attempt, total wall-clock `T_put`, abort by RST. **No pipelining**: nothing is sent on the connection after the body, since pipelined bytes suppress the RST's cancellation (`server.go:691`). |

**Invalidation rule.** Any change to **any** element above invalidates the
qualification, including a MinIO upgrade or a re-mirror with a new digest. A
multi-drive erasure layout is a different combination, not a variant: late
renames on timed-out drives can reach read quorum there. See
`FINALIZE-SOURCE.md`.

**While the qualification is invalid, ADR-035 enforcement must not be
enabled.** The combination has to be re-qualified first, by re-running the
qualification procedure and committing the new record.

**Enforcement: fail closed where the application can see it, detect where it
cannot.**

- **At startup.** `DeploymentSafety` refuses to start an ingestion node with
  `testinbox.storage.enforcement=ON` unless all of these hold:
  - `testinbox.storage.backend-identity` is declared: the image digest, mode,
    timeouts, runtime-configuration hash, kernel, mount options and proxy
    status;
  - that declaration exactly equals a qualification record shipped in the
    artifact;
  - the application's upload implementation version equals the record's.

  A mismatch is a refusal to start, not a warning.

  This check compares a *declaration* against a record. On its own it cannot
  see the real MinIO.
- **Observing the real MinIO.** An Ops-run `qualification-check` does that. It
  runs with MinIO admin credentials, which the application deliberately does
  not hold. It reads:
  - `ServerInfo`: version, commit-id, mode and drive count;
  - `mc admin config get` for the subsystems in the table above;
  - the data filesystem's mount options, and `uname -r`.

  It hashes the result and compares it with the declared qualification record.

  **When it runs:** before every enablement, before and after every MinIO
  change, and **daily**.

  **What it publishes:** `testinbox_storage_qualification_valid{…}` (0 or 1,
  with no tenant labels). A value of 0 raises a page and automatically sets
  the admission latch of §9, which fails closed until Ops re-qualifies or sets
  `enforcement=OFF`.
- **In CI.** The existing mirror-pin gate (`scripts/check-minio-mirror-pin.sh`)
  also checks that every repository MinIO pin is the digest of a qualification
  record, so a re-mirror cannot land without a record.
- **The MinIO change rule, for Ops.** Before changing any element while
  enforcement is ON, Ops either confirms that the new combination already has
  a record, or sets `enforcement=OFF` first and re-enables it only after
  re-qualification. This is a row in `production-ops-acceptance.md` and a step
  in the MinIO upgrade runbook.
- **A late object still latches.** A late-object detection (§9 point 3)
  latches admission closed whatever the qualification state.

**What this does and does not guarantee.** An invalidating change cannot pass
*unnoticed*:

- the mirror-pin gate catches it in the repository;
- `DeploymentSafety` catches it at the declared identity;
- `qualification-check` catches it on the real backend.

But a change made by hand between two daily checks is only **detected within
one check interval** (≤ 24 h), not prevented. For that window, what holds is
the change rule, together with the storage witness,
the ambiguity slots and late-object detection. This is stated rather than
claimed away.

**Qualification procedure.** It is recorded in `QUALIFICATION.md`, and it is
repeated for every new combination:

1. Use the exact image digest and configuration being qualified, with the
   client on the same host or network as MinIO and no proxy. Run on a
   **dedicated filesystem** that can be frozen, and record the kernel and mount
   options.
2. Use the largest permitted single-part PUT (15 MiB).
3. Make every outcome ambiguous: either withhold the response, or reset the
   connection after the full body.
4. Poll the object from a separate connection throughout, and list every key
   again once the full candidate `C_max` window has passed. The procedure looks
   specifically for **"absent at an earlier check, then appears later"**.
5. Cover three scenario groups:
   - baseline uploads;
   - `freeze-W`: freeze the filesystem after the full body is acknowledged,
     with the client staying silent and connected (the frozen-writer case);
   - `freeze-R`: freeze, then RST during the freeze, at several phase offsets,
     to try to catch a `rename(2)` in flight;
   - **`slow-W`: throttle the data filesystem without freezing it (for example
     `dm-delay`, or a cgroup `io.max` on its device). Keep the client silent
     and connected, and run the witness concurrently.** The question is
     whether an ambiguous upload's commit can land *after* a witness that was
     issued later has already completed, and if so, how late. That is A_F's
     residual.

   Throttling is **not** a substitute: it slows `fdatasync`, not the rename.
   Every existence check is strict: `200` means present, `404` means absent,
   and anything else is an error, never "absent". Check that the storage
   witness is blocked during every freeze.
6. Record the digest, the configuration, the topology, the number of trials,
   the object sizes, the maximum observed latency, the chosen `C_finish`, and
   the margin.

### 10. Accounting ledger, compaction, reconciliation

**The delta ledger.**
- `storage_delta(id bigserial, workspace_id, inbox_id, bytes)` is
  **append-only**.
- It is written only by **statement-level** triggers with transition tables:
  `AFTER INSERT`, `AFTER DELETE` and `AFTER UPDATE`, on `message` and on
  `attachment`.
  - Each trigger aggregates with `GROUP BY`. A bare `sum()` over an empty
    transition table (for example an `ON CONFLICT DO NOTHING` that skipped
    every row) would insert `NULL` and abort T2.
  - An attachment insert finds its inbox through its message.
  - A cascaded attachment delete carries `inbox_id = NULL`. Only torn-down
    inboxes lose messages (verified in the code), so the inbox figure can
    only over-count, and only until the inbox row disappears.

**Committed usage** = `base_bytes` + `Σ storage_delta`, where the base lives
in `workspace_storage_account` or `inbox_storage`.

**The compactor.** It is the single writer of the base figures. It runs every
few seconds, under its own advisory lock. In one transaction it:

- deletes the visible delta rows (`DELETE … RETURNING`);
- **upserts** the bases: `INSERT … ON CONFLICT DO UPDATE` for workspaces, and
  `INSERT … SELECT … WHERE EXISTS (inbox) ON CONFLICT DO UPDATE` for inboxes.
  - A plain `UPDATE` would silently drop the bytes of workspaces and inboxes
    created after the backfill.
  - A deleted inbox's own share is dropped, but its workspace share is always
    kept.
  - If a concurrent inbox delete breaks the foreign key, the whole compaction
    rolls back, restoring the deltas, and retries.

Deleting by visibility means uncommitted deltas and bigserial gaps simply
wait for the next pass. Each delta is folded exactly once.

**Exactly once (I6).** Every deleted row set produces exactly one negative
delta, inside the deleting transaction. It rolls back with that transaction,
and no application code is involved.

**No `TRUNCATE` trigger.** The migration gate refuses the word. Test fixtures
call `storage_account_recompute()` instead.

**Reconciliation (I8).**
- It runs every 6 h as one `GROUP BY` statement comparing `base + Σdelta` with
  the ADR-027 §5 derivation.
- A repair is also a single statement, `base := derived − Σdelta`, read from
  one snapshot, taken under the compactor's lock.
- Metric: `accounting_drift_total{direction}`. The operator log names the
  workspace; the metric never does.
- The physical side compares `physical_listed_bytes` against the covered
  total.

**ADR-024 carve-out.** Derived accounting may be maintained by database
triggers, provided a use case, `ReconcileStorageAccounting`, proves it
against the source rows.

**Placement.**
- **Ports:**
  - `StorageAdmission`: T1, the T2 fence, and refusals;
  - `StorageReservations`: claim, list per key, release;
  - `StorageLedger`: compaction;
  - `StorageAmbiguity`: slots, ambiguity, the latch, node heartbeat.
- **Use cases:** `ReceiveInboundDelivery` (extended),
  `ReleaseStaleReservations`, `CompactStorageLedger`,
  `ReconcileStorageAccounting`, and `VerifyAmbiguousUploads`.
- **The presigner, the upload client and `T_put`** sit behind `BlobStore`.

### 11. Global admission lock: measured contention and the enablement gate

The design is provisionally accepted. The laptop evidence below is preserved
verbatim; it was **not** re-run for revisions 3 and 4. It was run on
`postgres:16-alpine` 16.15, Docker Desktop, a 16-vCPU arm64 laptop, with 26
workspaces and 200 000 standing messages. The absolute numbers are not the
production host's; the comparisons between modes are the evidence.

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

**What the laptop evidence shows:**
- At **2× the expected load** with 7 000 live reservations, T1 p99 is
  10.1 ms.
- At **4× with that backlog** the design saturates. Without the backlog, T1
  p99 is 7.2 ms.
- **Admission cost is linear** in live reservations plus unfolded delta rows.
  The breaker, cleanup and compaction every few seconds keep both at
  in-flight scale, and more than 1 000 of either raises an alert.
- The chosen mode beats the hot-row design at equal offered load, and it
  leaves retention unaffected.
- The ledger sum (`Σ storage_delta`) and the pre-T1 slot are newer than the
  laptop run. The staging gate measures them.

**Hard enablement gate, before the global ceiling is turned on.** Re-run on
the **staging host class** with the revision 4 schema:

| Parameter | Values |
|---|---|
| Concurrency | 1 / 10 / 25 / 50 / 100 |
| Offered load (open loop) | the expected rate (260 events/s) and 2× that rate |
| Backlog | 1 000 live reservations, plus a compaction interval's worth of deltas |
| Reported | p50, p95 and p99 for T1, T2 and retention; lock wait; throughput; the rate of timeouts and errors |
| Event mix | multi-recipient events |

**Pass criterion:**
- 2× the expected load is sustained;
- T1 p99 ≤ 50 ms;
- retention p99 ≤ 2× the no-ceiling reference at the same offered load;
- zero deadlocks;
- `lock_timeout` < 0.1 %;
- no deadline miss caused by slot queueing.

The result is recorded in `production-ops-acceptance.md` row G. If it fails,
the design **returns to ADR review**. Per-node escrow is the named fallback,
and it is not pre-built. Nothing outside PostgreSQL is introduced.

### 12. SMTP and edge behaviour

| Situation | Gateway reply to the edge | Stored | Tenant sees |
|---|---|---|---|
| Invalid recipient or foreign domain | `553` at `RCPT` | nothing | — |
| Raw message > 15 MiB | `552` (a property of the sender's own message) | nothing | — |
| More than 50 recipients | `452` at `RCPT` for the excess, from the gateway's explicit cap; tenant-independent, and the edge already caps at 50 | — | — |
| Unknown, expired or deleted recipient | `250` | nothing | — |
| `INGEST` rate exhausted | `250` | nothing for that copy | — |
| **Inbox, workspace or service ceiling** | **`250`** | nothing for that copy, no upload | `INBOX_LIMIT` / `WORKSPACE_LIMIT` / `SERVICE_CAPACITY` |
| Mixed event | `250` | the admitted envelope-order prefix, committed atomically | refusals recorded on the refused inboxes |
| Breaker open, storage failure (including quota `400`), clock offset, latch | **`451`**, whole `DATA`, nothing committed | admitted reservations move to `RELEASING` | nothing |
| Slot wait, write deadline, `lock_timeout`, fenced reservation | `451`, whole `DATA` | the same | nothing |
| ADR-026 reprocessed event | `250` / ack | no new row | — |

**The edge hides every reply.** The edge is store-and-forward, and its
contract sends **no DSNs** (`notify_classes=""`, `bounce_queue_lifetime=0`).
- No ingestion reply, and no timing difference between refused and admitted
  copies, reaches an external sender.
- A `451` condition that outlives the edge queue (`maximal_queue_lifetime`
  4 h) therefore ends in **silent loss**. That includes a latch left set.

This does **not** block acceptance, implementation, staging or a dark
production. It **is an Ops prerequisite before any public SMTP/MX**: alerting
on queue age and deferred mail, early enough to act well before expiry (for
example, the oldest deferred message is older than 30 min). It is an
operational dependency, not an application feature (§18).

### 13. Authenticated API contract (spec-first, ADR-022; all additive)

All new schema members are **optional**, never `required`, so generated
clients keep working against a rolled-back server (ADR-028). Nullable members
use OpenAPI 3.1 type arrays. Every new enum states that *clients must tolerate
unknown values*.

**The shared `StorageUsage` object:**

```json
{ "limitBytes": 536870912, "storedBytes": 402653184, "reservedBytes": 15728640,
  "availableBytes": 118489088, "overLimit": false }
```

- `storedBytes` is committed usage (`base + Σdelta`).
- `reservedBytes` counts reservations in every state.
- `overLimit` means `stored + reserved > limit`.
- Every figure covers the caller's **own** scope only.

**13a. Workspace.** `GET /v1/workspace/storage` returns `StorageUsage`, with
`availableBytes = max(0, limit − stored − reserved)`.
- Scope: `messages:read`.
- Rate category: `READ`, through an explicit `RateCategories` pattern;
  `RouteCoverageTest` enforces it.
- The workspace is the authenticated key's own.

**13b. Inbox (O1).** Every representation of an inbox carries these fields:
create, get, and any future list (which must compute them set-based, per
page). They are read *live* from the inbox id, including when an idempotent
create replay rebuilds the response from `InboxSnapshot`.

| Field | Meaning |
|---|---|
| `storage: StorageUsage` | `limitBytes` is the effective inbox limit (§3). `availableBytes = max(0, min(inbox headroom, workspace headroom))`, which is what this inbox can still admit. It may be `> 0` while `SERVICE_CAPACITY` refusals are happening. |
| `storageRefusalCount` | int64, monotonic, starts at 0. |
| `lastStorageRefusalAt` | date-time or null. |
| `lastStorageRefusalReason` | `INBOX_LIMIT \| WORKSPACE_LIMIT \| SERVICE_CAPACITY` or null. |

`POST /v1/inboxes` needs only `inboxes:write`, but its response reveals the
caller's *own* workspace headroom through `availableBytes`. That is accepted:
it is the same workspace, and nothing crosses a tenant boundary.

**13c. Wait: the explicit observation boundary (O2).**

`POST /v1/inboxes/{id}/messages/wait` gains:

- an optional request member `afterStorageRefusalCount` (int64 ≥ 0; a
  negative value gets `400`);
- on every `200`, the members `storageRefusalCount` and
  `lastStorageRefusalAt`. These are **informational**, and are taken from the
  **same snapshot** the call evaluated, never from a fresh read.

**Server rule.** Each evaluation reads the messages and the inbox's
`refusal_count` in **one snapshot**, then decides:

1. A matching visible message returns `200 MATCHED`. A match always wins.
2. Otherwise, if a cursor was supplied and
   `refusal_count > min(afterStorageRefusalCount, refusal_count)`, the call
   returns `409 storage-limit-exceeded`.
3. Otherwise the call parks, re-evaluating on every wake, and returns
   `200 TIMEOUT` when the window ends.

The refusal check runs before a wait slot is claimed. Response precedence, in
order: request-rate `429` → `400` → `404` → `410` → `MATCHED` → `409` →
slot `429` → `TIMEOUT`.

| Case | Behaviour |
|---|---|
| Newly created inbox | The create response carries `storageRefusalCount: 0`. Passing 0 observes every refusal the inbox ever has. |
| Fetched existing inbox | `GET` returns the current count *c*. Passing *c* observes the refusals that happen after that read. |
| Repeated waits | The caller passes the boundary it has **observed**: the value it started from, or the count carried by a `409` it has handled. A `MATCHED`/`TIMEOUT` echo is informational. Adopting it is the caller's explicit choice to move the boundary, and it would skip any refusal that the match outranked. |
| Process restart | The cursor is explicit caller state. The caller persists it, or re-reads the inbox and accepts the new boundary. The server keeps no per-client state. |
| Multiple SDK instances | Each keeps its own cursor and observes relative to it. |
| Refusal before the first wait | With the cursor from create (0) or from a prior `GET`, the call returns `409` at once, unless a match is visible. |
| Refusal during the wait | The refusal's `pg_notify` wakes the waiter, which re-evaluates and returns `409`, not `TIMEOUT`. With LISTEN degraded, the ADR-020 bounded re-query does the same. |
| Refusal after the matching message committed | `MATCHED`. The refusal is still observable by the next wait that carries the unadvanced cursor. |
| Match and refusal racing | The single snapshot decides. The `409` body says the refused copy *may* have been the awaited one. Waits are non-consuming, so a later call can still find the match. |
| Cursor larger than the current count | Treated as the current count (the `min` above), so a future value cannot suppress a real refusal. |
| **Cursor omitted (legacy client)** | **Exactly the pre-ADR-035 behaviour.** A storage refusal never produces a `409`. The call ends `MATCHED` or `TIMEOUT`, and `TIMEOUT` now also carries the count for diagnosis. |

**Compatibility.** Only a request that carries the new member can receive the
new outcome, so every change is additive under ADR-015 and no amendment is
needed.

**The 409 problem.**

```
409 application/problem+json
type: https://testinbox.email/problems/storage-limit-exceeded
{ "inboxId", "refusalReason": "INBOX_LIMIT|WORKSPACE_LIMIT|SERVICE_CAPACITY",
  "afterStorageRefusalCount": n, "storageRefusalCount": c, "lastStorageRefusalAt": t,
  "quota": "STORED_BYTES", "limit": …, "current": … }
```

- `quota`, `limit` and `current` are present **only** for `INBOX_LIMIT` and
  `WORKSPACE_LIMIT`. `quota` uses the existing enum spelling (`STORED_BYTES`,
  as emitted by `InboxController` today). `current` is `stored + reserved` for
  the named scope.
- There is no `Retry-After`.
- A wait may return `409` because ADR-020 §3 treats a wait's outcomes as
  answers about the inbox's state. ADR-027 §8 supplies the meaning "waiting
  does not help".
- The new members are added to the shared `Problem` schema and to both SDK
  problem parsers.

**SDKs (ADR-014).**

| TypeScript | Kotlin / JVM |
|---|---|
| `TestInboxStorageLimitExceededError extends TestInboxError` | `TestInboxStorageLimitExceededException`, which carries `problemType` and status 409 |
| `client.getWorkspaceStorage(): Promise<StorageUsage>` | `getWorkspaceStorage()` / `getWorkspaceStorageBlocking()` |
| `Inbox.storage`, `storageRefusalCount`, `lastStorageRefusalAt`, `lastStorageRefusalReason`: read-only snapshots | the same |
| `inbox.waitForMessage({ …, afterStorageRefusalCount?, observeStorageRefusals = true })` | `awaitMessage(…, afterStorageRefusalCount: Long? = null, observeStorageRefusals: Boolean = true)` and `awaitMessageBlocking(…)` |

- **The per-`Inbox` observation cursor.** It is a read-only accessor,
  initialized from the count on the representation the SDK was handed: 0 for
  an inbox it created.
- **It advances in exactly two cases.** When the SDK surfaces a `409` (it
  moves to that `409`'s count), and when the caller passes an explicit value.
  It **never** advances from a `MATCHED` or `TIMEOUT` echo.
- **Concurrent waits.** It advances monotonically and atomically (to the
  maximum of its current and new value), so two concurrent awaits on one
  `Inbox` are safe.
- **Opting out.** `observeStorageRefusals = false` sends no cursor, which
  gives the legacy behaviour.
- **No hidden state.** The same results are reachable with raw REST.

**13d. Disclosure (O3).**

- **What a tenant can learn:** `SERVICE_CAPACITY`, one bit: "the service was
  at application capacity when this copy was refused".
- **What a tenant never sees:** the global limit; global stored, reserved or
  available bytes; other workspaces' activity; the reservation backlog.
- **Residual, stated precisely.** A tenant can send one `DATA` to up to 50 of
  its own inboxes. Because admission is an envelope-order prefix, the number
  of copies admitted places global headroom within one copy's size, anywhere
  up to the tenant's own workspace headroom (about 2 GiB). That holds below
  the 90 % paging threshold. Repeated probing yields a coarse time series of
  other tenants' aggregate usage near the cap.
  - Workspaces are provisioned by operators.
  - The owner accepted the disclosure knowing this residual, which is recorded
    in `abuse-model.md`.
  - Quantizing the admission cap or adding hysteresis are named follow-ups,
    not built here.

### 14. Migration V6, backfill, activation

**V6 is expand-only**, with no `rollback-unsafe` declaration. It creates:

- `workspace_storage_account(workspace_id PK → workspace, base_bytes, reconciled_at)`
- `inbox_storage(inbox_id PK → inbox ON DELETE CASCADE, workspace_id, base_bytes, refusal_count, last_refusal_at, last_refusal_reason)`
- `storage_delta`
- `storage_reservation(message_id PK, workspace_id → workspace, inbox_id` *(no FK)*`, object_keys text[], bytes > 0, state, created_at, write_deadline_at, release_not_before, node_id, generation, first_upload_at)`
- `storage_ambiguity`
- `storage_node` (`node_id`, `generation`, capability, `heartbeat_at`,
  `clean_shutdown`), used for crash-ambiguity detection (§9).
- `storage_admission_latch`
- indexes: `(workspace_id) INCLUDE (bytes)`, `(inbox_id) INCLUDE (bytes)`, and `(state, write_deadline_at)`. Deadlines are never used in index predicates (ADR-021).
- the statement triggers and `storage_account_recompute()`, then the
  **backfill** of both bases, in the same transaction.

**Locking.** V6 starts with:

```
SET LOCAL lock_timeout = '30s';
LOCK TABLE workspace, inbox, message, attachment IN SHARE ROW EXCLUSIVE MODE;
```

- **Why this order.** V6's own foreign keys and `CREATE TRIGGER` take
  `SHARE ROW EXCLUSIVE` on `workspace`, `inbox`, `message` and `attachment`.
  Taking them up front in retention's order (inbox, then message) avoids the
  deadlock that the database review reproduced. With `message` locked first,
  V6 deadlocked against a concurrent `DELETE FROM inbox`.
- **Why the timeout.** V6 fails fast if it cannot get the locks, so it never
  queues for up to the 600 s migration window while blocking every writer.
  Ops re-runs it.
- **What is not blocked.** The lock blocks inserts and deletes, not reads or
  foreign-key checks (which take `ROW SHARE`). That was verified with
  `pg_locks`.

**Backfill.**

- **It is exact.** The byte columns hold exactly the arrays that were
  uploaded. It is deterministic: no object-store scan and no approximation.
- **Measured duration:** 0.30 s for 1 000 000 messages and 300 000
  attachments (85 ms and 215 ms, warm). Staging is far smaller, and this
  repository holds no credentials to query it. The migrator logs the real
  duration. The planning bound is ≤ 10 s cold, plus up to 30 s of lock wait.
- **What waits during it:** ingestion commits and inbox creation. Reads and
  waits continue. An SMTP session stalls for at most that long (the
  SubEthaSMTP timeout is 60 s). A timeout answers `451`, and the edge retries.
- **It fits the normal migrator step.**
- **Verified:** a draft V6 containing LOCK, the triggers, the functions and the
  backfill passes `check-migration-safety.sh`.

**Activation sequence.** Old and new binaries do overlap. The reference
`deploy.sh` runs the migrator while the old containers serve, then recreates
`api` and `ingestion` separately. The GitLab-reconciled host cannot be proven
overlap-free from here.

1. **Phase 1, expand.** V6 lands.
   - Old binaries are unaware of it. Their inserts and deletes also append
     deltas, which creates **no** new lock-order cycle.
   - Rev 2's "deadlocks resolve by retry" is withdrawn: the ledger removes
     those deadlocks.
2. **Phase 2, coexistence with enforcement OFF.** ADR-035 binaries ship with
   `testinbox.storage.enforcement=OFF`, the default.
   - The full protocol runs: slots, reservations, the fence, the T2 fence,
     cleanup, compaction, reconciliation, ambiguity tracking, heartbeat and
     visibility.
   - Nothing is refused. This is the ADR-027 §9 precedent.
   - Old and new binaries may coexist **only** in this phase.
3. **Phase 3, the activation barrier.** It is proven, not assumed, by five
   checks:
   - **(a) Capability, as an allowlist.**
     - Every session of the application DB role has an `application_name`
       matching `testinbox-%:%:storage-v1`, except named exclusions:
       `testinbox-migrator:%`, and Ops sessions tagged `ops:%`.
     - Old binaries show pgJDBC's default name, and the old LISTEN connection
       is `testinbox-listen`, so both fail the check.
     - The positive inventory must also agree: `storage_node` shows every
       declared ingestion and api node heartbeating with capability
       `storage-v1`.
     - The new LISTEN name requires updating `PgListenNotifierTest` and
       `E2eStack`, in the implementation.
   - **(b) Physical baseline.** A full orphan-sweep pass has completed since
     Phase 2 began, with `physical_listed ≤ covered + H`.
   - **(c) Clock offset.** It is within `ε_max` on every node.
   - **(d) Global benchmark.** For the global ceiling, the §11 gate has
     passed.
   - **(e) Rollback floor.** The ADR-035 commit is in
     `deploy/rollback-floors.txt` **on `master`** before Phase 4, because
     `production-handoff.yml` reads the floors from `master`.
     `verify-production-candidate.sh` then refuses a production candidate
     below the floor, unless the operator explicitly acknowledges the hazard.
     Staging's `deploy.sh` has no floor check today. The implementation adds
     the same check there, or a staging rollback with enforcement ON would go
     silent, with no new binary left to alarm.
4. **Phase 4, enforce.** Ops sets `testinbox.storage.enforcement=ON`. It is
   configuration on the same digests, and may be staged: inbox and workspace
   first, global after gate (d).
   - No old ingress instance may exist from here on.
   - Every node re-runs the allowlist and inventory checks on each cleanup
     pass, and raises `activation_violation` on any mismatch.

**Backups.** All seven tables are classified `-` in `deploy/backup/scope.txt`.
After a restore, `message` is empty, so empty bases are correct. The §9
restore procedure covers the bucket.

### 15. Supporting controls (O4), classified

| Control | Kept in ADR-035? | Invariant or bound it protects |
|---|---|---|
| Presigned uploads that are deadline-, size-, key- and create-bound (with URL redaction) | **Yes** | I3, I5, I7. This is the server-side fence that makes reclaim provable. Create-only prevents overwriting committed content. |
| Total wall-clock `T_put` with an RST abort | **Yes** | I5. It is a term of S, and without it no bound on a buffered tail exists. |
| One-attempt uploads, and the narrow definition of definitive | **Yes** | I1/H. At most one server-side request per slot, and every doubtful outcome counts. |
| Write slots (taken before T1, fair per workspace, occupied by persisted ambiguity) | **Yes** | I1/H: bounded across cycles and restarts. Keeps queueing from consuming deadlines (I9: a 451 is never chosen per tenant). |
| Storage circuit breaker | **Yes** | Stops reservations accumulating during outages; bounds ambiguity starts; keeps T1's cost down; keeps capacity separate from infrastructure. |
| Admission latch | **Yes** | I1 containment: fails closed on the first A_F violation. |
| Gateway recipient cap = 50 (edge parity, pinned in `contract.yaml` and set in the gateway) | **Yes** | ADR-026 atomicity (the edge never splits an event), and the per-event bound used to size `E`. |
| Gateway connection cap | **No: follow-up hardening** | No ADR-035 invariant depends on it. The DB pool and the slots already bound concurrency. |
| Chunked retention deletion | **No: dropped** | The ledger removes the account locks that needed it. A 50k cascade costs 0.25 s. |
| Monotonic per-event cutoff | **Efficiency only** | None. |

### 16. Observability

All labels are closed enums.

| Metric | Labels |
|---|---|
| `testinbox_storage_admission_total` | `outcome` = `admitted \| refused_inbox \| refused_workspace \| refused_global` |
| `testinbox_storage_admission_lock_wait_seconds`, `testinbox_storage_slot_wait_seconds` | — |
| `testinbox_storage_covered_bytes` | `kind` = `committed \| reserved` |
| `testinbox_storage_global_limit_bytes`, `testinbox_storage_finalize_budget_bytes` | — |
| `testinbox_storage_reservations` | `state` |
| `testinbox_storage_ledger_unfolded_rows`, `testinbox_storage_ambiguous_uploads` | — |
| `testinbox_storage_reservation_released_total` | `path` = `committed \| absent \| deleted \| reconciled` |
| `testinbox_storage_late_object_total`, `testinbox_storage_commit_fenced_total` | — |
| `testinbox_storage_physical_failure_total` | `kind` = `quota \| unavailable \| timeout \| ambiguous \| deadline \| lock_timeout \| slot_wait \| clock_offset` |
| `testinbox_storage_breaker_open`, `testinbox_storage_admission_latched`, `testinbox_storage_clock_offset_seconds` | — |
| `testinbox_storage_accounting_drift_total` | `direction` |
| `testinbox_storage_physical_listed_bytes`, `testinbox_storage_incomplete_uploads` | — |
| `testinbox_storage_activation_violation` | — |

`WaitOutcome` gains `STORAGE_LIMIT_EXCEEDED`.

Alerts:

- covered ≥ 90 % of the admission cap;
- any `refused_global`;
- any `physical_failure{quota}`;
- the breaker open for more than 5 min;
- the latch set, **paged**;
- the offset above `ε_max / 2`;
- more than 1 000 live reservations or unfolded delta rows;
- any ambiguous upload older than `T_verify` + 5 min;
- any `late_object`;
- any drift;
- any incomplete upload;
- a `RELEASING` row older than 1 h;
- `physical_listed > covered + H`;
- any activation violation;
- edge queue age (Ops, §12);
- `qualification_valid = 0`, which pages and latches (§9a);
- the storage witness failing for more than 5 min;
- `enforcement=OFF` in production for more than 24 h (for example, left off
  after a MinIO change and never re-qualified).

### 17. Test plan

**Seams.** These are required, so the tests can drive time and interleavings
without sleeping:

- SQL back-dating of `write_deadline_at`, `release_not_before` and
  `verify_at`;
- an injectable `Ticker`;
- an injectable signing clock for the presigner (probe E8 as a test);
- `IngestSyncHook`: `afterSlot`, `afterAdmission`, `beforeUpload`,
  `afterUploads`, `beforeCommit`, `inCommitAfterReservationLock`;
- `CleanupSyncHook`: `afterClaim`, `afterDeleteBeforeList`;
- a TCP fault proxy that can stall, swallow a response, or reset;
- `pg_blocking_pids` to prove that something is blocked.

**Accounting**

1. The ledger equals the derivation after inserts, `ON CONFLICT DO NOTHING`
   (all rows skipped), deletes, cascades, attachment inserts and deletes,
   updates, and truncate followed by recompute.
2. Property test: random append, duplicate, delete and compaction sequences
   preserve the ledger for every workspace and inbox. Inboxes may over-count
   during teardown.
3. Attachments count twice, and a parse failure counts the raw bytes only.
4. The V6 backfill is exact while latched old-style inserts wait. A
   concurrent `DELETE FROM inbox` does not deadlock V6. `lock_timeout` makes V6
   fail fast.
5. Compaction:
   - it folds each delta exactly once;
   - it upserts base rows for a workspace and an inbox created after the
     backfill;
   - it drops only the inbox share of a concurrently deleted inbox;
   - a T1 snapshot taken across a compaction never under-counts.
6. Reconciliation repairs drift in both directions with a single statement,
   and a clean run takes no lock.

**Admission**

7. Boundaries for the inbox, workspace and `G − H` ceilings.
8. `INBOX_LIMIT` precedence. A sibling inbox still admits. The inbox limit
   follows the share.
9. Mixed event: exactly the envelope-order eligible prefix is admitted, with
   zero uploads for refused copies and one `250`.
10. Global exhaustion mid-event.
11. Global concurrency: (a) a deterministic test in which the admission lock
    is held; (b) a load test that proves refusals happen and never exceed
    `G − H`.
12. Single snapshot: a latched demonstration, plus an assertion on the
    adapter's statement count.
13. T1 and slot acquisition are skipped for unknown-only events.
    `lock_timeout` answers `451`. `synchronous_commit = on`.
14. A scope over its limit after backfill: its content stays readable,
    nothing is admitted or deleted, and it recovers after retention.
    `CreateInbox` still answers `409 quota-exceeded`.
15. Slot fairness:
    - one workspace's events cannot hold more than its per-workspace share;
    - a `W_slot` timeout answers `451` with no reservation;
    - no deadline is missed because of slot queueing, even with a flood of
      large multi-recipient events from one workspace.

**Fence, reclaim, ambiguity**

16. A URL signed with a clock that has already expired gets `403`, classified
    `deadline`. A size mismatch gets `403`. A replay after commit gets `412`
    and the object is unchanged. URLs never appear in logs or exception
    messages, checked by a log-capture test.
17. **Owner test: crash after upload, before T2.** The charge is held until
    `release_not_before`, proven through admission. It is then released.
18. **Owner test: expiry before any upload.** Released after the per-key
    proof.
19. A late object found at `afterDeleteBeforeList` is deleted, and the charge
    is held.
20. T2 fenced by cleanup: `451`, and nothing becomes visible. T2 against
    cleanup, in both orders.
21. Response swallowed after a full body (E5 via the proxy): ambiguous, the
    slot stays occupied, and there is no inline release.
22. The body is stalled, then `T_put` expires: the proxy observes an **RST**,
    and no object exists afterwards.
23. `5xx` and reset outcomes are classified ambiguous. `2xx`, `403`, `411`,
    `412` and quota `400` are definitive.
24. The per-key multipart proof fails on an injected open upload for a
    reserved key, while the parent-prefix listing is empty (a regression test
    for M2). The bucket-wide guard aborts the upload and alarms.
25. Ambiguity slots:
    - 16 ambiguous uploads block new uploads on that node until they are
      verified;
    - a breaker close/reopen cycle does not free them;
    - a process restart does not free them (persisted);
    - an unclean shutdown records keyless ambiguity from `first_upload_at`.
26. A late object at verification sets the latch. Every node then answers
    `451` before T1, and only an operator clears it.
27. ADR-026 duplicate. Storage down during cleanup. Inbox hard-deleted while
    `RESERVED`. Two cleaners. Path [D].
28. `OrphanBlobSweep` uses one statement, and a T2 latched between its checks
    cannot lose committed blobs.
29. Clock offset:
    - an injected skew opens the breaker and suspends releases;
    - resumption pushes `release_not_before` back.
30. Breaker kinds:
    - on a quota `400`, the half-open trial uses a real event, never the
      zero-byte probe;
    - the zero-byte probe closes the breaker only for other kinds.
31. Outage plus edge retries, then recovery: the mail is admitted, not
    refused.
32. Lock order, deterministic, including an old-binary-style path: no
    deadlock.

**SMTP**

33. **Owner test: anti-oracle.** The transcript is byte-identical across
    admitted, unknown, `INBOX_LIMIT`, `WORKSPACE_LIMIT` and
    `SERVICE_CAPACITY` recipients, each alone and mixed. The test also asserts
    that every refusal really happened.
34. Quota classification through fault injection (`400
    XMinioAdminBucketQuotaExceeded`). A real `mc quota` check runs only in the
    rehearsal.
35. MinIO stopped (its own container): `451`, then admission after restart.
36. `552`, `553` and the unknown-recipient `250` are unchanged. The gateway's
    `maxRecipients` equals `contract.yaml`.

**Wait protocol**

37. **Owner test: refusal before the first wait.** Cursor 0 gives `409` at
    once.
38. A refusal during the wait, via real ingestion: `409`, not `TIMEOUT`.
39. A refusal after the match committed: `MATCHED`. The next wait with the
    unadvanced cursor gets `409`.
40. A match racing a refusal, in both orders, decided by one snapshot.
41. Cursor edge cases:
    - a cursor equal to the count;
    - a cursor above the count (clamped);
    - a negative cursor (`400`);
    - an omitted cursor never gets `409`, and `TIMEOUT` carries the count.
42. The echo comes from the evaluated snapshot. A refusal committed after the
    evaluation is not echoed.
43. Chained calls, two SDK instances, and a restart: each behaves as the
    table says.
44. Precedence: `409` before slot `429`, and no slot is consumed. A refusal on
    another inbox does not end the wait. LISTEN killed. `404` and `410` are
    unchanged.
45. SDKs:
    - the typed error;
    - the cursor seeded from the `Inbox`;
    - the cursor never advances on `MATCHED`;
    - the cursor advances on a surfaced `409`, and concurrent awaits advance
      it monotonically;
    - `observeStorageRefusals = false`;
    - `getWorkspaceStorage`;
    - count floors in the SDK CI jobs.

**Visibility, deployment, activation**

46. `StorageUsage` for the workspace and the inbox equals the accounting.
    Inbox `availableBytes` is the minimum of inbox and workspace headroom. A
    schema test asserts that no global figure appears in any tenant
    response. An idempotent create replay returns live fields. New members
    are optional in the OpenAPI contract, and the compatibility gate passes.
47. Metric cardinality, including `STORAGE_LIMIT_EXCEEDED`.
48. `DeploymentSafety` refuses to start when any of these is missing or
    wrong: *G*, the declared quota, the declared process count, `Q` too
    small, or the share outside `(0,1]`. With `enforcement=ON` it also refuses
    to start without a declared backend identity, or with one that does not
    exactly match a shipped qualification record (§9a). Every element is
    tested separately: digest, mode, timeouts (environment and flag),
    runtime-configuration hash, kernel, mount options, proxy status, and the
    upload implementation version.
49. The activation barrier fails on any of:
    - a pgJDBC default name;
    - `testinbox-listen`;
    - a missing heartbeat;
    - a declared node without capability.

    Named exclusions pass. With enforcement OFF, nothing is refused and the
    full protocol runs.
50. ArchUnit: no unfenced payload write path exists, and the new types sit in
    their proper layers.
51. The migration gate (no `TRUNCATE`) and the backup-scope gate (seven
    tables).

**Physical proof** (ephemeral rehearsal only)

52. **Owner test: physical proof.**
    - Setup: a dedicated database and bucket, a small *G*, versioning checked
      off.
    - Fill until both `refused_workspace > 0` and `refused_global > 0`.
    - Assert `listed ≤ committed + reserved` at three points: after the fill,
      after a crash at `beforeCommit` with cleanup paused, and after cleanup.
    - Finally, assert `G − H − f ≤ listed ≤ G − H`.

**Release gates (§7)**

53. **Pre-EOF stall.** A writer that stops mid-body is simulated: a TCP proxy
    freezes the stream after 90 % of the body. MinIO fails the request within
    about 30.25 s, no object appears (strict checks plus an on-disk check), and
    the reservation is released only through the normal rule.
54. **Storage witness.** With the witness PUT blocked (a stalled test storage
    adapter), or with the data filesystem frozen (`fsfreeze`, as in
    qualification), no ambiguous reservation is released. Releases resume only
    after
    a witness issued after `release_not_before` completes.
55. **Qualification-check signal.** `qualification_valid = 0` sets the
    admission latch, and every node answers `451` before T1.

**Explicitly untested:**

- a real MinIO violating A_F. A `rename(2)` stalled beyond `C_finish` cannot
  be injected deterministically; test 26 simulates it with a direct put after
  release. The empirical side is the §9a qualification, which is a
  procedure, not a CI test.
- nodes configured with different values of *G*;
- provider adapters;
- Ops alert rules.

Each touched module ratchets its minimum in `verify-test-results.sh`.

### 18. Gates and Ops prerequisites

**Implementation gates, before the implementation PR merges:**

1. The presigner signs with an explicit clock and the signed `content-length`
   and `If-None-Match` headers, verified against the pinned MinIO in the
   storage suite. The probes E9 and I1–I6 are the reference.
2. The upload client enforces a total wall-clock `T_put` and aborts with RST,
   proven with the TCP proxy.
3. URL redaction is proven by a log-capture test.
4. Tests 1–55 are in place, and the minima are ratcheted.
5. Staging's `deploy.sh` gains the rollback-floor check.

**Enablement gates, before `enforcement=ON`:**

6. The activation barrier (a)–(e) of §14.
7. The staging-host-class benchmark of §11, before the global ceiling.
7a. **The storage backend qualification is valid** (§9a), and the Ops
    `qualification-check` reports it valid on the real backend. The deployed MinIO
    combination is exactly one that has a committed qualification record,
    covering digest, mode, timeouts, filesystem, direct path and upload
    implementation. The production host (amd64, Ops-owned filesystem) is
    **re-qualified on its own combination**. The laptop qualification in
    `QUALIFICATION.md` covers the arm64 member in the reference topology,
    and does not stand in for it. The production qualification must include
    the `slow-W` scenario (§9a), which the laptop qualification did not run.

**Ops prerequisites, before production enforcement:**

8. Versioning off, no object lock, and no retrying proxy in front of MinIO.
   Quota `Q ≥ G + max(1 GiB, 10 %, H + the measured MinIO usage-lag churn)`.
   Evidence goes in `production-ops-acceptance.md` row G.
9. NTP on the database and MinIO hosts.
10. Declared values: `global-limit-bytes`, `declared-bucket-quota-bytes`,
    `declared-max-ingestion-processes` (including deploy surge).
11. The restore procedure of §9.
12. A runbook for the admission latch: investigate, then clear.
12a. **The MinIO change rule (§9a).** Before any MinIO upgrade, re-mirror,
     configuration, filesystem or topology change while enforcement is ON:
     either the new combination already has a qualification record, or
     `enforcement=OFF` is set first and re-enabled only after
     re-qualification. This is a row in `production-ops-acceptance.md` and a
     step in the MinIO upgrade runbook.

**Ops prerequisite, before any public SMTP/MX (§12):**

13. Edge queue-age and deferred-mail alerting, early enough to act before the
    4 h expiry. This does not block acceptance, implementation, staging or a
    dark production.

## Amendments to Accepted ADRs (effective 2026-09-29)

- **ADR-027 §2.** The storage-quota paragraph and the overshoot bound are
  superseded by §3, §4 and §9. The `CreateInbox` rule stands.
- **ADR-027 §4.** "The one place mail addressed to a live inbox is dropped" no
  longer holds. Storage-ceiling refusals are a second such place, and the
  tenant can see them.
- **ADR-027 §5.** "Derived, never accounted" is superseded for stored bytes by
  the reconciled ledger (§10). `maxActiveInboxes` remains derived.
- **ADR-027 Alternatives and Consequences.** Accept-and-drop and maintained
  counters are adopted in a form that answers the reasons they were rejected.
  Eviction stays rejected. "No usage table" and the overshoot bound are
  superseded.
- **ADR-020 §3.** A wait gains the opt-in `409 storage-limit-exceeded`
  (§13c).
- **ADR-024.** The carve-out for trigger-maintained accounting (§10).
- **CLAUDE.md invariant 8.** "Quota usage is derived from real rows, never a
  counter" becomes *derived, or database-maintained and reconciled; never an
  application-maintained counter*.

## Alternatives considered

- **`452` for a tenant quota, or `451` for the global ceiling.** Rejected:
  §12, and ADR-027 §1.
- **Releasing capacity when the TTL elapses.** Rejected by the owner and by
  I5.
- **Eviction, grandfathering, or a grace period.** Rejected by the owner.
- **A single hot global accounting row.** Measured and rejected (§11).
- **Row-level triggers that update account rows (rev 2).** Replaced. They
  took 19.8 s against 0.25 s for a 50k cascade, and they caused lock cycles
  with old binaries.
- **Deriving stored bytes per delivery.** Measured at 26–45 ms.
- **Application-maintained counters.** Rejected: the cascade objection
  applies to them.
- **A writer-side cutoff as the fence (rev 2).** Replaced by server-enforced
  presigned expiry.
- **Reserving before taking a write slot (rev 3).** Replaced. Slot queueing
  consumed write deadlines and turned load into cross-tenant `451`s.
- **`C_max = 9 min` as a bare assumption (rev 4).** Replaced by:
  - a storage-liveness witness, with a drain margin, on every release;
  - `C_max` = 15 min, measured from the writer's RST, qualified for one
    combination, and fail-closed on change.

  The pinned MinIO enforces neither a commit-start bound after EOF nor a
  finish bound.
- **A writer-liveness gate (rev 5 draft).** This used a heartbeat after
  `release_not_before`, or a missing session advisory lock held for `K_dead`.
  It was dropped as unnecessary and fragile:
  - Once the body is complete, a commit does not depend on the client
    (verified in source). The gate could never have *prevented* a frozen
    writer's late commit: it could only keep capacity charged. The same
    residual is now stated honestly as part of A_F (§7), and contained by H and
    the latch.
  - The focused review found real weaknesses in it: a missing lock does not
    prove death (failover, `idle_session_timeout`, pooling); MinIO's
    `TCP_USER_TIMEOUT` of 10 min made `K_dead` too short; and a heartbeat does
    not prove the RST was sent.
- **Treating MinIO's idle timeout as a commit-start backstop (rev 5 draft).**
  Rejected. After body EOF the Go server clears the read deadline, and
  MinIO's `DeadlineConn` then applies none (`server.go:685`,
  `deadlineconn.go:56,133`). This is verified in source, and it is not relied
  on either way.
- **Treating MinIO's 30 s drive timeout as the bound.** Rejected. It abandons
  the operation without cancelling it, so a late `rename(2)` can still land
  (`FINALIZE-SOURCE.md`).
- **An in-memory ambiguity budget (rev 3).** Replaced by persisted ambiguity
  that occupies slots. The in-memory version was unbounded across breaker
  cycles and restarts.
- **A content checksum to prevent URL replay.** `If-None-Match: *` is
  stronger (create-only) and is verified.
- **Multipart ownership in the reservation protocol (options B and C).**
  Unnecessary: only single-part PUTs are issued, and a guard catches anything
  else.
- **Redis or an in-memory counter.** Rejected (ADR-006, owner).
- **Counting metadata.** Rejected by the owner.
- **Evaluating the waiter's matcher against the refused copy.** Rejected: that
  needs content that is discarded.
- **A new `200` wait status.** Rejected: released SDK builds would swallow it.
- **A `409` on every wait, cursor or not (rev 2).** Replaced by the opt-in
  cursor, which is fully additive.
- **Advancing the SDK cursor on a `MATCHED` echo.** Rejected: it would swallow
  the refusal that the match outranked.
- **Exposing global headroom.** Rejected (O3).
- **Quantizing or adding hysteresis to the global cap now.** Deferred as a
  follow-up (§13d).

## Consequences

- TestInbox-owned payload stays ≤ *G* once any pre-existing excess has
  drained, under A_F or A_F violated by up to `T_verify`. Beyond that, the
  system detects the violation and fails closed. The ADR-027 overshoot of
  about 12.4 TiB per workspace is gone.
- A refused copy is never silent to the tenant. The sender sees only `250`.
- A crash or a storage stall costs capacity and temporary write concurrency.
  It never costs accuracy.
- The write path changes: slot, then T1, then presigned create-only uploads
  with RST aborts, then T2. `BlobStore` loses its unfenced `put`.
- V6 adds no lock-order hazard for old binaries. Enforcement turns on only
  behind a proven barrier and a floor that is already on `master`.
- Seven tables, statement triggers, the compactor, and one expand-only
  migration.
- These documents change with the implementation (CLAUDE.md invariant 8
  already changed with acceptance):
  - `docs/api/v1-design.md`;
  - `docs/architecture/` (`wait-semantics`, `inbound-mail-flow`,
    `failure-modes`, `observability`, `data-ownership`);
  - `docs/security/abuse-model.md`, which keeps its anti-enumeration
    statement and gains the O1 and O3 residuals and the persistence residual;
  - `docs/dev/production.md`, `production-ops-acceptance.md` (row G, plus
    rows for restore, clock, latch, edge queue and activation), and
    `rollback.md`;
  - `deploy/backup/scope.txt`, `deploy/mail-edge/contract.yaml`,
    `deploy/rollback-floors.txt`, and `deploy/staging/deploy.sh`;
  - the SDK READMEs.
- Every workspace limit can still be bypassed with a second workspace. The
  global ceiling bounds the service regardless.
