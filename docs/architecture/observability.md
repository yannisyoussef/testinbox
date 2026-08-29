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

## Metrics (minimum set)

- `inbox_created_total`, `inbox_expired_total`, `inbox_deleted_total`
- `message_received_total` (tagged by `parseStatus`)
- `message_parse_duration_seconds`
- `wait_request_duration_seconds` (tagged by outcome: matched/timeout/cancelled)
- `wait_requests_active` (gauge, to catch connection-leak regressions)
- `smtp_accept_total` / `smtp_reject_total` / `smtp_quarantine_discard_total`
- `object_storage_operation_duration_seconds`

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
