# TestInbox

**TestInbox is an automation-first email testing platform.** It gives automated
tests, CI pipelines, and local development environments a deterministic way to
create ephemeral email addresses, receive mail sent to them, and assert on the
result — without polling a real mailbox or fighting flaky delivery timing.

> **Status: first release candidate (0.1.0), not yet in production.** The
> MVP developer loop is implemented end to end and verified on a dark staging
> environment: authenticate with an API key, create a disposable inbox,
> receive mail over SMTP, inspect the parsed message, raw MIME and
> attachments, wait deterministically for a matching message, and delete or
> let the inbox expire. See [`docs/releases/0.1.0.md`](docs/releases/0.1.0.md)
> for what the release contains and what it deliberately leaves out,
> [`docs/dev/local-setup.md`](docs/dev/local-setup.md) to run it locally, and
> [`VISION.md`](VISION.md) for the roadmap.

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

## What ships, and what does not (0.1.0)

**Supported.** The contract-first REST v1 (`backend/api/contract/openapi.yaml`):
API keys with a managed lifecycle (ADR-032), inboxes with a TTL in `GENERATED`
or `EXACT` address mode (ADR-021), inbound SMTP ingestion with real MIME
parsing, attachments and raw MIME retrieval (ADR-005), the deterministic
`wait` primitive over PostgreSQL LISTEN/NOTIFY (ADR-020), idempotent creates
(ADR-033), per-workspace rate limits and quotas (ADR-027), storage visibility
figures (ADR-035 §13), RFC 7807 errors, and the developer loop through the
JVM SDK (Java 17 baseline) and the TypeScript SDK (Node ≥ 22): inboxes,
waits, messages, raw MIME, attachment metadata, API keys and storage figures
(attachment bytes are REST-only for now). The web app is
an inspection tool with a sandboxed HTML preview (ADR-011), not a dashboard.

**Deployment status.** Deployed dark on staging behind Cloudflare → Traefik
with the full post-deployment synthetic suite as the gate; a first production
deployment is dark too: no public MX, no public SMTP. Storage *accounting* is
live everywhere; storage *enforcement* is `OFF` by owner decision until the
ADR-035 filesystem qualification completes — the physical storage bound of
ADR-035 is therefore **not** enforced in this release.

**Not yet available.** The SDKs are built from this repository, not from Maven
Central or npm (publishing is a pending human decision); custom domains,
webhooks, an AWS SES adapter, malware scanning of attachments, framework
integrations and further SDKs are roadmap items, not promises.

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
| Deployment, staging, release and rollback | [`docs/dev/deployment.md`](docs/dev/deployment.md), [`docs/dev/staging.md`](docs/dev/staging.md), [`docs/dev/release-process.md`](docs/dev/release-process.md), [`docs/dev/rollback.md`](docs/dev/rollback.md) |
| Production contract and Ops acceptance | [`docs/dev/production.md`](docs/dev/production.md), [`docs/dev/production-ops-acceptance.md`](docs/dev/production-ops-acceptance.md) |
| Third-party image mirrors (why MinIO is mirrored, and its provenance) | [`docs/dev/third-party-mirrors.md`](docs/dev/third-party-mirrors.md) |
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
  observability/     # Micrometer metric adapters
  migrator/          # One-shot Flyway executor; the sole migration path (ADR-029)
  architecture/      # ArchUnit dependency-rule tests
  e2e/               # Black-box acceptance: SMTP + REST + Karate + both SDKs
sdk/
  kotlin/            # email.testinbox:testinbox-client (JVM, Java 17 baseline)
  typescript/        # @testinbox/client (npm, Node >= 22)
web/                 # Next.js inspection UI (sandboxed HTML preview, ADR-011)
deploy/
  docker/            # Production-grade container definitions (ADR-028)
  staging/           # Reference topology: compose, nginx edge, deploy script (staging and production profiles)
  backup/            # Data-lifecycle backup scope: which tables a backup may hold (ADR-034)
  rollback-floors.txt # Commits a production candidate must contain (ADR-034)
  synthetic/         # Post-deployment verification driven by the public SDK
scripts/             # CI gates and their self-tests; the staging rehearsal
docs/                # this documentation set
```
