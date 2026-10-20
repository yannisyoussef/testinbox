-- Workspace-scoped listings (console API reads): keyset paging on
-- (created_at, id) for inboxes and on (received_at, id) for messages, both
-- within one workspace. Expand-only: two indexes, nothing dropped, nothing
-- renamed, nothing narrowed (docs/dev/rollback.md).
--
-- `ix_message_workspace (workspace_id)` is now a strict prefix of the new
-- message index and redundant; it stays because dropping it would be a
-- rollback-unsafe migration, which is a separate decision.
CREATE INDEX ix_inbox_workspace_created ON inbox (workspace_id, created_at, id);
CREATE INDEX ix_message_workspace_order ON message (workspace_id, received_at, id);
