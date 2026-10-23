import glob, os, re, sys
base = sys.argv[1]
print(f"{'mode':5}{'clients':>8}{'events/s':>10}{'ingest p50':>12}{'p99':>9}{'max':>9}{'ret p99':>9}{'failed':>8}{'retried':>8}")
for mode in "cab":
    for c in (1, 16, 64, 128):
        d = f"{base}/logs/{mode}-{c}"
        if not os.path.isdir(d): continue
        lat = {0: [], 1: []}
        for f in glob.glob(d + "/tx*"):
            for line in open(f):
                p = line.split()
                if len(p) < 6 or p[2] in ("failed", "skipped"): continue
                lat[int(p[3])].append(int(p[2]) / 1000)
        s = open(d + "/summary.txt").read()
        m = re.search(r"SQL script 1.*?tps = ([\d.]+)", s, re.S)
        fl = re.search(r"number of failed transactions: (\d+)", s)
        rt = re.search(r"number of transactions retried: (\d+)", s)
        def q(xs, p):
            xs = sorted(xs); return xs[min(len(xs)-1, int(p*len(xs)))] if xs else float('nan')
        print(f"{mode:5}{c:>8}{float(m.group(1)) if m else 0:>10.0f}{q(lat[0],.5):>11.1f}m{q(lat[0],.99):>8.1f}m{max(lat[0] or [0]):>8.0f}m{q(lat[1],.99):>8.1f}m{fl.group(1) if fl else '?':>8}{rt.group(1) if rt else '?':>8}")
