# Structural redesign of T1 admission accounting: slot-lane escrow — design

**Status: DESIGN ONLY. Not implemented, not accepted, not gate evidence.**

- **Authority.** The owner's decision to take option 3 of #124 comment
  6689093449 ("a structural redesign of admission accounting … one selected
  design and a bounded prototype"). It is quoted in full in §0.
- **What it authorizes.** Nothing beyond this document.
  - **Revision 2** applies the three reviews of revision 1 (`a453e14`):
    correctness, security, and architecture. All three returned AGREE WITH
    CHANGES, and every required edit is applied. Where to find each one:
    Appendix A.
  - **Revision 3** applies the re-check of revision 2 (`f8d869c`). **M0′:
    all three reviewers AGREE subject to these edits, which are applied; M1
    may start.** Where to find each one: Appendix B.
  - The gating is in §14.
- **The ADR change** is the separate, **Proposed** ADR-038
  ([`docs/adr/0038-slot-lane-escrow-admission.md`](../adr/0038-slot-lane-escrow-admission.md)).
  It amends ADR-035, its Amendment 2 annex and ADR-037 by reference only.
  - Nothing here edits an Accepted ADR, a §11 criterion, the beta's `INGEST`
    limits (amendment D: r = 1/s, b = 60, R = 31, 2R = 62), or any database
    setting.
  - **Acceptance of ADR-038**, not §11's naming of per-node escrow, would be
    the authority for this design.
- **Run 3 stays FAIL.** The A′ focused runs (`ebdad58`) are NON-GATE and are
  cited only as measurements.
- **Branch.** `app/fasttrack-structural-admission-v2`, built from
  `origin/app/fasttrack-admission-candidate` (it carries amendment D,
  `06e8448`) merged with `app/fasttrack-gate-d-a-prime` (`848359e`) and
  `app/fasttrack-harness-cold` (`920ba1d`).

Labels: **[measured]** is copied from committed evidence; **[derived]** is
arithmetic on measured figures; **[estimate]** is mine and may be wrong;
**[inference]** is a reading of evidence that a measurement has not confirmed.

---

## 0. The owner's decision (binding, verbatim)

> Choose option 3 as the primary path: a structural redesign of admission
> accounting. Do not proceed with another full mode-a optimisation round or
> change database analyze settings for gate evidence under this authorization.
> Start with one selected design and a bounded prototype. The proposal must:
> - explain why it avoids both repeated ledger aggregation and the previously
>   rejected hot-global-row bottleneck;
> - preserve conservative accounting, pending-cleanup debt, concurrency safety,
>   restart/restore behavior and rollback boundaries;
> - cover fresh traffic, deferred retries and backlog recovery together;
> - retain the accepted privacy scope and identify any genuinely new
>   disclosure.
>
> Have correctness and architecture/security reviewers agree on the design and
> decisive tests before expanding implementation. Measure the representative
> loaded-cohort path, including cold connections and replanning, early enough
> to reject an unsuccessful approach without another week of work. […] Do not
> reopen P, which also failed, without new evidence supporting it. Keep run 3
> as FAIL. The required R/2R, latency, integrity and qualification criteria
> remain unchanged. Do not lower the inbound limits again merely to accommodate
> the implementation.

| Requirement | Section |
|---|---|
| Avoids repeated aggregation and the hot global row | §3 |
| Conservative accounting, pending-cleanup debt | §5, §6.1 |
| Concurrency safety | §4.8, §5, §11.1 |
| Restart/restore, rollback boundaries | §6.4, §6.5 |
| Fresh, deferred and backlog traffic together | §8 |
| Privacy: accepted scope kept, new disclosure identified | §9 |
| Reviewer agreement on design and decisive tests | §11, §14, Appendix A |
| Early, representative measurement with cold connections | §12 |

---

## 1. The problem, measured

T1 today is ADR-035 mode a (`JdbcStorageAdmission`, `CohortSql`,
`FootprintSql`, migrations V6–V27): take `(35, 1)` exclusive; one snapshot
statement that re-aggregates `storage_delta`, `storage_reservation`, the
footprint inputs and every share of the cohort pool, for every event; decide
in the application; insert the reservations and commit, in one round trip
since A′.

The figures below are from the staging host class, with the beta's loaded
closed cohort (31 required shares), CHOSEN at 2R = 62 events/s (A′ focused
runs, `ebdad58`, runs 1–4):

| Figure | Value |
|---|---|
| `(35, 1)` hold, median | 13.0–17.3 ms [measured] |
| Lock utilisation ρ | 0.91–1.12 [derived] |
| T1 p99 | 2.5–14.4 s, against 50 ms [measured] |
| Snapshot round trip inside the hold | 9–13 ms. The cohort-pool branch is ≈ 6.5 ms of it, with a per-share HashAggregate over `storage_delta` that grows during the phase [measured] |
| Insert + COMMIT round trip inside the hold | 3.1 ms median, 8.5–9.6 ms p99 [measured, run 2] |
| `preLock` (mode a's three configure round trips) | 0.74–0.86 ms mean [measured] |
| In-lock re-plans | 21–28 per 60 s phase, at 12.8–14.3 ms each, caused by autovacuum/analyze invalidations [measured] |

**The NO_LOCK verdict at 62/s.** NO_LOCK runs the same statement without the
lock.

- Its T1 p99 at 62/s was 37.5–50.0 ms in the 80482e6 focused run (60 s
  windows, 2 of 2 ≤ 50.0 ms) [measured].
- In run 3 it was 24–42 ms in 7 of 8 scenarios, and 196 ms in one [measured].

So **NO_LOCK passes at 62/s, narrowly and not uniformly**, while CHOSEN
fails. That is the gate D note's branch "CHOSEN fails while NO_LOCK passes",
which returns the design to ADR review (ADR-035, Amendment note 2026-11-05,
gate D). NO_LOCK still runs the heavy statement, so it is no floor for a
design that does not.

**P's failure was service time, not lock waits.** In the focused P run,
`adv_wait` was 0.00–0.07, there were no fallbacks, and the admission call was
nearly all work: a 15.1–17.2 ms statement [measured]. P is not reopened here
(§10).

**Therefore** a passing design must:

- run no population-scaled or cohort-scaled aggregate per event;
- not replace the global lock with another global serialization point whose
  hold contains a commit.

---

## 2. The selected design in brief

**Slot-lane escrow with exact scope rows (SLE).**

1. **Lanes.** Global headroom (payload `G − H`; the footprint headroom of
   rules (G) and (C)) is pre-split into **grants** held in **lane rows**, one
   per *(ingestion node, write-slot index)*. That is 16 rows per node, so even
   one node has 16 lanes.
2. **No shared lane.** An event holds one write slot from before T1 until
   after T2 (ADR-035 §4 step 5). Its slot index names its lane, so no two
   in-flight T1s share a lane.
3. **Scope rows.** Each workspace and each inbox has **one scope row**. It
   holds a grant equal to its exact headroom at its last refill, minus what was
   spent since.
4. **The fast path** locks the event's lane and scope rows, reads each inbox's
   owner, and spends through one database function. That function inserts the
   reservations and derives the spend from them. The fast path takes **no
   `(35, 1)`** and runs **no aggregate** beyond the bounded per-scope refill of
   point 6.
5. **Admissions come from grants; refusals never do.** Grants are lower bounds
   of headroom.
6. **Scope refusals are exact.** A short or stale scope row is refilled inline,
   in a separate statement after its lock. A workspace or inbox refusal is then
   decided with **mode a's formula on the refilled truth**.
7. **The slow path.** A short or stale lane escalates to it:
   - the drain fence (`(35, 1)`, then every lane);
   - mode a's snapshot statement, byte-identical, and mode a's rules;
   - the event's insert, **then** the regrant from truth that includes that
     insert.

   `SERVICE_CAPACITY` is decided only there.
8. **Who writes grants.** Only `SECURITY DEFINER` code, from the authoritative
   rows. The global policy inputs come from a database row the operator
   writes. Applications can only spend.
9. **Staleness.** Two database-maintained epochs, `epoch` for lanes and
   `scope_epoch` for scope rows, plus a revocation `generation`, make a stale
   grant unspendable.
10. **Audit.** Reconciliation checks `truth + grants ≤ limit` against limits it
    derives itself. A violation is a distrust event: `451` under `ALL` until a
    clean pass.

---

## 3. Why it avoids both bottlenecks

### 3.1 No repeated ledger aggregation in T1

| Mode a reads per event | SLE fast path reads per event |
|---|---|
| Σ `storage_delta` over all rows (`g_delta`) | nothing |
| Σ `storage_reservation` over all rows (`g_reserved`) | nothing |
| the cohort pool: per-share aggregates, debt, charges, node readiness, JSON | nothing; the verdict is stamped on the lane at refill (§4.4) |
| per involved workspace and inbox: base + Σ delta + Σ reservation | one primary-key row each, plus `inbox(id, workspace_id)` for ownership |

The fast path touches at most 1 + |W| + 2·|I| rows by key: the lane, the
scope rows, and each inbox's owner. That is ≤ 151 for a 50-recipient event.

The ledger is aggregated in exactly three places, none of them per event in
steady state:

| Where | When |
|---|---|
| **The global refill** (slow path or keeper): mode a's statement, unchanged | rare; frequency in §3.3 |
| **The scope refill**: one workspace or one inbox, through the V26/V27 indexes | inline only when that row is short or stale; otherwise by the keeper in the background (§4.5) |
| **Reconciliation** | every 6 h, as today |

### 3.2 No single hot row

- **Lanes never contend between fast paths.** A lane is keyed by the event's
  slot index, which is unique among the node's in-flight events. A node id is
  unique among live processes (`StorageNodeClaims`). So no two fast paths ever
  contend on a lane.
- **Other writers never touch escrow rows.** T2, retention, the compactor and
  cleanup never read or write one. Mode b was rejected (ADR-035 §11: retention
  p99 207.6 ms at 64 clients, T1 and T2 serialized) because those writers
  updated its hot row.
- **The epoch row is read without a lock** (no `FOR SHARE`, no `KEY SHARE`).
  T-C1 asserts it behaviourally: while a fast path is paused mid-transaction,
  another session's `SELECT … FROM storage_admission_epoch FOR UPDATE NOWAIT`
  must succeed. Row locks are not visible in `pg_locks`. Otherwise every T1 would take a shared
  lock on one global row, which is a hot row in another form (MultiXact
  churn).
- **What does still serialize:**
  - the global refill against fast paths (§3.3, rare);
  - events that share a workspace or inbox scope row, bounded by the slot
    fairness of 4 events per workspace per node.
- **More ingestion nodes** add lanes (16 per node), not per-event cost. One
  node still has 16 lanes. That is how SLE avoids the trap that, with one node,
  a per-node escrow row is the rejected hot global row.
- **The other hot row is not removed.** If the INGEST deferral design (#143,
  not accepted) lands, its `t1_schedule` row is a single global row spent once
  per T1, in series before it. The prototype measures a stub of it (§12.1,
  correctness 11).

### 3.3 Expected cost and contention at 62/s and 124/s [estimate]

**Per-event fast path, on the staging host** [estimate built from measured
steps]:

| Step | Estimate |
|---|---|
| Configure | ≈ 0.8 ms, taking mode a's measured `preLock` for three round trips. Batching into one round trip should give ≈ 0.3 ms; until measured, use 0.8 |
| `storage_escrow_lock(…)`: lane, ≤ 100 scope rows and ≤ 50 inbox owners by key, the epoch row | 0.3–0.8 ms |
| Inline scope refill, only where needed | 0.3–1 ms each |
| Decide (application, unchanged rules) | 0.1–0.5 ms |
| `storage_escrow_admit(…)` + COMMIT: reservation insert (2.0 ms median measured at base), ≤ 101 HOT updates, flush (≈ 1.1 ms measured) | 3–4 ms |
| **T1 transaction** | **≈ 4.5–5.5 ms p50; 10–20 ms p99.** G7's 6 ms mean is tight against this |

- **Server CPU per T1** falls from about 10 ms to about 1–2 ms, which frees
  roughly 0.5–1 core at 62–124/s on an 8-vCPU host [estimate].
- **Lane contention between fast paths** is zero by construction.

**Contention on scope rows** [derived from the harness mix; the hold is an
estimate]. The harness spreads `1:60,3:25,10:12,50:3` uniformly over 31
workspaces, so an event touches 3.1 distinct workspaces on average.

| | 62/s | 124/s |
|---|---:|---:|
| λ per workspace row | 6.2 events/s | 12.5 events/s |
| ρ per workspace row (4.5 ms hold) | 0.028 | 0.056 |
| P(a 50-recipient event waits at least once on its ≈ 25 rows) | ≈ 0.51 | ≈ 0.76 |
| ρ per inbox row (310 inboxes, 4.05 copies/event) | ≈ 0.004 | ≈ 0.008 |

- When an event does wait, it waits for about one residual hold (2–5 ms).
- Under amendment D (r = 1 copy/s per workspace, sustained) λ per workspace is
  at most about 1/s in production, so the harness overstates this by about 6×.
- A 50-recipient event holds ≈ 25 workspace rows for ≈ 6 ms. That is ≈ 1.1 %
  of time at 62/s [derived].

**The global refill blocks every fast path while it holds the lanes.**

- **Hold.** The mode a statement (9–13 ms measured), plus lane writes
  (≈ 1 ms), plus a re-plan if invalidated (12.8–14.3 ms measured): 10–30 ms in
  total.
- **Frequency.** One per lane-epoch bump or validity expiry, plus low-water
  refills: ≤ 1–2 per minute in steady state [estimate; §4.5]. That is
  ≈ 0.05 % of time [derived from the estimate].
- **Splitting the epoch** (correctness 4) means an observation, a trust change
  or a node change no longer makes scope rows stale. So no post-bump storm of
  inline scope refills.

**For comparison: the rejected single escrow row** (per-node escrow with one
node), at a 3.5–4.5 ms hold [estimate]. ρ is 0.22–0.28 at 62/s and 0.43–0.56
at 124/s. The M/M/1 wait p99 is 14–21 ms and 23–41 ms. Every commit-flush
stall is head-of-line for every event.

**Projected T1 p99** (completion − due): 10–30 ms at 62/s and 15–50 ms at
124/s [estimate, low confidence]. That excludes `t1_schedule`, which the
prototype adds (§12). Host stalls are the dominant unknown.

---

## 4. Mechanism

### 4.1 Data and functions (one expand-only migration, "V28")

```
storage_admission_epoch        -- one row, id = 1; seeded by V28 from a sequence
  epoch bigint                   -- lane inputs (§4.6)
  scope_epoch bigint             -- scope-row inputs (§4.6)
  generation bigint              -- bumped only by storage_escrow_revoke (§6.3, §6.5)
  escrow_enabled boolean         -- false while any not-clean node row is not escrow-capable
  last_cause text CHECK (last_cause IN (<closed enum, §13.4>))
  changed_at timestamptz

storage_admission_epoch_bumps  -- (cause PK, bumps bigint): the DB-side counter behind the metric (L3)

storage_escrow_policy          -- one row; written only through storage_escrow_set_policy (operator role only), in the
                               -- pool reserve's transaction, under the S1 drain fence (§4.1, "The policy row")
  binding text                   -- hash of every application-supplied refill input (M1b)
  g_payload_cap, g_footprint_cap, c_fs_terms, h_f, p_f, model (B, ε-denominator, O_max),
  q_lane_payload, q_lane_footprint, k_max

storage_escrow_lane            -- PRIMARY KEY (node_id, lane); fillfactor 50
  node_id text, lane smallint CHECK (lane BETWEEN 0 AND 15)
  payload_avail bigint CHECK (>= 0), footprint_avail bigint CHECK (>= 0)
  epoch bigint, generation bigint, binding text
  ready boolean, not_ready_reason text   -- closed enum; the stamped readiness verdict
  valid_until timestamptz                -- min(heartbeat freshness, observation age)
  refilled_at timestamptz

storage_escrow_scope           -- PRIMARY KEY (kind, scope_id); fillfactor 50
  kind char(1) CHECK (kind IN ('W','I')), scope_id uuid
  payload_avail bigint CHECK (>= 0), footprint_avail bigint CHECK (>= 0)  -- footprint: W only
  limit_payload bigint, limit_footprint bigint, binding text
  scope_epoch bigint, generation bigint, active boolean
  refilled_at timestamptz
```

- **No foreign keys.** Retention's cascade never locks an escrow row. A scope
  row of a vanished inbox or workspace is collected by the keeper. The fast
  path still fails closed on it through the ownership read (§4.2,
  correctness 2).
- **`storage_node` gains `admission_accounting text`.** It is NULL for every
  older artifact. Every new artifact writes `escrow-v1`, API and ingestion
  alike. Lanes exist only for ingestion node ids (§6.5).

**Function hygiene** (security 3.1; follows V8–V25). Every V28 function:

- is `SECURITY DEFINER` with `SET search_path = pg_catalog, public`;
- uses schema-qualified references;
- has `REVOKE ALL … FROM PUBLIC`;
- is owned by the migrator/schema owner, not a superuser;
- refuses any isolation level but READ COMMITTED (finding P2 of #136);
- **takes the locks it relies on itself** (`(35, 1)`, lanes, scope rows),
  never trusting the caller;
- where it must read state after a lock, is `VOLATILE` plpgsql with the read
  in **its own statement after the locking statement** (correctness 1). Under
  READ COMMITTED a statement that waited on `FOR UPDATE` re-checks only the
  locked rows; every other table in it is read from the snapshot taken before
  the wait.

| Function | Purpose |
|---|---|
| `storage_t1_fence(drain)` | `(35, 1)`, then every lane `FOR UPDATE` when `drain`; after acquiring both, it runs `set_config('testinbox.t1_fence', 'drained', true)` (transaction-local). The flag lives exactly as long as the locks: reset at commit or rollback, reverted on a subtransaction abort, which also releases the locks that subtransaction took. It asserts lock order, not a security boundary: revoke is executable by no application role (§13.2) |
| `storage_escrow_lock(…)` | §4.2 step 3 |
| `storage_escrow_admit(…)` | §4.2 step 5 |
| `storage_escrow_refill_scope(…)` | §4.3 |
| `storage_escrow_refill_global()` | §4.4 |
| `storage_escrow_revoke(cause)` | the generation bump (and `escrow_enabled = false` when the cause is a mixed version), and nothing else. It **requires** the drain fence to be held already by its caller's first statement, and refuses otherwise: it refuses unless `current_setting('testinbox.t1_fence', true) = 'drained'` (set by `storage_t1_fence(true)`, below). Not `pg_locks`: lane row locks live in the tuple's xmax and are not in the shared lock table (correctness confirmation, 637fea7). It is always the caller's **last** write (§4.8) |
| `storage_escrow_audit()` | §6.3 |
| `storage_escrow_lane_footprint_total()` | Σ spendable lane footprint grants, for rule (P) (§6.1) |
| `storage_footprint_bound(p, n, …)` | IMMUTABLE; `FootprintModel.bound` in SQL, pinned to Kotlin by P-F (§11.2) |

`storage_footprint_bound` is a **second implementation of the footprint
model**. Today F(·) exists only in Kotlin. ADR-038 names this explicitly
(architecture H2(d)).

**The policy row, `storage_escrow_policy`** (correctness Q1, security 3,
architecture 5):

- It carries the same statement trigger as the V25 share tables: every write
  calls `storage_t1_fence(true)` and is refused under any isolation level but
  READ COMMITTED.
- It is written **only** through the definer function
  `storage_escrow_set_policy(...)`. Only the operator role may execute it, so
  E9 holds literally: no application role writes it.
- It is called in the **same transaction as the plan reserve**, under the S1
  fence. The reserve refuses it unless it is consistent with the approved
  plan's `assess = Fits`, exactly as `storage_partition_bound` is today.
- **With no policy row, or a policy/binding mismatch,
  `storage_escrow_refill_global` grants nothing.** Every event then takes the
  slow path and is decided as mode a decides it, and an alert fires
  (`testinbox_storage_escrow_policy_missing`). **A missing row alone never
  causes a `451`.**
- Gate S requires each escrow node's configured binding to equal
  `storage_escrow_policy.binding` (§13.4).

**The bump counter, `storage_admission_epoch_bumps`** (security 4,
architecture 5): global counts per closed-enum cause, no tenant ids, `-` in
backup. Ingestion gets `SELECT`; the API gets none. Exactly one deployable,
ingestion, exports it, as a gauge read from the table, so no series is
duplicated across deployables. A restore resets it, which is harmless.

### 4.2 The fast path (one T1 transaction)

Under `ALL`, with `testinbox.storage.admission-accounting = ESCROW` and
`admission-locking = GLOBAL` (M4):

1. **Configure** as mode a's `configureTransaction`: READ COMMITTED first,
   `synchronous_commit = on`, `lock_timeout`, `statement_timeout`,
   `idle_in_transaction_session_timeout`. These are batched into one round
   trip where the driver allows; §3.3 assumes 0.8 ms until measured.
   - The escrow lock statement alone runs with a short `lock_timeout = L_w`
     (proposed 250 ms; §4.8).
   - No `force_generic_plan` is needed: the fast path's statements are key
     lookups.
2. **(Only if the INGEST deferral design (#143) is accepted; not part of this
   amendment, architecture M6.)** Its allowance consume would be the first
   write here, sharing T1's fate.
3. **`storage_escrow_lock(node, slot, workspaces[], inboxes[], needs[],
   policy)`**, one round trip. Inside, statement by statement:
   - **(a)** lock the lane `(node, slot)` `FOR UPDATE`;
   - **(b)** insert any missing scope row (`ON CONFLICT DO NOTHING`), then lock
     every scope row in **one** `ORDER BY kind, scope_id FOR UPDATE`
     statement. PostgreSQL takes the locks in sorted order (LockRows sits
     above the sort), and the keys are immutable, so a wait cannot reorder
     them. T-C8 pins this;
   - **(c)** in a **new statement**, read `inbox(id, workspace_id)` for every
     inbox of the event, and `now()` as `t0` (correctness 2, architecture M2);
   - **(d)** in a **new statement per row**, refill every scope row that is:
     - **stale**: `scope_epoch`, `generation`, `binding` or `limit_*` does not
       match. The inbox limit is checked against the one derived from the
       freshly validated workspace row;
     - or **short**: its avail is below the application's upper-bound need for
       that scope, or it is already flagged for refill by the keeper;
   - **(e)** return every row flagged `exact` when refilled in this
     transaction. Exact rows also return their refilled `U`, `N`, `C` and
     `C_obj`. The function also returns each inbox's current owner, `t0` and
     the lane's state.
4. **Decide** in the application. `StorageAdmission` keeps
   `verifyOwnership`: a missing inbox, or one owned by another workspace,
   fails T1 closed exactly as mode a (`StorageAdmissionInputException`), from
   the owner returned in 3(c), never from the scope row. Then, per copy, in
   envelope order, narrowest scope first:
   - **Exact row:** decide with **mode a's own running rule** on the returned
     truth. Payload: `U + running + f > lim_P`. Footprint:
     `F(U + C + running + c) > F_W` (correctness 3).
     - Refused ⇒ that scope's reason (`INBOX_LIMIT`, `WORKSPACE_LIMIT`,
       `WORKSPACE_FOOTPRINT_LIMIT`).
     - Admitted ⇒ continue to the next scope.
   - **Non-exact row:** the copy fits only if `f ≤ remaining payload grant`
     and `F(c) ≤ remaining footprint grant`. Otherwise the whole event
     escalates. Grant arithmetic is used for admissions only.
   - **Lane:** fits if the full-envelope priced prefix fits: payload
     `f·(p+1)`, footprint `F(f·(p+1), n·(p+1))`. If it does not, or the lane
     is missing, `ready = false`, past `valid_until`, of another binding, or
     of a stale `epoch`/`generation`, the **whole event** escalates.
   - On escalation the transaction rolls back; nothing was written. The
     adapter returns `Escalated(reason)` (§7).
5. **`storage_escrow_admit(node, slot, reservations[])`, then COMMIT**, in one
   round trip. In **its own statement**, the function:
   - reads `storage_admission_epoch` (correctness 1, E10). It never compares
     against an epoch captured by the lock statement. A fast path that waited
     for its lane behind a revocation therefore sees the new generation and
     escalates;
   - inserts the reservations;
   - **derives** each row's spend from them: payload Σ bytes, footprint
     Σ `storage_footprint_bound(bytes_i, objects_i, …)`;
   - decrements every lane and scope row `WHERE avail >= spend` and the
     current epoch, scope epoch, generation, `escrow_enabled` and, for the
     lane, `valid_until > clock_timestamp()` and `ready` all hold;
   - raises SQLSTATE `TI001` (`escrow_short`) if any row does not match. The
     adapter maps it to `Escalated`.

   On an exact row whose mode-a admission set spends a few bytes more than the
   grant, because F is only subadditive (`F(x) + F(y) − F(x+y) ∈ {0, 1}`),
   this also escalates. The slow path then decides it as mode a.

The reservations themselves are unchanged: their columns,
`t0 = now()`, `write_deadline_at = t0 + E`, and everything after T1.

### 4.3 Scope refill (`storage_escrow_refill_scope`)

It is called only with the scope row already locked by the caller: the fast
path, the slow path or the keeper. It runs **in its own statement**, after
the locking statement (correctness 1). So every T1 that could have spent from
this scope has either committed, and its reservations are visible, or not
started. The **one statement**:

- **reads truth for the scope:**
  - workspace: `workspace_storage_account` base + Σ `storage_delta` +
    Σ `storage_reservation` + Σ `workspace_deletion_charge`, the share row,
    and `workspace_lifecycle_eligibility`;
  - inbox: `inbox_storage` base + Σ delta + Σ reservation, through the V26/V27
    indexes;
- **sets the exact headroom:** `a_P = lim_P − U`,
  `a_F = F_W − F(U + C, N_U + N_C)`, each floored at 0;
- **stamps** `scope_epoch`, `generation`, `binding`, the limits and `active`.

**Where the limits come from.**

- Share limits come from the share rows, in the database.
- `maxStoredBytes` and the inbox-share rational come from the application. They
  can only inflate a **scope** grant, never a lane (security 3.4). That is the
  same trust mode a places in configured policy.
- The row's `binding` is a hash over every application-supplied input, so a
  node with different policy cannot spend another node's grant (architecture
  M1b).

**Cost:** index-bounded by the scope's unfolded deltas and live reservations
[estimate 0.3–1 ms].

### 4.4 The slow path (escalation)

One new transaction, in this order (architecture H1):

1. configure, as mode a;
2. `storage_t1_fence(true)`: `(35, 1)`, then every lane `FOR UPDATE` in
   `(node_id, lane)` order. Every fast path holds exactly one lane, so this
   waits for every in-flight fast path and excludes new ones;
3. lock the event's scope rows, in order;
4. **in a new statement**, run mode a's snapshot statement byte-identical
   (`snapshotStatement`, pinned by `AdmissionStatementTextTest`), including its
   inbox owners;
5. decide with mode a's rules on that truth, `verifyOwnership` included. Every
   grant of the involved scopes is locked, and none is outstanding, so truth
   alone decides. `SERVICE_CAPACITY` and cohort or footprint unavailability are
   therefore exactly mode a's;
6. **insert the reservations**, as mode a;
7. **in statements after step 6**, `storage_escrow_refill_global()` and
   `storage_escrow_refill_scope(…)` for the event's rows. The truth they read
   now includes this event's reservations. The global refill:
   - reads the global inputs from `storage_escrow_policy` and the truth
     (`FootprintSql.CTES`);
   - sets every live ingestion lane to `min(Q_lane, headroom / K_live)`, where
     the payload headroom is `G − H − L_P` and the footprint headroom is
     `min(G_F − H_F − F(L), C_fs − F(L + D) − W − H_F − P_F − M − R_ops)`;
   - **deletes** the lanes of non-live node ids (L1);
   - stamps `epoch`, `generation`, `binding`, `ready` (the application's
     readiness verdict from step 5) and `valid_until`;
   - checks, with a deferred constraint trigger, that Σ new grants ≤ the
     headroom it computed;
8. COMMIT.

This is **not** mode a byte for byte (architecture H2(g)). It runs mode a's
snapshot statement byte for byte, and decides with mode a's rules, inside a
different transaction: the drain fence, the event's scope-row locks, the
insert, then the regrant.

If a fast path escalates twice for the same event, the slow path is final.

### 4.5 The keeper (one per ingestion node; idempotent)

**Triggering.** It is NOTIFY-driven, like ADR-020's LISTEN pattern
(correctness 12):

- the epoch triggers `pg_notify('storage_escrow', <cause>)`;
- the keeper LISTENs on a **session-scoped** connection, never through a
  transaction-mode pooler (CLAUDE.md);
- while LISTEN is down, it falls back to a bounded 1 s poll of the epoch row,
  with health surfaced (`testinbox_storage_escrow_keeper_listen_degraded`).

**It runs the refill-only slow path** (steps 1, 2, 4 and 7, with no event)
when any of these holds:

- `epoch` or `generation` changed;
- `valid_until` is within 60 s;
- one of its own lanes is below `Q_lane / 2`.

It re-checks after acquiring the fence, so several keepers produce one refill.

**Background scope refill: mitigation (m1), adopted (§9.3).**

- After a `scope_epoch` or `generation` bump, the keeper refills every
  existing scope row, each under its own row lock and in its own transaction,
  in key order.
- On a fixed 5 s cadence, it refills every scope row whose avail is below
  one maximum event (`max raw size × max objects`).
- Inline refills on the fast path should then remain only for rows created by
  the event itself and rows that are near their limit.
- The measured criterion is in §12.3.

**Collection.**

- It deletes the lanes of non-live ingestion node ids: `clean_shutdown`, or a
  heartbeat older than `STALE_HEARTBEAT`.
- It deletes scope rows whose inbox or workspace is gone, each under its row
  lock. Test T-C12 covers the race with a fast path.

**Warming.** Before taking the fence, it executes the snapshot statement once,
unlocked, discarding the result. A plan invalidated by autovacuum is then
rebuilt outside the critical section in the common case. This reduces
in-lock planning; it does not remove it (RS-5).

**Liveness** (L1): `testinbox_storage_escrow_keeper_refill_total{outcome}` and
`testinbox_storage_escrow_keeper_last_success_seconds`, with an alert. A dead
keeper costs speed, not safety: a stale lane escalates and the slow path
regrants.

**Placement** (architecture M2): the scheduler lives outside
`email.testinbox.ingestion.config..` and calls the use case
`RefillStorageEscrow` (§7).

**Frequency [estimate].**

| Trigger | Cadence |
|---|---|
| Lane-epoch bumps | observations (assumed ≥ 1 min apart); trust, watermark and node changes (rare); rule (P) writes (rare when healthy) |
| `valid_until` expiry | every ≤ 4.5 min |
| Low-water | minutes to hours at beta volumes |

### 4.6 The epochs: what bumps them (database triggers only)

Triggers are `AFTER` triggers (definer). They bump **only when a value
actually changes**: transition tables for statement-level triggers, `WHEN
(OLD.x IS DISTINCT FROM NEW.x)` for row-level ones. For example, the
compactor's `UPDATE storage_debt_watermark SET … greatest(…)` runs every 5 s
and must bump only when the watermark rises. `INSERT … ON CONFLICT DO NOTHING`
that inserts nothing does not bump.

Each bump also increments `storage_admission_epoch_bumps(cause)` and
`pg_notify`s.

**Coverage proof (correctness 7).** Every input of mode a's ceilings, of rules
(G), (C) and (P), and of readiness R1–R4, with its writers and what covers
each:

| Input | Writers | Covered by |
|---|---|---|
| `U` / `L` (base, delta, reservation) | T1 | the spend itself (E8) |
| | T2, compactor | transfers: `U` and `L` unchanged |
| | retention cascade | `L → D`, or `U → C` for shares: neutral for (C) and E3, decreasing for (G), E1, E2 |
| | release | `L → D` (neutral) |
| | reconciliation repair, `storage_account_recompute` | **`storage_t1_fence(true)` as the first statement, then `storage_escrow_revoke('repair')` as the last write, in the same transaction** (architecture M1a; lock order per §4.8). It is stricter than relying on V24's trust bump, which covers workspace-scope drift only |
| `D` (debt rows) | retention, release | transfer (above) |
| | rule (P) pending rows | `epoch` + `scope_epoch` |
| | debt compaction | superseded by `W` (annex §2.4); the watermark bump covers validity |
| `C_W` (charges) | V20 trigger | transfer `U → C` |
| | operator pool reserve | V25 S1 fence (now draining) + `scope_epoch` |
| | charge release | a decrease |
| `W`, observation validity | monitor INSERT | `epoch` |
| Trust, `distrusted_seq` | verifier; repair (V24 raises distrust in its own function — pinned as an invariant); V9 stamps | `epoch` |
| Watermark | compactor | `epoch` (on change only) |
| Plan, binding, fingerprint, allowances | operator reserve/revoke | `epoch` + `scope_epoch` |
| Shares (`participant_workspace`, `storage_legacy_share`) | beta admission, operator reserve | `epoch` + `scope_epoch` |
| Partition bound, `storage_escrow_policy` | operator reserve | `epoch` |
| `unknown` (R1) | `workspace` INSERT/DELETE | `epoch` |
| `nodesReady` | `storage_node` INSERT/DELETE, UPDATE of capability, cohort mode, binding, `clean_shutdown` or `admission_accounting` | `epoch` (never per heartbeat) |
| Heartbeat freshness, observation age | time | `valid_until` |
| Latch | `storage_admission_latch` INSERT/DELETE | `epoch` |
| Lifecycle eligibility (`Inactive`) | `human_account.state`, `beta_admission` | `scope_epoch` |
| Configured policy | node configuration | `binding` |

**Inbox rows** depend only on `generation`, `binding` and their limit, never
on either epoch. So no bump makes inbox rows stale.

**Tenant reachability of every epoch writer** (security 6):

| Writer | Who can reach it |
|---|---|
| Monitor; operator commands (reserve, revoke, lifecycle, bootstrap `ensureWorkspace`); compactor; verifier; repair; node registration | not tenant-callable |
| Self-service beta admission (`HumanAccountController` → `AdmitBetaParticipant`): inserts `workspace`, `beta_admission`, `participant_workspace` | callable by a signed-in human, but only while the operator has opened admissions; ≤ 30 places ever; one per account; a repeated request writes nothing and so bumps nothing |
| Rule (P) writes (witness probes, orphan sweep, ambiguity verifier) | driven by storage-path behaviour: ambiguous uploads, the breaker's 15 s–2 min backoff. Never by message content (abuse model). A tenant cannot make them happen at will; load-induced timeouts are bounded by the breaker cadence |

Test T-C13 pins this table: every writer of a trigger table that a tenant
role can reach is listed, and is bounded.

### 4.7 The fences: every existing `(35, 1)` holder

| Holder today | Under SLE | Why that suffices |
|---|---|---|
| Mode a T1 | slow path, `storage_t1_fence(true)` | exact decisions need every grant reclaimed |
| Rule (P) (`JdbcRowFreeDebtStore`, ingestion and API) | `(35, 1)` only; its rule counts `storage_escrow_lane_footprint_total()` (§6.1); its pending row bumps both epochs | `L + Σ g` is invariant under T1, so one snapshot suffices |
| Lifecycle (`lockStorageFence`) | `storage_t1_fence(true)` | it must see every reservation of the workspace |
| Pool reserve/revoke, beta admission, V25 S1 share-table fence | `storage_t1_fence(true)`, by replacing the V25 trigger function body | reserve reads "the same live global snapshot as T1" |
| V21 node fence trigger (every heartbeat) | `(35, 1)` only; `storage_escrow_revoke('mixed_version')` **only** when a registering or updating row has `admission_accounting IS NULL` (§6.5) | a latch committed a few ms before an in-flight fast path commits is equivalent to that T1 committing first; no usage is read |

### 4.8 Lock order, isolation, timeouts, deadlock freedom

**Canonical order**, extending ADR-035 §6:

1. ADR-033 claim;
2. ADR-027 `guardAdmission`;
3. (deferral design's `ingest_allowance` rows — **only if #143 is accepted;
   not part of this amendment**);
4. `(35, 1)`;
5. lanes, by `(node_id, lane)`;
6. scope rows, by `(kind, scope_id)`;
7. `inbox` reads for ownership (no lock);
8. `storage_reservation` rows, ascending;
9. `inbox` `FOR KEY SHARE`;
10. `inbox_storage`;
11. `message`;
12. `attachment`;
13. `(35, 2)`;
14. **the epoch row, last** (correctness 5).

**The epoch row's rule.** Every epoch bump takes a row lock on the one epoch
row, from many writers, including A′ and OFF code once V28 is applied. So:

- a transaction that will later take `(35, 1)` or any lane must take `(35, 1)`
  **before** its first epoch-bumping write;
- a transaction that holds the epoch row and then takes lanes is allowed only
  while it holds `(35, 1)`.

**The audit behind that rule** (both artifacts, A′ and SLE):

- beta admission takes `beta_cohort FOR UPDATE`, then `(35, 1)`
  (`JdbcBetaAdmissions.lockPolicy`), and under SLE then calls
  `storage_t1_fence(true)` **immediately**, before its `workspace` INSERT
  (an epoch-bumping write). Its order is therefore 4, 5, 14, with no exception
  to the canonical rule (correctness confirmation, 637fea7);
- the bootstrap `ensureWorkspace` (`Ops.kt`) takes nothing later;
- rule (P) holds `(35, 1)` before its pending row;
- the compactor holds `(35, 2)` before its watermark write, and takes no
  `(35, 1)` and no lane, so it never needs rank 4 or 5;
- **repair, recompute and the audit's repair transaction** (correctness and
  architecture re-check, edit 1, fix (a)). Today V24 repair and V8 recompute
  take `(35, 2)` first. V24 repair also writes distrust, an epoch bump, before
  it returns. A revoke after that would give `(35, 2)` → epoch row →
  `(35, 1)`, while rule (P) holds `(35, 1)` and then bumps the epoch row: a
  **cycle**. So under V28:
  - repair, recompute and the audit's repair transaction call
    `storage_t1_fence(true)` as their **first** statement, before `(35, 2)` and
    before any epoch-bumping write;
  - the generation bump of `storage_escrow_revoke` is their **last** write.
    Revoke is split into the drain (the fence, taken first) and the bump
    (taken last);
  - the audit's violation path never revokes inside the read-only audit. It
    runs its revoke and distrust in a **fresh transaction**, in canonical
    order: fence → `(35, 2)` → re-check the violation → distrust → revoke;
- the V21 node fence's mixed-version revoke holds `(35, 1)` (the trigger takes
  it first) and drains before its bump;
