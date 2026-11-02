-- expect-exit: 1
-- expect: \[routine-body\] FUNCTION body or signature reads human_account
CREATE FUNCTION contact_emails() RETURNS SETOF text LANGUAGE sql STABLE
    AS 'SELECT contact_email FROM human_account';
