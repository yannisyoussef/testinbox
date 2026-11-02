-- expect-exit: 1
-- expect: \[data-movement\] INSERT statement \(data movement\) reads human_identity
-- Uppercase, schema-qualified and quoted spellings are the same table.
INSERT INTO inbox_note (note) VALUES ((SELECT subject FROM PUBLIC."HUMAN_IDENTITY" LIMIT 1));