- the fast path and the keeper's refills read the epoch row without a lock.

**Deadlock freedom.** Every locker of an escrow lock, of `(35, 1)`, of
`(35, 2)` or of the epoch row takes them in canonical order: it skips ranks,
and never takes a lower rank after a higher one.

| Locker | Ranks taken, in order |
|---|---|
| fast path | 5 (one lane), 6 |
| slow path, keeper | 4, all of 5, 6 |
| scope refills | only rows already held at 6 |
| fences (lifecycle, reserve, V25 share fence, beta admission) | 4, 5, then their own writes; any epoch bump last (14) |
| rule (P) | 4, then 14 |
| repair, recompute, audit repair | 4, 5, then 13, then 14 (distrust, then the generation bump) |
| compactor | 13, then 14 |
| V21 node fence (mixed-version revoke) | 4, 5, then 14 |
- T2, retention, the compactor and cleanup take no escrow lock.
- The reservation insert's FK `KEY SHARE` on `workspace` conflicts only with
  deleting the workspace, which erasure does after its fence.
- **T-C6** proves this across every pair, under both A′ and escrow code,
  including rule (P), the observation insert, `storage_repair_ledger`,
  workspace provisioning and beta admission. It also covers rule (P)
  concurrent with repair + revoke, and with recompute. A reversed-order
  mutant must deadlock, and so must a mutant that takes `(35, 2)` before the
  fence.

