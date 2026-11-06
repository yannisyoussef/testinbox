# INGEST deferral allowance — design (replaces the pre-T1 refund)

**Status:** DRAFT for review, not implemented. Implementation starts only
after an independent correctness reviewer and an independent security
reviewer agree on §3 (invariants) and §7 (acceptance tests). Nothing here
authorizes production activation or admission.

Branch `app/fasttrack-ingest-refund`, PR #143.

## 1. Problem and constraints

`INGEST` is charged per resolved recipient copy:

- per inbox first: 30 tokens, refill 0.5/s;
- then per workspace: b = 60, r = 1/s.

That gives R = 31 events/s under ADR-035 amendment D.

The problem is the pre-T1 `451`:

- The event has already paid when it is refused.
- The edge retries no sooner than 120 s later (`minimal_backoff_time`), for
  up to 4 h (`maximal_queue_lifetime`).
- The retry is charged again and can be discarded behind a `250`. The mail
  is lost.

The immediate refund in #143 was rejected for three reasons:

1. **Free probes.** Probes cost nothing, which broke the I9 exception's
   `INGEST`-paid sampling bound.
2. **No back-pressure.** Poison retries did not drain the budget, and
   back-pressure was lost under slot saturation.
3. **Clock.** `consume` could move `updated_at` backwards.

The owner's decision, verbatim:

> "Choose option 3: a bounded, durable allowance tied to the deferred delivery and its eligible recipient copies.
> Required properties:
> - A transient pre-T1 deferral preserves the already-paid allowance across retries and service restarts.
> - The allowance cannot be forged, transferred, replayed for additional deliveries or consumed more than once.
> - Identify retries through a reviewed transport mechanism, not a content hash. Preserve ADR-019's distinction between separate legitimate emails.
> - Bound retained state by the edge's actual delivery lifetime and concurrency. Pin the relevant edge concurrency in the reviewed configuration.
> - Deterministic failures must not acquire an indefinitely reusable transient-failure allowance.
> Preserve the approved workload envelope. Counting the initial charge alone is insufficient: retries can arrive together later. Show that fresh traffic plus retries still respects the qualified T1 load; temporary congestion may defer the retry again without destroying its allowance. […] Do not extend the accepted privacy exception or accept restart-dependent mail loss implicitly."

## 2. Mechanism

### Escrow, not refund

- A spent token never returns to its bucket early.
- A copy's spend is recorded as an **allowance** in PostgreSQL, in the same
  transaction as the spend.
- After a transient pre-T1 deferral, the allowance stays. The edge's retry of
  the same queue file **redeems** it instead of spending again.
- Invoking T1 **consumes** it, so one allowance buys exactly one T1.

### Retry identity: Postfix `XFORWARD IDENT`

Verified on 2026-11-06 against `testinbox-mail-edge:rehearsal` (Postfix
3.8.6), using a sink that answers `451`:

- **What is sent.** With `smtp_send_xforward_command = yes`, the relay client
  sends `XFORWARD IDENT=<queue id>` before **every** `MAIL`, reused
  connections included.
- **Only what is advertised.** The client sends only the attributes the
  server advertises. A server that advertises `XFORWARD IDENT` alone receives
  no client IP and no HELO.
- **Stability.** IDENT was the same across 451 retries and across
  `postfix stop/start`. It also survived `postsuper -r`, but nothing here
  relies on that.
- **Distinct sends.** Two byte-identical `DATA`s got different IDENTs, which
  is ADR-019's "separate legitimate emails".
- **Long queue IDs.** `enable_long_queue_ids = yes` gives non-repeating
  queue IDs (postconf(5)). Short IDs are inode-derived: they repeat, and
  `postsuper -s` can rename them.
- **The edge refuses a sender's `XFORWARD`.** The reply is
  `550 5.7.0 insufficient authorization`, because
  `smtpd_authorized_xforward_hosts` is empty. An IDENT that reaches ingestion
  is therefore always the edge's own.
- **One queue file is one ingestion transaction.** qmgr has no reason to
  split it: `smtpd_recipient_limit` = `relay_destination_recipient_limit` =
  50, and every tenant recipient has one nexthop. The key is per copy anyway.
- **Rejected alternatives.**
  - DSN `ENVID` is set by the sender, optional and repeatable.
  - The topmost `Received: … id` also comes from the edge, and ADR-004 names
    it, but it lives in the content bytes and needs format parsing (Q-3).

### Trust

