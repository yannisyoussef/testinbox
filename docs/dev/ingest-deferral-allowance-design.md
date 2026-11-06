# INGEST deferral allowance and T1 scheduling budget — final design

**Status: design only, not implemented.**

- **Sources.** This consolidates three things:
  - the draft at `29a1010`;
  - the independent correctness review and the independent security review,
    both AGREE WITH CHANGES;
  - the owner's decisions of 2026-11-06, #124 comment
    [6684756433](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6684756433).
- **Before implementation.** Both reviewers agree on §8(a), the invariants,
  and §8(b), the acceptance tests.
- **Before acceptance.** The owner accepts or rejects each residual in §8(c).
- **What it authorizes.** Nothing beyond the design: no activation, no
  admission.
- **Branch.** `app/fasttrack-ingest-refund`, PR #143.

## 0. Decisions

**The owner's decisions** (verbatim in #124):

| # | Area | Decision |
|---|---|---|
| 1 | Privacy | Accept the quantified retry-timing extension of the I9 exception for the initial beta (≤ 30 accounts). It holds under the reviewed edge retry schedule, the enforced concurrency limits and the stated observation model. The bounds apply to that model only, not to every timing observation. No hour-long redemption windows. Escalate if the mechanism goes beyond this scope. The text to record is in §4. |
| 2 | Load | Fresh traffic plus retry attempts entering T1 must stay within R = 31, with gate evidence at R and 2R. Use a common scheduling budget. A scheduling refusal keeps the delivery allowance. The allowance is separate from throughput control. Every T1 attempt counts toward scheduling, including attempts that certainly roll back. |
| 3 | Rollback | Keep the allowance when T1 is proven rolled back. Consume it atomically with the T1 that commits. An uncertain commit is not a proven rollback. |
| 4 | Residuals | Minimise them, then present one list. None is accepted now. A duplicate concurrent attempt is deferred, not charged fresh. Expiry cleanup is kept apart from loss. Nothing is called "rare" without a bound. |

**The reviews.** Every change requested by both reviews is adopted. Each is
cited at the invariant (I-) or acceptance test (AT-) that carries it. The
second round on `94242c5`, AGREE WITH CHANGES from both reviewers, is
applied in full:

- T1's statement order;
- the scheduling-token scope of I-1, and I-2 scoped to `INGEST` buckets;
- the partitioned fallback (I-18);
- the release and forfeit `lock_timeout` (I-14);
- the `INSERT … ON CONFLICT` escrow;
- the orphaning causes (RL-2);
- the production-pin run (§7);
- ETRN closed at the edge (§6);
- the S-C2 wording, and I-20;
- the corrected trade-off (d)3;
- the `abuse-model.md` text.