**Isolation:** READ COMMITTED only, enforced in every escrow function (P2 of
#136).

**Timeouts.**

| Where | Bound | On expiry |
|---|---|---|
| Fast path lock statement | `L_w` = 250 ms | `Escalated(scope_wait)`, then the slow path. Never a `451`: one scope's wait depends on that scope's load (A3 of #136) |
| Fast path admit + COMMIT | mode a's `statement_timeout` | `451` (`LOCK_TIMEOUT` class), as mode a |
| Slow path fence | mode a's 5 s `lock_timeout` | `451 LOCK_TIMEOUT`. It is global: while it waits, its held lanes block every fast path, as a stalled mode a `(35, 1)` holder does |
| `statement_timeout`, idle-in-transaction | unchanged | unchanged |

**Slow path latency** [estimate]: drain ≤ 5 ms, plus the mode a statement
(9–13 ms, +12.8–14.3 ms if re-planned), plus insert and regrant (≈ 4 ms) ⇒
**≈ 20–35 ms, p99 ≈ 50 ms**. It must stay below 1 % of events (S4).

**Fairness.**

- A short lane never refuses.
- Refill gives each live lane the same quantum.
- An event whose priced envelope exceeds `Q_lane` always takes the slow path.
  That is decided by its own shape.

---

## 5. Conservativeness: invariants, and where each is enforced

**Notation.**

- `U_s` = committed + reserved payload of scope s, `N_s` its objects.
- `C_W` = the attributed cleanup charge.
- `L`, `D_k`, `W_k` are as in annex §2.4.
- `g` = lane grants; `a_s` = the scope grant.
- **Spendable** = epoch (lanes), `scope_epoch` (workspaces), generation,
  binding, `escrow_enabled` and validity all current.

| # | Invariant (at every commit) | Enforced by |
|---|---|---|
| **E1** | `U_I + a_I ≤ lim_I` for every spendable inbox row | refill sets `a = lim − U` in one statement, after the lock (definer); spends only decrease `a` (definer, `CHECK ≥ 0`); every non-T1 change of `U_I` is a transfer, a decrease, or a repair that revokes |
| **E2** | `U_W + a^P_W ≤ lim^P_W` | the same |
| **E3** | `F(U_W + C_W) + a^F_W ≤ F_W` | the same; a rule (P) charge bumps `scope_epoch` |
| **E4** | `L_P + Σ g^P ≤ G − H` | global refill **after** the event's insert, under the drain fence (definer, H1); only fast-path spends touch `g` otherwise |
| **E5** | `F(L) + Σ g^F + H_F ≤ G_F` (rule G) | the same |
| **E6** | `F(L + D_k) + Σ g^F + W_k + H_F + P_F + M + R_ops ≤ C_fs` (rule C), k = the lanes' observation | the same; Lemma 1 for every committed k |
| **E7** | every rule (P) write satisfies `F(L + D + x) + Σ g^F + W + H_F + M + R_ops ≤ C_fs` | rule (P) reads `storage_escrow_lane_footprint_total()` in its one statement under `(35, 1)` |
| **E8** | every spend ≥ the load of the reservations inserted with it (payload exact, footprint Σ `F(c_i)`) | `storage_escrow_admit` derives it. **The ingestion role keeps `INSERT` on `storage_reservation`** for the slow path and for rollback to A′, so skipping `storage_escrow_admit` is **detected** by the audit of E1–E6, not **prevented** (security 3.2) |
| **E9** | only definer functions write escrow rows; no application role holds DML on them | `roles.reference.sql`, gate F's privilege query |
| **E10** | a spend requires spendable rows, read **in `storage_escrow_admit`'s own statement** | `WHERE` clause of that statement (correctness 1) |

**Theorem S (safety).** If E1–E10 hold, every commit satisfies mode a's
ceilings (inbox, workspace, global `G − H`) and rules (G), (C) and (P).

*Sketch.*

1. A fast path moves load `c` from grants to reservations.
2. By Lemma 2 (`F(S + c) − F(S) ≤ F(c)`) and E8, no left-hand side of E1–E6
   increases.
3. The slow path inserts **before** it regrants (H1), so its refill's truth
   contains `c`.
4. The input-coverage table (§4.6) shows that every other increase is either
   charged (T1, rule (P)), revoked (repair), or an observation. For the
   observation, Lemma 1 holds for every committed k, and the epoch stops
   further spending of grants made under the older k.
5. What remains is a fast path that read spendable rows inside its admit
   statement before a bump, and committed after it. That spends a grant valid
   under the prior inputs, which Lemma 1 covers.

**Theorem D (decision equivalence).**

- Every copy the fast path admits, mode a admits on the truth at the event's
  serialization point. Grants are ≤ exact headroom, and on exact rows mode a's
  own formula is used.
- Every copy it refuses, mode a refuses with the same reason. A refusal needs
  an exact row and mode a's formula. Narrower scopes were admitted either by
  grants (≤ truth) or by mode a's formula.
- Missing or foreign inboxes fail closed as mode a's.
- Everything else escalates to the slow path, which decides as mode a.

So the outcome classes (`250` admitted; `250` refused with reason; `451`
class) are mode a's. That is the basis of §9.1.

**Mode a's own overshoot is preserved, not widened.** A rule (P) charge that
takes a share over budget bumps both epochs. In-flight fast paths add at most
their spendable grant, which equals a mode a T1 serialized just before rule
(P). The next T1 escalates and fails closed (`COHORT_BUDGET_EXHAUSTED`) as in
mode a.

**P's holes (#136), checked here:**

- **P1 (orphan debt).** SLE does not derive the global bound from the shares,
  and E4–E6 count all of `D`. A share check that misses such a debt row is as
  wrong in mode a (RS-6).
- **P2.** READ COMMITTED only, with drains through row locks.
- **The deferred-constraint lesson (V25 S2).** No commit-time aggregate is
  run on the fast path. Grants increase only in refill functions that compute
  from truth in the same statement, and a deferred constraint checks Σ new lane
  grants ≤ the computed headroom (§4.4). The audit covers the rest.

---

## 6. The existing guarantees, preserved

### 6.1 Pending-cleanup debt (rule P)

**Rule (P)** becomes `F(L + D + x) + Σ g^F + W + H_F + M + R_ops ≤ C_fs` (E7).

- Outstanding lane grants count as live. `L + Σ g` is invariant under T1, so
  rule (P)'s one statement needs no lane lock.
- The refill keeps `P_F` out of every grant, so probes stay admissible while
  copies are refused.
- This changes the formula of the Accepted annex's rule (P), which is listed in
  ADR-038.

**Every pending row bumps both epochs.** An attributed charge can flip R2 (a
share over budget). Unattributed debt can flip R4 (its allowance). Under the
bump:

- a lane whose `ready` was stamped earlier is no longer spendable;
- the slow path re-evaluates R2/R4 in mode a's statement;
- workspace rows refill against the new charge.

**Workspace charges from retention and release** are transfers `U → C`:
neutral for E3, so no bump. **Charge release** by a clean observation lowers
`C_W`.

### 6.2 The lifecycle drain fence

`lockStorageFence()` calls `storage_t1_fence(true)`.

- Every in-flight fast path holds a lane, so it has committed or rolled back
  before the lifecycle step reads `reservationsHeld(workspace)`.
- The state change bumps `scope_epoch`. The next fast path for that workspace
  refills its row inline (or the keeper does first), reads
  `workspace_lifecycle_eligibility`, and stamps `active = false`, `a = 0`. The
  copy takes TI-DELIVERY-002's distinct `Inactive` outcome.

**Older operator lifecycle images** run the fence on `(35, 1)` alone. They are
covered by the rollback floor and the lifecycle-image check (§6.5).

### 6.3 Trust and reconciliation (I8)

`ReconcileStorageAccounting` gains `storage_escrow_audit()`. It is one
statement, from one snapshot, under the compactor's lock. A fast path commits
its spend and its reservations atomically, so the snapshot sees both or
neither.

**What it checks,** for every spendable row:

- E1–E3, against the authoritative figures: the ADR-027 §5 derivation, plus
  reservations and charges;
- E4–E6, against `L`, `D` and `W`.

**The limits it compares against** are the ones the reconciler derives from
its own `EffectiveStoragePolicy`, the share rows and `storage_escrow_policy`.
It **never** uses the limits stamped on the row, so the audit is not circular
(architecture M1b).

**On a violation**, the audit (read-only) ends, and its violation path runs a
**fresh transaction** in canonical order (§4.8):

1. `storage_t1_fence(true)` (drain), then `(35, 2)`;
2. re-check the violation under those locks; if it no longer holds, commit
   nothing and report;
3. stamp a distrust (`storage_footprint_trust`). Under `ALL`, admission then
   answers **`451` before recipient resolution** (`UNTRUSTED`) until a clean
   reconciliation and an observation begun after the distrust (annex §4.5),
   exactly as for ledger drift;
4. as the **last** write, `storage_escrow_revoke('drift')`: the generation
   bump, so no grant is spendable.

Also:

- `testinbox_storage_escrow_violation_total{scope}` pages;
- `storage_confirm_footprint_trust()` refuses to mark trust while the audit
  fails;
- no API role can call `storage_escrow_revoke` directly (security 3.3), so a
  compromised API process cannot force a global `451`;
- operators see grants only as metrics with closed labels (§13.4). There is no
  REST surface. Any future one would start as an `openapi.yaml` diff
  (ADR-022; L4).

### 6.4 Restart and restore

| Event | Effect |
|---|---|
| Crash or connection loss mid fast path | PostgreSQL rolls back; grants and reservations unchanged |
| Crash mid refill | one transaction: the old grants stand, consistent with the old truth |
| Node death | its lanes hold stranded grants: headroom withheld, never over-admitted. The keeper deletes them once the node is not live. A zombie of that node then finds no lane and escalates. C-Q counts these lanes until they are deleted (§9.4) |
| Restart with the same node id | `StorageNodeClaims` keeps the id unique among live processes |
| Restore from backup | the escrow tables are `-` in `deploy/backup/scope.txt` and in `REQUIRED_DENY` (§13.3), so they are empty after a restore. The epoch row is seeded fresh from a sequence. The first T1 escalates, and the slow path refills from the restored truth. No grant survives a restore. The existing restore procedure (latch until a full orphan sweep; V22 quarantine) is unchanged; the latch bumps `epoch` |

### 6.5 Rollback boundaries and OFF exactness

**V28 is expand-only.** It:

- creates five tables (three escrow tables, the policy row and the bump
  counter), a sequence and the functions;
- adds one nullable column to `storage_node`;
- adds the epoch triggers;
- replaces three trigger or function bodies, by `CREATE OR REPLACE` as V24
  did:
  - V25's share fence, which now drains;
  - V21's node fence, which now revokes on NULL capability;
  - the repair and recompute functions, which now take the drain fence first
    and revoke last (§4.8).

Nothing is dropped, renamed or narrowed, and it takes a 2 s `lock_timeout`
(V27 precedent).

**V28 changes behaviour for every older artifact the moment the migrator runs**
(architecture M5):

- the V21 node fence now drains, revokes and disables;
- the V25 share fence now drains;
- the epoch triggers write one row;
- the repair and recompute functions take `storage_t1_fence(true)` first and
  revoke last.

`docs/dev/rollback.md` gains a "V28" section, like V25–V27: what an older
artifact meets, and "partial migration is roll forward only". B-1′ tests A′
under `ALL` on V28 (§11.5).

**OFF and TENANT_LIMITS: T1 and the ledger byte for byte** (correctness 8).

- `ESCROW` is selectable only under `ALL` (`DeploymentSafety`). Under `OFF`
  no escrow row is written, and T1's transaction and statement and the ledger
  and its definer functions are unchanged. §A2.8's "OFF rollback stays exact"
  holds for the ledger.
- **What is not byte for byte under OFF:** epoch-input writers also update the
  epoch row, and the replaced V21, V25 and repair bodies run under `OFF` and
  under A′.

**Mixed versions** (architecture H3, correctness 6):

- **Who must declare.** Every new artifact, API and ingestion alike, registers
  `admission_accounting = 'escrow-v1'`. Its rule (P) counts Σ g, and its
  lifecycle fence drains. Lanes exist only for ingestion node ids, so a new API
  never disables escrow and never adds lanes.
- **What NULL triggers.** A row with `admission_accounting IS NULL` comes from
  an older binary of **either** role. The replaced V21 trigger, which runs in
  the registering or updating transaction whatever binary wrote it, then calls
  `storage_escrow_revoke('mixed_version')`: drain, generation bump,
  `escrow_enabled = false`.
- **While it lasts.** The refill functions refuse to grant while any row that
  is not `clean_shutdown` has NULL. That includes stale rows, so a zombie
  cannot reappear after a refill. Escrow nodes then decide every event on the
  slow path, which is mode a's decision, serialized with older nodes on
  `(35, 1)`.
- **The unproven ordering.** An older API's registration (phase
  `DEFAULT_PHASE − 100`, `ApiStorageNodeRuntime`) is not proven to precede its
  first lifecycle fence or rule (P) write. Older **operator** artifacts
  (`account-lifecycle`, `storage-budget`) register no node at all. So **a
  rollback floor is required** (architecture H3), following the `006c468…`
  and `edabf29…` discipline:
  - a `deploy/rollback-floors.txt` line at the V28 artifact;
  - the Ops step: "set `testinbox.storage.admission-accounting=GLOBAL` on every
    node and confirm `escrow_enabled = false` before rolling any deployable,
    or the operator image (`scripts/check-lifecycle-image.sh`), back below
    this commit".
- **The evidence.** T-C9 is extended with an A′ API registration, an A′
  lifecycle fence and A′ rule (P).

---

## 7. Invariant 8 and ADR-024

**Invariant 8 holds in substance** (architecture M1).

- **Usage is untouched.** `U`, `L`, `D` and `C` remain the V6–V27 ledger,
  bases and in-flight rows, written by triggers and definer functions and
  reconciled as today.
- **A grant is headroom, not usage**, a lower bound of `limit − usage`:
  - a cascade delete lowers usage, which makes every grant more conservative;
  - every increase is computed by definer code from the authoritative rows,
    after the locks;
  - every decrease is derived by definer code from the reservations it
    inserts;
  - no application role writes one;
  - grants are audited against non-stamped limits;
  - a non-transfer increase of usage invalidates them (repair revokes; rule (P)
    bumps).

**For the owner, not a team edit.** ADR-038 carries the proposed sentence for
CLAUDE.md invariant 8, for the owner to decide. This design does not edit
CLAUDE.md.

**ADR-024 placement** (architecture M2, M3):

| Layer | What lives there |
|---|---|
| domain | `EscrowFit`: does a copy fit these grants; the full-envelope prefix. Pure arithmetic beside `FootprintAdmission` |
| application | `StorageAdmission` **owns the sequence**: fast path → escalation → slow path. One port: `StorageEscrowStore.tryAdmit(scope, slot, decide: (EscrowView) -> EscrowPlan): Decided \| Escalated(reason)`, where `reason` is a closed enum (`lane_short`, `lane_stale`, `lane_missing`, `not_ready`, `scope_wait`, `spend_mismatch`, `binding_mismatch`). The slow path stays behind `StorageAdmissionStore`, with the regrant as the last step of its transaction (§4.4). Refill-only regrants go through the use case `RefillStorageEscrow`, called by the keeper |
| persistence | `JdbcEscrowAdmission` maps SQLSTATE `TI001` and the `L_w` timeout to `Escalated`, and decides nothing. No SQLSTATE or JDBC type crosses the port. `JdbcStorageAdmission` is constructed by `IngestionWiring` and injected, so `PartitionedT1RuleTest`'s `CONSTRUCTION_SITES` stays intact |

**How the slow path is built** (architecture re-check, items 2 and 3; before
M1):

- `JdbcStorageAdmission` gains a **constructor-injected escrow strategy**,
  `null` under `GLOBAL`. Under `ESCROW` it supplies the drain fence in place of
  `acquireAdmissionLock`, the scope-row locks, and the regrant after the
  insert. Under `GLOBAL` and `OFF`, with the strategy `null`, nothing changes.
- **A test records the full SQL sequence of the T1 transaction under `GLOBAL`
  and `OFF`** through a JDBC proxy: every statement, in order, including the
  `SET`s, the lock, the snapshot, the insert and the COMMIT. It requires that
  sequence to be byte-identical to today's, not only the snapshot statement's
  text (`AdmissionStatementTextTest` pins only the latter).
- `acquireAdmissionLock`, and every new protected hook the strategy uses, are
  added to the `PartitionedT1RuleTest`-style rule that forbids a main-code
  subclass from overriding them.
- **The global regrant** is one definer function. Its SQL text is a constant in
  exactly one object (`EscrowSql.REGRANT`). An ArchUnit field-access rule allows
  only the two named classes to read it: the slow-path strategy, within T1's own
  invariant-bearing write, and the adapter behind `RefillStorageEscrow`.

**ArchUnit rules,** each with a rogue fixture, as `PartitionedT1RuleTest`:

- only `DeploymentSafety`, the wiring and the benchmark name `ESCROW`;
- only `StorageAdmission` and `RefillStorageEscrow` call `StorageEscrowStore`,
  so the API cannot reach spend or refill;
- `JdbcEscrowAdmission` has fixed construction sites;
- no main-code subclass overrides the fence or the spend;
- the new metric ports carry no caller-controlled value (`DependencyRuleTest`).

**One use case.** ADR-037's "one storage T1/T2 writer" holds: both paths are
inside the one `StorageAdmission` use case.

---

## 8. Fresh traffic, deferred retries and backlog recovery, together

This is written against `docs/dev/ingest-deferral-allowance-design.md`
(`app/fasttrack-ingest-refund`, `f1e63b1`, **not accepted**; architecture M6).
Every coupling below applies **only if that design is accepted**, and is not
part of ADR-038. In particular, R_T is not an ADR-035 term until #143 is
accepted.

**T1 is class-blind.** Fresh, catch-up and backlog events run the same fast
path. SLE changes no scheduling rule. The T-class set is unchanged:
escalations are internal, and `LOCK_TIMEOUT` remains the slow path's global
fence timeout. If the allowance consume lands, it stays the first write of the
T1 transaction (I-19). On escalation the fast-path transaction rolls back,
restoring the hold, and the slow path consumes it again.

**Scheduling tokens (OD-5).** The owner's decision 2 (#124 comment
6684756433) counts **every T1 attempt, including those that certainly roll
back**. An escalated fast attempt is exactly such an attempt.