- Ingestion advertises and accepts `XFORWARD` only from peers listed in
  `testinbox.ingestion.smtp.xforward-peers` (`CIDR → edge label`). This
  enforces S-C2 instead of assuming it.
- Any other peer sees no advertisement. Its `XFORWARD` gets
  `550 5.7.0 insufficient authorization`, independent of recipients, and it
  is charged exactly as today.
- Identity is `(edge label, IDENT)`, and only if IDENT has the long-ID shape:
  alphanumeric with the `z` separator. Anything else, `[UNAVAILABLE]`
  included, gives no identity.
- `Command` gains `transportId`. `providerMessageId` stays null, and the
  ADR-026 dedup is untouched.

### Allowance row

Table `ingest_allowance`, added by an expand-only migration:

| Column | Notes |
|---|---|
| `edge`, `queue_id`, `inbox_id` | The key. ADR-035 §4 gives one copy per inbox per event |
| `workspace_id` | Must match on redemption |
| `workspace_token` | Bool. The inbox token is implied by the row |
| `created_at`, `expires_at` | `expires_at` is set once and never extended |
| `held_by`, `hold_until` | The attempt UUID holding the row, and until when |
| `deferrals`, `orphan_recoveries` | Counters |

- Foreign keys `ON DELETE CASCADE` to `inbox` and `workspace`.
- No address, no envelope sender, no content, no hash, no `Message-ID`.

### Lifecycle

There is one random attempt UUID per `execute()`. Every timestamp comes from
the database clock.

| Step | Effect |
|---|---|
| **Charge.** An identified copy with no usable row. | One transaction. Spend the inbox token, then the workspace token if the inbox allowed it. `INSERT` the row as HELD (`hold_until = now()+120 s`) with what was taken. If another live attempt holds the row, charge without escrow (R-5). |
| **Redeem.** The key and `workspace_id` match, the row is unexpired, and it is free or orphaned. | Take the hold. Spend nothing for the tokens it holds. A missing workspace token is spent and recorded in the same transaction. |
| **Consume.** After the slot is acquired, immediately before the T1 flag. | A hook in `GuardedStorage.attempt` deletes **all** of the attempt's rows in one transaction, but only if every hold is still live. Otherwise it deletes none and the event is refused before T1. T1 never runs without a committed consume. |
| **Release.** `RefusedBeforeT1` with a cause in **T** = {`SLOT_WAIT`, `BREAKER_OPEN`, `LATCHED`, `ACTIVATION_VIOLATED`}. | The rows become free again; `deferrals` is incremented. |
| **Forfeit.** Any other end: T1 ran, no candidate, a resolution/limiter/planning failure, an untyped pre-T1 exception, an `Error`. | Delete the attempt's rows. Charges stand exactly as today. |
| **Orphan.** The hold expired after a crash. | Redeemable once. A second orphaning forfeits. |
| **Expire.** `expires_at` has passed. | Never redeemed; reaped and counted. |

**Transient** means decided only by node state, deployment state or load. It
never depends on the event's bytes, its recipients' tenant data, or a parse
or plan outcome, so replaying the same event later can succeed. Re-deferral
is bounded by the fixed expiry, not by a count.

### What happens to #143

- **Keep** `GuardedOutcome.RefusedBeforeT1`, the positional boundary.
- **Apply** #143's `GREATEST` guard to `consume`.
- **Delete** `RateLimiter.refund`, `TokenBucket.refund`,
  `IngestCharges.claimRefund` and `testinbox_rate_refund_total`.
- **Add** `testinbox_ingest_allowance_total{outcome}`.

## 3. Invariants

- **I-1 (no credit).** `tokens` rises only by refill.
- **I-2 (one T1 per spend).** Every copy that reaches T1 holds exactly one
  inbox spend and one workspace spend. Each was taken fresh by the attempt,
  or carried by an allowance whose consume committed before T1. An allowance
  is consumed at most once.
- **I-3 (durable).** An identified copy's spend and its allowance commit in
  the same transaction.
- **I-4 (unforgeable).** An identity exists only for an allow-listed peer and
  an IDENT of long-ID shape. Redemption needs an equal
  `(edge, queue_id, inbox_id, workspace_id)` and an unexpired row. No message
  content is consulted.
- **I-5 (exclusive).** At most one live attempt holds a row. Consume is
  all-or-nothing per attempt.
- **I-6 (transient only).** A row outlives its attempt only by Release
  (reason in T) or by one orphan recovery.
