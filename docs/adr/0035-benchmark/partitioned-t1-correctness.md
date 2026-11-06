# Share-partitioned T1: correctness argument (for independent review)

**Status: implementation and testing authorized (owner, 2026-11-05) behind a
lab-only switch; NOT released.** This argues that the production code path
behind `testinbox.storage.admission-locking = COHORT_PARTITIONED` keeps every
ADR-035 invariant that mode a keeps. The mode is a **PROPOSED** ADR-035
amendment ([`../0035-amendment-note-partitioned-t1-PROPOSED.md`](../0035-amendment-note-partitioned-t1-PROPOSED.md));
ADR-035's dated "Owner decision note (2026-11-05)" records what the decision
authorizes. Nothing here changes a §11 criterion or threshold, and nothing here
is gate D evidence. Revised after the two independent reviews of #136 and their re-review, with the security review of the timing channel (§8).

Owner decision, verbatim: *"Partitioned locking: Implementation and testing
authorized. Release requires independent correctness review, passing applicable
benchmark criteria, and enforced safeguards against cohort growth or
overcommit. Benchmark success alone is insufficient. This does not authorize
opening admission."*

Pointers are `path:line` at the commit that adds this revision. `JSA` is
`backend/persistence/src/main/kotlin/email/testinbox/persistence/JdbcStorageAdmission.kt`,
`PTS` is `.../persistence/PartitionedT1Sql.kt`,
`V25` is `backend/persistence/src/main/resources/db/migration/V25__storage_partition_guard.sql`,
`PAT` and `PRT` are `backend/persistence/src/test/kotlin/email/testinbox/persistence/PartitionedAdmissionTest.kt`
and `PartitionedAdmissionRegressionTest.kt`.

## 1. What runs

**Switch.** `AdmissionLocking { GLOBAL, COHORT_PARTITIONED }`, default `GLOBAL`
(`application/.../storage/StorageDeclarations.kt`), bound from
`testinbox.storage.admission-locking` on the ingestion deployable only, passed
to the one T1 construction site (`IngestionWiring.kt:346`). Safeguards around
the switch:

- `DeploymentSafety` refuses `COHORT_PARTITIONED` in every environment but
  `lab`, and in the lab anywhere but `ALL` with a declared filesystem
  (`DeploymentSafety.kt:629`).
- Each node records its protocol in `storage_node.admission_locking` (V25:136),
  in the registration insert and the heartbeat upsert themselves
  (`JdbcStorageAmbiguity.kt:194`, `:218`: one statement, never a GLOBAL window); the
  activation inventory refuses a live non-`GLOBAL` node outside the lab and
  stops admission (`ActivationBarrier.kt:218`; `partitionedAllowed` only for
  environment `lab`, `IngestionWiring.kt:309`).
- Activation gate L blocks while any node runs it, or cannot prove it does not
  (an absent metric series is unknown, never mode a), from the metrics and the
  database (`scripts/check-storage-activation.sh:553`).
- `scripts/check-storage-enforcement-off.sh:174` refuses any committed
  non-`GLOBAL` value, in every spelling Spring binds, and pins the default.
- ArchUnit (`PartitionedT1RuleTest`): only `IngestionWiring` and the benchmark
  build the adapter; only the adapter and the benchmark's declarations name
  `COHORT_PARTITIONED`; no main-code subclass overrides the guard or the locks.
  Each rule fails on a rogue fixture.

**Mode a's T1 is unchanged byte for byte** — its transaction and its
statement: `admit` (`JSA:110`) runs the original transaction when
`locking == GLOBAL`, and the statement's SHA-256 for both `readsFootprint`
values is pinned to the 9d06ab2 text (`AdmissionStatementTextTest`). **Other
things do change under `GLOBAL`:** the pool reserve records the bound; the V25
share/plan/bound triggers (fence, isolation rule, bound check) apply whatever
the switch says; nodes write `admission_locking`; the activation barrier has
gate L.

**Partitioned protocol** (`JSA:110`, `JSA:186`), one transaction:

1. mode a's `configureTransaction` (READ COMMITTED, `synchronous_commit = on`,
   `lock_timeout`, `statement_timeout`, idle timeout, generic plan);
2. `pg_advisory_xact_lock(38, k)` for each distinct key, **one statement per
   key**, in ascending order of the key itself (`JSA:301`, keys `JSA:633`);
