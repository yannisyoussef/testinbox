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
