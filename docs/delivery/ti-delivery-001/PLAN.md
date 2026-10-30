# TI-DELIVERY-001 — 30-account self-service beta

Started: 2026-10-29. Current checkpoint: [STATUS.md](STATUS.md).
Verified starting point: [DISCOVERY.md](DISCOVERY.md).

## Outcome and authorization

Deliver a working registration and product loop through Google, GitHub or
verified email, with at most 30 admitted participant accounts across all
methods. Each account receives its own private workspace. Later registrants
can request a place on a waitlist. The owner decides when to expand access.

The owner authorized repository discovery, planning, implementation and
verification. Existing production remains owner-only during development.
Do not merge a production release, change the WAF, provision live OAuth or
email credentials, admit a tester or remove the cap under this instruction.
No production SMTP/DNS/storage changes are needed for repository discovery.

## Implementation defaults to make concrete in the design

- Reuse the existing Kotlin application and PostgreSQL, not another service
  or datastore. Evaluate native session authentication against a separate
  TestInbox Keycloak realm and an external provider; document the trade-offs
  before choosing. Never couple silently to nkama's realm or its policies.
- Use the existing GitLab site console as the user interface, reusing its
  design and MR !4 where suitable; preserve the monorepo Inspector. Reconcile
  the site's API-key-login bridge with real human sessions rather than
  persisting a user credential in browser storage.
- Email login uses an expiring, single-use link or code. OAuth identifies an
  account by provider and immutable subject, not just email. Linking an
  existing account requires authenticated proof; no implicit email-based merge.
- Count 30 participant admissions, not sessions, logins, OAuth identities or
  HTTP requests. The existing owner/monitoring workspace is not a participant
  seat. One participant gets one workspace for this release. Deletion or
  suspension does not automatically recycle a seat or create another workspace.
  These defaults must be explicit in the reviewed design and operator controls.
- Verified applicants enter automatically while admissions are open and a
  place is available; otherwise they can explicitly join the waitlist. No
  workspace, API key or seat is created for an unverified application.
- Use secure server-side sessions and explicit authorization. Preserve the
  existing Bearer API-key API and synthetic credentials. Human-session and
  machine-credential paths must not grant one another implicit privileges.
- Keep rate/space budgets and admission pause/suspension controls on the
  server. Numeric beta allowances require a capacity calculation including
  the owner, monitoring, pending writes, object overhead and cleanup debt.
  Do not mistake a 20 GiB bucket quota or a payload allowance for a per-tenant
  physical-storage guarantee.
- Keep unfinished demo functions out of the real product flow. No passwords,
  passkeys, billing, team membership, test-run model or dashboard redesign is
  required to deliver the requested three login methods and core email loop.

## Milestones

Each milestone ends with an updated checkpoint, test evidence and a local
commit. A milestone can have multiple small commits. Record blockers as soon
as found and continue independent work; do not mark unexecuted tests passed.

| Milestone | Deliverable | Required evidence / exit |
|---|---|---|
| M0 — onboard and checkpoint | Refresh refs; inventory modules, rules, tests, handoff and related open work; save this plan and resumption instructions | Clean starting checkout, exact refs and baseline test results recorded |
| M1 — design and contract | Threat model, identity/session/admission model, data lifecycle, public API boundary, cohort budget and necessary ADR proposals; authored API contract diff | Resolve contradictions against Accepted ADRs; keep Proposed ADRs visibly Proposed until accepted; identify only genuine owner decisions |
| M2 — reusable console foundation | Bring backend PR #107 up to the current base on an isolated branch and verify it; reuse site MR !4 without merging its browser-held-key authentication as the final human login | Contract, persistence, workspace isolation, pagination/event limits and existing regression suites pass |
| M3 — identity and admission | Google/GitHub/email verification, revocable sessions, first-30 admission and one-workspace provisioning transaction, pause/suspension and waitlist | Deterministic concurrent final-seat tests; duplicate callback/replay, linking, session revocation, CSRF and rate-bound tests; real PostgreSQL integration |
| M4 — workspace safety | Organization/cohort ceiling, bounded participant provisioning and effective resource limits; backup classification, deletion and migration safety | Cross-workspace read/write/download/event tests; concurrent limit tests; storage-policy release blockers resolved without bypassing ADR-035 activation restrictions |
| M5 — real product loop | Wire the existing console to the authenticated workspace; inboxes, messages, MIME/attachments and key management | Browser acceptance against real API, no mock session/data in the authenticated path, plaintext keys shown once and never persisted by the browser |
| M6 — release packet | Participant notices/terms, Ops integration sheet, callback URLs, env names, deployment/rollback and cohort observation plan | All relevant checks green; independent architecture/security/functional review; unknown business fields remain explicit; one consolidated production-approval packet |
| M7 — authorized beta rollout | Staging-proven artifacts promoted through the existing release process; explicit access authorization; up to 30 participants | Ops verification and owner GO; normal monitoring and first feedback review after seven days of actual participant use; no automatic cap increase |

M1 and console review can overlap. Identity, console and notice work can
overlap once their shared contracts are stable. Live activation follows the
existing promotion and Ops process, not parallel production changes.

## Release conditions that must remain distinct

1. A successful login is not admission. An admitted account is not authority
   over a workspace supplied by the browser. Every transition is checked.
2. Infrastructure containment was qualified by Ops. Do not repeat D3 host
   qualification, but do resolve the application assumptions self-service
   invalidates (ADR-027, ADR-033, ADR-034 and the ADR-035 production restrictions).
3. Production refuses storage `TENANT_LIMITS` and `ALL` today. Do not flip an
   environment variable or remove the refusal to ship signup. Any new tenant
   policy needs a scoped decision, contract and tests. Until then, no third-party
   activation is ready, even if identity works in local tests.
4. Registering OAuth apps, transactional sending identity/DNS, secret delivery,
   public origins and WAF changes belong in a concrete Ops request. Contact
   forwarding and inbound TestInbox SMTP do not send login messages.
5. Privacy text must cover the actual new identity/waitlist/control-plane data
   and backup behavior. Reuse confirmed owner facts; do not invent retention,
   residency, access promises, signatures or a legal address.

## Verification and checkpoint discipline

- Follow `docs/quality/strategy.md`, not a second test policy. Run focused tests
  during development, the appropriate integration/browser suites at a milestone
  and the existing required checks before opening a release candidate.
- Use Testcontainers for database integration and synchronization primitives
  for races. Never replace a database proof with mocks or sleeps.
- For each result record the command, actual outcome and limits (cache reuse,
  unavailable tools, root/platform requirements). Never hide skips or failures.
- Keep checkpoint files tracked and committed. The status document must name
  the active branch, baseline, last saved milestone, pending changes, external
  dependencies, exact next commands and deployment authorization boundary.
- Keep credentials, private material, real message content and personal test
  data out of commits, logs and artifacts. Use the owner's configured identity
  and plain Conventional Commit messages without attribution trailers.

## M4 completion and review publication

M4 runtime storage is complete and locally verified; see STATUS M4d. The owner
now authorizes feature-branch pushes and backend/frontend PR/MR creation. This
is not a merge/deployment approval. Continue M3j lifecycle/restore and M6 release
work after review; do not reopen the accepted ADR-036/037 implementation choices.
