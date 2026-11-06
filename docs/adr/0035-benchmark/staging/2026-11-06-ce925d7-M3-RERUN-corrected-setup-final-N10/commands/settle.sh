#!/bin/bash
# WS-B3 M3 rerun: settle check: load1 < 4, apt/logrotate/dpkg-db-backup inactive and no test/build process, for 13 x 10 s.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/m3-rerun; L=$B/logs/settle.log; ok=0; n=0
while [ $ok -lt 13 ] && [ $n -lt 120 ]; do n=$((n+1)); l=$(cut -d' ' -f1 /proc/loadavg)
  a=$(systemctl is-active apt-daily.service apt-daily-upgrade.service logrotate.service dpkg-db-backup.service | paste -sd,)
  t=$(ps -eo args | /usr/bin/grep -E 'selftest|GradleWorkerMain|GradleDaemon|gradlew|pytest|test_[^ ]*\.sh|bats' | /usr/bin/grep -v -e 'grep -E' -e shell-snapshots -e settle.sh | wc -l)
  echo "$(date -u +%T) load1 $l svc $a testproc $t" >> $L
  if [ "$t" = 0 ] && awk -v l=$l 'BEGIN{exit !(l<4)}' && [ "$a" = "inactive,inactive,inactive,inactive" ]; then ok=$((ok+1)); else ok=0; fi
  [ $ok -lt 13 ] && sleep 10
done; echo "ok=$ok n=$n"
