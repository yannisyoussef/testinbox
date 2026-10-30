# TI-DELIVERY-001 — resumption checkpoint

Updated: 2026-10-29.

## Goal

Deliver Google, GitHub and email self-service registration, capped at thirty
admitted participant accounts, private workspaces, a real console and a
waitlist. Preserve the live owner-only service until a reviewed release and
explicit access authorization.

## Current state

- Active local branch: `feature/ti-delivery-001-beta`.
- Starting base: `408c3f3dc5520f914f866e342f50e4437c15e8ac` (current
  origin/develop, verified with fetch and ls-remote on 2026-10-29).
- origin/master: `ff21481fe2781f8ac75d95ccc1c40f6830214504`; same file tree.
- M0 discovery and durable delivery/resumption documents: complete.
- Existing console API PR #107 integrated locally without conflicts; its
  authored contract and V11 indexes are present on this branch. Remote PR,
  develop and master are unchanged. Native login, admission/waitlist and real human console wiring are implemented
  and locally verified. Runtime cohort storage is complete (M4d below); lifecycle and release gates remain.
- Feature-branch push and PR/MR creation are now owner-authorized. Shared branches, production and access remain unchanged; see M4d and the review requests for publication state.
- New root `AGENTS.md` points to existing repository guidance and these files.
- Saved local milestones: `4a79dca` (M0), `72d5c5a` (console integration),
  `da8acef` (stream fix and accepted ADR-036), `e52d057` (M3a admission),
  `792564f` (M3b sessions), `bab3521` (M3c email core), `7a60631` (M3d email HTTP),
  `f078aa6` (M3e encrypted provider state), followed by the M3f provider protocol checkpoint.
- Frontend companion checkout:
  `/Users/yannisbabineni/Projects/infinity/testinbox-web`, on its own delivery branch, starting at
  `e6304d01d341ad3e90a6953211e2a7dba9904e68`; origin/master
  `8da2d40a264179bb0e74fd7c4a6631fccade2c68`. No tracked AGENTS/CLAUDE files
  exist in the frontend checkout. Its README and `docs/handoff.md` apply.

## Verification saved for M0

`./gradlew :domain:test :application:test :architecture:test spotlessCheck
--console=plain` passed. Domain 115, application 424, architecture 38; zero
failures/errors/skips. Domain/application tests restored from Gradle cache;
architecture ran. See [DISCOVERY.md](DISCOVERY.md) for scope and limitations.

## Console foundation verification (M2, backend portion)

The existing PR #107 at `986532ee00d08aa2a3fcad03cfd75887fcce879a`
integrated cleanly on the delivery branch. `./gradlew build --console=plain`
passed on the integrated tree, including real PostgreSQL/MinIO adapter and
black-box E2E tests. This does not mean the frontend, identity, admission or
public-release conditions are complete. No shared PR was merged.

Review found and fixed stream failure cleanup: a subscription acquired before
`Stream` initialization fails was not closed, and a failed unsubscribe could
keep its wait slot. Three deterministic regression cases failed before the
fix; all pass after it. The combined read/cleanup failure preserves the
original exception and records cleanup as suppressed.

Final `./gradlew spotlessApply build --console=plain`: PASS. The result
verifier confirms 1,452 tests across all twelve backend modules, zero
failures/errors/skips. Its fourteen self-tests pass. Migration safety checks
pass for all eleven migrations. Detekt passes with the installed Homebrew
Java 21 runtime; Java 25 remains the compilation/runtime target. Gitleaks
finds no leaks in the delivery commits or changed application sources.

## Accepted design (M1)

The owner accepted ADR-036 for implementation on 2026-10-29 through the
explicit architecture question. Native backend identity, maintained OAuth
libraries, email codes, revocable sessions, one participant workspace and a
fixed thirty-account admission transaction are accepted. Production activation
is still separate. See `docs/adr/0036-human-identity-and-beta-admission.md`.

## Account/admission foundation (M3a)

Implemented and verified: framework-free account/admission model, one
transactional provisioner, PostgreSQL adapter, expand-only V12 and backup
classification. Both cohort controls default false. SQL bounds the one cohort
to places 1–30, one account/workspace/project per membership, and preserves
consumed places when an account is removed. No public endpoint uses it yet.

