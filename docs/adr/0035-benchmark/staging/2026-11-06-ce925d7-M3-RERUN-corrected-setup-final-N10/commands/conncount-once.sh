#!/bin/bash
# WS-B3 run 2: connection headroom (probes double the harness pool). One psql session inside ws-b3-pg every 5 s: all backends,
# client backends of the bench DB, and max_connections. Diagnostic only; one extra connection.
docker exec -i ws-b3-pg psql -U bench -d postgres -Atq <<'SQL'
select to_char(clock_timestamp() at time zone 'UTC','HH24:MI:SS') || ' all=' || (select count(*) from pg_stat_activity) ||
  ' client=' || (select count(*) from pg_stat_activity where backend_type='client backend') ||
  ' bench=' || (select count(*) from pg_stat_activity where datname like 'testinbox_bench%') ||
  ' max_connections=' || current_setting('max_connections') || ' reserved=' || current_setting('superuser_reserved_connections')
\watch 5
SQL