- **The text must say it:** counting the slow path as the same attempt, with no
  second token, **departs from owner decision 2** (correctness 10).
- **The case for departing:** the event is one admission attempt, and a second
  spend after the slot would break I-21's fresh-conformance argument by up to
  the escalation rate × T.
- **Both options are sound only if stated as follows** (correctness Q3):
  - **(i) Two tokens per escalated event** (decision 2 literally). This needs a
    restated I-21, plus an explicit escalation allowance `(1 + e_max)` in σ′
    and in the fresh rate, with G-D evidence at that attempt rate. A sender of
    large envelopes can double its own token demand, because envelopes above
    `Q_lane` always escalate.
  - **(ii) One token per event** (departs from decision 2). The escalated fast
    attempt holds no global lock, and the slow path is counted once. But the
    escalation rate then becomes a **runtime alarm**
    (`testinbox_storage_escrow_escalation_total` against `e_max`, paging), not
    only the prototype's S4.
- **The choice is the owner's.**

**Bursts after an outage.** B = σ′ + B_c = 5 890 attempts at ≤ 16 concurrent
per node. At 24 KiB × 4.05 copies that consumes ≈ 560 MiB of payload, ≈ 35 MiB
per lane, below the low-water mark, so no refill is needed [derived/estimate].
A workspace's own backlog drains through its scope row, and refuses exactly as
mode a under the uniform `250`.

