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

Review found a follow-up in stream failure cleanup: a subscription acquired
before `Stream` initialization fails is not closed. Add deterministic
regression cases and fix before calling this backend foundation reviewed.

## Next milestone and exact continuation

M1: make the human identity/admission architecture and contract concrete;
review the existing console foundation in parallel where independent.

1. Read `AGENTS.md`, `CLAUDE.md`, this file, [PLAN.md](PLAN.md) and
   [DISCOVERY.md](DISCOVERY.md). The ignored handoff may be absent in another
   checkout; these tracked files are the durable replacement.
2. Inspect `git status --short --branch` and `git log -5 --oneline`; preserve
   in-progress work. Fetch refs before starting another milestone; do not
   automatically reset or pull over a checkpoint branch.
3. Review backend PR #107 at `986532ee00d08aa2a3fcad03cfd75887fcce879a`
   against the current base and frontend MR !4 at
   `f184776412596e82eb384e338e75e3cbf5bfd66b`. A local integration branch is
   allowed; merging shared branches is not authorized by discovery.
4. Draft the smallest identity/session/admission contract and ADR proposal;
   compare native auth, isolated Keycloak and an external IdP. Keep Proposed
   decisions visibly Proposed; do not change an Accepted invariant in passing.
5. Specify cap transaction, retry/linking behavior, one-workspace provisioning,
   suspension/session/key revocation and account deletion. Revisit ADR-027,
   ADR-033 and ADR-034 assumptions explicitly. Include security abuse cases
   and test mapping before implementation.
6. Propose a cohort resource budget and the scoped storage-policy path. Do not
   enable production TENANT_LIMITS/ALL or mistake OFF for capacity enforcement.
   See ADR-035 Amendment 2 and the public-boundary decision document.
7. Save M1 findings, decisions, actual tests, outstanding owner-only facts and
   exact next action here, then make a coherent local milestone commit.

## Known follow-ups, not hidden completion claims

- GitLab frontend work needs its own checkout and repository guidance before
  editing. Current primary checkout contains only the Inspector.
- A Java 21 runtime is needed for Detekt; Java 25/Node/Docker are present.
- Full integration/E2E/browser baselines are not yet executed for this delivery.
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
