# Observability Strategy

## Tracing

A single OpenTelemetry trace should span the full inbound path where
possible: SMTP session accepted → raw MIME stored → parsed → persisted →
wait notification published → wait request resolved. Since inbound delivery
and the eventual wait request are causally linked but not part of the same
HTTP request, correlation is via a **message correlation ID** propagated as:

- A trace/span link (not a single parent trace) between the ingestion trace
  and any wait request(s) it satisfies, since a message may satisfy multiple
  independent waiter traces.
- A stable `correlationId` returned in API responses and included in
  RFC 7807 error bodies, so a support/debugging session can grep logs across
  both the API and ingestion-gateway processes.

## Metrics

Exported on the **private management port** (`/actuator/prometheus`, 9090 API /
9091 ingestion), which is never routed by the edge. Scraped by the estate's
Prometheus (ADR-030).

### Naming

Every meter carries the `testinbox_` prefix. This document originally wrote
some names unprefixed (`inbox_created_total`) and some prefixed
(`testinbox_rate_decision_total`); the implementation normalises all of them,
because a half-prefixed namespace is worse than either convention applied
consistently.

Two names deviate further, and the reason is worth knowing before adding a
metric: **the Prometheus exposition format reserves the suffixes `_created`,
`_info`, `_total`, `_count`, `_sum` and `_bucket`**, and Micrometer strips them
before re-adding the type suffix. A meter named `testinbox_inbox_created_total`
exports as `testinbox_inbox_total`, and `testinbox_build_info` exports as
`testinbox_build` — so a dashboard written against the documented name would
have queried a series that does not exist. `MetricCardinalityTest` asserts the
**exported** names for exactly this reason.

### Implemented

| Metric | Type | Labels | Emitted when |
|---|---|---|---|
| `testinbox_inbox_creations_total` | counter | `mode` | An inbox is created (`_created_total` is unusable — see above) |
| `testinbox_inbox_expired_total` | counter | — | The lifecycle sweep moves an inbox to EXPIRED |
| `testinbox_inbox_deleted_total` | counter | — | Explicit teardown |
| `testinbox_message_received_total` | counter | `parse_status` | A message row is appended |
| `testinbox_message_parse_duration_seconds` | timer | `parse_status` | Around the MIME parser alone |
| `testinbox_message_duplicate_event_noop_total` | counter | — | A reprocessed provider event appends nothing (ADR-019/026) |
| `testinbox_smtp_accept_total` | counter | — | A `DATA` transaction gets the uniform 250 |
| `testinbox_smtp_reject_total` | counter | `reason` | Protocol-level refusal (never recipient existence) |
| `testinbox_smtp_unknown_recipient_discard_total` | counter | — | A recipient resolved to no receivable inbox |
| `testinbox_wait_request_duration_seconds` | timer | `outcome` | Every `waitForMessage`, including failures |
| `testinbox_wait_requests_active` | gauge | — | In-flight waits; catches handle leaks |
| `testinbox_wait_listen_reconnect_total` | counter | — | Each LISTEN reconnect attempt |
| `testinbox_wait_listen_degraded_polling` | gauge | — | **1 while notifications are not being delivered** |
| `testinbox_object_storage_operation_duration_seconds` | timer | `operation`, `outcome` | Every blob operation |
| `testinbox_rate_decision_total` | counter | `category`, `outcome` | ADR-027 rate decision |
| `testinbox_quota_rejected_total` | counter | `quota` | ADR-027 quota refusal |
| `testinbox_wait_slot_rejected_total` | counter | — | Concurrent-wait admission refusal |
| `testinbox_wait_slots_active` | gauge | — | Held wait slots |
| `testinbox_build` | gauge (=1) | `service`, `git_sha`, `version` | Once per process — "what is running?" |
| `testinbox_api_key_auth_total` | counter | `outcome` | Every authentication attempt (ADR-032) |
| `testinbox_api_key_lifecycle_total` | counter | `operation` | A credential is created or revoked |
| `testinbox_api_key_last_used_writes_total` | counter | — | A coalesced `last_used_at` write actually reached the database |

### Two credential signals worth watching

`testinbox_api_key_auth_total{outcome="REVOKED"}` climbing means something is
still presenting a credential that was retired — a pipeline that missed a
rotation, or an attacker replaying a leaked key. Both are worth knowing and
neither is visible anywhere else: the client just sees a `401`, identical to
every other authentication failure.

