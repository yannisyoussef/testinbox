-- expect-exit: 1
-- Inside a routine body a string may be dynamic SQL, so strings are scanned there.
-- expect: \[routine-body\] FUNCTION body or signature reads human_account
-- expect: \[dynamic-sql\] dynamic SQL \(EXECUTE\) in a function body
CREATE FUNCTION copy_contacts() RETURNS void LANGUAGE plpgsql AS
$body$
BEGIN
    EXECUTE format('INSERT INTO inbox_note (note) SELECT contact_email FROM %I', 'human_account');
END
$body$;
