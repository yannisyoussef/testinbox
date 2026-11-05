# ADR-035 amendment note — share-partitioned T1 (PROPOSED)

**Status: PROPOSED. Not Accepted.** This note is not part of ADR-035 and
changes nothing in it. It does not authorize release, enablement, opening
admission, or any production use. It does not change any §11 parameter,
matrix entry, threshold or pass criterion, and gate D's verdict is still
computed from mode a (CHOSEN) scenarios only.

**Authority so far.** Owner decision 2026-11-05, verbatim: *"Partitioned
locking: Implementation and testing authorized. Release requires independent
correctness review, passing applicable benchmark criteria, and enforced
safeguards against cohort growth or overcommit. Benchmark success alone is
insufficient. This does not authorize opening admission."*

## Context

Gate D failed at `c10dd3d` because every T1 held the global exclusive lock
`(35, 1)` for 15–24 ms (ADR-035, "Amendment note (2026-11-05, gate D)"). The
correction stays in mode a. Workstream W1 asked whether, for the bounded beta
of ADR-037 (fixed, non-overcommitted shares), T1 can stop serializing every
tenant.

## Proposal

Add a second T1 lock protocol, selected by
`testinbox.storage.admission-locking` (default `GLOBAL` = mode a, unchanged):

- `COHORT_PARTITIONED`: `(35, 1)` SHARED, one exclusive `(38, k)` per involved
  workspace in ascending order, one statement without global sums that also
  evaluates a partition guard in the same snapshot; if the guard does not hold,
  the same call runs mode a.
- The guard and its growth safeguards live in PostgreSQL (V25,
  `storage_partition_bound`): an approved pool with its own recorded bound;
  all thirty participant places plus every legacy share within that bound; every
  involved workspace shared; every write to the share tables takes `(35, 1)`
  exclusive and is refused at commit past the bound.
- Every other holder of `(35, 1)` (rule (P), the lifecycle drain fence, the
  node fence, pool reserve/revoke, beta admission) stays exclusive.

The correctness argument (invariants, lock order, the five dependencies on the
global lock, failure modes, residuals) is
[`0035-benchmark/partitioned-t1-correctness.md`](0035-benchmark/partitioned-t1-correctness.md).

## Text this would amend, if accepted

- §4 "The lock" ("Only T1 takes it") and §11's statement that T1 serializes
  on the global lock: under the partitioned protocol T1 takes it shared.
- §1 I2 ("evaluated in one database snapshot, under one admission lock"):
  the global ceiling would hold by the static partition (ADR-037 §2), checked
  in T1's snapshot, rather than by a global sum under an exclusive lock.
- §11: the PARTITIONED benchmark variant would need its own reading of the
  applicable criteria. **This note proposes none and changes none**; which
  criteria apply to a partitioned T1, on which population, is an owner
  decision.

## Conditions before any acceptance

1. An independent correctness review of the argument and the code.
2. Passing applicable benchmark criteria on the staging host class, decided by
   the owner (benchmark success alone is insufficient).
3. The safeguards stay enforced in code: `DeploymentSafety` refuses the mode in
   production until a reviewed change lifts it; gate D blocks while any node
   exports `testinbox_storage_admission_locking{mode="cohort_partitioned"} 1`.
4. A decision on the residuals listed in the correctness document §7.

## What exists today (implementation and testing only)

Code behind the switch, V25, grants, backup classification, role-model and
gate tests, the production adapter measured by the benchmark's
`--partitioned-variant` (reported apart). Mode a's T1 statement is pinned byte
for byte to its 9d06ab2 text.
