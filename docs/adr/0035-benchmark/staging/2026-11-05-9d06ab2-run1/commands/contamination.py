#!/usr/bin/env python3
"""WS-B3 host-contamination mapping (coordinator request 2026-11-05). Reads only the run's own logs; does not touch evidence.
Per 30-s sample: host busy cores (load.log cpu_busy% x 8, the same 2-s /proc/stat snapshot as c10dd3d), benchmark cores (docker stats
ws-*-pg + ws-*-gate, nearest sample within 30 s), non-benchmark cores = host - benchmark, load1, and steal (mpstat %steal, max over +-15 s).
Same metric computed on the c10dd3d run (bench2 logs) as the quiet reference.
A sample is FLAGGED when (A) non-benchmark cores > the c10dd3d p95 of the same metric in this sample AND its neighbour (sustained
>= ~1 min; single-sample spikes of the estate baseline, e.g. GitLab, are not flagged), or (B) load1 > max(1.25 x, +3 over) the c10dd3d
maximum for the same scenario name (CHOSEN/REFERENCE only; PARTITIONED has no c10dd3d counterpart). A scenario is NOT REPRESENTATIVE when its [start, done]
interval overlaps the known external window or contains a flagged sample.
usage: contamination.py <bench3 dir> <bench2 dir> [--md]"""
import re, sys, datetime as dt, statistics as st

KNOWN = [("2026-11-05T22:43:40", "2026-11-05T23:18:23", "Ops selftest mutant shards (two parallel shards), confirmed by the coordinator")]
D = dt.datetime

def p(s): return D.strptime(s, "%Y-%m-%dT%H:%M:%S")

def load(path):
    out = []
    for l in open(path):
        m = re.match(r"(\S+)Z load (\S+) \S+ \S+ cpu_busy% (\d+) .*\| top: (.*)", l)
        if m: out.append((p(m[1]), float(m[2]), int(m[3]), m[4].strip()))
    return out

def dstats(path):
    out = []
    for l in open(path):
        m = re.match(r"(\S+)Z (.*)", l)
        if not m: continue
        cpu = sum(float(x) for x in re.findall(r"ws-b\d-(?:pg|gate) cpu=([\d.]+)%", m[2]))
        out.append((p(m[1]), cpu / 100))
    return out

def gate(path, day):
    """scenario intervals from the console log (HH:MM:SS.mmm; date rolls over at midnight)"""
    starts, res, d, last = {}, [], D.strptime(day, "%Y-%m-%d"), None
    for l in open(path):
        m = re.match(r"(\d\d):(\d\d):(\d\d)\.\d+ INFO .*scenario (\S+) (starting|done:)(.*)", l)
        if not m: continue
        t = d.replace(hour=int(m[1]), minute=int(m[2]), second=int(m[3]))
        if last and t < last: d += dt.timedelta(days=1); t += dt.timedelta(days=1)
        last = t
        if m[5] == "starting": starts[m[4]] = t
        else: res.append((m[4], starts.get(m[4]), t, m[6].strip()))
    return res

def mpstat(path, day):
    out, d, last = [], D.strptime(day, "%Y-%m-%d"), None
    for l in open(path):
        f = l.split()
        if len(f) == 12 and f[1] == "all" and re.match(r"\d\d:\d\d:\d\d$", f[0]):
            h, mi, s = map(int, f[0].split(":")); t = d.replace(hour=h, minute=mi, second=s)
            if last and t < last: d += dt.timedelta(days=1); t += dt.timedelta(days=1)
            last = t; out.append((t, float(f[8]), 100 - float(f[11])))
    return out

def nearest(series, t, win=30):
    best = min(series, key=lambda x: abs((x[0] - t).total_seconds()), default=None)
    return best if best and abs((best[0] - t).total_seconds()) <= win else None

def samples(L, DS):
    rows = []
    for t, l1, busy, top in L:
        n = nearest(DS, t)
        bench = n[1] if n else None
        rows.append(dict(t=t, load1=l1, busy=busy, host=busy * 8 / 100, bench=bench, nonbench=(busy * 8 / 100 - bench) if bench is not None else None, top=top))
    return rows

def scen_of(scen, t):
    for name, s, e, _ in scen:
        if s and s <= t <= e: return name
    return None

def pct(xs, q):
    xs = sorted(xs); return xs[min(len(xs) - 1, int(round(q * (len(xs) - 1))))]

