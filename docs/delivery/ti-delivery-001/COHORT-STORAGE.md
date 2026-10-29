# Bounded beta storage — implementation checkpoint

Authority: ADR-037, accepted for implementation by the owner on 2026-10-29
following the storage/privacy explanation. No deployment/admission approval.

## Implementation order

1. Carry normalized distinct envelope positions through the existing receive,
   guarded write and T1 path. Price every preceding position against both
   global totals, including unknown/rate/tenant refusals. Keep only real
   candidates in reservation scopes and persisted objects; preserve event
   identity, rate-limit order and exact raw/attachment copy bytes.
2. Calculate the non-overcommitted capacity proof with all thirty 128 MiB
   payload / 256 MiB footprint shares, explicit legacy shares, worst-event
   transient pricing, finalization and containment/debt/trash/probe/metadata
   reserves. Missing/invalid/overflowing declarations cannot mean ready.
3. Persist shares and their proof, bind provisioning atomically through
   AdmitBetaParticipant, attribute deletion debt and fence every new copy in
   the existing one-snapshot T1. Usage APIs/SDKs must show the same effective
   own-workspace ceilings as admission, with private footprint diagnostics.
4. Complete participant lifecycle, restore/rollback floors and independent
   release evidence before changing the production ALL restriction. Do not
   enable TENANT_LIMITS in production. Global accounting is not an isolation
   guarantee for every failure state; preserve the accepted residual notice.

## Envelope accounting (M4a)

Global prefix cost uses the candidate's original zero-based envelope position
plus one, multiplied by exact bytes/objects per copy, rather than the number
of admitted predecessors. Transient prices do not create fake reservations,
store unknown content or change a valid SMTP response. Local inbox/workspace
running usage still adds only admitted copies. Empty candidate events return
without T1; existing pre-resolution storage health checks still apply equally.

This is a scoped semantic change: near capacity, a discarded foreign position
can cause a later copy to be refused even though physical room remains. It
closes the within-event substitution channel; it does not claim to close
cross-event inference. No global usage is returned to participants.

## Capacity is evidence, not a default

Existing code bounds an event at fifty normalized distinct recipients, 15 MiB
raw per copy, up to another 15 MiB of extracted content, and 501 objects per
copy. Use the actual FootprintModel and checked arithmetic for the event;
never equate payload with filesystem bytes or apply a guessed percentage.
The global declarations, legacy inventory and cleanup reserves remain Ops
release evidence, not values inferred from the prepared image's size.

## Still incomplete

The durable reservation/provisioning and per-workspace footprint/debt path,
matching HTTP/SDK/UI visibility, lifecycle/restore safety and release package
are not complete. Tests of envelope accounting alone do not complete M4 or
make third-party registration ready.

## M4b — conservative cohort feasibility arithmetic

Implemented the fixed 128 MiB/256 MiB share constants and a checked capacity
calculation reserving all thirty places, explicit legacy shares, a complete
worst-case transient envelope and finalization. It independently checks global
payload, global footprint and filesystem containment; unattributed deletion
debt, trash, probe, metadata and operational reserves are additional. Missing
declarations or arithmetic overflow return Unproven, never a zero-sized fit.
The shared factory uses actual protocol/configuration maxima, without OFF
reference defaults as supposed capacity evidence.

Seven domain cases and one factory case added; complete domain/application/
architecture suites PASS (133/466/55), Java 21 Detekt PASS. Equality/one-byte
shortfall for each boundary, tiny-object amplification, legacy shares, missing
fields, overflow and 1,000 bound-subadditivity cases are covered. Initial
format errors and a test-only Kotlin infix/shift precedence mistake were
corrected; no production rule was weakened. Prior full backend baseline is
M4a (1,698); these are focused results, not a new full backend run.

This is feasibility arithmetic, not verified host capacity or active limits.
With the reference footprint model and one ingestion process, the worst
50-recipient/501-object envelope costs 2,297,642,303 footprint bytes; all
participant shares plus that envelope plus finalization need about 9.876 GiB
of global footprint allowance BEFORE legacy shares. The corresponding payload
allowance needs about 5.449 GiB before legacy shares. No such values were
installed or inferred to be the live configuration.

Next: durable participant workspace/share markers retained through membership
removal, then persisted global budget approval and debt/usage enforcement.

## Next increment: enforceable shares and deletion charging (M4d)

The durable marker and feasibility calculator are foundations, not runtime
quota enforcement. Complete these together before any resource-ready claim:

- Store a verified configuration/inventory fingerprint and the pool reserving
  all thirty shares. Require an exact match to each node's effective payload,
  footprint, process-count and protocol declarations. Unknown/missing legacy
  workspaces or a changed declaration close admission. A manually true
  `capacity_ready` flag alone remains insufficient release evidence.
- Provisioning retains one writer and one transaction. Serialize budget plan
  changes with admission through cohort → existing global T1 fence → account.
  Do not acquire the T1 fence after locking the applicant. Coordinate this
  with lifecycle's workspace guard → T1 fence → account order and prove races.
- Read participant classification, fixed ceilings, live/reserved object loads,
  attributed deletion charge and pool state in T1's SAME statement/snapshot.
  A missing marker for a known participant must fail closed, never use legacy
  defaults. All reservation states, expired rows and pending teardown count.