Verification: full backend build and final result verifier report 1,467 tests,
zero failures/errors/skips; thirteen new PostgreSQL proofs and two architecture
guard tests. Detekt, formatting, migration safety and backup classification
pass. The backup suite passes 165/165 with `LC_ALL=C`; its production gate is
unchanged. See [VERIFICATION.md](VERIFICATION.md) for exact commands and limits.

Next increment: authored human-account HTTP contract, email challenge/session
and OAuth adapters, bounded verification attempts and transient state, then
frontend real-session wiring. Do not confuse `capacity_ready=false` with an
implemented resource policy; M4 remains a release gate.

## Session boundary increment (M3b, verified)

The session model, account-locking PostgreSQL adapter and lifecycle use cases
are implemented, with an authored `/account` HTTP contract. The boundary
stays disabled by default, requires an explicit HTTPS public origin, keeps
cookie and Bearer authority separate, and reuses M3a admission. See
[ACCOUNT-BOUNDARY.md](ACCOUNT-BOUNDARY.md). Final full build and verifier:
1,513 tests across twelve modules, zero failures/errors/skips. Detekt passes
on Java 21; thirteen migrations are expand-only; the backup suite passes
168/168, including a real session-containing dump and misclassification cases.
The result verifier self-test passes 14/14. Formatting, diff and scoped secret
checks pass. No HTTP login issuer exists yet; this is a verified foundation,
not completed Google/GitHub/email signup.

The first session test run exposed `SpringTransactionRunner` asserting nullable
application results non-null after commit. Its callback now wraps the value,
with real PostgreSQL null-commit and exception-rollback tests. An initial API
suite could not connect to Docker's Ryuk at localhost; it passed with the
explicit local Docker address `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1`, with
cleanup and all assertions kept enabled.

Frontend local checkpoints: `48f8607` integrates the existing console;
`ad38dea` patches DOMPurify 3.4.17. Clean install, typecheck, build, 35 units
and all 28 browser tests pass; audit reports zero vulnerabilities. Browser
proof uses a fixture backend, not a real cross-repository account session.

## Email verification core (M3c, verified)

Verified foundation: eight-digit keyed code proof; browser binding; ten-minute
expiry; five atomic attempts; one-time consumption; immutable identity
resolution without email-based linking; account/session atomicity; global,
source, recipient and retained-row budgets; expand-only V14 and scheduled
cleanup independent of new requests. See [EMAIL-LOGIN.md](EMAIL-LOGIN.md).

Full build plus the final API suite and result verifier: 1,552 tests across
twelve modules, zero failures/errors/skips. Detekt passes without threshold
changes. Backup suite 175/175; all fourteen migrations expand-only; nine
control-plane tables retained and twenty-four tables excluded. Local fixture
mail only; no HTTP email issuer or delivery adapter exists yet. Google/GitHub
identity persistence tests assume verified protocol proof; they do not prove
OAuth signature/state/nonce handling. Those protocol adapters remain next.

The owner-only production service and all shared branches are unchanged.

## Email HTTP/delivery increment (M3d, verified)

Latest verified core: `bab3521`. The main authored contract and controllers now
implement email-code requests/verification, secure browser/session cookies,
closed-enum metrics, a 4 KiB pre-JSON body limit and mandatory-STARTTLS sending.
Invalid/incomplete human/email configuration closes that flow while preserving
the existing Bearer API. Both shipped flags default false; no credentials,
host or sender were provisioned and no external email was sent.

Final full build and verifier: 1,570 tests across twelve modules, zero
failures/errors/skips. Includes the complete new HTTP journey, two-cookie
handling, same-account rotation, encoded/chunked body-limit probes, live SMTP
refusal without STARTTLS, unavailable-config isolation and real YAML/env
binding. See [EMAIL-LOGIN.md](EMAIL-LOGIN.md) for the environment contract and
[VERIFICATION.md](VERIFICATION.md) for actual evidence and limits.

