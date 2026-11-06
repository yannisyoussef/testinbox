#!/usr/bin/env python3
# WS-B3: the t1Probe check the app team asked for: every scenario's t1Probe.readsFootprint must be true, else the run is void.
# usage: readsfootprint_check.py <evidence.json>
import json, sys
d=json.load(open(sys.argv[1]))
rows=[(s.get('name'),'scenarios',s['t1Probe']) for s in d['scenarios']]+[(s.get('name'),'variant.scenarios',s['t1Probe']) for s in d['variant']['scenarios']]
print("t1Probe check over evidence.json (gitSha %s): every scenario's t1Probe.readsFootprint must be true" % d['gitSha'])
print("%-34s %-18s %-14s %-12s %-20s %s" % ('scenario','block','readsFootprint','footprintRead','footprintUnavailable','storeClass'))
for n,b,t in rows: print("%-34s %-18s %-14s %-12s %-20s %s" % (n,b,t.get('readsFootprint'),t.get('footprintRead'),t.get('footprintUnavailable'),t.get('storeClass')))
bad=[n for n,b,t in rows if t.get('readsFootprint') is not True]
print("\nRESULT: %d scenario(s) checked (%d gate + %d variant); readsFootprint true in %d; false/missing in %d%s" % (len(rows),len(d['scenarios']),len(d['variant']['scenarios']),len(rows)-len(bad),len(bad),(': '+', '.join(bad)) if bad else '. The run is NOT void on this check.'))
