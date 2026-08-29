# Contributing

TestInbox is currently in the **inception** stage: only architectural and
product documentation exists (see [`README.md`](README.md)). This document
describes the conventions that will apply once implementation begins, so that
early contributions are consistent from the start.

## Repository structure

Single monorepo (see [ADR-013](docs/adr/0013-repository-module-architecture.md)):
backend (Gradle multi-module Kotlin/Spring Boot), `sdk/kotlin`, `sdk/typescript`,
`web` (Next.js), and `docs/`.

## Decision records

Architecturally significant decisions are recorded as ADRs under
[`docs/adr/`](docs/adr/README.md), numbered sequentially, using the standard
Context / Decision / Consequences format with an explicit status
(`Proposed`, `Accepted`, `Superseded`, `Rejected`). Propose a new ADR instead
of silently deviating from an existing one; supersede rather than edit
history.

## Commit and branch conventions

- Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, etc.) to drive
  changelog generation for both Maven and npm releases.
- Feature branches off `main`; no long-lived branches.
- No direct pushes to `main` once CI is established.

## Testing expectations

See [`docs/quality/strategy.md`](docs/quality/strategy.md). In short: new
backend code requires unit tests, integration-level changes (persistence,
inbound mail, wait semantics) require Testcontainers-backed tests, and CI must
verify tests actually executed and passed — not just that the build exited
zero.

## Before adding a dependency or new module

Check whether it changes a documented ADR. Architecture-changing PRs
(new service boundary, new datastore, new external provider) should update or
add an ADR in the same PR, not after the fact.