The first HTTP run failed safely because the test key decoded to 35 bytes,
not the required 32; its incomplete-flow response was correct. Another test's
HTTP helper omitted Origin, correctly refused as 403. Correct fixture key and
Origin-capable transport pass without weakening the production checks.

## Federated login state (M3e, verified foundation)

AEAD-encrypted, ten-minute, browser/provider-bound single-use state,
bounded rate metadata, V15, cleanup and the verified-provider sign-in
transaction are implemented and tested. Full build, Detekt and formatting
pass. Final verifier: 1,596 tests across twelve modules, zero failures/errors/
skips; backup suite 178/178; fifteen expand-only migrations. See
[PROVIDER-LOGIN.md](PROVIDER-LOGIN.md). Google/GitHub HTTP/protocol adapters
are still unimplemented: upstream proof is assumed in these core tests.

The first focused run had an overloaded test-helper name; the helper now
names its provider operation. The new issuer guard initially counted Kotlin's
own default-argument bridge as an external issuer. It now excludes internal
self-delegation while independently proving both external bypass fixtures
fail. Production behavior and guard allow-lists were not relaxed.

Read-only Ops source check: infinity-core master `9b4f57e70687cb64dc08447d3dd6ecf5ffd715c7`
requires root-installed backup scope AND checker to match the candidate before
migrating (reconcile.sh gate 3b), then applies the candidate grants before
starting services. Include this explicit host prerequisite in the final Ops
packet; no host preparation was authorized or performed here.

## OAuth protocol wiring (M3f, verified locally)

The authored Google/GitHub start/callback contract is implemented using the
Boot-managed Spring Security 7.1.1 resolver and authentication providers.
S256 PKCE, Google nonce/signature/issuer/audience/time validation, GitHub numeric
identity and verified primary contact, fixed redirects, single-use encrypted
state and discarded provider tokens are verified. No SecurityContext,
HttpSession, provider-token store or security starter changes legacy auth.
See [PROVIDER-LOGIN.md](PROVIDER-LOGIN.md) for scope, configuration and limits.

Full backend build: PASS (1m25s). The final complete API suite after added
configuration/default/HEAD regressions also passes (1m9s). The result verifier
reports 1,625 tests across twelve modules, zero failures/errors/skips. Includes
25 real HTTP/provider/PostgreSQL cases, three fixed-registration/default tests
and the real shipped YAML/env binding case. Detekt, formatting, diff and scoped
secret checks pass. New imports/nullability and overlong test/response lines
were corrected during compile/format runs; a complex contact condition was
split into named checks to meet the unchanged static-analysis gate.

No live OAuth application, provider credential, sender, host or shared ref was
changed. These tests use a local RSA-signing provider, not live Google/GitHub
consent. The complete human console, waitlist/account controls, resource-policy
release gate and cross-repository browser acceptance are still outstanding.

## Waitlist increment (M3g, verified locally)

Latest saved prior milestone: `74a6c83`. Explicit session/CSRF-bound join,
read/withdraw, one unique account entry, a database ceiling of 1,000 places and
scheduled expiry are implemented. Proposed retention is thirty days,
configurable within 1–90 days; its acceptance remains a release decision.
Waitlist is excluded from backups and grants no product authority. See
[WAITLIST.md](WAITLIST.md). No campaign or automatic admission follows joining.

Full backend build: PASS (2m37s), final verifier 1,648 tests across twelve
modules, zero failures/errors/skips. Backup suite 181/181; all sixteen
migrations expand-only; Detekt, formatting, diff and scoped gitleaks pass.
A shrinking-ceiling regression failed before the query counted every existing
entry, then passes after correction. The first full run also caught the
explicit migration inventory still ending at V15; it now explicitly includes
V16, preserving the exact discovery assertion. No failed checks were waived.

All three login methods plus the waitlist are local and dormant. The human
product authority/credential actor, account/operator lifecycle, real console,
M4 policy decisions and cross-repository acceptance remain incomplete.

## Frontend account integration (M5a, in progress)

