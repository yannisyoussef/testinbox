# API Versioning and Compatibility Policy

See [ADR-015](../adr/0015-rest-compatibility-versioning.md).

- **URI versioning**: `/v1`, `/v2`, etc. No header-based or content-negotiated
  versioning — URI versioning is simplest to reason about for SDK generation
  and for engineers reading logs/curl commands.
- **Additive-only within a major version**: new optional request fields, new
  response fields (clients must ignore unknown fields — SDKs must be built
  to tolerate this, see [`docs/sdk/architecture.md`](../sdk/architecture.md)),
  new endpoints, new enum values *only* where the SDK/consumer contract
  already treats unknown enum values as "unknown, do not switch
  exhaustively" (documented per-field in the OpenAPI spec).
- **Breaking changes require a new major version.** Both versions are
  served concurrently for a documented deprecation window (length TBD —
  a Human Decision once there are external consumers).
- **Deprecation signaling**: `Deprecation` and `Sunset` HTTP headers (per
  the relevant IETF drafts) on responses from a version once its successor
  ships.
- **Pre-v1 status**: until the MVP is validated with real usage, the API may
  be labeled `v1` but treated internally as subject to change without the
  full deprecation ceremony — this should be stated plainly (e.g., an
  `X-API-Stability: experimental` header) rather than silently breaking
  SDKs, which is worse than admitting instability upfront.
