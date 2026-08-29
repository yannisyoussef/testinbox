# ADR-018: Framework Integration Strategy

**Status:** Proposed

## Context

The brief lists many possible integrations (JUnit 5, TestNG, Kotest,
Playwright, Cypress, Karate, REST Assured). Building all of them is
explicitly out of scope; a principle for what belongs where is needed.

## Decision (proposed)

Framework integrations are separate packages depending on the core SDK
(`testinbox-junit5`, `@testinbox/playwright`, etc.), containing only
framework-specific glue (lifecycle hooks, fixture injection) — never
duplicated core logic (matching, wait-chaining, HTTP handling stay in the
core SDK). None are built before MVP; the first candidate(s) to build are
determined by observed adoption/demand, not speculatively up front.

## Alternatives considered

- **Bundle a JUnit 5 extension into the core `testinbox-client` artifact**:
  rejected — would force a JUnit 5 dependency onto every consumer, including
  those using TestNG, Kotest, or no framework integration at all, violating
  the "no framework lock-in in the core SDK" principle.

## Consequences

- Slightly more publishing/versioning surface (each integration is its own
  release artifact), accepted as the cost of not forcing framework choices
  on core SDK consumers.
