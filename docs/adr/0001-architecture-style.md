# ADR-001: Architecture Style

**Status:** Accepted (amended by [ADR-024](0024-application-layer-and-dependency-rule.md):
the ingestion gateway remains independently deployable, but invokes the
shared `application` use-case layer rather than writing to persistence
directly)

## Context

The brief proposes a modular monolith over microservices, but this must be
evaluated, not assumed. Inbound mail handling has a genuinely different
network-exposure and scaling profile from the rest of the API.

## Decision

Modular monolith (single Kotlin/Spring Boot deployable, Gradle multi-module,
enforced boundaries via ArchUnit) for all API/domain/persistence concerns,
plus one deliberate exception: the **Inbound Mail Gateway** is an
independently deployable/scalable process from day one, because it
terminates untrusted network input and should be isolated from the
authenticated API for security blast-radius and independent scaling reasons
(see `docs/architecture/component-architecture.md`).

## Alternatives considered

- **Full microservices** (separate services per domain area): rejected —
  no concrete scaling/isolation/deployment-cadence evidence justifies the
  operational cost at this stage.
- **Single deployable including ingestion**: rejected — mixing untrusted
  SMTP-facing code into the same process as the authenticated API needlessly
  widens the API's attack surface and couples their scaling/deployment
  cadence.

## Consequences

- Lower operational overhead for MVP; a single team can move fast.
- Ingestion gateway must communicate with core state (Postgres/object
  storage) directly rather than through an internal API, which is
  acceptable since both share the same data ownership model
  (`docs/architecture/data-ownership.md`).
- Revisit if wait-request traffic or CRUD API traffic need independent
  scaling from each other — no evidence of that yet.