3. THEN `pg_advisory_xact_lock_shared(35, 1)` (`JSA:204`, `JSA:283`): a T1
   queued on a busy workspace holds no `(35, 1)` (security review S-REC, §3);
4. ONE statement (`JSA:369`, read at `JSA:465`): mode a's per-workspace and
   per-inbox CTEs and rows; **no `g_*` global sums** (*L* = 0 and global payload
   0, `PTS:70`); debt, trash, trust, watermark, observation and the WHOLE cohort
   pool read and later decided; and the four guard columns (`PTS:15–34`). Not
   "no global scan": the cohort pool's per-share usage still reads every
   shared workspace's deltas and reservations (gate D A1; before it, the
   whole of both tables), and `fp_debt` the debt table, as in mode a;
5. a guard condition false ⇒ `setRollbackOnly`, nothing written (`JSA:207`); a
   per-workspace lock timeout (55P03/57014) or deadlock (40P01) ⇒ the
   transaction rolls back (`JSA:202`, `JSA:220`). Either way the SAME call runs
   the mode a transaction (exclusive `(35, 1)`) from scratch and reports the
   reason (`JSA:130`) to the closed-label counter
   `testinbox_storage_admission_partition_fallback_total{reason}`. Otherwise
   production's `StorageAdmission` decides on the snapshot, the reservations
   are inserted, commit.

**The guard** (`PTS`), in step 4's snapshot (the V25 function is `STABLE`, so
it reads the calling statement's snapshot):

- G1 `NO_MATCHING_BOUND`: an APPROVED plan and a `storage_partition_bound` with
  the plan's exact `binding` and `fingerprint`;
- G2 `OVER_BOUND`: `storage_partition_within_bound()` (V25:67): 30 × 128 MiB +
  Σ legacy payload ≤ the bound's payload capacity, 30 × 256 MiB + Σ legacy
  footprint ≤ its footprint capacity, ≤ 30 participant rows, every one at V19's
  pinned figures;
