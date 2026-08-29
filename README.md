# TestInbox

**TestInbox is an automation-first email testing platform.** It gives automated
tests, CI pipelines, and local development environments a deterministic way to
create ephemeral email addresses, receive mail sent to them, and assert on the
result — without polling a real mailbox or fighting flaky delivery timing.

> **Status: inception.** This repository currently contains only the product
> and architectural decisions needed before implementation starts. No product
> code has been written yet. See [`VISION.md`](VISION.md) for what "done with
> inception" means and what happens next.

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

## Intended (not yet created) repository layout

```
backend/            # Kotlin/Spring Boot modular monolith (Gradle multi-module)
  api/               # HTTP adapter; implements the contract-first OpenAPI spec
  ingestion/         # Inbound mail gateway adapter (separately deployable)
  application/       # Use cases + ports (ADR-024); all invariant-bearing writes
  domain/            # Core domain model, framework-free
  persistence/       # Postgres adapter implementing repository ports
  storage/           # S3/MinIO object storage adapter
sdk/
  kotlin/            # email.testinbox:testinbox-client (JVM)
  typescript/        # @testinbox/client (npm)
web/                 # Next.js dashboard
docs/                # this documentation set
```

Nothing under `backend/`, `sdk/`, or `web/` exists yet. It will be scaffolded
only after the decisions in [`VISION.md`](VISION.md#human-decisions-required-before-implementation)
are reviewed.
