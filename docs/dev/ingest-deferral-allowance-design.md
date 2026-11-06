# INGEST deferral allowance and T1 scheduling (fresh envelope + catch-up) — design

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
| 2 | Load (refined by 5) | Fresh traffic plus retry attempts entering T1 must stay within R = 31, with gate evidence at R and 2R. Use a common scheduling budget. A scheduling refusal keeps the delivery allowance. The allowance is separate from throughput control. Every T1 attempt counts toward scheduling, including attempts that certainly roll back. |
| 3 | Rollback | Keep the allowance when T1 is proven rolled back. Consume it atomically with the T1 that commits. An uncertain commit is not a proven rollback. |
| 4 | Residuals | Minimise them, then present one list. None is accepted now. A duplicate concurrent attempt is deferred, not charged fresh. Expiry cleanup is kept apart from loss. Nothing is called "rare" without a bound. |
| 5 | Scheduling, privacy (#124 comment 6686171551) | Trade-offs (d)1–(d)3 of the single 31 + 31/s budget are **not accepted**: under full fresh load, deferred mail cannot drain. Preserve the approved fresh envelope and its qualification guarantees, and propose bounded catch-up capacity or another mechanism that allows recovery, with evidence covering continued arrivals, repeated congestion and competing workspaces. Any reliance on 2R needs passing evidence at that load. The privacy extension (d)4 is accepted **conditionally** (§4). |

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

The third round brings the recommended scheduling of §2.3: the fresh
envelope untouched, plus catch-up capacity sized to the edge scan. It also
brings the drain bound and the conditions under which drain fails (§3), the
observed edge values (§6) and the evidence plan G-D/G-C/G-E (§7). The edge is
treated as public throughout.

**Superseded:**
- the single shared T1 scheduling budget of 31 + 31/s (round 2);
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

### 2.3 Scheduling: fresh envelope plus bounded catch-up

This replaces the single 31 + 31/s budget. The owner did not accept its
trade-offs (d)1–(d)3 (#124 comment 6686171551): when fresh traffic uses the
whole rate, deferred mail cannot drain.

**Recommended: option (a), a fixed split.** Fresh traffic keeps exactly its
approved envelope. Retries get their own catch-up capacity, sized to the
edge's retry scan. The other options are not recommended:

- **(b) Shared budget at R with retry priority or a reserved share.** It
  defers fresh traffic, so it changes the fresh envelope. Done per tenant, it
  is a new I9 channel.
- **(c) Per-workspace caps on outstanding allowances.** A refusal "because
  your workspace's backlog is full" is per tenant, which is a new I9 channel.
  Forfeiting beyond the cap is loss.
- **Pacing retries by letting them wait.** A waiting retry holds one of the
  edge's 20 relay sessions, and the edge serves roughly first in, first out.
  Fresh mail would queue behind it at the edge (RL-9).

**The two buckets.** They are global PostgreSQL rows in `t1_schedule`: the
database clock, the `TokenBucket` arithmetic, and
`GREATEST(updated_at, now())`.

- Every T1 attempt spends exactly one token, from exactly one bucket.
- The spend runs in its own `REQUIRES_NEW` transaction, after the slot and
  immediately before T1.
- A token is never returned. Attempts that roll back are counted.

| Class | Events | Rate | Capacity | When empty |
|---|---|---|---|---|
| fresh | no allowance taken in the Hold step, including events without identity | R_f = R_gated = 31/s | B_f = (30+L)·b = 1 860 | wait up to the remaining `W_slot`, then `SCHEDULE_WAIT` (allowance kept) |
| catch-up | at least one allowance taken in the Hold step | R_c = R_gated = 31/s | B_c = R_c·`queue_run_delay` = 3 720 | refused at once with `SCHEDULE_WAIT`, allowance kept. Never waits |

**Fresh is never refused when in envelope.** Fresh first attempts are at
most the cohort's `INGEST` workspace spends. Each of those buckets holds at
most b tokens and refills at r. So from any instant, fresh first attempts
are ≤ (30+L)·b + (30+L)·r·T. A (σ, ρ) token bucket admits every stream that
conforms to (σ, ρ). This leaves the approved fresh envelope as approved:

- r = 1/s per workspace, b = 60, R = 31 sustained;
- amendment D's accepted and unproven burst residual included.

The bucket binds only on traffic outside that envelope, such as the
partitioned fallback's second T1 (lab only) or a misconfiguration. It then
defers that traffic and keeps the allowance. A fresh event whose T1 rolls
back keeps its allowance, so its next attempt is a catch-up attempt.

**Catch-up is sized to the edge.**

- The edge presents deferred mail only at its deferred-queue scans
  (`queue_run_delay` 120 s, observed). So catch-up tokens are spent in bursts
  at scan instants.
- With a capacity of one scan interval's refill, no catch-up token is lost
  between scans, and each scan can admit up to B_c = 3 720 retries.
- A smaller B_c caps catch-up at B_c / 120 s, whatever R_c is.
- Neither class borrows the other's tokens, so neither can starve the other.

**Activation.** c = R_c/R_gated = 1 needs passing evidence at sustained 2R
(gate D) **and** for the catch-up burst (G-C, §7). Gate D has **not** passed
(run 3 FAILED), and G-C does not exist yet. So nothing in this section is
qualified today, and no deployment may run it.

Under `ALL`, `DeploymentSafety` refuses:

- R_f ≠ R_gated;
- B_f ≠ (30+L)·b;
- R_c > R_gated;
- B_c ≠ R_c × the contract's `queue_run_delay`;
- R_c or B_c above what the recorded G-C evidence covers.

Gate D is already an activation gate, and G-C is added to it. No runnable
configuration has c = 0: without catch-up, retries could never enter T1.

**Fairness without a per-tenant refusal.**

- Both refusals are global and reveal aggregate load only (I9 as written).
- Every deferred message is presented again at least once per P = 720 s.
- Catch-up tokens go to retries in the order the edge delivers them.
- The slot cap of 4 events per workspace per node still limits any one
  workspace's concurrent T1 work.
- No workspace can wait longer than the whole-backlog drain bound of §3,
  because that bound covers every message.

**`abuse-model.md` text, for the implementation round:**

> A T1 scheduling refusal (`SCHEDULE_WAIT`, fresh or catch-up) reveals
> aggregate T1 load only. It is recorded as a global-load `451` under ADR-035
> I9 as written ("it reports storage, database or load failure for the whole
> `DATA`, and it is never chosen per tenant"). It is **not** recorded under
> ADR-037 A2, and **not** under the 2026-11-06 timing-disclosure exception
> or its extension.

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
  - `testinbox_t1_schedule_total{class,outcome}`, with class `fresh` or `catch_up`;
  - `testinbox_smtp_transactions_total{identity}`;
  - `testinbox_ingest_allowance_oldest_seconds`.
- **Record dated notes that supersede this branch's refund text,** never
  rewriting it:
  - ADR-027 §4;
  - the ADR-035 amendment D reading;
  - `beta-limits.md`;
  - `abuse-model.md`;
  - ADR-004 §5: IDENT is a charging key, not a message identity.

## 3. Load and drain argument

The edge values used here are the observed ones (§6, which also gives their
status):

- q = `queue_run_delay` = 120 s;
- backoff from 120 s to 600 s, so a deferred message is presented again
  within P = 600 + 120 = 720 s, and first within 240 s;
- L = `maximal_queue_lifetime` = 4 h;
- margin M = 30 min;
- one ingestion node.

**Capacity assumption S.** The edge and ingestion can carry 2R = 62
events/s:

- relay session time t_s ≤ 20/62 ≈ 0.32 s;
- 16 slots ÷ event time ≥ 62/s.

Evidence G-C and G-E (§7) must show S holds. Every number below assumes it.

### T1 load

> S(t, T) ≤ B_f + B_c + (R_f + R_c)·T + 16·nodes = 5 580 + 62·T + 16·nodes

- **Sustained:** at most 2R = 62 events/s, with fresh alone ≤ R.
- **Bursts:** fresh bursts are ≤ 1 860, which is amendment D's accepted
  residual, unproven and unchanged. Catch-up bursts are ≤ 3 720 per scan.
  They enter at the edge's pace (≤ 20 sessions ÷ t_s) through ≤ 16 slots per
  node.
- **Evidence:** the catch-up bursts are new and need G-C. Gate D's 2R
  scenarios last 30 s, while a drain holds 2R for longer, so G-C must also
  cover sustained 2R (§7).

### Fresh overflow

Fresh overflow means fresh events deferred by `SLOT_WAIT`, which turns them
into retries.

- Under S, slots serve ≥ 62/s. A cohort-wide burst of 1 860 is therefore
  served for `W_slot` = 10 s (620 events), and the remaining ≤ 1 240
  overflow.
- Workspace buckets need b/r = 60 s to refill a burst.
- So the adversarial overflow rate is ρ_o ≤ 1 240 / 60 ≈ 20.7 events/s,
  when all 31 workspaces burst together every 60 s.
- Overflow presented in any one scan is ≤ ρ_o·q ≈ 2 480.

### Drain bound

**Congestion** is any time in which retries are refused for a T-class reason
other than an empty catch-up bucket: infrastructure, `SLOT_WAIT` saturation,
or T1 rollbacks.

- **Backlog.** Let D be the total congestion within the oldest deferred
  message's 4 h window, so repeated episodes add up. The backlog is then
  Q_D ≤ (30+L)·b + R·D = 1 860 + 31·D.
- **Net admission per scan.** When a backlog message is presented, the scan
  admits at least min(what is presented, B_c − overflow presented in that
  scan). Under adversarial fresh that is ≥ 3 720 − 2 480 = 1 240.
- **Synchronized backlog** (it arrived within about one scan, so the edge
  presents it together once per P):
  T_drain ≤ 240 + 720·⌈Q_D / net⌉.
- **Spread backlog** (its arrivals span ≥ P, so it falls into ≥ 5 scan
  phases): T_drain ≤ 240 + Q_D / (net / q).
- **Delivery condition.** Mail is delivered within L − M if
  D + T_drain ≤ 12 600 s:

| Fresh during recovery | Backlog | Net per scan | Largest D still delivered |
|---|---|---|---|
| adversarial, ρ_o = 20.7/s | synchronized | 1 240 | ≈ 9 min |
| adversarial | spread | 1 240 | ≈ 50 min |
| no overflow | synchronized | 3 720 | ≈ 28 min |
| no overflow | spread | 3 720 | ≈ 1.7 h |

**With no congestion,** every deferral from steady adversarial fresh is
admitted at its first presentation, within 240 s. Each scan's overflow
(≤ 2 480) is below B_c.

**When it cannot drain,** stated exactly:

- **D beyond the table.** Mail is then lost at the edge (RL-3).
- **B_c below the overflow presented per scan.** If G-C qualifies only a
  smaller B_c, every row of the table scales with B_c − 2 480. At
  B_c ≤ 2 480, adversarial fresh never lets the backlog drain.
- **S does not hold.** If t_s > 0.32 s, or the node is slower, catch-up
  admits retries the edge or the slots cannot carry. Drain then runs at
  (capacity − fresh), and stops entirely once capacity ≤ R + ρ_o.
- **An edge assumption is violated** (§6 table): a destination marked dead,
  reload or restart effects, or operator holds.

### Conservation (I-2)

For every `INGEST` bucket:

> tokens spent = copies whose T1 committed + allowances outstanding
> + allowances forfeited + allowances expired
> + copies discarded by the limiter after a spend

An inbox-only row deleted by a committed T1 counts in the last term.

### Delivery and privacy consequences, together

**Delivery.**

- In-envelope fresh traffic is never refused by scheduling. It waits for
  slots as before.
- Deferred mail is delivered at its first presentation (≤ 240 s) in steady
  state, and within the drain table after congestion. Beyond that it is lost
  (RL-3, not accepted).
- **Edge coupling.** Admitted catch-up work holds relay sessions for up to
  B_c·t_s/20 per scan: about 19 s at t_s = 0.1 s, about 60 s at 0.32 s.
  Fresh mail queued at the edge waits behind it. It then reaches ingestion
  bunched, is charged on arrival, and can exceed b (RL-9).

**Privacy.**

- Both refusals are global (I9 as written).
- A probe's retry ordinal now also reflects global catch-up availability,
  which is aggregate load.
- It is still one ordinal per paid probe, so the bound stays log₂(K+1) for
  the modeled observation (§4).

## 4. Privacy: the I9 extension and its record

The owner accepted this extension **conditionally** (#124 comment
6686171551) on four conditions:

- the reviewed retry schedule, network isolation and concurrency assumptions
  are enforced;
- Ops verifies on the **live** edge that ETRN is refused;
- log₂(K+1) is described as a bound for the modeled retry-ordinal
  observation, not as a universal timing bound;
- the wording is reconciled with the final scheduling design.

The edge is **internet-facing**: it listens on `0.0.0.0:25`, has a live MX,
and Ops observed it (6686107928). The owner confirmed that public inbound
SMTP and the MX were authorized for the owner-only production beta launch
(#124 comment 6686330363). Ops records the listener going live at
2026-10-28 18:07:03Z and the MX at 18:12:03Z (6686375458).

**Observation model.** The bounds hold only under all of these:

- **Who observes.** The observer is an authenticated participant. Internet
  senders reach only the public edge. They get the uniform `250` and no DSN,
  and the edge stores and forwards, so they read no delivery timing. They can
  supply load to addresses they know, and that load is bounded by `INGEST`.
- **Network.** Only the edge reaches ingestion SMTP (S-C2). Under deployed
  `ALL` this is enforced by I-20. `xforward-peers` only prevents identity
  forgery.
- **What is observed.** The observer reads only the eventual delivery time
  of its own co-addressed copy: `receivedAt` against the edge's `Received:`
  time.
- **Retry schedule.** These are observed values. The contract pins them (§6):
  - scans every 120 s;
  - backoff 120 s to 600 s;
  - lifetime 4 h.

  The schedule excludes operator flushes (`edge-stop.sh`: `postsuper -H ALL;
  postqueue -f`; `postsuper -r`). Each use adds at most one presentation to
  every held or deferred message, and Ops logs each use.
- **No client-triggered retries.** No SMTP client can trigger an early retry
  once ETRN is refused. ETRN is **open on the live edge today**, so the model
  does not hold until Ops verifies the refusal.
- **Reconcile reload.** Reconcile's `systemctl reload postfix` (falling back
  to restart) runs on a config change at most every 2 min. It is assumed not
  to advance next-attempt times. This is **unverified**: Ops reports it with
  the ETRN post-deploy check.
- **Concurrency limits.**
  - edge relay concurrency: 20 (the Postfix default; pin proposed in §6);
  - write slots: 16 per node and 4 per workspace;
  - T1 scheduling (§2.3): fresh 1 860 + 31/s, catch-up 3 720 per scan +
    31/s, both global.

**Bounds, for the modeled retry-ordinal observation only.**

- Every probe event is paid once from W's `INGEST` budget, so the number of
  probe events is ≤ b + r·T.
- A deferred probe is retried at the edge's scan instants without a new
  charge. Its delivery ordinal shows the first attempt at which both of
  these held:
  - W was below its slot cap;
  - a global catch-up token was available, which reflects aggregate load
    only.
- One ordinal per paid probe gives at most log₂(K+1) bits:
  - K ≈ 26 attempts in 4 h on the observed schedule gives about **4.8 bits**;
  - the hard bound K ≤ 121 at the 120 s minimum gives at most **6.9 bits**.
  - Each operator flush, and each retry caused by a reload if the
    verification finds any, adds one attempt to K.
- This is not a bound on every timing observation.
- I-17's immediate deferral changes the `451`'s latency, not the ordinal.

**Text to add to the ADR-035 I9 note (implementation round):**

> **Dated addition (2026-11-06): retry-timing disclosure under the INGEST
> deferral allowance.** Authority: owner decisions of 2026-11-06, #124
> comments 6684756433 (Q-1 option 2) and 6686171551 (conditional acceptance),
> for the initial beta of at most 30 admitted accounts.
>
> A probe event deferred before T1, or by a proven T1 rollback, keeps its
> paid `INGEST` allowance. The edge retries it without a new charge, so its
> eventual delivery ordinal tells the observer at which edge retry the probed
> workspace was first below its slot cap. A global catch-up token also had to
> be available at that retry.
>
> The edge is public. The figure applies to the modeled retry-ordinal
> observation only, under:
> - the observed edge retry schedule (`queue_run_delay` 120 s, backoff
>   120–600 s, `maximal_queue_lifetime` 4 h, `bounce_queue_lifetime` 0);
> - ETRN refused, verified on the live edge;
> - operator flushes counted as extra attempts;
> - S-C2 enforced;
> - the enforced concurrency limits (edge relay concurrency 20; 16 write
>   slots per node, 4 per workspace; global T1 scheduling).
>
> Under that model, one paid probe reveals at most log₂(K+1) bits: about 4.8
> (K ≈ 26), and at most 6.9 (K ≤ 121) plus operator flushes. Probe events stay
> bounded by the probed workspace's `INGEST` budget. This is not a universal
> bound on timing information.
>
> No redemption window exceeds the edge's delivery lifetime by more than
> 15 min. The exclusions of the original exception stand. If the mechanism is
> found to exceed this scope, it goes back to the owner. The exception is
> reassessed, with #139, before any cohort expansion. The acceptance remains
> conditional until Ops has verified ETRN refusal and the effect of reloads
> on the live edge.

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
- **`t1_schedule`** is two rows, fresh and catch-up.
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

**Edge values this design assumes** (observed by Ops on edge-fr-01,
2026-11-06, #124 comment 6686107928):

| Value | Observed | Status | Used for |
|---|---|---|---|
| `maximal_queue_lifetime` | 4h | pinned (`production`) | TTL, drain deadline, K |
| `bounce_queue_lifetime`, `notify_classes` | 0, empty | pinned (`required`) | expiry is cleanup, not loss |
| `minimal_backoff_time` / `maximal_backoff_time` | 120s / 600s | pinned (`production`) | P = 720 s, K |
| `queue_run_delay` | 120s | pinned (`production`) | q; B_c = R_c·q |
| `relay_destination_concurrency_limit` | 20 (default) | **pin proposed** (Ops review) | HELD rows, edge capacity, coupling |
| `initial_destination_concurrency` | 5 (default) | **pin proposed** (Ops review) | ramp-up after idle; slows the start of a scan's catch-up |
| `default_destination_concurrency_failed_cohort_limit` | 1 (default) | **pin proposed** (Ops review) | a connect-level failure (ingestion down, or I-20's `421`) marks the destination dead and defers all relay mail, which then arrives in sync |
| `relay_destination_rate_delay` | 0s (default) | **pin proposed** (`required`, Ops review) | any non-zero value forces concurrency 1 and breaks S |
| `relay_destination_recipient_limit`, `smtpd_recipient_limit` | 50, 50 | pinned | one DATA, one event |
| `default_process_limit` | 100 (default) | assumption (≥ 20) | relay concurrency is reachable |
| `qmgr_message_active_limit` | 20000 (default) | assumption | a scan moves at most 20 000 messages; B_c < 20 000 |
| `in_flow_delay` | 1s (default) | assumption | throttles inbound arrival only when qmgr lags |
| `smtpd_client_*` limits | 20 / 60/min / 100/min | pinned (`production`) | per-client limits on a public edge. They do not bound a distributed sender; `INGEST` does |
| ETRN | **open today** | keys added (§6). **Needs live verification** | no client-triggered retries |
| Operator flush (`edge-stop.sh`) | after an operator hold only | Ops procedure; each use logged | an extra presentation (K, catch-up burst) |
| Reconcile reload or restart | on a config change, at most every 2 min | **unverified** effect on next-attempt times | P, K |
| Relay session time t_s | not measured | **needs evidence** (G-E) | assumption S: t_s ≤ 0.32 s |

**Ops:**

- Close ETRN on the real edge (above) and **verify on the live edge** that it is refused.
- Verify whether reconcile's reload or restart changes deferred messages' next-attempt times. Report it with the ETRN check (AT-23 reproduces it on the rehearsal image).
- Review the proposed pins in the table above.
- Log every operator flush (`edge-stop.sh`).
- The edge is live, so switch to long queue IDs only when the deferred queue holds no tenant mail (`postqueue -j`) (RL-7).
- Add an `ops_activation_prerequisites` entry.
- Make sure ingestion sees the edge's real address (no userland-proxy SNAT).

**`DeploymentSafety` refuses `ALL` when:**

- `xforward-peers` is empty or contains anything other than single hosts;
- `trusted-clients` contains anything other than single hosts;
- the scheduling pins (§2.3) do not hold, or the recorded G-D or G-C evidence does not cover R_c and B_c;
- the hold inequality does not hold.

## 7. Evidence

Nothing here is qualified today. Gate D **failed** (run 3), and any reliance
on 2R needs a passing run at that load. `ALL` stays refused until G-D and
G-C pass with the configured R_c and B_c.

**G-D: gate D at R and 2R, with consume in T1** (focused remeasurement).

- **Command.** The documented gate command (`docs/dev/storage-benchmark.md`)
  on the staging host class, at the implementing commit, with
  `--expected-rate 31 --rates 31,62` and every other flag unchanged.
- **Harness.** `ProtocolAssembly` builds T1 with the consume `DELETE`, in
  the statement order of §2.2 step 6. It seeds one held allowance row per
  copy. The scheduling spend runs at its production position with a
  non-binding capacity.
- **What it isolates.** New timers `consume` and `scheduleSpend`. They are
  compared with run 3 at equal (workspaces, concurrency, rate) for:
  - `t1P99` and `lockTimeoutRate`, which should show only the `consume`
    delta;
  - `slotQueueingDeadlineMisses` and the sustained ratio, which show
    `scheduleSpend`;
  - deadlocks and other errors.
- **Production-pin run at R.** One more run at R with the production pins
  (fresh 1 860 / 31, catch-up 3 720 / 31). It must show zero fresh
  `SCHEDULE_WAIT`.
- **A′.** It may be combined with the A′ rerun if the owner confirms that
  paper.
- **Expected impact.** About +0.5 ms on `t1Transaction` for consume, and
  about 1 ms per event for the spend, on a hot row at ≤ 62/s.

**G-C: catch-up burst and sustained 2R** (new scenario, new harness flags).

- **Setup.** One node, the staging host class, production-faithful T1 with
  consume, 16 slots, `W_slot` 10 s. Fresh traffic runs throughout:
  - Poisson at R;
  - in a second variant, the adversarial pattern of all 31 workspaces
    bursting 60 every 60 s.
- **Catch-up load.** Every 120 s, a batch of 3 720 retry events with held
  allowances, offered by 20 closed-loop clients (the relay concurrency).
- **Duration.** At least 20 min (10 scans) after warm-up, which also covers
  sustained 2R beyond gate D's 30 s scenarios.
- **Pass criteria:**
  - catch-up admitted per scan ≥ 0.995 × min(offered, B_c);
  - zero fresh `SCHEDULE_WAIT` under the Poisson variant;
  - fresh T1 p99 ≤ 50 ms;
  - `lockTimeoutRate` < 0.1 %;
  - zero deadlocks, zero other errors, zero post-T1 failures.
- **Reported.** Slot occupancy, the `SLOT_WAIT` rate, and per-scan drain
  time.
- **Proposed flags:** `--catch-up-batch 3720 --catch-up-period-seconds 120
  --catch-up-clients 20 --fresh-pattern poisson|bursty`.
- **Fallback.** If it passes only at a smaller B_c, `DeploymentSafety` caps
  B_c there, and the §3 table is recomputed.

**G-E: relay session time** (assumption S).

- **What.** Edge→ingestion session time t_s for the beta message-size mix,
  with real MinIO uploads (gate D stubs uploads).
- **Where.** On staging, from ingestion's event timers plus the edge log's
  `delays=…/d` term.
- **Requirement.** S needs a p99 of t_s ≤ 0.32 s. Otherwise the §3 table is
  recomputed with capacity 20/t_s.

**Reused as is:** the filesystem and storage qualification (E1–E11, gate F,
A2.2). No object, upload, fence, MinIO or filesystem dependency changes.

**New CI and rehearsal evidence:**

- the AT-11 proofs;
- AT-23 (reload and restart effect on next-attempt times on the rehearsal
  image);
- the live edge's Ops reconcile and verification of §6.

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
17. **I-17 Edge occupancy.**
    - A catch-up event is never kept waiting for a token: an empty catch-up
      bucket means `SCHEDULE_WAIT` at once.
    - A pure retry whose workspace is at its slot cap is likewise deferred
      without the `W_slot` wait.
18. **I-18 Scheduling.**
    - Every T1 attempt is preceded by exactly one committed or uncertain
      spend, taken after the slot, from exactly one global bucket:
      - fresh: 1 860 + 31/s;
      - catch-up: R_c·q + R_c/s, with R_c = 31.
    - So S(t, T) ≤ 5 580 + 62·T + 16·nodes.
    - A refusal is in T.
    - There is one spend per T1 transaction. The partitioned fallback (lab
      only) spends a second token.
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
    - The refusal is the transient greeting `421 4.3.2 Service not
      available`, then close, never a 5xx, so a mis-listed edge defers its
      mail rather than bouncing it.
21. **I-21 Fresh envelope untouched.** A fresh event conforming to the
    `INGEST` envelope is never refused by scheduling.
22. **I-22 Qualified catch-up only.** `ALL` is refused unless passing G-D
    (R and 2R) and G-C evidence covers the configured R_c and B_c. A failed
    or pending run is never treated as qualification.

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
     traffic at the full envelope. The measured T1 arrival rate stays within
     5 580 + 62·T + 16·nodes, with no `LOCK_TIMEOUT`. Overflow is deferred
     with its allowance, and every in-budget message is stored once.
   - (c) Attempts that certainly roll back (`HOLD_LOST`, `LOCK_TIMEOUT`)
     still spend a token, from the class of the event.
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
      connect with `421 4.3.2`, never a 5xx, and an edge that is not on the
      list keeps the message queued. A listed trusted client connects, and
      gets no identity.

20. **AT-20 Drain.** A simulation that models the edge: scans every 120 s,
    age-based backoff from 120 to 600 s, 20 sessions, a 4 h lifetime.
    - It feeds the real scheduler arithmetic with continued fresh traffic at
      the full envelope, the adversarial burst pattern, repeated congestion
      episodes and 31 competing workspaces.
    - Every message deferred within the §3 table's D is admitted within
      4 h − 30 min.
    - Beyond that D, the simulation reproduces the predicted loss.
21. **AT-21 Fresh untouched.** A random (b, r)-conformant cohort stream
    through the fresh bucket is never refused (I-21).
22. **AT-22 Activation.** `ALL` is refused when:
    - there is no G-C evidence;
    - the evidence is for a smaller B_c or R_c;
    - gate D evidence is failed or pending (I-22).
23. **AT-23 Edge schedule (rehearsal image).** It measures deferred
    messages' next-attempt times across `postfix reload` and `restart`, and
    across an operator flush. It asserts or reports how many extra
    presentations each causes. The answer feeds K and P.

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
3. **RL-3 Edge expiry.** This is genuine loss (ADR-035 §12).
   - *Mechanism:* congestion (§3) whose total D, within a message's 4 h,
     exceeds the drain table:
     - ≈ 9 min under adversarial fresh with a synchronized backlog;
     - ≈ 28 min to 1.7 h without fresh overflow;
     - smaller if G-C caps B_c, or if S fails.
   - *Also loss:* a T-class condition that persists for more than 4 h.
   - *Metrics:* edge queue age (Ops alerting, already a prerequisite),
     `testinbox_ingest_allowance_oldest_seconds`, and the per-class
     `SCHEDULE_WAIT` rate.
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
7. **RL-7 Short-ID transition.** The edge is live.
   - *Bound:* the deferred tenant messages present at the switch, for at most
     4 h. Zero if the switch happens while the deferred queue holds no tenant
     mail.
   - *Metric:* `identity="none"`.
8. **RL-8 Redemption after δ.**
   - *Mechanism:* an edge attempt that started before the edge's deadline
     reaches the end of `DATA` more than 15 min plus (`created_at` −
     arrival) later.
   - *Metric:* `expired_on_redeem`. Any non-zero value is investigated.

9. **RL-9 Edge-delay bunching.**
   - *Mechanism:* mail held at the edge arrives at ingestion bunched. It is
     charged on arrival, so a sender within its envelope can exceed b and
     lose copies behind a `250`. The mail is held when:
     - admitted catch-up work occupies the 20 relay sessions, for up to
       B_c·t_s/20 per scan;
     - ingestion is unreachable, or the destination is marked dead;
     - a pre-resolution `451` was given, which charges nothing and creates
       no allowance;
     - an operator hold was in place.
   - *Bound:* per workspace per episode, ≤ r × the edge delay, and only for a
     workspace at its burst limit.
   - *Metrics:* edge `delays=` (Ops) and
     `testinbox_rate_decision_total{category="INGEST",outcome="rejected"}`.
   - *Minimisation done:* catch-up never waits, so it occupies relay
     sessions only for real work. This failure is pre-existing for outages
     and is amplified by catch-up.

**Delays only, never loss:** `HOLD_BUSY` (one deferral per lost edge
session), and `SCHEDULE_WAIT` and `SLOT_WAIT` deferrals.

### (d) For the owner

Only privacy, delivery-policy and resource trade-offs are listed here.

1. **Scheduling recommendation: option (a).**
   - Fresh traffic is unchanged: it is never refused by scheduling.
   - Catch-up is 31/s, with up to 3 720 per edge scan.
   - T1 is ≤ 2R sustained, with catch-up bursts of up to 3 720 per scan.
   - It depends on G-D at 2R, which **failed** (run 3), and on a new G-C.
     `ALL` stays refused until both pass.
   - If G-C passes only at a smaller B_c, congestion tolerance shrinks (§3).
2. **Congestion tolerance.** Delivery is guaranteed only for congestion
   within the §3 table: about 9 min worst case under adversarial fresh and a
   synchronized backlog, up to about 1.7 h without fresh overflow. Loss
   beyond that is RL-3, not accepted.
3. **Edge coupling (RL-9).** Catch-up work and outages hold fresh mail at
   the edge, and its bunched arrival can exceed b. The options are:
   - accept it;
   - review a higher relay concurrency with Ops;
   - charge `INGEST` against the edge's arrival time instead. That is a
     policy change, not proposed here.
4. **Privacy.** The acceptance is conditional and still open on:
   - live ETRN refusal (Ops);
   - the reload effect (AT-23, Ops);
   - operator flushes counted in K.

