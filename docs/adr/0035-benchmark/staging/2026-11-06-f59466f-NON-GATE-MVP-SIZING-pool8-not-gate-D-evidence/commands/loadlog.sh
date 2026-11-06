#!/bin/bash
# WS-B3: as bench2/loadlog.sh. Every 30 s: loadavg, host CPU% busy over the last ~2 s (/proc/stat delta),
# the top-3 instantaneous CPU users (top second iteration), running apt/dpkg/gradle counts. Stops when loadlog.stop exists.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/sizing-p8
while [ ! -e $B/loadlog.stop ]; do
  read -r _ a1 a2 a3 a4 a5 a6 a7 _ < /proc/stat; t1=$((a1+a2+a3+a4+a5+a6+a7)); i1=$((a4+a5))
  tops=$(top -bn2 -d2 -w 200 | awk '/^top -/{n++} n==2 && /^ *[0-9]+ /{print $9" "$12; c++} c==4{exit}' | tail -n +1 | head -4 | tr '\n' ';')
  read -r _ b1 b2 b3 b4 b5 b6 b7 _ < /proc/stat; t2=$((b1+b2+b3+b4+b5+b6+b7)); i2=$((b4+b5))
  busy=$(( (100*((t2-t1)-(i2-i1))) / ((t2-t1)>0 ? (t2-t1) : 1) ))
  apt=$(pgrep -c -f 'apt-get|unattended-upgrade |dpkg ' ); gr=$(pgrep -c -f 'GradleDaemon|GradleWrapperMain')
  echo "$(date -u +%FT%TZ) load $(cut -d' ' -f1-3 /proc/loadavg) cpu_busy% $busy apt $apt gradle $gr | top: $tops" >> $B/logs/load.log
  sleep 26
done
