-- expect-exit: 1
-- expect: \[data-movement\] CREATE RULE reads human_identity
CREATE RULE mirror_identity AS ON INSERT TO human_identity
    DO ALSO INSERT INTO inbox_note (note) VALUES (NEW.subject);
