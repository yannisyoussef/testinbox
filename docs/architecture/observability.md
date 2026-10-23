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
| `testinbox_idempotency_total` | counter | `operation`, `outcome` | Every request carrying an `Idempotency-Key` resolves (ADR-033) |
| `testinbox_storage_ledger_unfolded_rows` | gauge | — | After each ADR-035 compaction tick, including a failed or contended one: delta rows not yet folded. The figure is global, so aggregate replicas with `max`. |
| `testinbox_storage_covered_bytes` | gauge | `kind` (`committed`/`reserved`) | `committed`: after each compaction tick, Σ base + Σ delta. `reserved`: after each cleanup pass, Σ bytes of every unreleased reservation. Both global, so aggregate replicas with `max`. `committed + reserved` is the covered total the ADR-035 §14 (b) physical baseline compares `physical_listed_bytes` against. |
| `testinbox_storage_ledger_compaction_total` | counter | `outcome` (`ok`/`contended`/`failed`) | Every compaction tick. `contended` is normal with several replicas; a sustained `failed` means the ledger is not being folded. |
| `testinbox_storage_admission_total` | counter | `outcome` (`admitted`/`refused_inbox`/`refused_workspace`/`refused_global`) | Every recipient copy decided by ADR-035 T1. Enforcement is OFF, so the `refused_*` series stay 0 in every deployment. |
| `testinbox_storage_admission_unenforced_total` | counter | `ceiling` (`inbox`/`workspace`/`global`) | A copy exceeded a ceiling whose enforcement is OFF: observed, not refused (ADR-035 Phase 2). |
| `testinbox_storage_admission_lock_wait_seconds` | timer | — | Every T1 (lock, snapshot, reservation insert). |
| `testinbox_storage_slot_wait_seconds` | timer | — | Every write-slot acquisition, successful or not. |
| `testinbox_storage_physical_failure_total` | counter | `kind` (`quota`/`unavailable`/`timeout`/`ambiguous`/`deadline`/`lock_timeout`/`slot_wait`/`clock_offset`) | An event abandoned for infrastructure (`451`). Never capacity. |
| `testinbox_storage_commit_fenced_total` | counter | — | T2 found a reservation no longer `RESERVED`: cleanup owned it. |
| `testinbox_storage_reservation_released_total` | counter | `path` (`committed`/`absent`/`deleted`/`reconciled`) | Cleanup released a reservation; `reconciled` is the impossible case [D] and alarms. |
| `testinbox_storage_late_object_total` | counter | — | An object reappeared after cleanup deleted it. The admission latch is set. |
| `testinbox_storage_witness_failed_total` | counter | — | A storage witness did not complete: releases wait. |
| `testinbox_storage_breaker_open` | gauge | — | 1 while this ingestion node's storage breaker is open. |
| `testinbox_storage_admission_latched` | gauge | — | 1 once a node has seen the latch set. |
| `testinbox_storage_clock_offset_seconds` | gauge | — | The last measured DB↔storage clock offset. |
| `testinbox_storage_ambiguous_uploads` | gauge | — | Unresolved persisted ambiguity (each holds a write slot). |
| `testinbox_storage_reservations` | gauge | `state` (`reserved`/`releasing`) | Live reservations, after each cleanup pass. |
| `testinbox_storage_physical_listed_bytes` | gauge | — | Payload bytes actually listed in the bucket (orphan sweep). |
| `testinbox_storage_incomplete_uploads` | gauge | — | Incomplete multipart uploads in the bucket. TestInbox never starts one, so any is a defect. |
| `testinbox_storage_global_limit_bytes`, `testinbox_storage_finalize_budget_bytes` | gauge | — | The EFFECTIVE G and H of this deployment (declared values, or the ADR-035 reference while OFF). H is what the activation barrier's physical-baseline check reads. |
| `testinbox_storage_enforcement_mode` | gauge (0/1) | `mode` (`off`/`tenant_limits`/`all`) | 1 on the effective ADR-035 enforcement mode, 0 on the others (TI-STORAGE-006). Operators read the mode here; it never appears in a tenant response. Alert: `off` in production for more than 24 h once enablement has begun (§16). |
| `testinbox_storage_activation_violation` | gauge (0/1) | — | 1 while a non-OFF node's re-check of the §14 barrier (session allowlist, node inventory) fails. The node answers `451` meanwhile. Any 1 alerts; identities are in the `storage_activation_violation` error log, never a label. |
| `testinbox_storage_activation_gate_ready` | gauge (0/1) | `gate` (`session_allowlist`/`node_inventory`) | The two runtime-checkable barrier gates as this node last saw them, in EVERY mode (OFF observes, non-OFF fails closed). The remaining gates are evaluated by `scripts/check-storage-activation.sh`. |
| `testinbox_storage_orphan_sweep_completed_at_seconds` | gauge (epoch s) | — | When the last FULL orphan sweep completed on this API node; 0 until one has since process start. Barrier (b) requires it later than `process_start_time_seconds` of the candidate. |
| `testinbox_storage_orphan_sweep_total` | counter | `outcome` (`ok`/`failed`) | Every orphan sweep pass. A `failed` pass never moves the completion marker. |
| `testinbox_storage_accounting_drift_total` | counter | `direction` (`under`/`over`) | Reconciliation repaired a drifted figure. Always a defect. |
| `testinbox_storage_reconciliation_total` | counter | `outcome` (`clean`/`repaired`/`failed`) | Every ADR-035 reconciliation run |

### Reading the idempotency outcomes

Both labels are closed enums (`IdempotentOperation`, `IdempotencyOutcome`); the
key itself is never a label, hashed or otherwise. The five outcomes answer
different questions, and only two of them are ever interesting:

- `EXECUTED` / `REPLAYED` — the feature working. A healthy client retrying a
  lost response shows up here, so a rising `REPLAYED` is not a problem signal;
  it is evidence that clients are protected.
- `IN_PROGRESS` — **the one to watch.** It means a duplicate arrived while the
  first was still running and did not resolve inside the claim wait. A sustained
  rise is either genuine client contention or a slow mutation, and it is also
  the signal that would move if the retention sweep ever started contending with
  claims (ADR-033 §9), which is otherwise invisible.
- `CONFLICT` — a client reusing one key for different requests, or a second
  credential replaying a key-creation. Client-side bug, not ours; it never
  executes anything.
- `ROLLED_BACK` — the mutation was refused, so the key is free again. A run of
  these means clients are burning keys on requests that never commit.

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

The full **production alerting contract** — which of these signals Ops must
alert on, from which source, at what threshold — is
[`docs/dev/production.md`](../dev/production.md#observability--the-alerting-contract).
It adds nothing to the registry: every production signal is one of the meters
above or a standard Spring Boot binder, whose presence on the scrape endpoint
the rehearsal asserts.

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