**RL-3** (only if #143 is accepted). If SLE's evidence passes at 62/s, and at
124/s should amendment D's 2R rule apply to R_T, the owner could raise R_T to
62. That is OD-6, and it needs G-C at B_c = 7 440 and G-E for
`t_s ≤ 20/62 ≈ 0.32 s`. With I-23's formulas, L − M = 12 600 s and
P′ ≈ 1 196 s [derived]:

| ρ̄_f | Drain | Largest D, spread | Largest D, synchronized |
|---|---|---|---|
| 0 | 62/s | ≈ 126 min | ≈ 31 min |
| 15.5/s | 46.5/s | ≈ 114 min | ≈ 31 min |
| **31/s (full fresh envelope)** | **31/s** | **≈ 94 min** | **≈ 31 min** |

At R_T = R, RL-3 stands exactly as written.

---

## 9. Privacy

### 9.1 Outcomes and replies: nothing new

- By Theorem D, every per-copy decision and every `451` class is mode a's.
- The fast path never refuses on a grant.
- A scope wait beyond `L_w` escalates to the global fence, whose timeout is
  global (A3 of #136, kept).
- **I9 holds as written for replies.** The accepted exception (write slots, its
  two consequences) and the deferral design's retry-timing extension (if
  accepted) are unchanged. SLE adds no deferral cause and no retry, so that
  model's K is untouched.

### 9.2 Timing channels

The observer is an authenticated participant timing its own co-addressed copy,
as in the accepted exception. External senders read nothing: the edge stores
and forwards (S-C2).

| # | Channel | Depends on | Magnitude [estimate] | Status |
|---|---|---|---|---|
| N1 | wait on a workspace or inbox **scope row** held by another in-flight T1 | that scope's concurrent T1 activity | typically one hold, 2–5 ms; capped at `L_w` = 250 ms before escalation | **Genuinely new** (confirmed by security). Its threshold is a single concurrent T1, finer than the exception's "at the slot cap of 4 or not". It is the same kind as the partitioned residual, which is excluded by name |
| N2 | an **inline scope refill** | the scope near its limit, newly created, or not yet refilled by the keeper since a `scope_epoch` bump | +0.3–1 ms | **Genuinely new**, the same kind as N1. (m1) confines it mostly to near-limit scopes |
| N3 | **lane escalation or global refill** | aggregate consumption since the last refill; lane-epoch bumps (global events); the event's own priced envelope. Global headroom only if C-Q fails | +10–30 ms | **Not per tenant**: the same kind as mode a's `(35, 1)` wait. Under C-Q it carries aggregate load only. Without C-Q it reveals one coarse bit ("global headroom < K × Q_lane"), which would be new and global, and ADR-037 A2 may not be cited for it |
| N4 | outcome and reply classes | — | — | none new (§9.1) |

**Bounds on N1** [estimate].

- *Natural activity:* under amendment D a workspace runs ≤ 1 copy/s, so its row
  is busy at a random probe ≈ 0.5 % of the time. The signal is 2–5 ms against
  API timing noise, and needs many samples.
- *Induced contention:* holding the row busy needs a flood through the target's
  own `INGEST` budget (b = 60, then 1/s). The same flood already yields the far
  larger slot signal (up to `W_slot` = 10 s, or a `451`).
- *Sampling* is bounded by those budgets, as in the exception.

**A pre-existing disclosure, out of scope here.** The security review found a
pre-existing timing disclosure in the current ingest path, in the ADR-025
recipient-existence class, observable only by an authenticated workspace
holder. It is independent of the storage mode and of this design, is out of
this design's scope, and is with the owner. It is also why padding T1 would be
pointless (§9.3).

### 9.3 What the owner is asked (OD-3), and the mitigation adopted

N1 and N2 are not covered by the dated I9 exception, whose decisions exclude
"the separate partitioned-locking residual" and "anything materially broader".
They need the owner's own decision before `ESCROW` carries tenant traffic.

**Adopted: (m1) background refill** (§4.5). It is cheap. It removes N2's
dependence on epoch bumps and on freeing, leaving near-limit scopes and newly
created rows.

**Its measured criterion** (§12.3, G8):

- in the paced probe point, inline refills on scopes **not** within one
  maximum event of their limit are ≤ 0.1 % of events;
- the inside–cold gap meets the PROPOSED note's criterion 8 relative bound
  against mode a under the same load: `gap_SLE ≤ gap_modeA + max(10 ms,
  0.2 × gap_modeA)`, over ≥ 300 pooled target-reached samples per class, with
  `inside 451` and `cold 451` no worse than mode a.

**Rejected:**

- **(m2) constant-time padding of T1.** To hide N1 it would have to cover `L_w`
  (250 ms). And padding T1 is pointless while the pre-existing ingest-path
  disclosure of §9.2 remains.
- **(m3) per-permit workspace lanes.** They fragment small shares
  (128 MiB / 8 lanes = 16 MiB, below one 26 MiB copy), so large messages would
  escalate or ping-pong between lanes.

### 9.4 Condition C-Q

**The rule.** Each live lane is refilled to exactly `Q_lane` while
`headroom ≥ K × Q_lane`. Lane state then depends only on consumption through
it, never on the headroom level.

**The correction (security 1).** `K` must count every lane that can hold a
grant, **including the lanes of dead or replaced node ids until the keeper has
deleted them**. A node replaced under a new id strands its grants for up to
`STALE_HEARTBEAT`, so `K_max = 16 × (P_declared + P_stranded)`:

- `P_declared` = `declared-max-ingestion-processes`, surge included;
- `P_stranded` is declared too, defaulting to `P_declared`, which is one full
  generation stranded.

The refill refuses to create a lane that would take the total over
`K_max × Q_lane`.

**What `DeploymentSafety` refuses.** It refuses `ESCROW` unless the
declarations guarantee `K_max × Q_lane` beyond every share, legacy share, the
worst envelope and finalization:

- payload: `G − H − 30·P − Σ legacy_P − worst_P ≥ K_max × Q_lane_P`;
- footprint: the analogue for `G_F`, and for `C_fs` with its reserves.

Under ADR-037's non-overcommitted shares, headroom then never falls below
`K_max × Q_lane`, and N3 carries aggregate load only.

**Proposed values.** `Q_lane_P` = 256 MiB, which is 8 GiB at
`K_max = 32` (one process plus one stranded generation) [derived]. Whether the
beta's declared G leaves that slack is unknown here (OD-4).

---

## 10. Alternatives considered

| Candidate | Verdict | Why |
|---|---|---|
| **(i) Per-node escrow** (ADR-035 §11's named fallback) | Rejected as named; adapted | With one ingestion node it is one row every T1 updates and holds through its commit: the hot global row. ρ 0.43–0.56 at 124/s [estimate]. SLE is a new mechanism, not an execution of the named fallback; ADR-038 says so |
| **(ii) Striped escrow, K stripes by hash** | Selected, refined | Hash stripes still collide. Slot-indexed lanes cannot. Refill runs under the global lock from ledger truth. Exact scope rows remove the remaining per-event scope aggregation |
| **(iii) Running aggregates maintained by statement triggers** | Rejected for usage; its machinery is used for the epochs | Per-scope or global running rows would be updated by T2 (p99 111–114 ms [measured]), retention (cascade 0.25 s) and the compactor: mode b's failure. Deferring decreases to avoid that **is** escrow |
| **(iv-a) P** | Not reopened | P failed on its per-event cohort statement (15.1–17.2 ms; lock waits ≈ 0). SLE runs no such statement, takes no global lock on its fast path, and does not rely on the partition for the global bound. It shares one residual with P: N1 |
| **(iv-b) Another mode a round, analyze settings** | Excluded by the owner | — |
| **(iv-c) A materialized cohort pool** | Rejected | Still a global hold of at least insert + commit, plus hot-row writes from T2 and retention |
| **(iv-d) `synchronous_commit = off`** | Rejected | §4 |
| **(iv-e) SERIALIZABLE with optimistic retries** | Rejected | Abort storms; C-1 of #136 |
| **(iv-f) Per-workspace quotas only** | Rejected | That is P's partition argument, and it drops I2's global check |
| **(iv-g) Redis or in-memory counters** | Rejected | ADR-006 |

---

## 11. Decisive tests (for reviewer agreement before implementation)

All run against PostgreSQL 16 in Testcontainers. Each concurrency test is
deterministic, using sync hooks and `pg_blocking_pids`, never sleeps. Every
test names the mutant it kills.

### 11.1 Deterministic concurrency

| Test | What it proves | Mutant it kills |
|---|---|---|
| T-C1 | two fast paths on different slots never block each other; no lock on the epoch row (L2): another session's `FOR UPDATE NOWAIT` on it succeeds while a fast path is paused mid-transaction | lane keyed by node only; epoch read `FOR SHARE` |
| T-C2 | fast path ↔ global refill, both orders; E4–E6 after each; the slow path's refill sees its own insert | refill without lane locks; **regrant before the event's insert** (must over-grant by c, H1) |
| T-C3 | lifecycle fence ↔ fast path, both orders: the fence sees the reservation; the next event is `Inactive` | fence without drain |
| T-C4 | rule (P) ↔ fast path, latched between lock and spend: E7 holds | rule (P) without Σ g (must over-admit) |
| T-C5 | observation INSERT racing a fast path: old-epoch spends commit; new ones escalate; Lemma 1 holds against both | epoch trigger dropped |
| T-C6 | every pair among fast path, slow path, keeper, scope refill, lifecycle, pool reserve, beta admission, workspace provisioning, node revoke, rule (P), observation insert, `storage_repair_ledger`, recompute, the audit's violation transaction, T2 and retention, forced into every wait order, under **A′ and escrow code**: 0 deadlocks. Includes rule (P) concurrent with repair + revoke, and with recompute | reversed lock order (must deadlock); epoch row taken before `(35, 1)`; **`(35, 2)` taken before the fence in repair or recompute (must deadlock against rule (P))**; **a revoke without the fence (must be refused by the `testinbox.t1_fence` guard)**; beta admission's `workspace` INSERT before its fence |
| T-C7 | a scope wait > `L_w` escalates; a stalled holder makes the slow path time out globally; never a scope-attributed `451` | `451` on scope timeout |
| T-C8 | `ORDER BY … FOR UPDATE` locks in key order under contention | — (pins PostgreSQL behaviour) |
| T-C9 | mixed versions: A′ ingestion **and A′ API** registration, an A′ lifecycle fence and A′ rule (P) → drain, revoke, disable; decisions equal mode a's; no over-admission across the transition; a new API (`escrow-v1`) never disables | trigger without drain; NULL from the API ignored |
| T-C10 | two fast paths on one workspace row serialize; refusals after inline refill equal mode a's | refusal on a non-exact grant |
| **T-C11** | for **each** writer in §4.6's coverage table, a mutant that commits without its bump or revoke is caught by P-S or P-D | each missing trigger |
| **T-C12** | the keeper's scope-row deletion races a fast path on that row; the fast path escalates or fails closed (ownership), never spends | deletion without the row lock |
| **T-C14** | READ COMMITTED after-lock reads | (a) the scope refill computed **in the locking statement** (must miss the previous holder's reservations); (b) the epoch **passed in from the lock statement** to admit (must spend a revoked grant) |
| **T-C15** | an inbox deleted, or owned by another workspace, with a live scope row: the fast path fails closed as mode a | ownership read from the scope row |

### 11.2 Property tests

**P-S (safety).** Over random interleavings of:

- fast T1, slow T1, T2, retention and release;
- compaction, rule (P), observation and debt compaction;
- keeper refill, scope refill, lifecycle, revoke, repair and crash,

at every commit E1–E7 and mode a's ceilings hold, and physical ≤ Φ_k + late
for every committed k.

**P-D (equivalence)** runs against the **real SQL refill and spend**, not a
model of them (architecture: ADR-024 dual implementation). SLE's outcomes
equal mode a's on the same truth, and fast admissions ⊆ mode a's. It
includes:

- F-boundary truths, and several copies per workspace (correctness 3);
- a deleted inbox and a foreign inbox (correctness 2);
- an exact row whose mode a admission set exceeds its grant by the
  subadditivity gap, which must escalate and then match.

**P-F.** `storage_footprint_bound` equals `FootprintModel.bound`, overflow
edges included, and Σ `F(c_i)` ≥ `F(L + Σc) − F(L)`.

**P-E.** Full-envelope pricing holds: an unknown recipient priced like a
foreign one (ADR-037 A1).

### 11.3 Crash and restart

| Test | What it proves |
|---|---|
| R-1 | a backend killed mid fast path, and mid refill, leaves rows unchanged |
| R-2 | node death: stranded lanes counted in C-Q, then deleted only once the node is not live; a zombie escalates |
| R-3 | restore drill: empty tables; first-T1 escalation; no stale grant |
| R-4 | same-node-id restart (claim) |

### 11.4 Reconciliation mutation tests

| Test | What it proves |
|---|---|
| M-1 | an injected over-grant: detected, revoked, distrusted, `451` under `ALL`; trust not re-markable while violated |
| M-2 | a spend below the reservation load (mutant definer) is refused (E8) and caught by the audit |
| M-3 | a refill reading truth in two statements: the latched under-count is demonstrated |
| M-4 | a slow path not reclaiming grants: a false `SERVICE_CAPACITY` is caught by P-D |
| M-5 | an application role granted DML on escrow rows: gate F fails |
| **M-6** | an upward inbox-scope repair with an outstanding inbox grant must not over-admit (architecture M1a) |
| **M-7** | an audit using the row's stamped limits instead of derived ones (circular) must miss an inflated limit (architecture M1b) |
| **M-8** | ingestion skipping `storage_escrow_admit` and inserting directly: detected by the audit (security 3.2) |

### 11.5 Rollback, migration, configuration, architecture

| Test | What it proves |
|---|---|
| B-1 | A′'s persistence suite on V28, under `OFF`: no escrow row written; ledger exact |
| **B-1′** | A′'s concurrency and lock-order suites under **`ALL`** on V28, including §17 test 32's old-binary path; A′ nodes registered **before** V28 ran; V28 applied with zero lanes (M5) |
| B-2 | `check-migration-safety.sh` accepts V28; its test gains a case for trigger-body replacement; the 2 s `lock_timeout` holds |
| B-3 | `check-backup-scope.sh` (the five tables in `REQUIRED_DENY`); `check-roles-reference.sh`; `StorageRoleModelTest` |
| B-4 | §17 tests 1–55 parameterised over GLOBAL and ESCROW |
| B-5 | `check-storage-enforcement-off.test.sh` refuses a committed `ESCROW` in every spelling: properties, environment variables, `-D` system properties, `SPRING_APPLICATION_JSON` (M4) |
| B-6 | `DeploymentSafety` refuses `ESCROW` with `admission-locking ≠ GLOBAL`, outside `ALL`, without C-Q, or with `L_w ≥ lock_timeout` |
| A-1 | the ArchUnit rules of §7, each with a rogue fixture, including the override rule over `acquireAdmissionLock` and the strategy hooks, and the field-access rule on `EscrowSql.REGRANT` |
| **B-7** | the full SQL sequence of the T1 transaction under `GLOBAL` and `OFF`, recorded through a JDBC proxy, is byte-identical to today's (§7) |
| **B-8** | no policy row, or a binding mismatch: no grant is issued, every event is decided on the slow path as mode a, the alert fires, and no `451` results; a policy write under REPEATABLE READ, or by a non-operator role, is refused |
| **T-C13** | tenant reachability: every writer of an epoch-trigger table reachable by a tenant role is in §4.6's table, and is bounded (security 6) |
| MC-1 | `MetricCardinalityTest` pins `cause` to the `last_cause` enum, and unknown values map to `other` (security 5, L3) |

---

## 12. Bounded prototype and early measurement

### 12.1 The smallest prototype that answers the question (≈ 2 dev days) [estimate]

It is built on this branch, which includes `920ba1d`, behind a
lab/benchmark-only switch.

**In scope:**

- **V28-proto**, applied **by the benchmark harness to a disposable database
  only; never placed in `db/migration`, never merged** (architecture re-check,
  item 2):
  - the escrow tables, the policy row and the epoch row with the observation
    trigger;
  - `storage_escrow_lock`, `storage_escrow_admit`,
    `storage_escrow_refill_scope`, `storage_escrow_refill_global` and
    `storage_t1_fence`, each with the after-lock statement structure
    (correctness 1);
  - the ownership read (correctness 2).
- **`WriteSlots`** exposing the slot index.
- **The application.** `StorageAdmission` owns the sequence, over
  `StorageEscrowStore.tryAdmit` returning `Decided | Escalated`. Its slow path
  inserts before it regrants (H1). `JdbcStorageAdmission` is injected, never
  constructed by the escrow adapter (M3), and receives the escrow strategy by
  constructor; under `GLOBAL` the strategy is `null` and the recorded SQL
  sequence test passes unchanged (§7).
- **`EscrowFit`**, with exact-row refusals by mode a's formula (correctness 3).
- **The keeper**, NOTIFY-driven, with the background scope refill (m1).
- **A stub `t1_schedule` spend** (correctness 11), **benchmark-only code**: one row, its own
  transaction, at its production position after the slot and before T1, with a
  token rate high enough never to refuse. It measures the second global row's
  cost, not scheduling.
- **The ArchUnit rules the prototype touches** (M3): construction sites, the
  `ESCROW` reference rule, and the store's callers.
- **Benchmark:** `--escrow-variant`, reported apart from the gate. It adds:
  - the new steps to `t1Steps`, cold/warm-split;
  - closed-label counters for path and escalation reason;
  - refill counts and durations;
  - scope waits;
  - inline refills by cause.

**Out of scope for the prototype** (in M3):

- rule (P) counting Σ g;
- the lifecycle fence;
- mixed-version revocation;
- the audit;
- the remaining epoch triggers;
- `DeploymentSafety`;
- the deferral allowance.

None of these is on the measured critical path in the focused workload, except
the epoch triggers, which the harness simulates.

### 12.2 Measurement (NON-GATE, labelled like `--focused`)

**Host.** The staging host class, quiet (Ops' contamination check), with a
disposable PostgreSQL running only `max_connections=300`. A separate diagnostic
run adds `pg_stat_statements` with `track_planning`. **No analyze or
autovacuum changes.**

**Workload.** `--focused-cohort` (31 required shares) at **62/s and 124/s**,
concurrency **c10, c25, c50, c100**, 60 s windows.

**The cold path,** using `920ba1d`:

- **The cold pool is the default.**
- `--pool-max-lifetime 30s` forces recycling inside each phase. It is a
  departure from production's 30 min (`AdrConformance.poolLifecycle`), so it is
  focused-only.
- A second pair runs at the production default.
- `--warm-pool` is a diagnostic control only.
- **What we read:**
  - client-side cold-connection plans;
  - `pg_stat_statements` plans and plan time;
  - derived invalidation re-plans;
  - T1 p99 split by cold and warm connection;
  - Hikari connections opened during the phase;
  - `Diagnostics.t1Stats`.

  A plan in the fast path costs about 0.1 ms. One in the slow path or the
  keeper's refill is the 12.8–14.3 ms case. Each is counted where it happens.
- **These figures are conservative.** The scenario pool is c + 6 connections
  (106 at c100), and one deployed ingestion node's pool is 8, so a cold c100
  scenario pays far more cold plans than a deployed node. A failure seen only
  at cold c100 is read with that in mind, and reported with the split, not
  discarded.

**Realism knobs** (each reported):

- the stub `t1_schedule`;
- heartbeats at 10 s;
- a synthetic lane-epoch bump every 60 s;
- a **storm** scenario: a lane-epoch bump every 5 s, **plus a `scope_epoch`
  bump every 30 s followed immediately by 50-recipient events**
  (correctness 4);
- a **fresh-inbox** scenario: 50 % of events to newly created inboxes;
- a **single hot workspace** scenario;
- the paced **probe** point (`inside`/`cold`, criterion 8's procedure) for G8;
- a NO_LOCK reference at the same points, for retention.

**Integrity.**

- `storage_escrow_audit()` runs at the end of every scenario, and is sampled
  every 5 s.
- One scenario uses deliberately small ceilings, so that refusals and
  escalations actually happen, and checks for over-admission.

**Command shape.** `--escrow-variant` is the prototype's flag; the rest exists
at `920ba1d`:

```
storage-benchmark --jdbc-url <disposable db> --username <user> --create-database --out <dir> \
  --focused-cohort --escrow-variant --rates 62,124 --concurrency 10,25,50,100 \
  --duration-seconds 60 --pool-max-lifetime 30s \
  --host-class "<staging host class> (NON-GATE SLE prototype)"
# second pair: without --pool-max-lifetime (production's 30 min)
# control pair: --warm-pool (diagnostic only)
```

### 12.3 Success and stop criteria (decided at the end of day 2)

These are expressed against the unchanged §11 criteria, which stay the gate.

**GO.** Every item, in every scenario, **with the stub `t1_schedule`
included**:

| # | Criterion |
|---|---|
| G1 | T1 p99 (completion − due, including the `t1_schedule` spend) ≤ 50 ms at 62/s and at 124/s, c10–c100, cold pool with `--pool-max-lifetime 30s`, on both the cold- and warm-connection splits; target ≤ 25 ms at 62/s |
| G2 | sustained ≥ 0.995 at both rates |
| G3 | retention p99 ≤ 2 × NO_LOCK at the same point |
| G4 | 0 deadlocks; `lock_timeout` < 0.1 % (target 0) |
| G5 | audit clean; 0 over-admissions |
| G6 | fast-path share ≥ 99 % outside the storm; escalation p99 ≤ 50 ms; global refill p99 ≤ 40 ms |
| G7 | mean T1 transaction ≤ 6 ms (tight, §3.3) |
| G8 | (m1)'s criterion of §9.3: off-limit inline refills ≤ 0.1 % of events, and the probe gap within criterion 8's relative bound |

**STOP: reject SLE, back to the owner.** Any one of:

| # | Criterion |
|---|---|
| S1 | T1 p99 > 50 ms at **62/s** in any cold-pool scenario not flagged as contamination, with its cause in SLE's own path (fast path, escalation or refill) or in the `t1_schedule` row, not in T2 or retention |
| S2 | any audit violation, over-admission or deadlock |
| S3 | mean T1 transaction > 8 ms |
| S4 | escalations > 1 % of events outside the storm |

**INCONCLUSIVE.** 62/s passes but 124/s fails G1 or G2, or G8 fails alone.
Report it. SLE then qualifies at most R_T = R (OD-6 falls away), or OD-3 has no
mitigation evidence, and the owner decides.

**Why two days suffice.** Every stop criterion is about the fast path, the
escalation rate, the second global row or correctness, and the prototype has
all of them on the measured path, cold connections included. What it omits
adds writes only off that path, or about 0.1 ms.

---

## 13. Changes required

### 13.1 ADR

**The ADR change is ADR-038, Proposed:**
[`docs/adr/0038-slot-lane-escrow-admission.md`](../adr/0038-slot-lane-escrow-admission.md).

- It amends, by reference only and never in place:
  - ADR-035 §1 I2;
  - §4 (T1, "Why one statement", "The lock");
  - §6 (lock order);
  - §10 (placement, carve-out);
  - §11;
  - §14, §16, §17 and §18;
  - Amendment 2 §A2.1;
  - the annex's rules (C) and (P), and its §2.4 transitions;
  - the gate D note;
  - ADR-035's Alternatives;
  - ADR-037's implementation gates.
- It states that **its acceptance, not §11's naming of per-node escrow, is the
  authority**.
- It records the NO_LOCK verdict at 62/s.
- It carries the invariant-8 sentence for the owner.

On acceptance, only dated pointers would be added to ADR-035 and ADR-037.

### 13.2 Grants (`deploy/database/roles.reference.sql`; its sha changes)

| Role | Grants |
|---|---|
| Ingestion | `SELECT` on the lane, scope and epoch tables and on `storage_admission_epoch_bumps`; `EXECUTE` on `storage_escrow_lock`, `storage_escrow_admit`, `storage_escrow_refill_scope`, `storage_escrow_refill_global`, `storage_t1_fence`, `storage_escrow_lane_footprint_total`. No DML. `INSERT` on `storage_reservation` is kept (E8 note) |
| API | **no `SELECT` on any escrow table**; `EXECUTE` on `storage_escrow_audit` (whose violation path revokes by itself) and `storage_escrow_lane_footprint_total` (rule (P)). **No `EXECUTE` on `storage_escrow_revoke`** (security 3.3). The operator lifecycle image uses operator credentials for `storage_t1_fence` |
| Monitor | nothing new; its INSERT fires the definer trigger |
| Operator (pool reserve) | `EXECUTE` on `storage_escrow_set_policy` only (no table DML), called inside the reserve transaction under the S1 fence; the API and ingestion roles hold no `EXECUTE` on it |

- Gate F's privilege query adds the five tables to "no
  `INSERT/UPDATE/DELETE/TRUNCATE/TRIGGER` for any application role".
- `StorageRoleModelTest` and `check-roles-reference.test.sh` gain cases.
- Ops re-runs the file after the migrator, in `production.md` order.

### 13.3 Backup scope

`deploy/backup/scope.txt`:

```
# Slot-lane escrow (V28, ADR-038 Proposed): derived headroom, an input epoch,
# its counters and the escrow policy row, rebuilt by the running system and the
# pool reserve. Per-workspace headroom metadata, not content. Never restored: a
# restored grant could exceed the restored truth's headroom.
- storage_admission_epoch
- storage_admission_epoch_bumps
- storage_escrow_policy
- storage_escrow_lane
- storage_escrow_scope
```

All five are also added to `REQUIRED_DENY` in `scripts/check-backup-scope.sh`
(security 4).

### 13.4 Rollout

1. **V28**: expand-only, a 2 s `lock_timeout`, plus a `docs/dev/rollback.md`
   V28 section (M5).
2. **`DeploymentSafety`:**
   - `testinbox.storage.admission-accounting ∈ {GLOBAL, ESCROW}`, default
     `GLOBAL`;
   - `ESCROW` is refused outside `lab` until ADR-038 is accepted. **Lifting that
     refusal is its own reviewed PR** (2026-10-30 precedent);
   - after that, `ESCROW` is refused unless all of these hold: `ALL`;
     `admission-locking = GLOBAL` (M4); a declared filesystem; C-Q (§9.4);
     `L_w < lock_timeout`;
   - `check-storage-enforcement-off.sh` refuses a committed `ESCROW` in every
     spelling.
3. **Activation gate S (slot-lane):**
   - every live node row of either role has `admission_accounting =
     'escrow-v1'`;
   - `escrow_enabled = true`;
   - `storage_escrow_policy` exists, and every escrow node's configured binding
     equals `storage_escrow_policy.binding`;
   - the inventory refuses an escrow node beside a NULL one.

   The name "S" avoids a clash with the existing gate E (H2e).
4. **Gate F:** the privileges of §13.2, plus a clean `storage_escrow_audit()`
   within the activation window.
5. **Rollback floor:** a `deploy/rollback-floors.txt` line at the V28 artifact,
   with the Ops step of §6.5 (H3).
6. **Gate D rerun:** the §11 matrix unchanged, CHOSEN = ESCROW as wired, on the
   default cold pool. 124/s points are added only if OD-7 says so.
   `GateEvaluator.ADR_REVIEW_REQUIRED`'s text is updated once ADR-038 is
   accepted (L5).
7. **Metrics** (closed enums only, I12):
   - `testinbox_storage_escrow_path_total{path=fast|scope_refill|slow}`;
   - `…_escalation_total{reason}`, with the §7 enum;
   - `…_refill_seconds{kind=global|workspace|inbox}`;
   - `…_epoch_bump_total{cause}`, read from `storage_admission_epoch_bumps`.
     `cause` is the CHECK enum of `last_cause`: `observation`, `trust`,
     `watermark`, `plan`, `share`, `policy`, `workspace`, `lifecycle`, `node`,
     `latch`, `row_free_debt`, `repair`, `drift`, `mixed_version`. Unknown
     values map to `other`;
   - `…_violation_total{scope}`;
   - `…_enabled`;
   - `…_keeper_refill_total{outcome}`;
   - `…_keeper_last_success_seconds`;
   - `…_keeper_listen_degraded`;
   - grant totals as `…_grant_bytes{scope_kind}`, for operators only.
8. **Alerts:**
   - any violation (page);
   - `escrow_enabled = 0` for more than 10 min outside a rollout;
   - escalations > 1 % over 5 min;
   - keeper last success > 2 min;
   - LISTEN degraded;
   - `testinbox_storage_escrow_policy_missing` (no policy row, or a binding
     mismatch: every event on the slow path).

---

## 14. Milestones (all estimates; working days unless stated)

The gating follows the architecture review.

| # | Milestone | Estimate | Exit |
|---|---|---|---|
| M0 | Review of revision 1 | done | AGREE WITH CHANGES × 3 |
| **M0′** | Re-check of revision 2 (`f8d869c`) | **done: all three reviewers AGREE subject to the edits of Appendix B, which are applied; M1 may start** | H1, M2, the prototype-touched M3 rules and the slow-path construction rules (§7) are in the design |
| M1 | Bounded prototype (§12.1) on this branch (contains `920ba1d` and amendment D); laptop smoke | 2 d | runs the focused workload end to end, cold pool by default, stub `t1_schedule` included |
| M2 | Focused NON-GATE staging run (Ops window) and analysis | 0.5 + 0.5 d | GO / STOP / INCONCLUSIVE (§12.3) |
| **Gate before M3** | H2 (ADR-038 complete), H3 (floor, T-C9), M1, M4, M5, M6 settled in the design and agreed | (part of M0′ / M2) | — |
| **Gate before merging M3** | **The owner's authorization of implementation.** ADR-038 is still Proposed, and V28 runs on every environment the migrator reaches | owner | — |
| M3 | Full implementation: triggers, fences, rule (P), lifecycle, revocation, audit, `DeploymentSafety`, gate S, metrics, all §11 tests, ArchUnit | 7–10 d | CI green, minima ratcheted |
| **Gate before M4** | L1–L5 implemented | (part of M3) | — |
| M4 | Reviews: correctness, architecture, security, including the N1/N2 probe evidence; fixes | 3–5 d | AGREE; evidence for OD-3 |
| M5 | Gate D rerun (full §11 matrix) | 1 d Ops + 0.5 d | PASS or FAIL as computed |
| M6 | Owner decisions (§15); ADR-038 accepted or not; release through S1/handoff | owner-paced | — |

**Calendar:** M1 → M5 is about **3–4 weeks** [estimate], if M2 says GO and
M0′ needs no second design round. My estimates on this thread have run
optimistic twice.

---

## 15. Residuals and owner decisions

### 15.1 Owner decisions

| # | Decision |
|---|---|
| OD-1 | Proceed to the prototype once both reviewers confirm M0′ (the option-3 decision already provides this) |
| OD-2 | Accept or refuse ADR-038 after M4 |
| OD-3 | N1 (scope-row wait) and N2 (inline refill): accept for the beta with mitigation (m1) adopted and its measured criterion (G8), or refuse. (m2) and (m3) are rejected, with reasons (§9.3) |
| OD-4 | If C-Q cannot be met with the beta's declarations, with `K_max` counting stranded lanes: a smaller `Q_lane`, or accept N3's one global bit |
| OD-5 | Scheduling tokens (only if #143 is accepted). **Design default (delegated technical decision `app-sle-od5-tokens-001`, private decision channel, 2026-11-06): option (i), one token per T1 attempt, so an escalated event consumes two**, inside the existing aggregate envelope R = 31 with evidence at R and 2R and unchanged latency, integrity and qualification criteria. The `(1 + e_max)` escalation allowance is **not adopted**: no fresh-traffic limit or attempt allowance changes without demonstrated compatibility, and any required constraint change goes to the owner. Delivery-allowance consumption on successful commit stays distinct from token spending. Decisive tests must cover envelopes that always escalate and the in-envelope `SCHEDULE_WAIT` behaviour. **Option (ii)** (one token per event, exempting the rolled-back fast attempt) **remains an owner decision**, raised only if pursued |
| OD-6 | Only if #143 is accepted and evidence passes at 62/s (and 124/s): raise R_T to 2R, with G-C and G-E at that rate |
| OD-7 | Whether 124/s scenarios become gate evidence, or stay focused-only |
| OD-8 | Rollback: mixed versions run mode a semantics at mode a speed, **and a rollback floor is required** (H3): `GLOBAL` everywhere and `escrow_enabled = false` before rolling any deployable or operator image below the V28 artifact |
| OD-9 | The invariant-8 sentence proposed in ADR-038, for the owner to adopt into CLAUDE.md, or not |
| OD-10 | The pre-existing ingest-path disclosure (§9.2), found by the security review. Out of this design's scope; with the owner |
| OD-11 | Once ADR-038 lands on a shared branch, two owner-held files become inaccurate: CLAUDE.md line 12, "Proposed ADRs (currently 0016, 0017, 0018)", and `.claude/agents/architecture-reviewer.md` line 24. This design edits neither; the `docs/adr/README.md` index row stays |

### 15.2 Residuals (none accepted here)

| # | Residual |
|---|---|
| RS-1 | Near the global cap (outside C-Q's guarantee) most events escalate; throughput degrades to mode a's. This is an alert state |
| RS-2 | A full or nearly full scope runs an inline refill per event touching it. It is per scope, index-bounded, and serialized on that scope only |
| RS-3 | Host stalls still affect each commit; p99 on this host class is the main unknown |
| RS-4 | The harness's uploads are instant: slot holds are shorter than in production, the slot baseline in the N1 comparison is understated, and throughput is overstated |
| RS-5 | In-lock planning for the rare global refill is reduced (keeper warm-up), not removed |
| RS-6 | P1-style debt/charge races under-count a share in mode a and SLE alike; the global bound is unaffected |
| RS-7 | If #143 lands, the real `t1_schedule` replaces the prototype's stub, and its scheduling behaviour is measured then |
| RS-8 | The fast path's configure cost: 0.8 ms assumed until batching is measured |

---

## 16. Where I am uncertain

- **The fast path's p99 on this host,** with the `t1_schedule` row in series.
- **The real lane-epoch cadence in production** (the monitor interval; rule (P)
  write rates under ambiguity). The storm scenario bounds its cost.
- **Whether the beta's G, `G_F` and `C_fs` leave room for C-Q,** with
  `K_max = 32`.
- **The PostgreSQL behaviours relied on**, each pinned by a test rather than
  assumed:
  - `ORDER BY … FOR UPDATE` locks in order (T-C8);
  - READ COMMITTED re-checks only locked rows (T-C14);
  - plpgsql statements in a VOLATILE function each take a fresh snapshot
    (T-C14).
- **Whether the keeper's background scope refill (m1)** keeps inline refills
  below G8's threshold in the fresh-inbox scenario. New inboxes are refilled
  inline by construction, and are excluded from G8's denominator only as rows
  created by the event itself.

---

## Appendix A. Disposition of the reviews of revision 1 (`a453e14`)

Where two reviewers overlap, the stricter wording is applied and noted.

| Item | Applied in |
|---|---|
| Correctness 1 (after-lock statements; E10 inside admit; mutants) | §4.1 hygiene, §4.2 3(c)–(d) and step 5, §4.3, §4.4 steps 4 and 7, E10, T-C14 |
| Correctness 2 (inbox existence and ownership) | §4.2 3(c) and step 4, §4.1, T-C15, P-D. **Overlaps architecture M2 (ownership, `t0`):** both applied; my revision 1's "vanished inbox admits" was wrong and is removed |
| Correctness 3 (footprint refusals by mode a's formula) | §4.2 step 4 and step 5's last paragraph, Theorem D, P-D |
| Correctness 4 (split epoch; storm case) | §4.1, §4.6, §3.3, §12.2 storm |
| Correctness 5 (epoch row last; bump rule; audit; T-C6) | §4.8 order item 14, the epoch row's rule and its audit, T-C6 |
| Correctness 6 (escrow-capable rows) | §6.5. **Overlaps architecture H3(i):** the stricter combination is applied. Every new artifact (API too) declares `escrow-v1`, lanes exist only for ingestion ids, and NULL of either role revokes |
| Correctness 7 (epoch coverage; T-C11; repair pinned) | §4.6 coverage table, T-C11. **Overlaps architecture M1a:** stricter applied; repair and recompute call `storage_escrow_revoke('repair')`, rather than relying on the trust bump |
| Correctness 8 ("OFF byte for byte") | §6.5, ADR-038 |
| Correctness 9 (configure cost) | §3.3 (0.8 ms used), G7 marked tight, RS-8 |
| Correctness 10 (OD-5 departs from decision 2) | §8, OD-5 |
| Correctness 11 (stub `t1_schedule`; G1 with it) | §3.2, §12.1, §12.2, G1, S1 |
| Correctness 12 (NOTIFY keeper; deletion race test) | §4.5, T-C12 |
| Security 1 (N1 new; (m1) adopted; (m2), (m3) rejected; C-Q dead lanes) | §9.2, §9.3, §9.4, OD-3, OD-4 |
| Security 2 (pre-existing ingest-path disclosure) | §9.2 last paragraph, at class level only; OD-10. Revision 1's description is removed |
| Security 3.1 (definer hygiene) | §4.1 |
| Security 3.2 (E8 detected, not prevented) | E8, M-8 |
| Security 3.3 (no API `SELECT` on scope rows, no `EXECUTE` on revoke) | §13.2. **Stricter than asked:** no API `SELECT` on any escrow table; §6.3 |
| Security 3.4 (application policy inflates scope grants only) | §4.3. Made true by `storage_escrow_policy` (§4.1, §4.4) |
| Security 4 (`REQUIRED_DENY`) | §13.3 |
| Security 5 (`cause` enum pinned) | §13.4 item 7, MC-1 |
| Security 6 (epoch writers' tenant reachability; test) | §4.6 reachability table, T-C13 |
| Architecture H1 (insert before regrant) | §4.4 steps 6–7, E4, §12.1, T-C2 mutant |
| Architecture H2 (a)–(i), form, base branch | ADR-038 (every item, including (g)'s corrected wording, (h)'s deletion, (i)'s authority and the NO_LOCK verdict), §13.1, §1, this branch's base |
| Architecture H3 (API and operator artifacts; floor) | §6.5, §13.4 item 5, T-C9, OD-8 |
| Architecture M1 (repair bump; non-circular audit; binding; sentence) | §4.6, §6.3, §4.1, §4.3, M-6, M-7, ADR-038, OD-9 |
| Architecture M2 (ADR-024 placement; one regrant; keeper placement; ownership) | §7, §4.5, §4.2 |
| Architecture M3 (ArchUnit) | §7, A-1, §12.1 |
| Architecture M4 (`GLOBAL` locking; config spellings; own PR) | §4.2, §13.4 item 2, B-5, B-6 |
| Architecture M5 (V28 behaviour change; B-1′; rollback.md) | §6.5, §13.4 item 1, B-1′ |
| Architecture M6 (unaccepted dependencies) | §4.2 step 2, §4.8 rank 3, §8 |
| Architecture L1 (keeper liveness; delete dead lanes) | §4.5, §4.4 step 7, §13.4 items 7–8 |
| Architecture L2 (epoch read without lock) | §3.2, T-C1 |
| Architecture L3 (`last_cause` CHECK; DB-side counter) | §4.1, §13.4 item 7 |
| Architecture L4 (operator view) | §6.3: metrics only, no REST |
| Architecture L5 (`GateEvaluator` text) | §13.4 item 6 |
| Architecture: gating | §14 |

---

## Appendix B. Disposition of the re-check of revision 2 (`f8d869c`)

All three reviewers AGREE subject to these edits, applied in one commit.

| Item | Applied in |
|---|---|
| A. Repair, recompute and audit lock order (fix (a)) | §4.1 (revoke split: requires the fence, bumps last), §4.6 coverage row, §4.8 (the audit behind the rule; the deadlock-freedom table, now true for every locker), §6.3 (violation path in a fresh transaction: fence → `(35, 2)` → re-check → distrust → revoke), §6.5, T-C6 (rule (P) against repair + revoke and recompute; mutant with `(35, 2)` before the fence) |
| B. Before M1: V28-proto never in `db/migration`; stub benchmark-only; constructor-injected escrow strategy; full T1 SQL sequence test under `GLOBAL`/`OFF`; override rule incl. `acquireAdmissionLock`; regrant SQL constant with a field-access rule | §12.1, §7 ("How the slow path is built"), A-1 |
| C. `storage_escrow_policy`: share-table trigger (drain, READ COMMITTED); operator-only definer writer in the reserve transaction, consistent with `assess = Fits`; no row or mismatch ⇒ no grants, slow path, alert, never a `451`; gate S binding check; `binding_mismatch` reason | §4.1 ("The policy row"), §7 reason enum, §13.2, §13.4 items 3 and 8 |
| D. `storage_admission_epoch_bumps`: ingestion `SELECT`, API none; closed causes, no tenant ids, `-`; one exporting deployable, as a gauge; restore resets | §4.1 ("The bump counter"), §13.2, §13.3 |
| E. ADR-038 gaps: rule (G) at refill time and in the header; §10 Reconciliation (I8); §A2.8 with the honest OFF wording; the `AdrConformance` bullet reworded | ADR-038 header, "ADR-035 §10", "Amendment 2 §A2.1 and §A2.8, and the annex", "ADR-035 §11" |
| F. Gate before merging M3: the owner's authorization | §14 |
| G. Public-repo wording | Appendix A row reworded. Both files searched for mechanism wording near the disclosure: none remains. `upload` appears only in §4.6 (rule (P) writers, unrelated) and RS-4 (harness) |
| H. OD-5 both options, each with its conditions | §8, OD-5 |
| I. Owner note on CLAUDE.md line 12 and `.claude/agents/architecture-reviewer.md` line 24 | OD-11 (neither file edited; README row kept) |
| Header and §14: "M0′: all three reviewers AGREE subject to these edits (applied); M1 may start" | header, §14 |
