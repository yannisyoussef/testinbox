# ADR-003: Inbound Mail Abstraction

**Status:** Accepted (amended by [ADR-019](0019-inbound-deduplication-semantics.md):
`providerMessageId` deduplication is scoped to reprocessing of the *same
provider delivery event* only — never content-based suppression)

## Context

The product must support multiple inbound mail providers (local SMTP
adapter, AWS SES, self-hosted Postfix, future providers) without leaking
provider-specific concepts into the core domain model.

## Decision

Define an `InboundMailProvider` port in the `ingestion-gateway` module with
adapters per provider. The port's output is a provider-agnostic "raw message
received for token X" event carrying only: recipient token, envelope
from/to, raw MIME bytes, and an optional opaque `providerMessageId` used
solely for deduplication. No provider-specific type (SES event shape,
Postfix queue semantics) crosses into `domain`.

## Alternatives considered

- **Provider-specific ingestion logic directly in the domain/API layer**:
  rejected — would make adding a provider a cross-cutting change and risks
  domain logic depending on provider quirks.

## Consequences

- Adding a new provider means implementing one adapter, not touching
  `domain`/`api`.
- The `providerMessageId` is intentionally the only provider fingerprint
  retained, purely for idempotency (see `docs/architecture/inbound-mail-flow.md`).
