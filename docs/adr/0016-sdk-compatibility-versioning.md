# ADR-016: SDK Compatibility/Versioning

**Status:** Proposed

## Context

SDK versioning need not track REST API versioning 1:1, but the relationship
needs a stated policy before publishing.

## Decision (proposed)

Each SDK follows independent semantic versioning. An SDK major version bump
is required for any breaking change to the SDK's own public API surface,
regardless of whether the underlying REST version changed (an SDK can
usually absorb a REST v1→v2 migration internally without a breaking public
change, if the abstraction holds). This is proposed, not finalized, pending
real experience with how cleanly REST changes can be absorbed.

## Alternatives considered

- **Lockstep versioning (SDK major = REST major)**: rejected as a hard rule
  — would force unnecessary SDK major bumps for REST changes that don't
  affect the public SDK surface, churning consumers' dependency pins for no
  reason.

## Consequences

- Requires disciplined SDK-level changelog/semver review at release time,
  independent of REST API changes — process detail to be defined before
  first publish (see ADR-017, Human Decisions Required).
