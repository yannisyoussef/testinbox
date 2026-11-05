# Share-partitioned T1: correctness argument (for independent review)

**Status: implementation and testing authorized (owner, 2026-11-05); NOT
released.** This argues that the production code path behind
`testinbox.storage.admission-locking = COHORT_PARTITIONED` keeps every ADR-035
invariant that mode a keeps. The mode is a **PROPOSED** ADR-035 amendment
([`../0035-amendment-note-partitioned-t1-PROPOSED.md`](../0035-amendment-note-partitioned-t1-PROPOSED.md)).
Nothing here changes a §11 criterion or threshold, and nothing here is gate D
evidence.

Owner decision, verbatim: *"Partitioned locking: Implementation and testing
authorized. Release requires independent correctness review, passing applicable
benchmark criteria, and enforced safeguards against cohort growth or
overcommit. Benchmark success alone is insufficient. This does not authorize
opening admission."*

Pointers are `path:line` at the commit that adds this file. `JSA` is
`backend/persistence/src/main/kotlin/email/testinbox/persistence/JdbcStorageAdmission.kt`,
`V25` is `backend/persistence/src/main/resources/db/migration/V25__storage_partition_guard.sql`,
`PAT` is `backend/persistence/src/test/kotlin/email/testinbox/persistence/PartitionedAdmissionTest.kt`.

## 1. What runs

**Switch.** `AdmissionLocking { GLOBAL, COHORT_PARTITIONED }`, default `GLOBAL`
(`application/.../storage/StorageDeclarations.kt`), bound from
`testinbox.storage.admission-locking` on the ingestion deployable only
(`IngestionProperties.kt`), passed to the one T1 construction site
(`IngestionWiring.kt:339`). `JdbcStorageAdmission` refuses to build the
partitioned mode without the cohort read (`JSA:93`). `DeploymentSafety`
refuses it in production and anywhere but `ALL` with a declared filesystem
(`DeploymentSafety.kt:627`). Every node exports
`testinbox_storage_admission_locking{mode}`; the activation barrier's gate D
blocks while any node reports `cohort_partitioned`
(`scripts/check-storage-activation.sh:458`).

**Mode a (switch off) is unchanged byte for byte.** `admit` (`JSA:107`) runs
the original transaction when `locking == GLOBAL`; the statement is assembled
from shared parts, and its SHA-256 for both `readsFootprint` values is pinned
to the 9d06ab2 text (`AdmissionStatementTextTest`). The partitioned statement
is not even built in mode a (`JSA:316`).

**Partitioned protocol** (`JSA:119`, `JSA:162`), one transaction:

1. `SET TRANSACTION ISOLATION LEVEL READ COMMITTED`, `synchronous_commit = on`,
   `lock_timeout`, `statement_timeout`, idle timeout, generic plan — mode a's
   `configureTransaction`, unchanged.
2. `pg_advisory_xact_lock_shared(35, 1)` (`JSA:234`).
3. `pg_advisory_xact_lock(38, k)` for each distinct key of the event's
   workspaces, ascending (`JSA:250`, keys `JSA:606`).
4. ONE statement (`JSA:316`, read at `JSA:433`): the same per-workspace and
   per-inbox CTEs and rows as mode a; no global sum (`g_*` absent, *L* = 0 via
   `FootprintSql.PARTITIONED_COLUMNS`, `FootprintSql.kt:62`); debt, trash,
   trust, watermark, observation and the WHOLE cohort pool (`CohortSql`) read
   and later decided; and the column `partition_ok`, the **guard**
   (`JSA:609`).
5. Guard false ⇒ `setRollbackOnly`, nothing written (`JSA:172`), and the SAME
   call runs the mode a transaction (exclusive `(35, 1)`) from scratch
   (`JSA:126`). Guard true ⇒ production's `StorageAdmission` decides on that
   snapshot (unchanged use case), the reservations are inserted, commit.

