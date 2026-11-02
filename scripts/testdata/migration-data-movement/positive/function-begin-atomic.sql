-- expect-exit: 1
-- expect: \[routine-body\] PROCEDURE body or signature reads beta_cohort
CREATE PROCEDURE copy_cohort() LANGUAGE sql
BEGIN ATOMIC
    INSERT INTO inbox_note (note) SELECT id FROM beta_cohort;
    UPDATE inbox_note SET note = upper(note);
END;
