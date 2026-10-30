# Account lifecycle — next implementation design (M3j)

> **Implemented in TI-DELIVERY-002 stream L.** See
> [ti-delivery-002/LIFECYCLE.md](../ti-delivery-002/LIFECYCLE.md) for what
> was built, the operator and restore procedures, the grant delta and the open
> owner decisions. The design below is the unchanged record it implements.

This is the recorded design before implementation, under accepted ADR-036.
It is not an installed operator tool or a participant-activation approval.
The native identity/console foundation is locally verified. ADR-037 was
accepted for implementation on 2026-10-29 after the storage/privacy explanation.
Physical storage/tunnel qualification is not reopened. This document records
lifecycle work still outstanding; only the durable marker foundation below
is now implemented.

## Preserve durable participant classification

V19 now adds the persistent participant-workspace marker, written atomically
by the existing admission provisioner and backfilled from existing admissions.
It contains a workspace UUID, spent place, fixed payload/footprint ceilings and
allocation time, not a contact or session. It survives account/membership AND
workspace removal. Application roles cannot erase or enlarge it. An old
unidentifiable cleared membership stops the upgrade for reconciliation. Otherwise deleting a human account could make its old workspace
look like an unrestricted legacy workspace and revive its machine keys.

A security-barrier view exposes only workspace ID and current eligibility,
derived from the marker, membership and active verified account. Missing
membership/account means inactive, never legacy. The SMTP role gets SELECT on
this view, not the underlying human/control tables. API identity and credential
tables are already being made API-only by the role-template correction.
Marker metadata is control-plane backup data; the view is derived. Restore
must not treat a historical active account as proof of current permission.

## Operator authority and writes

Use the existing API artifact with an explicit non-web operator subcommand,
dispatched before starting Spring's web application/schedulers. Do not add a
new deployable or give the migration executor a second job. Require explicit
DB configuration; never fall back to development credentials. Authorization is
the authenticated, privileged database operator role, rechecked in the write
transaction. No API key or arbitrary request account ID is operator authority.
No public admin endpoint or fake human-owner principal.

One account-state use case owns pause/suspend/resume/deletion-pending writes.
Use the existing session revoker and API-key revoker; extend the latter with an
explicit, verified operator revocation authority instead of adding another
credential writer. Suspension permanently revokes browser sessions and managed
SDK keys. Resuming requires a fresh login and newly issued keys; it must not
revive revoked access. Deletion-pending is not normally resumable, and neither
state releases the spent participant place or any storage still occupied.

Serialize account transitions with participant key/inbox writers using the
existing workspace admission guard. Then take storage's existing `(35,1)` T1
advisory fence and the account row, in that order. Do not introduce an opposing
account→workspace/storage lock path. Admission continues to be the sole
workspace provisioner; revisit its ordering explicitly when M4 adds share
reservation. Authenticate/read paths do not take these write guards.

## Ingestion and in-flight mail

Use the existing T1 global fence and ONE decision snapshot to read eligibility.
The operator's state transition uses that same fence. A prepared recipient
that becomes inactive before T1 is a distinct discard outcome: no reservation,
no upload, no storage-refusal attribution and no false duplicate report.
The valid SMTP response remains uniform. Early recipient resolution also
filters inactive participants, but that alone does not close the prepare/T1
race. No content for an ineligible copy is persisted.

Do not retrofit account changes into T2's qualified lock order or pretend
`persist=false` is an account discard (it means provider duplicate). Copies
already reserved before the suspension fence may finish their existing fenced
operation. The operator reports a transition as DRAINING until every relevant
reservation is consumed/released. It can report suspension complete only after
that proof. T1 refuses new inactive copies, so the zero-reservation check cannot
race with an untracked late admission. Preserve existing accounting/ambiguity
and cleanup; do not delete uploaded objects or queue mail indiscriminately.

## Participant key inventory

Keep the existing sole minter and workspace transaction. Proposed implementation
ceilings for the bounded beta are 20 usable managed keys and 1000 retained key
metadata rows per participant workspace, covering machine-created children as
well as human-created keys. A revoke/expiry frees usable capacity, not retained
audit history. Expose a private, contract-first refusal with the relevant own
workspace dimension; never expose global inventory. Legacy owner/monitoring
workspaces retain their existing behavior. These bounds do not substitute for ADR-037's runtime storage policy.

## Required proof / remaining release work

- Real role-based DB checks, state/credential/session refusal, permanent
  revocation across resume, missing-membership tombstone behavior and rollback.
- Deterministic recipient preparation/T1/suspension and pre-reserved/drain races;
  no altered SMTP reply, false duplicate or unaccounted object.
- Concurrent key inventory/replay tests, no alternate minter or actor issuer.
- Operator CLI must fail without authority, never start a web listener/migrator,
  expose no credentials/contact content and leave legacy traffic operational.
- Deletion must remove retained content through existing lifecycle/deleter
  paths; contact/identity retention and backup deletion/restore decisions must
  be explicit. Deletion-pending alone is not completed erasure.
- Before admitting participants, enforce the semantic rollback floor outside
  the old artifact: an old binary ignores the new human state. Restore stays
  quarantined until deleted/revoked access is reconciled; a backup is never
  current authorization. Reuse/extend the actual Ops restore path and test it,
  rather than claiming expand-only migrations solve semantic rollback.
