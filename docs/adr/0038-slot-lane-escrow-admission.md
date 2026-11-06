# ADR-038: Slot-lane escrow admission accounting

**Status: Proposed.** This is not accepted, and nothing may rely on it until
the owner accepts it. It authorizes nothing: no implementation beyond the
bounded prototype the owner's option-3 decision already allows, no enablement,
no admission, and no change to any §11 parameter, threshold or criterion.

**Authority, if accepted:** acceptance of **this ADR**. It is not ADR-035
§11's naming of "per-node escrow" as the fallback. ADR-035 §11 and the gate D
note (2026-11-05) require an *ADR review* when CHOSEN fails. This ADR is that
review's proposal. It is **not** an execution of the named fallback: per-node
escrow is rejected as named (with one ingestion node it is the rejected hot
global row), and the mechanism here is new. It uses:

- per-slot lanes;
- exact scope rows with an inline refill;
- two input epochs and a revocation generation;
- a keeper;
- new timing residuals.

**Amends, by reference only** (on acceptance, dated pointers are added at
each passage; no text is edited in place):

- [ADR-035](0035-physical-storage-bound-at-ingest.md):
  - §1 I2;
  - §4: T1, "Why one statement", "The lock";
  - §6: lock order;
  - §10: placement and the ADR-024 carve-out;
  - §11, §14, §16, §17, §18;
  - Amendment 2 §A2.1 and §A2.8;
  - the gate D amendment note (2026-11-05);
  - the Alternatives section.
- The Amendment 2 annex,
  [`0035-amendment-proposal-filesystem-containment-contract.md`](0035-amendment-proposal-filesystem-containment-contract.md):
  rules (G), (C) and (P), and the §2.4 transitions table.
- [ADR-037](0037-bounded-cohort-storage-and-disclosure.md): "Implementation and
  release gates".

**Leaves unchanged:**

- CLAUDE.md invariant 8: a sentence is proposed for the owner below; this ADR
  does not edit CLAUDE.md;
- ADR-025 and I9 for replies;
- the 2026-11-06 I9 exception, which is not extended;
- amendment D: r = 1/s, b = 60, R = 31, 2R = 62;
- every §11 criterion;
- the T2 transaction;
- `synchronous_commit = on`.

**Design:** [`docs/dev/structural-admission-design.md`](../dev/structural-admission-design.md)
(revision 2). All section references below (§n) are to that document.

## Context

Gate D failed. Run 3 stays FAIL. The A′ focused runs (NON-GATE, `ebdad58`)
show mode a saturated at 2R = 62/s on the staging host class with the loaded
beta cohort:

- `(35, 1)` hold 13.0–17.3 ms (median);
- ρ 0.91–1.12;
- T1 p99 2.5–14.4 s;
- in-lock re-plans at 12.8–14.3 ms each.

**The NO_LOCK verdict at 62/s.** NO_LOCK's T1 p99 was:

- 37.5–50.0 ms in the 80482e6 focused run (2 of 2 ≤ 50 ms);
- 24–42 ms in 7 of 8 run 3 scenarios, and 196 ms in one.

NO_LOCK therefore passes at 62/s, narrowly and not uniformly. This is the
gate D note's branch "CHOSEN fails while NO_LOCK passes", which returns the
design to ADR review.

The owner chose a structural redesign (option 3). The owner excluded another
mode a round, analyze-setting changes for gate evidence, reopening P without
new evidence, and lowering the inbound limits.

## Decision (proposed)

Under `ALL`, with `testinbox.storage.admission-accounting = ESCROW` (and
`admission-locking = GLOBAL`), T1 is the slot-lane escrow protocol (§2, §4).

**Lanes.**

- Global headroom (payload `G − H`; the footprint headroom of rules (G) and
  (C)) is held as grants in lane rows, one per (ingestion node, write-slot
  index).
- An event spends only from the lane of the slot it holds, so no two in-flight
  T1s share a lane.

**Scope rows.**

- Each workspace and inbox has one scope row: a grant that is a lower bound of
  its headroom.
- A short or stale row is refilled exactly, under its own row lock, in a
  statement after that lock.
- Scope refusals are decided with mode a's formula on that refilled truth.

**The escalation path.**

- A short or stale lane escalates. The escalation takes the drain fence:
  `(35, 1)`, then every lane.
- It runs mode a's snapshot statement byte for byte and decides with mode a's
  rules.
- It inserts the event's reservations, **then** regrants from truth that
  includes them.

**Grants, epochs and audit.**

- **Who writes grants.** They are written only by `SECURITY DEFINER`
  functions. Increases are computed from the authoritative rows. Decreases are
  derived from the reservations inserted with them.