- G3 `UNSHARED_WORKSPACE`: every workspace of the event holds a share;
- G4 `UNCHARGED_DEBT`: no unsuperseded debt row (`fp_debt`'s own filter) of a
  shared workspace lacks its `workspace_deletion_charge` row (review P1: a debt
  row whose transaction raced the reserve that shared its workspace).

**The bound** is `CohortStorageBudget.partitionCapacity`
(`domain/.../CohortStorageBudget.kt:103`): `G − H − W_bytes` and
`min(G_F − F(W) − H_F, C_fs − F(W) − H_F − U − T − P_F − M − R_ops)`. By
construction, and by property (`PartitionCapacityTest`,
`PartitionCapacityRuntimeTest`), `assess(declarations, legacy) is Fits ⇔
30·P + Σ legacy ≤ payload bound ∧ 30·F + Σ legacy ≤ footprint bound`. It is
written only by the pool reserve, in the reserving transaction, for that plan's
binding and fingerprint (`JdbcCohortBudgets.kt:107`, computed at
`ReserveCohortStorage.kt:41` after `assess` is `Fits`).

**Database safeguards on the share tables** (V25), whoever writes:

- S1 fence: a BEFORE STATEMENT trigger on `storage_legacy_share`,
  `participant_workspace`, `storage_cohort_plan`, `storage_partition_bound`
  (insert, update, delete, truncate) refuses any isolation level but READ
  COMMITTED (a REPEATABLE READ or SERIALIZABLE snapshot predates the fence, and
  SSI cannot see a READ COMMITTED writer on the other side), then takes
  `(35, 1)` EXCLUSIVE (V25:84,
  V25:114–120).
- S2 bound check: a DEFERRED constraint trigger refuses at commit a state in
  which the bound matches the APPROVED plan (binding and fingerprint) and G2
  fails (V25:100, V25:123–129). A bound that does not match the approved plan
  (a re-reserve by an older binary, a revocation) is inert: it neither binds
  nor lets a T1 partition (G1).

## 2. Invariants

Let `U_w` be workspace *w*'s payload usage (base + Σ delta + Σ reservation),
`C_w` its attributed cleanup charge (`workspace_deletion_charge`), `share_w =
(P_w, F_w)` its ceilings. Mode a's cohort readiness, which the partitioned T1
still reads and decides in its own snapshot (`CohortStorage.kt:118–142`),
requires:

- R1 `unknown = 0`: every `workspace` row has a share;
- R2 no share overallocated: `U_w ≤ P_w` and `F(U_w + C_w) ≤ F_w`;
- R3 the plan approved for this node's binding, its fingerprint equal to the
  current legacy inventory, `assess = Fits` for this node's declarations, nodes
  ready, a fresh valid observation;
- R4 `F(D_u) ≤ U` and observed trash `≤ T`, with `D_u` the unattributed load
  (`cp_unattributed`).

Under `ALL` any failure throws `StorageFootprintUnavailableException` (451 for
the whole DATA, for everyone), in BOTH modes, from the same rule code. In a
partitioned deployment the pool is always required (`CohortStorage.kt:55`; G1
needs a plan).

**I-W (per-workspace).** For every shared *w*, at every committed state,
`U_w ≤ P_w` and `F(U_w + C_w) ≤ F_w`.

*Proof.* Writers that can raise `U_w + C_w`:
(a) a T1 admitting copies to *w*. A partitioned T1 holds `(38, k(w))`
exclusive from before its snapshot to commit; a mode a T1 holds `(35, 1)`
exclusive, which conflicts with every partitioned T1's shared `(35, 1)`. So T1s
admitting to *w* are totally ordered, each reads after the previous committed
(READ COMMITTED, statement after the lock), and each admits only within
`min(policy, P_w)` and `F_w`, over running totals of its own copies.
(b) Rule (P): pending debt for a canonical key raises `C_w` through V20's
attribution trigger. It holds `(35, 1)` EXCLUSIVE (`JdbcRowFreeDebtStore.kt:52`),
so no T1 is in flight, and it admits against the global (P) inequality on the
full *L* and *D*. If it leaves *w* over `F_w`, R2 fails in every later
snapshot and every T1 in both modes answers 451 until the charge is released —
fail closed, as in mode a (`PRT:355`).
(c) Debt that enters `D` for a shared workspace WITHOUT a charge (it raced the
reserve that shared the workspace, review P1): `C_w` does not grow, so I-W is
not violated, but that debt is then in no share and not in `D_u`. G4 sees it in
the same snapshot and sends every T1 to mode a, whose (C) counts it in *D*
(`PRT:175`).
Every other writer lowers or moves bytes WITHIN *w* (T2, compaction,
retention, cleanup — usage → debt and its charge in the same transaction —,
charge release). ∎

**I-G (global).** While G1–G4 and R1–R4 hold in a T1's snapshot, the global
payload usage `Σ U ≤ G − H` and rules (G) and (C) hold.

*Proof.* By R1 and the foreign keys of `workspace_storage_account` and
`storage_reservation` to `workspace`, every byte of usage belongs to a shared
workspace. By I-W, `Σ U ≤ Σ_w P_w ≤ 30·P + Σ legacy ≤ G − H − W_bytes` (G2).
For footprint: `F` is subadditive, so `F(L + D + A + c) ≤ Σ_w F(U_w + C_w) +
F(D_u) + F(A + c)`, because every unsuperseded debt row is either a shared
workspace's charge (G4) or in `D_u`. With I-W, R4 and `F(A + c) ≤ F(W)` for any
event the gateway can produce (≤ 50 copies of ≤ 2 × max object, ≤ 501 objects
each), (G) and (C) reduce to the bound's two inequalities. ∎

Concurrent partitioned T1s for disjoint workspaces each preserve their own
I-W and nothing else; the global bound depends only on the static partition
(G1–G2) and the per-share invariant, so their interleaving preserves I-G.

**I-E (equivalence with mode a).** In a snapshot where G1–G4 and R1–R4 hold,
mode a's global rules admit every candidate (I-G with the event's own prefix ≤
W), and the partitioned rules evaluate the same rules on smaller figures, which
admit too; every other input is byte-identical SQL. So both modes decide alike;
when a guard condition fails the event is decided by mode a itself. Tests:
`PAT:57` (a sequence under both modes), `PRT:374` (at the edge of the
partition: 30 full participants and legacy shares filling the bound exactly,
mode a's global slack exactly `W + 3` copies, seven events decided identically
on both paths), `PAT:264` (an un-approved pool refused identically).