Backend prior milestone is `fd417ca` (M3g). The authored `/account/methods`
readiness operation is implemented and verified: complete API suite, architecture
and Detekt pass; final verifier 1,650 tests, zero failures/errors/skips. The
endpoint lists only configured method names, creates no account and inherits
the disabled account/no-store boundary. Frontend root proxy is saved at
`fb54107` (62 units and 30 fixture browser checks). Its native screens and HTTPS
fixtures are in progress, not verified yet. Human product/credential authority
and native real-backend acceptance still remain. No publication/activation.


## Human product actor (M3h foundation, verified locally)

Explicit workspace actors, live session/admission resolution, human key provenance
and independently bound idempotency claims are implemented. Only the existing
credential use case mints keys; human sessions never become API keys. The full
build passed, then the complete persistence suite passed after a private
parameter-grouping refactor. Final verifier: 1,666 tests, no failures/errors/skips;
Detekt and formatting pass, seventeen migrations are expand-only, backup suite
181/181. Audit labels now quote/escape untrusted values; a regression caught the
previous log-field injection before correction. No HTTP product aliases yet.

Frontend `aaa9e3a` verifies native login/admission/waitlist/logout over fixture
HTTPS (69 units, 35 browser checks); native product operations remain explicitly
unavailable. Next: author product aliases and reuse controllers with route-derived
human authority, workspace budgets, Origin/CSRF and actor-bound idempotency.
See [HUMAN-CONSOLE.md](HUMAN-CONSOLE.md). Account lifecycle/action budgets, M4
resource policy and real cross-repository acceptance remain release gates.

## Human product HTTP aliases (M3h adapter, verified locally)

Actor foundation saved at `bd269f3`. The authored contract now exposes nineteen
human product operations, implemented by the same four controllers as `/v1`.
Resolved handler patterns select cookie versus Bearer authority, including
encoded/matrix URLs. Exact Origin/CSRF and pre-JSON 4 KiB limits cover human
mutations; workspace rate/wait/idempotency budgets cover both route families.
One-time keys keep human actor-bound replay. Disabled/incomplete accounts close
all aliases while the original API stays operational. Complete API/architecture
and Detekt pass; combined verifier 1,678 tests, no failures/errors/skips.
Next.js product streaming transport is in progress in the frontend repository.

## Native console and real backend acceptance (M5, locally verified)

Frontend `4b220b9` replaces the transitional machine-key console session with
live admitted human authority and server logout; the Inspector remains a
separate key tool. Typecheck/build, 88 units and 35 HTTPS fixture browser cases
pass. Frontend `117ec42` adds and passes a real Kotlin/PostgreSQL/MinIO/SMTP/
Next.js rehearsal: two email-verified private accounts, authenticated STARTTLS
codes, human-issued SDK key/replay, paging and live mail, MIME/attachment bytes,
cross-account 404s, both logouts and independent SDK access. Final disposable
DB has 2 admissions, 1 human-issued key and 0 sessions. All fixtures removed.
See frontend `docs/delivery/TI-DELIVERY-001.md` for commands, identities, honest
failure history and limits. No live provider credentials/consent were changed.

Next independent implementation: account/operator lifecycle, account-action
and key-inventory bounds, deletion/restore safeguards. ADR-037 remains proposed
and its owner question is pending; do not implement or assume its acceptance.

## Account metadata request bounds (M3i, verified locally)

Session/admission/waitlist requests share a 60/minute authenticated-account
budget across sessions/nodes and a 3600/minute global budget. Logout remains
usable after exhaustion; product requests keep workspace budgets. There is no
unbounded source/credential map. V18's two transient tables are excluded from
backups. Full build PASS (2m41s), verifier 1,689 tests/no skips; Detekt PASS,
backup suite 187/187; eighteen expand-only migrations. The two-account real
native browser/STARTTLS/SMTP/MIME/attachment/isolation/logout rehearsal passes
again. See [ACCOUNT-ACTIONS.md](ACCOUNT-ACTIONS.md).

Next independent increment: actual operator/account suspension, permanent
credential revocation, ingestion admission/drain, deletion and restore safety,
and key inventory bound. ADR-037 is now accepted for implementation; production gates stay closed.

