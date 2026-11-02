-- expect-exit: 1
-- expect: \[data-movement\] WITH statement \(data movement\) reads human_session
WITH s AS (SELECT token_hash FROM human_session)
INSERT INTO inbox_note (note) SELECT token_hash FROM s;