- **The global policy inputs** come from `storage_escrow_policy`, written only
  by the operator pool reserve.
- **Staleness.** Two database-maintained epochs and a revocation generation
  make stale grants unspendable.
- **The audit.** Reconciliation checks `truth + grants ≤ limit` against limits
  it derives itself. A violation revokes every grant and is a distrust event.

## The passages amended, and how

### ADR-035 §1 I2 (atomic ceilings)

Today: *"The inbox, workspace and global ceilings are evaluated in **one**
database snapshot, under one admission lock."*

Proposed reading under `ESCROW`:

> The inbox, workspace and global ceilings, and rules (G) and (C), hold at
> every commit. A copy is admitted either:
>
> - (a) against database-maintained escrow grants, each a lower bound of its
>   scope's headroom computed from the authoritative rows; or
> - (b) in one database snapshot under the T1 drain fence (`(35, 1)` plus
>   every lane), with every grant of the involved scopes reclaimed.
>
> A copy is refused only on exact headroom: either an exact refill of its scope
> under that scope's row lock, decided with mode a's formula, or (b). The
> global admission cap is unchanged.

### ADR-035 §4: T1, and "The lock" (H2(a))

- **T1 under `ESCROW`** is the fast path of design §4.2, escalating to the
  path of §4.4.
- **"The lock"** says *"Only T1 takes it … T1 creates reservation rows. It
  waits on no row lock."* Under `ESCROW`:
  - `(35, 1)` is taken by the escalation path, the keeper's refill and the
    existing fences, never by the fast path;
  - the fast path **does** wait on row locks: its own lane, and up to 100
    scope rows, bounded by `L_w` = 250 ms. Past that it escalates, never
    answering `451`.
- **Mode a remains the T1 of `OFF` and `TENANT_LIMITS`, byte for byte.** The
  escalation path of `ESCROW` runs mode a's snapshot statement byte for byte
  and decides with mode a's rules. It does so inside a different transaction:
  the drain fence, the event's scope-row locks, the insert, then the regrant.
  (H2(g): this replaces the earlier draft's "byte for byte" claim for the
  escalation path.)

### ADR-035 §4, "Why one statement", and §17 test 12 (H2(b))

They continue to apply to:

- the escalation path's snapshot;
- each scope refill and the global refill. Each reads truth in **one
  statement**, executed **after** the locking statement.

The fast path does not decide on a snapshot of usage. It decides on grants
read under row locks, and its correctness rests on design invariants E1–E10
(§5) instead. A fast-path counterpart of test 12 is the after-lock mutant test
T-C14.

### ADR-035 §6: lock order

The order gains, after ADR-027's `guardAdmission`:

1. `(35, 1)` (escalation, keeper, fences);
2. lanes, by `(node_id, lane)`;
3. scope rows, by `(kind, scope_id)`;
4. then `storage_reservation` onward, as today;
5. `(35, 2)`;
6. **the epoch row, last.** A transaction that will later take `(35, 1)` or a
   lane takes `(35, 1)` before its first epoch-bumping write. Repair,
   recompute and the audit's repair transaction take the drain fence
   (`(35, 1)`, then every lane) as their **first** statement, before `(35, 2)`
   and before any epoch-bumping write. Their generation bump is their **last**
   write (design §4.8).

### ADR-035 §10: placement and the ADR-024 carve-out (H2(d))

**Placement** gains:

- the port `StorageEscrowStore` (fast path; `Decided | Escalated(reason)`, no
  SQLSTATE or JDBC type across it);
- the use case `RefillStorageEscrow` (keeper regrants);
- the domain `EscrowFit`.

`StorageAdmission` owns the sequence of fast path, escalation and slow path.