`testinbox_api_key_last_used_writes_total` tracking request volume means the
ADR-032 §7 coalescing has stopped working and the authentication path has
silently acquired a per-request database write. In the steady state this
counter moves at most once per credential per five minutes.

The failure outcomes are distinguished from one another here and nowhere else.
The HTTP answer is one byte-identical `401` for all of them, because a response
that told the client *why* would be an enumeration oracle; the scrape endpoint
is not client-reachable, so the same distinction is safe — and necessary — for
an operator.

### The one to alert on

`testinbox_wait_listen_degraded_polling` deserves its own alert, because the
condition it reports is **otherwise visible only as an outage or as nothing at
all**. When the notification path breaks, TestInbox does not fail:
`waitForMessage` still returns the right answer, HTTP stays 200 and messages
still arrive — only latency moves, by up to the degraded re-query interval
(ADR-020).

Readiness is not a substitute. ADR-020 puts LISTEN health *in* the readiness
group, so on a platform that routes on readiness this designed latency
degradation removes the node entirely — a blunter outcome than the condition
warrants, and one that tells an operator "down" rather than "degraded". The
gauge is what distinguishes the two, and revisiting the readiness membership
belongs in an ADR-020 amendment rather than a change made in passing here. A database placed behind a transaction-mode pooler produces exactly
this state — `LISTEN` is accepted and no notification is ever delivered
(ADR-030 capability 2).

    testinbox_wait_listen_degraded_polling == 1   for more than a minute

`testinbox_wait_listen_reconnect_total` distinguishes a single blip from
flapping, and is registered at zero so `increase()` works over a window that
contains process start.

### Deliberately not implemented

- **Image digest as a label on `testinbox_build`.** An image cannot know its
  own digest. A value computed at runtime would be a convincing lie in the one
  metric whose entire purpose is identifying what is deployed. The digest is
  deployment metadata and stays with Ops, which chose it; `git_sha` is baked in
  at build time and identifies the source unambiguously.
- **`wait_request_duration_seconds{outcome="cancelled"}`.** A client
  disconnecting mid-wait is not observable from the use case — the servlet
  thread simply returns — so a `cancelled` bucket would be permanently empty
  and would misrepresent the others as complete. The `ERROR` outcome covers
  the failures that *are* observable.

### A scrape endpoint is a trust boundary

`testinbox_smtp_unknown_recipient_discard_total` is deliberately unlabelled and
cannot name a recipient, so it does not reintroduce the ADR-025 enumeration
oracle in the obvious form. It is still a *differential* one for anyone who can
both send SMTP and read the scrape endpoint: send to one address, re-scrape,
and compare against `testinbox_smtp_accept_total`.

The control is entirely network-level — the management port is never published
and never routed by the edge — which makes it a deployment assumption rather
than something this repository can test. It is recorded here so it is a stated
boundary rather than an accident of a compose file: **an environment that
exposes `/actuator/prometheus` more widely than its SMTP listener has weakened
ADR-025.**

### Cardinality is a security property, not tidiness

Every label value is drawn from a closed Kotlin enum or a boolean. Workspace
id, API key, inbox id, message id, email address and correlation id are **never
labels**: their cardinality is chosen by the caller, so labelling by them would
let a caller decide how much memory the metrics backend spends. Per-tenant
attribution belongs in the structured logs, which are access-controlled and
already carry a correlation id.

`MetricCardinalityTest` enforces this against the whole registry rather than
per metric: it drives every enum value through every meter, asserts every label
key and value against an allow-list, asserts that a thousand further events
create no new series, and asserts that a random tenant identifier appears
nowhere in the scrape.

## Logging

Structured (JSON) logs, no raw message HTML/attachment bytes logged (only
metadata: sizes, content-types, parse outcome) to avoid leaking user content
into log aggregation. API key values are never logged, even hashed forms are
avoided in general logs (rely on a separate, access-controlled audit trail
once `AuditEvent` exists).

## SLO candidates (not yet committed — needs real usage data)

- P99 `wait` resolution latency after message persistence: target sub-second
  for the "message already visible" fast path.
- P99 SMTP-accept-to-message-visible latency.
- Ingestion availability distinct from API availability (they are separate
  deployables and should be monitored/alerted independently).
