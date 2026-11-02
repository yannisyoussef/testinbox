-- expect-exit: 1
-- expect: \[data-movement\] SELECT statement \(data movement\) reads human_session
SELECT token_hash INTO UNLOGGED session_copy FROM "human_session";