**The carve-out** (*"Derived accounting may be maintained by database
triggers, provided a use case, `ReconcileStorageAccounting`, proves it against
the source rows"*) is extended to:

- **admission headroom computed in `SECURITY DEFINER` functions**: escrow
  grants, the input epochs and their counters, and the escrow policy row;
- **a second implementation of the footprint model F(·) in SQL**
  (`storage_footprint_bound`). Until now F existed only in Kotlin; `FootprintSql`
  and V25 contain no F arithmetic. It is pinned to `FootprintModel.bound` by a
  property test (design P-F). P-D runs the decision-equivalence test against
  the real SQL.

**Grants are headroom, not usage.** Committed usage remains `base + Σdelta`,
maintained as today.

**§10 "Reconciliation (I8)"** gains (architecture re-check, item 4(b)):

- `ReconcileStorageAccounting` also runs `storage_escrow_audit()`: one
  statement checking `truth + grants ≤ limit` for every spendable grant,
  against limits the reconciler derives itself, never those stamped on the
  rows.
- On a violation, a fresh transaction in canonical order (drain fence →
  `(35, 2)` → re-check → distrust → revoke as the last write) makes every grant
  unspendable and stamps distrust. Under `ALL` that is a `451` until a clean
  pass, as for ledger drift.
- **Repair and recompute** now take the drain fence first and revoke every
  grant as their last write, so an upward correction of usage can never leave
  a spendable over-grant.

### ADR-035 §11

- **Gate D's CHOSEN** is the T1 that production wires; with `ESCROW`, that is
  this T1. Every parameter, threshold, matrix entry and criterion is unchanged,
  including R and 2R (amendment D), the staging host class and the loaded
  cohort.
- **The benchmark's connection lifecycle** (cold pool, production's 30 min
  max lifetime, `920ba1d`) is a measurement-fidelity rule already enforced by
  the harness for every CHOSEN run. It is not a §11 criterion, and not
  introduced by this ADR.
