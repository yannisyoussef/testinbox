# INGEST deferral allowance and carved T1 scheduling — design

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

**Fourth round.** Both reviewers agree on one scheduling mechanism: the
correctness reviewer's *carved single bucket* (§2.3), with the recovery
requirement R-REC (§3). Their correctness and security changes are applied
in §2.3, §3, §4, §6, §7 and §8. The edge is internet-facing.

**Superseded:**
- the single shared T1 scheduling budget of 31 + 31/s (round 2);
- option (a)'s separate catch-up rate above R (round 3). It survives only as
  the upgrade path: raise R_T when evidence qualifies it;
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

### 2.3 Scheduling: one carved global bucket

Both reviewers agree on this mechanism.

**The mechanism** (correctness reviewer, verbatim):

> "One global bucket, rate R_T, capacity B = σ′ + B_c, where σ′ = (30+L)·b +
> R·W_slot. A fresh event may spend any token. A catch-up event may spend only
> when tokens > σ′, and is refused at once otherwise (`SCHEDULE_WAIT`,
> allowance kept). R_T = R until passing evidence qualifies a higher R_T.
> B_c is qualified as a burst by G-C at R_T."

**Values.**

| Quantity | Value |
|---|---|
| R_T | R_gated = 31/s |
| σ′ | (30+L)·b + R·`W_slot` = 1 860 + 310 = **2 170** |
| B_c (candidate) | R_T·`queue_run_delay` = 3 720. It must pass G-C at R_T |
| B | σ′ + B_c = 5 890 |

The bucket is a single PostgreSQL row, `t1_schedule`. It uses the database
clock, the `TokenBucket` arithmetic, and `GREATEST(updated_at, now())`.

**Classes.**

- *Catch-up event:* at least one allowance was taken in the Hold step.
- *Fresh event:* every other event, including events without identity.

**Spending.**

- Every T1 attempt spends exactly one token, in its own `REQUIRES_NEW`
  transaction, after the slot and immediately before T1.
- A token is never returned, and attempts that certainly roll back are
  counted.
- A fresh event that finds no token waits up to its remaining `W_slot`.
  After that it gets `SCHEDULE_WAIT`, and the allowance is kept.
- A catch-up event never waits (I-17).

**Why fresh is never refused (I-21).**

- A fresh event's scheduling spend can come up to `W_slot` after its
  `INGEST` spend.
- So, from any instant, fresh spends are ≤ (30+L)·b + R·(T + `W_slot`) =
  σ′ + R·T.
- A catch-up spend never takes the bucket below σ′.
- Fresh therefore always faces an effective (σ′, R_T ≥ R) bucket, which
  admits every (σ′, R)-conformant stream.
- The approved fresh envelope (r = 1/s, b = 60, R = 31, and amendment D's
  accepted burst residual) is unchanged.

**Why retries recover.** Catch-up uses only the tokens fresh demand leaves
unused above σ′. The drain rate is therefore **R_T − ρ_f**, where ρ_f is the
current fresh demand: this is the feasibility fact. Total T1 attempts never
exceed the bucket: sustained ≤ R_T = R. So nothing above R is relied on.

**Fairness without a per-tenant refusal.**

- Both refusals are global.
- Every deferred message is presented again within P.
- The slot cap of 4 events per workspace per node limits any one workspace's
  concurrent T1 work.
- The drain bound (§3) covers the whole backlog.

**Upgrade path:** raise R_T when evidence qualifies it. The earlier
option (a), a separate catch-up rate above R, is that upgrade.

**Observability.**

- `testinbox_t1_schedule_tokens` is a global gauge with no labels.
- `testinbox_t1_schedule_total{class,outcome}` counts spends and refusals by
  class.
- An alert fires when ρ̄_f ≥ 0.8·R_T is sustained, measured as the rate of
  fresh spends. At that level a backlog drains at ≤ 0.2·R_T.

**`abuse-model.md` text, for the implementation round:**

