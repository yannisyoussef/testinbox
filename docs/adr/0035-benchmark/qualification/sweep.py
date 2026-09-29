"""Final strict sweep, run at least C_max (15.5 min) after the last trial. Any error aborts (never 'absent')."""
import json
from harness import state
import os
rows = [json.loads(l) for l in open(os.environ.get("RESULTS", "/q/results.jsonl")) if l.strip()]
late = 0
for r in rows:
    now = state(r["key"])                              # raises on anything but 200/404
    appeared_after_window = now and r["visible_after_thaw_s"] is None
    late += appeared_after_window
    print(json.dumps({"key": r["key"], "scenario": r["scenario"], "exists_at_sweep": now,
                      "visible_during_poll": r["visible_after_thaw_s"] is not None,
                      "appeared_after_poll_window": appeared_after_window}), flush=True)
print(json.dumps({"summary": True, "keys": len(rows), "appeared_after_poll_window": late}))
