# ADR-037: Bounded cohort storage and capacity disclosure

**Status: Proposed — owner acceptance required before implementing this policy.**
No production configuration, storage activation or admission is authorized.

## Why this decision is separate

ADR-036 accepts identity/admission, and explicitly leaves ADR-035's public
resource policy unresolved. Thirty default 2 GiB payload allowances would
promise 60 GiB on a dedicated filesystem reported by Ops at about 44.7 GiB
usable, with a 20 GiB bucket early-stop quota. Payload is not physical footprint:
small parts and metadata amplify it. The existing production ALL/TENANT_LIMITS
refusals stay intact until the corresponding mitigations and release evidence
are complete. Existing D3 physical qualification is reused, not reopened.

## Proposed beta policy

1. **Participant ceilings:** each of the fixed thirty workspaces gets at most
   128 MiB payload and 256 MiB of conservative physical footprint, including
   extracted attachments. Both ceilings apply; this is not a promise that
   128 MiB always fits. At most thirty shares total 7.5 GiB of footprint.
   These are proposed beta ceilings, not claims about existing configuration.
2. **No overcommit:** admission requires a durable reserved footprint share.
   Sum of all thirty reserved participant shares plus explicitly budgeted
   owner/synthetic/legacy workspaces must fit the qualified global footprint
   allowance G_F. Reserve all thirty places before the first participant;
   expired inbox rows and in-flight reservations remain charged. Suspension
   and account deletion do not silently release budget while objects/debt
   remain. No implicit unlimited workspace and no cap increase through config.
3. **Deletion/probe debt:** workspace-attributable pending objects remain
   charged to their originating share until proven deleted. Unattributable
   historical/orphan/probe debt remains in a separately bounded global pool;
   never assign it zero cost. Existing global containment inequalities,
   finalization, probe, metadata and operational reserves still apply.
4. **Uniform SMTP:** no quota/rate/account-state failure changes the valid
   recipient acceptance behavior. Unknown content is never persisted.
   Workspace refusals are recorded privately using the existing refusal
   semantics; API/UI explain payload versus footprint and report own usage.
5. **Envelope invariance (decision A1):** price every syntactically valid
   envelope position against BOTH global prefix totals, including unknown,
   rate-limited or tenant-refused recipients. This is transient conservative
   accounting only, never storage for an unknown address. Property tests must
   prove substituting any foreign recipient with an unknown does not change
   another recipient's intra-event outcome. Preserve provider-event identity
   and the existing recipient normalization semantics explicitly.
6. **Residual disclosure (decision A2):** retain the existing private,
   per-inbox SERVICE_CAPACITY diagnostic. Explicitly accept that cooperating
   workspaces, or other globally constrained conditions, can still correlate
   their own capacity outcomes with changes in global stored data across
   separate events. This is a coarse capacity side channel, not message-content
   access. Non-overcommitted shares reduce the ability of a single workspace
   to force the cliff; they do not prove the channel impossible. This is a
   scoped amendment to ADR-025's recipient-existence confidentiality expectation;
   SMTP acceptance and unknown-recipient non-persistence are unchanged.
   Alternative requiring another design: suppress/delay individual global
   refusal diagnostics. Do not silently claim the recommended option closes
   every cross-event channel.
7. **Availability and operator evidence:** bound active inboxes, API requests,
   waits, participant key inventory and signup metadata. Track own workspace
   refusal and aggregate SERVICE_CAPACITY counters without tenant metric labels.
   Ops supplies and verifies concrete global/debt/reserve declarations in the
   release packet; the code may not invent them from the image's apparent size.
   Admission remains closed if any required budget/configuration is unknown.

## Implementation and release gates

Author the footprint-policy HTTP and SDK contract first. Preserve the one
storage T1/T2 writer, transaction lock ordering, global row-free deletion debt
and paced retention. Add expand-only attribution/configuration migrations and
backup classification; test concurrency, expiry/pending teardown, rollback,
unknown positions, payload/footprint amplification and existing recovery.

Do not remove the production ALL refusal until every explicit mitigation and
required qualification record is independently reviewable and tested. Never
permit TENANT_LIMITS in production. Acceptance of this proposal authorizes
implementation only; actual global numbers, notices and artifact promotion,
Ops configuration and participant activation require the consolidated release
approval. No live host/storage/DNS change is implied.

## Consequences

Finite beta capacity is intentionally smaller than the old observational
payload defaults. One participant cannot consume another's reserved share;
operator/global debt and recovery can still temporarily interrupt service.
Users get accurate private usage and refusal information rather than a false
storage promise. A materially different privacy or resource policy needs a
new explicit decision; numerical capacity increases are never automatic.