- **I-7 (bounded life).**
  `expires_at = created_at + TTL`, TTL = 5 h ≥ `maximal_queue_lifetime` + 1 h.
  The margin covers the last attempt's client timeouts: 35.5 min summed at
  the defaults. Never extended.
- **I-8 (ADR-019/026).** An allowance changes only charging, never whether a
  row is created. The dedup key is untouched.
- **I-9 (SMTP unchanged).** No reply depends on allowance state (ADR-025,
  ADR-027 §1, invariant 8). XFORWARD handling depends only on the peer
  address.
- **I-10 (privacy).** Only IDENT is advertised or parsed. No other XFORWARD
  attribute is stored or logged.
- **I-11 (clock).** `consume` writes `updated_at = GREATEST(updated_at, now())`.
- **I-12 (labels).** Closed enums only.

## 4. Load and sampling argument

### T1 load, per bucket

Take a workspace bucket (C = 60, r = 1) or an inbox bucket (C = 30,
r = 0.5). Let A(t) be the allowances outstanding at time t. By I-2:

> T1 copies in (t, t+T] ≤ A(t) + C + r·T

By I-7:

> A(t) ≤ C + r·TTL = 18 060 per workspace

- **Sustained.** T1 copies per workspace average ≤ r. The cohort's sustained
  T1 event rate therefore stays **≤ R = 31/s**, because every T1 event keeps
  one workspace spend.
- **Retries arriving together.** The A(t) term is delayed spend. A drain
  after congestion *can* briefly offer more than 2R = 62 events/s. The budget
  does not stop that; three bounds do:
  - The pinned `relay_destination_concurrency_limit = 20` caps concurrent
    `DATA` per edge.
  - Deferred mail is retried only on the edge's deferred-queue scans
    (`queue_run_delay` 120 s), each message after its own backoff of
    120–600 s.
  - The write slots cap T1 contenders at 16 per node and 4 per workspace.
- **T1 lock waiters** are therefore ≤ min(20 × edges, 16 × nodes). That is
  inside gate D's measured concurrency (10–100) for up to 6 nodes. With one
  node, 16 × T1 p99 (50 ms) ≈ 0.8 s, well under the 5 s `lock_timeout`.
- **Overflow** waits at the slot and is re-deferred **before T1 with its
  allowance**. That is the owner's "temporary congestion may defer the retry
  again".
- **Not covered by evidence.** Gate D used Poisson arrivals at R and 2R, not
  a saturated drain. That is the regime of the accepted burst residual,
  larger by A(t) (Q-2).

### I9 sampling

The refund made a deferred probe free at times the observer chose, so the
number of observations per W-spend was unbounded. With escrow:

- **Paid once.** Every probe event is paid once from W's budget when it is
  created. Probe events, and so observations, number ≤ b + r·T. That is the
  documented bound, restored.
- **Retries are edge-timed.** The observer sees only its own copy's delivery
  time. A deferred probe's retries come at the edge's scan instants, at least
  120 s apart. They stop at the first non-deferred attempt, or at 4 h.
- **Up to log₂(K+1) bits per observation.** One paid observation can
  therefore reveal which attempt first found W below its slot cap. K ≈ 26
  attempts in 4 h under exponential backoff to 600 s; the hard bound is
  K ≤ 121 at the 120 s minimum. One bit per observation becomes up to
  log₂(K+1) bits.
- **Not claimed to fit the exception.** These samples are coarser in time
  than the 1/s observer-chosen series the budget already allows. It is still
  a quantitative change, and this design does not claim it stays within the
  exception (Q-1).
- **The consequence-1 flood is not cheapened.** Only slot-holding events
  create contention, and holding a slot means reaching T1, which consumes the
  allowance.

## 5. State bound

- **Hard bound.** Rows ≤ spends within TTL:
  - per workspace: 60 + 18 000 = 18 060;
  - per inbox: 30 + 9 000 = 9 030;
  - cohort: 31 × 18 060 ≈ 560 k rows, about 110 MB with the index.
- **HELD rows** are ≤ 20 sessions × 50 recipients = 1 000 per edge.
- **Typical** state is the copies in the edge's deferred queue plus those in
  flight.
- **Cleanup.** Rows are reaped at expiry and cascade-deleted with their inbox
  or workspace.
- **Backups.** `deploy/backup/scope.txt` must classify the table: it holds
  tenant-linked identifiers and no content.

