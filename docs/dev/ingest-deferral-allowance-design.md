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

The identity is Postfix `XFORWARD IDENT`, the edge's queue ID.

**The live edge sends no XFORWARD today** (#124 [6688743690](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688743690)):

- `smtp_send_xforward_command = no` and `enable_long_queue_ids = no`, with no
  `master.cf` override on `relay`, so queue IDs are short;
- today ingestion sees only the edge's WireGuard address as the client.

So **this design, and EP-8 (4), cannot run on today's edge**. They need EP-0
and EP-1 (§9.9), which are required behaviour changes, together with
ingestion's `xforward-peers` allow-list.

These properties were verified on `testinbox-mail-edge:rehearsal` (Postfix
3.8.6) with XFORWARD enabled:

- It is sent before every `MAIL`, including on a reused connection.
- It is stable across `451` retries and across `postfix stop/start`.
- Two byte-identical sends get different IDENTs (ADR-019).
- The edge sends only the attributes ingestion advertises.
  - **Ingestion advertises `IDENT`, `PROTO` and `SOURCE`**, and none of
    them is personal data.
  - `PROTO` and `SOURCE` are used only for credit eligibility and for EP-8
    (4). They are never stored or logged.
  - `NAME`, `ADDR`, `HELO` and `PORT` stay unadvertised, so no client IP or
    HELO ever reaches ingestion.
  - **Why `SOURCE` is advertised:** in the lab, local origins sent *only*
    `SOURCE=LOCAL`. Without it, a genuine bounce could arrive with no
    XFORWARD at all and look like an EP-0 regression.
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
  postqueue -f`) and requeues (`postsuper -r`). Each use adds at most one
  presentation to every held or deferred message. Today these uses appear
  only as journal lines; EP-6 would log them.
- **Edge clock steps.** A forward step of the edge clock makes every due
  deferred message retry at once, which adds attempts to K just as operator
  flushes do. A canary offset alarm (EP-3, recommended) is therefore an alarm for
  this privacy model.
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
  - `NOTIFY=SUCCESS,DELAY` producing no notice;
  - the expiry-bounce path into the relay domain closed (EP-8, §9.9).
- **Reload and restart, measured in the lab** (6688294528). Reconcile runs
  `systemctl reload postfix` on a config change (falling back to restart),
  checking at most every 2 min.
  - **Stored retry times are never advanced.** Every attempt falls within
    [stored − 1 s, stored + `queue_run_delay`), the same window as a
    periodic scan. So the scan-phase reset adds no attempts.
  - **The in-flight exception.** If a reload hits a delivery that is in
    flight, that attempt's backoff is lost. The next attempt comes at the
    first mail arrival after reload + 60 s, otherwise at reload + 300 s. So
    **any sender** can time that extra attempt anywhere within
    [reload + 60 s, reload + 300 s]. It counts once in N_rr, and the bound
    is unchanged, but the instant is chosen by the sender, not the edge. A
    restart during an in-flight delivery retries at once.
  - **Model:** each reload or restart adds at most one attempt to each
    message in flight at that instant.
- **Requeue** (`postsuper -r`) gives a new queue ID but keeps the old IDENT
  and `arrival_time`. It triggers an immediate attempt and resets the
  backoff, so it adds an attempt to K, just as a flush does.
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
- **The attempt count, on the measured model:**

  > K ≤ K_sched + N_rr + N_op + N_step

  - K_sched is the schedule's own attempts in L = 4 h. An attempt can come
    1 s before its stored time, so the spacing is ≥ 119 s and
    K_sched ≤ 1 + ⌊14 400/119⌋ = **122**. On the observed age-based backoff
    (120, 240, 480, then 600 s), it is **≈ 26**.
  - N_rr is the reloads and restarts in the message's lifetime that hit it
    while it was in flight. Each adds at most one. Worst case, it is every
    reload or restart in the window.
  - N_op is operator flushes and requeues.
  - N_step is forward steps of the edge clock.
- **Bits = log₂(K + 1).** Let N = N_rr + N_op + N_step.

  | N | Hard (K_sched = 122) | Typical (K_sched ≈ 26) |
  |---|---|---|
  | 0 | log₂ 123 ≈ **6.94** | log₂ 27 ≈ **4.75** |
  | 1 | log₂ 124 ≈ 6.95 | log₂ 28 ≈ 4.81 |
  | **3 (the observed worst 4 h window)** | log₂ 126 ≈ **6.98** | log₂ 30 ≈ 4.91 |
  | 10 | log₂ 133 ≈ 7.06 | log₂ 37 ≈ 5.21 |
  | 30 | log₂ 153 ≈ 7.26 | log₂ 57 ≈ 5.83 |
  | 120 (reconcile at its maximum, every 2 min for 4 h) | log₂ 243 ≈ **7.92** | log₂ 147 ≈ 7.20 |

- **The parameter, observed** on edge-fr-01's maillog, 2026-10-07 to now
  (#124 [6688743690](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688743690)).
  - **Counts:** 8 reloads and 5 starts.
  - **Since public launch (2026-10-28 18:07Z):** only the weekly
    maillog-rotation reload (Sundays at about 00:05).
  - **Steady state:** about 1 a week, plus 1 per reviewed config MR, plus 1
    per cert renewal (every 60–90 days).
  - **The busiest 4 h window had 3**, all during launch config changes: Oct
    28 at 15:08, 18:00 and 18:07.
  - **Unexplained:** two starts (Oct 15 and Oct 19) have unverified causes.
  - **What N_rr counts.** Only reloads or restarts that hit a message *in
    flight*, and a pure retry is in flight for milliseconds. So N_rr is
    realistically 0 per message, and at most the number of reloads within
    its 4 h lifetime: **N_rr ≤ 3** on the observed worst window.
  - **N_op and N_step since launch.** Ops' records list no operator flush or
    clock step since launch (the Oct 27 edge-stop drills were before it).
    This is **unverified**: no source states it explicitly, so EP-6 and EP-3,
    or journal counts, must report them.
  - The expected count is far smaller. A reload adds an attempt only to a
    message in flight at that instant, and a probe's attempts last seconds
    while their spacing is minutes.
  - The bound must be worst-case all the same.
- **Every term of K is reported, not assumed.**
  - N_rr comes from the reconcile and systemd logs (n₃₀).
  - N_op comes from EP-6, or from the `postsuper`/`postqueue` journal.
  - N_step comes from EP-3, or from the `timesyncd` step journal.
  - EP-3 and EP-6 are **required for this reporting** unless Ops provides
    equivalent journal counts. They are not credit gates (§9.9).
- **For the owner.**
  - **≈ 6.98 bits (K ≤ 125)** is the figure at the observed worst reload
    density: 3 in 4 h, during launch config changes, with N_op and N_step
    still unverified. **No control enforces it.**
  - The only hard cap is reconcile's 2-minute cycle: 120 reloads per 4 h,
    **≈ 7.92 bits**.
  - The typical figure is ≈ 4.75 bits at N_rr = 0, ≈ 4.81 at 1, and ≈ 4.91
    at 3 (K ≈ 29, log₂ 30).
  - **Rolling out this design itself adds reloads:** EP-0, EP-1, #147 and
    EP-8.
  - **Making 6.98 a bound rather than an observation** would need Ops to
    batch edge config changes to ≤ 3 reloads per 4 h. Otherwise N_rr is
    reported per 4 h window, alongside N_op and N_step.
  - **Ops' position** (#124 [6688849188](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688849188)):
    - **A batching operating rule,** once the owner adopts the figure: at
      most 3 edge Postfix reloads or restarts in any rolling 4 h window,
      counting the weekly rotation reload. Edge config MRs are grouped into
      one merge per window, and any exception is recorded.
    - **Not a hard bound.** Security fixes and incident restarts take
      precedence, and nothing enforces the rule mechanically.
    - **Reporting:** N_rr per rolling 4 h window, as an edge metric taken
      from the maillog's `postfix/master … reload|daemon started` lines.
    - A mechanical cap (reconcile refusing a reload when the window is full)
      is possible, but Ops does not recommend it, because it would delay
      security fixes.
  - **The owner's figure is therefore** ≈ 6.98 bits under policy, with the
    measured N_rr shown, and ≈ 7.92 bits as the hard worst case (reconcile's
    2-minute cycle).
  - **This is the owner's call under the I9 exception's escalation
    clause.**
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
> - operator flushes and requeues counted as extra attempts;
> - reloads and restarts counted as at most one extra attempt per message in
>   flight at that instant (lab-measured; stored retry times are never
>   advanced);
> - S-C2 enforced;
> - the enforced concurrency limits (edge relay concurrency 20; 16 write
>   slots per node, 4 per workspace; global T1 scheduling).
>
> Under that model, one paid probe reveals at most log₂(K+1) bits, where
> K ≤ 122 + N_rr + N_op + N_step within the message's lifetime: N_rr counts
> reloads or restarts that hit it in flight, N_op operator flushes or
> requeues, and N_step edge clock steps. On the observed schedule that is
> about 4.8 bits (K ≈ 26). The hard figure is about 6.94 bits at N = 0. It
> rises to about 6.98 bits at the observed worst-case reload density
> (N_rr ≤ 3 in a 4 h window, #124 6688743690), and would reach about 7.9 bits
> only at 120 per 4 h. That figure is the owner's call under the escalation
> clause. Probe events stay
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

- `smtp_send_xforward_command: "yes"`. The live value is **`no`** (#124 [6688743690](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688743690)),
  so this is EP-0, a **required behaviour change** toward ingestion only;
- `enable_long_queue_ids: "yes"`. The live value is **`no`** (#124 [6688743690](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688743690)), so this
  is EP-1, a **required behaviour change**;
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
| `smtp_send_xforward_command`, `enable_long_queue_ids` | **`no`, `no`**; no `relay` override (#124 [6688743690](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688743690)) | **not pinned**: EP-0 and EP-1 are required changes | identity (the whole allowance design), EP-8 (4) |
| Operator flush (`edge-stop.sh`) | after an operator hold only | Ops procedure. **Not logged today**, only journal lines (EP-6) | an extra presentation (K, catch-up burst) |
| Reconcile reload or restart | 2026-10-07 to now: 8 reloads and 5 starts. Since launch, only the weekly rotation reload (#124 [6688743690](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688743690)) | lab-measured effect: stored times never advanced; at most one extra attempt per message in flight (§4) | K (N_rr) |
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
- the hold inequality does not hold;
- `testinbox_smtp_transactions_total{identity="edge_without_xforward"}` is
  non-zero over the activation window;
- EP-0 and EP-1 are not yet verified live.

The activation barrier blocks on the last two as well.

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
    - **What is advertised.** Ingestion advertises `IDENT`, `PROTO` and
      `SOURCE`, and none of them is personal data. `NAME`, `ADDR`, `HELO`
      and `PORT` stay unadvertised, so they never reach ingestion.
    - **What is used.** `PROTO` and `SOURCE` are used only for credit
      eligibility and EP-8 (4). They are never stored or logged.
    - **Logging.** IDENT may appear in ingestion logs as metadata only,
      under the existing retention. It is never logged together with
      recipient addresses for unknown recipients (ADR-025).
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
    - Every XFORWARD attribute (IDENT, and `PROTO` under §9) is reset per
      transaction. It is cleared on `RSET`, `HELO`/`EHLO` and the end of
      `DATA`.
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
    - A transaction from an `xforward-peers` edge without XFORWARD is
      counted as `identity="edge_without_xforward"` and raises an alert
      (EP-0).
      - **`edge_without_xforward`** means no XFORWARD command at all in the
        transaction.
      - A transaction carrying only `SOURCE=LOCAL` has XFORWARD present,
        from a local origin.
    - **Activation barrier.** `ALL` is refused, and the activation barrier
      blocks, while either of these holds:
      - `testinbox_smtp_transactions_total{identity="edge_without_xforward"}`
        is non-zero over the activation window;
      - EP-0 and EP-1 are not yet verified live.
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
    - `ALL` is refused, and the activation barrier blocks, in two cases:
      - one `edge_without_xforward` transaction in the activation window;
      - EP-0 or EP-1 not verified live.
    - A transaction carrying only `SOURCE=LOCAL` does not count as
      `edge_without_xforward`.

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
      `postfix reload`, `restart`, an operator flush and `postsuper -r`.
    - Asserts the measured model: stored retry times are never advanced, and
      each reload or restart adds at most one attempt per message in flight.
      This is already shown in the lab (6688294528).
    - Reports n₃₀ for §4's K.
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
   - *Under deployed `ALL`:* only declared single-host `trusted-clients`
     (I-20) connect without identity, and the edge is allow-listed (§6).
   - *Until EP-0 is in force,* however, every edge transaction carries no
     XFORWARD (`identity="edge_without_xforward"`) and is charged as today.
     The whole allowance design is therefore inactive for edge traffic.
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
  - about 4.8 bits on the observed schedule;
  - at most log₂(123 + N), where N counts reloads and restarts hitting the
    message in flight, plus operator flushes, requeues and clock steps
    (§4);
  - **≈ 6.98 bits is the figure at the observed worst reload density** (3
    in 4 h, during launch config changes, #124 [6688743690](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688743690)).
    - **No control enforces it.** The only hard cap is reconcile's 2-minute
      cycle, ≈ 7.92 bits.
    - Rolling out this design (EP-0, EP-1, #147, EP-8) itself adds reloads.
    - For 6.98 to be a bound, Ops would batch edge config changes to ≤ 3
      reloads per 4 h. Otherwise N_rr is reported per 4 h window, alongside
      N_op and N_step, which are still unverified.
    - **Ops** (#124 [6688849188](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688849188)) commits to that batching as an operating rule once the
      owner adopts the figure: ≤ 3 reloads or restarts per rolling 4 h,
      counting the weekly rotation, with config MRs grouped and exceptions
      recorded. It is not a hard bound, since security fixes and incident
      restarts take precedence. Ops also reports N_rr per rolling 4 h window
      from the maillog. It does not recommend a mechanical cap.
    - **For the owner's decision under the I9 escalation clause:** ≈ 6.98
      bits under policy, with the measured N_rr shown; ≈ 7.92 bits as the
      hard worst case.
- It depends on Ops' live verifications:
  - ETRN refused;
  - no delivery-status notice of any class;
  - N_op and N_step since launch, which are still unverified.
- **XFORWARD and long queue IDs are off on the live edge**, so EP-0 and
  EP-1 are required before any of this can run.

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

## 9. RL-9 correction (PROPOSED: revised after the lab and Ops' live answers; both reviewers AGREE subject to these edits (applied); not accepted, not implemented; lab item 6 re-run pending)

**Review status (2026-11-06).** Both reviewers agreed the invariants at
`32539dd`. That agreement covered three carriers. Ops' lab then measured them
(#124 comment
[6688294528](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688294528)):
neither edge carrier is usable, so **the correction reduces to C3**, the
time ingestion first saw the identity. That is a material change.

- Both reviewers re-checked `7202c85` and returned AGREE WITH CHANGES.
  Rejecting C1/C2, adopting C3, the requeue handling and the K model are
  confirmed.
- Both reviewers then checked `e7a4a46` and returned AGREE WITH CHANGES.
  Those edits are applied in this commit. They cover the advertised XFORWARD
  set, the framing of the 6.98-bit figure, the IDENT logging rule, RL-4 and
  the activation barrier.
- The lab item 6 re-run with the advertised set {IDENT, PROTO, SOURCE} is
  pending (#124 6688831352). It is queued after the A′ checkpoint run (#124
  6688849188).
- No agreement is owner acceptance of anything, and nothing here activates
  anything.

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
**Live edge (#124 [6688743690](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688743690)):** `smtp_send_xforward_command = no` and
`enable_long_queue_ids = no`, with no `relay` override. The edge sends **no
XFORWARD** today, and ingestion sees only its WireGuard address as the
client.

- EP-0 and EP-1 (§9.9) are therefore **required behaviour changes**, with
  ingestion's `xforward-peers` allow-list.
- Neither C3, the allowance design nor EP-8 (4) can run on today's edge.

### 9.1 The problem

`INGEST` is charged when a copy reaches ingestion. While the edge holds mail,
the bucket is not charged, so it refills to b and the cap discards the rest
of the refill. When the held mail is released, it arrives together and finds
at most b tokens.

**What is lost.** A workspace sending within its envelope at rate r through
a hold of H seconds loses about r·H − b copies, all behind a `250`. For
example, a 10 min hold loses about 540 of 600 copies.

**What holds the mail:**

- (h1) pre-resolution `451`s (breaker, latch, activation, footprint,
  ambiguity-exhausted slots), which charge nothing;
- (h2) ingestion unreachable, or the destination marked dead;
- (h3) an operator hold, then the `edge-stop.sh` flush;
- (h4) catch-up work occupying the 20 relay sessions;
- (h5) the edge's own queueing.

**Not affected:** mail deferred *after* charging. It already holds an
allowance.

**Genuinely over-allowance traffic** exceeds (b, r) at the time it first
arrived. This section can attest only one such time: ingestion's own first
sight of a copy (§9.2).

### 9.2 Arrival-time carriers: measured; only C3 is usable

**Measured and rejected: the edge carriers C1 and C2.** These are the lab
results (Postfix 3.8.6 lab mirror, not the live edge, 6688294528, items 1, 3,
4 and 5):

| Carrier | What it marks | Stability |
|---|---|---|
| C1, the long queue ID's time field | the **first valid `RCPT`**, when the queue file opens under `smtpd_delay_open_until_valid_rcpt = yes` | stable across defer, reload, restart and hold or release. A requeue gives a **new ID and time** |
| C2, the top edge `Received:` date | **`MAIL FROM`** | unchanged on retry. Told apart from sender headers only by position. A requeue prepends another header (pickup's), so it is no longer first |
| `postqueue -j` `arrival_time` | **`MAIL FROM`** | kept through restart and requeue. Never sent to ingestion |

- **No carrier marks the end of `DATA`, and no end-of-`DATA` signal reaches
  ingestion.** The only end-of-`DATA` traces are the `cleanup` and `qmgr`
  log lines and the first relay attempt itself.
- **The trickle test:** with the live settings (no per-request deadline), a
  client sending 1 byte every 90 s held one `DATA` open for **270 s** and was
  accepted. No total transaction limit was observed within the lab's 5 min
  cap.

So an edge time can precede the true end of the message by the edge's whole
transaction time, D_max, counted from `MAIL FROM`. Credit would then have to
start D_max after it. With the deadline on and CHUNKING off, using the
symbols below:

> D_max = (R + O + J + E)·(T + T_srv) + E·s + 2T + M/ρ

The `DATA` command line and the `DATA` content are two separate requests,
hence 2T.

| Symbol | Meaning | Value |
|---|---|---|
| T | `smtpd_timeout` | 120 s |
| M | `message_size_limit` | 15 728 640 |
| ρ | `smtpd_min_data_rate` | 65 536 with EP-4; 500 today |
| R | `smtpd_recipient_limit` | 50 |
| O | `smtpd_recipient_overshoot_limit` | 1 000 |
| J | `smtpd_junk_command_limit` | 100 |
| E | `smtpd_hard_error_limit` | 20 |
| s | `smtpd_error_sleep_time` | 1 s |
| T_srv | per-command processing | ≈ 0 |

How large D_max is at each setting:

| Setting | D_max |
|---|---|
| Today: deadline off | **unbounded**: any command line can be trickled |
| Defaults, with EP-4 | 1 170·120 + 20·1 + 2·120 + 15 728 640/65 536 = 140 400 + 20 + 240 + 240 = **140 900 s ≈ 39 h**, beyond the 4 h lifetime |
| Tightest plausible pins (R = 50, O = 0, J = 0, E = 1, T = 120 s, ρ = 64 KiB/s) | 51·120 + 1 + 240 + 240 = **6 601 s ≈ 1.8 h** |
| Even with T = 30 s, far below RFC 5321's 5 min | 51·30 + 1 + 60 + 240 = **1 831 s** |

Credit cannot cover the first D_max + ε_late of a hold, so the per-episode
loss stays ≤ r·(D_max + ε_late): about 6 630 copies at the tightest
plausible pins. That is more than any hold this section is meant to fix.
Those pins would also refuse legitimate clients: any overshooting `RCPT`, a
`NOOP` or a single error would end the session.

**Neither edge carrier gives usable credit**, unless the owner accepts much
tighter recipient, overshoot, junk and error limits, and even then D_max is
hours. C1 and C2 are therefore **rejected on the measurement**. They are kept
here with their evidence.

**C3, adopted: first sight at ingestion.**

- **What t_fs is.** When a presentation of an (edge, IDENT) ends in a
  pre-resolution `451`, ingestion durably records t_fs = DB `now()`. It does
  this only then, and only for the first such presentation (INV-R9-1).
- **Why it can't be pre-dated.** By then the whole message has been received,
  and t_fs is taken from the database clock. No sender trickle and no edge
  clock enters it.
- **Requeue.** A requeued copy keeps the **old** IDENT (lab item 2), so it
  matches the same first-sight row.
- **Failure.** A failed first-sight write gives no credit.
- **What it covers:** (h1), pre-resolution `451`s (by far the most common
  hold where ingestion is up), and any later re-presentation of a copy first
  seen in (h1). A copy that reached charging has a marker or an allowance
  instead.
- **What it does not cover:** holds at the edge before first sight, (h2) to
  (h5). These stay in RL-9.

**C4, if IDENT itself is unstable.** No correction is possible. The lab found
IDENT stable on retries and kept across a requeue (item 2), so C4 does not
apply.

**Eligible origin (lab item 6: measured in the lab; a re-run with the real
advertised set is pending).**

- **What the lab found.** The lab sink advertised every attribute.
  - Mail the edge's `smtpd` received always carried `ADDR`, `PORT`, `PROTO`,
    `HELO` and `IDENT`, with `SOURCE=REMOTE` under the edge's empty
    `local_header_rewrite_clients`.
  - Every other origin carried **only** `SOURCE=LOCAL`, with no `PROTO`:
    `pickup`, `sendmail -bs` (which goes through postdrop and pickup), expiry
    bounces and delay notices.
- **The re-run.** Ingestion advertises exactly {IDENT, PROTO, SOURCE}, so
  `ADDR` and `HELO` never arrive. A re-run with that advertised set,
  recording each origin, is requested (#124 [6688831352](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688831352)) and **pending**. The
  eligibility rule and EP-8 (4) depend on its result.
- **The rule:** only copies whose `PROTO` is in {SMTP, ESMTP, ESMTPS, ESMTPA,
  ESMTPSA} are eligible for credit. An absent `PROTO` means ineligible.
  - Ingestion advertises `IDENT`, `PROTO` and `SOURCE` (I-10). `PROTO` and
    `SOURCE` are never stored or logged.
  - `NAME`, `ADDR`, `HELO` and `PORT` stay unadvertised.
- **What `PROTO` cannot do:** it cannot tell a bounce from a delay notice
  from a local injection. Neither this rule nor EP-8 (4) needs it to.
- **No forgery:** a client's own `XFORWARD` to the edge is refused with
  `550`.

### 9.3 Candidates

| | Distinguishes over-allowance from bunched mail | Forgeable or gameable | Effect on T1 | I9 | Verdict |
|---|---|---|---|---|---|
| **(a)** a rate limiter run at virtual arrival time | exactly, given a trusted time | no edge time is usable (§9.2) | bunched admits arrive at once | none new | **no** |
| **(b)** credit inside Ops-declared windows | only inside declared windows | sender-shaped inside a window | as (d) | as (d) | **no** |
| **(c)** charge at the first presentation, even on a pre-resolution `451` | (h1) only | no | none | changes ADR-025's pre-check ordering | **no** |
| **(d)** overflow credit since first sight (C3) | for (h1) and re-presented copies | not forgeable; credit is only allowance the workspace did not use | credited events are scheduled as catch-up | lateness-based class | **recommended** |

### 9.4 How (d) works with C3

**Credit is the refill the cap threw away.**

- For each `INGEST` bucket (per inbox and per workspace), refill that the cap
  discards is recorded as **overflow**, in 60 s slots, on the database clock.
- It is written in the refilling transaction, after the bucket's
  `FOR UPDATE`. Slots older than L + δ are dropped.
- **Lateness:** λ = DB `now()` − t_fs.

**A late copy can claim one unit, once.** All of these must hold:

- it has an eligible origin;
- λ > ε_late;
- it finds no whole token;
- **no earlier presentation of its IDENT reached the `INGEST` charging step**
  (the marker).

It then claims one unclaimed overflow unit from a slot starting at ≥ t_fs.
The claim is narrow-first, under the bucket's `FOR UPDATE` (I-13). A copy
that cannot claim is discarded, as today.

**The marker** closes the co-address exploit.

- **What it is:** a durable per-(edge, IDENT) record, written in the charge
  transaction for charged and limiter-discarded copies alike.
- **Pre-resolution `451`s** write the first-sight record, but no marker.
- **Requeue:** a requeued copy keeps the old IDENT, so the marker still
  matches it and blocks re-credit. Its allowance, which is also keyed by
  IDENT, still redeems.
- **Retention:** it expires at L + δ.

**Guarantees.**

- For every bucket and every s ≤ t, the spends and claims made during [s, t]
  by copies with t_fs ≥ s are ≤ tokens(s) + r·(t − s).
- Over a hold that began after first sight, admissions are ≤ b + r·H.
- Over shorter windows, admissions can exceed b + r·T by unclaimed overflow
  (≤ r·(L + δ)).
- Shape within a hold is not policed (RQ-1).

**Scheduling.** An event with λ > ε_late is catch-up in the carved bucket,
and keeps its allowances when deferred. Late events without an allowance
count as catch-up demand in I-23. Fresh events never use credit, so I-21,
I-18 and G-D/G-C are unchanged.

**I9.** The class depends on lateness only, never on a workspace's credit.
Admission and discard stay behind the uniform `250`.

### 9.5 Proposed invariants

1. **INV-R9-1 (trusted time: C3 only).**
   - **When it is written.** The first-sight record is written **only** when
     a presentation ends in a pre-resolution `451`, from an `xforward-peers`
     edge with a long-ID IDENT, in its own short transaction. t_fs is DB
     `now()` at that first such presentation.
   - **Failure.** A failed write never changes the reply (I-9); the copy
     then simply gets no credit.
   - **Content.** It holds only (edge, IDENT, t_fs): no recipient, no
     content.
   - **Never on `250`.** It is never written for a transaction answered
     `250`.
   - **Charging writes the marker instead.** A presentation that reaches
     charging writes the marker, which already excludes credit.
   - **Access and retention.** Only the ingestion role is granted, and
     `scope.txt` marks it `-`. `REQUIRED_DENY` lists it, and it is reaped at
     L + δ.
   - **ADR-025.** This keeps "an unknown recipient leaves nothing". During a
     pre-resolution-`451` window the row is written before resolution, so it
     says nothing about any recipient.
   - No edge time is ever used: not the queue-ID time, not a `Received:`
     date, not `arrival_time`. That is C1/C2, measured and rejected.
2. **INV-R9-2 (credit source).** Credit is only overflow, recorded in the
   refilling transaction. It is never more than recorded, expires at L + δ,
   and is never returned (I-1).
3. **INV-R9-3 (eligibility).**
   - **When:** λ > ε_late, with no whole token.
   - **Origin:** `PROTO` must be in the SMTP family.
   - **Marker:** no earlier presentation of the IDENT reached charging. The
     marker is written for charged and limiter-discarded copies alike, and
     pre-resolution `451`s write none.
   - **Slot:** the claimed slot starts at ≥ t_fs.
   - **Claim:** narrow-first and once per unit. Every ledger access happens
     after `FOR UPDATE`.
4. **INV-R9-4 (envelope).**
   - The arrival-indexed bound holds on t_fs.
   - Over a hold that began after first sight, admissions are ≤ b + r·H.
   - Over shorter windows, admissions exceed b + r·T by at most the unclaimed
     overflow (≤ r·(L + δ)).
   - Shape within a hold is not policed.
5. **INV-R9-5 (class).**
   - λ > ε_late means catch-up.
   - Late events without an allowance count in I-23.
   - Fresh events never use credit.
6. **INV-R9-6 (clock).**
   - Credit uses only the database clock: t_fs, `now()` and the slots.
   - No edge clock enters credit, so the canary is **not** a credit gate.
     It stays recommended as a §4 privacy-model alarm (EP-3).
7. **INV-R9-7 (restart).**
   - First-sight records, markers, overflow and claims are durable.
   - IDENT survives edge restarts and requeues (lab).
   - A restored database has none of them (I-16).
8. **INV-R9-8 (labels).** Closed enums only.
9. **INV-R9-9 (injection).**
   - Only `smtpd` on port 25 and `pickup` can inject. Today `pickup` is open
     to every local account (EP-5).
   - `pickup`, bounce and notice origins are ineligible, through
     `PROTO`-absent.
   - Operator actions are **not** logged today. C3 does not need them to be:
     a requeue keeps the IDENT, so it changes neither the first-sight record
     nor the marker.

**I-2, restated.** For every `INGEST` bucket: tokens spent + credits claimed
= copies whose T1 committed + allowances outstanding + allowances forfeited +
allowances expired + copies discarded by the limiter after a spend.

**I-23** counts late events without an allowance as catch-up demand.

**Values:**

- **ε_late = 30 s.** First-attempt relays take under 1 s end to end (Ops:
  n = 56 over the full retention from 2026-09-29, `d` p99 0.79 s). The sample
  is small, so 30 s is a margin, not a measurement.
- **Slot = 60 s.**
- **ε_skew and D_max** no longer apply to credit.

### 9.6 Proposed acceptance tests

1. **AT-R9-1 first sight.**
   - A pre-resolution `451` writes t_fs and no marker.
   - A retry with the same IDENT matches it.
   - A failed write gives no credit.
   - After a `postsuper -r` on the edge, the copy still matches: old IDENT,
     same record.
   - A presentation that reaches charging writes a marker and no first-sight
     record.
   - An event whose recipients are all unknown writes nothing outside a
     pre-resolution-`451` window.
   - A failed first-sight write leaves the `451` byte-identical.
2. **AT-R9-2 pre-resolution hold.** A breaker or latch `451` storm runs for
   10 min while a workspace sends at r.
   - On recovery all 600 copies are stored.
   - At 2r, exactly b + r·H are admitted.
3. **AT-R9-3 uncovered holds.** Ingestion unreachable, an operator hold and
   catch-up coupling still lose up to r·H − b per workspace. These are the
   documented residual, asserted as such.
4. **AT-R9-4 over-allowance.** Discarded exactly as today, whether the mail
   is on time or late.
5. **AT-R9-5 no credit.** None of these gets credit:
   - a non-peer;
   - a short ID or `[UNAVAILABLE]`;
   - an on-time copy;
   - an absent `PROTO` (`pickup`, `sendmail -bs`, a bounce, a delay notice);
   - no first-sight record.
6. **AT-R9-6 concurrency.** N concurrent late copies against k units produce
   exactly k claims, with no `40P01`.
7. **AT-R9-7 fresh unaffected.** AT-21 is rerun with late arrivals: fresh is
   never refused, and I-18 holds.
8. **AT-R9-8 no edge time.**
   - No code path decodes a queue-ID time, parses `Received:` or reads
     `arrival_time` for credit.
   - Shifting the edge clock changes no credit decision.
9. **AT-R9-9 I9.** An event's class is identical whether W pays with tokens or
   with credit.
10. **AT-R9-10 co-address exploit.** A copy discarded at one presentation and
    late at the next claims nothing. The same holds across a requeue, because
    the IDENT is unchanged.
11. **AT-R9-11 old overflow.** Overflow recorded before t_fs cannot be
    claimed.
12. **AT-R9-12 envelope property.** A property test of INV-R9-4 on t_fs,
    including slot boundaries.
13. **AT-R9-13 injection.**
    - The `master.cf` service list is only port-25 `smtpd`.
    - `pickup`, `-bs`, bounce and notice copies are ineligible.
14. **AT-R9-14 requeue.**
    - A requeued copy (new queue ID, old IDENT) redeems its allowance once.
    - The marker blocks any credit for it.

### 9.7 Edge values, the lab, and questions

**Relied on.** Current values, which Ops treats as pinned (EP-2):

| Value | Setting |
|---|---|
| relay concurrency | 20 |
| backoff | 120 / 600 s |
| `queue_run_delay` | 120 s |
| lifetime | 4 h |
| `bounce_queue_lifetime` | 0 |
| recipient limits | 50 / 50 |
| `smtpd_authorized_xforward_hosts` | empty |
| `local_header_rewrite_clients` | empty, so `SOURCE=REMOTE` for `smtpd` mail |
| `master.cf` | only `0.0.0.0:25 smtpd` |

- **Observed off:** `smtp_send_xforward_command = no` and
  `enable_long_queue_ids = no` (#124 [6688743690](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688743690)). They are not pinned, and EP-0 and EP-1
  change them.
- **Not applied anywhere:** migrations V24–V27, on production and on staging
  (both at max 23; #124 [6688743690](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688743690)).

**Ops lab results** (lab mirror, 6688294528):

- **Item 1:** C1 marks the first valid `RCPT`. A requeue gives a new ID.
- **Item 2:** IDENT is stable on retry and kept across a requeue, and
  XFORWARD carries no timestamp.
- **Item 3:** C2 marks `MAIL FROM`, and is distinguishable only by position.
- **Item 4:** `arrival_time` is kept through restart and requeue.
- **Item 5:** nothing marks the end of `DATA`, and a trickled `DATA` stayed
  open 270 s.
- **Item 6:** measured with the sink advertising every attribute.
  Non-`smtpd` origins sent only `SOURCE=LOCAL`. A re-run with ingestion's
  real advertised set {IDENT, PROTO, SOURCE} is **pending** (#124 [6688831352](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688831352)).
- **Reload and restart:** §4.

**Questions still open:**

- the causes of the Oct 15 and Oct 19 starts;
- N_op and N_step since launch (§4).

### 9.8 What it changes, and the owner items

**What changes in the agreed design:**

- **§2.1:** ingestion advertises `IDENT`, `PROTO` and `SOURCE`.
- **The `INGEST` limiter** gains:
  - the first-sight record;
  - the overflow ledger and credit claims;
  - the marker.

  All of them sit under the existing row lock.
- **Catch-up** becomes "allowance taken **or** λ > ε_late".
- **Unchanged:** T1, G-D/G-C, R-REC and the carved bucket.
- **RL-9 shrinks to:**
  - holds before first sight: ingestion unreachable or the destination dead,
    operator holds, catch-up coupling, and edge queueing;
  - bunching within ε_late;
  - identity-less and ineligible-origin mail;
  - first-sight write failures.
- **Pre-existing, not changed:** the command-phase slowloris. The edge runs up
  to 100 `smtpd` processes, 20 per IP, at 120 s per command, with the
  deadline off.

**Owner items** (none accepted):

1. **RQ-1.** Shape within a hold is not policed. Accept that, or require a
   per-arrival ledger, option (a), which needs a trusted time that does not
   exist (§9.2).
2. **The marker and first-sight cost.**
   - **Markers:** one durable write per identified event that reaches
     charging, up to about 476 k rows across the cohort over L + δ.
   - **First-sight rows:** at most the new identified transactions that
     arrive during pre-resolution-`451` windows within L + δ. They are
     reaped at L + δ.
3. **The remaining RL-9.** Holds at the edge before first sight are not
   corrected:
   - ingestion unreachable;
   - an operator hold;
   - catch-up coupling.

   Correcting them needs a trusted end-of-`DATA` time from the edge, and none
   exists. The only way to make an edge time usable would be recipient,
   overshoot, junk and error limits tight enough to refuse legitimate
   clients, and D_max would still be hours (§9.2).
4. **EP-4 is optional.** The deadline plus 64 KiB/s no longer unlocks credit.
   It remains a sender-facing hardening against the slowloris, and nothing in
   this design requires it.
5. **EP-8 (4) when XFORWARD is missing.** This is proposed, not accepted:
   - **fail closed:** discard every edge null-sender message while the
     `edge_without_xforward` alert is open. Genuine bounces are lost, and §4
     is kept;
   - **or fail open:** deliver them, which reopens the expiry-bounce path for
     that duration.

### 9.9 Edge preconditions: one change set for Ops to review

None is assumed. Each needs Ops' review. The ETRN and DSN keys of PR #147
belong to §4.

| # | Edge change | Status | Unlocks |
|---|---|---|---|
| EP-0 | `smtp_send_xforward_command = yes` on the relay transport, with ingestion's `xforward-peers` allow-list. Live value: `no` (#124 [6688743690](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688743690)) | **required**: a new behaviour change toward ingestion, for the whole allowance design and EP-8 (4). Nothing deployed trusts XFORWARD today, and enabling it first is harmless, because the edge sends only the attributes ingestion advertises | identity, C3, EP-8 (4) |
| EP-1 | `enable_long_queue_ids = yes`, switched while no tenant mail is deferred. Live value: `no`, so queue IDs are short (#124 [6688743690](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688743690)) | **required**: a behaviour change | IDENT uniqueness: the allowance design and C3 (RL-7) |
| EP-2 | Pin the current values as a no-change hardening: relay concurrency 20, backoff 120/600 s, `queue_run_delay` 120 s, lifetime 4 h, `bounce_queue_lifetime` 0, recipient limits 50/50, empty `smtpd_authorized_xforward_hosts`, empty `local_header_rewrite_clients`, and the `master.cf` service list. It does **not** include `smtp_send_xforward_command` or `enable_long_queue_ids`, which are off live and changed by EP-0 and EP-1 | **required** | all credit |
| EP-3 | Clock canary: the minutely edge timer is read over WireGuard against DB `now()` | **required for §4 reporting** (N_step), unless Ops provides equivalent `timesyncd` step-journal counts. Not a credit gate | §4's K reporting |
| EP-4 | `smtpd_per_request_deadline = yes`, `smtpd_min_data_rate = 65536`, `chunking` in `smtpd_discard_ehlo_keywords`, stress form kept | **optional**, a sender-facing hardening that nothing requires. It affects slow senders | none for credit |
| EP-5 | Restrict `authorized_submit_users` | **recommended**; `pickup` origins are already ineligible through `PROTO` | none for credit; it narrows local injection |
| EP-6 | Append-only operator-action wrapper | **required for §4 reporting** (N_op), unless Ops provides equivalent `postsuper`/`postqueue` journal counts. Not a credit gate | §4's K reporting |
| EP-7 | NTS: chrony 4.5 plus an egress rule for TCP 4460 and UDP 123 | **recommended** | none for credit |
| EP-8 | Close the expiry-bounce path into the relay domain (below) | **required** for §4's "no DSN of any class", and before any activation that can produce a persistent per-recipient `451` | §4 |

**C3 needs EP-0, EP-1 and EP-2.** The allowance design itself needs EP-0
and EP-1. §4's reporting of K needs EP-3 and EP-6, or Ops'
equivalent journal counts.

**Missing XFORWARD is alerted (with I-20).**

- **What counts.** A transaction from an `xforward-peers` edge that carries
  **no XFORWARD command at all** is counted as
  `identity="edge_without_xforward"` and raises an alert. A transaction
  carrying only `SOURCE=LOCAL` has XFORWARD present, from a local origin.
- **The barrier.** `ALL` is refused, and the activation barrier blocks,
  while that count is non-zero over the activation window, or before EP-0
  and EP-1 are verified live.

**EP-8 in detail** (#124 comments 6687275537, 6687301964 and 6687332923).

**The gap.** An expiry bounce to a relay-domain `MAIL FROM` is relayed into
ingestion. That path is reachable whenever one recipient gets `451` for 4 h
while ingestion is up. A participant sending as its own inbox address would
then receive a bounce for an existing, persistently deferred recipient, and
nothing for an unknown one. That is a recipient-existence and activity
signal (ADR-025, §4).

**Ops' log check.** Across the edge's whole retention from 2026-09-29: 0
expired, 0 bounced, 0 `postfix/bounce` lines, 0 DSN of any class, and 0
deferrals with an ingestion `4xx`. That is consistent with "not reachable in
practice today". It is **an observation, not a proof**.

**Remedies.** The owner decides; none is assumed.

- **(4), preferred. Lab item 6 confirmed the discriminator, in the lab and not yet on the live edge (6688294528).** Ingestion
  discards edge-originated notices (null sender, not `smtpd`-originated per
  `PROTO`) behind the uniform `250`. That is ADR-025's unknown-recipient
  treatment.
  - It needs EP-0. XFORWARD is off on the live edge (#124 6688743690).
  - Genuine internet bounces, and testers sending as an inbox address, are
    unaffected.
  - It is an app change behind the uniform reply, so it needs its own
    security review.
  - **The rule, stated exactly:**
    - Discard applies only to a transaction from an `xforward-peers` edge
      when all three of these hold:
      - XFORWARD is present;
      - `MAIL FROM:<>`;
      - `PROTO` is absent or not SMTP-family ({SMTP, ESMTP, ESMTPS, ESMTPA,
        ESMTPSA}).
    - An absent or `[UNAVAILABLE]` `PROTO` from an edge counts as not
      `smtpd`-originated.
    - The rule depends on the pending lab item 6 re-run with the advertised
      set {IDENT, PROTO, SOURCE} (#124 [6688831352](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6688831352)).
    - A `trusted-clients` peer (no XFORWARD) is handled normally.
    - **If XFORWARD is missing from an edge** (the alert above), this is
      **proposed, for the owner**: fail closed. Every edge null-sender
      message is discarded while the alert is open, so genuine internet
      bounces are lost, in exchange for keeping §4's privacy condition.
      - The alternative is to fail open: deliver them, which reopens the
        expiry-bounce path for that duration.
      - Neither is accepted.
    - The discriminator is `PROTO`, not the null sender alone. The lab
      measured that every non-`smtpd` origin (`pickup`, `sendmail -bs`,
      bounce, delay notice) sends only `SOURCE=LOCAL`, with no `PROTO`. So
      "`PROTO` absent or not SMTP-family" matches the measurement. It cannot
      tell those origins apart, and does not need to. A null-sender message received from the internet reports an
      smtpd protocol, so genuine bounces are delivered.
  - **Where it happens:** at the same point as ADR-025's unknown-recipient
    discard, **before recipient resolution**.
    - There is no `INGEST` charge, no allowance row, no per-IDENT marker,
      no credit, no stored row and no stored content.
    - ADR-019/026 and I-2 are unaffected.
    - Logging is metadata only.
    - It adds one metric, `inbound_edge_notice_discarded_total`, with no
      labels.
  - **Forgery and oracles:**
    - `PROTO` is set by the edge, not the sender. Only `pickup` and the
      bounce daemon produce a non-smtpd origin, and only local accounts can
      use `pickup` (EP-5).
    - The edge still sees the uniform `250`, so there is no oracle.
  - **AT-EP8:** send a forced expiry as the participant's own inbox address,
    once to an existing recipient and once to an unknown one. Its inbox stays
    empty in both cases. A null-sender message from the internet to an
    inbox is delivered.
- **(1), otherwise.** `check_sender_access` refuses a relay-domain
  `MAIL FROM` from non-`mynetworks` clients.
  - The refusal comes before `RCPT`, so it reveals nothing about any
    recipient.
  - It needs the owner's reading of ADR-025's uniform `250`.
  - It accepts a tester behaviour change: no one can send *as* an inbox
    address.
- **Not recommended:**
  - (2), never delivering bounces into the relay domain. It refuses genuine
    bounces and changes the null-sender reply.
  - (3), replacing `bounce`/`defer` with `discard` in `master.cf`. That is a
    non-standard daemon replacement.

**Verification, for whichever remedy is chosen:** a lab proof, a reviewed
change, and a live check that no DSN of any class reaches a sender, against a
forced expiry and a forced `5xx`.

