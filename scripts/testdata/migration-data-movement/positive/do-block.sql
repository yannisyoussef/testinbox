-- expect-exit: 1
-- expect: V23__fixture\.sql:5: \[routine-body\] DO block body reads api_key
DO $$
BEGIN
    INSERT INTO inbox_note (note) SELECT key_hash FROM api_key;
END
$$;