## 6. Edge contract requirements (Ops-owned host; the app states the need)

**Add under `contract.yaml` `required:`** (every profile, CI included):

- `smtp_send_xforward_command: "yes"`
- `enable_long_queue_ids: "yes"`
- `smtpd_authorized_xforward_hosts: ""`
- `smtpd_authorized_xclient_hosts: ""`
- `relay_destination_concurrency_limit: "20"`. Today it inherits
  `default_destination_concurrency_limit` = 20 unpinned, which is the trap
  `relay_destination_recipient_limit` had.

**Already pinned, now load-bearing:**

- `relay_destination_recipient_limit` 50
- `smtpd_recipient_limit` 50
- `maximal_queue_lifetime` 4h
- `minimal_backoff_time` 120s
- `maximal_backoff_time` 600s
- `queue_run_delay` 120s

**Other changes:**

- A repository test asserts `ingestion.allowance_ttl ≥ maximal_queue_lifetime
  + 1h` from `contract.yaml`.
- Ops adds an `ops_activation_prerequisites` entry.
- Ingestion must see the edge's real peer address. A userland-proxy SNAT
  would defeat the allow-list.

## 7. Acceptance tests

Real stack: Testcontainers Postgres/MinIO and the real `GuardedStorage`.
AT-11 runs in the rehearsal.

1. **AT-1 restart.** A typed `SLOT_WAIT` deferral, then a fresh context, then
   the same-IDENT retry. The message is stored once, both buckets are
   untouched by the retry, and the row is gone.
2. **AT-2 crash.** Kill after the charge commits and before the `451`. After
   the hold expires, the retry redeems (`orphan_recoveries` = 1). A second
   orphaning forfeits.
3. **AT-3 concurrent retries.** Two same-IDENT attempts behind a barrier:
   exactly one redeems and the other is charged. A steal after hold expiry
   racing a consume never yields two T1s.
4. **AT-4 expiry.** A row past `expires_at` is not redeemed, the retry is
   charged, and the reaper counts it.
5. **AT-5 attribution.**
   - One event, two workspaces:
     - X: both tokens taken;
     - Y: inbox token taken, workspace refused;
     - Z: unknown recipient;
     - V: inactive.
   - The rows are exactly X(both) and Y(inbox).
   - On retry, X redeems. Y redeems its inbox token and spends a workspace
     token. Per-bucket deltas are exact.
6. **AT-6 reuse.** None of these redeems:
   - a forged IDENT from a non-listed peer (`550`, charged);
   - the same IDENT with another recipient;
   - another IDENT with byte-identical content (two rows, ADR-019);
   - a retry after consume;
   - a replay after a lost `250` (charged, two rows);
   - the address re-reserved by another inbox;
   - a short ID or `[UNAVAILABLE]`.
7. **AT-7 re-defer.** A redeemed retry is deferred again: `deferrals` = 2 and
   `expires_at` is unchanged. The third attempt delivers, with one spend per
   bucket.
8. **AT-8 deterministic.** None of these leaves a row, and the retry is
   charged as today:
   - a resolution, limiter or planning failure;
   - an untyped pre-T1 exception;
   - `LOCK_TIMEOUT`, `UPLOAD_FAILED`, `COMMIT_FENCED`;
   - a T2 failure;
   - a lost-hold consume.
9. **AT-9 T1 bound.**
   - (a) A property test over random interleavings of spend, defer, redeem,
     consume, expire and refill. T1 copies in every window are
     ≤ A(t) + C + r·T, and every T1 maps to one unique spend.
   - (b) A storm: 31 workspaces with outstanding allowances plus fresh
     traffic at r, through 16 slots. T1 contenders stay ≤ the slots, there is
     no `LOCK_TIMEOUT`, overflow is re-deferred and keeps its allowance, and
     every in-budget message is stored exactly once.
10. **AT-10 I9 cost.**
    - W's tokens rise only by refill.
    - Admitted probe events are ≤ W's budget.
    - Probe retries spend nothing and create no row.
    - The test fails under #143's refund.
11. **AT-11 edge.**
    - IDENT is present.
    - It is stable across `451` retries and an edge restart.
    - It differs for identical sends.
    - The edge refuses a sender's `XFORWARD`.
    - Only IDENT reaches ingestion.
    - Ingestion sees the edge's address.
    - Removing any §6 key fails the mutation suite.
12. **AT-12 oracle.**
    - SMTP replies are byte-identical with and without allowances.
    - Unknown and inactive recipients never create a row.
