import glob, os, re, sys
base = sys.argv[1]
names = {0: "T1", 1: "T2", 2: "ret"}
def q(xs, p):
    xs = sorted(xs); return xs[min(len(xs)-1, int(p*len(xs)))] if xs else float('nan')
print("mode rate  achieved/s  T1 p50/p99   T2 p99   retention p99   failed  aborted")
for rate in (520, 1040):
    for mode in "cab":
        d = f"{base}/logs3/{mode}-{rate}"
        if not os.path.isdir(d): continue
        lat = {0: [], 1: [], 2: []}
        for f in glob.glob(d + "/tx*"):
            for line in open(f):
                p = line.split()
                if len(p) < 7 or not p[2].isdigit(): continue
                lat[int(p[3])].append((int(p[2]) + int(p[6])) / 1000)  # latency incl. schedule lag, ms
        s = open(d + "/summary.txt").read()
        m = re.search(r"SQL script 1:.*?tps = ([\d.]+)", s, re.S)
        fl = re.search(r"number of failed transactions: (\d+)", s)
        ab = "yes" if "aborted" in s else "no"
        print(f"{mode:4} {rate:5} {float(m.group(1)) if m else 0:10.0f}  {q(lat[0],.5):5.1f}/{q(lat[0],.99):5.1f}  {q(lat[1],.99):7.1f}  {q(lat[2],.99):12.1f}   {fl.group(1) if fl else '?':>6}  {ab}")