def main():
    b3, b2 = sys.argv[1], sys.argv[2]
    R2 = samples(load(b2 + "/logs/load.log"), dstats(b2 + "/logs/dstats.log"))
    S2 = gate(b2 + "/logs/gate.log", "2026-11-03")
    R3 = samples(load(b3 + "/logs/load.log"), dstats(b3 + "/logs/dstats.log"))
    S3 = gate(b3 + "/logs/gate.log", "2026-11-05")
    MP = mpstat(b3 + "/logs/mpstat.log", "2026-11-05")
    run2 = [r for r in R2 if S2[0][1] <= r["t"] <= S2[-1][2] and r["nonbench"] is not None]
    nb95 = pct([r["nonbench"] for r in run2], 0.95)
    l2 = [r["load1"] for r in run2]
    ref_max = {}
    for r in run2:
        sc = scen_of(S2, r["t"])
        if sc: ref_max[sc] = max(ref_max.get(sc, 0), r["load1"])
    run3 = [r for r in R3 if S3 and S3[0][1] <= r["t"] <= S3[-1][2]]
    for r in run3:
        sc = scen_of(S3, r["t"]); r["scen"] = sc
        ms = [m for m in MP if abs((m[0] - r["t"]).total_seconds()) <= 15]
        r["steal"] = max((m[1] for m in ms), default=None)
        r["hi"] = r["nonbench"] is not None and r["nonbench"] > nb95
    for i, r in enumerate(run3):
        sc = r["scen"]; why = []
        nb = [run3[j]["hi"] for j in (i - 1, i + 1) if 0 <= j < len(run3)]
        if r["hi"] and any(nb): why.append("A")
        if sc and sc in ref_max and r["load1"] > max(1.25 * ref_max[sc], ref_max[sc] + 3): why.append("B")
        r["why"] = why
    l3 = [r["load1"] for r in run3]
    print("# Host contamination map, gate D run 1 (9d06ab2), generated by tools/contamination.py from the run's own logs")
    print()
    print("Known external load (coordinator): " + "; ".join(f"{a}Z to {b}Z: {w}" for a, b, w in KNOWN))
    print()
    print("Method: " + " ".join(__doc__.split("usage:")[0].split()))
    print()
    print("## Reference profiles (30-s samples inside the gate run)")
    print(f"- c10dd3d: n={len(run2)}, load1 mean {st.mean(l2):.1f}, median {st.median(l2):.1f}, p90 {pct(l2,.9):.1f}, max {max(l2):.1f}; "
          f"non-benchmark cores median {st.median([r['nonbench'] for r in run2]):.2f}, p95 {nb95:.2f}")
    nb3 = [r["nonbench"] for r in run3 if r["nonbench"] is not None]
    print(f"- 9d06ab2 run 1: n={len(run3)}, load1 mean {st.mean(l3):.1f}, median {st.median(l3):.1f}, p90 {pct(l3,.9):.1f}, max {max(l3):.1f}; "
          f"non-benchmark cores median {st.median(nb3):.2f}, p95 {pct(nb3,.95):.2f}")
    stl = [m[1] for m in MP if S3[0][1] <= m[0] <= S3[-1][2]]
    print(f"- steal (mpstat 5 s, {len(stl)} intervals in the run): max {max(stl):.2f} %, mean {st.mean(stl):.3f} %, intervals > 0: {sum(1 for x in stl if x > 0)}")
    vs = []
    for l in open(b3 + "/logs/vmstat.log"):
        f = l.split()
        if len(f) == 20 and f[0].isdigit():
            t = p(f[18] + "T" + f[19])
            if S3[0][1] <= t <= S3[-1][2]: vs.append(int(f[16]))
    if vs: print(f"- steal (vmstat 5 s st column, {len(vs)} intervals in the run): max {max(vs)} %, intervals > 0: {sum(1 for x in vs if x > 0)}")
    print()
    print("## Flagged 30-s samples (merged into windows; a gap of one sample or less joins)")
    flagged = [r for r in run3 if r["why"]]
    wins = []
    for r in flagged:
        if wins and (r["t"] - wins[-1][1]).total_seconds() <= 60: wins[-1][1] = r["t"]; wins[-1][2].append(r)
        else: wins.append([r["t"], r["t"], [r]])
    print("| Window (UTC, sample times) | Samples | Flags | max load1 | max non-bench cores | max steal % | Scenarios running | Top list at the max non-bench sample |")
    print("|---|---|---|---|---|---|---|---|")
    for s, e, rs in wins:
        sc = sorted({r["scen"] or "between scenarios" for r in rs})
        mx = lambda k: max((r[k] for r in rs if r[k] is not None), default=float('nan'))
        top = max(rs, key=lambda r: r["nonbench"] or 0)["top"]
        fl = "+".join(sorted({w for r in rs for w in r["why"]}))
        print(f"| {s:%H:%M:%S}–{e:%H:%M:%S} | {len(rs)} | {fl} | {mx('load1'):.1f} | {mx('nonbench'):.1f} | {mx('steal'):.2f} | {', '.join(sc)} | {top} |")
    print()
    print("## Every window with load1 > 15.1 (the c10dd3d p90), with attribution")
    print("Attribution rule: inside the known external window -> EXTERNAL (known); else FLAGGED by A/B -> NOT EXPLAINED by the benchmark;")
    print("else a c25/c50/c100 scenario was running in the sample or within the 60 s before it (load1 is a 1-min average) -> consistent with the")
    print("benchmark's own c25+ load (c10dd3d: up to 19.3 in a c25 scenario, 33-40 in its c100 scenarios; PARTITIONED c100 reached 58.7 at 00:52-00:55Z, after the")
    print("host was kept quiet); otherwise NOT EXPLAINED.")
    print()
    print("| Window (UTC, sample times) | Samples | max load1 | max non-bench cores | Scenarios (running / within 60 s before) | Attribution |")
    print("|---|---|---|---|---|---|")
    ks0 = [(p(a), p(b)) for a, b, _ in KNOWN]
    hi = [r for r in run3 if r["load1"] > 15.1]
    lw = []
    for r in hi:
        if lw and (r["t"] - lw[-1][1]).total_seconds() <= 60: lw[-1][1] = r["t"]; lw[-1][2].append(r)
        else: lw.append([r["t"], r["t"], [r]])
    for a, b, rs in lw:
        near = set()
        for r in rs:
            for name, s0, e0, _ in S3:
                if s0 and s0 - dt.timedelta(seconds=0) <= r["t"] + dt.timedelta(seconds=0) and e0 >= r["t"] - dt.timedelta(seconds=60): near.add(name)
        known = any(a <= kb and b >= ka for ka, kb in ks0)
        fl = any(r["why"] for r in rs)
        own = any(re.search(r"-c(25|50|100)-", n) for n in near)
        att = "EXTERNAL (known window 22:43:40-23:18:23Z)" if known else ("NOT EXPLAINED (flag " + "+".join(sorted({w for r in rs for w in r["why"]})) + ")" if fl else
              ("consistent with own c25+ load" if own else "NOT EXPLAINED (no c25+ scenario running)"))
        mx = max((r["nonbench"] for r in rs if r["nonbench"] is not None), default=float("nan"))
        print(f"| {a:%H:%M:%S}–{b:%H:%M:%S} | {len(rs)} | {max(r['load1'] for r in rs):.1f} | {mx:.1f} | {', '.join(sorted(near))} | {att} |")
    print()
    print("## Scenario map")
    print("| Scenario | Start (UTC) | Done (UTC) | Overlaps known external window | Flagged samples | max load1 | c10dd3d max load1 (same scenario) | max non-bench cores | max steal % | Latency figures |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    ks = [(p(a), p(b)) for a, b, _ in KNOWN]
    nr = 0
    for name, s, e, _ in S3:
        rs = [r for r in run3 if s <= r["t"] <= e]
        ov = any(s <= kb and e >= ka for ka, kb in ks)
        fl = sum(1 for r in rs if r["why"])
        bad = ov or fl > 0; nr += bad
        mx = lambda k: max((r[k] for r in rs if r.get(k) is not None), default=float('nan'))
        print(f"| {name} | {s:%m-%d %H:%M:%S} | {e:%m-%d %H:%M:%S} | {'YES' if ov else 'no'} | {fl}/{len(rs)} | {mx('load1'):.1f} | "
              f"{ref_max.get(name, float('nan')):.1f} | {mx('nonbench'):.1f} | {mx('steal'):.2f} | {'NOT REPRESENTATIVE' if bad else 'clean'} |")
    print(f"\n{nr} of {len(S3)} scenarios marked NOT REPRESENTATIVE.")

main()