**I9 (no per-tenant 451 from partitioning).** Every partition-specific failure
falls back to mode a, never to a refusal: a guard condition (G3 is the per-tenant
one), and a `(38, k)` lock timeout or deadlock, whose wait depends on one
workspace's load (review A3; `PRT:238`, `PRT:264`). The shared `(35, 1)` is
everyone's, like mode a's exclusive one, so its timeout stays mode a's global
451 (`PRT:238`, second half). The residual timing channel is §7.

**One statement (§4).** The partitioned figures, the cohort pool and the guard
come from one statement (`AdmissionStatementTextTest`); the `STABLE` guard
function sees that statement's snapshot.

**Other invariants untouched.** I3–I7, the write slot, `t0 = now()`,
`synchronous_commit = on`, the insert and the timeout classification are mode
a's code. No content dedup, raw-first storage, unknown recipients and SMTP
replies are not touched.

## 3. Lock order and deadlock freedom

| Holder | Order |
|---|---|
| partitioned T1 | `(38,k)` one statement per key, ascending by key → `(35,1)` shared → FK `KEY SHARE` of the insert |
| mode a T1 (also every fallback) | `(35,1)` exclusive → FK `KEY SHARE` |
| rule (P) | `(35,1)` exclusive → definer writes |
| lifecycle fence | ADR-027 guard → `beta_cohort` row (cohort ops) → `(35,1)` exclusive → account row |
| beta admission | `beta_cohort` row → `(35,1)` exclusive → inserts (S1 re-enters) |
| pool reserve / revoke | `beta_cohort` row → `(35,1)` exclusive → share writes (S1 re-enters) |
| node fence (V21), incl. every heartbeat | `storage_node` row → `(35,1)` exclusive |
| share-table writer (S1) | share row(s) → `(35,1)` exclusive |

- Partitioned T1s among themselves: shared `(35,1)` never conflicts with
  shared; `(38,·)` keys are deduplicated and sorted by the key itself and taken
  one statement at a time in that order, so the order is the program's, not an
  executor's (review P4), and there is no cycle. Keys are `UUID.hashCode`
  (specified by the JDK, identical on every node); two colliding workspaces in
  one event take their one key once, and across events every key comes in the
  same order, so a collision only serializes (`PRT:286`, with two colliding ids).
- **Deadlock freedom with `(38,·)` before `(35,1)`.** Order the lock kinds
  `(38, k₁) < … < (38, kₙ) < (35,1)`. Every partitioned T1 acquires in that
  order and takes nothing between them but those locks. Every exclusive
  `(35,1)` holder (mode a T1, every fallback, rule (P), the lifecycle fence, the
  node fence and heartbeats, reserve/revoke, beta admission, S1 writers) takes
  NO `(38,·)` at all, before or after. So a waits-for edge into a `(38,·)` lock
  only ever comes from a partitioned T1 holding smaller keys, and a holder of
  `(35,1)` (shared or exclusive) waits only for `(35,1)` itself or for row
  locks no partitioned T1 holds before its insert (mode a's FK `KEY SHARE` on
  `workspace` is the only one, and it conflicts only with deleting or re-keying
  a workspace). A cycle would need a `(35,1)` holder waiting on a `(38,·)`
  holder: there is none. ∎
- **Why the order matters (cross-tenant convoy).** With `(35,1)` first, a T1
  queued on a hot workspace's `(38,k)` held a shared `(35,1)` for its whole
  wait (up to `lock_timeout`); an exclusive `(35,1)` asker (a heartbeat's node
  fence, a fallback, rule (P)) queued behind those, and PostgreSQL queues every
  later shared request — every cold workspace's T1 — behind that exclusive
  waiter. With `(38,·)` first, the queued hot T1s hold nothing global; the
  exclusive asker waits only for T1s that already hold their workspace locks and
  are in their short critical section. `PRT:137` holds a hot workspace's key,
  queues a hot T1, and shows an exclusive `(35,1)` asker and a cold T1 both
  complete while the key is still held (it failed with the old order).
- **What is unchanged.** The critical section (snapshot → decision → insert →
  commit) is still covered by the shared `(35,1)` AND the workspace locks, so
  I-W and mode a's exclusion (an exclusive holder excludes every partitioned
  T1's critical section) hold exactly as before; a T1 still waiting on `(38,k)`
  has read nothing yet, and reads after its locks.
