-- expect-exit: 1
-- expect: V23__fixture\.sql:5: \[data-movement\] INSERT statement \(data movement\) reads human_account
INSERT INTO inbox_note (workspace_id, note)
SELECT a.workspace_id, h.contact_email
  FROM human_account h JOIN beta_admission a ON a.account_id = h.id;
-- expect: \[data-movement\] INSERT statement \(data movement\) reads beta_admission
