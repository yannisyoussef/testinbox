-- expect-exit: 1
-- expect: V23__fixture\.sql:5: \[data-movement\] CREATE VIEW reads provider_login_state
CREATE OR REPLACE VIEW login_debug AS
SELECT state, created_at
  FROM provider_login_state;
