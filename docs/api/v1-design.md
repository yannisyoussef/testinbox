# REST v1 — Endpoint Evaluation

| Endpoint | MVP? | Notes |
|---|---|---|
| `POST /v1/inboxes` | Yes | Body: `ttl`, `addressMode` (`GENERATED` default with optional `aliasHint` prefix; `EXACT` with `localPart`, `409` on reservation conflict, cooldown on reuse — ADR-021). `Idempotency-Key` is deferred, not advertised (docs/api/principles.md §7). |
| `GET /v1/inboxes/{id}` | Yes | Returns state (`Active`/`Expiring`/`Expired`/`Deleted`), address, TTL/expiry timestamp. |
| `DELETE /v1/inboxes/{id}` | Yes | Explicit early teardown; cascades to messages/attachments per `message-lifecycle.md`. |
| `GET /v1/inboxes/{id}/messages` | Yes | Cursor-paginated; supports basic filter params mirroring wait matchers, for polling-style consumers/dashboard. |
| `POST /v1/inboxes/{id}/messages/wait` | Yes | The core primitive — see [`wait-semantics.md`](../architecture/wait-semantics.md). Long-poll, capped duration; `200` with explicit `MATCHED`/`TIMEOUT` result (never `408` — ADR-020); `410 Gone` on a non-`Active` inbox. With the optional `afterStorageRefusalCount` cursor, `409 storage-limit-exceeded` when a storage ceiling refused a copy after the caller's boundary (ADR-035 §13c); omitted, the legacy behaviour is unchanged. |
| `GET /v1/workspace/storage` | Yes | The authenticated key's own workspace `StorageUsage` (`limitBytes`, `storedBytes`, `reservedBytes`, `availableBytes`, `overLimit`; ADR-035 §13a). No workspace id is accepted from anywhere; no global figure is returned. Every inbox representation carries its own `storage` plus `storageRefusalCount`, `lastStorageRefusalAt` and `lastStorageRefusalReason`, read live (§13b). |
| `GET /v1/messages/{id}` | Yes | Parsed fields + `parseStatus`; `404` if not found or caller lacks workspace access (not `403`, to avoid resource-existence leakage across tenants). |
| `GET /v1/messages/{id}/raw` | Yes | Streams raw MIME from object storage; useful for debugging parse failures. |
| `GET /v1/messages/{id}/attachments` | Yes | Metadata list only. |
| `GET /v1/messages/{id}/attachments/{attachmentId}` | Yes | Streams attachment bytes with the original `Content-Type`, served with `Content-Disposition: attachment` and a restrictive `Content-Security-Policy`/`X-Content-Type-Options: nosniff` to prevent it being rendered as HTML if fetched directly in a browser. |
| `GET /v1/inboxes` | Yes | The workspace's inboxes, newest first, every state still present unless `state` narrows it; keyset-paged on `(createdAt, id)`; each item carries its live `storage` read for the page in one set-based read (ADR-035 §13b). Charged `READ`. |
| `GET /v1/messages` | Yes | The workspace-wide feed across every *serving* inbox (never `EXPIRED`/`DELETED` ones, ADR-035 Amendment 2 servability); newest first, `order=asc` walks forward from the position of the newest message held, which is how a client catches up. The same opaque cursor kind as the per-inbox list and the event stream. Charged `READ`. |
| `GET /v1/workspace` | Yes | The key's own workspace, its `mailDomain` and the key's project. No id accepted from anywhere. Charged `READ`. |
| `GET /v1/workspace/usage` | Yes | ADR-027 in one read: every quota dimension with its limit and row-derived consumption, every rate category with its policy and the tokens left (read without spending), and whether limits are enforced. Charged `READ`. |
| `GET /v1/workspace/events` | Yes | Server-sent events (`message.received`, `inbox.created/expiring/expired/deleted/gone`) for the workspace, one `wait-window-cap` window at a time. Every window ends with an `end` event whose `id`/`resume` the client sends back as `Last-Event-ID`; the next window re-reads a 90 s visibility horizon behind it, so no message committed within 90 s of receipt is missed and a few may repeat (at-least-once across windows, deduplicate on the message `id`; `id` lines are positions and not monotonic). A parked wait over the whole workspace: charged `WAIT`, holds a concurrent-wait slot (ADR-027 §7), reconnects must honour `Retry-After` on `429`. |
| `POST /v1/test-runs` | **No** | Deferred — see `TestRun` under Human Decisions Required in `VISION.md`. Not built until a concrete use case is confirmed. |
| `GET /v1/test-runs/{id}` | **No** | Same as above. |
| `DELETE /v1/test-runs/{id}` | **No** | Same as above. |

## Cross-cutting decisions

- **Cursor pagination** everywhere lists exist (`messages`, `inboxes`,
  `api-keys`; `attachments` if it grows). Cursors are opaque: a client
  passes back what it was given and never constructs one. A message list's
  position and an event stream's `Last-Event-ID` are the same kind, so one
  resumes the other.
- **Scopes**: `inboxes:write` required for create/delete, `messages:read`
  for every read — message/attachment retrieval, wait, the inbox and
  message lists, the workspace, its usage and its event stream — separable so a CI job can hold
  a read-only key for reporting while a different key creates inboxes.
- **RFC 7807** `type` values are stable URIs under
  `https://testinbox.email/problems/...` (e.g., `.../inbox-not-found`,
  `.../rate-limited`), documented alongside the OpenAPI spec once it exists.
- **`test-runs` omission is deliberate**, not an oversight: including it in
  v1 without a confirmed use case risks locking in a schema for a concept
  that may not survive contact with real usage.
