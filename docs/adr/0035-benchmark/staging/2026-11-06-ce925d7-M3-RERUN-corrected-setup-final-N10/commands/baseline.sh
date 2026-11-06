#!/bin/bash
# WS-B3 M3 rerun: baselines immediately before the start (coordinator rule 4 + review §3a). Usage: baseline.sh <tag>
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/m3-rerun; T=$1
{
echo "# baseline $T at $(date -u +%FT%TZ)"
echo "## uptime"; uptime
echo "## ps -eo pid,user,pcpu,etime,args --sort=-pcpu | head -60"; ps -eo pid,user,pcpu,etime,args --sort=-pcpu | head -60 | cut -c1-250
echo "## ps -eo pid,ppid,etimes,pcpu,args --sort=-pcpu (top 40)"; ps -eo pid,ppid,etimes,pcpu,args --sort=-pcpu | head -40 | cut -c1-250
echo "## docker ps"; docker ps --format '{{.Names}} {{.Image}} {{.Status}}'
echo "## docker ps count"; docker ps -q | wc -l
echo "## systemctl list-timers --all"; systemctl list-timers --all --no-pager
echo "## pgrep test/build/selftest (must be empty apart from the estate baseline)"
pgrep -a -f 'selftest|compose|gradle|GradleWorkerMain|pytest|test_.*\.sh|bats|lab' | /usr/bin/grep -v -e 'baseline.sh' -e 'shell-snapshots' || echo "(none)"
} > $B/logs/baseline-$T.txt 2>&1
ps -ef --forest > $B/logs/ps-forest-$T.txt 2>&1