- The fallback never upgrades a lock: the partitioned transaction ends (all
  locks released) before mode a's begins.
- S1 takes `(35,1)` after a share row lock; a cycle needs an operator
  transaction holding `(35,1)` (reserve) and an owner-SQL edit of the same row.
  PostgreSQL detects it and aborts one (operator vs operator).
- Starvation: PostgreSQL queues a new lock request behind a waiting
  conflicting one; every wait is bounded by `lock_timeout`. A T1 can therefore
  take about two lock timeouts plus its statement when it times out on `(38,k)`
  and falls back (the benchmark's hot scenario reports the worst case and the
  fallbacks by reason, C-3).

Tests: `PAT:134` (12 threads, multi-workspace events in random order, no
deadlock, no share exceeded), `PAT:103` (same workspace serialized), `PRT:286`.

## 4. The five dependencies on the global exclusive lock

| # | Dependency | Holder | Preserved because |
|---|---|---|---|
| 1 | per-workspace races | (T1 itself) | `(38, k(w))` exclusive per workspace, snapshot after it (I-W); `PAT:103`, mutant `PAT:118` over-admits without it |
| 2 | lifecycle drain fence | `JdbcAccountLifecycle.lockStorageFence` (`JdbcAccountLifecycle.kt:49`) | still EXCLUSIVE; `PAT:172` (both directions) |
| 3 | rule (P) debt writes | `JdbcRowFreeDebtStore.admit` (`JdbcRowFreeDebtStore.kt:52`) | still EXCLUSIVE, decides on the full *L* + *D*; `PAT:207`; over-allocation closes admission in both modes, `PRT:355` |
| 4 | node fence, pool reserve/revoke, beta admission | V21 `storage_cohort_node_fence`, `JdbcCohortBudgets.lockClosed` (`JdbcCohortBudgets.kt:24`), `JdbcBetaAdmissions.lockPolicy` (`JdbcBetaAdmissions.kt:31`); plus S1 | all EXCLUSIVE; S1 extends the fence to any writer; `PAT:294`, mutant `PAT:310` |
| 5 | unbudgeted / unshared workspaces, no cohort | (guard) | G1/G3 ⇒ mode a; `PAT:283`; without a cohort the guard sends T1 to mode a whose global sum refuses, and the guard-less mutant over-admits past G − H (`PAT:327`) |

## 5. What the safeguards enforce, and where