**Superseded:**
- the immediate refund (#143);
- consume in its own transaction before T1;
- an allowance TTL of edge lifetime plus 1 h;
- fresh charging of a duplicate attempt.

## 1. Problem

`INGEST` is charged per resolved recipient copy:

- per inbox: 30, refilling at 0.5/s;
- then per workspace: b = 60, r = 1/s.

That gives R = r × (30 + L) = 31 events/s (ADR-035 amendment D).

An event answered `451` before T1 has already paid. The edge retries no
sooner than 120 s later, for up to 4 h. The retry pays again and can be
discarded behind a `250`: the mail is lost.

The design separates two things:

- **Delivery allowance:** the escrowed `INGEST` payment of one recipient copy
  of one edge queue file.
- **Scheduling budget:** global throughput control of T1 attempts.

## 2. Mechanism

### 2.1 Transport identity

The identity is Postfix `XFORWARD IDENT`, the edge's queue ID. Verified on
`testinbox-mail-edge:rehearsal` (Postfix 3.8.6):

- It is sent before every `MAIL`, including on a reused connection.
- It is stable across `451` retries and across `postfix stop/start`.
- Two byte-identical sends get different IDENTs (ADR-019).
- The edge sends only the attributes ingestion advertises. Ingestion
  advertises `IDENT` alone, so no client IP or HELO is sent.
- The edge refuses a sender's own `XFORWARD` with
  `550 5.7.0 insufficient authorization`.
- `enable_long_queue_ids=yes` makes queue IDs non-repeating.
- One queue file is one ingestion transaction: the recipient limits are
  50/50 and there is a single nexthop.

**Gateway rules:**

- **Allowed peers.** `XFORWARD` is advertised and accepted only from peers
  in `testinbox.ingestion.smtp.xforward-peers` (`address → edge label`). In
  deployed profiles every entry is a single host (/32 or /128).
- **Other peers.** They see no advertisement. Their `XFORWARD` gets
  `550 5.7.0`, and they are charged as today.
- **Sequencing.** `XFORWARD` is accepted only before `MAIL`; otherwise
  `503 5.5.1`.
- **Per-transaction state.** It is cleared on `RSET`, `HELO`/`EHLO`, and the
  end of `DATA`.
- **IDENT validation.** It is xtext-decoded, at most 32 bytes, and must have
  the long-ID shape (alphanumeric, with the `z` separator).
- **No identity.** Two different IDENTs in one transaction, a missing IDENT,
  or `[UNAVAILABLE]`.
- **Other attributes** are ignored and never stored or logged.
- **Implementation.** SubEthaSMTP's internal `CommandHandler`
  (`getCommandHandler().addCommand` plus a replacement `EHLO`), pinned to
  7.2.2, with a test that breaks on upgrade.
- **Command.** `Command.transportId = (edge label, IDENT)`.
  `providerMessageId` stays null, and ADR-026 dedup is untouched.

### 2.2 Delivery allowance

**Table `ingest_allowance`** (expand-only migration):

| Column | Meaning |
|---|---|
| `edge`, `queue_id`, `inbox_id` | Primary key |
| `workspace_id` | Owning workspace |
| `workspace_token` | Whether the workspace token was taken; the inbox token is implied by the row |
| `created_at` | When the allowance was created |
| `expires_at` | `created_at + maximal_queue_lifetime + δ`, with δ = 15 min; never extended |
| `held_by`, `hold_until` | The attempt UUID holding the row, and until when |
| `deferrals`, `orphan_recoveries` | Counters |

- Foreign keys cascade on delete to `inbox` and `workspace`.
- Indexes on `held_by` and `expires_at`.
- No address, sender, content, hash or `Message-ID`.
- Only the ingestion role is granted. No API, console or other role.
- `-` in `deploy/backup/scope.txt`, and listed in `REQUIRED_DENY`.

**T, the transient outcomes.** These are decided by load or by node or
deployment state, never by the event's bytes or plan:

- `SLOT_WAIT`, `SCHEDULE_WAIT`;
- `BREAKER_OPEN`, `LATCHED`, `ACTIVATION_VIOLATED`;
- `HOLD_BUSY`, `HOLD_LOST`;
- at T1: `LOCK_TIMEOUT`, `FOOTPRINT_UNAVAILABLE`;
- `DB_TRANSIENT` before T1 commits: SQLSTATE `08xxx`, `40001`, `40P01`,
  `55P03`, `57014`, `57P0x`.

Planning and validation failures, untyped exceptions and `Error` are not in
T. Every known infrastructure path is typed.

**Attempt flow.** Each `execute()` gets one random attempt UUID.

- Every condition sits in an `UPDATE`/`DELETE … WHERE` clause and is
  re-checked under the row lock. Nothing is read and then written.
- Lock order: sorted allowance rows before any `rate_bucket` row. Buckets
  are sorted by (workspace, category, inbox).

1. **Pre-check.** `storage.unavailable()`, unchanged. It charges nothing and
   takes no hold.
2. **Hold.** One transaction takes every row of `(edge, queue_id)` that is
   unexpired and either free, or orphaned with `orphan_recoveries = 0`.
   - Taking an orphaned row counts one orphan recovery. An orphaned row that
     has already been recovered once is deleted.
   - If any row is held live by another attempt, roll back: `451 HOLD_BUSY`,
     nothing spent.
   - `hold_until = now() + H`, with H = 120 s ≥ W_slot + breaker-probe
     timeout + resolution deadline + 30 s. `DeploymentSafety` checks this.
3. **Resolve and charge.** Recipients resolve as today. For each eligible
   copy, in one transaction:
   - if the copy has a held row, **redeem** it, spending only a missing
     workspace token (narrow-first);
   - otherwise **charge and escrow**. The copy's transaction first runs
     `INSERT … ON CONFLICT DO NOTHING` of its held row.
     - Zero rows inserted means `HOLD_BUSY`. That transaction spends nothing,
       and the attempt releases the rows it has already escrowed.
     - Otherwise the transaction locks the buckets, spends the inbox token
       and then the workspace token, and records what was taken.

   Held rows whose recipient no longer resolves are forfeited at the end.
4. **Slot.** A *pure retry* (every copy redeemed) whose workspace is at its
   per-node cap is answered `451` at once, without the `W_slot` wait (I-17).
5. **Schedule.** One scheduling token, in its own transaction (§2.3).
6. **T1.** One transaction, in this order:
   - `configureTransaction()` first. `SET TRANSACTION ISOLATION LEVEL READ
     COMMITTED` must be its first statement;
   - then its `SET LOCAL`s;
   - then the consume
     `DELETE FROM ingest_allowance WHERE held_by = :attempt AND hold_until > now()`.
     It must delete exactly the expected count. Otherwise T1 rolls back
     without writing: `HOLD_LOST`;
   - then `pg_advisory_xact_lock(35,1)`, the one-statement snapshot and the
     reservation inserts, all unchanged;
   - `COMMIT`.

   The consume shares T1's fate.

**Outcomes:**

- **T1 committed.** The rows are consumed. A later `451` (uploads, T2) keeps
  its charges (RL-1).
- **Ending in T, including any T1 exception** (proven rollback or unknown
  commit). **Release:**
  `UPDATE … SET held_by = NULL, deferrals = deferrals + 1 WHERE held_by = :attempt`.
  If T1's delete committed, the rows are gone and this is a no-op. The
  database decides, never the client's view of the commit.
- **Any other ending, and a `250` without storage.** **Forfeit:**
  `DELETE … WHERE held_by = :attempt`. It resolves itself the same way, and
  the charges stand as today.
- **Release and forfeit timing.** Both use a `lock_timeout` of at least T1's
  `lock_timeout` plus a statement margin. The database therefore settles the
  T1 outcome in every case except process death. A release or forfeit that
  still times out leaves the hold to expire (I-14, RL-2).

**Cleanup.** A reaper deletes expired rows every 5 min. Every row is gone
within `expires_at` + 10 min.

A row past `expires_at` belongs to a delivery the edge has already ended:

- the edge's last attempt starts before arrival + `maximal_queue_lifetime`;
- `bounce_queue_lifetime = 0`;
- IDENTs never recur.

Deleting such a row is **cleanup, not loss** (`outcome="expired"`). The
window past the edge's lifetime is δ = 15 min, never hours.

### 2.3 Scheduling budget (throughput control)

**Bucket.** One global token bucket in PostgreSQL, `t1_schedule` (one row):

- database clock, the `TokenBucket` arithmetic, and
  `GREATEST(updated_at, now())`;
- shared by every node;
- R_s = R_gated = **31 events/s**, capacity B_s = **31 events**;
- active under `ALL`, the mode gate D qualifies.

Under `ALL`, `DeploymentSafety` refuses R_s ≠ R_gated, and B_s > R_s × 1 s.

**Spending.**

- Every event spends one token after it holds its slot and immediately before
  T1. The spend is its own `REQUIRES_NEW` transaction.
- Fresh events and retries share the bucket.
- The spend commits independently, so T1 attempts that roll back are counted.
- A spend whose commit is uncertain counts as taken. The event does not enter
  T1: `451 DB_TRANSIENT`, release.
- A fresh event waits for a token up to its remaining `W_slot`. A pure retry
  does not wait (I-17).
- A refusal is `SCHEDULE_WAIT`, which is in T, so the allowance is kept.
- Each T1 transaction spends exactly one token. Under `COHORT_PARTITIONED`
  (lab only), a fallback to mode a is a second T1 transaction and spends a
  second token.
- A scheduling token is never returned.

**Fairness.** Only slot holders contend for tokens, at most 4 per workspace
per node. Slot fairness (ADR-035 §4) therefore carries over. Fresh events
wait for a token while they hold their write slot, so a burst can turn
scheduling waits into `SLOT_WAIT` deferrals for other events (§7 measures
this, §8(d)1).

**Why global.** A refusal is a load `451` for the whole `DATA`, never chosen
per tenant, which is how I9 is written. A per-workspace refusal would add a
per-tenant channel, so it is not proposed.

**`abuse-model.md` text, for the implementation round:**

> A T1 scheduling refusal (`SCHEDULE_WAIT`) reveals aggregate T1 load only.
> It is recorded as a global-load `451` under ADR-035 I9 as written ("it
> reports storage, database or load failure for the whole `DATA`, and it is
> never chosen per tenant"). It is **not** recorded under ADR-037 A2, and
> **not** under the 2026-11-06 timing-disclosure exception or its extension.

### 2.4 Disposition of #143

- **Keep** `GuardedOutcome.RefusedBeforeT1`, which now carries the T reason.
- **Delete** `RateLimiter.refund`, `TokenBucket.refund`,
  `IngestCharges.claimRefund` and `testinbox_rate_refund_total`.
- **Fix** `consume` to write `updated_at = GREATEST(updated_at, now())`.
- **Add metrics:**
  - `testinbox_ingest_allowance_total{outcome}`, where `outcome` is one of
    `created`, `redeemed`, `released`, `consumed`, `consumed_then_deferred`,
    `forfeited`, `orphaned`, `orphan_forfeited`, `hold_busy`, `hold_lost`,
    `expired`, `expired_on_redeem`;
  - `testinbox_ingest_allowance_orphaned_total{cause}`, where `cause` is one
    of:
    - `release_timeout`, emitted by the holder when its release or forfeit
      times out;
    - `stall`, emitted by the holder on `HOLD_LOST`;
    - `unattributed`, emitted at recovery when no holder reported, i.e.
      process death;
  - `testinbox_t1_schedule_total{outcome}`;
  - `testinbox_smtp_transactions_total{identity}`;
  - `testinbox_ingest_allowance_oldest_seconds`.
- **Record dated notes that supersede this branch's refund text,** never
  rewriting it:
  - ADR-027 §4;
  - the ADR-035 amendment D reading;
  - `beta-limits.md`;
  - `abuse-model.md`;
  - ADR-004 §5: IDENT is a charging key, not a message identity.

## 3. Load argument

**Scheduling.** Let S(t, T) be the T1 attempts in (t, t+T].

- Each attempt holds a token taken after its slot and before its T1.
- At most 16 × nodes tokens can be granted before t and used after t.

So:

> S(t, T) ≤ B_s + R_s·T + 16·nodes = 31 + 31·T + 16·nodes

This holds for fresh traffic and retries together, whether T1 commits or
rolls back.

- **Sustained:** at most **R = 31 events/s**.
- **Short windows, one node:** at most 78 attempts in 1 s, and at most **2R**
  over any window of 1.52 s or more. Those are the two rates gate D
  qualifies.
- **Contention:** T1 lock waiters ≤ min(20 × edges, 16 × nodes). That is
  inside the qualified concurrency range (10–100). With one node,
  16 × T1 p99 (50 ms) ≈ 0.8 s, which is under the 5 s `lock_timeout`.

**Delivery.**

- Overflow is refused before T1 with its allowance kept (`SLOT_WAIT` or
  `SCHEDULE_WAIT`).
- The edge retries after 120–600 s. A retry spends a scheduling token only
  when it enters T1.
- Unique demand stays inside the `INGEST` envelope: events ≤ copies, ≤ 31/s
  sustained, plus bursts.
- So the backlog drains whenever aggregate demand is below R (RL-3).

**Conservation (I-2).** For every `INGEST` bucket:

> tokens spent = copies whose T1 committed + allowances outstanding +
> allowances forfeited + allowances expired + copies discarded by the
> limiter after a spend

An inbox-only row deleted by a committed T1 counts in the last term.

## 4. Privacy: the I9 extension and its record

**Observation model.**

- The observer is an authenticated participant.
- It reaches ingestion only through the edge. That is S-C2 (network: only the
  edge reaches ingestion SMTP), enforced under deployed `ALL` by I-20.
  `xforward-peers` only prevents identity forgery.
- It reads only its own co-addressed copy's eventual delivery time:
  `receivedAt` against the edge's `Received:` time.
- The edge retry schedule is pinned (§6). It excludes operator-initiated
  flushes (`postqueue -f`, `postsuper -r`). No SMTP client can trigger an
  early retry, because ETRN is refused (§6).
- Concurrency limits: edge 20; slots 16 per node and 4 per workspace;
  scheduling 31 + 31/s.

**Bounds.**

- Every probe event is still paid once from W's `INGEST` budget, so probe
  events are ≤ b + r·T.
- A deferred probe is retried at the edge's scan instants, without a new
  charge. Scans run every `queue_run_delay` (120 s), with backoff from 120 s
  to 600 s.
- The probe's delivery time reveals the first attempt at which W was below
  its slot cap. That is at most log₂(K+1) bits:
  - K ≈ 26 attempts in 4 h with backoff to 600 s gives about **4.8 bits**;
  - K ≤ 121 at the 120 s minimum gives at most **6.9 bits**.
- I-17's immediate deferral changes the `451`'s latency, not the bit.
- The global scheduling refusal discloses only aggregate load.

**Text to add to the ADR-035 I9 note (implementation round):**

> **Dated addition (2026-11-06): retry-timing disclosure under the INGEST
> deferral allowance.** Authority: owner decision of 2026-11-06, #124 comment
> 6684756433 (Q-1 option 2), for the initial beta of at most 30 admitted
> accounts.
>
> A probe event deferred before T1, or by a proven T1 rollback, keeps its
> paid `INGEST` allowance and is retried by the edge without a new charge.
> Its eventual delivery time therefore tells the observer at which edge
> retry the probed workspace was first below its slot cap.
>
> This holds under the reviewed edge retry schedule (`minimal_backoff_time`
> 120 s, `maximal_backoff_time` 600 s, `queue_run_delay` 120 s,
> `maximal_queue_lifetime` 4 h). That schedule excludes operator-initiated
> flushes (`postqueue -f`, `postsuper -r`), and no SMTP client can trigger an
> early retry, because the edge refuses ETRN (`smtpd_etrn_restrictions =
> reject`, `fast_flush_domains` empty). It also holds under S-C2 (network:
> only the edge reaches ingestion SMTP; `xforward-peers` only prevents
> identity forgery), the enforced concurrency limits (edge
> `relay_destination_concurrency_limit` 20; 16 write slots per node, 4 per
> workspace; T1 scheduling 31 + 31/s) and the observation model of the
> deferral-allowance design §4. Under those conditions, one paid probe
> reveals at most log₂(K+1) bits: about 4.8 (K ≈ 26), at most 6.9
> (K ≤ 121). Probe events remain bounded by the probed workspace's `INGEST`
> budget.
>
> These figures bound that model only. They are not a universal bound on
> every timing observation. No redemption window exceeds the edge's delivery
> lifetime by more than 15 min. The exclusions of the original exception
> stand. If review finds the mechanism exceeds this scope, it is escalated
> to the owner. The exception is reassessed, with #139, before any cohort
> expansion.

## 5. State bound

| Scope | Bound on rows |
|---|---|
| Per workspace | 60 + 1 × 15 300 = 15 360 (spends within `expires_at`) |
| Per inbox | 30 + 0.5 × 15 300 = 7 680 |
| Cohort | ≈ 476 k rows, about 95 MB with indexes |
| HELD rows | ≤ 20 edge sessions × 50 recipients = 1 000 per edge |

- **Typical size** is the edge's deferred queue plus the copies in flight.
  The gauge reports the oldest row's age.
- **Removal.** Rows are reaped within `expires_at` + 10 min, and cascade
  with their inbox or workspace.
- **`t1_schedule`** is one row.
- **Restore.** A restored database has no allowances (I-16, RL-6).

## 6. Edge contract and deployment requirements

**Add to `contract.yaml` under `required:`** (every profile):

- `smtp_send_xforward_command: "yes"`;
- `enable_long_queue_ids: "yes"`;
- `smtpd_authorized_xforward_hosts: ""`;
- `smtpd_authorized_xclient_hosts: ""`;
- `relay_destination_concurrency_limit: "20"`. Today it is inherited from
  `default_destination_concurrency_limit` and not pinned.
- `smtpd_etrn_restrictions: reject`;
- `fast_flush_domains: ""`.

**ETRN, observed 2026-11-06** on `testinbox-mail-edge:rehearsal` (Postfix
3.8.6) with the contract's acceptance settings. The template sets neither
key, so the Postfix defaults apply: `smtpd_etrn_restrictions` is empty and
`fast_flush_domains = $relay_domains`.

- Today's edge advertises `ETRN` in EHLO.
- From a non-`mynetworks` client, `ETRN inbox.testinbox.email` gets
  **`250 Queuing started`**.
- With the two keys above, it gets
  `554 5.7.1 <inbox.testinbox.email>: Etrn command rejected: Access denied`.

So any SMTP client of a host rendered from today's contract can force an
early flush of deferred tenant mail. **This exposure exists today,
independent of this design. Ops must close it** on the real edge (verify
the host, then reconcile), not only in this repository's contract.

**Already pinned, now load-bearing:**

- `relay_destination_recipient_limit` 50, `smtpd_recipient_limit` 50;
- `maximal_queue_lifetime` 4h;
- `minimal_backoff_time` 120s, `maximal_backoff_time` 600s;
- `queue_run_delay` 120s;
- `bounce_queue_lifetime` 0.

**A repository test** reads `contract.yaml` and asserts:

- δ ≤ 15 min, and `expires_at` = `maximal_queue_lifetime` + δ;
- H ≥ W_slot + probe timeout + resolution deadline + 30 s.

**Ingestion peer gate (I-20).** Under deployed `ALL`, ingestion refuses a
connection from any peer that is in neither `xforward-peers` nor the
explicit `testinbox.ingestion.smtp.trusted-clients` list. The decision is
per peer, at connect time, through SubEthaSMTP's `SessionHandler`, and is
independent of any recipient. This turns S-C2 from a network assumption into
an enforced check.

**Ops:**

- Close ETRN on the real edge (above).
- Enable long queue IDs while the edge is still dormant, before any relay
  (RL-7).
- Add an `ops_activation_prerequisites` entry.
- Make sure ingestion sees the edge's real address (no userland-proxy SNAT).

**`DeploymentSafety` refuses `ALL` when:**

- `xforward-peers` is empty or contains anything other than single hosts;
- `trusted-clients` contains anything other than single hosts;
- the scheduling pins (§2.3) do not hold;
- the hold inequality does not hold.

## 7. Evidence

**Gate D run 3 is not reusable for T1.** Consume moves inside T1's
transaction, and a scheduling spend is added before T1. Both are in gate D's
measured path.

**Expected impact:**

- **Consume.** One indexed `DELETE` of at most 50 rows in T1's transaction,
  before `(35,1)`. The lock hold, the snapshot statement and T2 are
  unchanged. Expect about +0.5 ms on `t1Transaction`.
- **Scheduling spend.** A new serialization point on one hot row, while the
  slot is held. Expect about 1 ms at ≤ 62/s, about 6 % row utilisation.

**Focused remeasurement.**

- **Command.** The documented gate command (`docs/dev/storage-benchmark.md`)
  on the staging host class, at the implementing commit, with
  `--expected-rate 31 --rates 31,62` and every other flag unchanged.
- **Harness.** `ProtocolAssembly` builds T1 with the consume statement
  exactly as `IngestionWiring` does, and seeds one held allowance row per
  copy under the event's attempt UUID.
- **Scheduling during the run.** The scheduling spend runs at its production
  position with a non-binding capacity. That measures its cost while 2R
  still measures headroom, not scheduler refusals. AT-9 proves the bound
  functionally.
- **What it isolates.** New per-step timers `consume` and `scheduleSpend`,
  inside `t1Transaction` and `event`. Compared with run 3 at equal
  (workspaces, concurrency, rate):
  - `t1P99` and `lockTimeoutRate` should show only the `consume` delta;
  - `slotQueueingDeadlineMisses` and the sustained ratio show
    `scheduleSpend`;
  - deadlocks and other errors.
- **One additional run at R with the production pins** (R_s = 31, B_s = 31).
  It reports:
  - the `SCHEDULE_WAIT` rate;
  - slot occupancy, because fresh events wait for a token while holding a
    slot;
  - `slotQueueingDeadlineMisses`.

  The non-binding 2R run stays.
- **A′.** The run may be combined with the A′ rerun if the owner confirms
  the run 3 paper's points.

**Reused as is.** The filesystem and storage qualification (E1–E11, gate F,
A2.2): no object, upload, fence, MinIO or filesystem dependency changes.

**New CI evidence.** The AT-11 rehearsal proofs. The live edge also needs an
Ops reconcile of §6.

## 8. Final lists

### (a) Invariants

1. **I-1 No credit.**
   - A bucket's `tokens` rises only by refill.
   - This also holds for `t1_schedule`: a scheduling token is never returned.
2. **I-2 Conservation.**
   - For every `INGEST` bucket: tokens spent = copies whose T1 committed +
     allowances outstanding + allowances forfeited + allowances expired +
     copies discarded by the limiter after a spend.
   - Each allowance is consumed at most once, and only in the transaction
     that commits T1.
3. **I-3 Durable.** An identified copy's spend and its allowance commit in
   one transaction.
4. **I-4 Unforgeable.**
   - Identity requires a single-host allow-listed peer and an IDENT of
     long-ID shape (xtext-decoded, ≤ 32 bytes).
   - Redemption requires an equal `(edge, queue_id, inbox_id, workspace_id)`
     and an unexpired row.
   - No content is consulted.
5. **I-5 Exclusive.**
   - At most one live attempt holds a row.
   - A concurrent attempt with the same identity is deferred (`HOLD_BUSY`)
     and spends nothing.
6. **I-6 Transient only.**
   - A row outlives its attempt only through Release (an ending in T) or one
     orphan recovery.
   - Every other ending forfeits it.
7. **I-7 Bounded life.**
   - `expires_at = created_at + maximal_queue_lifetime + 15 min`, never
     extended.
   - Expired rows are never redeemed, and are removed within `expires_at` +
     10 min.
8. **I-8 ADR-019/026.**
   - An allowance changes only charging, never whether a row is created.
   - The dedup key is untouched.
9. **I-9 Reply class.**
   - For a given storage state, the reply to an event with allowances is one
     a fresh in-budget event with the same recipients could receive.
   - An allowance only turns a limiter discard into a storage attempt. It
     never creates a reply class of its own.
10. **I-10 Privacy.**
    - Only IDENT is advertised or parsed.
    - ADDR, NAME and HELO are absent from rows, logs and metrics.
    - No API, console or API-role grant reads `ingest_allowance`.
11. **I-11 Clock.** `consume` writes `updated_at = GREATEST(updated_at, now())`.
12. **I-12 Labels.** Closed enums only.
13. **I-13 Lock order.**
    - Sorted allowance rows come before any `rate_bucket` row.
    - Buckets are sorted by (workspace, category, inbox).
    - Hold, expiry and ownership conditions sit in the `WHERE` clause and are
      re-checked under the row lock, never read-then-write.
14. **I-14 Hold.**
    - `hold_until − now()` ≥ W_slot + the longest pre-T1 path + 30 s.
    - Release and forfeit act only `WHERE held_by = :attempt`, so a stolen
      hold makes them a no-op.
    - Release and forfeit use a `lock_timeout` of at least T1's
      `lock_timeout` plus a statement margin. The database therefore settles
      the T1 outcome in every case except process death.
15. **I-15 XFORWARD per transaction.**
    - IDENT is cleared on `RSET`, `HELO`/`EHLO` and the end of `DATA`.
    - `XFORWARD` after `MAIL` gets `503`; from a non-peer it gets `550`.
    - A transaction without its own single IDENT has no identity.
16. **I-16 Restore.** `ingest_allowance` is `-` in `scope.txt` and in
    `REQUIRED_DENY`, so a restored database has no allowances.
17. **I-17 Edge occupancy.** A pure retry that finds its workspace slot cap,
    or the scheduling budget, exhausted is deferred without waiting.
18. **I-18 Scheduling.**
    - Every T1 attempt is preceded by one committed or uncertain spend from
      the global bucket (31 + 31/s), taken after the slot.
    - So S(t, T) ≤ 31 + 31·T + 16·nodes.
    - A refusal is in T.
    - There is one scheduling spend per T1 transaction. Under
      `COHORT_PARTITIONED` (lab only), a fallback to mode a is a second T1
      transaction and spends a second token.
19. **I-19 Rollback.**
    - An allowance survives T1 exactly when T1's transaction did not commit.
    - The database decides this, through the consume inside T1 and the
      row-locked release or forfeit. The client's view of the commit never
      decides it.
20. **I-20 Peer gate.**
    - Under deployed `ALL`, ingestion refuses a connection from any peer that
      is in neither `xforward-peers` nor `trusted-clients` (single hosts
      only).
    - The decision is made at connect time and depends only on the peer
      address.

### (b) Acceptance tests

They run on Testcontainers Postgres and MinIO, with the real
`GuardedStorage`. AT-11 runs in the rehearsal.

1. **AT-1 Restart.**
   - A `SLOT_WAIT` deferral, then a fresh context, then the same-IDENT retry.
   - The message is stored once.
   - The retry changes no `INGEST` bucket, and the row is gone.
2. **AT-2 Crash.**
   - Kill after the charge and before the `451`.
   - Kill after the consume statement and before T1 commits: T1 rolls back
     and the row survives.
   - After the hold expires, the retry redeems once. A second orphaning
     forfeits.
3. **AT-3 Duplicate.**
   - Two same-IDENT attempts behind a barrier: the second gets `HOLD_BUSY`
     and spends nothing.
   - After the first defers, the retry redeems.
   - Two concurrent FIRST deliveries of one IDENT (no rows yet): at most one
     escrows each copy, no token is spent twice, and I-2 holds.
4. **AT-4 Expiry and cleanup.**
   - A row past `expires_at` is not redeemed. It counts as
     `expired_on_redeem`.
   - It is reaped as `outcome="expired"` within `expires_at` + 10 min.
5. **AT-5 Attribution.**
   - One event across two workspaces: X takes both tokens; Y takes the inbox
     token and is refused by the workspace; Z is unknown; V is inactive.
   - The rows are exactly X (both tokens) and Y (inbox token).
   - On retry, X redeems; Y redeems the inbox token and spends a workspace
     token.
   - The per-bucket deltas are exact.
6. **AT-6 Reuse and forgery.** None of these redeems:
   - a forged IDENT from a non-listed peer (`550`);
   - a non-edge container on the edge network (`550`);
   - the same IDENT from another edge label;
   - the same IDENT for another recipient;
   - another IDENT with byte-identical content (two rows, ADR-019);
   - a retry after consume;
   - a replay after a lost `250` (charged, two rows);
   - a re-reserved address;
   - a short ID or `[UNAVAILABLE]`;
   - session transaction 2 without `XFORWARD`;
   - `XFORWARD` after `MAIL`;
   - conflicting IDENTs.

   ADDR, NAME and HELO are absent from rows, logs and metrics.
7. **AT-7 Re-defer.**
   - A redeemed retry is deferred again by `SLOT_WAIT`, by `SCHEDULE_WAIT`,
     and by a T1 `LOCK_TIMEOUT` rollback.
   - After each, the row is available again, `deferrals` rises and
     `expires_at` is unchanged.
   - Final delivery: one spend per bucket.
8. **AT-8 Deterministic.** None of these leaves a row, and the retry is
   charged as today:
   - a planning or validation failure;
   - an untyped pre-T1 exception;
   - after T1 commits: `UPLOAD_FAILED`, `COMMIT_FENCED`, or a T2 failure
     (`consumed_then_deferred`).
9. **AT-9 Load.**
   - (a) A property test over random interleavings of spend, defer, redeem,
     consume, rollback, expire and refill. I-2 holds, and S(t, T) stays
     within I-18.
   - (b) A storm: 31 workspaces with allowances outstanding, plus fresh
     traffic at r. The measured T1 arrival rate stays within
     31 + 31·T + 16·nodes, with no `LOCK_TIMEOUT`. Overflow is deferred
     with its allowance, and every in-budget message is stored once.
   - (c) Attempts that certainly roll back (`HOLD_LOST`, `LOCK_TIMEOUT`)
     still spend a scheduling token.
10. **AT-10 I9.** Under the stated model:
    - slot-wait samples per paid probe are counted and are ≤ K+1;
    - probe events are ≤ W's budget;
    - probe retries spend no `INGEST` token;
    - a pure retry at the cap is deferred without waiting.
11. **AT-11 Edge.**
    - IDENT is present, stable across `451` and an edge restart, and
      distinct for identical sends.
    - The edge refuses a sender's `XFORWARD`.
    - The edge refuses ETRN (`554 5.7.1`).
    - Only IDENT reaches ingestion.
    - Ingestion observes the edge's address.
    - Removing any §6 key fails the mutation suite, including
      `smtpd_etrn_restrictions` and `fast_flush_domains`.
12. **AT-12 Reply class.**
    - Replies with allowances are always ones a fresh in-budget event could
      receive (I-9).
    - Unknown and inactive recipients never create a row.
13. **AT-13 Clock.** Driven by lock interleaving: a spend that waited behind
    a later-committing spend never moves `updated_at` back.
14. **AT-14 Architecture.**
    - Only `ReceiveInboundDelivery` and `GuardedStorage` (consume, schedule)
      use the allowance and scheduling ports.
    - No refund API exists.
    - There is no content, hash or `Message-ID` column.
15. **AT-15 XFORWARD state.**
    - The state is cleared on `RSET`, `EHLO` and the end of `DATA`.
    - Transaction 2 on a reused connection, without `XFORWARD`, has no
      identity.
    - `XFORWARD` after `MAIL` gets `503`.
16. **AT-16 Stress.** Many threads charge, redeem, consume, release and
    steal. No `40P01`, and I-2 holds.
17. **AT-17 Stolen hold.**
    - The holder outlives its hold and a stealer redeems.
    - Exactly one T1 runs.
    - The old holder gets `HOLD_LOST`, and its release is a no-op.
18. **AT-18 Rollback.**
    - Abort T1's transaction at `COMMIT` (connection kill) and at a T1 lock
      timeout.
    - The row survives exactly when no reservation committed.
19. **AT-19 Restore, grants, deployment.**
    - `check-backup-scope` refuses a dump with `ingest_allowance` rows.
    - No API or console role can read the table.
    - `DeploymentSafety` refuses `ALL` with any of: an empty or
      non-single-host `xforward-peers`, a non-single-host `trusted-clients`,
      broken scheduling pins, or a broken hold inequality.
    - I-20: under deployed `ALL`, a peer in neither list is refused at
      connect. A listed trusted client connects, and gets no identity.

### (c) Residuals

None of these is accepted. Each needs the owner's explicit acceptance.

1. **RL-1 Failure after T1 commits.**
   - *Cases:* an upload that failed or is ambiguous, a fenced reservation, a
     T2 failure, or an uncertain commit.
   - *Effect:* the allowance was consumed with T1, so the retry is charged
     fresh. It is lost only if a needed bucket is empty at the retry.
   - *Metric:* `consumed_then_deferred`, plus
     `testinbox_storage_physical_failure_total{kind}`.
   - *Minimisation done:* consume-in-T1 closed the earlier crash-before-T1
     window. The remaining window opens only after T1 has committed.
2. **RL-2 Second orphaning.**
   - *Mechanism:* an attempt outlives its hold H. That happens on process
     death, a stall, or a release or forfeit that timed out. It uses up the
     single orphan recovery, so a second orphaning of the same queue file's
     allowance forfeits it.
   - *Metrics:* `orphaned{cause}` and `orphan_forfeited`.
   - *Minimisation done:* one recovery covers any single restart or stall.
     The release and forfeit `lock_timeout` (I-14) removes the timeout cause
     except under process death. The cap stops a crash-on-input loop from
     keeping a reusable allowance.
3. **RL-3 Edge expiry.** This is genuine loss, unchanged (ADR-035 §12).
   - *Mechanism:* a T-class condition that persists for more than 4 h. Or a
     scheduling backlog that cannot drain, because aggregate demand stays at
     the full R = 31 events/s for the whole queue lifetime after a burst.
   - *Metrics:* edge queue age (Ops alerting, already an Ops prerequisite)
     and `testinbox_ingest_allowance_oldest_seconds`.
   - Row cleanup after the edge has ended delivery is **not** loss.
4. **RL-4 Transactions without identity.**
   - *Effect:* they are charged as today.
   - *Under deployed `ALL`:* only the declared single-host `trusted-clients`
     (I-20) can connect without identity. Every other non-edge peer is
     refused at connect, and the edge is allow-listed (§6
     `DeploymentSafety`). So no edge traffic is affected.
   - *Outside `ALL`:* the staging synthetic suite and the lab, which run
     `OFF` or in the lab.
   - *Metric:* `testinbox_smtp_transactions_total{identity="none"}`.
5. **RL-5 Forfeit by design.**
   - *Cases:* an untyped pre-T1 exception, or a planning or validation
     failure.
   - *Metric:* `forfeited{reason}`.
   - *Minimisation done:* every known infrastructure path is typed,
     `DB_TRANSIENT` included.
6. **RL-6 Database restore.**
   - *Effect:* allowances are not restored (I-16). Messages deferred at the
     edge at that moment are charged fresh.
   - *Bound:* the edge's deferred queue at restore time. This only happens in
     a declared disaster operation.
7. **RL-7 Short-ID transition.**
   - *Bound:* zero if Ops enables long IDs while the edge is dormant (it is
     today). Otherwise, the messages queued at the switch, for at most 4 h.
   - *Metric:* `identity="none"`.
8. **RL-8 Redemption after δ.**
   - *Mechanism:* an edge attempt that started before the edge's deadline
     reaches the end of `DATA` more than 15 min plus (`created_at` −
     arrival) later.
   - *Metric:* `expired_on_redeem`. Any non-zero value is investigated.

**Delays only, never loss:** `HOLD_BUSY` (one deferral per lost edge
session), and `SCHEDULE_WAIT` and `SLOT_WAIT` deferrals.

### (d) Trade-offs for the owner

1. **Scheduling burst B_s = 31.**
   - At most 31 + 16·nodes events enter T1 at once across the cohort.
   - Above that, events are deferred by at least 120 s, instead of waiting up
     to `W_slot`. For example, part of one workspace's 60-copy burst is
     delayed by about 2 min.
   - A larger B_s defers less, but allows T1 bursts beyond the R/2R
     evidence.
   - Fresh events wait for a scheduling token while holding a write slot. A
     burst can therefore turn scheduling waits into `SLOT_WAIT` deferrals for
     other events, each of at least 120 s. The production-pin run at R (§7)
     measures this.
2. **R_s = R exactly.** At the edge of the envelope the service has no spare
   throughput to drain a backlog (RL-3). A higher R_s would need the owner's
   approval beyond R = 31.
3. **Global scheduling.**
   - One tenant's paid-allowance backlog can occupy most of the global budget.
     The backlog is at most b + r·(`maximal_queue_lifetime` + δ) = 15 360
     events, and it can hold the budget for up to about backlog / R_s
     seconds: about 8 min at one node.
   - During that time other tenants are deferred repeatedly. They are not
     lost unless RL-3 applies.
   - A per-tenant budget would be fairer, but its refusal would be a new
     per-tenant I9 channel.
4. **Privacy record.** Confirm the dated addition text in §4.
