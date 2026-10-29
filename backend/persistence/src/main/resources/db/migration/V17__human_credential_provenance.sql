-- ADR-036. Expand-only: distinguish human authority from machine credentials.
-- No plaintext key or session credential is stored in these metadata columns.
ALTER TABLE api_key ADD COLUMN created_by_human_account_id uuid REFERENCES human_account(id) ON DELETE SET NULL;
ALTER TABLE api_key ADD CONSTRAINT ck_api_key_one_creator
    CHECK (created_by_api_key_id IS NULL OR created_by_human_account_id IS NULL);
-- Transient claim state remains excluded from backups.
ALTER TABLE idempotency_record ADD COLUMN created_by_human_account_id uuid REFERENCES human_account(id) ON DELETE SET NULL;
ALTER TABLE idempotency_record ADD CONSTRAINT ck_idempotency_one_creator
    CHECK (created_by_api_key_id IS NULL OR created_by_human_account_id IS NULL);
