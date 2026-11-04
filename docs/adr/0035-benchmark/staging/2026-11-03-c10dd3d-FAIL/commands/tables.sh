#!/bin/bash
# WS-B2: markdown tables from the harness evidence (read-only).
E=$1
jq -r '"| Scenario | mode | ws | c | offered | achieved | ratio | sched | completed | T1 p50/p99 ms | T1tx p99 ms | T2 p99 ms | lockWait p99 ms | retention p99 ms (samples) | starved | lockTO | deadl | slotMiss | other |",
 "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
 (.scenarios[] | "| \(.name) | \(.mode) | \(.workspaceCount) | \(.concurrency) | \(.offeredRate) | \(.achievedRate|.*10|round/10) | \((.achievedRate/.offeredRate)*1000|round/1000) | \(.scheduledEvents) | \(.completedEvents) | \(.percentiles.t1.p50Ms|round)/\(.percentiles.t1.p99Ms|round) | \(.percentiles.t1Transaction.p99Ms*10|round/10) | \(.percentiles.t2.p99Ms*10|round/10) | \(.percentiles.lockWait.p99Ms // 0|round) | \(.percentiles.retention.p99Ms // null|if .==null then "-" else (.*10|round/10) end) (\(.percentiles.retention.samples // 0)) | \(.retentionStarvedTicks) | \(.errors.lockTimeouts) | \(.errors.deadlocks) | \(.errors.deadlineMissesFromSlotQueueing) | \(.errors.other) |")' "$E"
echo
jq -r '"| Criterion | pass | observed | threshold | detail |", "|---|---|---|---|---|", (.criteria | to_entries[] | "| \(.key) | \(.value.pass) | \(.value.observed) | \(.value.threshold) | \(.value.detail) |")' "$E"
echo; jq -r '"verdict: \(.verdict); adrEvidence: \(.adrEvidence); adrEvidenceReasons: \(.adrEvidenceReasons); incompleteReasons: \(.incompleteReasons)"' "$E"
