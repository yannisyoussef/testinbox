# ADR-013: Repository/Module Architecture

**Status:** Accepted (module list amended by
[ADR-024](0024-application-layer-and-dependency-rule.md): an `application`
module is added to the backend Gradle build; the monorepo decision itself
is unchanged)

## Context

Backend modules, two SDKs, a web app, and documentation all need a home;
polyrepo vs. monorepo affects release coordination.

## Decision

Single monorepo containing `backend/` (Gradle multi-module Kotlin/Spring
Boot: `api`, `domain`, `persistence`, `storage`, `auth`, `wait`,
`ingestion-gateway`), `sdk/kotlin`, `sdk/typescript`, `web/` (Next.js), and
`docs/`. Backend modules share a Gradle build; SDKs and web app have their
own toolchains (Gradle/npm respectively) within the same repo.

## Alternatives considered

- **Polyrepo (separate repo per SDK/backend/web)**: rejected for now — the
  REST contract is shared and evolves alongside SDKs during early product
  iteration; keeping them in one repo makes cross-cutting changes (e.g., a
  new API field consumed by both SDKs) a single PR instead of coordinated
  multi-repo releases. Revisit once release cadences diverge enough to
  justify the split (e.g., SDKs stabilize while backend iterates rapidly).

## Consequences

- CI must be structured to build/test only the affected parts of the
  monorepo (path-based triggers) to keep pipeline times reasonable as it
  grows.
- Versioning is independent per publishable artifact (backend has no public
  version; each SDK/package has its own semver) even though they share a
  repo — see ADR-016, ADR-017.