## Lifecycle next increment and database privacy boundary

[ACCOUNT-LIFECYCLE.md](ACCOUNT-LIFECYCLE.md) records the intended authority,
write ordering, durable participant marker, T1 eligibility fence and honest
in-flight drain before implementation. Do not treat a suspended flag or an
empty queue alone as proof of lifecycle completion. The locally verified role-template change
removes identity/session/key/snapshot access from the SMTP DB role;
three dedicated PostgreSQL role tests and Detekt pass; no live role was changed. Concrete lifecycle/key bounds and restore safety
remain next, alongside accepted ADR-037.

## Resource-policy decision (M4, accepted for implementation)

[ADR-037](../../adr/0037-bounded-cohort-storage-and-disclosure.md) accepts
128 MiB payload / 256 MiB footprint per participant, no overcommit, attributed
deletion debt and full-envelope pricing. It explicitly accepts the remaining
cross-event capacity side channel, rather than claiming shares remove it.
The owner accepted implementation after the storage/privacy explanation on
2026-10-29. Immediate own-workspace diagnostics remain; hiding them would not
remove the observable absence of a message. Production gate relaxation is
not authorized. Concrete global/debt/reserve
configuration remains an evidence-based release/Operations step.

## Next milestone and exact continuation

M4: finish enforceable participant storage shares/debt/usage and match the
persisted pool proof to runtime declarations. Complete account/operator lifecycle,
key bounds and restore safety alongside it. Native human console and all three
login protocols are locally implemented; external provider/sender acceptance,
release review and activation remain separate gates. ADR-036 and ADR-037 are
both accepted for implementation; do not ask again. Do not repeat accepted ADR-036
or ask the owner to reauthorize implementation.

1. Read `AGENTS.md`, `CLAUDE.md`, this file, [PLAN.md](PLAN.md) and
   [DISCOVERY.md](DISCOVERY.md). The ignored handoff may be absent in another
   checkout; these tracked files are the durable replacement.
2. Inspect `git status --short --branch` and `git log -5 --oneline`; preserve
   in-progress work. Fetch refs before starting another milestone; do not
   automatically reset or pull over a checkpoint branch.
3. Backend PR #107 at `986532ee00d08aa2a3fcad03cfd75887fcce879a`
   is integrated locally as `72d5c5a`, not merged remotely. Reuse frontend MR !4 at
   `f184776412596e82eb384e338e75e3cbf5bfd66b`. A local integration branch is
   allowed; merging shared branches is not authorized by discovery.
4. Implement ADR-036, explicitly accepted by the owner. Write the account HTTP
   contract before its controllers. Do not change another Accepted invariant
   in passing or silently accept any unrelated Proposed ADR.
5. Reuse `AdmitBetaParticipant` and `JdbcBetaAdmissions`; do not create another
   participant provisioner. Add email/OAuth identity resolution and revocable
   sessions. The source of the account ID must be the validated identity/session,
   never a request field. Complete suspension/key/ingestion and deletion policy
   before participant activation. Revisit ADR-027/033/034 assumptions explicitly.
6. Propose a cohort resource budget and the scoped storage-policy path. Do not
   enable production TENANT_LIMITS/ALL or mistake OFF for capacity enforcement.
   See ADR-035 Amendment 2 and the public-boundary decision document.
7. Save milestone findings, decisions, actual tests, outstanding owner-only facts and
   exact next action here, then make a coherent local milestone commit.

## Known follow-ups, not hidden completion claims

- GitLab frontend has its own delivery branch and checkpoint beside this
  repository. The primary repo contains only the Inspector.
- Java 21 for Detekt is at
  `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`; Java 25,
  Node and Docker are present.
- Backend integration/E2E baselines passed. The frontend fixture browser suite
  passes; real native cross-repository acceptance now passes. The ephemeral edge
  deployment rehearsal remains a later release gate.
- Issue #87 is open but implemented already; verify rather than duplicate it.
- No new OAuth credentials, paid service, sending-domain change or public
  access decision is implied by the implementation plan.
- Owner facts already established: registered legal entity Infinity Ventures,
  Yannis BA BINENI, America/Toronto and agreed contact aliases. Do not ask for
  them again or invent missing address/retention/signature decisions.

