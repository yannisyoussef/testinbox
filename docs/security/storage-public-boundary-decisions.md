# Storage public-boundary decisions (TI-STORAGE-006E) — OWNER DECISIONS REQUIRED

**Status: decisions accepted for the bounded beta in ADR-037 (2026-10-29);
implementation and activation evidence remain required.** Each would
change product semantics, so each is prepared here as its own scoped
decision rather than slipped into the containment PRs (owner review b, §5).
Both are **mandatory before `ALL` carries any traffic beyond the synthetic
suite**.

Until then the restriction is enforced, not merely documented:
- `DeploymentSafety` refuses `ALL` in production.
- Staging has no public MX record.
- ADR-035 Amendment 2 §A2.7 makes both decisions activation prerequisites.

Neither issue exists under `OFF`. Neither weakens containment: rule (G)
still refuses. They are a confidentiality leak (A) and an availability
problem (B).

---

## A. The envelope-order recipient-existence oracle

**The problem** (annex §11 item 6).
- Under `ALL`, the copies of one event are admitted against running totals in
  envelope order.
- A tenant can park the global headroom at the cliff. Footprint
  amplification makes that cheap: a 1 GiB-payload workspace of 1 KiB mail
  holds about 29 GiB of footprint.
- It then sends an event with a foreign address **before** its own address.
- If the foreign address exists and is admitted, the tenant's own copy is
  refused, and its own `SERVICE_CAPACITY` refusal record shows it.
- Comparing with a control event reveals whether the foreign address exists,
  and whether that inbox is under its payload limit.
- The SMTP reply stays the uniform `250`. The leak is through the attacker's
  **own** authenticated refusal record.

**Requirements.**
- The SMTP behaviour stays uniform (ADR-025).
- Within one event, no refusal information visible to an authenticated
  tenant may depend on whether a **foreign** recipient exists, or on its
  capacity.

**Two channels, not one.**
1. *Intra-event (envelope order).* The running totals inside one event
   depend on which earlier recipients resolved and were admitted. There
   are two totals: the payload `globalUsed`, and the footprint increment.
2. *Cross-event (persistence).* An admitted foreign copy is stored and
   lowers the global headroom for every later event. An unknown recipient
   stores nothing. An attacker parked at the cliff who sends `{foreign}` and
   then `{own}` learns from its own refusal whether the foreign address
   exists. T1 serializes events, so on a quiet system this is clean.

**Options for the intra-event channel.**
1. **Charge every syntactically valid recipient (recommended).** Every
   envelope recipient adds the copy's increment to **both** GLOBAL running
   totals (payload and footprint), whether it resolves, is unknown, is
   rate-limited, or is refused by a tenant limit. Nothing is stored for
   those that are not admitted: the charge exists only inside the T1
   decision.
   - The prefix totals before any copy then depend only on the envelope's
     length and the message size.
   - Rule (G) stays sound, because it over-charges and never under-charges.
   - Cost: near the cliff, an envelope with unknown recipients may refuse a
     later known copy that would otherwise have fit.
   - Implementation: **not** a one-line change. Today T1 receives only the
     copies that survived recipient resolution (`StorageAdmissionRequest`),
     so the envelope's full recipient count and positions must be threaded
     from `ReceiveInboundDelivery` into the admission request and the
     running totals. It also needs a property test: admission outcomes for
     known recipients are invariant under replacing any other recipient
     with an unknown one.
2. **Pre-event snapshot with slack.** Check every copy against the snapshot
   taken before the event, plus a slack of `(maxRecipients − 1) ·
   F(P_copy_max, N_copy_max)`.
   - Order-independent.
   - But the slack is large: 49 × F(15 MiB raw plus extracted parts, up to
     501 objects). That is ≥ 1.5 GiB of global headroom permanently reserved.

