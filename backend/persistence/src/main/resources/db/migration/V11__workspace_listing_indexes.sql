-- Workspace-scoped listings (console API reads): keyset paging on
-- (created_at, id) for inboxes and on (received_at, id) for messages, both
-- within one workspace. Expand-only: two indexes, nothing dropped, nothing
-- renamed, nothing narrowed (docs/dev/rollback.md).
--
-- `ix_message_workspace (workspace_id)` stays: it serves the quota and
-- accounting reads that need no order.
CREATE INDEX ix_inbox_workspace_created ON inbox (workspace_id, created_at, id);
CREATE INDEX ix_message_workspace_order ON message (workspace_id, received_at, id);
