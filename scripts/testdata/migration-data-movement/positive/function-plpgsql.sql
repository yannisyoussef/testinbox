-- expect-exit: 1
-- expect: V23__fixture\.sql:8: \[routine-body\] FUNCTION body or signature reads project
CREATE FUNCTION copy_projects() RETURNS void
    LANGUAGE plpgsql AS
$fn$
BEGIN
    INSERT INTO inbox_note (note)
    SELECT name FROM project;
END
$fn$;
