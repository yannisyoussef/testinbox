# API Design Principles

1. **REST is authoritative.** All SDKs are clients of this API; no
   SDK-only capability may exist that isn't also expressible via REST
   (SDKs may add ergonomics/DX, not exclusive functionality).
2. **Resource-oriented, versioned by URI**: `/v1/...`. See
   [`versioning.md`](versioning.md) and
   [ADR-015](../adr/0015-rest-compatibility-versioning.md).
3. **Errors follow RFC 7807** (`application/problem+json`) with a stable
   `type` per error category, human-readable `detail`, and a `correlationId`
   for support/debugging.
4. **Authentication**: `Authorization: Bearer <api-key>` on every call except
   health checks. No cookie-based auth for the API surface (dashboard may use
   session cookies against its own backend-for-frontend, out of scope here).
5. **Authorization**: API keys carry scopes (e.g., `inboxes:write`,
   `messages:read`) plus an implicit workspace/project binding; every
   resource fetch is authorized against the caller's workspace, never by
   trusting a path parameter alone (see
   [ADR-010](../adr/0010-authentication-api-keys.md)).
6. **Pagination**: cursor-based (`?cursor=...&limit=...`), never offset-based,
   for `GET /v1/inboxes/{id}/messages` and similar list endpoints — offset
   pagination is unstable under concurrent inserts, which is the common case
   here (mail arriving while a test paginates).
7. **Idempotency**: *planned, not implemented.* The intent is that mutating
   POSTs which create a resource (`POST /v1/inboxes`) accept an optional
   `Idempotency-Key` header whose replays return the original result rather
   than creating a duplicate. Until the semantics below are implemented and
   tested, the header is **absent from the public contract** rather than
   advertised-but-ignored — a documented header that silently does nothing is
   worse than no header. Implementation must define and test: scope
   (workspace/project), key retention, same-key/same-request replay,
   same-key/different-request conflict (`409`), concurrent same-key creation,
   behaviour when the original request failed, response replay, and cleanup.
8. **Correlation IDs**: every response includes a `correlationId` (also
   present in error bodies), propagated into logs/traces — see
   [`docs/architecture/observability.md`](../architecture/observability.md).
9. **Rate limits and quotas** (implemented, ADR-027): governed responses carry
   `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset` for the
   caller's own workspace, so a client can pace itself without provoking a
   refusal. The two refusals are deliberately different answers:
   - exceeding a **rate** returns `429` + `Retry-After` (whole seconds, never
     zero) with problem type `rate-limit-exceeded`, or
     `concurrent-wait-limit-exceeded` for the long-poll ceiling — waiting
     helps;
   - exhausting a **quota** returns `409` with problem type `quota-exceeded`
     and **no** `Retry-After` — waiting does not help, the caller must free
     capacity. `429` there would invite a retry loop that cannot succeed.
   `POST /v1/inboxes` therefore has two distinct `409` meanings; clients must
   discriminate on the problem `type`, never on the status code. Limits are
   workspace-scoped and derived from the authenticated key, so rotating or
   minting a key does not reset them.
10. **No breaking changes within a major version.** Additive changes
    (new optional fields, new endpoints) are always safe; anything else
    requires a new version per [ADR-015](../adr/0015-rest-compatibility-versioning.md).
