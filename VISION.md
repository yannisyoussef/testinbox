# TestInbox — Vision

## Product vision

TestInbox makes email a first-class, deterministic dependency in automated
testing. Instead of testing against real, provider-hosted mailboxes (Gmail,
etc.) with polling and IMAP flakiness, a test reserves a disposable inbox,
triggers an email from the system under test, and waits — with an explicit,
bounded, race-free primitive — for a matching message. TestInbox is
infrastructure for CI/CD and developer workflows, not a communication product.

## Non-goals

See [`docs/product/non-goals.md`](docs/product/non-goals.md) for the full
list. In short: TestInbox is not a YOPmail-style disposable inbox for humans,
not an SMTP relay or sending service, not persistent email storage/archival,
and not an anonymous communication channel. It is inbound-only, ephemeral,
and authenticated by design.

## Personas and use cases

See [`docs/product/personas.md`](docs/product/personas.md) and
[`docs/product/use-cases.md`](docs/product/use-cases.md).

## Primary interfaces, in priority order

1. REST API (authoritative, language-neutral)
2. JVM SDK — `email.testinbox:testinbox-client` (Maven Central)
3. TypeScript/JavaScript SDK — `@testinbox/client` (npm)
4. Testing-framework integrations (JUnit 5, Playwright, etc.) — later, as thin
   adapters over the core SDKs
5. CLI — later

The web dashboard is a secondary interface for debugging, message inspection,
and administration — not the product's primary value delivery mechanism.

## MVP

The smallest slice that proves the product end-to-end, runnable entirely
locally (no cloud dependency required):

1. `POST /v1/inboxes` creates an inbox and returns a generated address.
2. A local SMTP adapter accepts mail addressed to that inbox.
3. The message is parsed (MIME → headers/text/HTML/links/attachments),
   persisted, and raw MIME is stored in object storage (MinIO locally).
4. A waiter (`POST /v1/inboxes/{id}/messages/wait`) is notified deterministically
   (no polling) and returns the first matching message.
5. The message, including extracted links, is retrievable via REST.
6. TTL-based cleanup removes the inbox and its data automatically.
7. The same capability is exposed through the JVM SDK and TypeScript SDK, not
   just REST.
8. The web UI can safely display a received message (sandboxed HTML render)
   — nothing more.

Explicitly deferred past MVP: `EXACT` address-mode *implementation* (the
API contract defines both modes per ADR-021; whether exact mode ships in
the walking skeleton or immediately after is a scheduling decision, see
below), custom/customer domains, AWS SES adapter, webhooks, `TestRun` as a
tracked entity, attachment malware/archive-bomb scanning, multi-workspace
RBAC beyond a single API key per workspace, all framework integrations,
CLI, Python/.NET SDKs.

Rationale for cuts: each deferred item is either (a) not required to prove the
core deterministic-wait value proposition, or (b) requires infrastructure
(real domains, cloud mail providers, billing-relevant abuse controls) that
should not gate proving the core loop locally.

## Roadmap (indicative, not committed)

1. **Inception** *(this stage)* — vision, architecture, ADRs, no product code.
2. **Walking skeleton** — MVP slice above, deployable locally via
   docker-compose (Postgres, MinIO, local SMTP adapter).
3. **Hardening for shared use** — auth/quotas/rate limits suitable for a
   shared dev or staging environment; SES adapter; attachment safety scanning.
4. **SDK ecosystem breadth** — Kotlin coroutine ergonomics polish, JUnit 5 /
   Testcontainers-style integration, Playwright/Cypress helpers.
5. **Multi-tenant production readiness** — billing, quotas, custom domains,
   webhooks, audit log, SLOs.
6. **Ecosystem expansion** — Python/.NET SDKs, CLI, additional framework
   integrations — driven by demand, not speculative build-out.

Each stage should re-validate assumptions rather than assume the plan above
survives contact with real usage.

## Human Decisions Required Before Implementation

These require explicit human sign-off before implementation work starts;
they are not resolvable by further internal analysis alone.

1. **Exact-match inbox addresses — scheduling and cooldown only.** The
   design question is settled by ADR-021 (two modes: `GENERATED` default,
   `EXACT` via Postgres unique-constraint reservation with `409` on
   conflict); what still needs sign-off is (a) whether `EXACT` mode is
   implemented in the walking skeleton or immediately after, and (b) the
   cooldown-window default for exact-address reuse (proposed 24h, with
   the documented residual risk that MTA retry horizons can exceed it).
2. **Legal/compliance posture for receiving arbitrary third-party email**
   (data retention law, GDPR/CCPA for message content, abuse/CSAM reporting
   obligations for an inbound-mail service open to any authenticated user).
   Narrowed by ADR-025: unknown-recipient content is never stored, so this
   decision now covers only mail attributed to a tenant's inbox.
3. **Which inbound provider to build first in production** (self-hosted
   Postfix vs. AWS SES) — affects deployment/ops investment ordering
   (ADR-004).
4. **Maven Central and npm publishing ownership**: who holds the Sonatype
   namespace (`email.testinbox`) and npm org (`@testinbox`), and who controls
   signing keys / npm 2FA — required before ADR-017 can move past "Proposed."
5. **Pricing/quota model**, since abuse prevention (rate limits, TTL caps,
   storage caps) is partly a product/business decision, not purely technical.
6. **Whether a `TestRun` entity is a real product requirement** or an
   assumption inherited from the brief — no concrete use case currently
   justifies it for MVP.
7. **Target initial deployment environment** (self-hosted/on-prem vs. a
   TestInbox-operated cloud service vs. both) — affects DevOps/SRE priorities
   and multi-tenancy urgency.
