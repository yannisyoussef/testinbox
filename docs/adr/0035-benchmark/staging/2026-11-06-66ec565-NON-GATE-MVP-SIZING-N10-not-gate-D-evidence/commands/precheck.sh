#!/bin/bash
# WS-B3: quiet-host pre-check before the gate run (read-only).
echo "== $(date -u +%FT%TZ) $(hostname)"; uptime
echo "== top CPU (2nd iteration)"; top -bn2 -d2 -w 200 | awk '/^top -/{n++} n==2' | sed -n '1,5p;7,20p'
echo "== apt/dpkg/unattended-upgrade processes"; pgrep -a -f 'apt-get|apt.systemd|unattended-upgrade|dpkg ' | /usr/bin/grep -v -e unattended-upgrade-shutdown -e shell-snapshots || echo none
echo "== apt timers"; systemctl list-timers --all --no-pager 'apt-daily*' 2>&1 | head -5; systemctl is-active apt-daily.service apt-daily-upgrade.service unattended-upgrades.service 2>&1
echo "== gradle / test JVMs"; pgrep -a -f 'GradleDaemon|GradleWrapperMain|gradlew|GradleWorkerMain|surefire|junit' | /usr/bin/grep -v shell-snapshots || echo none
echo "== java processes"; pgrep -a java | cut -c1-200 || echo none
echo "== containers running (name, image, status)"; docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}' | sort
echo "== lab/bench containers"; docker ps --format '{{.Names}}' | /usr/bin/grep -i -e lab -e bench -e 'ws-' || echo none
echo "== LAB LOCK"; ls -la /opt/infinity/workspace/ti-delivery-002/lab/LOCK 2>&1
echo "== memory"; free -m | head -2
