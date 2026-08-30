# TestInbox

**TestInbox is an automation-first email testing platform.** It gives automated
tests, CI pipelines, and local development environments a deterministic way to
create ephemeral email addresses, receive mail sent to them, and assert on the
result — without polling a real mailbox or fighting flaky delivery timing.

> **Status: walking skeleton.** The foundation and MVP vertical slice are
> implemented and runnable entirely locally: backend (API + SMTP ingestion
> gateway), contract-first REST v1, JVM and TypeScript SDKs, and a minimal
> inspection web UI. See [`docs/dev/local-setup.md`](docs/dev/local-setup.md)
> to run it, and [`VISION.md`](VISION.md) for the roadmap.

## Why TestInbox

Most disposable-email tools (e.g. Mailinator/YOPmail-style products) are built
for humans browsing a inbox in a browser. TestInbox is built for machines:
SDKs and a REST API are the primary interfaces; the web dashboard exists for
debugging, not as the product.

```kotlin
val inbox = testInbox.createInbox(ttl = 10.minutes)
sut.register(inbox.address)
val message = inbox.awaitMessage(timeout = 30.seconds) {
    from("no-reply@example.com")
    subjectContains("Verify your email")
}
val verificationUrl = message.links.first().href
```

## Documentation map

| Area | Where |
|---|---|
| Product vision, non-goals, MVP, roadmap, open decisions | [`VISION.md`](VISION.md) |
| Personas, use cases, domain model | [`PRODUCT.md`](PRODUCT.md), [`docs/product/`](docs/product/) |
| System context, components, data flow, lifecycle, wait semantics | [`docs/architecture/`](docs/architecture/) |
| REST API design principles and v1 surface | [`docs/api/`](docs/api/) |
| SDK design and package distribution | [`docs/sdk/`](docs/sdk/) |
| Threat model and abuse prevention | [`docs/security/`](docs/security/), [`SECURITY.md`](SECURITY.md) |
| Testing/quality strategy | [`docs/quality/`](docs/quality/) |
| Architecture decision records | [`docs/adr/`](docs/adr/README.md) |
| Contribution and repo conventions | [`CONTRIBUTING.md`](CONTRIBUTING.md) |

## Repository layout

```
backend/            # Kotlin/Spring Boot modular monolith (Gradle multi-module)
  api/               # HTTP adapter; implements the contract-first OpenAPI spec
                     #   (committed at backend/api/contract/openapi.yaml, ADR-022)
  ingestion/         # Inbound mail gateway adapter (separately deployable)
  application/       # Use cases + ports (ADR-024); all invariant-bearing writes
  domain/            # Core domain model, framework-free
  persistence/       # Postgres adapter implementing repository ports (+ Flyway)
  storage/           # S3/MinIO object storage adapter
  notification/      # Postgres LISTEN/NOTIFY wait fan-out adapter (ADR-020)
  architecture/      # ArchUnit dependency-rule tests
  e2e/               # Black-box acceptance: SMTP + REST + Karate + both SDKs
sdk/
  kotlin/            # email.testinbox:testinbox-client (JVM, Java 17 baseline)
  typescript/        # @testinbox/client (npm, Node >= 20)
web/                 # Next.js inspection UI (sandboxed HTML preview, ADR-011)
docs/                # this documentation set
```
