#!/bin/bash
# WS-B2: one psql session (inside ws-b2-pg, local socket) sampling every 2 s: backends of the bench DB by
# state/wait event + first 32 chars of the statement of active backends, and advisory-lock waiters/holders (pg_locks).
# Diagnostic only; one extra connection.
docker exec -i ws-b2-pg psql -U bench -d postgres -Atq <<'SQL'
select to_char(clock_timestamp() at time zone 'UTC','HH24:MI:SS') || ' adv_wait=' ||
  (select count(*) from pg_locks where locktype='advisory' and not granted) || ' adv_held=' ||
  (select count(*) from pg_locks where locktype='advisory' and granted) || ' ' ||
  coalesce((select string_agg(k||'='||n, ' ' order by n desc) from (select coalesce(state,'-')||'/'||coalesce(wait_event_type,'cpu')||':'||coalesce(wait_event,'-')
       || case when state='active' then '/' || regexp_replace(left(ltrim(query),32),'\s+','_','g') else '' end k, count(*) n
     from pg_stat_activity where datname like 'testinbox_bench%' and pid<>pg_backend_pid() group by 1) x),'idle-db')
\watch 2
SQL