## Saving and resuming

Milestones live in local Git history on this branch. Identify the latest
checkpoint with `git log --oneline -- docs/delivery/ti-delivery-001 AGENTS.md`.
Read the actual diff and status if interrupted mid-milestone; a status document
is a pointer, not proof that uncommitted code passed a test. Do not store keys,
credential values, personal message content or real test-user data here.

## Current work: accepted M4 storage policy

Remote refs refreshed on 2026-10-29; backend and frontend bases are unchanged
and both working trees were clean at entry. Implement full-envelope transient
pricing first, preserving normalization/provider dedup and unknown-content
non-persistence. Then durable non-overcommitted participant shares, debt
attribution and private HTTP usage semantics. No configuration numbers are
inferred from filesystem size, and production ALL/TENANT_LIMITS refusals stay.

## M4a — full-envelope storage privacy rule

ADR-037 is accepted for implementation (owner decision 2026-10-29). The
receive path carries original normalized distinct envelope positions into
GuardedStorage/T1. Global payload and footprint prefixes conservatively price
unknown, rate-limited and tenant-refused positions; only real admitted copies
get reservations/objects. Own tenant totals still charge admitted copies only.
SMTP uniformity and provider-event normalization/dedup remain unchanged.

Two independent payload/footprint regression tests failed against the prior
code and pass after the fix. Added 500 fixed-seed substitutions for each
global ceiling, invalid/reordered position checks, unknown/rate/tenant
receive-to-storage journeys and a real PostgreSQL no-fake-reservation proof.
Two old assertions expected refused predecessors to leave global room;
updated explicitly for ADR-037. The independent PostgreSQL reference model
now prices public envelope positions globally, retaining real admitted usage
for subsequent events. No assertions or activation gates were removed.

Full backend build PASS (2m34s); verifier 1,698 tests across twelve modules,
zero failures/errors/skips. Java 21 Detekt PASS (10s). All eighteen migrations
remain expand-only; storage-OFF guard, diff and secret checks pass. The HTTP
contract and SDK prose disclose conservative pricing and residual cross-event
recipient inference. No production configuration, deployment or access change.

Next: capacity proof including all thirty shares, explicit legacy shares and
worst-case transient envelope headroom, then durable allocation/debt/usage
enforcement. See [COHORT-STORAGE.md](COHORT-STORAGE.md). M4 is not complete.

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

## M4d — runtime limits, cleanup charging and private visibility complete

The owner authorized finishing this work, pushing the feature branches and
opening review requests. ADR-037 acceptance is unchanged; no deployment or
participant activation was authorized.

V20 attributes cleanup to persistent participant/legacy workspace identity and
keeps a workspace mirror independent of global debt compaction. Finite charges
are freed only by a later valid, fresh, trusted, zero-trash observation;
pending/late charges survive. Legacy/OFF deletion with no allocated share adds
no per-workspace mirror. V21 stores an operator-reviewed non-overcommitted
pool and fences incompatible/unenforcing node registrations and heartbeats.
Both migrations are expand-only and dormant without activation.

The one T1 snapshot includes persisted payload/footprint shares, all reserved
states and cleanup. Participants are capped at 128 MiB payload / 256 MiB
conservative footprint. Existing workspace/inbox limits may only narrow that
budget. Admission, inbox creation, workspace usage, private wait 409s, both
SDKs and the real console read the same own-workspace accounting. The console
separates payload, physical charge and pending cleanup, uses binary units and
explains silent SMTP quota discards. No global capacity is returned.

`ReserveCohortStorage` is the sole pool writer. The offline `storage-budget`
command uses owner credentials, no HTTP/Spring/schedulers, explicit declarations
and exact legacy inventory. It takes cohort then global T1 locks, requires both
admission flags closed, checks live nodes, observations, existing loads and all
thirty shares before committing. Failure rolls the transaction back. Allocations
cannot shrink/disappear. Revoke keeps shares/fence and invalidates approval.
Manual `capacity_ready=true` cannot bypass the real readiness collaborator.
Runtime roles cannot mutate approval, shares or cleanup. Restores retain shares
but exclude approval, sessions and cleanup evidence. See
[operator procedure](../../dev/cohort-storage.md).

