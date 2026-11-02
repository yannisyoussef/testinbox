-- expect-exit: 1
-- expect: \[data-movement\] CREATE MATERIALIZED VIEW reads idempotency_record
CREATE MATERIALIZED VIEW IF NOT EXISTS request_digest AS SELECT key, response_body FROM idempotency_record;
