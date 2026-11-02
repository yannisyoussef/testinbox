-- expect-exit: 1
-- expect: V23__fixture\.sql:5: \[data-movement\] UPDATE statement \(data movement\) reads human_account
UPDATE inbox
   SET label = h.contact_email
  FROM human_account h
 WHERE h.workspace_id = inbox.workspace_id;