**The guard** (`JSA:609`, evaluated in step 4's snapshot; the V25 function is
`STABLE`, so it reads the calling statement's snapshot):

- G1: an APPROVED `storage_cohort_plan` and a `storage_partition_bound` with the
  plan's exact `binding` and `fingerprint`;
- G2: `storage_partition_within_bound()` (`V25:47`): 30 × 128 MiB + Σ legacy
  payload ≤ the bound's payload capacity, 30 × 256 MiB + Σ legacy footprint ≤
  its footprint capacity, ≤ 30 participant rows, every participant row at
  V19's pinned figures;
- G3: every workspace of the event holds a share (participant or legacy).

**The bound** is `CohortStorageBudget.partitionCapacity`
(`domain/.../CohortStorageBudget.kt:103`): `G − H − W_bytes` and
`min(G_F − F(W) − H_F, C_fs − F(W) − H_F − U − T − P_F − M − R_ops)` (W the
worst envelope, U/T the unattributed and trash allowances). By construction,
and proved by property (`PartitionCapacityTest`, `PartitionCapacityRuntimeTest`),
`assess(declarations, legacy) is Fits ⇔ 30·P + Σ legacy ≤ payload bound ∧
30·F + Σ legacy ≤ footprint bound`. It is written only by the operator's pool
reserve, in the reserving transaction, for that plan's binding and fingerprint
(`JdbcCohortBudgets.kt:107`, computed at `ReserveCohortStorage.kt:41` after
`assess` is `Fits`).

**Database safeguards against growth** (`V25`):

- S1 fence: a statement-level BEFORE trigger on `storage_legacy_share`,
  `participant_workspace`, `storage_cohort_plan`, `storage_partition_bound`
  (insert, update, delete, truncate) takes `(35, 1)` EXCLUSIVE (`V25:64`,
  `V25:87–93`), whoever writes (application, operator, owner SQL).
- S2 bound check: a DEFERRED constraint trigger refuses at commit any state in
  which a bound exists and G2 fails (`V25:75`, `V25:96–100`), whoever writes.

## 2. Invariants

Let `U_w` be workspace *w*'s payload usage (base + Σ delta + Σ reservation),
`C_w` its attributed cleanup charge, `share_w = (P_w, F_w)` its payload and
footprint ceilings. Mode a's readiness, which the partitioned T1 still reads
and decides in its own snapshot (`CohortStorage.kt:118–139`), requires:

- R1 `unknown = 0`: every `workspace` row has a share (`CohortSql.kt:46`);
- R2 no share overallocated: `U_w ≤ P_w` and `F(U_w + C_w) ≤ F_w`
  (`CohortStorage.kt:142`);
- R3 the plan approved for this node's binding, its fingerprint equal to the
  current legacy inventory, `assess = Fits` for this node's declarations, nodes
  ready, a fresh valid observation (`CohortStorage.kt:133`);
- R4 `F(D_u) ≤ U` and observed trash `≤ T`.

Under `ALL` any failure throws `StorageFootprintUnavailableException` (451 for
the whole DATA), in BOTH modes, from the same rule code. In a partitioned
deployment the pool is always required (`CohortStorage.kt:55`: a plan exists
because G1 needs one).

**I-W (per-workspace).** For every shared *w*, at every committed state,
`U_w ≤ P_w` and `F(U_w + C_w) ≤ F_w`.

*Proof.* Writers that can raise `U_w + C_w`:
(a) a T1 admitting copies to *w*. A partitioned T1 holds `(38, k(w))`
exclusive from before its snapshot to commit; a mode a T1 holds `(35, 1)`
exclusive, which conflicts with every partitioned T1's shared `(35, 1)`. So T1s
that admit to *w* are totally ordered, each reads after the previous committed
(READ COMMITTED, statement after lock), and each admits a copy only if
`U_w + f ≤ min(policy, P_w)` (`StorageAdmission.kt:293`) and the footprint check
holds (`StorageAdmission.kt:295`), over running totals for its own copies.
(b) Rule (P): pending debt for a canonical key is attributed to *w* and raises
`C_w` (`V20:38`). It holds `(35, 1)` EXCLUSIVE (`JdbcRowFreeDebtStore.kt:52`),
so no T1 is in flight, and it admits only against the global (P) inequality on
the full *L* and *D* (mode a's figures). If it leaves *w* over `F_w`, R2 fails in
every later snapshot, and every T1 in both modes answers 451 until the charge
is released — fail closed, as in mode a.
Every other writer lowers or moves bytes WITHIN *w*: T2 (reservation →
delta), the compactor (delta → base), retention and cleanup (usage → debt and
its attributed charge in the same transaction, `V20:65`, `V20:145`), charge
release (lowers). A move within *w* leaves `U_w + C_w` unchanged in bytes and
objects. ∎

**I-G (global).** At every committed state while the guard can hold, the
global payload usage `Σ U ≤ G − H` and rules (G) and (C) hold.

*Proof.* By R1 and the foreign keys of `workspace_storage_account` and
`storage_reservation` to `workspace`, every byte of usage belongs to a shared
workspace. By I-W, `Σ U ≤ Σ_w P_w ≤ 30·P + Σ legacy ≤` payload bound `= G − H − W_bytes` (G2; participant rows ≤ 30 at P). For
footprint: `F` is subadditive (block rounding and the ε ceiling are), so
`F(L + D + A + c) ≤ Σ_w F(U_w + C_w) + F(D_u) + F(A + c)` because every debt
row in *D* belongs to a shared workspace's charges or to `D_u`
(`storage_attribute_cleanup_charge`, `V20:38`; reserve back-fills legacy
charges). With I-W, R4 and `F(A + c) ≤ F(W)` for any event the gateway can
produce (≤ 50 copies of ≤ 2 × max object, ≤ 501 objects each), (G) and (C)
reduce to the bound's two inequalities. ∎

Concurrent partitioned T1s for disjoint workspaces each preserve their own
I-W and nothing else, so their interleaving preserves I-G: the global bound
never depended on a global read, only on the static partition (G1–G2) and the
per-share invariant.

**I-E (equivalence with mode a).** For every snapshot in which the guard holds
and R1–R4 hold, mode a's global rules admit every candidate (I-G with the
event's own prefix ≤ W), and the partitioned rules evaluate the same rules on
smaller global figures (*L* = 0, global payload 0), which admit too. The
per-workspace, per-inbox, lifecycle and readiness inputs are byte-identical
SQL. So both modes reach the same decision; when the guard fails the event is
decided by mode a itself. `PAT:225` runs one sequence under both modes and
compares; `PAT:432` shows an un-approved pool refused identically.

**I9 (no per-tenant 451).** The fallback is to mode a, never a refusal:
a guard failure for one tenant's event (e.g. G3, an unshared workspace) gives
that event mode a's outcome, which is a global 451 only when the deployment-wide
readiness fails, exactly as in mode a. Refusing instead would make a 451 depend
on the recipient's share, an oracle I9 forbids. This is why the safeguard falls
back rather than refuses.

**One statement (§4).** The partitioned figures, the cohort pool and the guard
come from one statement (`AdmissionStatementTextTest` asserts no `;`); the
`STABLE` guard function sees that statement's snapshot.

**Other invariants untouched.** I3–I7 (fence, T2, release, ownership), the
write slot, `t0 = now()`, `synchronous_commit = on`, the reservation insert,
the timeouts and their 451 classification (`bounded`, applied to both
transactions) are mode a's code. No content dedup, raw-first storage, unknown
recipients and SMTP replies are not touched.

## 3. Lock order and deadlock freedom

| Holder | Order |
|---|---|
| partitioned T1 | `(35,1)` shared → `(38,k)` ascending → row `KEY SHARE` of the insert's FK |
| mode a T1 | `(35,1)` exclusive → FK `KEY SHARE` |
| rule (P) | `(35,1)` exclusive → (definer writes) |
| lifecycle fence | ADR-027 guard → `beta_cohort` row (cohort ops) → `(35,1)` exclusive → account row |
| beta admission | `beta_cohort` row → `(35,1)` exclusive → inserts (S1 re-enters) |
| pool reserve / revoke | `beta_cohort` row → `(35,1)` exclusive → share writes (S1 re-enters) |
| node fence (V21) | `storage_node` row → `(35,1)` exclusive |
| share-table writer (S1) | share row(s) → `(35,1)` exclusive |

- Partitioned T1s among themselves: `(35,1)` shared never conflicts with
  shared; `(38,·)` keys are taken in one global ascending order (`unnest` of a
  sorted array consumed in order by the aggregate, `JSA:250`), so no cycle.
  Keys are `UUID.hashCode` (specified by the JDK, identical on every node); a
  collision only serializes two workspaces.
- A partitioned T1 waits only on `(35,1)` (for an exclusive holder) and
  `(38,·)` (for another partitioned T1). It takes no row lock before its
  insert and holds none another `(35,1)` waiter needs before `(35,1)`, except
  the FK `KEY SHARE` on `workspace`, which conflicts only with deleting or
  re-keying a workspace (mode a has the same). An exclusive holder never
  takes `(38,·)`. So no cycle through `(35,1)`.
- The fallback never upgrades a lock: the partitioned transaction ends
  (rollback, all locks released) before mode a's begins.
- S1 takes `(35,1)` after a share row lock. A cycle needs a holder of
  `(35,1)` that then waits on that row: only an operator transaction that
  locked `(35,1)` first (reserve) and writes the same row, against a concurrent
  owner-SQL edit of that row. PostgreSQL detects it and aborts one (operator
  vs operator; documented).
- Starvation: PostgreSQL queues a new lock request behind a waiting
  conflicting one, so a stream of shared holders cannot starve an exclusive
  waiter, and each waits at most `lock_timeout` (5 s) before 451.

Tests: `PAT:302` (12 threads, multi-workspace events in random order, no
deadlock, no share exceeded), `PAT:271` (same workspace serialized).

## 4. The five dependencies on the global exclusive lock

Every production holder of `(35, 1)` other than T1, located by search of all
main sources and migrations:

| # | Dependency | Holder | Preserved because |
|---|---|---|---|
| 1 | per-workspace races | (T1 itself) | `(38, k(w))` exclusive per workspace, snapshot after it (I-W); `PAT:271`, mutant `PAT:286` over-admits without it |
| 2 | lifecycle drain fence | `JdbcAccountLifecycle.lockStorageFence` (`JdbcAccountLifecycle.kt:49`), via `lockCohortAndFence` | still EXCLUSIVE: waits for every in-flight shared holder and blocks new ones until commit; eligibility is read in T1's statement after; `PAT:340` (both directions) |
| 3 | rule (P) debt writes | `JdbcRowFreeDebtStore.admit` (`JdbcRowFreeDebtStore.kt:52`) | still EXCLUSIVE and still decides on the full *L* + *D* in its own snapshot; `PAT:375` |
| 4 | node fence, pool reserve/revoke, beta admission | V21 `storage_cohort_node_fence` (`V21:28`), `JdbcCohortBudgets.lockClosed` (`JdbcCohortBudgets.kt:24`), `JdbcBetaAdmissions.lockPolicy` (`JdbcBetaAdmissions.kt:31`); plus the new S1 on every share-table write | all EXCLUSIVE; S1 extends the fence to writers that never took it (owner SQL); `PAT:462`, mutant `PAT:478` |
| 5 | unbudgeted / unshared workspaces, no cohort | (guard) | G3 and G1 fail ⇒ mode a in a new exclusive transaction; `PAT:451`; without a cohort, `PAT:495`: the guard sends T1 to mode a whose global sum refuses, the guard-less mutant over-admits past G − H |

## 5. What the safeguard enforces, and where

| Requirement | Enforced by | Where | Fails |
|---|---|---|---|
| partitioned only for an approved pool with its own recorded bound | G1 | T1 statement (PostgreSQL, same snapshot) | to mode a (`PAT:521`) |
| non-overcommit: all shares within the capacity bound | G2 in T1; S2 at every commit | PostgreSQL | T1 to mode a; growth refused (`PAT:390`, mutant `PAT:414`) |
| closed cohort: event's workspaces shared | G3 | PostgreSQL | to mode a (`PAT:451`) |
| closed cohort: no other workspace, no overallocation, allowances, nodes, fingerprint | R1–R4 | application rules on the same snapshot (mode a's readiness, unchanged) | 451 for every event, as mode a (`PAT:432`) |
| growth atomically ordered with every T1 | S1 | PostgreSQL triggers | the writer waits (`PAT:462`) |
| bound equals the proof | `partitionCapacity` ⇔ `assess` | domain, property-tested | — |
| not in production, only `ALL` + filesystem | `DeploymentSafety` | startup | refuses to start |
| visible to activation | metric + gate D | `check-storage-activation.sh` | gate D BLOCKED |

## 6. Failure modes

- **Bound missing, stale, or from another binding/fingerprint** (a restore —
  the bound is never backed up, `deploy/backup/scope.txt` —, a re-reserve by an
  older binary, revocation): G1 fails, mode a decides. Liveness only.
- **Overcommitting share write by anyone**: S2 refuses the commit; if S2 were
  dropped, G2 still sends T1 to mode a, whose readiness refuses (`PAT:414`).
- **Growth within the bound** (fingerprint changes): G1 still holds (plan and
  bound unchanged), R3 fails in the same snapshot: 451 in both modes until a
  reserve.
- **A new unshared workspace**: its events go to mode a; R1 then fails for
  every event in both modes (451) until it is shared or removed.
- **Rule (P) overallocates a share**: R2 fails; 451 in both modes.
- **Mixed fleet** (some nodes GLOBAL, some partitioned, or an older binary):
  exclusive holders exclude shared ones; safe. Gate D blocks it anyway.
- **Lock timeouts**: 451 as in mode a; `(38,·)` waits add to a hot
  workspace's latency, not to others'.
- **Node heartbeats** take `(35,1)` exclusive through the V21 trigger: each
  briefly drains all in-flight partitioned T1s. Throughput effect, measured by
  the benchmark variant; not a safety issue.

## 7. Residuals for the reviewer

1. R1–R4 are application rules over database figures (mode a's code, same
   snapshot), not SQL. The database enforces G1–G3, S1, S2; a reviewer may ask
   for R2 (overallocation) to move into the guard as well.
2. `storage_delta` has no foreign key: I-G assumes no positive delta exists for
   a workspace without a share (only corruption could create one; I8
   reconciliation reports drift).
3. Subadditivity of `F` and `F(A + c) ≤ F(W)` are stated, not machine-checked
   here (`FootprintModelTest` covers the closed form).
4. The benchmark variant runs on a closed 31-workspace population with
   usage-neutral deltas and retention in the legacy share
   (`docs/dev/storage-benchmark.md`); its informational criteria compare
   against references from the gate population.
