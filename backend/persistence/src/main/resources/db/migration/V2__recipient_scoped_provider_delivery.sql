-- ADR-026 (amends ADR-019): provider-event identity is recipient-scoped.
-- A single provider event (e.g. one SES received-mail notification) may carry
-- several envelope recipients, each of which is its own observable delivery.
-- Keying dedup on (provider, provider_message_id) alone would let the first
-- recipient's row suppress every other recipient of the same event.
-- Reprocessing the same event must still be idempotent, so the key gains the
-- normalized envelope recipient.
DROP INDEX ux_message_provider_event;

CREATE UNIQUE INDEX ux_message_provider_delivery
    ON message (provider, provider_message_id, envelope_to)
    WHERE provider_message_id IS NOT NULL;
