#!/bin/bash
# WS-B3 DIAGNOSTIC: dump pg_stat_statements (top 40 by total_exec_time), pg_stat_user_tables (bench DB) and pg_stat_bgwriter.
# Usage: statdump.sh <label>. Note: the harness resets pg_stat_statements before each scenario's measured phase
# (ScenarioRunner.kt:154), so a dump shows the statements since the last reset.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/diag
F=$B/dumps/$1.txt
{
echo "# $1 at $(date -u +%FT%T.%3NZ)"
echo "## pg_stat_statements ORDER BY total_exec_time DESC LIMIT 40 (all databases)"
docker exec ws-b3-pg psql -U bench -d postgres -X -P pager=off -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" -c "SELECT * FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 40" -x
echo "## pg_stat_user_tables (testinbox_bench_diag)"
docker exec ws-b3-pg psql -U bench -d testinbox_bench_diag -X -P pager=off -c "SELECT * FROM pg_stat_user_tables ORDER BY relname"
echo "## pg_stat_bgwriter"
docker exec ws-b3-pg psql -U bench -d postgres -X -P pager=off -x -c "SELECT * FROM pg_stat_bgwriter"
} > $F 2>&1
