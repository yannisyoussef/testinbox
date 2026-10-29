# Human console authority — M3h implementation design

Authority: accepted ADR-036, especially §§4/6; no additional deployment or
participant activation is authorized. This design is recorded before the actor
model and HTTP adapter changes. Latest verified backend: `7c9b4c9`; frontend
native login/waitlist UI: `aaa9e3a` (69 units, 35 HTTPS fixture browser cases).

## Actor foundation (first increment)

Introduce a framework-free sealed `WorkspaceActor` with workspace/project and
explicit scope ceiling. Existing persisted `ApiKey` remains a real key and
implements it. `HumanWorkspaceOwner` contains a verified account/membership,
not a fake key, hash, public ID or bootstrap credential. Its ceiling names only
the three current product scopes explicitly; new enum values cannot silently
become human-owner powers. Only `ResolveHumanWorkspace` constructs that actor
from live session/account and stored admission, in a transaction. No browser
field selects its workspace or project.

Keep `CreateApiKey` as the sole minter. Accept the explicit actor model,
preserve scope/expiry checks and record human provenance separately from
`created_by_api_key_id`. A human's SDK key is independent of its login cookie;
logout does not expire an intentionally issued key. No plaintext credential
enters persistence, audit or browser storage. Shared read/revoke operations
remain workspace scoped. Revisit application-level revoke authorization while
adding the actor; do not rely only on an HTTP scope helper.

Preserve idempotent creates. Extend transient claim metadata with a separate
human-account actor ID; key-create replays compare BOTH actor identities, so a
human or key cannot claim another actor's prior credential result. API-key
claim behavior and workspace-scoped inbox replay remain unchanged. Add nullable
columns in V17, preserving old-artifact schema compatibility. Backups retain
key verifier/provenance as control-plane data, exclude claim rows as before.
Semantic rollback/account-restore safety is a distinct release gate.

## HTTP reuse (implemented and locally verified)

Author `/account/product/...` operations before their handlers. Reuse the same
inbox/message/workspace/key controllers and application operations as `/v1`.
Resolve the authority from the matched route: `/v1` accepts only a real Bearer
key; account product paths require a live admitted human session, exact Origin
and CSRF on mutations. Set the actor internally only after verification.
Resolved handler patterns, not only raw URL text, protect encoded/matrix paths.
The account flag/origin gate applies to aliased web controllers as well.

Apply the existing workspace request/rate/wait budgets to both route families
with one canonical cost mapping. Keep idempotency support/refusal on both,
with stable human-account actor binding. Enforce the account body ceiling
before JSON on the aliased handlers. Return cross-workspace 404; ignore any
caller account/workspace/project headers. Validate all negative boundaries.

Nineteen operations on fifteen paths reuse the four existing controllers.
Complete API suite: 265 PASS; combined verifier: 1,678; Detekt PASS.

## Next.js product wiring (in progress)

Add an explicit streaming account-product proxy: only known product routes,
secure cookie/Origin/CSRF, bounded request bodies, no machine credential,
allowlisted response headers, MIME/attachment download disposition, unbuffered
events and abort propagation. The smaller login proxy remains bounded JSON.
Use the existing console adapters/views with a human transport; keep credentials
minted for SDKs only in the one-time reveal. Remove the transitional browser-key
console session from the participant path. Preserve the standalone Inspector
as the explicit API-key tool. Do not substitute seeded data for failures.

## Still required before release

- Account/operator pause, suspension/revocation/deletion and ingestion behavior,
  including races with in-flight storage commits and session/key operations.
- Account-action budgets and participant key inventory bound. Waitlist's entry
  ceiling and login budgets alone do not bound an authenticated request flood.
- Explicit M4 footprint/envelope/resource policy decision and capacity proof;
  `capacity_ready=false` remains a scaffold. Do not weaken ADR-035 restrictions
  or reopen physical D3 qualification.
- Cross-repository browser proof against the real Kotlin API and sending/provider
  adapters in a disposable environment, then authorized external acceptance.
- Identity/waitlist retention, notices, backup-deletion/restore controls and the
  semantic rollback floor; no automatic participant activation or cap increase.
