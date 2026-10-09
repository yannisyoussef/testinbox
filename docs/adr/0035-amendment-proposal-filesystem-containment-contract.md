# ADR-035 amendment proposal, part 2: the filesystem-containment safety contract

**Status: PROPOSED — not accepted.** Prepared by the application team for owner
review (TI-STORAGE-006E, 2026-10-08; corrected after the owner review of
2026-10-08, which approved the direction and found that admitting on the
copy's own φ under-charges the aggregate bound — §2.4 replaces that rule). It answers the owner's conditional
approval of the filesystem-containment direction: *the mechanism is approved,
the numerical safety model of PR #81 is not accepted as proven.* This document
is that model, re-derived so that every term is defined, every bound has a
stated proof obligation, and every assumption is named as one. Nothing here
changes ADR-035 until the owner accepts it; staging stays `OFF`.

It keeps PR #81's root cause (§1 there) and its measured ENOSPC behaviour
(§2.1 there) as evidence, and **replaces its §4 capacity model**. Where the two
disagree, this document is the proposal.

Notation: *B* is the filesystem block size, 1 KiB = 1024 B, ⌈x⌉_B is x rounded
up to a multiple of *B*.

---

## 0. What is decided here, and what is not

| Decided by this document (if accepted) | Left to the owner (§11) |
|---|---|
| the containment theorem and its proof obligations (§2) | the declared values for staging: *G_F*, *D_budget*, *R_ops*, *M* |
| the per-object footprint bound φ and its closed form (§3) | whether the global ceiling *G* changes unit (payload → footprint) under this name or a new one |
| the footprint ledger, kept by the same statement triggers as today (§4) | whether a debt episode shrinks the GLOBAL ceiling (`SERVICE_CAPACITY`, this document's proposal) or is answered `451` before recipient resolution |
| the deletion-debt ledger and its bound by Ops-observed trash (§5) | whether retention pacing (inboxes torn down later than their TTL when debt is high) is acceptable |
| the split between what admission guarantees and what monitoring detects (§6) | the qualification experiments that turn §3's layout assumptions into measured constants (§10) |
| the `STORAGE_FULL` breaker kind (§8) and gate F (§9) | |

**One sentence.** The kernel bounds what MinIO can allocate; the application
bounds what it *asks* MinIO to allocate, in the same unit the kernel charges
(blocks and inodes), with every term that can hold physical bytes without a
live row — in-flight uploads, late commits, deleted-but-unpurged objects,
MinIO's own metadata — carried by an explicit, conservatively bounded term.
Filesystem exhaustion is then unreachable by TestInbox while the model's
assumptions hold, and detectable the moment they do not.

---

## 1. Terms

Every symbol below has a unit, an owner, a lifecycle and the single point where
it is enforced. *Declared* means a configuration value the application
validates for shape and consistency but cannot verify against the world;
*observed* means a value Ops measures on the host and publishes; *derived*
means computed by the application from rows.

| Symbol | Meaning | Unit | Owner / source | Lifecycle | Enforced at |
|---|---|---|---|---|---|
| *B* | filesystem block size (`f_frsize`) | bytes | declared, verified by gate F against `statvfs` | fixed per filesystem | `DeploymentSafety` (shape), gate F (truth) |
| *O_max* | per-object fixed footprint overhead, above the payload's block rounding | bytes | declared; must be ≥ the qualified maximum of the storage combination (§3, §10) | per qualification record | `DeploymentSafety`, gate F |
| *ε* | proportional allowance for extent-tree metadata of large part files | ratio | at least `1 / min(256, ⌊(B − 12)/12⌋ − 1)` (§3.4): the code refuses a denominator above 256, 168 and 83 at 4, 2 and 1 KiB blocks | fixed | code |
| *K* | `(B − 1)·(1 + ε) + O_max + 1`: the most one object can cost above its payload under the closed form (the `+ 1` absorbs the per-object rounding-up of the ε allowance) | bytes | derived | per deployment | T1 |
| φ(p) | footprint bound of one object of payload *p*: `⌈p⌉_B + ⌈ε·⌈p⌉_B⌉ + O_max` | bytes | derived | per object | T1, ledger reads |
| *P* | payload bytes of a set of objects (what the ledger holds today) | bytes | derived (ledger) | live | T1 snapshot |
| *N* | object count of a set of objects (new ledger quantity) | objects | derived (ledger) | live | T1 snapshot |
| *F(P, N)* | closed-form footprint bound of a set: `(P + N·(B − 1))·(1 + ε) + N·(O_max + 1) = P·(1+ε) + N·K` (§3.3) | bytes | derived | live | T1 snapshot |
| *F_c* | footprint of committed objects: `F(P_c, N_c)` over `message`/`attachment` rows | bytes | derived (ledger bases + deltas) | live | T1 |
| *F_r* | footprint of reserved objects: `F(Σ bytes, Σ cardinality(object_keys))` over `storage_reservation` | bytes | derived | live | T1 |
| *G_F* | global ceiling in footprint bytes, key `global-footprint-limit-bytes`; the payload `global-limit-bytes` is never reinterpreted as it | bytes | declared | per deployment | T1, rule (G) of §2.4 |
| *H_F* | finalize budget in footprint: `procs × 16 × F(15 MiB, 1)`. **F, not φ**: a late object occupies up to `F(p, 1)` under the set premise, and leaving the uncovered class it is charged `ΔF ≤ F(p, 1)` when its debt row is written (§2.4, Lemma 3) | bytes | derived from declarations | per deployment | T1 (held back) |
| *P_F* | probe reserve: room that copies leave unused in rule (C), so that witness and breaker probes — themselves admitted by rule (P) — keep running while copies are refused. **Liveness, not safety**: every probe is admitted against the potential (§2.1, rule P) | bytes | declared | per deployment | T1, rule (C) |
| *L* | live set: `(P_c + P_r, N_c + N_r)` over `message`/`attachment` rows and `storage_reservation` (`RESERVED` and `RELEASING`) | bytes, objects | derived (ledger + reservations) | live | T1 snapshot |
| *D* | unsuperseded debt: `(Σ bytes, Σ objects)` over `storage_deletion_debt` rows that are pending (`incurred_at = 'infinity'`) or whose `seq ≥ started_seq` of the newest observation | bytes, objects | derived | live | T1 snapshot |
| `seq` | `storage_debt_order_seq`: one database sequence orders debt rows against observations; never a wall clock, which can step backwards on failover | — | database | — | debt rows, observations |
| *W* | `trash_bytes` of the newest observation; **⊥ (unbounded) when there is none** | bytes | observed | per observation | T1 snapshot |
| Φ | **the admission potential**: `F(L + D) + W`, one aggregate, rounded once (§2.4) | bytes | derived | live | T1, rule (C) |
| *D_est* | `Φ − F(L)`: the deletion-debt estimate, for metrics and pacing only | bytes | derived | live | metrics, retention pacing |
| *D_budget* | the debt up to which the configured *G_F* stays the binding ceiling (I-C); above it the ceiling shrinks by the excess | bytes | declared | per deployment | `DeploymentSafety` (sizing), retention pacing |
| *M* | budget for everything MinIO allocates that is not an object or trash (`.minio.sys`, tmp residue, caches) | bytes and inodes | declared; observed by Ops; gate F compares | per deployment | gate F, monitor |
| *R_ops* | operational reserve never planned for use (recovery, metadata ops) | bytes | declared, ≥ max(5 % of *C_fs*, 2 GiB) | per deployment | `DeploymentSafety` |
| *C_fs* | data blocks usable by MinIO: `f_blocks × f_frsize` of the dedicated filesystem (ext4 metadata already excluded; MinIO runs as root, so the 5 % root reserve is **not** excluded) | bytes | declared; observed (`statvfs`) by Ops; gate F requires observed ≥ declared | per filesystem | `DeploymentSafety`, gate F, monitor |
| *I_fs* | inodes of the filesystem (`f_files`) | inodes | declared; observed | per filesystem | `DeploymentSafety`, gate F |
| *i_max* | the most inodes one object can consume, amortized directories included | inodes | constant 6 (§3.5) | fixed | code |
| *U(t)* | bytes actually allocated on the filesystem at *t* | bytes | observed (`f_blocks − f_bfree`) | live | monitor (detection only) |
| *T_obs* | newest observation's `started_seq` and `started_at`, both **issued by the server** when the monitor calls `storage_begin_observation()` before measuring (`started_at` is for age only) | sequence value, instant | observed | live | T1 |
| *A_obs* | maximum tolerated observation age before the deployment is "unverified" | duration | declared (proposed 15 min) | per deployment | metric + Ops latch policy; **not** a safety input (§5.5) |

---

## 2. The containment theorem

### 2.1 Statement

Let the storage combination be the qualified one (ADR-035 §9a) **plus** the
filesystem elements of gate F (§9), and let the ledger's object counts be
trusted (§4.5). Under `ALL`, a copy *c* with payload *p_c* in *n_c* objects is
admitted only if, evaluated in T1's single snapshot **after** adding it (and
every copy of the same event admitted before it):

```
(G)  F(L + c) + H_F                          ≤ G_F               policy ceiling (live footprint)
(C)  F(L + D + c) + W + H_F + P_F + M + R_ops ≤ C_fs              containment (the potential Φ)
```

with `W ≠ ⊥` (no observation, no admission). Every **row-free** debt write —
a witness or breaker probe's pending `(0 B, 1 object)` row, and the pending
row the orphan sweep or the ambiguity verifier records for a late object
`(p, 1)` before deleting it — is itself an admission of its load *x*:

```
(P)  F(L + D + x) + W + H_F + M + R_ops ≤ C_fs                    row-free debt write
```

Refused, the probe is skipped (the node reports unhealthy), and the late
object stays where it is and **keeps its §9 write slot**, so no further late
object can take its place (fail closed). Both kinds put bytes in trash that,
once an observation supersedes their row, no row charges any more; a cap on
*rows* therefore bounds nothing under a purge stall, and only admitting each
write against the potential does (found by `FootprintWorldTest`: a row-count
probe cap, and a sweep that freed the slot without (P), both breached
containment).

**(P) is an admission in every respect.** It is evaluated and its pending
row inserted in **one transaction under the global admission lock**, reading
T1's one-statement snapshot — otherwise a (P) write and a T1 copy, or two
(P) writes, could each pass against the same snapshot and together overshoot
by up to `F(15 MiB, 1)` per racing write. It has **T1's preconditions**:
trusted counts and a valid newest observation (watermark, *B*, *C_fs*,
`written_by`); otherwise it refuses, like T1. A retry for a key that already
has a pending row adds nothing to *D* (the upsert does nothing) and needs no
new admission. A **probe PUT** is a single attempt (no SDK retries); if its
outcome is ambiguous, its pending row stays pending until
`ambiguous_at + T_verify`, so a PUT landing late is still covered. The
**ambiguity verifier** behaves as the sweep: a refused late object is kept,
its ambiguity row stays unresolved (keyless crash rows too, while any key
they cover is held) and is retried after `verify_at`. All of this is PR D.

**Progress.** (P) is weaker than (C) by exactly *P_F*, so it starves only
when a copy of the same size would. Φ falls without admissions (observations
supersede debt rows, compaction, purges), and once copies are not being
admitted — the §9.3 latch is set by the first late object detected — a held
late object *x* is admitted as soon as
`F(D_pending) + F(p_x, 1) ≤ D_budget + P_F`. While copies are admitted and
(C) binds, small copies could hold the headroom at *P_F*; so either *P_F* ≥
`F(15 MiB, 1)` plus the probe allowance, or the latch stays set while any
(P)-refused late object is held. The latch **cannot be cleared** while one
is held: otherwise slot exhaustion would answer the post-resolution `W_slot`
`451` for as long as Φ stays high — exactly the stable post-resolution `451`
§5.4 rejects as a recipient-existence oracle. Held refused late objects are
metered (PR D). Under a permanent purge stall nothing progresses; that is
the intended fail-closed behaviour. The static sizing condition is

```
G_F + D_budget + P_F + M + R_ops ≤ C_fs                            (I-C)
```

which only says that while the debt part of Φ stays within `D_budget`, (G) is
the binding rule and tenants get the full declared ceiling; above it (C)
binds, so **deletion debt reduces the available global ceiling** by exactly
the excess (the owner's decision 3). Then at every instant *t*:

```
U_TI(t) ≤ C_fs − R_ops − M                                         (the theorem)
```

where *U_TI* is the bytes TestInbox's objects (classes a–d, f of §2.2, and
probe objects) occupy; *M* bounds class (e) by observation, so
`U(t) ≤ C_fs − R_ops`.

**The physical premise (one, for the whole proof).** Any set *S* of
TestInbox objects occupies at most `F(P_S, N_S)` blocks' worth of bytes,
directory blocks amortized over the objects that share them, and directory
blocks that outlive every object under them assigned to *M* (class e). It is
a statement about **sets**: §3.3 explains why no single object is bounded by
φ. Experiments E1–E3 (§10) measure per-size deltas and **totals ≤ Σφ**;
since `F(P, N) ≥ Σφ(p_i)` for every set (§3.3, proved by property in
`FootprintModelTest`), that evidence implies the premise. Nothing below
assumes more than it: in particular no object is assumed to occupy at most
φ of its payload.

**Not φ_copy.** An earlier draft admitted on `F(L) + φ_copy`. That
under-charges: the closed form rounds per object, so its increment for one
object can exceed φ. For *B* = 4096, *O_max* = 24 576, ε = 1/256 and a
4 096 B object, φ = 28 688 B but `F(4096, 1) − F(0, 0)` = 32 800 B; with
30 000 B of headroom the φ rule admits. Under the premise above the rule is
**physically** unsafe, not only a broken invariant: the object may occupy up
to `F(4096, 1)`. The world test charges each object
`cost(x) = a + ⌊a/d⌋ + O_max + 1` with `a = p + B − 1` and `d = 1/ε` —
admissible, since `Σ cost ≤ F` for every set — and `cost(4096)` = 32 799 B
> 30 000 B. The rules above evaluate the post-admission aggregate exactly;
`FootprintAdmissionTest` and `FootprintWorldTest` pin this counterexample. A conservative additive
charge would also be sound — `ΔF ≤ F(p_c, n_c)` (Lemma 2) — but the exact
aggregate is no more expensive, refuses less, and is what the property tests
check.

The proof is §2.4. Inodes: §3.5 shows that with `I_fs ≥ C_fs / B` inodes
cannot run out before blocks do, so the same inequality bounds them.

I-G is decided inside T1's single snapshot, under the admission lock, exactly
like today's ceilings (ADR-035 §4): the snapshot statement gains the debt sums,
the newest observation and the trust marker. A refusal on (G) or (C) is
`SERVICE_CAPACITY` (`250` and discard, one bit to the tenant, §13d), never a
different SMTP reply (§5.4). Untrusted counts, no observation, an invalid
observation, negative totals, or an arithmetic overflow (corrupt totals) are
not a capacity verdict; they are an infrastructure state, answered `451`
**before recipient resolution** like a latch (§4.5), so the reply is the same
for every recipient. Because an overflow depends on the candidate, the
pre-resolution check evaluates the rules with the **worst-case event**
(the largest copy payload and object count × the maximum recipients); in T1
an overflow anywhere makes the **whole event** `INDETERMINATE`, never a mix of
verdicts (`FootprintAdmission.decide`, PR #83). Negative totals are mapped to
`INDETERMINATE` by the T1 adapter (PR D; today it clamps them to 0). **I-C is static**: checked at startup from declarations,
verified against the real filesystem by gate F and the monitor.

**Under `TENANT_LIMITS` the theorem does not hold.** ADR-035 §14 Phase 4
enforces the GLOBAL scope only under `ALL`; under `TENANT_LIMITS` nothing
bounds Φ, ENOSPC is reachable, and containment there means *fails
closed at ENOSPC* through the `STORAGE_FULL` breaker (§8) and the latch, not
*unreachable*. The filesystem is still the dedicated, preallocated one, so
what fails is TestInbox's storage, not the host.

### 2.2 Decomposition and proof obligations

Everything on the filesystem belongs to exactly one of these classes at any
instant, and each class has a bound. The obligations are what qualification
(§10) and code (tests, §4–§8) must discharge.

| Class of allocated bytes | Bounded by | Obligation |
|---|---|---|
| **(a)** objects with a live `message`/`attachment` row | *F_c* | the set premise (§2.1): any set occupies at most *F* of its payload and count (E1–E3); every row-creating path is T2 for reserved bytes (ADR-035 I4, existing); the ledger counts every row exactly once (existing triggers, extended to counts) |
| **(b)** objects, or their `.minio.sys/tmp` upload files, whose reservation is live (`RESERVED` or `RELEASING`) | *F_r* | a reservation names exactly the keys and bytes it authorizes (existing fence, I7); nothing is written without one (ADR-035 §5, ArchUnit); a reservation is released only after its keys are proven absent (§7) |
| **(c)** objects that may surface *after* their reservation's release (A_F violated up to `T_verify`) | *H_F* | ADR-035 §9: at most one per occupied slot, bounded by `procs × 16` — which needs a **global** cap on unresolved ambiguities (Lemma 3) — each ≤ `F(15 MiB, 1)`. Unchanged argument, footprint unit |
| **(d)** deleted-but-unpurged objects (`.minio.sys/tmp/.trash`) and objects deleted directly whose blocks are not yet freed | *D_est* | §5: every path that moves an object to trash appends a debt row **after** the S3 delete returned; the newest Ops observation measures trash directly |
| **(e)** MinIO's own metadata, caches, listing caches, tmp residue of aborted uploads after their ambiguity resolved, directory blocks not attributed to objects — including the bucket directories a partially drained inbox leaves behind (ext4 never shrinks a directory: an inbox of 100 000 messages torn down in batches keeps a `{inbox}/` directory of ≈ 1 200 blocks) — and the blocks of an unlinked object an in-flight GET still holds open (≤ in-flight GETs × 15 MiB) | *M* | observed by Ops (`du` of `.minio.sys` excluding `.trash`, **plus the directory blocks of the bucket tree**, which `minio_sys_bytes` alone does not see; PR E), gated (`≤ M`), alerted on growth. Witness and breaker probes are **not** here: each is admitted by rule (P) into *D* (§2.1). **Not provable by the application**; stated as a budget with a measurement obligation (§10 E7, at the E2 population, since listing caches and the usage cache scale with the number of prefixes) |
| **(f)** orphans: objects with no row and no reservation | 0 beyond (c) | the only row-deleting paths delete blobs first **and prove the deletion**: `ExpireInboxes` runs the prefix delete, which must fail on any per-key `<Error>` of `DeleteObjects` (today it ignores them — fixed with this contract), and only then deletes rows; `DeleteInbox` only marks, the sweep deletes. A probe object whose writer failed to delete it is removed by the orphan sweep once older than its minimum age (today the sweep skips `_probe/` — fixed). An orphan is then a crash remnant covered by (b)/(c) until the orphan sweep removes it (ADR-035 §9 point 2); a `physical_listed > covered + H` detection latches. Tests pin the order **and the outcome** (§4.6) |

Classes (a)–(d) are bounded by **construction** (database rows and declared
constants); (e) is bounded by **observation**; (f) by **detection**. That is
the honest shape of the guarantee: containment is deterministic for everything
the application allocates, and MinIO's own allocations are budgeted and
watched, with the kernel as the backstop the application never plans to reach.

### 2.3 What the theorem does not say

- It does not bound *payload*. A tenant's view (inbox and workspace limits,
  `/v1/workspace/storage`) stays in payload bytes; the GLOBAL ceiling moves to
  footprint, so a service full of tiny messages refuses with `SERVICE_CAPACITY`
  long before any workspace is near its payload limit (§4.7).
- It does not make ENOSPC impossible on the host; it makes it unreachable by
  TestInbox *while (e) stays within M and the filesystem is what gate F
  verified*. Anything else on that filesystem is an Ops invariant (dedicated
  mount), not an application one.
- It does not depend on MinIO's purge latency, scanner cadence or quota. None
  of them appears in any inequality.

### 2.4 The admission potential and its proof over every transition

**State.** At any instant: *L* = `(P_c + P_r, N_c + N_r)` (rows and live
reservations); *D* = the pending debt rows plus the debt rows whose `seq` is at
or after the newest observation's `started_seq`; *W* = that observation's
`trash_bytes`. For **any** committed observation *k* define

```
Φ_k(t) = F(L(t) + D_k(t)) + W_k        D_k = pending rows + rows with seq ≥ started_seq_k
```

and the uncovered set *X_k(t)*: TestInbox objects physically present that are
in none of *L*, *D_k* or *W_k* (late objects of reservations released before
`started_seq_k` was issued; ADR-035 §9 bounds them by the occupied ambiguity
slots).
`Φ_k` is defined abstractly even after compaction deleted the rows it sums.

**Lemma 1 (soundness, over sets).** For every observation *k* already
started, `U_TI(t) ≤ Φ_k(t) + Σ_{x ∈ X_k(t)} F(p_x, 1)`. *Proof:* every
present TestInbox object is in *L* (a row or a live reservation), in trash
(moved after `started_seq_k`, so its debt row — taken after the move, or
pending — is in *D_k*; or moved before, so it is in *W_k*), or in *X_k*.
Apply the set premise (§2.1) to *L + D_k* and to each member of *X_k*; *W_k*
is already physical. Directory blocks that outlive every object under them
belong to class (e) and are bounded by *M* (§2.2). Double counts only add. ∎

**Lemma 2 (increments).** For every set *S* and object set *c*:
`F(S + c) − F(S) ≤ F(c)`, because `⌈(x + y)/d⌉ ≤ ⌈x/d⌉ + ⌈y/d⌉`. So the exact
aggregate rule is never stricter than charging `F(c)`, and a late object
moved into *D* raises Φ by at most `F(p, 1)`. (The increment is also ≥ φ for
one object — an arithmetic fact the proof does not use: nothing assumes an
object occupies at most φ.) ∎

**Lemma 3 (late objects).** Let Λ_k be the objects that can surface late
relative to *k*: the ambiguous keys of reservations whose release debt row has
`seq < started_seq_k` and whose ambiguity was still unresolved when
`started_seq_k` was issued (a release ordered after it has its debt row in
*D_k*, so its late object is covered there — a phantom count until it
surfaces). ADR-035 §9 bounds Λ_k: each holds one write slot until it is
verified, an event stops at its first non-`Stored` upload (one ambiguous key
per slot), and keyless crash rows cap at the slots, so `|Λ_k| ≤ procs × 16`
— **provided** the slots are bounded globally. Today they are counted per
`node_id`, so node ids that change across deploys while their rows are
unresolved could hold `distinct ids × 16`. Slot acquisition must therefore count the
unresolved rows **no live node answers for** — rows of dead, replaced or
cleanly stopped node ids, and held rows — against **every** live node's 16
slots (PR D, **mandatory**): each node then holds at most `16 − orphaned`, so
the late objects in flight or held stay within `procs × 16` whatever node ids
come and go, even while a (P)-refused late object holds its row beyond
`T_verify`. (A global cap on persisted rows alone does not suffice: it ignores
the slots in flight on live nodes.) Bounding the distinct node ids live
within `T_verify` (gate F) is no longer an alternative. *X_k* ⊆ Λ_k at every instant, and *X_k*
can **grow** after any instant (a member of Λ_k surfaces) as well as shrink.
A member leaves *X_k* into *D_k* only when the orphan sweep or the verifier
records its pending debt row, and that write is **admitted by rule (P)**:
Φ rises by `ΔF ≤ F(p, 1)` (Lemma 2) only if the result stays within
`C_fs − R_ops − M − H_F`; refused, the object stays in *X_k* and keeps its
slot. So *H_F* only ever covers late objects whose slot is still held, and
for every instant *t*, with *A* the last admission of any kind before *t*:
`Φ_k(t) + Σ_{x ∈ X_k(t)} F(p_x, 1) ≤ Φ_k(A) + H_F`. ∎

*Two earlier wordings were wrong.* One claimed *X_k* only shrinks after *A*;
a late landing after *A* contradicts it. The other let the sweep's deletion
free the slot without an admission: the deleted object's trash then stays
charged by nothing once an observation supersedes its row, the freed slot
admits the next late object, and under a purge stall late-origin trash
accrues past *H_F* (`FootprintWorldTest` mutant `SWEEP_UNCHECKED`).

**Transitions.** For the observation *k\** newest at the last admission,
every transition between admissions leaves `Φ_k* + H-charge` unchanged or
lower; every rise that is not a transfer **is** an admission — copies by
(G) and (C), row-free debt writes by (P). Each row is an obligation on code; where it is enforced today is
the status table after this one, and **activation is gated on every row**:

| Transition | Effect on *L*, *D* | Φ_k* | Why exact |
|---|---|---|---|
| T1 creates a reservation | *L* += (p, n) | **rises, checked by (G) and (C)** | the only uncharged-by-construction increase, and it is charged |
| upload of a reserved key | physical only | = | the key is in *L* as reserved |
| T2 consumes a reservation | *L*: reserved −(p, n), committed +(p, n) | = | one transaction; T2 refuses unless the reservation's bytes equal the copy's bytes **and its key count equals the rows inserted** (asserted, not only by construction); and T2 locks the inbox `FOR SHARE` and refuses (`COMMIT_FENCED`, reservation released) unless the inbox is `ACTIVE` or `EXPIRING` — otherwise retention could delete the prefix, the upload land, T2 commit and the cascade write a debt row for an object that never went to trash |
| ADR-026 duplicate | reservation → `RELEASING` | = | still in *L* until released |
| definitive failure, ambiguous upload, expiry | reservation → `RELEASING`, then released | = | release below |
| release (cleanup, after the keys were deleted and proven absent) | *L* −(p, n), *D* +(p, n) | = | the `storage_reservation` delete trigger appends the debt row in the **same statement**; it is incurred after the deletes |
| retention (paced or not) | *L* −, *D* + | = | the inbox's state change to `EXPIRED`/`DELETED` commits **first** (T2 refuses from then on); then each batch deletes exactly the keys of the selected message ids, proves them, and deletes **exactly those ids** in one statement (triggers write the debt row after the trash moves); the inbox row is deleted only when no message row remains |
| partial `DeleteObjects`, crash between S3 and the row delete | none | = | the rows stay, so the objects stay in *L* until a retry deletes them |
| late object found inside a release (reservation postponed `S`) | none | = | still in *L* as reserved; its release later writes the debt row |
| orphan sweep, ambiguity verifier deleting a late object | *X* → *D* | **rises by ΔF ≤ F(p, 1), admitted by (P)** | Lemma 3; a **pending** debt row (one per key, upserted) is admitted by (P) and committed before the S3 delete, re-stamped after the key is proven absent (§5.2); refused, the object stays and keeps its slot |
| witness and breaker probe | pending (0 B, 1) row → probe written → deleted → resolved | **+F(0, 1), admitted by (P)** | the probe writer admits its pending `(0 B, 1 object)` row by (P) and commits it **before** writing the probe, deletes it, and resolves the row after proving it absent; refused, it skips the probe and the node reports unhealthy. *P_F* in (C) keeps probes admissible while copies are refused. A cap on probe ROWS would not do: a probe whose row an observation superseded can stay in trash with no row, and under a purge stall probe trash then grows past `C_fs − R_ops − M` with no admission involved |
| size or key edits of `message`/`attachment` | — | refused | a trigger refuses any change of `raw_size_bytes`, `size_bytes` or `object_key`: shrinking one would lower *L* with no debt row |
| ledger compaction | deltas → bases | = | bytes **and objects** folded in one statement; a pre-V8 compactor's fold is completed by a trigger that folds the objects it drops (§4.5) |
| debt compaction | rows with `seq < started_seq_newest`, never pending, deleted; the watermark rises to `started_seq_newest` in the same transaction | = for Φ_newest | they are superseded by `W_newest`, not lost; observations at or above the watermark cannot be deleted (§5.3) |
| new observation | *k\** may change | — | Lemma 1 holds for every *k*; admissions use the newest |
| purge, metadata | physical only | = | *M* (class e); probe objects are not here — each is admitted by (P) into *D* |
| reconciliation repair, recompute | counts corrected to the rows | corrects | the true potential never changed; WORKSPACE-scope drift found revokes trust in the repair transaction, so admission waits for a clean pass (§4.5) |
| rollback below the containment floor | its T1 does not apply (C), or its deletions write no debt | — | forbidden by the rollback floor: the **first artifact that carries every debt-writing obligation** — rules (G) and (C), pending rows for the sweep, verifier and probes, `deletePrefix` failing on per-key errors — not merely the two rules (`deploy/rollback-floors.txt` names it once they enforce); a rollback above it is covered by the folding trigger, and a roll-forward waits for a clean reconciliation **and** an observation begun after the last distrust event (§4.5) |

**Where each obligation is enforced.** "Tested" above means a test exists
for the enforcement that exists; many enforcements do not exist yet. This
table is the gate: `ALL` and `TENANT_LIMITS` must not be enabled on any
traffic while a row is not enforced, and the activation barrier refuses
until every row is.

| Obligation | Enforced in | Status |
|---|---|---|
| sequence ordering of debt rows and observations; pending rows (`'infinity'`, one per key, never compacted); append-only observations, retention of the newest and of rows at or above the watermark; compaction raising the watermark | V8 (PR #83) | built, tested (`StorageFootprintLedgerTest`) |
| observation order and start **issued by the server** (`storage_begin_observation()`), `written_by` and `observed_at` stamped, an order no walk of the role began refused | V8 (PR #83) | built, tested |
| trust epoch, folding trigger for a pre-V8 compactor, `distrust_epoch` monotone, trust scoped to WORKSPACE drift, missing trust row recreated | V8 + adapter (PR #83) | built, tested (incl. a rollback / roll-forward sequence and a lock race) |
| size and object-key immutability; `storage_delta` `UPDATE`/`TRUNCATE` refused | V8 (PR #83) | built, tested |
| every definer function and V8 trigger function with `search_path = pg_catalog, public, pg_temp`; debt-writing triggers `SECURITY DEFINER` | V8 (PR #83) | built; the temp-schema shadowing attack is replayed by `StorageV8GrantsTest` |
| T2: inbox `FOR SHARE` and an `ACTIVE`/`EXPIRING` fence; the keys of the rows inserted equal the reserved keys (asserted) | PR D (#85) | built, tested (`T2InboxLockTest`, `T2InboxFenceTest`, `T2CommittedKeysTest`) |
| pending debt rows written by the orphan sweep, the ambiguity verifier and the witness / breaker probes, **each admitted by rule (P)** under T1's lock with T1's preconditions (refused: probe skipped, late object kept and held with its slot and the latch); objects sized by `HEAD`, unsized never deleted under `ALL`; single-attempt probe PUTs; a resolver for stale pending rows (V9 `recorded_at`); the ingestion role limited to the probe-only pair | PR D (#85) | built, tested (`RowFreeDebtTest`, `JdbcRowFreeDebtStoreTest`, `RowFreeDebtCleanupTest`, `StorageV8GrantsTest`) |
| reconciliation marks trust after any pass that found no WORKSPACE-scope drift (inbox-only drift, which paced retention leaves on every batch, never starves trust); `testinbox_storage_footprint_counts_trusted` gauge | adapter + use case (PR #83) | built, tested (incl. a real-PostgreSQL distrust-then-paced-deletes sequence) — the gauge is what an operator alerts on until T1 consumes the flag (PR D) |
| `deletePrefix` fails on any per-key `DeleteObjects` error; under `ALL`, retention deletes exact proven message ids per batch, longest-waiting inboxes first, state change first, no row-free prefix delete; reads gated on inbox state; pacing with `T_max` | PR D (#85) | built, tested (`PacedRetentionTest`, `PacedTeardownRepositoryTest`, `MessageReadGatingTest`) |
| T1 reads *L*, *D*, *W*, watermark, trust, the last distrust stamp and observation validity in **one statement** (only where a filesystem is declared); `451` before resolution for untrusted / unobserved / invalid / observed-before-distrust / negative or worst-case-overflowing totals, with T1 as the race backstop | PR D (#85) | built, tested (`FootprintT1AdmissionTest`, `StorageFootprintSnapshotTest`, `FootprintEnforcementTest`) |
| Lemma 3's slot bound: unresolved rows no live node answers for (dead or replaced node ids, held rows) count against **every** node's 16 slots, so `|Λ| ≤ procs × 16` whatever node ids come and go; exhaustion answered before resolution | PR D (#85) | built, tested (`WriteSlotsTest`, `DistrustAndLatchHoldTest`) |
| ArchUnit rule: only the four deleting use cases call the deleting ports (§4.6) | earlier (`DependencyRuleTest.deletingPortRule`) | built, tested (`RogueDeleterFixture`) |
| prompt reconciliation on a distrust event; admission waits for an observation begun after the last distrust event (V9 stamp, the upgrade included); the latch cannot be cleared while a refused late object is held (V9 trigger) | PR D (#85) | built, tested (`StorageAccountingTest`, `DistrustAndLatchHoldTest`) |
| gate F rows (§9), including privileges, sequence `CACHE 1`, bucket directory blocks in *M*, procs / node ids | PR E | **not built** |

**Theorem.** At every instant, `U_TI ≤ C_fs − R_ops − M`. *Proof (by
injection):* let *A* be the last admission and *k\** the observation newest
at *A*. Map every TestInbox object present at *t* to a charge that existed at
*A*: an object of *L(t)* to its reservation or row (both in *L(A)*, or
admitted at *A*); an object in trash to its debt row in *D_k*(A)* or to
*W_k** (or, if its debt row was written after *A*, to the *L(A)* entry it was
transferred from — transfers are exact); an object of *X_k*(t)* or of Λ_k*
still holding its slot to that slot in *H_F* (Lemma 3, at most `F(p, 1)`
each). Here *A* is the last admission **of any kind** — a copy by (C), or a
probe or a late object's pending row by (P) — so everything a row-free write
put in trash is in *D_k*(A)*. The map is injective on charges, so
`U_TI(t) ≤ F(L(A) + D_k*(A)) + W_k* + H_F = Φ_k*(A) + H_F
≤ C_fs − R_ops − M` by (C) or (P) at *A* (*P_F* only makes (C) stricter). The proof needs no monotonicity of *W* and
no recomputation of Φ_k* after compaction. **Base case:** activation (gate F,
§9) requires a completed orphan sweep that began after the counts became
trusted and listed bytes within the covered total, and an observation begun
after the last distrust event, so nothing TestInbox owns is uncounted when
the first admission happens.
**Excluded, as in ADR-035:** commits later than `T_verify` (A_F), and the
window in which a rolled-back artifact whose T1 does not apply (C) runs —
which the rollback floor forbids (§4.5). ∎

**Executable check.** `FootprintWorldTest` (PR #83) drives 400 pinned
worlds of 600 atomic steps each:
- **The steps are split where the contract requires the implementation to
  split them** (V8 and the domain rules exist; the row-free writers, the
  observation walk's caller and T1 footprint admission are PR D / PR E, see
  the status table), and any other step may run in between: upload per
  object; release (keys, then row); retention (blobs to trash, then rows and
  one debt row); the sweep (admitted by (P), pending row, delete, resolve
  under a new order); probes, several in flight (admitted by (P), pending
  row, write, delete, resolve); observation (order, walk, row); compaction
  keyed on the newest-started observation.
- **The world is adversarial under the premise**: every object costs
  `cost(x)` (above), the most the set premise allows and more than φ for most
  payloads; copies often materialize at once; a walk sees only trash present
  at its start and still present at its end; purges are rare, and in a
  quarter of the worlds **stalled** (§5.1: purge latency is unbounded); late
  objects surface whenever the §9 slots allow.

After every step it asserts, from the physical state:
- **injection**, the core of the proof above: every object on the
  filesystem, in trash or not, is covered by a live row or reservation, a
  debt row the snapshot counts, or the newest observation's measured set.
  The only exceptions are late objects whose write slot is still held;
- **the theorem**: physical ≤ *C_fs* − *R_ops* − *M*, and the sharper
  *non-late physical* ≤ *C_fs* − *R_ops* − *M* − *H_F*;
- **Lemma 1** in bytes: physical ≤ Φ + Σ `F(p, 1)` over the uncovered —
  implied by injection under the set premise, so it never catches a mutant
  alone; it is kept as a byte-level cross-check;
- **the admission invariant** on the exact post-admission aggregate. This
  oracle restates the rule, so it proves nothing about a correct rule; it is
  there to catch the φ_copy mutant in random worlds.

Coverage floors (admissions, refusals by each rule, late objects, resolved
and (P)-refused sweeps, resolved and (P)-refused probes, observations
overlapping a trash move, compacted rows, steps within 10 % of the ceiling),
each about half of what the pinned worlds reach, make a vacuous pass fail.

Each committed mutant must be caught in the same worlds:
- **With the admission oracle off**, ten mutants must be caught by a
  *physical* oracle: *H_F* = 0, *D* outside (C), *W* outside (C), probes not
  admitted by (P), a late object's deletion not admitted by (P), the debt row
  before the trash move, the observation's order taken at its end, pending
  rows compacted, resolution keeping the old order, and T1 reading the
  latest-arrived observation. The first five breach the theorem; the other
  five break injection (two of them also the theorem).
- **The φ_copy rule** is caught **physically** by a deterministic
  exact-headroom test built from the owner's counterexample (`cost(4096)` =
  32 799 B > 30 000 B of headroom), and by the admission invariant in the
  random worlds. The random worlds do **not** resolve rounding-sized errors:
  "within 10 % of the ceiling" is about 1.4 MB there, against a φ error of a
  few KiB per object. The deterministic test is what pins that class.

**Not modeled** (each is a code obligation of the status table, with its own
test in the PR that builds it): a two-statement snapshot read, T2 inserting
fewer rows than its reservation has keys, retention after a failed per-key
delete, more late objects than the slots allow, concurrent (P) and T1
admissions (serialized by the admission lock, PR D), ambiguous probe PUTs,
node-id churn, the §9.3 latch. The rule-(P) holes that the random worlds
catch in only one or two seeds are pinned by deterministic exact-headroom
tests in `FootprintAdmissionTest` (a probe during a purge stall with no
probe rows left, and a late object's pending row charged ΔF, not φ).

**One snapshot.** T1 reads *L*, *D*, *W*, the watermark, the trust marker and
the observation's validity in **one statement**. Today *L*
(`JdbcStorageAdmission.readSnapshot`) and *D, W, trust*
(`JdbcStorageLedger.deletionDebt`) are two adapter calls; PR D merges them.
Reading *D* then *L* would under-charge: a release committing in between
leaves *L* and enters *D* unseen by both reads. Reading *L* then *D* only
double-counts, since every transfer goes *L* → *D*. PR D adds a persistence
test for that window, as `StorageAdmissionSnapshotTest` does for payload.

**Mixed-recipient and multi-copy events.** T1 evaluates the event's copies in
envelope order against running totals in **one** snapshot under the
admission lock: copy *i* is checked with copies 1..i−1 that passed already
added, so the event's admitted set satisfies (G) and (C) as a whole.
Narrowest ceiling first: a copy refused by INBOX or WORKSPACE (payload, as
today) adds nothing; GLOBAL refusals are per copy. Different workspaces and
inboxes in one event change nothing above: (G) and (C) are global sums.

**Under `TENANT_LIMITS`** (owner decision 9) the rules are evaluated and
metered, never enforced, so the theorem does not hold: ENOSPC is reachable
and fails closed through `STORAGE_FULL` (§8) and the latch. `TENANT_LIMITS`
is approved for **dark staging qualification only**; it must never be
represented as making filesystem exhaustion unreachable, nor authorize
public traffic.

---

## 3. The per-object footprint bound φ

### 3.1 Layout of one object in the qualified combination

Single-node single-drive MinIO (erasure "SD" mode), versioning off, release
default inline threshold (128 KiB), object key
`{ws}/{inbox}/{message}/raw.eml` or `…/{message}/attachments/{id}`:

| Payload *p* | Files created under `bucket/` | Blocks (ext4, *B* = 4 KiB) | Inodes |
|---|---|---|---|
| ≤ inline threshold | `{key}/xl.meta` holding metadata **and** the data | `⌈(p + m)⌉_B` for xl.meta (*m* = MinIO metadata, hundreds of bytes) + 1 directory block for `{key}/` + 1 for `{message}/` (first object of the message) | 3: `{message}/`, `{key}/`, `xl.meta` |
| > inline threshold | `{key}/xl.meta` (metadata only) and `{key}/{uuid}/part.1` (the data) | `⌈p⌉_B` + 1 (xl.meta) + 3 directory blocks (`{message}/`, `{key}/`, `{uuid}/`) + extent-tree blocks of `part.1` | 5 |

Measured by PR #81's lab A (`statvfs` deltas, 10–200 objects per size): 512 B
→ 12 308 B / 3 inodes; 4 KiB → 16 404 / 3; 128 KiB → 143 401 / 3; 256 KiB →
282 706 / 5; 1 MiB → 1 069 193 / 5; 15 MiB → 15 749 530 / 5. Every row is
`⌈p⌉_B + O` with *O* between 8 212 B (inline, two directory blocks and the
rounding of `p + m` into one block) and 20 890 B (15 MiB: xl.meta, three
directory blocks and one extent-tree block).

### 3.2 The terms the lab did not isolate, bounded by argument

These are the reasons *O_max* is a declared constant to be **qualified**, not
the lab's 21 KiB copied over.

1. **Directory growth.** Directory entries are not free: `{inbox}/` grows by
   one entry (≈ 48 B for a UUID name) per message, `{ws}/` by one per inbox,
   `{message}/attachments/` by one per attachment, and ext4's htree adds one
   index block per ~100 leaf blocks. Amortized per object this is < 128 B;
   the worst single step is one new 4 KiB leaf block. It is charged to
   *O_max*'s slack, never to a shared term.
2. **The `attachments/` directory** of a message with attachments: one more
   directory block and inode, attributable to the first attachment.
3. **Metadata rounding.** For an inline object `⌈p + m⌉_B ≤ ⌈p⌉_B + B` as long
   as `m ≤ B`; MinIO's xl.meta for our objects (one content-type, no user
   metadata, no tags, no versioning) is a few hundred bytes. The extra block is
   inside *O_max*.
4. **Extent-tree blocks of `part.1`** (§3.4): proportional to the file's
   block count, so they get their own term ε rather than a fixed allowance.
5. **A 500-attachment message** (the parser's part cap): `{message}/` holds
   502 entries ≈ 28 KiB ≈ 7 blocks, amortized 56 B per object. Covered by 1.
6. **Zero-byte and one-byte objects** (an empty attachment is legal): inline,
   `⌈m⌉_B` = 1 block + 2 directory blocks = 12 KiB ≤ *O_max*. The ledger
   counts them as objects (`N`), which is exactly why *N* is needed: their
   payload is 0 and their footprint is not.
7. **`message/rfc822` parts** are kept whole, so no object exceeds the 15 MiB
   raw cap (ADR-035 §2), which bounds `F(15 MiB, 1)` and therefore *H_F*.

**Proposed *O_max* = 24 KiB = 6 blocks:** `{message}/`, `{key}/`, `{uuid}/`,
`xl.meta`, one extent-tree index/root block, and one block of slack for
directory growth and metadata rounding. The lab maximum including its extent
block was 20 890 B. The qualification plan (§10, E1–E3) measures every term
above on the real combination; if any measured value exceeds its allowance,
*O_max* is raised **in the qualification record**, and `DeploymentSafety`
refuses a declared *O_max* below the record's measured maximum.

### 3.3 The closed form the ledger can carry

The ledger must not depend on *B* or *O_max*, which are properties of one
filesystem, and the triggers cannot know the payload distribution; but a set
of objects with payload sum *P* and count *N* satisfies

```
Σ φ(p_i) = Σ (⌈p_i⌉_B + ⌈ε·⌈p_i⌉_B⌉) + N·O_max
        ≤ Σ ⌈p_i⌉_B·(1+ε) + N + N·O_max             (each ⌈x⌉ ≤ x + 1)
        ≤ Σ (p_i + B − 1)·(1+ε) + N·(O_max + 1)
        = (P + N·(B − 1))·(1+ε) + N·(O_max + 1)      =: F(P, N)
```

(computed with checked arithmetic, rounding every division up). The `+ N`
is load-bearing: ε is applied per object and rounded up each time, so without
it a set of objects each one byte past a block boundary is under-counted
(`FootprintModelTest` exhibits such a set, and proves the inequality by
property over boundary-biased payloads and non-power-of-two denominators). So the database keeps **payload sums and
object counts**, both environment-independent, and the application applies
the deployment's *B*, *O_max* and ε at read time, inside T1's snapshot. The
same *P* and *N* serve the OFF-mode metrics, so the footprint is observable
long before anything refuses on it.

**F bounds the set; φ bounds no single object.** One PUT can take a new
`{inbox}/` leaf block, an htree split, a `{ws}/` leaf and an `attachments/`
block at once — four blocks against one of slack. Those blocks are amortized
over the objects that share the directories, which is why §3.2's terms are
stated per set and E2 (§10) measures totals, never a single object. This is
the premise of §2.1, and the only one the proof uses.

Conservatism of the closed form against the exact φ: it over-charges each
object by up to `B − 1` of rounding slack — a 15 MiB object by 0.03 %, a 4 KiB
object by 15 %, a 512 B object by 12 % of its true footprint. The tiny-object
regime is dominated by *O_max* in either form, so the closed form costs little
where it matters (§4.7).

### 3.4 The fragmentation term ε

A `part.1` of *n* blocks is stored as extents; on a nearly full, long-lived
filesystem ext4 may be unable to allocate contiguously, and the worst case is
one extent per block. An extent-tree leaf block holds 340 extents (12-byte
entries after a 12-byte header in a 4 KiB block); index blocks hold 340 index
entries. Metadata blocks are therefore at most
`⌈n/340⌉ + ⌈n/340²⌉ + … < n/339 + 2`. The `+ 2` (root/index) is inside
*O_max*'s extent block and slack; the proportional part is bounded by
`ε = 1/256 > 1/339`. For a 15 MiB object that is at most 60 KiB, 0.4 %.
Inline objects have no part file and the term is harmless slack.

**The denominator can only make ε larger, and depends on the block size.**
The worst case is one extent per block and `(B − 12)/12` extents per leaf
block: 340 at 4 KiB, 169 at 2 KiB, 84 at 1 KiB. `FootprintModel` refuses a
denominator above `min(256, ⌊(B − 12)/12⌋ − 1)` — 256, 168 and 83 — and below
16 (a typo, not a policy). A smaller allowance needs its own accepted qualification first, and
then a code change, never a configuration value.

### 3.5 Inodes

Per object: 3 or 5 inodes (§3.1) plus amortized directory inodes (`{inbox}/`,
`{ws}/`, `{message}/attachments/`), each ≤ 1 per message and so ≤ 1 per
object; `i_max = 6` covers every object. The argument is on **charged** blocks,
not physical ones (a 0-byte object physically takes 3 blocks and 3 inodes):
every object is *charged* at least `O_max / B = 6` blocks, so
`inodes used ≤ i_max · N ≤ Σφ / B ≤ (G_F + D_budget) / B`. Trash preserves the
ratio (a trashed object keeps its files until purge). Hence, with

```
I_fs ≥ C_fs / B          (mkfs.ext4 -i 4096 at B = 4 KiB; gate F verifies f_files)
```

inodes cannot be exhausted before blocks, and `(M + R_ops) / B` inodes (about
630 000 at the staging values) remain for MinIO's own (`.minio.sys`, a few
hundred; *M*'s inode budget is 100 000). With `f_frsize = B`, which gate F
requires, `I_fs ≥ C_fs / B` is exactly `f_files ≥ f_blocks`. The staging
filesystem today has 3 145 728 inodes for 12 582 912 blocks (`-i 16384`) and
**fails this condition**; recreating it at `-i 4096` is an Ops change (§9).

### 3.6 What φ does not cover, by design

- MinIO's `.minio.sys` tree (format, config, bucket metadata, usage cache,
  listing metacache, healing/scanner state), the `.minio.sys/tmp` residue of
  aborted uploads once their ambiguity resolved, and `.trash`'s own directory
  blocks: all in *M* (§6).
- Any object TestInbox did not create. The bucket is private to the
  application (acceptance row G); a foreign object is an Ops invariant
  violation the monitor would see as `U > model`.
- Filesystem-level features that change the layout (ext4 `inline_data`,
  encryption, compression, a different block size, XFS) are **different
  combinations** and need their own *O_max* and record (ADR-035 §9a
  invalidation rule extended to the filesystem elements of gate F).

---

## 4. The footprint ledger

### 4.1 Shape

The ADR-035 §10 ledger gains one quantity: **object counts**, kept beside the
byte sums by the same statement-level triggers, folded by the same compactor,
reconciled by the same reconciliation, backfilled by the same recompute.

| Table | Today | Added (V8, expand-only) |
|---|---|---|
| `storage_delta` | `bytes` | `objects bigint NOT NULL DEFAULT 0` |
| `workspace_storage_account` | `base_bytes` | `base_objects bigint NOT NULL DEFAULT 0` |
| `inbox_storage` | `base_bytes` | `base_objects bigint NOT NULL DEFAULT 0` |
| `storage_reservation` | `bytes`, `object_keys text[]` | nothing: `cardinality(object_keys)` **is** the count (I7: the keys are exact) |

The trigger bodies are replaced (`CREATE OR REPLACE FUNCTION`) to aggregate
`count(*)` alongside `sum(bytes)`, and the `HAVING` clause becomes
`sum(bytes) <> 0 OR count <> 0` so an update that moves no bytes and no rows
still appends nothing while a delete of zero-byte attachments appends a row
with `bytes = 0, objects = −k`. An artifact that predates V8 keeps working:
its inserts and deletes run the new triggers and are counted; it never reads
the new columns (ADR-029 rollback stays safe; no `rollback-unsafe`
declaration).

### 4.2 Reads

T1's single snapshot statement (ADR-035 §4, "why one statement") adds the
object sums to the GLOBAL row — `Σ base_objects + Σ delta.objects` and
`Σ cardinality(object_keys)` — plus the unsuperseded debt sums, the newest
observation's `trash_bytes` and the trust marker (§4.5), and returns the
GLOBAL usage as `(P_c + P_r, N_c + N_r)`, `(P_d, N_d)`, *W*. The application
applies `F` **once** to each aggregate it needs — `F(L + c)` for (G) and
`F(L + D + c)` for (C) — never to separately rounded parts. Inbox and
workspace rows are unchanged and stay in payload bytes. All of it is **one
statement** (§2.4 "One snapshot"); PR D merges the two reads that exist
today.

### 4.3 The rule

ADR-035 §4's rule, with the GLOBAL scope in footprint:

```
INBOX:      used_payload + bytesPerCopy ≤ inboxLimit          (unchanged)
WORKSPACE:  used_payload + bytesPerCopy ≤ workspaceLimit      (unchanged)
GLOBAL (G): F(L + A + c) + H_F                     ≤ G_F                (footprint, §2.1)
GLOBAL (C): F(L + D + A + c) + W + H_F + P_F + M + R_ops  ≤ C_fs
```

*A* is the sum of the event's copies already admitted in this snapshot; *c*
is the candidate (its payload and its object count). Narrowest ceiling first,
enforced scopes only refuse — unchanged.

### 4.4 Exactly-once, cascades, updates, failures

| Event | Payload today | Footprint (new) |
|---|---|---|
| T2 commits *k* copies | +bytes per copy, by trigger | +objects per copy, same trigger, same transaction |
| inbox teardown cascades `message` → `attachment` | one negative delta per statement per (workspace, inbox); cascaded attachments carry `inbox_id = NULL` | the same rows carry `objects = −count`; the workspace figure is exact, the inbox figure can only over-count until the inbox row disappears (unchanged argument) |
| `UPDATE` moving a message between inboxes | nets to zero at workspace level | nets to zero in objects too |
| failed upload, definitive | reservation → `RELEASING`, released after proof; charged until then | identical: the reservation's keys are the count |
| ambiguous upload | reservation stays; ambiguity row holds the slot; *H* covers late surfacing | identical in footprint units; *H_F* is sized at `F(15 MiB, 1)` per slot |
| ADR-026 duplicate at T2 | `releaseDuplicates`: the copy's reservation → `RELEASING`; its stored objects are deleted at release | identical; the deletion appends **debt** (§5.2) |
| crash / restart | rows are durable; `recoverDeadGenerations` records keyless ambiguity | identical |
| compaction | folds `bytes` into bases | folds `objects` too, in the same `DELETE … RETURNING` statement |
| reconciliation | `base + Σdelta` vs the §5 derivation | adds `count(message) + count(attachment)` vs `base_objects + Σdelta.objects` per scope; repair sets both bases from one snapshot |
| `storage_account_recompute()` | rebuilds bases | rebuilds `base_objects` too |

### 4.5 Compatibility and rollback

V8 is expand-only: three `ADD COLUMN … NOT NULL DEFAULT 0` (PostgreSQL 11+
fast default, no rewrite), the trigger bodies replaced with `CREATE OR
REPLACE FUNCTION`, new tables (debt, observations, observation walks,
watermark, trust) and a sequence (§5), new refusal triggers that refuse
nothing any artifact does, one function replaced to backfill counts, and a recompute. It takes the
V6 lock set for the backfill so the counts describe exactly the rows that
exist. A rolled-back artifact keeps appending correct deltas through the new
triggers and ignores the counts. Its compactor, however, predates the counts:
it folds the bytes into the bases and deletes the deltas, so their object
counts are lost. After a rollback the counts are therefore UNDER (bytes stay
exact) until the next reconciliation repairs them from the rows; a
rolled-forward artifact finds them exact only after its first reconciliation
or a recompute, and the footprint gauges under-read until then.

**Every V8 function runs on a fixed path with `pg_temp` last.** Every
`SECURITY DEFINER` function and every V8 trigger function sets
`search_path = pg_catalog, public, pg_temp`. Without `pg_temp` listed,
PostgreSQL searches the caller's temporary schema **first** for relations,
and any role with the default `TEMP` privilege can shadow a table: the
security review reproduced the API role compacting real debt rows through a
temporary `storage_filesystem_observation` holding int8 max (and a monitor
defeating the order check through a temporary sequence stand-in).
`StorageV8GrantsTest` replays both attacks. The debt-writing triggers on
`message`, `attachment` and `storage_reservation` are `SECURITY DEFINER`
too: no deployable needs a V8 grant for its deletes (a missing grant used to
fail every T2 commit), and the internet-facing ingestion role cannot write a
debt row — such as a never-compactable pending one — itself.

**Machine-enforced rollback protection.** Counts that may be low must never
admit, and no operator memory is involved:
- **Counts cannot be lost by an old compactor.** A statement trigger on
  `storage_delta` `AFTER DELETE` (`SECURITY DEFINER`, fixed `search_path`, so
  it runs whatever role the old artifact uses) folds the deleted rows'
  `objects` into `base_objects` itself — with the compactor's own `EXISTS`
  filters — whenever the deleting transaction has not set
  `testinbox.ledger_counts = 'v8'` (read with `current_setting(…, true)`).
  The V8 compactor and `storage_account_recompute()` set it (`set_config(…,
  true)`) and fold objects themselves. A pre-V8 compactor's bytes and the
  trigger's objects therefore commit together. `TRUNCATE` and `UPDATE` of
  `storage_delta` are refused by triggers (the ledger is append-only; the
  application roles hold no `UPDATE` on it).
- **Trust is an epoch, compared and set.** `storage_footprint_trust(id = 1,
  distrust_epoch, trusted_epoch)`. It starts untrusted (V8 creates it with
  `trusted_epoch` NULL), and a missing row reads as untrusted; the next clean
  pass recreates it untrusted and then marks it (a restore never brings it
  back, `deploy/backup/scope.txt`). A trigger keeps `distrust_epoch`
  monotone and `trusted_epoch ≤ distrust_epoch`. Every folding by the
  trigger, and every **WORKSPACE-scope** drift found by reconciliation (in
  the repair transaction), increments `distrust_epoch`. Reconciliation takes
  the ledger lock (35,2) **before** its clean check and, in the same
  transaction, sets `trusted_epoch = distrust_epoch WHERE distrust_epoch =
  :read` — a compare-and-set, so a distrust event racing the check is never
  overwritten.
- **Trust is about the global sums.** The global potential is the sum of
  WORKSPACE counts, so only WORKSPACE-scope drift revokes trust or withholds
  it. An inbox-level over-count — a message deleted with its attachments in
  one statement leaves their delta unattributed to the inbox (V6, accepted),
  which is exactly what per-message-batch retention does — never does;
  otherwise trust would flap, and with it every admission under `ALL`.
- **What trust does not stop.** The API role holds `UPDATE` on the trust row
  (it marks it), so it can assert trust it has not proven — the same trust
  level as its existing `UPDATE` on the base figures; a database-side verify
  function that checks the counts itself before marking is a follow-up.
  Reconciliation marks trust after **any** pass that found no WORKSPACE-scope
  drift, including one that repaired only inbox drift; requiring no drift in
  any scope starved trust forever under paced retention (quality review,
  reproduced on PostgreSQL). Until T1 consumes the flag (PR D),
  `testinbox_storage_footprint_counts_trusted` (1/0, 0 when unreadable) is
  what an operator alerts on.
  `testinbox.ledger_counts` is a setting any role can set, which suppresses
  folding: also no more than a role that can already write the bases. A
  distrust event should trigger a reconciliation promptly (PR D): left to
  the 6 h schedule, `ALL` answers `451` until then.
- **Under `ALL`**, T1's single snapshot reads the marker; `trusted_epoch ≠
  distrust_epoch` is an infrastructure state, answered `451` before recipient
  resolution (the pre-T1 check). If the pre-check passed and T1 then sees
  untrusted counts (a race), T1 refuses with the same `451`, the documented
  noise profile of `LOCK_TIMEOUT`. Gate F and the activation barrier refuse
  while untrusted.
- **After a roll-forward**, or any distrust event, admission waits for a
  clean reconciliation **and** for an observation whose `started_seq` is
  after the last distrust event: a rolled-back artifact's orphan sweep and
  probes deleted without debt rows, and trust tracks counts only, so the
  trash baseline must be re-measured (PR D reads it; gate F checks it).
- **A rollback below the containment floor is forbidden**, not detected: the
  floor is the first artifact carrying **every** debt-writing obligation —
  rules (G) and (C), pending rows for the sweep, verifier and probes,
  `deletePrefix` failing on per-key errors — not merely the two rules.
  `deploy/rollback-floors.txt` names it once they enforce, gate F's "mixed
  versions" row uses the same capability, and the fuse stays a real (not
  merely declared) check below it.
- **Sub-resolution oracle windows that remain.** The `STORAGE_FULL`
  half-open race (two events pass `isBlocked()`, the one with a known
  recipient gets the trial's `451`) and the T1 untrusted-race backstop are
  bounded, share the `LOCK_TIMEOUT` noise profile the QUOTA trial already
  has, and are recorded in `abuse-model.md` (PR D).

`check-migration-safety.sh` accepts it without a
declaration; `check-backup-scope.sh` classifies the new tables `-` (derived,
never restored).

### 4.6 A new invariant to pin in tests

**Every row-deleting path deletes blobs first, and proves it.** Today that is
`ExpireInboxes` (prefix delete, then `DELETE FROM inbox`), and `DeleteInbox`
only marks. Three obligations:

- `BlobStore.deletePrefix` fails on any per-key error of `DeleteObjects`
  (a `200` with `<Error>` entries is not a deletion); `ExpireInboxes` already
  retries on the next sweep. A pure-function test pins the response handling.
- A test asserts the order through the sync-hook seam: rows are deleted only
  after a prefix delete that reported no error.
- An ArchUnit rule names **both** deleting ports — `BlobStore.delete*` and
  `StorageInspection.deleteObject` (used by `ReleaseStaleReservations` and
  `VerifyAmbiguousUploads`) — and allows only `ExpireInboxes`,
  `OrphanBlobSweep`, `ReleaseStaleReservations` and `VerifyAmbiguousUploads`
  to call them, each of which has a debt obligation (§5.2). The sweep's and
  the verifier's deletions need the object's size for their debt row, which
  `listKeysOlderThan` does not return: the port grows a sized listing.

Without this, class (f) of §2.2 is unbounded.

### 4.7 Consequence: effective payload capacity

With *B* = 4 KiB, *O_max* = 24 KiB, ε = 1/256 (so *K* = 4 095 + 16 + 24 576 + 1 = 28 688 B), a
20 GiB *G_F* holds:

| Message | Objects | Payload per message | F bound per message | Messages in 20 GiB | Payload fraction of *G_F* |
|---|---|---|---|---|---|
| 1 KiB, no attachment | 1 | 1 KiB | 29.0 KiB | ≈ 722 000 | 3.4 % |
| 10 KiB, no attachment | 1 | 10 KiB | 38.1 KiB | ≈ 551 000 | 26 % |
| 100 KiB, no attachment | 1 | 100 KiB | 128.4 KiB | ≈ 163 000 | 78 % |
| 1 MiB raw + one 700 KiB attachment | 2 | 1.68 MiB | 1.74 MiB | ≈ 11 700 | 96.5 % |
| 15 MiB, no attachment | 1 | 15 MiB | 15.09 MiB | ≈ 1 357 | 99.4 % |

`F(15 MiB, 1)` = 15 818 768 B (φ itself is 15 814 656 B; *H_F* uses the
closed form, since that is what every ledger read uses), so *H_F* = 241.4 MiB
per declared ingestion process (482.8 MiB with a deploy surge of 2).

A test-mail service is dominated by small messages; **the owner should expect
the effective payload ceiling to be 25–60 % of *G_F*** for realistic mixes, and
3 % in the adversarial tiny-message case that today has no bound at all. The
tenant-visible limits do not change; what changes is how early
`SERVICE_CAPACITY` can be reached, and that it is now reached *before* the
filesystem is, rather than after. The REST contract is unchanged; a global
figure is still never exposed (§13d: one bit).

---

## 5. Deletion debt

### 5.1 Why `D_del ≤ G_F` was not a bound

PR #81 budgeted the deleted-but-unpurged footprint at *G_F* "unless retention
is paced". That assumes every object is deleted at most once per purge
interval. Under a repeated cycle — expire, release payload, admit replacement,
expire again before the first generation purged — trash holds **every**
generation deleted since the oldest unpurged one. Purge latency was measured
once, at 250 s, on a near-empty lab; it depends on MinIO's background workers,
the drive, and the trash size itself. No declared constant bounds it, and a
timer is not evidence. So the debt must be **accounted and verified**, not
assumed.

### 5.2 The debt ledger

`storage_deletion_debt(id bigserial, bytes bigint, objects bigint,
incurred_at timestamptz NOT NULL DEFAULT clock_timestamp(), seq bigint NOT
NULL DEFAULT nextval('storage_debt_order_seq'), object_key text, source text)`,
one row per deleting statement. **Pending** is encoded as
`incurred_at = 'infinity'`, so every reader counts it and every compactor of
every version keeps it, with no code change; pending rows carry their
`object_key` and `source`, with `UNIQUE (object_key) WHERE incurred_at =
'infinity'` (an upsert, so retries never add a second row). Written by:

| Path | How | When |
|---|---|---|
| `message`/`attachment` `DELETE` (retention teardown, cascades) | the same statement triggers, `AFTER DELETE`, `sum(bytes), count(*)` | inside the deleting transaction; `ExpireInboxes` ran `deletePrefix` **before** it |
| reservation release after proof (`ReleaseStaleReservations.release`) | a trigger on `storage_reservation` `AFTER DELETE` where `OLD.state = 'RELEASING'`, `bytes, cardinality(object_keys)` | the release transaction; the keys were deleted and proven absent before it |
| T2 consume (`DELETE … state = 'RESERVED'`) | **no** debt row: the objects live on as rows | — |
| orphan sweep, ambiguity verifier deleting a late object | an upsert of a **pending** row for the key with its size (a sized listing or `HEAD`), **committed in its own transaction before** the S3 delete; after the key is proven absent, `resolve_pending_debt(key)` (`SECURITY DEFINER`: `incurred_at = clock_timestamp()`, `seq = nextval(...)`, bytes and objects immutable) | before the delete, then after the proof |
| a crashed pending row | a resolver pass re-proves every pending key absent, then resolves it; one still present is retried, never dropped | each sweep |
| witness and breaker probes | a **pending** `(0 B, 1 object)` row, admitted by rule (P) and committed before the probe is written, resolved after the probe is deleted and proven absent; refused, the probe is skipped and the node reports unhealthy (§2.1) | before the write, then after the proof (PR D) |
| late object found inside a release (reservation still `RELEASING`) | none: the object is still in *L* as reserved; the release's own trigger row comes later | — |

**Why pending, then re-stamped.** A debt row written *after* the delete loses
the debt if the process dies in between; one written *before* breaks the
ordering lemma (an observation starting between the row and the trash move
would supersede the row and miss the object). A pending row is never
superseded, so it counts until the proof; re-stamping after the proof gives
a `seq` later than the trash move. A crash leaves a pending row that
over-counts until the resolver re-proves the key absent and resolves it.
Every row-free deletion path writes one; a path that cannot size its object
does not delete it (fail closed), and counts it in
`testinbox_storage_undeletable_orphans` so it is never silent. The
`storage_record_pending_debt` / `storage_resolve_pending_debt` functions are
in V8 (PR #83); their callers — the sweep, the verifier, the probe writers,
and the sized listing — are PR D, and until then those paths write no debt
(status table, §2.4).

Debt is charged by **footprint**: the application applies `F(bytes, objects)`
at read time, as for every other figure.

### 5.3 The bound, and why it holds

Ops publishes observations (§6): `storage_filesystem_observation(started_seq,
started_at, observed_at, written_by, trash_bytes, used_bytes, avail_bytes,
inodes_total, inodes_used, minio_sys_bytes, source, …)`. The monitor calls
`storage_begin_observation()` **before** the measurement begins: the server
issues `started_seq` (a `nextval`) and `started_at`, and records the walk for
`session_user`. The insert names that `started_seq` and the measured figures;
a trigger stamps `started_at` from the walk, `observed_at = clock_timestamp()`
and `written_by = session_user`, and refuses an order no walk of the same
role began; `started_seq` is `UNIQUE`. `trash_bytes` is `du -B1 -s
.minio.sys/tmp/.trash` (allocated blocks). Then

```
D (the debt in Φ) = pending rows + rows with seq ≥ started_seq             (counts, F applied with L, §2.4)
W                 = trash_bytes of the newest observation                (already physical: no φ)
D_est             = Φ − F(L)                                              (metrics and pacing only)
```

**Lemma (monotone trash).** Trash grows only by TestInbox deletions and
shrinks only by MinIO's purge. So `trash(t) ≤ trash(t_du) + added(t_du, t)`.
Its exact form is `trash(t) ≤ F(D) + W + R(t)`, where *R(t)* is the trashed
footprint that still has a live row or a live reservation (a retention
transaction whose prefix delete succeeded and whose row delete rolled back:
the objects are in trash **and** still in *F_c*, so Φ counts them, and the
theorem is unaffected). Probe objects are admitted by (P) into *D* like
any other row-free deletion.

**Lemma (ordering).** Ordering uses one database **sequence**,
`storage_debt_order_seq`, never a wall clock (a failover or an NTP step can
move a clock backwards; a sequence never does). Every resolved debt row's
`seq` is a `nextval` taken *after* the S3 delete that moved its objects to
trash returned (§5.2: retention deletes the proven keys before the deleting
statement; the release deletes before its statement; the sweep and the
verifier resolve their pending row after the proof). An observation's
`started_seq` is issued by the server when the monitor begins the walk,
*before* its `du`.
Therefore an object moved to trash **after** `du` began has a debt row with
`seq > started_seq` and is in the sum; an object moved before `du` began is
in `trash_bytes`. Objects counted twice (moved before `du`, row written after
`started_seq`) only make the bound larger. Pending rows are never superseded,
so the row-before-delete paths keep the lemma too (§5.2). Hence
`trash(t) ≤ F(D) + W` — and Lemma 1 (§2.4) folds that into Φ.

**Where the lemma could fail, and what pins it:** a path that deletes rows and
only later deletes blobs would create a debt row *before* the trash move (the
object would then be in neither term for the observation taken in between).
That is the §4.6 invariant; it is tested. MinIO moving to trash
asynchronously *after* answering `DeleteObjects` would break the lemma too; the
pinned release renames synchronously inside the request (lab D: the data is in
`.trash` immediately and purged later), and E8 (§10) re-verifies it.

Objects MinIO frees immediately (single deletes in the lab) are simply a purge
that has already happened: `trash_bytes` does not include them, and their debt
rows age out of the sum at the next observation. Nothing depends on which path
MinIO takes.

**Validity of an observation.** The sequence and the clock both sides use are
the **primary** database's; the monitor reads them from it, never from a
replica. `du` measures **allocated** blocks (`du -B1`), never
`--apparent-size`, or small `xl.meta` files would be under-counted.
Observations are **append-only**: a trigger refuses `UPDATE`, and refuses
`DELETE` of the newest row or of any row at or above the compaction
watermark; debt compaction raises a monotone watermark
(`storage_debt_watermark.compacted_through_seq`) in the same transaction as
its deletes. T1 uses the newest observation **unconditionally** and answers
`451` before resolution if it is invalid — `started_seq` below the
watermark, `block_size_bytes ≠ B`, `capacity_bytes < C_fs`, or `written_by`
other than the declared monitor role (`written_by` is stamped `session_user`,
which neither `SECURITY DEFINER` nor `SET ROLE` changes) — and **never falls
back** to an older observation, whose debt rows may already be compacted.
Because the server issues the order and both timestamps, neither an invented
nor a replayed order can become the newest observation, and "newest" is by
`started_seq`, not by insertion order or by any clock, so a late-arriving
stale measurement cannot resurrect an old `trash_bytes`. **What the database
cannot enforce** is that the monitor begins the walk *before* `du` and
writes the figures it measured under that walk, rather than beginning after
`du` or replaying old figures under a new walk: that is the monitor contract
(Ops handoff, PR E) and evidence for gate F. A `du` that fails (anything but "entry vanished mid-walk")
writes no row. Compaction of superseded debt rows (§5.4) is sound because the
observation it keys on is committed before the compaction commits, and a later
T1 statement sees both or neither under READ COMMITTED.

### 5.4 Enforcement

- **Admission (hard).** T1's snapshot includes *D* and *W*, and rule (C) of
  §2.1 checks the potential Φ after the copy. A copy that does not fit is
  refused with `SERVICE_CAPACITY`, exactly as a copy above *G_F* is:
  `250`, discarded, one bit to the tenant (§13d). **Why not `451`.** A `451`
  decided in T1 would be decided *after* recipient resolution, and T1 only
  runs when a recipient resolved (ADR-035 §4 step 4; `ReceiveInboundDelivery`
  answers `250` for unknown-only recipients before any slot). During a debt
  episode — a stable state that lasts until the next observation, or forever
  if observations stop — unknown recipients would get `250` and known ones
  `451`: a recipient-existence oracle (ADR-025, CLAUDE.md invariant 8). A
  ceiling refusal changes no SMTP reply, so it cannot. A `451` that outlived
  the 4 h edge queue would also be silent loss (§12), and purge latency is,
  by §5.1, unbounded. *Alternative for the owner:* evaluate `D_est` in
  `GuardedStorage.unavailable()` before resolution (one more ledger read per
  event, the same `451` for every recipient) and keep the T1 check as a race
  backstop with `LOCK_TIMEOUT`'s noise profile; this document proposes the
  ceiling.
- **Retention pacing (liveness only).** `ExpireInboxes` tears an expired inbox
  down **in bounded message batches** — each batch's prefixes deleted and
  proven, then its rows — and takes the next batch only while
  `D_est ≤ D_budget`; otherwise it waits for the next sweep. The
  grain is the batch, never the inbox: an inbox whose footprint exceeds
  `D_budget` (501 near-empty parts per copy at the INGEST rate reach 8 GiB of
  footprint in about five minutes, far below its payload limit) still drains batch
  by batch, so pacing can always make progress while `D_est < D_budget`.
  Pacing keeps the ceiling at *G_F* through mass expiries at the cost of
  physical reclamation lagging the TTL; the theorem holds with or without it
  (a teardown is a transfer *L* → *D*: Φ is unchanged).
- **Pacing applies only under `ALL`.** Under `OFF` and `TENANT_LIMITS`
  retention tears down as today (no observation is needed, and a missing
  monitor never stops retention); the debt is still metered.
- **Logical expiry is independent of physical deletion.** Pacing delays only
  the blob and row deletion. An inbox past its TTL is expired the moment its
  state says so: it refuses new mail (routing and T2 both check its state)
  and **its messages, raw MIME and attachments are not served** once it is
  `EXPIRED` or `DELETED`. Today those reads only check the workspace, which
  was harmless while hard deletion followed within one sweep; with pacing it
  is not, so the reads gain the state check (`404`, as for a deleted
  message). ACTIVE → EXPIRING → EXPIRED transitions are never paced. Batches
  are chosen fairly across workspaces. The delay is measured
  (`testinbox_storage_retention_backlog_seconds`: the oldest expired-but-not-
  torn-down inbox), alerted above one TTL, and escalates per the Ops runbook
  (raise `D_budget` within I-C, or investigate the purge).
- **Pacing is bounded by `T_max`** (declared). If observations stop, *D_est*
  only grows and pacing would stop teardown indefinitely, leaving a tenant's
  explicitly deleted content on disk against the REST promise that "data
  removal completes asynchronously but boundedly". A batch whose inbox has
  waited longer than `T_max` is torn down anyway — a teardown is the
  Φ-neutral transfer *L* → *D*, so rule (C) shrinks the ceiling instead — and
  an alert fires. Pacing is liveness only; it never carries the theorem.
- **Compaction.** Debt rows with `seq < started_seq` of the newest
  observation, **never pending ones**, are superseded by that observation's
  `trash_bytes` and are deleted by `storage_compact_deletion_debt()`, which
  raises the watermark in the same transaction; the table stays small.

### 5.5 Fail-closed when purge progress cannot be verified

If observations stop (monitor down, database role revoked, host unreachable),
`D_est` is computed from the last observation plus every debt row since: it
is still a sound upper bound, and it **only grows**. The ceiling therefore
shrinks monotonically, retention pacing stops tearing down, and nothing is
ever admitted beyond the containment bound. If **no observation has ever been
recorded** — a fresh deployment, or the table emptied by hand — there is no
trash baseline for pre-V8 objects and probe residue, so `D_est` is
**unbounded**: the ceiling is zero and nothing is admitted under `ALL` until
Ops writes the first observation (gate F requires a live one before
activation, so a non-OFF node never starts in this state except by tampering,
which this rule fails closed on). The observation's age is a metric
(`testinbox_storage_filesystem_observation_age_seconds`, `−1` while no
observation exists), alerted on `age < 0 OR age > A_obs` so that the
never-observed state alerts too; Ops policy may latch admission earlier. No timer in the application
ever *releases* debt; only an observation does.

If observations are **inconsistent** (an observation whose `used_bytes` exceeds
the model, or whose `f_blocks` is below the declared *C_fs*), the monitor
latches (§6) and gate F blocks.

### 5.6 Cycle analysis

Repeat: (1) 10 GiB of inboxes expire; (2) retention deletes their prefixes and
rows: payload accounting falls by 10 GiB, debt rises by ≈ 10 GiB·(1+ε) + N·K;
(3) MinIO's purge begins; (4) replacement mail is admitted; (5) another
10 GiB expires before (3) finished. Under this contract: step (2) is paced in
batches to `D_budget`; if `D_budget` = 8 GiB, the second teardown waits;
admission in (4) continues against rules (G) and (C);
at the next observation `trash_bytes` has fallen by whatever purged, the
superseded debt rows are compacted, and retention proceeds. Physical usage
never exceeds `C_fs − R_ops`. If MinIO never purges, retention stops at
`D_budget`, the ceiling stays at *G_F* until the ledger reaches it (tenants see
`SERVICE_CAPACITY` under `ALL`, nothing under `TENANT_LIMITS`), and the
observation-age alert pages Ops. No cycle accumulates beyond `D_budget` without
shrinking the ceiling by exactly the excess, whatever the purge latency.

---

## 6. Headroom and monitoring: what admission guarantees, what Ops detects

**Admission guarantees** (classes a–d of §2.2): `U ≤ C_fs − R_ops` while
`.minio.sys` stays within *M* and the filesystem is the verified one. This
holds at any write rate, with no polling in the loop.

**Why a monitor cannot be the containment.** 16 writers of 15 MiB reach
≈ 210 MiB/s on the staging host (PR #81 lab). A one-minute poll with 30 s of
reaction admits up to **18.5 GiB** between detection and effect; sizing a
reserve for that would consume the filesystem. PR #81's "preventive headroom"
is therefore replaced by the deterministic terms above; the monitor's job is
to detect **model violations** and to publish the observations §5 depends on.

**The monitor (Ops, every minute, host side):** calls
`storage_begin_observation()` first (on the primary), then measures `statvfs`
of the mount (`f_blocks`, `f_bfree`, `f_bavail`, `f_frsize`, `f_files`,
`f_ffree`), `du -B1 -s` of `.minio.sys/tmp/.trash`, of `.minio.sys` excluding
`.trash`, and of the **directory blocks of the bucket tree** (which no other
figure sees: a partially drained inbox keeps its directory), the mount source
and the image file's allocated vs apparent size, and writes one
`storage_filesystem_observation` row under the walk's `started_seq`. It evaluates, on the
host:

| Check | Action |
|---|---|
| `f_blocks × f_frsize < declared C_fs` or `f_files < declared I_fs` or mount source ≠ qualified | **latch** (`filesystem-identity`) |
| image `allocated < size` (preallocation lost: sparse again) | **latch** (`preallocation`) |
| `used_bytes > Φ + H_F + M` (the application exposes Φ's parts as `testinbox_storage_footprint_bytes{kind}`; *P_F* is a liveness reserve, not a physical class) | alert; **latch** above `+ R_ops / 2` (`model-violation`) |
| `minio_sys_bytes` + bucket directory blocks `> M`, or their inodes > *M*'s inode budget | alert; latch at `2·M` |
| `avail_bytes < R_ops` | **latch** (`headroom`) — this should be unreachable; reaching it is a model failure |
| observation write fails | page (the application's age metric rises) |

Every latch is the existing `storage_admission_latch` (ADR-035 §9): every node
answers `451` before T1 and only an operator clears it. The monitor's checks
are **detection with a bounded delay**; none of the inequalities of §2 depends
on them.

**R_ops** is for recovery, not for racing writers: MinIO needs free blocks to
rewrite `xl.meta` on delete, to write listing caches, and to heal; the
runbook needs room to act. Proposed `R_ops = max(5 % of C_fs, 2 GiB)`.

---

## 7. The containment condition in `DeploymentSafety`

`FilesystemContainment` becomes a required startup check for every non-`OFF`
mode. **`BucketQuotaFuse` stays required as transitional protection** until
footprint admission (rules G and C) is enforced in T1 and gate F exists:
while T1 admits on payload *G* only, the fuse is the only startup check that
links *G* to physical bytes (owner review §6). It becomes optional — never
consulted for safety — in the release that enforces (G) and (C). A non-`OFF`
process refuses to start unless all hold:

| Declaration | Check |
|---|---|
| `filesystem-block-size-bytes` (*B*) | ∈ {1024, 2048, 4096}, equals the qualification record's |
| `object-overhead-max-bytes` (*O_max*) | ≥ the record's `footprint.measuredObjectOverheadMaxBytes`; a multiple of *B* |
| `global-footprint-limit-bytes` (*G_F*) | > `H_F`; `H_F` computed with `F(15 MiB, 1)` (§1) |
| `deletion-debt-budget-bytes` (*D_budget*) | > 0 |
| `minio-metadata-budget-bytes` (*M*) | > 0 |
| `probe-budget-bytes` (*P_F*) | ≥ `F(15 MiB, 1)` + `F(0, 1)` × the probes all nodes may have in flight at once, so probes and a held late object stay admissible by (P) while copies are refused (liveness; until unpurged trash fills it, which the latch and the purge-stall alert cover) |
| `operational-reserve-bytes` (*R_ops*) | ≥ max(5 % of *C_fs*, 2 GiB) |
| `filesystem-capacity-bytes` (*C_fs*) | `G_F + D_budget + P_F + M + R_ops ≤ C_fs` (checked arithmetic, never wrapped) |
| `filesystem-inodes` (*I_fs*) | `I_fs ≥ C_fs / B` |
| backend identity | the existing §9a match, with the record carrying the filesystem elements of gate F |

The application cannot verify that the filesystem *has* `C_fs` bytes or
`I_fs` inodes, is dedicated, or is preallocated: an environment variable is a
claim. **Roles:** the monitor's role may `EXECUTE`
`storage_begin_observation()` and `INSERT` into
`storage_filesystem_observation`, and nothing else; the application role may
`SELECT` it and never writes it; the debt-writing triggers are
`SECURITY DEFINER`, so no deployable needs a grant on `storage_deletion_debt`
to delete rows, and none holds `INSERT`, `UPDATE` or `DELETE` on it
(compaction and pending rows go through the definer functions;
`docs/dev/production.md`). An Ops-written row is the only input that ever
*loosens* admission, which is why it is write-only for Ops, read-only for the
application, bounded by the lemma of §5.3 and refused when invalid.
**Rollback window:** if decision 1 (§11) renames the global-limit key, both
keys stay set until no artifact below the change can be rolled back to, or a
rolled-back non-OFF artifact would refuse to start (`docs/dev/rollback.md`). Gate F (§9) verifies the claim against Ops evidence before activation;
the monitor (§6) re-verifies it every minute and latches on a mismatch. That is
the same division of labour as §9a: declaration matched in the application,
reality observed by Ops with privileges the application does not hold.

---

## 8. The `STORAGE_FULL` breaker kind

The pinned MinIO answers a full filesystem with `507 XMinioStorageFull` or
`500 InternalError` whose message names `no space left on device` (lab B:
3 and 13 of 16). Today both are `Ambiguous(SERVER_ERROR)`: correct for the
reservation (the lab's "16 of 16 absent" is measured, not proven, so the
reservation stays charged and the slot stays held until verification), but
wrong for the breaker, whose `SERVER_ERROR` trial is a zero-byte probe — and a
zero-byte PUT **succeeds on a full filesystem** (lab C1). The breaker would
close on the probe and re-trip on every real upload.

Changes:

- `AmbiguityKind.STORAGE_FULL`: `507` with `<Code>XMinioStorageFull</Code>`
  or with no code at all (a lost or truncated body: the conservative reading,
  since a `SERVER_ERROR` trial is a zero-byte probe a full filesystem passes),
  or `500` whose `<Message>` contains `no space left on device`
  (case-insensitive, bounded body already read). **A `500` is never
  storage-full by status alone**: without that message it stays
  `SERVER_ERROR`; a `507` with another code is not assumed either. The classifier is a pure
  function with a corpus test for both bodies and for a plain `500`.
- `StorageBreaker.Kind.STORAGE_FULL`, tripped by that ambiguity kind
  **instead of** `AMBIGUOUS` (today `GuardedStorage.abandon` trips `AMBIGUOUS`
  for every ambiguous outcome; left as is, the `AMBIGUOUS` witness would pass
  at full, the real-event trial would fail, and each cycle would cost an
  ambiguity row and a slot for `T_verify`). `Trial.needsRealEvent` is true for
  it, and the probe step skips the zero-byte witness for it.
- **The trial is gated by evidence, not by a timer.** A `STORAGE_FULL` trial is
  issued only when the newest filesystem observation **began after the latest
  `STORAGE_FULL` trip** (on the database clock), is younger than *A_obs*
  measured from its start, and shows `avail_bytes ≥ R_ops` **and at least
  `R_ops / B` free inodes** (MinIO answers ENOSPC on inode exhaustion too);
  otherwise the breaker stays open **without consuming a trial** (and without
  a slot). An observation the 507/ENOSPC just contradicted can therefore never
  license a trial, and each failed trial (itself a trip) needs a newer
  observation, so a stale-but-fresh observation cannot spend a slot on every
  backoff.
- **Evidence belongs to one trip generation.** The breaker bumps an epoch on
  every trip. The evidence is read outside the breaker's lock (it is a
  database read) for the epoch current when the read began, and a trial is
  issued only if the epoch is still that one when the lock is taken again. A
  trip landing during the read invalidates the answer, whatever it was.
  Concurrency tests drive the interleavings with latches. A full filesystem is an incident,
  and each failed real-event trial would otherwise hold a write slot for at
  least `T_verify` (longer if (P) refuses its late object); sixteen of them
  would wedge the node for an hour or more.
- The whole-event `451`, the ambiguity row, the held slot and *H_F* are
  unchanged: nothing about ENOSPC is treated as definitive until E3 (§10)
  proves it so across runs, and even then the reservation rule need not
  change.

---

## 9. Activation gate F (filesystem qualification) and the Ops evidence

A new gate in `check-storage-activation.sh`, `F-filesystem`, evaluated from an
Ops-produced evidence file (`--filesystem-evidence`) plus the live
`storage_filesystem_observation` table. It **blocks** unless every row holds;
a missing, stale (older than *A_obs*) or internally inconsistent input is
`NOT RUN`, and NOT RUN blocks.

| Evidence | Rule |
|---|---|
| filesystem identity: UUID, type, mount options, mount source (device or image path) | equals the qualification record's filesystem elements |
| dedicated mount | the mount's source is used by no other mount; `/proc/mounts` lists exactly one mount on it; MinIO's data directory is on it and nothing else is |
| usable block capacity | `f_blocks × f_frsize ≥ declared C_fs`; `f_frsize = B` |
| inode capacity | `f_files ≥ declared I_fs ≥ C_fs / B` |
| preallocation | backing image `allocated == apparent size` (or a block device); not on a thin pool; `fstrim` exclusion recorded |
| host-filesystem isolation | the image lives on a filesystem with ≥ its own size free at creation, or on a dedicated device; `/` exhaustion cannot shrink it |
| physical headroom | newest observation: `used_bytes ≤ Φ + H_F + M` and `avail_bytes ≥ R_ops` |
| deletion-debt | `D_est ≤ D_budget` from the live table and the newest observation |
| trusted counts | `storage_footprint_trust.trusted_epoch = distrust_epoch` (§4.5) |
| base case | a full orphan sweep that **began after** the counts were last trusted and after the last lower-capability node's last heartbeat has completed, and `physical_listed ≤ covered`; and the newest observation's `started_seq` is after the last distrust event |
| mixed versions | no `storage_node` with a capability below the containment floor is live — the capability that carries **every** debt-writing obligation, not merely rules (G) and (C) (an older node admits without rule C, or deletes without debt) |
| procs | the declared *procs* covers every live ingestion node, and slot acquisition counts orphaned rows against every node (Lemma 3, PR D) |
| observation source | the newest observation's `written_by` is the declared monitor role, and the monitor role is the only one holding `EXECUTE` on `storage_begin_observation()` and `INSERT` on `storage_filesystem_observation` (`has_function_privilege`, `has_table_privilege`) |
| privileges | the application roles hold no `INSERT` on observations, no `EXECUTE` on `storage_begin_observation()`, no `INSERT`/`UPDATE`/`DELETE` on `storage_deletion_debt` or `storage_debt_watermark`, and own none of the V8 tables, sequences or functions. Staging, where the application connects as the owner, therefore **fails gate F by design**: every V8 privilege boundary is void there |
| ordering sequence | `storage_debt_order_seq` has `CACHE 1` (`pg_sequences.cache_size = 1`) and every reader and writer uses the primary |
| MinIO metadata | `minio_sys_bytes` + bucket directory blocks `≤ M` |
| qualification identity | `qualification_valid = 1` from the Ops `qualification-check`, extended with the filesystem elements; record id equals the declared one |
| observation liveness | the newest observation is younger than *A_obs* and was written by the expected source |

The existing gates A–E and Q, and the rollback floors, are unchanged. A
changed filesystem (recreated at `-i 4096`, preallocated, new UUID) is a **new
combination**: the staging record `staging-amd64-vmi2932906-2026-10-08` does
not carry filesystem elements and must not be reused silently; the record is
re-issued with them after E1–E8 (§10) run on the recreated filesystem.

---

## 10. Experiments required before acceptance (isolated lab, never the live MinIO)

| # | Experiment | Discharges | PASS |
|---|---|---|---|
| E1 | amplification sweep: 0 B, 1 B, 4095/4096/4097 B, 64 KiB, 131 071/131 072/131 073 B, 1 MiB, 15 MiB; 200+ objects per size; `statvfs` deltas | §3.1–3.2 terms 3, 6 | every per-object delta ≤ `⌈p⌉_B + O_max` |
| E2 | directory growth: 100 000 messages in one inbox, 10 000 inboxes in one workspace, 500 attachments in one message; then a **partially drained** inbox (paced teardown of 99 % of it) | §3.2 terms 1, 2, 5; the premise of §2.1 | total delta ≤ Σ φ; amortized directory cost reported; the directory blocks left after the partial drain are measured by the monitor's bucket-directory figure and fit *M* |
| E3 | fragmentation: fill to 90 %, random deletes, refill with 15 MiB objects, repeat ×5; per-object delta | §3.4 | ≤ `⌈p⌉_B·(1+ε) + O_max` |
| E4 | inode ratio at `-i 4096`: tiny objects until blocks run out | §3.5 | `f_ffree > 0` when `f_bavail = 0`; blocks exhaust first |
| E5 | trash accounting: mass `DeleteObjects` while writing, with an injected per-key delete failure (a read-only object or a full drive) in one run; compare `du .trash` + debt rows vs model | §5.3 lemmas, §4.6 | `trash ≤ D_est + R` at every sample; the injected failure makes retention fail and retry, never delete rows |
| E6 | debt release: observations every 60 s during purge; admission resumes only after an observation | §5.4–5.5 | no release without an observation; monotone estimate between observations |
| E7 | `.minio.sys` growth: 24 h on the E2 population (listing metacache and the usage cache scale with the number of prefixes) with full-bucket listings every 30 min, the scanner, 10 000 aborted uploads and the witness every 30 s | *M* | `minio_sys_bytes + probe residue ≤ M`; inodes ≤ budget; or *M* is raised in the record |
| E8 | ENOSPC corpus: 20 runs to full; capture every failure body; sweep absence at `C_max + 2 min` | §8 classifier; the "measured, not proven" caveat | both response shapes captured; classifier matches all; absence 20/20 (still not treated as definitive) |
| E9 | zero-byte PUT at full and `STORAGE_FULL` trial gating | §8 | breaker stays open without an observation; trials only after `avail ≥ R_ops` |
| E10 | monitor and latch: identity, preallocation and model-violation latches fire on injected faults | §6 | each latch fires; `451` on every node; tenant sees nothing |
| E11 | restart at full, deletes at full, recovery runbook end to end | PR #81 §5 | as PR #81 §7 rows 5, 6, 11 |

E1–E4 fix *O_max* (and confirm ε and *i_max*) in the record — and, because
`F ≥ Σφ`, totals within Σφ discharge the set premise of §2.1; E5–E6 are the
proof of §5 on the real combination; E7 fixes *M*; E8–E9 the breaker;
E10–E11 the Ops side. The application tests of §4–§8 run against the pinned
MinIO in Testcontainers and prove the accounting, not the filesystem.

---

## 11. Owner decisions (review of 2026-10-08) and what remains open

**Decided:**
1. **The global key is `global-footprint-limit-bytes`** (*G_F*). The payload
   `global-limit-bytes` is never silently reinterpreted as it: under a
   non-`OFF` mode with footprint admission enforced, a node refuses to start
   if only the payload key is set.
2. **Effective capacity** (§4.7) is provisionally accepted for staging; §13
   quantifies it for production readiness.
3. **Deletion debt reduces the available global ceiling** (rule C), with
   `SERVICE_CAPACITY` and the unchanged SMTP `250` under `ALL`.
4. **Retention may be paced in bounded message batches**, with logical
   expiry independent of physical deletion (§5.4).
5. **Staging planning values:** *G_F* 20 GiB, *D_budget* 8 GiB, *M* 256 MiB,
   *R_ops* ≥ 2.4 GiB and ≥ 5 % of *C_fs*, *O_max* 24 KiB (provisional, E1–E3),
   *A_obs* 15 min. Planning inputs, not qualification.
6. **A dedicated production MinIO** is required before production enforcement.
7. **`TENANT_LIMITS`** is for dark staging qualification only (§2.4).

**Still open:**
1. Formal acceptance of this corrected contract, and of the merge sequence.
2. Experiments E1–E11 (§10) on the recreated filesystem; *O_max*, *M* and ε
   are assumptions until they pass.
3. The filesystem recreation (`-i 4096`, preallocated, dedicated) and the
   re-issued qualification record with the filesystem elements.
4. The production database privilege review and issue #80 (unchanged
   dependencies).
5. §13's recommendations on fair use, before production readiness.
6. **Before `ALL` on any traffic beyond the synthetic suite** (security review):
   - *Envelope-order oracle.* GLOBAL admission is an envelope-order prefix,
     so a tenant can place a foreign address before its own and compare its
     own refusal records with a control event, learning whether the foreign
     address exists, and whether that inbox is under its payload limit. With
     footprint amplification this is a practical single-tenant oracle (a
     1 GiB-payload workspace of 1 KiB mail holds ≈ 29 GiB of footprint, enough
     to park the global headroom at the cliff and binary-search it with its
     own refusal bits), not a coarse residual. One of these is **mandatory**
     before `ALL` on traffic beyond the synthetic suite: charge GLOBAL for
     every syntactically valid recipient whether or not it resolves; or check
     every copy against the pre-event snapshot with slack
     `(maxRecipients − 1) · F(P_copy_max, N_copy_max)`, where `P_copy_max`
     is the largest stored copy — raw MIME up to 15 MiB **plus** its
     extracted parts, stored as separate objects — and `N_copy_max` its
     object count (not `F(15 MiB, 501)`). Accepting the residual is not an
     option this contract offers: it would need an explicit ADR-025
     amendment.
   - *Single-sender exhaustion.* 501 near-empty objects cost ≈ 13.7 MiB of
     footprint per copy; one known address at the INGEST rate fills 20 GiB in
     about twelve minutes, and every tenant then gets `SERVICE_CAPACITY`. A
     per-workspace footprint or object ceiling (§13) is needed before public
     traffic; the activation barrier refuses `ALL` beyond dark staging until
     it is decided. A per-workspace ceiling bounds global exhaustion by a
     sender who knows addresses in many workspaces **only if the shares are
     not overcommitted** (Σ shares ≤ *G_F*); otherwise an edge per-source
     limit is needed as well. Deletion debt carries no workspace attribution,
     so no per-tenant ceiling can charge it.
   - Whether payload of `EXPIRED` inboxes awaiting paced teardown counts
     toward the workspace limit. **Recommendation: it counts.** Otherwise a
     tenant can delete and refill repeatedly, and its payload awaiting
     teardown grows global *L* past its own bound.

---

## 12. If accepted: the ADR-035 text that changes

§2 (footprint beside payload; *N*), §3 (GLOBAL in footprint, *G_F*), §4 (the
rule), §8 (the `STORAGE_FULL` kind and the evidence-gated trial), §9 (*H_F*;
the quota paragraph becomes the containment theorem; *Q* optional; "a late
object found then is deleted" becomes "deleted only if rule (P) admits its
pending row, otherwise kept with its slot and ambiguity row held beyond
`T_verify`, the latch set and not clearable while it is held"; the verifier
and the orphan sweep retry a refused object after `verify_at`; slots are
capped globally), §9a (the
filesystem elements join the qualified combination; invalidation extends to
them), §10 (object counts; the debt ledger; the observation table; compaction
of superseded debt; and a new sentence beside the ADR-024 carve-out: the
debt ledger has no source rows to be proven against, so its proof is the
observation lemma of §5.3 and experiments E5–E6, not a reconciliation), §14 (gate F; Phase 4 re-checks include observation
liveness), §16 (new metrics: `testinbox_storage_footprint_bytes{kind=committed|reserved|deletion_debt|finalize_budget}`,
`testinbox_storage_filesystem_observation_age_seconds`,
`testinbox_storage_deletion_debt_budget_bytes`; all labels closed), §17 (tests
of §4–§8), §18 (prerequisite 8 replaced by the filesystem prerequisites;
declared values extended). Implementation PR boundaries follow the owner's §6:
(1) footprint model and persistence, (2) admission/retention integration and
debt control, (3) `DeploymentSafety` and the breaker, (4) activation tooling
and acceptance tests. None of them enables anything.

---

## 13. Capacity economics (for production readiness; recommendations only)

With *B* = 4 KiB, *O_max* = 24 KiB, ε = 1/256 (*K* = 28 688 B) and
*G_F* = 20 GiB − *H_F* (482.8 MiB with two processes):

| Workload | Objects per message | Footprint per message | Payload efficiency |
|---|---|---|---|
| 1 KiB transactional mail | 1 | ≈ 29.0 KiB | 3.4 % |
| 10 KiB HTML mail | 1 | ≈ 38.1 KiB | 26 % |
| 100 KiB mail | 1 | ≈ 128.4 KiB | 78 % |
| 200 KiB mail with three 50 KiB attachments (350 KiB stored: raw plus extracted parts) | 4 | ≈ 463 KiB | 75 % |
| 1 MiB with a 700 KiB attachment | 2 | ≈ 1.74 MiB | 96.5 % |
| 15 MiB | 1 | ≈ 15.09 MiB | 99.4 % |

**Inodes.** At one inode per 4 KiB block, a 48 GiB filesystem has 12.6 M
inodes; the worst object takes 6, and is charged at least 6 blocks, so
blocks run out first (§3.5). Object count is therefore already priced into
the footprint; no separate inode quota is needed for containment.

**One tenant against the global ceiling.** Yes: a tenant within its payload
quota can exhaust the global footprint. A workspace at a 1 GiB payload limit
sending 1 KiB messages occupies ≈ 29 GiB of footprint, more than all of *G_F*.
Containment still holds (rule G refuses), but every other tenant then gets
`SERVICE_CAPACITY` until retention frees space. **Recommendations for the
owner (none implemented here):**
- an **object-count or footprint ceiling per workspace** (a fair-use limit in
  the unit that actually costs disk), sized from the tenant mix;
- or a per-workspace **share of *G_F*** (the inbox share already exists for
  payload), refusing a tenant's copies above its share with `WORKSPACE_LIMIT`
  — which bounds a multi-workspace sender only if Σ shares ≤ *G_F*;
  otherwise pair it with an edge per-source limit;
- alerting on `SERVICE_CAPACITY` volume and on the top footprint consumers;
- an operational runbook for `SERVICE_CAPACITY`: it means global footprint
  exhaustion, mail is discarded with `250`, and the first lever is retention.
