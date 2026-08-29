# REST v1 — Endpoint Evaluation

| Endpoint | MVP? | Notes |
|---|---|---|
| `POST /v1/inboxes` | Yes | Body: `ttl`, `addressMode` (`GENERATED` default with optional `aliasHint` prefix; `EXACT` with `localPart`, `409` on reservation conflict, cooldown on reuse — ADR-021). Supports `Idempotency-Key`. |
| `GET /v1/inboxes/{id}` | Yes | Returns state (`Active`/`Expiring`/`Expired`/`Deleted`), address, TTL/expiry timestamp. |
| `DELETE /v1/inboxes/{id}` | Yes | Explicit early teardown; cascades to messages/attachments per `message-lifecycle.md`. |
| `GET /v1/inboxes/{id}/messages` | Yes | Cursor-paginated; supports basic filter params mirroring wait matchers, for polling-style consumers/dashboard. |
| `POST /v1/inboxes/{id}/messages/wait` | Yes | The core primitive — see [`wait-semantics.md`](../architecture/wait-semantics.md). Long-poll, capped duration; `200` with explicit `MATCHED`/`TIMEOUT` result (never `408` — ADR-020); `410 Gone` on a non-`Active` inbox. |
| `GET /v1/messages/{id}` | Yes | Parsed fields + `parseStatus`; `404` if not found or caller lacks workspace access (not `403`, to avoid resource-existence leakage across tenants). |
| `GET /v1/messages/{id}/raw` | Yes | Streams raw MIME from object storage; useful for debugging parse failures. |
| `GET /v1/messages/{id}/attachments` | Yes | Metadata list only. |
| `GET /v1/messages/{id}/attachments/{attachmentId}` | Yes | Streams attachment bytes with the original `Content-Type`, served with `Content-Disposition: attachment` and a restrictive `Content-Security-Policy`/`X-Content-Type-Options: nosniff` to prevent it being rendered as HTML if fetched directly in a browser. |
| `POST /v1/test-runs` | **No** | Deferred — see `TestRun` under Human Decisions Required in `VISION.md`. Not built until a concrete use case is confirmed. |
| `GET /v1/test-runs/{id}` | **No** | Same as above. |
| `DELETE /v1/test-runs/{id}` | **No** | Same as above. |

## Cross-cutting decisions

- **Cursor pagination** everywhere lists exist (`messages`, future
  `inboxes` listing, `attachments` if it grows).
- **Scopes**: `inboxes:write` required for create/delete, `messages:read`
  for message/attachment retrieval and wait, separable so a CI job can hold
  a read-only key for reporting while a different key creates inboxes.
- **RFC 7807** `type` values are stable URIs under
  `https://testinbox.email/problems/...` (e.g., `.../inbox-not-found`,
  `.../rate-limited`), documented alongside the OpenAPI spec once it exists.
- **`test-runs` omission is deliberate**, not an oversight: including it in
  v1 without a confirmed use case risks locking in a schema for a concept
  that may not survive contact with real usage.
