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
| Architecture | ArchUnit | Enforces module boundaries from `docs/architecture/component-architecture.md` (e.g., `domain` module must not depend on Spring/JPA/provider-specific types). |
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
| Kotlin static analysis | Detekt (backend + JVM SDK) | Yes |
| Test execution evidence | `verify-test-results.sh` | Yes |
| Verifier self-test | `verify-test-results.test.sh` | Yes |
| Architecture boundaries | ArchUnit | Yes |
| OpenAPI structure/style | Spectral | Yes |
| OpenAPI backwards compatibility | oasdiff vs. the PR base spec | Yes on ERR; warnings (e.g. removing an optional parameter) are reported only |
| Compatibility-gate self-test | `openapi-breaking-check.test.sh` | Yes |
| Secret detection | gitleaks (working tree) | Yes |
| Dependency vulnerabilities | OSV-Scanner (npm lockfiles) | **No — informational** |
| Dependency updates | Dependabot (grouped, weekly) | n/a — opens PRs |

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
