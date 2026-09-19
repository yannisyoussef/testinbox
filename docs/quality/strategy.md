# Quality Strategy

Testing is treated as part of the product, not an afterthought bolted onto a
finished design — this document is reviewed alongside architecture, not
after it.

## Layers

| Layer | Tooling | Focus |
|---|---|---|
| Unit | JUnit 5, Kotest | Domain logic in isolation (no Spring context), matcher evaluation, addressing/token generation, MIME-to-domain mapping given pre-parsed input. |
| Property-based | Kotest property testing | Address token uniqueness/format invariants, matcher logic (e.g., "a message matches iff all specified fields match"), idempotency-key/deduplication logic. |
| Persistence integration | Testcontainers (Postgres) | Repository-layer correctness, workspace-scoping enforcement (a query for workspace A must never surface workspace B's rows), TTL/expiry state transitions. |
| Local SMTP integration | Testcontainers or an embedded test SMTP server | End-to-end inbound flow: SMTP session → parse → persist → notify, including duplicate-delivery and unknown-recipient scenarios. |
| MIME corpus tests | Curated corpus of real-world and adversarial `.eml` fixtures | Hostile/malformed MIME: nested multiparts, bad charsets, MIME bombs, malicious SVG/HTML, broken headers, oversized attachments — asserting graceful `ParseFailed` handling, not just success cases. This corpus is a first-class, versioned test asset, not ad hoc. |
| API contract | Karate or REST Assured against a running instance | Verifies the OpenAPI contract matches actual behavior; run against the same build that will be released. |
| Acceptance / end-to-end | The product's own SDKs, dogfooding | The MVP slice itself (create → send → wait → assert → cleanup) is validated by running it through the real JVM and TypeScript SDKs, not just internal test harnesses — if the SDK can't do it ergonomically, the feature isn't done. |
| UI | Playwright | Dashboard: message inspection renders safely (sandboxed HTML, no script execution), core admin flows (API key creation, project management). |
| Deployment | Container build, `staging-rehearsal.sh`, synthetic suite | The deployment path itself: images build and run non-root, one migration job applies exactly what the artifact bundles, readiness reflects database/schema/object store/`LISTEN`/DB session bound, both deployables report the deployed commit, and a real inbound workflow succeeds through a real TLS ingress. A full server wait window is parked through the actual reverse proxy, because a proxy read timeout shorter than the wait window is invisible to every other layer. Two host-side targets exist for a production estate — build identity on the management ports, and direct-origin isolation from outside — with unit-tested classifiers. |
| Architecture | ArchUnit | Enforces module boundaries from `docs/architecture/component-architecture.md` (e.g., `domain` module must not depend on Spring/JPA/provider-specific types). |
| Idempotent mutations | Domain + application + Postgres integration + API + e2e + product synthetic | ADR-033: canonical fingerprinting (absent vs empty, field-boundary shifting, local-part case), the single-transaction claim proven against real Postgres under eight-way concurrency, the `lock_timeout` not leaking onto the rest of the transaction, refusals rolling their claim back, workspace and operation isolation, batched retention sweep, and the header being refused where it is not honoured. |
| Credential lifecycle | Domain + application + Postgres integration + API + e2e + product synthetic | ADR-032: format parsing (truncation, transposition, an unknown future version), revocation taking effect on the next request with no cache to wait out, expiry, scope enforcement and the refusal to grant a scope the creator lacks, the bootstrap window closing and reopening *and* the configured value being what retires it, coalesced `last_used_at` writes across nodes, and the negative proofs — no representation but the single `201` carries a credential, and nothing reaches the logs at debug level. |
| HTTP contract edges | API integration (`UnmatchedRouteTest`) | Unknown path, wrong method, unsupported media type. Reported from staging as `500`s: a catch-all `@ExceptionHandler(Exception::class)` caught Spring's own dispatch failures, so every client typo became an ERROR stack trace and real `500`s stopped being distinguishable from them. |
| Postfix mail edge | Rendered-config gate + mutation proofs + edge synthetics through real Postfix 3.8.6 | TI-005/ADR-004: the hop that will face the Internet. Acceptance is equivalent for tenant, unknown and postmaster recipients on code, full reply text and ordering; an unknown recipient is *relayed* and discarded in ingestion, proven against Postgres and MinIO rather than by a 404; one DATA to several recipients stays one inbound event; a transient failure queues and retries to exactly one delivery; queue expiry emits a signal and no DSN. The mutation suite is the load-bearing part — a Docker subnet in `mynetworks`, a default `local_header_rewrite_clients`, a defined `relay_recipient_maps`, `smtp_destination_recipient_limit=1`, the dormant `discard:` line, a multi-day production queue lifetime, a 25 MiB ceiling, and a configuration that passes `postfix check` while leaving smtpd answering nothing. |
| Content fingerprint | Application unit + ingestion Testcontainers (`simple-text-relayed.eml`) | ADR-019 §4 as amended: the same content fingerprints alike across differing `Received:` timestamps, queue identifiers, folded continuation lines and hop counts, while `Received-SPF:`/`X-Received:` and a `Received:` line in the *body* stay content and still change it. Asserted through the real gateway — which stamps its own trace header — against a relayed twin carrying two further hops, and paired with the negative proof that `/raw` still differs. Hashing the stored bytes made two identical sends match only inside one wall-clock second, which reached CI as an intermittent failure, not a bug report. |
| Properties a runtime test cannot see | ArchUnit source rules | Some invariants have no observable failure: constant-time verification produces identical outputs either way, and a second credential-minting path would work perfectly. These are pinned as rules over call sites — `MessageDigest.isEqual` is called and `String.equals` is not; the plaintext is rendered in exactly one place; only `CreateApiKey` writes a managed row; no audit event has a field that could hold a secret. Each carries a guard-the-guard check, because a rule that resolves nothing passes silently. |
| Resource limits | Postgres integration + API + e2e | Rate/quota decisions (ADR-027): capacity boundaries, multi-node budget sharing across two limiter instances, per-workspace and per-inbox isolation, derived usage surviving a cascade delete, the `429`/`409` split, and that no limiter key derives from a request header. |
| Concurrency | Targeted tests | Concurrent `createInbox()` token collisions, concurrent `EXACT` reservations racing to `409` (ADR-021), concurrent waiters on one inbox, wait-request cancellation/resource cleanup, inbox-expiry-vs-inbound-delivery race (`docs/architecture/inbound-mail-flow.md`), persist+`pg_notify` single-transaction atomicity, and a kill-the-`LISTEN`-connection test proving parked waiters still resolve after reconnect (ADR-020). |
| Security | Dependency/SCA scanning, SAST, targeted tests for the threat-model mitigations (XSS sandbox escape attempts, SSRF attempt via extracted links, cross-tenant access attempts) | Tied directly to `docs/security/threat-model.md` — each mitigation there should have a corresponding test, not just a design statement. |
| Mutation testing | Where valuable (e.g., PIT/Kotlin mutation tooling) on the matcher/dedup/lifecycle-state-machine logic specifically | Not applied blanket across the codebase — targeted at logic where "the tests pass but a subtle bug survives" is a realistic, costly failure mode (matching, deduplication, TTL transitions). |
| Performance | k6 | Inbound throughput, wait-request latency and resource usage under concurrent waiters, to validate the no-busy-polling design actually scales as claimed. |

## CI must verify tests actually ran

A green build-tool exit code is not sufficient evidence tests executed and
passed. CI must additionally assert, from the test report output, that:

- The expected number of test suites/modules produced a report (catches a
  silently-skipped module or a misconfigured test task that "succeeds" by
  running nothing).
- Zero skipped/ignored tests beyond an explicit, reviewed allow-list.
- Coverage/mutation reports (where used) are generated and checked, not just
  attempted.

This is implemented by `scripts/verify-test-results.sh`, which CI runs after
the build. Because CI jobs execute disjoint suites, the verifier takes an
explicit scope (`VERIFY_SCOPE=backend|e2e|all`) and checks only the modules
that scope is supposed to have produced — an "all modules" expectation would
otherwise fail whichever job it does not describe. Missing report directories,
report directories with no XML at all, counts below a per-module minimum,
any skip, and any failure/error all fail the build.

The verifier is itself tested (`scripts/verify-test-results.test.sh`, run in
CI before the build): a gate that cannot fail is indistinguishable from no
gate.

## CI gates: what fails the build

| Gate | Tool | Fails CI? |
|---|---|---|
| Formatting | Spotless/ktlint | Yes |
| Kotlin static analysis | Detekt (backend + JVM SDK) | Yes. Run it locally with `./gradlew detekt --no-daemon -Dorg.gradle.java.home=<jdk-21>`; `JAVA_HOME` alone leaves the daemon on Java 25 and Detekt aborts with a bare `> 25.0.3` |
| Test execution evidence | `verify-test-results.sh` | Yes |
| Verifier self-test | `verify-test-results.test.sh` | Yes |
| Architecture boundaries | ArchUnit | Yes |
| JVM SDK usable from plain Java | `JavaInteropTest` (Java source compiled against the built SDK) | Yes — it caught `ApiScope` as a `@JvmInline value class`, whose constants compile to name-mangled accessors no Java caller can reference |
| OpenAPI structure/style | Spectral | Yes |
| OpenAPI backwards compatibility | oasdiff vs. the PR base spec | Yes on ERR; warnings (e.g. removing an optional parameter) are reported only |
| Compatibility-gate self-test | `openapi-breaking-check.test.sh` | Yes |
| Secret detection | gitleaks (working tree) | Yes |
| Deployment-gate self-tests | `validate-image-digest.test.sh`, `deploy-preflight.test.sh`, `gitlab-handoff.test.sh`, `check-migration-safety.test.sh`, `scan-images.test.sh` | Yes |
| Production-contract self-tests (ADR-034) | `verify-production-candidate.test.sh` (17 refusals: non-develop head, no merged PR, missing/rebuilt/repointed artifact, rollback floor), `promotion-gate.test.sh` (failed/cancelled/skipped/missing/unlisted leg), `check-backup-scope.test.sh` (content rows, missing control plane, a dump that examines nothing) | Yes — on every pull request, because the gates they protect run only on a promotion |
| Candidate identity + `Production promotion gate` | `release-candidate.yml` | Yes, on a pull request to `master` only; the one context `master` requires |
| Production fail-closed configuration | `DeploymentSafetyTest`, `DeploymentSafetyCheckTest`, `IngestionDeploymentSafetyCheckTest`, `DeployedConfigurationTest`, `S3BlobStoreTest` | Yes — staging configuration labelled production, an env var re-enabling bucket creation, a production node creating its bucket, and profiles that drift |
| Migration rollback safety | `check-migration-safety.sh` | Yes on an **undeclared** rollback-breaking migration; a **declared** one warns on develop and blocks promotion |
| Edge invariant classifier | `deploy/synthetic/unit` | Yes |
| Container build + non-root runtime | `build-images.yml` | Yes |
| Ephemeral staging rehearsal + synthetic suite | `staging-rehearsal.sh` | Yes |
| Idempotency product synthetic | `staging-rehearsal.sh` step 9 / `npm run test:product` | Yes in the rehearsal. Proves a retried create returns the same inbox through the deployed edge, a changed request under the same key is refused, and a refused request leaves its key free. |
| Credential-lifecycle product synthetic | `staging-rehearsal.sh` step 9 / `npm run test:product` | Yes in the rehearsal. Against a **deployed** environment it is a separate run from the deployment gate: it needs a credential that can administer keys, and "the credential lifecycle is broken on an otherwise healthy deployment" is a different verdict from "do not ship this artifact" |
| Container vulnerabilities | Trivy (`scan-images.sh`) | **No on develop/PR — informational.** **Yes on promotion to `master`**, for HIGH/CRITICAL *with a fix available* |
| Dependency vulnerabilities | OSV-Scanner (npm lockfiles) | **No — informational** |
| Dependency updates | Dependabot (grouped, weekly, `target-branch: develop`) | n/a — opens PRs |

The container build and the ephemeral rehearsal are **required status checks on
`develop`**, and `deploy-staging.yml` runs on every pull request with no path
filter. That is deliberate and costs CI minutes on changes that plainly do not
need an image build — a documentation edit pays for one.

The filter was removed because a required check that never runs is never
*reported*, and branch protection cannot tell an absent check from a pending
one: it blocks the merge forever. While `deploy-staging.yml` filtered on
`deploy|backend|web|sdk|scripts`, every docs-only and config-only pull request
to `develop` was unmergeable without an admin bypass — in a repository whose
ADRs are authoritative, that is a routine shape of change, not an edge case.

The two rejected alternatives are worth recording, because both look cheaper:
dropping the checks from the required list demotes a gate the deployment path
depends on, which this document forbids doing silently; and skipping the jobs
from inside the workflow reports them green without running them, which is the
failure mode named at the top of this section.

**Trivy is environment-sensitive** (ADR-028 §6, as amended). On develop and
pull requests it is non-blocking for the same reason as OSV-Scanner, one layer
down: a CVE published in a base image is not a regression introduced by the
merge that happens to run next, and making every historical CVE a deployment
blocker is how a team learns to skip the gate. On **promotion to `master`** it
blocks on HIGH/CRITICAL — but only where a fix exists (`--ignore-unfixed` in
both modes), because refusing to release over a vulnerability nobody can
remediate does not make the release safer, it just stops the release. Promotion
is a deliberate, infrequent, human act; that is the right moment to require a
decision rather than a shrug.

`scripts/scan-images.test.sh` proves both halves against a stubbed scanner:
findings do not block develop, findings do block promotion, `--ignore-unfixed`
is present in both modes, all four images are scanned, and a scanner that
failed to install fails the step instead of passing quietly.

The deployment gates that *are* blocking — digest ownership/pinning, non-root
runtime, migration success, readiness, the synthetic suite, migration rollback
safety — all have something proving they can fail.

OSV-Scanner is deliberately non-blocking: a CVE published in a transitive
dependency is not a regression introduced by the pull request that happens to
run next, and blocking unrelated work on it is the noisy-gate failure mode
that gets security tooling disabled. Findings are visible in the job output
and remediated through Dependabot or an explicit dependency override.
JVM dependency vulnerabilities are monitored repository-side via GitHub's
dependency graph and Dependabot alerts rather than a second CI scanner.

Detekt 1.23.x (the current stable line) cannot run on a Java 25 runtime, so
it is detached from `check` and runs in a dedicated job on Java 21. This
bounds the analyzer's runtime only; production code still compiles against
the Java 25 toolchain and the JVM SDK still targets Java 17 bytecode.

## Hostile MIME as a first-class concern

Given email is fundamentally untrusted input, the MIME corpus test suite is
treated with the same seriousness as the security threat model — new corpus
entries should be added whenever a real-world malformed message causes an
issue, growing the suite as a regression net over time (a "fuzz corpus"
mindset, not a fixed fixture set written once).
