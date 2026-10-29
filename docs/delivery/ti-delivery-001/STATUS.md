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
  develop and master are unchanged. Human login is not implemented yet.
- Nothing pushed, merged, deployed or activated. No external messages sent.
- New root `AGENTS.md` points to existing repository guidance and these files.
- Saved local milestones: `4a79dca` (M0), `72d5c5a` (console integration),
  `da8acef` (stream fix and accepted ADR-036).
- Frontend companion checkout:
  `/Users/yannisbabineni/Projects/infinity/testinbox-web`, clean develop at
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

## Next milestone and exact continuation

M3b: implement authentication/session/provider flows around the tested cohort
transaction; author the account contract before its HTTP behavior.

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
7. Save M1 findings, decisions, actual tests, outstanding owner-only facts and
   exact next action here, then make a coherent local milestone commit.

## Known follow-ups, not hidden completion claims

- GitLab frontend is checked out beside this repository. Create its own delivery
  branch and checkpoint before editing. The primary repo contains only the Inspector.
- Java 21 for Detekt is at
  `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`; Java 25,
  Node and Docker are present.
- Backend integration/E2E baselines passed. Frontend browser checks and the
  ephemeral edge deployment rehearsal are not yet run for this delivery.
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
