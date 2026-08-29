# ADR-017: Maven/npm Distribution

**Status:** Proposed

## Context

Publishing requires namespace ownership (Sonatype `email.testinbox`, npm
`@testinbox`) and signing/publish-credential custody that are organizational
decisions, not purely technical ones.

## Decision (proposed)

Publish `testinbox-client` to Maven Central under group `email.testinbox`
and `@testinbox/client` to npm, via CI-driven, signed/provenance-attested
releases (see `docs/sdk/distribution.md`) — contingent on the namespace/
account-ownership Human Decision in `VISION.md` being resolved first.
Nothing is published during inception.

## Alternatives considered

- **Publish under a personal/unscoped namespace and migrate later**:
  rejected — migrating a published Maven Central group ID or npm scope
  after adoption is disruptive for consumers; better to resolve ownership
  before the first publish than move it later.

## Consequences

- This ADR cannot move to `Accepted` until Sonatype namespace verification
  and npm org creation are actually completed by a human decision-maker.
