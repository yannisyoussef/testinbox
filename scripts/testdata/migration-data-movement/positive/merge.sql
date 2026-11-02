-- expect-exit: 1
-- expect: \[data-movement\] MERGE statement \(data movement\) reads beta_waitlist
MERGE INTO inbox_note n
USING beta_waitlist w ON n.note = w.email
WHEN NOT MATCHED THEN INSERT (note) VALUES (w.email);
