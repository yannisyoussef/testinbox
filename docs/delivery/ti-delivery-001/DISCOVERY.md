# TI-DELIVERY-001 — verified repository discovery

Date: 2026-10-29. This is repository evidence, not a new production audit.

## Ref and working-tree baseline

The selected checkout was clean on `develop` before work. `git fetch --all
--prune` and `git ls-remote` confirmed:

| Ref | Revision |
|---|---|
| Local HEAD / origin/develop | `408c3f3dc5520f914f866e342f50e4437c15e8ac` |
| origin/master | `ff21481fe2781f8ac75d95ccc1c40f6830214504` |
| Reusable console PR #107 | `986532ee00d08aa2a3fcad03cfd75887fcce879a` |

`git diff --quiet origin/develop origin/master` returned 0: the trees are
identical; master's extra commit is the release merge. Do not reset develop
to master merely to make commit IDs match. Work starts on
`feature/ti-delivery-001-beta`. Other existing worktrees are left untouched.

Git identity is already configured to the owner. No global identity change
is necessary. The local handoff under `prompts/` is ignored by design; the
tracked delivery documents are the resumption record.

## Whole-repository inventory and governing rules

The baseline has 795 tracked files: backend 399, docs 193, scripts 90,
deploy 39, SDKs 31, Inspector web 17, GitHub configuration 7, root files 19.
Inventory covers all paths, modules, source/test families, build files,
contract, CI and deployment assets. It is not a claim that every historical
benchmark transcript was revalidated or that every test was executed.

Read `CLAUDE.md`, `CONTRIBUTING.md`, the ADR index, the relevant architecture,
API, security, quality and setup documents and the specialist review guidance.
No existing `AGENTS.md` applied in the checkout or its ancestors. The new
root entry point directs future sessions to those existing rules.

- Kotlin modular backend: domain, application, persistence, storage,
  notification, observability, api, ingestion, migrator, architecture,
  e2e and benchmark. Java 25, Spring Boot 4.1.1; plain JDBC/PostgreSQL.
- Existing dependency direction is domain <- application <- adapters,
  mechanically enforced. Invariant-bearing writes belong in application use
  cases. Do not add identity writes in controllers or database scripts.
- `backend/api/contract/openapi.yaml` is authored first; controllers are not
  the source for a generated replacement contract.
- Expand-only Flyway migrations, explicit backup table classification and
  rollback floors are required. Never edit an applied migration.
- Build once and promote digests through GitHub/GitLab. A local commit or
  successful test is not permission to merge a release or deploy it.
- `CONTRIBUTING.md` still says inception/main; README and parts of CLAUDE.md
  still describe dark production. Those descriptions predate the owner's
  completed SMTP beta. The current instruction changes delivery scope; it
  does not waive Accepted technical invariants or activation checks.
- Proposed ADRs 0016–0018 remain Proposed. The delivery must not silently
  promote any Proposed decision to Accepted.

## Exists, reusable and missing

