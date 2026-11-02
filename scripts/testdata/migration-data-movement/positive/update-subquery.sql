-- expect-exit: 1
-- expect: \[data-movement\] UPDATE statement \(data movement\) reads provider_login_state
UPDATE ONLY inbox SET label = (SELECT state FROM provider_login_state LIMIT 1);