**The cross-event channel is not closed by either option.** Closing it
means making global refusals independent of other tenants' stored data,
which a global ceiling cannot be. The options are:
- **(i) Accept it as a residual.** It is coarse: one bit per pair of events
  at the cliff, observable only while the attacker holds the global
  headroom at the cliff. Decision B's per-workspace share makes holding it
  there impossible for a single workspace. This needs an explicit ADR-025
  amendment.
- **(ii) Report `SERVICE_CAPACITY` only in aggregate.** Delaying or removing
  individual diagnostics obscures the reason but does not remove the channel:
  a sender can still inspect whether its own copy was stored. This is not
  accepted as a privacy fix.
- **(iii) Rely on decision B.** If Σ shares ≤ *G_F*, no workspace can reach
  the cliff on its own, and the oracle needs several cooperating
  workspaces.

**Owner decisions required:**
1. Adopt option 1 (recommended) or option 2 for the intra-event channel, as
   its own PR with the invariance property test.
2. Choose (i), (ii) or (iii) for the cross-event channel. Recommended: (iii),
   with (i) recorded as an ADR-025 amendment for the multi-workspace case.

Both are needed before `ALL` carries traffic beyond the synthetic suite.

---

## B. Single-sender global exhaustion through tiny-object amplification

**The problem** (annex §13).
- Footprint is dominated by per-object overhead: a 1 KiB message costs about
  29 KiB.
- A workspace well within its **payload** quota can fill all of *G_F*. Then
  every tenant gets `SERVICE_CAPACITY` (a `250`, with mail discarded) until
  retention frees space.
- A sender needs no account: one known address at the ingest rate, with 501
  near-empty parts per message (about 13.7 MiB of footprint per copy), fills
  20 GiB in about twelve minutes.
- Containment holds throughout; availability does not.

**Smallest defensible mitigation (recommended): a per-workspace footprint
share.**
- Each workspace may hold at most *S_w* of footprint, F(L_w), checked in T1
  beside rule (G) on the same snapshot. The ledger already carries per-
  workspace bytes and objects, so F(L_w) is exact.
- A copy above it is refused as `WORKSPACE_LIMIT`. That reason, and the
  uniform `250`, already exist, so the tenant sees an existing reason rather
  than a new kind of refusal.
- Object counts are thereby priced in the unit that costs disk. No separate
  inode quota is needed (blocks run out first, annex §3.5).

**Multi-workspace abuse and overcommitment.**
- A sender who knows addresses in *k* workspaces can take *k* shares.
- The shares bound global exhaustion **only if Σ S_w ≤ G_F** (no
  overcommitment). With many workspaces that makes each share small.
- Otherwise the share must be paired with an **edge per-source limit**
  (connection or message rate per source at the Postfix edge, an Ops
  control), because no per-tenant bound can stop a sender spraying many
  tenants.
- Deletion debt has no workspace attribution, so no per-tenant ceiling can
  charge it. Rule (C) and paced retention remain the global bound.
- Payload of `EXPIRED` inboxes awaiting teardown **counts** toward the share
  (it is still in L). Otherwise delete-and-refill grows L past the share.

**Alternatives.**
- A per-workspace object-count ceiling: simpler to explain, but blind to
  bytes.
- An edge per-source limit alone: needs no tenant-visible change, but does
  not stop a single paying tenant.

**Owner decisions required:**
1. Whether to introduce the per-workspace footprint share. It is a **new
   tenant policy**: its default, its exposure in the authenticated API, and
   its SDK surface need their own spec-first change.
2. Whether shares may be overcommitted. If yes, the edge per-source limit is
   mandatory and Ops-owned.
3. The `SERVICE_CAPACITY` alerting and runbook thresholds (annex §13).

## Accepted bounded-beta implementation

ADR-037 accepts envelope option 1 and cross-event (iii) with (i): immediate
own-workspace diagnostics remain, and residual recipient inference under
globally constrained conditions is explicit. Reservation proof also needs
worst-case transient envelope and finalization headroom, not merely Σ shares
≤ G_F. Accepted decisions do not lift production ALL/TENANT_LIMITS refusals.