- Attribute new deletion charges using known ownership at the database write,
  with an explicitly bounded pool for unidentifiable historical/probe debris.
  Keep per-workspace charges until physical removal is proven. The existing
  global debt compactor can supersede debt into an observation with POSITIVE
  trash bytes; that is NOT proof that the originating participant's physical
  charge is gone. Do not reset a share simply because the global debt row was
  compacted, an account was deleted, or an object disappeared from S3 listing.
  Consider a database-maintained attributed charge retained independently of
  global compaction until a later valid zero-trash observation (with proper
  sequence/clock fencing) proves removal. Test pre/post-walk races and pending
  late writes explicitly; keep the global row-free-debt protocol unchanged.
- Apply both participant ceilings in existing admission, with no alternate
  ingress writer or new enforcement mode. OFF remains observational, ALL
  remains production-blocked until the complete release proofs pass, and
  TENANT_LIMITS remains prohibited in production. Preserve valid SMTP replies.
- Author the private footprint-refusal and usage HTTP/SDK contract before
  changing it. Payload bytes are not disk footprint: expose a conservative
  own-workspace footprint including cleanup charges, not a global figure or
  a misleading payload-only explanation for a footprint refusal. T1, usage
  and wait-refusal snapshots must use the same effective limits and loads.
- Update the existing Next.js console without a redesign: explain both limits,
  pending cleanup and silent SMTP-discard semantics. Keep immediate private
  diagnostics and the explicitly accepted cross-event inference notice.

No additional owner storage-policy decision is needed for this implementation.
Actual host declarations/legacy inventory, notices, promotion and activation
still require the consolidated release evidence and authorization.

## M4c — durable participant workspace/share allocation

V19 adds `participant_workspace`: workspace UUID, spent cohort/place, fixed
128 MiB payload / 256 MiB footprint ceilings and allocation time. It contains
no contact/session/content. Existing admissions are backfilled; the legacy
owner is not reclassified. Admission creates the marker atomically through
its existing sole writer. Retrying creates no second marker. A failed
provisioning transaction rolls back the marker, membership and workspace.

The marker remains after membership/account/workspace removal; its FK keeps
the spent place. Runtime API grants allow insert/read, not update/delete;
SMTP grants allow read, not write. SQL checks prohibit increasing either
ceiling. A membership missing its marker fails closed for admission retry
and human authority. An unidentified pre-V19 tombstone stops migration for
reconciliation rather than silently losing participant classification.
No production role or schema was changed.

Backups MUST retain the marker: the scope and checker enforce `+`, and tests
prove that omitted/misclassified/lost data is refused. A genuine PostgreSQL
dump/restore preserves its identity and fixed ceilings. This does not prove
post-restore human authorization: quarantine, deletion/revocation
reconciliation and external semantic rollback floors remain lifecycle gates.

Final full backend build PASS (2m36s); verifier 1,712 tests across twelve
modules, no failures/errors/skips (domain 133, application 466, persistence
456, API 266, architecture 55; remaining modules unchanged). Java 21 Detekt
PASS (9s). Backup suite 192/192; all nineteen migrations expand-only;
storage-OFF guard, diff and redacted secret checks pass. Initial schema
inventory expected only V1..V18; extended its explicit list to V19. One
backup fixture lacked the new kept table. Two existing BOM tests also exposed
the SQL lexer's locale dependency: its byte parser now runs under C locale
only, retaining UTF-8 content bytes unchanged. Those regressions and the
whole backup suite pass under the caller's C.UTF-8 environment.

Final real native browser/mail rehearsal PASS on this production-source tree:
two email-verified private accounts, two negotiated STARTTLS code deliveries,
two markers with exactly the accepted shares and membership mapping, one
human-issued SDK key and zero sessions after logout. Existing real SMTP,
MIME/attachment-byte, event/pagination/lifecycle and cross-account 404 checks
pass. No mock identity/backend/mail adapter. All disposable fixtures cleaned.
API jar `79016bc803214365afe25b7eccd0210d2cd7469dcba64443c46f1f7af2220cbf`;
ingestion `50a3b2aadbb6c7021ce1729a62d169c93bd796736d62fcb201c3d928a0d26165`;
migrator `a49975f15d42cff01b93601ce33d27d9bcd7d9bd6c0a980dc8881ba30e804cb7`.

This completes durable classification/allocation ONLY, not the active storage
policy. Runtime quota/deletion charging, persisted pool proof/config match,
matching usage/refusal visibility and lifecycle/restore controls remain.
`capacity_ready` still has scaffold semantics until those are connected;
manual truth is not M4 proof. Production storage modes/access remain unchanged.

Exact next work is M4d in [COHORT-STORAGE.md](COHORT-STORAGE.md). Start with
attributed cleanup charging that cannot be freed by global debt compaction
into non-empty trash, then consume the charges and shares in the ONE T1
snapshot and the same private usage/refusal snapshots. Author that HTTP/SDK
contract first. Reuse the accepted decisions; do not ask the owner again or
restart physical D3/tunnel qualification. Ops must stage the candidate backup
scope AND checker before migrator at release gate 3b, and install the candidate
grants afterward. This checkpoint authorizes none of those live steps.