> A T1 scheduling refusal (`SCHEDULE_WAIT`) is a global-load `451` under
> ADR-035 I9 as written ("it reports storage, database or load failure for
> the whole `DATA`, and it is never chosen per tenant"). It is **not**
> recorded under ADR-037 A2, and **not** under the 2026-11-06
> timing-disclosure exception or its extension. A catch-up refusal reveals
> aggregate fresh-plus-retry load. A single workspace's fresh sends move the
> threshold by at most b + r·W_slot; one workspace's retry backlog can
> dominate it.

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
  - `testinbox_t1_schedule_tokens`, a global gauge with no labels (alert:
    sustained ρ̄_f ≥ 0.8·R_T);
  - `testinbox_smtp_transactions_total{identity}`;
  - `testinbox_ingest_allowance_oldest_seconds`.
- **Record dated notes that supersede this branch's refund text,** never
  rewriting it:
  - ADR-027 §4;
  - the ADR-035 amendment D reading;
  - `beta-limits.md`;
  - `abuse-model.md`;
  - ADR-004 §5: IDENT is a charging key, not a message identity.

## 3. Load and recovery argument

**Edge values used** (observed; §6 gives their status):

- q = `queue_run_delay` = 120 s;
- backoff 120 → 600 s, so the re-presentation period is P = 720 s and the
  first retry comes within 240 s;
- L = `maximal_queue_lifetime` = 4 h; margin M = 30 min;
- relay concurrency 20, starting at `initial_destination_concurrency` = 5;
- one ingestion node.

**Assumption S.** The edge and ingestion carry R_T sustained:

- relay session time t_s ≤ 20/R_T ≈ 0.65 s, with ramp-up from 5 sessions
  (throughput 5/t_s until qmgr raises the concurrency);
- 16 slots ÷ event time ≥ R_T.

G-E (§7) must show S holds.

### T1 load

> S(t, T) ≤ B + R_T·T + 16·nodes = 5 890 + 31·T + 16·nodes

- **Sustained:** at most R_T = R = 31 events/s.
- **Bursts:** fresh ≤ σ′ (amendment D's accepted residual, plus `W_slot`'s
  R·W_slot term). Catch-up ≤ B_c on top, which needs G-C at R_T.
- **Evidence the mechanism needs:** only R_T = R evidence, plus G-C for
  B_c at R_T. The carved design never relies on 2R.
- **Amendment D's existing activation criterion** is separate. Gate D at R
  **and** 2R gates `ALL` today, and run 3 FAILED it. Whether the 2R part
  still gates activating this design is **the owner's decision** (§8(d)(ii)).
  Either way, nothing can be activated today.

### R-REC, the recovery requirement

Verbatim from the correctness reviewer; the last sentence is the security
reviewer's addition:

> "R-REC: A message deferred before T1, or by a proven T1 rollback, reaches
> T1 within `maximal_queue_lifetime` − M whenever D + Q(D)/(R_T − ρ̄_f) + P ≤
> L − M. Here D is the total congestion in its window, ρ̄_f the cohort's
> average fresh demand over the drain, R_T the evidence-qualified T1-attempt
> rate, and P the edge's re-presentation period. In-envelope fresh mail is
> never refused by scheduling. Every T1 attempt counts. Any R_T > R needs
> passing evidence at R_T. Under sustained fresh demand ≥ R_T, no backlog can
> drain: that is a stated capacity limit, not a guarantee. An internet sender
> can approach ρ̄_f = R only by exhausting the INGEST budget of ≥ 2 known
> addresses in every workspace, which already discards all their fresh mail
> behind `250`."

**Definitions.**

- *Congestion:* any time in which retries are refused for a T-class reason
  other than the σ′ threshold.
- *Backlog:* Q(D) ≤ (30+L)·b + R·D = 1 860 + 31·D.

**Precision for the edge's scans.** The Q/(R_T − ρ̄_f) term assumes the
backlog is presented spread across scans. Each scan then finds up to B_c
tokens above σ′, and those accrue at (R_T − ρ_f)·q ≤ B_c per scan.

A *synchronized* backlog behaves differently. It arrived within about one
scan, and the age-based backoff keeps it together, so the edge presents it
together. Two corrections from the correctness reviewer apply to it.

1. **Per-presentation refill.** A presentation admits at most
   A = min(B_c, (R_T − ρ̄_f)·P) retries. The tokens above σ′ refill fully
   (A = B_c) between presentations only while ρ̄_f ≤ 25.8/s, that is
   31 − 3 720/720.
2. **Presentation time.** The edge must also deliver each admitted batch,
   and it starts from s₀ = `initial_destination_concurrency` = 5 sessions.
   The next presentation is counted from each message's own attempt. So
   presentations are P′ = P + B_c·t_s/s₀ apart. At t_s = 0.32 s that is
   720 + 238 ≈ 958 s.

The synchronized condition is therefore:

> D + 240 + P′·⌈Q/A⌉ ≤ L − M

In the spread column, R-REC's P is read as the same P′.

**Largest congestion D still delivered.** L − M = 12 600 s, R_T = 31,
B_c = 3 720, t_s = 0.32 s, so P′ = 958 s.

| ρ̄_f during the drain | Drain rate R_T − ρ̄_f | Spread backlog (R-REC, P′) | Synchronized backlog |
|---|---|---|---|
| 0 | 31/s | ≈ 96 min | ≈ 21 min (A = 3 720) |
| 15.5/s (R/2) | 15.5/s | ≈ 64 min | ≈ 21 min (A = 3 720) |
| 24.8/s (0.8·R_T, the alert threshold) | 6.2/s | ≈ 31 min | ≈ 21 min (A = 3 720) |
| 28/s | 3/s | ≈ 16 min | ≈ 13 min (A = 2 160; refill no longer full) |
| ≥ 31/s (full envelope) | 0 | **no drain** | **no drain** |

At the S limit t_s = 0.65 s, P′ ≈ 1 204 s and the synchronized D falls to
about 17 min for ρ̄_f ≤ 25.8/s. G-E's measured t_s replaces 0.32 s.

With no congestion and ρ̄_f < R_T, each deferral is admitted at its first
retry, within 240 s, as long as a scan's retries fit in the tokens above σ′.

**When it cannot drain**, stated exactly:

- **Sustained ρ̄_f ≥ R_T.** This is the capacity limit. Mail is lost at 4 h
  (RL-3).
- **D beyond the table.**
- **G-C qualifies only a smaller B_c, or t_s is larger.** The synchronized
  column then shrinks: through A when B_c is smaller, through P′ when t_s is
  larger.
- **S fails.**
- **An edge assumption is violated** (§6).

### Conservation (I-2)

For every `INGEST` bucket:

> tokens spent = copies whose T1 committed + allowances outstanding
> + allowances forfeited + allowances expired
> + copies discarded by the limiter after a spend

An inbox-only row deleted by a committed T1 counts in the last term.

### Delivery and privacy, together

**Delivery.**

- In-envelope fresh mail is never refused by scheduling.
- Backlogs drain at R_T − ρ̄_f under R-REC.
- Sustained full-envelope fresh demand leaves no drain. That is the
  capacity limit (RL-3).
- **Edge coupling is unchanged (RL-9).** Admitted catch-up holds relay
  sessions for up to B_c·t_s/20 per scan, and fresh mail waits behind it at
  the edge.

**Privacy.**

- Both refusals are global (I9 as written).
- The delivery ordinal reflects W's slot cap, and whether a global token
  above σ′ was available, which is aggregate fresh and retry load.
- It is one ordinal per paid probe (§4).

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
- **No delivery-status notices.** No delivery-status notice of any class
  reaches a sender. This must be verified on the live edge:
  - `notify_classes` empty;
  - `bounce_queue_lifetime = 0`;
  - `delay_warning_time = 0h`;
  - `smtpd_discard_ehlo_keywords` discarding `dsn` and `etrn` (contract PR
    #147);
  - `NOTIFY=SUCCESS,DELAY` producing no notice.
- **Reconcile reload.** Reconcile's `systemctl reload postfix` (falling back
  to restart) runs on a config change at most every 2 min. It is assumed not
  to advance next-attempt times. This is **unverified**: Ops reports it with
  the ETRN post-deploy check.
- **Concurrency limits.**
  - edge relay concurrency: 20 (the Postfix default; pin proposed in §6);
  - write slots: 16 per node and 4 per workspace;
  - T1 scheduling (§2.3): one global bucket at R_T = 31/s with capacity
    σ′ + B_c = 2 170 + 3 720; catch-up only above σ′.

**Bounds, for the modeled retry-ordinal observation only.**

- Every probe event is paid once from W's `INGEST` budget, so the number of
  probe events is ≤ b + r·T.
- A deferred probe is retried at the edge's scan instants without a new
  charge. Its delivery ordinal shows the first attempt at which both of
  these held:
  - W was below its slot cap;
  - a global token above σ′ was available, which reflects aggregate fresh
    and retry load.
- One ordinal per paid probe gives at most log₂(K+1) bits.
- The attempt count is K ≤ 121 + reload-advanced attempts + operator flushes
  within the message's lifetime:
  - **≈ 4.8 bits** for K ≈ 26 on the observed schedule;
  - **≤ ≈ 6.9 bits** at K = 121, which holds only if Ops verifies that
    reloads do not advance next-attempt times;
  - **≤ ≈ 7.9 bits** otherwise (at most 120 reloads in 4 h, so K ≤ 241). In
    that case the figure returns to the owner.
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
> workspace was first below its slot cap, with a global token above σ′
> available (aggregate fresh and retry load).
>
> The edge is public. The figure applies to the modeled retry-ordinal
> observation only, under:
> - the observed edge retry schedule (`queue_run_delay` 120 s, backoff
>   120–600 s, `maximal_queue_lifetime` 4 h, `bounce_queue_lifetime` 0);
> - ETRN refused, verified on the live edge;
> - no delivery-status notice of any class reaching a sender, verified on the
>   live edge;
> - operator flushes counted as extra attempts;
> - S-C2 enforced;
> - the enforced concurrency limits (edge relay concurrency 20; 16 write
>   slots per node, 4 per workspace; global T1 scheduling).
>
> Under that model, one paid probe reveals at most log₂(K+1) bits, with
> K ≤ 121 + reload-advanced attempts + operator flushes within the message's
> lifetime: about 4.8 bits (K ≈ 26). The ≈ 6.9-bit bound holds only if Ops
> verifies that reloads do not advance next-attempt times. Otherwise the
> bound is ≤ ≈ 7.9 bits, and the figure returns to the owner. Probe events stay
> bounded by the probed workspace's `INGEST` budget. This is not a universal
> bound on timing information.
>
> No redemption window exceeds the edge's delivery lifetime by more than
> 15 min. The exclusions of the original exception stand. If the mechanism is
> found to exceed this scope, it goes back to the owner. The exception is
> reassessed, with #139, before any cohort expansion. The acceptance remains
> conditional until Ops has verified, on the live edge, ETRN refusal, the
> absence of delivery-status notices, and the effect of reloads.

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
- `smtpd_etrn_restrictions: reject`;
- `fast_flush_domains: ""`;
- `smtpd_discard_ehlo_keywords`, discarding `dsn` and `etrn`, and
  `delay_warning_time: 0h`. Both are being added in contract PR #147.

**Relay pins, proposed for Ops review.** These are the `relay_`-prefixed
forms, because tenant mail is routed through `relay:`. Ops verifies each
effective value on the live edge with `postconf relay_…`:

| Key | Value |
|---|---|
| `relay_destination_concurrency_limit` | 20 |
| `relay_initial_destination_concurrency` | 5 |
| `relay_destination_concurrency_failed_cohort_limit` | 1 |
| `relay_destination_rate_delay` | 0s |

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
| `bounce_queue_lifetime`, `notify_classes` | 0, empty | pinned (`required`) | expiry is cleanup, not loss; no DSN |
| `delay_warning_time`, `smtpd_discard_ehlo_keywords` (`dsn`, `etrn`) | not yet pinned | contract PR #147. **No notice of any class must be verified live** | no DSN reaches a sender (§4) |
| `minimal_backoff_time` / `maximal_backoff_time` | 120s / 600s | pinned (`production`) | P = 720 s, K |
| `queue_run_delay` | 120s | pinned (`production`) | q; candidate B_c = R_T·q |
| `relay_destination_concurrency_limit` | 20 (default) | **pin proposed** (Ops review; verify `postconf`) | HELD rows, edge capacity, coupling |
| `relay_initial_destination_concurrency` | 5 (default) | **pin proposed** (Ops review; verify `postconf`) | ramp-up after idle: assumption S, G-E, the start of a scan's catch-up |
| `relay_destination_concurrency_failed_cohort_limit` | 1 (default) | **pin proposed** (Ops review) | a connect-level failure (ingestion down, or I-20's `421`) marks the destination dead and defers all relay mail, which then arrives in sync |
| `relay_destination_rate_delay` | 0s (default) | **pin proposed** (Ops review; verify `postconf`) | any non-zero value forces concurrency 1 and breaks S |
| `relay_destination_recipient_limit`, `smtpd_recipient_limit` | 50, 50 | pinned | one DATA, one event |
| `default_process_limit` | 100 (default) | assumption (≥ 20) | relay concurrency is reachable |
| `qmgr_message_active_limit` | 20000 (default) | assumption | a scan moves at most 20 000 messages; B_c < 20 000 |
| `in_flow_delay` | 1s (default) | assumption | throttles inbound arrival only when qmgr lags |
| `smtpd_client_*` limits | 20 / 60/min / 100/min | pinned (`production`) | per-client limits on a public edge. They do not bound a distributed sender; `INGEST` does |
| ETRN | **open today** | keys added (§6). **Needs live verification** | no client-triggered retries |
| Operator flush (`edge-stop.sh`) | after an operator hold only | Ops procedure; each use logged | an extra presentation (K, catch-up burst) |
| Reconcile reload or restart | on a config change, at most every 2 min | **unverified** effect on next-attempt times | P, K |
| Relay session time t_s | not measured | **needs evidence** (G-E) | assumption S: t_s ≤ 20/R_T ≈ 0.65 s, with ramp-up from 5 |

**Ops:**

- Close ETRN on the real edge (above). **Verify on the live edge** that it is refused (`554`), and that `NOTIFY=SUCCESS,DELAY` produces no notice.
- Verify whether reconcile's reload or restart changes deferred messages' next-attempt times. Report it with the ETRN check (AT-23 reproduces it on the rehearsal image).
- Review the proposed pins in the table above.
- Log every operator flush (`edge-stop.sh`).
- The edge is live, so switch to long queue IDs only when the deferred queue holds no tenant mail (`postqueue -j`) (RL-7).
- Add an `ops_activation_prerequisites` entry.
- Make sure ingestion sees the edge's real address (no userland-proxy SNAT).

**`DeploymentSafety` refuses `ALL` when:**

- `xforward-peers` is empty or contains anything other than single hosts;
- `trusted-clients` contains anything other than single hosts;
- the scheduling pins (§2.3) do not hold, i.e. R_T, σ′ or B_c differ from the configuration that the recorded R_T evidence (gate D at R) and G-C evidence covers, plus amendment D's 2R gate unless the owner decides otherwise (§8(d)(ii));
- the hold inequality does not hold.

## 7. Evidence

Nothing here is qualified today.

- **The mechanism needs R_T = R evidence only.** The carved design's drain
  bound needs evidence at R_T = R, plus G-C for B_c at R_T. It never relies
  on 2R.
- **The 2R requirement is separate.** It is amendment D's **existing** gate D
  activation criterion (R and 2R), which run 3 FAILED. Whether gate D at 2R
  still gates activating this design is **the owner's decision**
  (§8(d)(ii)).
- **A higher R_T** (the upgrade path) needs passing evidence at that R_T.
- **`ALL` stays refused** until the evidence the owner requires passes.

**G-D: gate D with consume in T1** (focused remeasurement, at R and, under amendment D's existing criterion, 2R).

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
- **Production-pin run at R.** One more run at R with the production pins:
  R_T 31, σ′ 2 170, B_c 3 720. It must show zero fresh `SCHEDULE_WAIT`.
- **A′.** It may be combined with the A′ rerun if the owner confirms that
  paper.
- **Expected impact.** About +0.5 ms on `t1Transaction` for consume, and
  about 1 ms per event for the spend, on a hot row at ≤ 62/s.

**G-C: the B_c burst at R_T** (new scenario, new harness flags).

- **Setup.** One node, the staging host class, production-faithful T1 with
  consume, 16 slots, `W_slot` 10 s. Fresh traffic runs throughout:
  - Poisson, with ρ_f swept from 0 to R_T;
  - in a second variant, the adversarial pattern of all 31 workspaces
    bursting 60 every 60 s.
- **Catch-up load.** Every 120 s, a batch of 3 720 retry events with held
  allowances, offered by 20 closed-loop clients (the relay concurrency).
- **Duration.** At least 20 min (10 scans) after warm-up. The bucket keeps
  the total at R_T = 31/s sustained.
- **Pass criteria:**
  - catch-up admitted per scan ≥ 0.995 × min(offered, tokens above σ′);
  - the drain rate matches R_T − ρ_f;
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
- **Requirement.** S needs a p99 of t_s ≤ 20/R_T ≈ 0.65 s, including the
  ramp-up from `relay_initial_destination_concurrency` = 5 after idle.
  Otherwise the §3 table is recomputed with capacity 20/t_s.

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
    - A catch-up event never waits for a token. When the token count is not
      above σ′, it gets `SCHEDULE_WAIT` at once.
    - A pure retry whose workspace is at its slot cap is likewise deferred
      without the `W_slot` wait.
18. **I-18 Scheduling.**
    - Every T1 attempt is preceded by exactly one committed or uncertain
      spend from the one global bucket. The bucket has rate R_T and capacity
      σ′ + B_c, and the spend is taken after the slot.
    - Hence S(t, T) ≤ σ′ + B_c + R_T·T + 16·nodes, so sustained T1 attempts
      are ≤ R_T = R.
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
21. **I-21 Fresh envelope untouched.**
    - A catch-up spend never takes the bucket below σ′ =
      (30+L)·b + R·W_slot = 2 170.
    - Fresh scheduling spends lag their `INGEST` spends by ≤ `W_slot`.
    - So a fresh event conforming to the `INGEST` envelope is never refused
      by scheduling.
22. **I-22 Qualified capacity only.**
    - `ALL` is refused unless passing evidence covers R_T and B_c: gate D at
      R_T = R and G-C for B_c at R_T.
    - Amendment D's 2R activation gate also applies unless the owner decides
      otherwise.
    - Any R_T above R needs passing evidence at that R_T.
    - A failed or pending run is never treated as qualification.
23. **I-23 Recovery (R-REC, §3).**
    - **Spread backlog.** A deferred message reaches T1 within L − M whenever
      D + Q(D)/(R_T − ρ̄_f) + P′ ≤ L − M.
    - **Synchronized backlog.** The condition is
      D + 240 + P′·⌈Q / min(B_c, (R_T − ρ̄_f)·P)⌉ ≤ L − M.
    - **P′ = P + B_c·t_s/s₀**, with s₀ = `initial_destination_concurrency` = 5.
      The edge needs time to deliver each admitted batch, and the next
      presentation is counted from each message's own attempt.
    - The tokens above σ′ refill fully between presentations only while
      ρ̄_f ≤ 25.8/s.
    - Under sustained ρ̄_f ≥ R_T nothing drains. That is a stated capacity
      limit, not a guarantee.

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
     consume, rollback, expire and refill. It checks that:
     - I-2 holds;
     - S(t, T) stays within I-18;
     - catch-up never takes the bucket below σ′.
   - (b) A storm: 31 workspaces with allowances outstanding, plus fresh
     traffic at the full envelope.
     - The measured T1 arrival rate stays within
       5 890 + 31·T + 16·nodes, with no `LOCK_TIMEOUT`.
     - Overflow is deferred with its allowance.
     - Every in-budget message is stored once, or, past the capacity limit,
       is counted toward RL-3.
   - (c) Attempts that certainly roll back (`HOLD_LOST`, `LOCK_TIMEOUT`)
     still spend a token.
10. **AT-10 I9.** Under the stated model:
    - slot-wait samples per paid probe are counted, and are ≤ K+1;
    - probe events are ≤ W's budget;
    - probe retries spend no `INGEST` token;
    - a pure retry at its cap, or below σ′, is deferred without waiting.
11. **AT-11 Edge** (rehearsal, and on the live edge by Ops).
    - IDENT is present, stable across `451` and an edge restart, and distinct
      for identical sends.
    - The edge refuses a sender's `XFORWARD`.
    - The edge refuses ETRN with `554 5.7.1`.
    - `NOTIFY=SUCCESS,DELAY` produces no notice, and EHLO advertises neither
      `DSN` nor `ETRN`.
    - Only IDENT reaches ingestion.
    - Ingestion observes the edge's address.
    - Removing any §6 key, or any proposed relay pin, fails the mutation
      suite. That includes `smtpd_etrn_restrictions`, `fast_flush_domains`,
      `smtpd_discard_ehlo_keywords`, `delay_warning_time`,
      `relay_destination_concurrency_limit`,
      `relay_initial_destination_concurrency`,
      `relay_destination_concurrency_failed_cohort_limit` and
      `relay_destination_rate_delay`.
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

20. **AT-20 Drain.** A simulation of the edge:
    - It models scans every 120 s, age-based backoff from 120 to 600 s, and a
      4 h lifetime.
    - It models the edge's session ramp: concurrency starts at 5 and grows to
      20, and each admitted batch is delivered at t_s per session before the
      next attempt is counted.
    - It drives the real carved-bucket arithmetic with:
      - ρ_f swept from 0 to R_T;
      - the adversarial burst pattern;
      - repeated congestion episodes;
      - 31 competing workspaces;
      - spread and synchronized backlogs.
    - Every message within I-23 (both forms, with P′ and the
      per-presentation refill A) reaches T1 within L − M.
    - Beyond R-REC, and at ρ̄_f ≥ R_T, the simulation reproduces the
      predicted loss.
    - The measured drain rate is R_T − ρ_f.
21. **AT-21 Fresh untouched.**
    - A random (b, r)-conformant cohort stream goes through the bucket.
    - Random slot delays of up to `W_slot` are injected between each
      event's `INGEST` spend and its scheduling spend.
    - A concurrent catch-up load runs at the same time.
    - No fresh event is ever refused (I-21).
22. **AT-22 Activation.** `ALL` is refused with any of:
    - no G-C evidence;
    - evidence for a smaller B_c;
    - any R_T above what the evidence covers;
    - failed or pending gate D evidence at R_T;
    - failed or pending 2R evidence, while amendment D's 2R gate applies
      (I-22).
23. **AT-23 Edge schedule** (rehearsal image, then live by Ops).
    - Measures deferred messages' next-attempt times across
      `postfix reload`, `restart` and an operator flush, and reports the
      extra presentations each one adds.
    - The ≈ 6.9-bit figure stands only if reloads add none; otherwise
      ≤ ≈ 7.9 bits.
    - `NOTIFY=SUCCESS,DELAY` produces no notice.
    - ETRN gets `554`.

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
   - *Mechanism:*
     - R-REC does not hold: congestion D beyond §3's table, or a synchronized
       backlog larger than its form allows;
     - **the capacity limit:** sustained fresh demand ρ̄_f ≥ R_T leaves no
       drain, and the backlog is lost at 4 h;
     - a T-class condition that lasts longer than 4 h.
   - *Bound:* §3's table. Under sustained full-envelope fresh demand there is
     none.
   - *Internet senders:* one can approach ρ̄_f = R only by exhausting the
     `INGEST` budget of at least 2 known addresses in every workspace. That
     already discards all of that sender's fresh mail behind `250`.
   - *Metrics:*
     - edge queue age (Ops alerting);
     - `testinbox_ingest_allowance_oldest_seconds`;
     - `testinbox_t1_schedule_tokens`, with the alert on ρ̄_f ≥ 0.8·R_T;
     - the per-class `SCHEDULE_WAIT` rate.
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

This is one list, with delivery and privacy consequences together. Nothing
here is accepted.

**(i) The capacity limit (delivery).**

- In-envelope fresh mail is never refused by scheduling.
- Backlogs drain at R_T − ρ̄_f under I-23 (§3 table).
- Under sustained full-envelope fresh demand (ρ̄_f ≥ R_T = 31/s) nothing
  drains, and deferred mail is lost at 4 h.
- This is RL-3, not accepted. Raising R_T, the upgrade path, needs passing
  evidence at that R_T.

**(ii) Whether amendment D's 2R gate applies to activating this design.**

- The carved design's drain bound needs only R_T = R evidence, plus G-C for
  B_c.
- Gate D at R **and** 2R is amendment D's existing activation criterion,
  and run 3 FAILED it.
- The owner decides whether 2R still gates activating this design. Either
  way, nothing is activatable until the evidence the owner requires passes.

**(iii) RL-9, edge-delay bunching (delivery).**

- Mail held at the edge arrives at ingestion in a bunch. The hold comes from
  catch-up work occupying relay sessions, outages, pre-resolution `451`s, or
  operator holds.
- It is charged on arrival, so it can exceed b and be discarded behind a
  `250`.
- Bound: per workspace per episode, at most r × the edge delay.
- This is pre-existing for outages, and catch-up amplifies it.

**(iv) The privacy acceptance, which stays conditional.**

- Scheduling refusals are global, so they fall under I9 as written.
- There is one delivery ordinal per paid probe:
  - about 4.8 bits;
  - at most 6.9 bits under the model;
  - at most 7.9 bits if reloads advance next-attempt times. That case
    returns to the owner.
- It depends on Ops' live verifications:
  - ETRN refused;
  - no delivery-status notice of any class;
  - the reload effect.

**(v) The final residual list for explicit acceptance (§8(c)).**

| Residual | Short description |
|---|---|
| RL-1 | Failure after T1 commits |
| RL-2 | Second orphaning |
| RL-3 | Edge expiry, including the capacity limit |
| RL-4 | Transactions without identity |
| RL-5 | Forfeit by design |
| RL-6 | Database restore |
| RL-7 | Short-ID transition |
| RL-8 | Redemption after δ |
| RL-9 | Edge-delay bunching |

Each one is listed with its mechanism, bound or metric, and the
minimisation done.

## 9. RL-9 correction (PROPOSED: not agreed, not implemented)

**Authority.** Owner decision, #124 comment
[6686873641](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6686873641),
verbatim:

> "RL-9: investigate a correction before requesting acceptance. Preserve the
> distinction between traffic exceeding the published ingress allowance and
> within-envelope mail bunched by our own outage, hold or recovery process.
> Coordinate a focused proposal with Ops. Assess stable retry identity,
> trusted timing, restart behavior and the qualified processing envelope. Do
> not assume that changing timestamps, burst size or relay concurrency is
> safe without that assessment. Do not implement another full variant before
> correctness and security reviewers agree on the proposed invariants."

This proposal changes no burst size, relay concurrency or edge timestamp.

**Ops' input** (#124 comment
[6686924864](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6686924864)):

- No relay concurrency or timing change is acceptable without a measured
  case. Pinning the *current* values is a no-change hardening, and Ops
  treats every value this proposal relies on as pinned (§9.7).
- Whether the edge can attest an arrival time is being **measured** on a
  Postfix 3.8.6 lab mirror, not the live edge. **So every part of this
  proposal that relies on a trusted arrival time is CONDITIONAL on that lab
  result for its specific carrier (§9.2).** The fallbacks for a failed
  measurement are stated there.

### 9.1 The problem

`INGEST` is charged when a copy reaches ingestion. While the edge holds mail,
the bucket is not charged, so it refills to b and the cap discards the rest
of the refill. When the held mail is released, it arrives together and finds
at most b tokens.

**What is lost.** A workspace sending within its envelope at rate r through
a hold of H seconds loses about r·H − b copies, all behind a `250`. For
example, a 10 min outage loses about 540 of 600 copies.

**What holds mail at the edge:**

- ingestion is unreachable, or the destination is marked dead;
- pre-resolution `451`s (breaker, latch, activation, footprint), which
  charge nothing;
- an operator hold, then the `edge-stop.sh` flush;
- catch-up work occupying the 20 relay sessions;
- the edge's own queueing.

**Not affected:** mail deferred *after* charging. It already holds an
allowance.

**Genuinely over-allowance traffic** is traffic whose arrival stream *at the
edge* exceeds (b, r). The natural criterion is therefore: judge conformance
at the time the mail reached the edge, not the time it reached ingestion.

### 9.2 A trusted edge-arrival time: candidate carriers, all conditional

**None of these is assumed.** Each carrier is usable only if Ops' lab
measurement passes for it (§9.7, lab items 1–6).

**C1, primary: the long queue ID's time field, carried in `XFORWARD IDENT`.**

- **Encoding.** A long queue ID begins with the queue file's creation time:
  6 base-52 characters for the seconds, then 4 for the microseconds, in the
  vowel-free alphabet.
- **App-side pre-check.** This is not the measurement. On the rehearsal
  image, four IDs decoded to `postqueue -j` `arrival_time` exactly,
  including one taken through `postsuper -r`.
- **What it would need.** No new transport and no content parsing. IDENT is
  accepted only from single-host edge peers (I-4, I-20), and the edge refuses
  a sender's `XFORWARD`.
- **Conditional on lab items 1, 2, 4 and 5.**
- **Conditions both reviewers attached** (RQ-2):
  - a version pin (3.8.6);
  - a decode test;
  - AT-R9-1 cross-checking every decoded value against `arrival_time`;
  - an undecodable or clamped value gives no credit;
  - whether `postsuper -r` keeps the ID is for the lab to establish (item 1).
- **Caveats.** The encoding is Postfix's internal format (`mail_queue.h`).
  The time comes from the edge host's clock (INV-R9-6).

**C2, fallback if C1 fails: the edge-added `Received:` timestamp.**

- **Conditional on lab items 3 and 5.**
- **Parse rule (the security reviewer's):** parse only the **first** header
  field of the relayed copy.
  - It must match `by <edge myhostname> (Postfix) with ESMTP[S][A] id <IDENT>`.
  - The date is taken after the **last** `;`.
  - HELO and rDNS text are ignored.
  - Any mismatch gives no credit.
  - Accepted only from edge peers.
- **Costs.** The time is second resolution, it is carried in content bytes,
  and the parse needs its own review.

**Start of transaction or end of DATA (lab item 5).** A trickled `DATA` can
make a carrier's time mark the *start* of the transaction rather than the
end. If the lab shows that:

- claims draw only from slots after slot(t_a + D_max);
- a copy counts as late only if λ − D_max > ε_late;
- D_max is the edge's enforced maximum transaction time, from
  `smtpd_per_request_deadline`, `smtpd_min_data_rate` and `smtpd_timeout`,
  as reviewed by Ops.

Under today's settings `smtpd_per_request_deadline` is off, so a trickle is
bounded only by per-read timeouts, D_max is effectively unbounded, and C1 and
C2 give **no** credit. Bounding D_max is an edge change and needs Ops' own
measured, reviewed case. This proposal does not assume it.

**C3, the narrower fallback: the first time ingestion saw the IDENT.**

- **How.** At the first presentation, ingestion writes a durable row keyed by
  IDENT. It writes it even when it answers with a pre-resolution `451`. It
  uses that row's time in place of t_a.
- **Dependencies.** It needs only IDENT stability (lab item 2), with no edge
  time and no D_max.
- **Failure.** A failed first-sight write gives no credit.
- **What it covers.** Pre-resolution `451`s and later deferrals.
- **What it does not cover.** Mail held at the edge before its first sight:
  ingestion unreachable or the destination dead, operator holds, and
  catch-up coupling. That mail stays in RL-9.

**C4, if IDENT itself is not stable (lab item 2 fails).** No correction is
possible, and the allowance design must be revisited. RL-9 goes to the owner
unchanged.

**Eligible origin (lab item 6).** Only queue files received by the edge's
`smtpd` are eligible. Following Ops' suggestion, that includes loopback
clients: the edge assigns their times, so a sender cannot pre-date them
beyond D_max. These origins are **ineligible**:

- `pickup` (local submission). `authorized_submit_users = static:anyone`,
  so every login account can submit, and the client writes the maildrop
  file;
- internal notices, including an expiry bounce for a tenant-domain sender.
  The bounce path is ineligible.

Ingestion must tell these apart without trusting sender data, and IDENT
alone cannot do it. Proposal:

- **Advertise one more XFORWARD attribute, `PROTO`.** It is not personal
  data. `NAME`, `ADDR`, `HELO` and `PORT` stay unadvertised (I-10).
- **Eligibility rule.** Eligible only when `PROTO` ∈ {SMTP, ESMTP, ESMTPS,
  ESMTPA, ESMTPSA}. The value is used only for this decision, and is never
  stored or logged.
- **Conditional on lab item 6.** The lab records `PROTO` (and `SOURCE`, as a
  possible second discriminator) for each origin:
  - Internet `smtpd`;
  - loopback `smtpd`;
  - `sendmail`/`pickup`, including `sendmail -bs`;
  - bounces and notices.
- **If `PROTO` does not discriminate:** recommend that Ops restrict
  `authorized_submit_users`, and that bounce relaying is addressed, as a
  separate reviewed edge change. Until then, no late copy gets credit.

**Rejected outright:** DSN `ENVID`, which the sender sets.

### 9.3 Candidates

| | Distinguishes over-allowance from bunched mail | Forgeable or gameable | Effect on T1 | Interaction with the I9 exception | Verdict |
|---|---|---|---|---|---|
| **(a)** charge at virtual time t_a (rate limiter run against t_a) | exactly | not forgeable | bunched admits arrive at once | none new | **no.** Out-of-order release makes it refuse conformant mail unless its tolerance grows with the hold. Exact handling needs a per-arrival ledger |
| **(b)** credit inside Ops-declared outage or hold windows | only inside declared windows. Misses coupling and undeclared outages | over-allowance mail sent inside a window gets credit unless bounded, which reduces to (d) | as (d) | as (d) | **no.** Depends on manual declarations |
| **(c)** charge at the first presentation, even on a pre-resolution `451` | only for pre-resolution `451`s | no | none | changes ADR-025's pre-check ordering | **no.** Partial, and needs the DB during outages |
| **(d)** overflow credit since edge arrival | yes, see below | not forgeable. Credit exists only as allowance the workspace did not use | credited events are scheduled as catch-up | lateness-based class, no new channel | **recommended** |

### 9.4 How (d) works

**Credit is the refill the cap threw away.**

- For each `INGEST` bucket (per inbox and per workspace), refill that the cap
  discards is recorded as **overflow**, in 60 s slots.
  - It is written in the same transaction as the refill, after the bucket's
    `FOR UPDATE`.
  - Slots older than L + δ are dropped.
- **Lateness** is λ = DB `now()` − t_a, or λ − D_max under lab item 5.

**A late copy can claim one unit of credit, once.** All of these must hold:

- it has an eligible carrier and origin (§9.2);
- λ > ε_late;
- it finds no whole token;
- **no earlier presentation of its IDENT reached the INGEST charging step**
  (the marker below).

It then claims one unclaimed overflow unit, under these rules:

- the unit's slot must start ≥ t_a + ε_skew (+ D_max under lab item 5), and
  ε_skew is less than the slot length;
- narrow-first;
- every ledger read and write happens after the bucket's `FOR UPDATE`
  (I-13).

A copy that cannot claim is discarded, as today.

**The marker** (correctness; it closes the co-address exploit).

- **What it is.** A durable per-(edge, IDENT) row.
- **When it is written.** In the charge transaction, for charged and
  limiter-discarded copies alike. Pre-resolution `451`s write none.
- **The exploit it closes.** An attacker floods W past its allowance and
  co-addresses a saturated workspace, so every event hits `SLOT_WAIT` and is
  retried. Without the marker, the late retries would claim credit for
  copies that were already discarded.
- **Retention.** It expires at L + δ, with the allowance rows' cleanup.
- **Cost.** One durable write per identified event (§9.8).

**What is guaranteed** (correctness change 1, and the security reviewer's
honest statement; both adopted):

- **Arrival-indexed bound.** For every bucket and every s ≤ t: the spends and
  claims made during [s, t] by copies with t_a ≥ s are ≤ tokens(s) +
  r·(t − s). This holds because claims draw only on overflow from slots after
  t_a.
- **Over a hold of H:** admissions ≤ b + r·H. A sender conforming at the edge
  is admitted in full; a sender over its allowance is still discarded beyond
  that.
- **Over shorter windows, measured at ingestion,** admissions can exceed
  b + r·T. The excess comes from copies with t_a < s claiming older overflow,
  and is bounded by the unclaimed overflow (≤ r·(L + δ)). The arrival-indexed
  bound does not remove this excess, because it constrains only copies that
  arrived after s.
- **Shape within a hold is not policed.** This is owner item RQ-1.

**Scheduling (the qualified envelope).**

- Every event with λ > ε_late is scheduled as **catch-up** in the carved
  bucket, however its copies were paid. It keeps its allowances when
  deferred, including a credit-paid copy, which keeps an ordinary allowance.
- Late events without an allowance are counted as catch-up demand in I-23's
  Q(D) (security change 12).
- Fresh events never use credit, so I-21, I-18 and G-D/G-C are unchanged.
- Bunched mail is *delayed* within R-REC, not discarded.

**I9.**

- The class depends only on the event's lateness, never on a workspace's
  credit, so the scheduling refusal stays global.
- Admission and discard stay invisible over SMTP (`250`). Credit is part of
  the `INGEST` budget the exception's bound already names.

### 9.5 Proposed invariants

Where the two reviews overlap, the stricter wording is adopted, and noted.

1. **INV-R9-1 (trusted time, conditional).**
   - t_a comes only from a carrier whose Ops lab measurement passed: C1,
     else C2, else C3 within its narrower scope.
   - The carrier must come from a single-host allow-listed edge peer, with
     an IDENT of long-ID shape.
   - **C1:** decoded from the time field; an undecodable value gives no
     credit.
   - **C2:** only the first header field, matching
     `by <myhostname> (Postfix) with ESMTP[S][A] id <IDENT>`, with the date
     after the last `;`. HELO and rDNS text are ignored, and any mismatch
     gives no credit.
   - t_a is clamped to [now − (L + δ), now + ε_skew]. A value outside that
     range gives no credit.
   - Under lab item 5, t_a + D_max replaces t_a for lateness and slot
     eligibility.
   - No other header or content is ever used.
2. **INV-R9-2 (credit source).**
   - Credit is only overflow, the refill the cap discarded, recorded in the
     refilling transaction.
   - It never exceeds the recorded amount and expires at L + δ.
   - It is never returned (I-1).
3. **INV-R9-3 (eligibility).** A copy may claim credit only if all of the
   following hold:
   - λ > ε_late;
   - it has no whole token;
   - its origin is eligible (§9.2);
   - **no earlier presentation of its IDENT reached the INGEST charging
     step.** A durable per-(edge, IDENT) marker is written in the charge
     transaction for charged and limiter-discarded copies alike, and
     pre-resolution `451`s write no marker;
   - the unit's slot starts at ≥ t_a + ε_skew, with ε_skew < slot length;
   - the claim is narrow-first, once per unit;
   - every ledger read or write happens only after the bucket's
     `FOR UPDATE` (I-13).
4. **INV-R9-4 (envelope).**
   - For every bucket and every s ≤ t, the spends and claims made during
     [s, t] by copies with t_a ≥ s are ≤ tokens(s) + r·(t − s).
   - Over a hold, admissions are ≤ b + r·H.
   - Over shorter windows they can exceed b + r·T by unclaimed overflow
     (≤ r·(L + δ)).
   - Shape within a hold is not policed. This is the stricter, honest
     statement, adopted from the security review (change 11).
5. **INV-R9-5 (class).**
   - λ > ε_late means a catch-up event.
   - Late events without an allowance count as catch-up demand in I-23.
   - Fresh events never use credit, so I-21 is unchanged.
6. **INV-R9-6 (skew).** Credit and the late class are on only while both of
   these hold.
   - **(i) An independent measurement is recent.** It does not rely on the
     edge's own view: a canary submitted to the edge every 5 min to a
     reserved, denylisted local-part. Ingestion compares the decoded t_a
     with its own clock, discounting first-attempt latency. The latest
     canary must be ≤ 15 min old with |offset| ≤ ε_skew.
   - **(ii) No anomaly.** Not most new identities are arriving late while no
     hold is known.
   - Ops' exported `timesyncd` offset gauge is **recommended, not
     sufficient**. It is the edge's own view. If used, its sample must be
     ≤ 10 min old and within ε_skew.
   - Recommend authenticated NTP (NTS) on the edge to Ops. It runs
     `timesyncd` today, against an unauthenticated server.
7. **INV-R9-7 (restart).**
   - Overflow, claims and markers are durable in PostgreSQL.
   - t_a survives edge restarts and retries (lab).
   - A restored database has none of them (I-16).
8. **INV-R9-8 (labels).** Closed enums only. Metrics: overflow recorded,
   credit claimed or refused by reason, a lateness histogram and the canary
   offset.
9. **INV-R9-9 (injection).**
   - Only `smtpd` on port 25 and Ops-local `pickup` may inject relayable
     mail.
   - The contract records the `master.cf` service list: today only
     `0.0.0.0:25 smtpd`, with no `qmqpd` and no submission listener.
   - `pickup` and notice origins are ineligible for credit.
   - `postsuper -r` and spool restores are logged operator actions. Their
     t_a is clamped like any other.

**I-2, restated** (correctness change 4). For every `INGEST` bucket:

> tokens spent + credits claimed = copies whose T1 committed
> + allowances outstanding + allowances forfeited + allowances expired
> + copies discarded by the limiter after a spend

**I-23** counts late events without an allowance as catch-up demand.

**Values for review:**

- **ε_late = 30 s.** First-attempt relays take under 1 s end to end (Ops,
  n = 56, `d` p99 0.79 s). The 200–400 s tail is retries. The sample is
  small, so 30 s is a margin, not a measurement.
- **ε_skew = 1 s.** The live offset was +126 µs, one sample.
- **Slot = 60 s.**
- **D_max** is per lab item 5, from Ops' review.

### 9.6 Proposed acceptance tests

1. **AT-R9-1 carrier** (Ops lab first, then rehearsal and live). For the
   carrier in use:
   - t_a = `postqueue -j` `arrival_time` for fresh mail, deferred mail,
     after reload, after `stop/start`, after `postsuper -r`, and after an
     `edge-stop.sh` hold and flush;
   - IDENT is identical on every retry;
   - for C2, a HELO containing `; <date>` and folded header lines are parsed
     correctly or give no credit.
2. **AT-R9-2 outage.** A workspace sends at r for 10 min while ingestion is
   down.
   - On recovery, all 600 copies are stored.
   - At 2r, exactly b + r·H are admitted.
3. **AT-R9-3 other hold causes.** The same results hold for a pre-resolution
   `451` storm, an operator hold, and catch-up coupling.
4. **AT-R9-4 over-allowance.** A sender over its allowance is discarded
   exactly as today, whether its mail arrives on time or late.
5. **AT-R9-5 no credit.** None of these gets credit:
   - a non-peer;
   - a short ID or `[UNAVAILABLE]`;
   - an undecodable t_a;
   - a future t_a, or one older than L + δ;
   - an on-time copy;
   - an ineligible `PROTO` (`pickup`, a bounce);
   - stale or absent skew evidence.
6. **AT-R9-6 concurrency.** N concurrent late copies against k units produce
   exactly k claims, with no `40P01`.
7. **AT-R9-7 fresh unaffected.** AT-21 is rerun with late arrivals injected:
   fresh is never refused, and I-18 holds.
8. **AT-R9-8 skew.**
   - A canary offset beyond ε_skew disables credit.
   - So does a stale canary (> 15 min).
   - So does the late-majority anomaly.
9. **AT-R9-9 I9.** An event's class is identical whether W pays with tokens
   or with credit.
10. **AT-R9-10 co-address exploit.** A copy discarded at one presentation and
    late at the next claims nothing (the marker).
11. **AT-R9-11 old overflow.** An idle workspace's 4 h of overflow cannot be
    claimed by copies whose t_a is after it.
12. **AT-R9-12 envelope property.** A property test of INV-R9-4's
    arrival-indexed bound, including slot boundaries, skew, and D_max (lab
    item 5).
13. **AT-R9-13 injection.**
    - The contract test asserts `master.cf`'s service list: only port-25
      `smtpd`, no `qmqpd` and no submission listener.
    - `pickup` and bounce copies are ineligible.

### 9.7 Edge values relied on, the Ops lab, and questions

**Relied on, current values, which Ops treats as pinned:**

| Value | Current setting |
|---|---|
| relay concurrency | 20 |
| backoff | 120 / 600 s |
| `queue_run_delay` | 120 s |
| lifetime | 4 h |
| `bounce_queue_lifetime` | 0 |
| recipient limits | 50 / 50 |
| `smtp_send_xforward_command` | yes |
| `smtpd_authorized_xforward_hosts` | empty |
| `master.cf` | only `0.0.0.0:25 smtpd` |

- **Changes needed:**
  - `enable_long_queue_ids = yes` (C1; RL-7);
  - advertising `PROTO` from ingestion (an app change).
- **Edge changes this proposal needs but does not assume** (each needs Ops'
  measured, reviewed case):
  - a bounded D_max (`smtpd_per_request_deadline`, `smtpd_min_data_rate`),
    only if lab item 5 shows a start-of-transaction time;
  - restricting `authorized_submit_users`, only if lab item 6 fails.
- **NTS** is recommended to Ops.

**Ops lab items** (Postfix 3.8.6 mirror; results labelled lab):

1. Is the arrival time encoded in long queue IDs, stable across defer,
   reload, restart, hold or release, and `postsuper -r`?
2. Which XFORWARD attributes does 3.8.6 send, and is IDENT identical on a
   retry?
3. The edge-added `Received:` timestamp: its source, stability, and whether
   it can be told apart from sender headers.
4. Does `postqueue -j` `arrival_time` survive restart and requeue? The same
   run measures the reload effect on retry times.
5. With a trickled `DATA`, does each carrier's time mark the start of the
   transaction or the end of `DATA`? What is the edge's enforced maximum
   transaction time today?
6. `XFORWARD PROTO` and `SOURCE` for four origins: Internet `smtpd`, loopback
   `smtpd`, `sendmail`/`pickup` (including `-bs`), and bounces and notices.

**Questions** (answered on the live edge, 6687028563, and folded in above):

- **Q-R9-5, clock.** `systemd-timesyncd` against `ntp.ubuntu.com`, stratum 2,
  offset +126 µs, one sample.
  - **What the design needs:** the independent canary (INV-R9-6(i)), at most
    15 min old.
  - **Ops' gauge** is welcome as an early warning, at most 10 min old, but is
    not sufficient on its own.
  - **NTS** is recommended.
- **Q-R9-6, first-attempt latency.** Under 1 s p99 (n = 56). ε_late = 30 s is
  a margin over a small sample, and a longer log or metric history should
  confirm it.
- **Q-R9-7, injection.**
  - `pickup` is open to every local account, and expiry bounces can relay.
    Both are ineligible (§9.2, INV-R9-9).
  - `postmaster@` is not re-injected.
  - `postsuper -r`, spool restore and the operator release are lab item 1.
  - Only `0.0.0.0:25 smtpd` listens.

### 9.8 What it changes, and the owner items

**What changes in the agreed design:**

- **§2.1:** IDENT (and, under lab item 6, `PROTO`) yields t_a and
  eligibility, only for a lab-qualified carrier.
- **The `INGEST` limiter** gains the overflow ledger, credit claims and the
  per-IDENT marker, all under the existing row lock.
- **Invariant updates:** I-1 holds; I-2 is restated; I-23 counts late demand;
  catch-up becomes "allowance taken **or** λ > ε_late".
- **Unchanged:** T1, G-D/G-C, R-REC and the carved bucket.
- **RL-9 shrinks to:**
  - bunching within ε_late, at most r·ε_late per workspace per episode;
  - identity-less and ineligible-origin mail;
  - periods when skew disables credit;
  - under C3, edge-side holds before first sight;
  - while D_max is unbounded (lab item 5), all edge-side holds.

**Owner items** (for the decision packet; none accepted):

1. **RQ-1, shape within a hold.**
   - Credit admits at most b + r·H over a hold, but does not police the
     arrival-time shape within it.
   - Accept that, or require a per-arrival ledger (option (a)), which is
     exact but heavier and needs its own review.
2. **The cost of the marker.** One durable write per identified event, kept
   for L + δ. That is up to about 476 k rows across the cohort, in line with
   the allowance table.
3. **Under C3**, or while D_max is unbounded, edge-side holds before first
   sight stay in RL-9.
4. **Edge changes that would widen the correction.** Each needs Ops' measured
   case: bounding D_max, and restricting `authorized_submit_users`.
5. **RQ-2.** C1's reliance on Postfix's internal queue-ID encoding, under the
   conditions in §9.2.
