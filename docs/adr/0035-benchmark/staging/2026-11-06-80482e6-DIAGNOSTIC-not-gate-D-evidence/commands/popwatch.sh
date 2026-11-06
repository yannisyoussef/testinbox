#!/bin/bash
# WS-B3 DIAGNOSTIC: on each scenario 'done' line run statdump.sh <scenario> (extra, per scenario); the last scenario of each
# population (reference-ws<N>-c100-r62) is the requested "after each population" dump. Stops at rc= in gate.log.
B=/opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/diag
cd $B
tail -n +1 -F logs/gate.log | while read -r line; do
  case "$line" in
    *" scenario "*" done: "*) sc=$(echo "$line" | sed -E 's/.* scenario ([^ ]+) done:.*/\1/'); ./statdump.sh "after-$sc"
      case "$sc" in reference-ws*-c100-r62) cp dumps/after-$sc.txt dumps/population-end-${sc#reference-}.txt;; esac;;
    rc=*) ./statdump.sh "after-run-end"; exit 0;;
  esac
done