- (H2(h): the earlier draft's added criterion, "evidence at 2·R_T", is
  **deleted**. R_T belongs to the INGEST deferral design (#143), which is not
  accepted, and R_T is not an ADR-035 term until it is.)

### ADR-035 §14, §16, §17, §18 (H2(e))

| Section | Change |
|---|---|
| §14 | V28 is expand-only, with a 2 s `lock_timeout`. It replaces the bodies of V21's node fence (revokes on a NULL capability), V25's share fence (drains), and the repair and recompute functions (drain fence first, revoke as the last write). V28 changes behaviour for older artifacts the moment the migrator runs (design §6.5); `docs/dev/rollback.md` gains a V28 section |
| §14 Phase 3 | **Activation gate S** (slot-lane; the name avoids a clash with the existing gate E): every live node row of either role declares `admission_accounting = 'escrow-v1'`; `escrow_enabled = true`; `storage_escrow_policy` exists, written only by the operator-only definer in the reserve transaction, and every escrow node's configured binding equals its binding. With no policy row, or a mismatch, nothing is granted and every event is decided on the slow path as mode a, never a `451` |
| §16 | The metrics of design §13.4, all closed enums. `cause` is pinned to the `last_cause` CHECK enum |
| §17 | The decisive tests of design §11 are added. Tests 1–55 run against both GLOBAL and ESCROW |
| §18 | Gate F's privilege query covers the five new tables (no application DML), and a clean `storage_escrow_audit()` is required within the activation window. A rollback floor at the V28 artifact, with the Ops step "set `admission-accounting = GLOBAL` everywhere and confirm `escrow_enabled = false` before rolling any deployable or operator image below it" (design §6.5) |

### ADR-035 Amendment 2 §A2.1 and §A2.8, and the annex (H2(c))

- **"Admission charges the exact increase of F on the aggregate."** Under
  `ESCROW`:
  - a fast-path spend charges `Σ F(c_i)`, an **upper bound** of the increase
    by Lemma 2, not the exact increase;
  - exact-row refusals and the escalation path still use the exact aggregate
    rule.
- **Rule (G), `F(L + A + c) + H_F ≤ G_F`** (architecture re-check, item
  4(a)). Under `ESCROW` it moves to refill time, like (C) and (P): it is
  evaluated by the definer refill (after the event's own insert on the
  escalation path), and between refills it is enforced through lane grants:
  E5, **`F(L) + Σ g^F + H_F ≤ G_F`**.
- **Rule (C), "evaluated in T1's one snapshot on running totals in envelope
  order."** Under `ESCROW` it is evaluated at refill time, by the definer refill
  (after the event's own insert on the escalation path). Between refills it is
  enforced through lane grants: E6,
  `F(L + D_k) + Σ g^F + W_k + H_F + P_F + M + R_ops ≤ C_fs`.
- **Annex rule (P).** Today it reads `F(L + D + x) + W + H_F + M + R_ops ≤ C_fs`.
  Under `ESCROW` it becomes **`F(L + D + x) + Σ g^F + W + H_F + M + R_ops ≤ C_fs`**:
  outstanding lane grants count as live. Probes stay admissible through `P_F`,
  which grants exclude.
- **The annex §2.4 transitions table gains:**

  | Transition | Effect on Φ |
  |---|---|
  | refill (global; scope) | `Σ g` set from truth after the locks: `Φ + Σ g` within the bound by construction |
  | spend | grant → reservation: `Φ + Σ g` unchanged or lower (Lemma 2) |
  | revoke (drift, repair, mixed version) | grants unspendable: lower |
  | keeper collection of dead lanes and orphaned scope rows | lower |

  Every other row stands.
- **§A2.8, "Under `OFF`, a rollback to an earlier artifact stays supported: the
  V8–V10 definer functions and triggers keep the ledger exact."** That still
  holds for **T1 and the ledger**, which stay byte for byte under `OFF` and
  `TENANT_LIMITS`. What is **not** byte for byte under `OFF` once V28 is
  applied:
  - the epoch-input writers also update the epoch row;
  - the replaced V21 node-fence, V25 share-fence, repair and recompute bodies
    run under `OFF` and under older artifacts.

  The OFF rollback stays exact for the ledger. A rollback below the V28
  artifact is covered by the rollback floor of §18.

### ADR-035 gate D note (2026-11-05) (H2(i))

- **"The design returns to ADR review for the named per-node escrow
  fallback."** This ADR is that review's outcome. It adopts a new mechanism,
  not the named fallback (see Authority).
- **The NO_LOCK verdict that puts us in this branch** is recorded under
  Context.

### ADR-035 Alternatives (H2(f))

- **"A single hot global accounting row. Measured and rejected (§11)."** It
  stays rejected. SLE has no row that every T1 updates. Lanes are per write
  slot, and T2, retention and the compactor touch no escrow row.
- **"Application-maintained counters. Rejected: the cascade objection applies
  to them."** It stays rejected. Escrow grants are not usage counters:
  - a cascade can only make them more conservative;
  - they are written only by definer functions from the authoritative rows,
    and audited against them.

### ADR-037, "Implementation and release gates" (H2(f))

- **"Preserve the one storage T1/T2 writer."** This holds. The fast path and
  the escalation path are inside the one `StorageAdmission` use case; T2 is
  unchanged.
- **"…transaction lock ordering."** This is amended by the lock order above.

## Dependencies that are NOT part of this ADR (M6)

The INGEST deferral design (#143, not accepted) is not relied on: its
`ingest_allowance` lock rank, its allowance consume in T1, its `t1_schedule`
bucket and R_T. If it is accepted, it composes with this ADR as described in
design §8. Its token rule for escalated attempts (design OD-5) is a separate
owner decision, and any choice other than counting every attempt departs from
owner decision 2 of #124 comment 6684756433.

## Privacy

**Replies and outcomes are mode a's** (design Theorem D). No new reply class,
no per-tenant `451`.

**Two timing residuals are genuinely new** and are **not** covered by the
2026-11-06 I9 exception, whose decisions exclude the partitioned-locking
residual and anything materially broader:

| | Residual |
|---|---|
| N1 | A wait on a workspace or inbox scope row held by another in-flight T1. Typically 2–5 ms, capped at 250 ms before escalation |
| N2 | An inline scope refill. +0.3–1 ms, confined mostly to near-limit scopes by the adopted background refill |

They need the owner's own decision, with the measured criterion of design
§9.3, before `ESCROW` carries tenant traffic. Constant-time padding and
per-permit workspace lanes are rejected (design §9.3).

**One global residual:** lane refill timing (N3), the same kind as mode a's
`(35, 1)` wait. **Condition C-Q** (design §9.4: `K_max × Q_lane` of headroom
guaranteed beyond the shares, counting stranded lanes) keeps it independent of
the headroom level. Without C-Q it would reveal one coarse global bit, and
ADR-037 A2 may not be cited for that.

**Context only.** The security review of the design found a pre-existing
timing disclosure in the current ingest path, unrelated to this ADR. It is
with the owner.

## For the owner: proposed sentence for CLAUDE.md invariant 8 (not adopted)

> Escrow grants (ADR-038) are headroom, not usage: database-maintained by
> definer functions from the authoritative rows, audited by
> `ReconcileStorageAccounting`, and invalidated by any non-transfer increase of
> usage.

Adopting it is the owner's decision. This ADR does not edit CLAUDE.md.

## Consequences

**Positive.**

- No per-event aggregation in T1, and no global lock on its fast path.
- Per-event server work of about 1–2 ms, against about 10 ms [estimate].
- The global bound is enforced directly, not through the ADR-037 partition.

**Costs.**

- Five tables, about nine definer functions, epoch triggers on rarely written
  tables, and a keeper per ingestion node.
- A second implementation of F, in SQL.
- A rollback floor.
- Two new timing residuals that need an owner decision.

**Risk.** It is unmeasured until the bounded prototype's focused NON-GATE run
(design §12). The stop criteria there can reject this ADR within about two
days of prototype work.

**Unchanged.** Run 3 stays FAIL. Gate D must still pass, unchanged, before any
activation.