13. **AT-13 clock.** A spend that waited behind a later-committing spend
    never moves `updated_at` back.
14. **AT-14 architecture.**
    - Only `ReceiveInboundDelivery` uses the allowance port.
    - No refund API exists.
    - The schema has no content, hash or `Message-ID` column.

## 8. Residuals and open questions

None of these is accepted implicitly. Each needs an explicit disposition.

**Residuals**

- **R-1. A crash between consume and T1** loses the allowance. The retry is
  then charged and may be discarded. The window is microseconds. Closing it
  means consuming inside T1, which changes T1 and forces a gate D rerun.
- **R-2. Post-T1 `451`s** (lock timeout, uploads, T2): the retry is charged
  again. That is unchanged and outside this authorization, but it is still a
  mail-loss case.
- **R-3. Expiry** adds no loss beyond the edge's own 4 h expiry (ADR-035 §12).
- **R-4. A second crash** on the same message forfeits its allowance.
- **R-5. A concurrent same-IDENT attempt** (the edge lost a session) is
  charged without escrow.
- **R-6. Clients without XFORWARD** keep today's charging: the staging
  synthetic suite and the lab.
- **R-7. During the edge's switch to long queue IDs**, messages already queued
  under short IDs get no allowance.

**Open questions**

- **Q-1 (security).** Is "≤ log₂(K+1) bits per paid observation, at
  edge-chosen instants" within "coarse busy/idle time series, no finer"? If
  not, cap re-deferrals at K_max. That brings back a bounded
  deferred-then-charged case, which only the owner can accept.
- **Q-2 (correctness).** Is the saturated drain inside the accepted burst
  residual? The alternative is a per-workspace cap A ≤ b:
  - it gives T1 ≤ 2C + r·T, and state of 31 × 60 rows;
  - its cost is that in-envelope mail deferred for more than about 60 s of
    congestion forfeits beyond the cap.
- **Q-3 (implementation).** SubEthaSMTP 7.2.2 has no public command API, so
  XFORWARD needs one of:
  - `SMTPServer.getCommandHandler().addCommand(…)` with
    `org.subethamail.smtp.internal` types, a replacement `EHLO`, and an
    upgrade-tripwire test;
  - or the `Received:` id instead.
- **Q-4.** One orphan recovery? Should an untyped pre-T1 exception (an
  ambiguity read failing in slot acquisition) stay out of T?
- **Q-5.** Should `DeploymentSafety` refuse `ALL` with an empty
  `xforward-peers` list? Otherwise R-6 covers all mail.
- **Q-6 (records).**
  - Dated superseding notes, never rewrites, for this branch's refund text:
    - ADR-027 §4;
    - the ADR-035 amendment D reading;
    - `beta-limits.md`;
    - `abuse-model.md`.
  - Should ADR-004 §5 get a note that IDENT is a charging key, not a message
    identity?

## 9. Evidence reuse

**Gate D run 3: reused, no rerun proposed.**

- *What the harness drives.* Latch, breaker, `WriteSlots`, T1, fence rows,
  T2, retention and compaction, directly
  ([`storage-benchmark.md`](storage-benchmark.md)). It never calls
  `ReceiveInboundDelivery` or the limiter.
- *What this design adds:*
  - the charge transaction, before the slot and outside the harness;
  - one indexed `DELETE` per identified event while the slot is held,
    outside the advisory lock;
  - a table T1 never reads.
- *Criteria with unchanged dependencies.* T1's statement, lock and snapshot
  and T2 are unchanged. So are the dependencies of `t1P99`,
  `lockTimeoutRate`, `deadlocks`, `retentionP99VsReference` and
  `otherErrors`.
- *The one changed input.* `slotQueueingDeadlineMisses` gains about one
  round-trip of slot hold: ≈ 0.06 slot-s/s at 2R, against 16 slots.
- *When to rerun.* Only if the reviewers move consume into T1 (R-1), or judge
  that added slot hold to be a changed dependency. The rerun is the
  documented command with `--expected-rate 31 --rates 31,62`, every other
  flag unchanged, with the consume statement added to the harness's slot
  step.

**Filesystem qualification (E1–E11, gate F): reused.** No object, upload,
fence, MinIO or filesystem dependency changes.

**Mail-edge rehearsal: new proofs (AT-11).** These are CI evidence. The live
edge needs an Ops reconcile of the §6 keys.
