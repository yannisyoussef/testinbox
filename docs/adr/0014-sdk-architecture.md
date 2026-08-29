# ADR-014: SDK Architecture

**Status:** Accepted (amended by [ADR-022](0022-openapi-contract-first.md):
the OpenAPI contract is authored contract-first and drives server-side
generation — it is not generated from the Spring Boot backend)

## Context

Generated OpenAPI clients are easy to produce but make poor public APIs
(leaky DTOs, no ergonomic matcher/wait abstractions, breaking on any schema
regeneration).

## Decision

Layer: REST → OpenAPI 3.1 contract → generated internal transport
(non-exported per language) → hand-designed public SDK. Public SDKs expose
resource-oriented types (`Inbox`, `Message`) and a matcher vocabulary,
never generated DTOs, per `docs/sdk/architecture.md`.

## Alternatives considered

- **Ship the generated client directly as the public SDK**: rejected —
  explicitly rejected by the product brief and reconfirmed here: generated
  types churn with every schema change and don't provide the
  `awaitMessage`/matcher ergonomics that are core to the product's intended
  developer experience.

## Consequences

- Extra engineering investment to hand-write and maintain the public layer
  per language, justified because the SDKs are a primary product interface,
  not a convenience add-on.
- The generated transport can be swapped (different generator, or a
  hand-written client) without a public API break, since nothing public
  depends on it directly.
