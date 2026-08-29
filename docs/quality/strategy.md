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

This is a CI-configuration requirement to be implemented alongside the first
CI pipeline, not a currently-implemented mechanism (no CI exists yet at the
inception stage).

## Hostile MIME as a first-class concern

Given email is fundamentally untrusted input, the MIME corpus test suite is
treated with the same seriousness as the security threat model — new corpus
entries should be added whenever a real-world malformed message causes an
issue, growing the suite as a regression net over time (a "fuzz corpus"
mindset, not a fixed fixture set written once).