| Area | Verified repository state | Delivery implication |
|---|---|---|
| Machine API authentication | `ApiKeyAuthFilter` protects `/v1/`; managed keys are hashed, scoped, revocable and workspace-bound | Preserve existing SDKs, owner credentials and synthetics |
| Human authentication | No implemented users, identity providers, sessions, membership or email-login flow in this monorepo | Implement a distinct authenticated human context; do not pretend a bootstrap key is a user |
| Workspace provisioning | Workspace/project models and fixture-only `ProvisioningRepository` exist | New participant provisioning needs its own invariant-bearing use case and transaction |
| Admission/waitlist | No account/admission/waitlist tables or flow | Enforce cap across all methods and nodes in PostgreSQL; UI flags are not an admission gate |
| Tenant reads | Existing repositories scope queries to workspace | Extend the same rule to new console reads, events, keys and downloads |
| Console APIs | Open GitHub PR #107 has contract, queries, SSE, tests and V11 indexes; 58 files, +3031/-71 against its PR base | Reuse and re-review on current base rather than reimplementing; avoid migration-number collision |
| Marketing/console frontend | Separate GitLab project 18, `infinity/testinbox-web`; production login and console still mock human identity | Coordinate changes in that repository; do not turn the monorepo Inspector into a second console |
| Frontend reusable work | Open draft MR !4 at `f184776412596e82eb384e338e75e3cbf5bfd66b`, pipeline 1294 successful | Its API-key sign-in is not final human authentication; integrate real sessions before public release |
| Privacy work | Open draft MR !5 at `b2d62a2b2f4c0027eaac31c82b8f25d425569286`; pipeline 1473 failed under placeholder gate | Preserve gate and reuse evidence; rewrite owner-only scope for actual participants |
| Legacy quota policy | Workspace-derived active inbox, rate and wait budgets exist; storage policy is amended by ADR-035 | A smaller config number is not proof of per-tenant ingest storage enforcement |
| Storage activation | Production `DeploymentSafety` refuses `TENANT_LIMITS` and `ALL`; OFF remains the authorized live mode in Ops handoff | Resolve cohort fairness and budget as scoped work, never bypass activation safety |
| Backup lifecycle | `deploy/backup/scope.txt` classifies all current tables; personal-identity data is not modeled yet | Classify new tables and document deletion/restore/retention behavior before release |
| Transactional mail | No application login-email sender | Ask Ops for a reviewed sender/credential path after choosing concrete callback and sending contracts |

Frontend facts were checked against current GitLab metadata and the site's
README, `lib/auth/mock.ts`, `lib/flags.ts` and `docs/handoff.md`. The mock
session, fixed verification code and hard-coded invite data must not become
authentication for real users. Do not reproduce personal invite data in this
delivery record.

### Correction to the Ops handoff

GitHub issue #87 is open, but its wait-across-expiry fix already exists at
the baseline: `WaitForMessage.kt`, `JdbcWaitObservations.kt`,
`WaitForMessageTest.kt` and `WaitObservationsTest.kt` check inbox state in the
deciding snapshot, including parked EXPIRED/DELETED waits. Treat this as
existing implementation to verify, not a new feature to rebuild. The full
persistence suite has not yet been rerun in this milestone.

## Assumptions reopened by the requested feature

- ADR-027 Consequences requires an organization-level ceiling before
  self-service workspace creation. The thirty-seat limit needs to bound
  workspace provisioning and account replacement, not merely concurrent users.
- ADR-033 section 4 derives an idempotency-record bound from manually
  controlled workspace count. Recalculate it for a bounded cohort and bound
  identity, session, challenge and waitlist records as well.
- ADR-034 and `docs/dev/production.md` explicitly require revisiting backup
  scope/volume when signup becomes self-service.
- ADR-035 Amendment 2 and `docs/security/storage-public-boundary-decisions.md`
  contain real application policy restrictions. Existing physical containment
  does not prove multi-workspace fairness or erase these restrictions.
- Thirty default 2 GiB payload budgets are 60 GiB, exceeding the live 20 GiB
  bucket quota from the Ops handoff; object overhead makes naive sizing worse.
  Do not claim changing that default alone enforces storage in OFF mode.

## Local tools and baseline verification

Available: GitHub `gh`, GitLab `glab`, Java 25.0.3, Node 22.18.0,
Docker server 29.5.2, Gradle wrapper. Only Java 25 is registered with macOS;
Detekt requires a Java 21 Gradle runtime. Resolve a suitable local JDK before
claiming static analysis, without changing the project's runtime baseline.

Command, from `backend/`:

```sh
./gradlew :domain:test :application:test :architecture:test spotlessCheck --console=plain
```

Result: PASS (`BUILD SUCCESSFUL`). JUnit XML: domain 115, application 424,
architecture 38 tests; zero failures/errors/skips. Domain/application tasks
were restored from Gradle's cache; architecture executed. Formatting passed.
This is not a new full backend, persistence, E2E, SDK or browser run. No live
host or cloud configuration was changed.

## External dependencies for later Ops request

OAuth applications/secrets; final origins/callback URLs; verified transactional
mail identity; secret placement; exact WAF/access delta; migration/backup
classification; current capacity and monitoring evidence. These are prepared
as one concrete integration request after M1, not guessed from root credentials
or applied during discovery.
