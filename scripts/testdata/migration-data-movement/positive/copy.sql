-- expect-exit: 1
-- expect: V23__fixture\.sql:4: \[data-movement\] COPY reads api_key
-- expect: V23__fixture\.sql:5: \[data-movement\] COPY reads email_login_challenge
COPY (SELECT key_hash FROM api_key) TO STDOUT;
COPY email_login_challenge TO '/var/tmp/challenges.csv';
