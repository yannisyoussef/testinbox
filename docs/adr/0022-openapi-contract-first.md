# ADR-022: Contract-First OpenAPI

**Status:** Accepted (amends [ADR-014](0014-sdk-architecture.md); corrects
`docs/sdk/architecture.md`)

## Context

The SDK architecture document declared the OpenAPI contract "the single
source of truth for request/response shapes, **generated from the Spring
Boot backend** (springdoc or equivalent)". This is internally
contradictory: if the spec is generated from code, the code — its
annotations and serialization behavior — is the actual source of truth,
and the "contract" is a build artifact that drifts with implementation
details. For a product whose JVM and TypeScript SDKs are primary
interfaces (`VISION.md`), API changes would then be reviewed as diffs of
generated output rather than as changes to the authored contract, and
incompatible changes would surface only at SDK regeneration time.

## Decision

A **committed, hand-authored OpenAPI 3.1 specification** (proposed
location: `backend/api/contract/openapi.yaml`, versioned with the repo)
is the source of truth for the REST surface. It drives:

1. **Server-side generation**: request/response models and controller
   *interfaces* (e.g., `openapi-generator` kotlin-spring with
   interface-only output) that the `api` module implements. The compiler
   then enforces that the backend matches the contract.
2. **JVM internal transport generation** (per ADR-014's layering —
   unchanged: generated transport stays non-exported behind the
   hand-designed public SDK).
3. **TypeScript internal transport generation** (same layering).
4. **CI compatibility checks**: spec linting (e.g., Spectral) and a
   breaking-change diff against `main` (e.g., `oasdiff`) that mechanically
   enforces the additive-only-within-a-major-version rule of ADR-015,
   plus contract tests (already in `docs/quality/strategy.md`) verifying
   the running implementation conforms.

An API change therefore starts as a reviewed spec diff in the same PR as
its implementation — the contract is the reviewed artifact, matching the
"REST is authoritative" principle in `docs/api/principles.md`.

## Alternatives considered

- **Code-first (springdoc-generated spec)**: rejected — the contract
  becomes downstream of implementation; drift is discovered by SDK
  regeneration churn; annotation defaults leak into the public contract;
  no mechanical pre-merge breaking-change gate is possible against a
  generated artifact without effectively re-creating contract-first.
- **Contract-first with hand-written server DTOs (no server generation)**:
  viable fallback if generator output quality disappoints, but weaker —
  conformance then rests on contract tests alone rather than the
  compiler. The layering keeps this swappable.

## Consequences

- ADR-014's layering diagram is unchanged; only the arrow direction
  between code and contract reverses (contract → server, not server →
  contract). `docs/sdk/architecture.md` is corrected.
- Writing OpenAPI by hand is a real (modest) authoring cost, paid where
  the product's primary interface is defined — the right place to pay it.
- Generator toolchain choices (which generator, lint ruleset, diff tool)
  are implementation details decided at walking-skeleton time; this ADR
  fixes only the direction of authority.
