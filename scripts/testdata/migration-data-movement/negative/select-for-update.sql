-- expect-exit: 0
-- Top-level reads with row locks are not data movement.
SELECT id FROM human_account WHERE id = '00000000-0000-0000-0000-000000000000' FOR UPDATE;
SELECT id FROM beta_cohort FOR NO KEY UPDATE;
WITH c AS (SELECT count(*) AS n FROM api_key) SELECT n FROM c;