| Requirement | Enforced by | Where | Fails |
|---|---|---|---|
| partition only an approved pool with its own recorded bound | G1 | T1 statement | to mode a (`PAT:353`, `PRT:205`) |
| non-overcommit: all shares within the bound | G2 in T1; S2 at every commit (matching bound) | PostgreSQL | T1 to mode a (`PRT:334` distinguishes G2 from R3); growth refused (`PAT:222`, mutant `PAT:246`) |
| closed cohort: the event's workspaces shared | G3 | PostgreSQL | to mode a (`PAT:283`) |
| every unsuperseded debt of a share is charged to it | G4 | PostgreSQL | to mode a (`PRT:175`) |
| closed cohort, no overallocation, allowances, nodes, fingerprint | R1–R4 | application rules on the same snapshot (mode a's readiness) | 451 for every event, as mode a (`PAT:264`, `PRT:355`) |
| growth atomically ordered with every T1 | S1 | PostgreSQL | the writer waits (`PAT:294`) |
| no write skew on stale snapshots | S1 isolation rule (READ COMMITTED only) | PostgreSQL | REPEATABLE READ writer refused (`PRT:35`), SERIALIZABLE writer refused (`PRT:91`); READ COMMITTED refused at commit (`PRT:67`); re-approval past the bound refused (`PRT:122`) |
| no per-tenant 451 from a workspace lock | fallback on 55P03/57014/40P01 in the `(38,k)` phase | `JSA:202` | to mode a (`PRT:238`, `PRT:264`) |
| lab only | `DeploymentSafety`, node inventory, gate L, committed-config gate, ArchUnit | startup, runtime, activation, CI | refuses to start / 451 / BLOCKED / CI fails |
| bound equals the proof | `partitionCapacity` ⇔ `assess` | domain, property-tested | — |
| fallbacks visible | `..._partition_fallback_total{reason}` | metrics | — |

## 6. Failure modes

- **Bound missing, stale, or from another binding/fingerprint**: G1 fails, mode
  a decides; S2 does not bind. Liveness only (`docs/dev/rollback.md`, V25).
- **Overcommitting share write by anyone**: S2 refuses the commit while the
  bound matches the plan; if S2 were dropped, G2 still sends T1 to mode a.
- **Growth within the bound** (the inventory no longer matches the fingerprint):
  G1 still holds; R3 fails in the same snapshot: 451 in both modes until a
  reserve.
- **A new unshared workspace**: its events go to mode a; R1 then fails for
  every event in both modes until it is shared or removed.
- **Debt racing a reserve**: G4 sends every T1 to mode a until the charge
  exists or the debt is superseded.
- **Rule (P) overallocates a share**: R2 fails; 451 in both modes.
- **Mixed fleet** (GLOBAL and partitioned nodes): exclusive holders exclude
  shared ones; safe. Outside the lab the inventory and gate L refuse it.
- **Workspace lock contention**: falls back to mode a (one more transaction);
  the shared `(35,1)` timeout stays mode a's global 451.
- **Node heartbeats** take `(35,1)` exclusive through the V21 trigger: each
  drains all in-flight partitioned T1s once (measured by the variant at
  production cadence).

## 7. Residuals for the reviewer

1. R1–R4 are application rules over database figures (mode a's code, same
   snapshot), not SQL; the database enforces G1–G4, S1, S2.
2. **Timing side channel (I9 / ADR-025).** The observer is an
   **authenticated tenant**, not the sender: it co-addresses its own inbox with a
   candidate address in one DATA and reads its own inbox's delivery time (or its
   absence after a `451`). The external sender learns nothing new: the edge
   stores and forwards, so the sender sees only the edge's reply. What the
   observer can see: if the candidate belongs to a workspace kept busy (by
   flooding addresses it knows there), the event waits on that workspace's
   `(38, k)` up to `lock_timeout` and then falls back to mode a, so the delivery
   is slower; it is never a partition-specific `451`.

   **The baseline, and its status (updated 2026-11-06).** Mode a already has a
   channel of the same kind: the per-workspace fair write slots and the
   `W_slot` wait that answers `451` as "a load failure" are SPECIFIED by
   ADR-035 §4 step 5, with the slots of §5 (Accepted; cited in #124 as "§5
   step 5") (4 slots per workspace per node, 10 s; `StorageProtocol.kt:42`,
   `:45`). Their consequence for a tenant observer (membership of an address
   in a saturated workspace, from the delay or the `451`) was not analysed by
   any ADR. It was escalated to the owner (#124) as an I9 tension. On
   2026-11-06 the owner **accepted it, together with the coarse reading of a
   known workspace's natural congestion, as one bounded timing-disclosure
   exception to I9, for the initial beta of at most 30 admitted accounts
   only**, with mitigation tracked in #139 and the exception reassessed before
   any expansion (ADR-035, "Owner decision note (2026-11-06)";
   `docs/security/abuse-model.md`).

   **The partitioned residual is NOT covered by that acceptance.** Both owner
   decisions exclude it: the first does not waive the partitioned-mode timing
   assessment, and the second excludes "the separate partitioned-locking
   residual". The partitioned residual is still measured against the mode a
   baseline, but "no worse than the baseline" is a comparison, not an
   acceptance: the baseline's acceptance is a scoped exception, not a
   threshold that other channels inherit. The partitioned residual still needs
   its own assessment, its criterion (criterion 8 of the PROPOSED note, not
   adopted) and its own owner decision. The security review found it no worse
   than the baseline, subject to its conditions; it did not, and cannot,
   accept it.

   **Conditions.** (a) The benchmark measures the channel against mode a under
   identical load: the `*-probe` scenarios at the heaviest matrix point and at a
   paced point (maximum-size flood events just under the hot workspace's INGEST
   refill, so the flood keeps its budget), `--probe-repetitions` (default 4)
   runs with INGEST buckets reset before each and the two modes' order
   alternating; per-class statistics are computed ONLY over events whose target
   copy reached the guarded protocol (`targetReachedEvents`), and pooled per
   point and mode with the sample named (`probePooled`, minimum 300). The
   proposed criterion is in the PROPOSED note, not adopted. (b) In any
   environment running the mode, only the edge or trusted test clients reach
   ingestion's SMTP port (Ops precondition). (c) The lock reorder (§3) keeps a
   busy workspace from slowing other workspaces through `(35,1)`.
   Limit: the harness's uploads are instant, so a write slot is held for T1 and
   T2 only; production upload time would lengthen slot holds at the paced point.
3. `storage_delta` has no foreign key: I-G assumes no positive delta exists for
   a workspace without a share (only corruption could create one; I8 reports
   drift).
4. Subadditivity of `F` and `F(A + c) ≤ F(W)` are stated, not machine-checked
   here (`FootprintModelTest` covers the closed form).
5. The benchmark variant runs on a closed 31-workspace population with
   usage-neutral deltas and retention seeded into the legacy share; its
   informational criteria compare against references from the gate population.

## 8. Review findings (#136) and resolution

| Finding | Resolution |
|---|---|
| P1 orphan debt | guard G4 (`PTS:34`) ⇒ mode a; `PRT:175` (failed before, passes after) |
| P2 REPEATABLE READ write skew | S1 refuses non-RC, non-SERIALIZABLE writers (V25:88); `PRT:35` (failed before), control `PRT:67` |
| P3 / A4 stale bound | S2 binds only a bound matching the approved plan (V25:105); rollback entry; `PRT:205` (failed before) |
| P4 lock order | one statement per key, sorted by key (`JSA:301`); collision test `PRT:286` |
| P5 invisible fallbacks | closed-reason counter; `MetricCardinalityTest` |
| P6 tests | `PRT:334`, `PRT:355`, `PRT:374`, `PRT:35`, `PRT:175` |
| P7 wording | "no `g_*` global sums", not "no global sum" (§1 step 4) |
| A1 ADR text | dated owner-decision note appended to ADR-035; both passages listed in the PROPOSED note |
| A2 lab only | `DeploymentSafety`, `storage_node.admission_locking`, inventory, gate L |
| A3 I9 oracle | `(38,k)` timeout and deadlock fall back (`JSA:202`); `PRT:238` (failed before), `PRT:264` (new); residual §7.2 |
| A5 wording | "mode a's T1 statement and transaction unchanged" (§1), V25 header, PROPOSED note, production.md |
| A6 ArchUnit | `PartitionedT1RuleTest` with rogue fixtures |
| A7 committed config | `check-storage-enforcement-off.sh` and its tests |
| A8 startup log | `IngestionOps.kt` logs `admissionLocking` |
| A9 criteria | recommendation in the PROPOSED note (not adopted); hot-workspace and heartbeat scenarios in the variant |
| C-1 SERIALIZABLE vs READ COMMITTED writers | fence READ COMMITTED only (V25:88); reserve command asserts it; `PRT:91` (R3, failed before), `PRT:122` (R5) |
| C-2 node writes | `admission_locking` in the insert and the heartbeat upsert themselves; one write per heartbeat |
| C-3 fallback cascade | hot scenario reports `partitionFallbackReasons` and `t1Transaction.maxMs` |
| N1 key spellings | `check-storage-enforcement-off.sh` canonicalizes the key like Spring (case, `-`, `_`, `.`); new tests failed before |
| N3 API inventory | `ApiStorageWiring` passes the lab flag |
| N5 heartbeat age | gate L uses `$HEARTBEAT_MAX_AGE` |
| per-metric reasons | `MetricCardinalityTest` keeps one `reason` set per metric |
| S-C1 probes | `*-probe` scenarios in mode a and partitioned (`ProbeLoad`); then target-copy accounting, a paced point, repetitions with bucket reset and alternating order, pooled samples (security re-check) |
| S-C2 Ops precondition | PROPOSED note and production.md |
| S-C3 §7.2 and abuse model | §7.2 above; abuse-model.md slot-channel baseline (behaviour specified by ADR-035 §4 step 5 and §5; its oracle consequence escalated to the owner, then accepted on 2026-11-06, with natural-congestion inference, as one bounded timing-disclosure exception to I9 for the beta of at most 30 accounts, #139) and partitioned-timing entry (not accepted) |
| S-REC lock order | `(38,·)` before shared `(35,1)` (`JSA:204`), argument in §3, `PRT:137` (failed before) |
