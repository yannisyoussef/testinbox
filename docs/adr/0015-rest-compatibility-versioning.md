# ADR-015: REST Compatibility/Versioning

**Status:** Accepted

## Context

SDKs depend on the REST contract; breaking it silently would break every
installed SDK version simultaneously.

## Decision

URI versioning (`/v1`, `/v2`...). Additive-only changes within a major
version (new optional fields/endpoints; clients must tolerate unknown
fields/enum values). Breaking changes require a new major version served
concurrently with the prior one for a deprecation window, signaled via
`Deprecation`/`Sunset` headers. See `docs/api/versioning.md`.

## Alternatives considered

- **Header/content-negotiation versioning**: rejected — harder to reason
  about from logs/curl and adds friction for SDK code generation compared
  to a simple URI segment.
- **No formal versioning during early iteration**: rejected — even during
  the experimental/pre-1.0 phase, an explicit (if lenient) policy is better
  than ad hoc breakage; the pre-v1 experimental stability is instead
  signaled via `X-API-Stability: experimental`, not by abandoning
  versioning altogether.

## Consequences

- Running two API major versions concurrently requires either two deployed
  versions or in-process version-aware routing — an implementation detail
  to resolve when a v2 is actually needed, not now.