Verification on the final production-source tree:

- Full backend build and normal Spotless rules PASS (2m51s); 1,726 tests in all
  twelve modules, zero failures/errors/skips, verified by the result checker:
  domain 133, application 467, persistence 468, API 267, architecture 55,
  storage 54, notification 9, observability 37, ingestion 145, migrator 6,
  benchmark 57, E2E 28. Twelve new PostgreSQL pool/cleanup/concurrency cases
  include the exact 128 MiB boundary and a restored marker fencing OFF nodes.
- Backend Java 21 Detekt PASS (5s). No formatting/static-analysis gate relaxed.
- Backup suite 197/197 PASS with genuine dumps/restores and both durable share
  tables; missing/misclassified/transient data is refused. All 21 migrations
  pass the existing expand-only check. Storage-OFF config policy PASS.
- OpenAPI compatibility gate PASS (warnings only for extensible response reason
  additions). API schema disclosure coverage includes the footprint object.
- TS SDK 75/75 PASS; Kotlin/Java SDK build PASS with the old Java constructor
  retained and two new footprint protocol cases. Local HTTP fixture sockets
  and URLs now explicitly use the same IPv4 loopback address after two
  intermittent mixed-localhost response failures. No transport retry added.
- Frontend types/production build, 90 units PASS. Browser fixture suite:
  34 passed initially; one stale enforcement-copy assertion corrected, its
  focused rerun PASS. Real native acceptance below covers final UI/source.
- Changed-source redacted secret scan PASS (277 files at scan time); generated
  build artifacts were excluded from the source scan. Diff whitespace PASS.

The real native rehearsal now uses an isolated ALL-mode deployment, the
existing lab-only startup-record path (explicitly not enablement evidence),
actual API/ingestion node registrations, an offline pool reservation, a lab
observation and real PostgreSQL/MinIO/SMTP ingestion plus the
STARTTLS login sender. Two private email-verified accounts were admitted;
SDK credentials were created once; sessions were revoked on logout.

Tiny MIME attachments exhaust physical share before payload: the input is
explicitly checked to parse successfully with 498 extracted attachments.
The next refused copy gets SMTP 250, creates no message, and the private wait
returns WORKSPACE_FOOTPRINT_LIMIT / STORED_FOOTPRINT without global figures.
Deleting the live inbox lowers payload while cleanup remains charged in API
and console. MIME/attachment bytes, event recovery, expiry, cross-account 404,
private key handling and independent SDK access after logout all PASS.
All disposable containers/network/volumes were removed. Candidate jar hashes:
API `69a2fed4332feb92a6778bf880c2e5b5fbfbe63e0bdbf577da818e422caeed26`,
ingestion `5176c7cb437b340b56d4c00a9370e2aae8ac536091d6bc3eb3980e209e433018`,
migrator `cfd8afc07b37507a897c96e8395f18fcbf17fc7867bf3354d740f50181b3ca0d`.
These identify local proof artifacts, never release/promotion digests.

The first native attempts were NOT passes: deployment safety rejected missing
lab identity/domain declarations; another input exceeded the 500-part MIME
limit and exercised raw-only storage. The corrected fixtures passed with the
original safety checks. Historical grant/schema assertions were updated for
V20/V21; identity-only tests use an explicit scoped capacity double, while the
real native test uses the unstubbed gate. The publication candidate does not
hide those distinctions.

Remaining release work is separate: M3j account/operator suspension/deletion,
revocation/restore reconciliation and semantic floors, release/notice/OAuth
and sending-identity configuration, actual Ops capacity/qualification/grants
and authorized promotion/access widening. Production ALL/TENANT_LIMITS startup
refusals remain. The live owner-only SMTP/MX service is unchanged.

Exact next action: review the backend and frontend `feature/ti-delivery-001-beta`
branches against develop, using their PR/MR and linked verification. No shared
branch merge, host edit or third-party admission follows from this checkpoint.
