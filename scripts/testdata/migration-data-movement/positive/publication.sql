-- expect-exit: 1
-- expect: \[data-movement\] CREATE PUBLICATION \(logical replication\) reads human_session
-- expect: \[data-movement\] ALTER PUBLICATION \(logical replication\) reads api_key
CREATE PUBLICATION export_sessions FOR TABLE human_session;
ALTER PUBLICATION export_sessions ADD TABLE public.api_key;
