-- expect-exit: 1
-- The table name is assembled at run time: only the EXECUTE itself is visible.
-- expect: V23__fixture\.sql:6: \[dynamic-sql\] dynamic SQL \(EXECUTE\) in a DO block
DO $$
BEGIN
    EXECUTE 'INSERT INTO inbox_note (note) SELECT contact_email FROM ' || 'human_' || 'account';
END
$$;
