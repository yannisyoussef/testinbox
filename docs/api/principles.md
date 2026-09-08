# API Design Principles

1. **REST is authoritative.** All SDKs are clients of this API; no
   SDK-only capability may exist that isn't also expressible via REST
   (SDKs may add ergonomics/DX, not exclusive functionality).
2. **Resource-oriented, versioned by URI**: `/v1/...`. See
   [`versioning.md`](versioning.md) and
   [ADR-015](../adr/0015-rest-compatibility-versioning.md).
3. **Errors follow RFC 7807** (`application/problem+json`) with a stable
   `type` per error category, human-readable `detail`, and a `correlationId`
   for support/debugging. This includes the transport-level refusals — an
   unknown path, a wrong method, a rejected content type. They are `4xx` with a
   problem body, never `5xx`: **a `500` from this API means a server fault**,
   and that is what makes it worth alerting on. A catch-all exception handler
   that swallows the framework's own dispatch failures breaks this quietly,
   which is exactly how it was broken until `UnmatchedRouteTest` pinned it.
4. **Authentication**: `Authorization: Bearer <api-key>` on every call except
   health checks. No cookie-based auth for the API surface (dashboard may use
   session cookies against its own backend-for-frontend, out of scope here).
5. **Authorization**: API keys carry scopes (`inboxes:write`, `messages:read`,
   `api-keys:manage`) plus an implicit workspace/project binding; every
   resource fetch is authorized against the caller's workspace, never by
   trusting a path parameter alone (see
   [ADR-010](../adr/0010-authentication-api-keys.md)). Keys are managed
   credentials (implemented,
   [ADR-032](../adr/0032-api-key-credential-lifecycle.md)): a workspace holds
   many, each is independently revocable, and the plaintext is returned by
   exactly one response — `POST /v1/api-keys` — and never stored, so it cannot
   be shown again or recovered. A key can never grant a scope its creator does
   not hold. Operating guide: [`docs/dev/api-keys.md`](../dev/api-keys.md).
6. **Pagination**: cursor-based (`?cursor=...&limit=...`), never offset-based,
   for `GET /v1/inboxes/{id}/messages` and similar list endpoints — offset
   pagination is unstable under concurrent inserts, which is the common case
   here (mail arriving while a test paginates).
7. **Idempotency** (implemented, [ADR-033](../adr/0033-idempotent-mutations.md)):
   `POST /v1/inboxes` and `POST /v1/api-keys` accept an optional
   `Idempotency-Key`, so a client that loses a response can retry without
   creating a second resource. It is **optional** — requiring it would be a
   breaking change to a published contract — and **refused** on operations that
   do not honour it, because accepting and ignoring it would grant retry
   protection that does not exist.
   - Same key, same request → the committed result, with
     `Idempotency-Replayed: true`. Same status as the original: the resource
     exists because of this logical request, which is what the status describes.
   - Same key, **changed** request → `409 idempotency-key-reused`. Terminal;
     the original request is never disclosed.
   - A concurrent identical request → `409 idempotency-request-in-progress`
     with `Retry-After`. The opposite action to the one above, which is why
     they are different types rather than one status code.
   - **Only a committed success binds a key.** A validation error, a quota
     refusal or an address conflict leaves it free, so a corrected retry with
     the same key executes normally.
   - A replay is a record of what the request **created**, not an assertion
     that it still exists: replaying an inbox creation after the inbox expired
     returns the creation, and a subsequent fetch returns `404`.
   - `POST /v1/api-keys` is the exception, and deliberately: it **suppresses
     the duplicate rather than replaying the response**. The credential is
     returned exactly once and nothing retains it (ADR-032 §4), so a replay is
     `409 idempotency-secret-not-replayable` naming the key that was created.
     Revoke it and mint again under a fresh key.
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
