#!/usr/bin/env python3
"""TI-STORAGE-006C: bounded quota usage-lag measurement on an ISOLATED MinIO lab (never the live staging MinIO).

The lab: same image digest, same memory limit (1536m), same runtime-config hash, ext4 rw,relatime on its own loop
filesystem, same kernel. Each run: a unique bucket with hard quota QP; W writers PUT S-byte objects as fast as they can;
every PUT completion/refusal is timestamped inside the writer container. Measured per run:
  crossing  = time of the PUT completion that took cumulative admitted bytes past QP
  refusal   = time of the first quota refusal
  lag       = refusal - crossing;  overshoot (churn) = admitted bytes - QP
Bounds / abort (watchdog, 1 s): host root free < MIN_ROOT_FREE, lab fs free < MIN_LAB_FREE, MinIO anon > MAX_ANON,
MinIO restart/OOM, run > MAX_RUN_S, or the per-run object cap (budget). After every run: bucket removed + fstrim.
"""
import json, os, shutil, subprocess, sys, time

OUT = sys.argv[1]; SERIES = sys.argv[2]; RUNS = int(sys.argv[3]); WRITERS = int(sys.argv[4]); SIZE_MIB = int(sys.argv[5])
CAP_PER_WRITER = int(sys.argv[6]); BACKGROUND = sys.argv[7] if len(sys.argv) > 7 else ""
MC = "registry.yvnn.is/infinity/mirror/minio/mc@sha256:2582c2f48b1e31545143ba5285c67d7b38c8b8f6912142d0630686dc7aaac28b"
QP = 256 * 2**20; MIN_ROOT_FREE = 40 * 2**30; MIN_LAB_FREE = 6 * 2**30; MAX_ANON = 1400 * 2**20; MAX_RUN_S = 240
os.makedirs(OUT, exist_ok=True)


def sh(*a, **k): return subprocess.run(a, capture_output=True, text=True, **k)
def minio_state():
    d = json.loads(sh("docker", "inspect", "churnlab-minio").stdout)[0]
    return d["Id"], d["RestartCount"], d["State"]["OOMKilled"], d["State"]["Status"]
def anon(cid):
    try:
        for l in open(f"/sys/fs/cgroup/system.slice/docker-{cid}.scope/memory.stat"):
            if l.startswith("anon "): return int(l.split()[1])
    except OSError: return -1
def mc(script): return sh("docker", "run", "--rm", "--network", "churnlab", "--entrypoint", "/bin/sh", MC, "-c",
                          "mc alias set l http://churnlab-minio:9000 labroot labrootpw12345 >/dev/null && " + script)


results = []
cid0, restarts0, _, _ = minio_state()
for run in range(1, RUNS + 1):
    d = f"{OUT}/{SERIES}-run{run}"; shutil.rmtree(d, ignore_errors=True); os.makedirs(d)
    bucket = f"lab-{SERIES.lower()}-{run}-{int(time.time())}"
    r = mc(f"mc mb l/{bucket} >/dev/null && mc quota set l/{bucket} --size {QP} >/dev/null && echo ok")
    if "ok" not in r.stdout: sys.exit(f"bucket setup failed: {r.stderr[-200:]}")
    writer = (f"head -c {SIZE_MIB * 2**20} /dev/urandom > /tmp/f; for W in $(seq 1 {WRITERS}); do ( n=0; while [ $n -lt {CAP_PER_WRITER} ]; do "
              f"if mc cp -q /tmp/f l/{bucket}/w$W-$n >/dev/null 2>/out/err-$W; then echo \"$(date +%s.%N) ok\" >> /out/w$W; else echo \"$(date +%s.%N) refused\" >> /out/w$W; break; fi; "
              f"n=$((n+1)); done ) & done; wait")
    name = f"labwriter-{SERIES.lower()}-{run}"
    p = subprocess.Popen(["docker", "run", "--rm", "--name", name, "--network", "churnlab", "-v", f"{d}:/out", "--entrypoint", "/bin/sh", MC, "-c",
                          "mc alias set l http://churnlab-minio:9000 labroot labrootpw12345 >/dev/null && " + writer])
    t0, peak, abort = time.time(), 0, None
    while p.poll() is None:
        time.sleep(1)
        cid, rs, oom, st = minio_state(); a = anon(cid); peak = max(peak, a)
        root_free = shutil.disk_usage("/").free; lab_free = shutil.disk_usage("/srv/churnlab").free
        if rs != restarts0 or oom or st != "running": abort = f"MinIO restart/OOM (restarts {rs}, oom {oom}, {st})"
        elif a > MAX_ANON: abort = f"MinIO anon {a >> 20} MiB > {MAX_ANON >> 20}"
        elif root_free < MIN_ROOT_FREE: abort = f"host root free {root_free >> 30} GiB < {MIN_ROOT_FREE >> 30}"
        elif lab_free < MIN_LAB_FREE: abort = f"lab fs free {lab_free >> 30} GiB < {MIN_LAB_FREE >> 30}"
        elif time.time() - t0 > MAX_RUN_S: abort = f"run exceeded {MAX_RUN_S} s"
        if abort: sh("docker", "rm", "-f", name); p.wait(); break
    events = []
    for f in os.listdir(d):
        if f.startswith("w"):
            for l in open(f"{d}/{f}"):
                ts, what = l.split(); events.append((float(ts), what))
    events.sort()
    obj = SIZE_MIB * 2**20; cum = 0; crossing = refusal = None
    for ts, what in events:
        if what == "ok":
            cum += obj
            if crossing is None and cum > QP: crossing = ts
        elif refusal is None: refusal = ts
    oks = sum(1 for _, w in events if w == "ok"); dur = (events[-1][0] - events[0][0]) if events else 0
    errs = sorted({open(f"{d}/{f}").read().strip()[-120:] for f in os.listdir(d) if f.startswith("err-") and os.path.getsize(f"{d}/{f}")})
    res = {"series": SERIES, "run": run, "writers": WRITERS, "objectMiB": SIZE_MIB, "quotaBytes": QP, "background": BACKGROUND,
           "admittedObjects": oks, "admittedBytes": oks * obj, "overshootBytes": oks * obj - QP,
           "crossingTs": crossing, "firstRefusalTs": refusal, "lagS": round(refusal - crossing, 2) if (refusal and crossing) else None,
           "refused": refusal is not None, "hitCap": (refusal is None and abort is None), "abort": abort,
           "throughputMiBs": round(oks * SIZE_MIB / dur, 1) if dur else None, "peakAnonMiB": peak >> 20,
           "refusalMessages": [e for e in errs if "quota" in e.lower()][:1] + [e for e in errs if "quota" not in e.lower()][:2]}
    mc(f"mc rb --force l/{bucket} >/dev/null 2>&1"); sh("fstrim", "/srv/churnlab")
    res["cleanup"] = "bucket removed, fs trimmed"; results.append(res)
    print(json.dumps(res), flush=True)
    json.dump(res, open(f"{d}/result.json", "w"), indent=1)
    if abort: print(json.dumps({"stopped": abort}), flush=True); break
    time.sleep(15)
json.dump(results, open(f"{OUT}/{SERIES}-results.json", "w"), indent=1)
