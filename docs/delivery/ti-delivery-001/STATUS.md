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
  develop and master are unchanged. Human HTTP login is implemented in the local M3d/M3f increments; the site still needs real-session wiring.
- Nothing pushed, merged, deployed or activated. No external messages sent.
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


## Next milestone and exact continuation

M3h: explicit human workspace/credential actor and account/operator lifecycle,
then the Next.js same-origin session/real-console flow. All three login methods are
implemented locally but dormant; live provider/sender acceptance and the M4
resource-policy decision remain release gates. Do not repeat accepted ADR-036
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
  passes; real cross-repository console acceptance and the ephemeral edge
  deployment rehearsal have not yet run for this delivery.
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
