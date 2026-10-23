"""ADR-035 A_F qualification, revision 2 (TI-DEC-001a §4, after the focused storage re-review).
Runs INSIDE the privileged qualification container: pinned MinIO binary on a dedicated loop-mounted ext4 at /data,
client on loopback (no proxy). fsfreeze stalls the data filesystem to create the strongest late-finalization case.
Every existence check is strict: 200 = present, 404 = absent, anything else is an ERROR (never counted as absent).

Revision 3 adds the ADR-035 §9a `slow-W` family (see SLOW-W below): the data filesystem is THROTTLED by a real
block-device mechanism (dm-delay, or cgroup v2 io.max on the loop device) without being frozen, the client stays
connected and silent after the body is acknowledged, and the storage witness runs concurrently. The freeze scenarios
and their emitted rows are unchanged."""
import datetime, fcntl, hashlib, hmac, json, os, random, socket, struct, subprocess, sys, termios, threading, time, urllib.parse, uuid
HOST, PORT, AK, SK, B = "127.0.0.1", 9000, "qual", "qualqualqual", "qual"
SIZE = 15 * 1024 * 1024
OUT = open(os.environ.get("OUT", "/q/results.jsonl"), "a")

def sign(method, key, expires=900, extra=None):
    now = datetime.datetime.now(datetime.timezone.utc); amz = now.strftime("%Y%m%dT%H%M%SZ"); day = amz[:8]
    scope = f"{day}/us-east-1/s3/aws4_request"; path = f"/{B}/{key}" if key else f"/{B}"
    hdrs = {"host": f"{HOST}:{PORT}", **(extra or {})}; sh = ";".join(sorted(hdrs))
    q = {"X-Amz-Algorithm": "AWS4-HMAC-SHA256", "X-Amz-Credential": f"{AK}/{scope}", "X-Amz-Date": amz,
         "X-Amz-Expires": str(expires), "X-Amz-SignedHeaders": sh}
    cq = "&".join(f"{urllib.parse.quote(k, safe='~')}={urllib.parse.quote(v, safe='~')}" for k, v in sorted(q.items()))
    creq = "\n".join([method, urllib.parse.quote(path), cq, "".join(f"{k}:{hdrs[k]}\n" for k in sorted(hdrs)), sh, "UNSIGNED-PAYLOAD"])
    sts = "\n".join(["AWS4-HMAC-SHA256", amz, scope, hashlib.sha256(creq.encode()).hexdigest()])
    k = ("AWS4" + SK).encode()
    for part in (day, "us-east-1", "s3", "aws4_request"): k = hmac.new(k, part.encode(), hashlib.sha256).digest()
    return f"{path}?{cq}&X-Amz-Signature={hmac.new(k, sts.encode(), hashlib.sha256).hexdigest()}"

def simple(method, pathq, body=b"", headers=None, timeout=10):
    s = socket.create_connection((HOST, PORT), timeout=timeout)
    h = {"Host": f"{HOST}:{PORT}", "Content-Length": str(len(body)), **(headers or {})}
    s.sendall((f"{method} {pathq} HTTP/1.1\r\n" + "".join(f"{a}: {b}\r\n" for a, b in h.items()) + "\r\n").encode() + body)
    d = b""
    while b"\r\n\r\n" not in d:
        c = s.recv(65536)
        if not c: break
        d += c
    s.close(); return int(d.split(b" ")[1])

def state(key):
    """Strict existence: True/False, or raises on anything but 200/404."""
    code = simple("HEAD", sign("HEAD", key))
    if code == 200: return True
    if code == 404: return False
    raise RuntimeError(f"HEAD {key} -> {code}")

def outq(s):  # bytes not yet acknowledged by the peer (unsent + unacked)
    return struct.unpack("i", fcntl.ioctl(s.fileno(), termios.TIOCOUTQ, b"\0\0\0\0"))[0]

def freeze(): subprocess.run(["fsfreeze", "-f", "/data"], check=True)
def thaw(): subprocess.run(["fsfreeze", "-u", "/data"], check=True)

def upload(key):
    extra = {"content-length": str(SIZE), "if-none-match": "*"}
    s = socket.create_connection((HOST, PORT), timeout=300)
    s.sendall((f"PUT {sign('PUT', key, extra=extra)} HTTP/1.1\r\nHost: {HOST}:{PORT}\r\nContent-Length: {SIZE}\r\nIf-None-Match: *\r\n\r\n").encode())
    t0 = time.monotonic(); s.sendall(os.urandom(SIZE))
    while outq(s) > 0: time.sleep(0.001)      # FULL body acknowledged by the server's kernel
    return s, t0, time.monotonic()

def on_disk(key):
    """Bypass MinIO: does the committed object metadata exist on the data filesystem? Reads work while frozen."""
    return os.path.exists(f"/data/{B}/{key}/xl.meta")

def rst(s):
    s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0)); s.close()

def poll(key, until_s, t_ref):
    """Poll strictly; return (first_visible_seconds_after_t_ref or None, absent_checks, errors)."""
    absent = errs = 0
    while time.monotonic() - t_ref < until_s:
        try:
            if state(key): return round(time.monotonic() - t_ref, 3), absent, errs
            absent += 1
        except Exception: errs += 1
        time.sleep(0.05)
    return None, absent, errs

def emit(**r): r["t"] = time.time(); OUT.write(json.dumps(r) + "\n"); OUT.flush(); print(json.dumps(r), flush=True)

def trial(scenario, i, **kw):
    if is_slow(kw): return slow_trial(scenario, i, **kw)
    try: return _trial(scenario, i, **kw)
    finally: subprocess.run(["fsfreeze", "-u", "/data"], capture_output=True)   # never leave /data frozen

def _trial(scenario, i, freeze_after_ms=None, hold_s=0, client="W", rst_after_s=0.0, poll_s=180):
    key = f"{scenario}/{i}-{uuid.uuid4().hex[:8]}/raw.eml"
    s, t0, t_acked = upload(key)
    froze = False
    if freeze_after_ms is not None:
        time.sleep(freeze_after_ms / 1000); freeze(); froze = True
    t_frozen = time.monotonic()
    disk_at_freeze = on_disk(key) if froze else None
    witness = None
    if froze:   # liveness witness: a small commit issued DURING the stall must not complete
        try:
            code = simple("PUT", sign("PUT", f"_probe/{uuid.uuid4().hex}", extra={"content-length": "1"}), b"x",
                          {"Content-Length": "1"}, timeout=5)
            witness = f"completed:{code}"
        except Exception as e: witness = f"blocked:{type(e).__name__}"
    if client == "R":
        time.sleep(max(0.0, rst_after_s - (time.monotonic() - t_frozen))); rst(s)
    t_rst = time.monotonic() if client == "R" else None
    disk_after_rst = on_disk(key) if (froze and client == "R") else None
    visible_during_freeze = None
    disk_before_thaw = None
    if froze:
        # a CAUTIOUS check during the freeze: HEAD reads work on a frozen FS
        time.sleep(max(0.0, hold_s - (time.monotonic() - t_frozen)))
        try: visible_during_freeze = state(key)
        except Exception as e: visible_during_freeze = f"error:{e}"
        disk_before_thaw = on_disk(key)
        thaw()
    t_thaw = time.monotonic()
    lat, absent, errs = poll(key, poll_s, t_thaw)
    if client == "W": s.close()
    disk_after = on_disk(key)
    committed_after_thaw = bool(froze and disk_before_thaw is False and disk_after)
    emit(disk_at_freeze=disk_at_freeze, disk_after_rst=disk_after_rst, disk_before_thaw=disk_before_thaw,
         disk_after_poll=disk_after, committed_after_thaw=committed_after_thaw, scenario=scenario, i=i, key=key, client=client, freeze_after_ms=freeze_after_ms, hold_s=hold_s,
         rst_after_s=rst_after_s if client == "R" else None, body_ack_s=round(t_acked - t0, 3),
         throughput_mib_s=round(SIZE / 1048576 / max(t_acked - t0, 1e-6), 1),
         visible_during_freeze=visible_during_freeze, witness_during_freeze=witness,
         visible_after_thaw_s=lat, absent_checks_before_visible=absent, poll_errors=errs,
         late_after_absent=(lat is not None and absent > 0))

# ---------------------------------------------------------------------------------------------------------------
# SLOW-W (ADR-035 §9a). The question: can an ambiguous upload's commit land AFTER a witness that was ISSUED later
# has already COMPLETED, and if so, how late? That is A_F's residual, which a freeze cannot show (the witness is
# blocked throughout a freeze). Throttling must be a real block-device mechanism on the data filesystem's device:
#   dm-delay : /data is an ext4 on /dev/mapper/qualslow (entry.sh, SLOW=1|dm); the write delay is changed by a table
#              reload (`0 <sectors> delay <dev> 0 0 <dev> 0 <write_ms>` — reads undelayed, writes delayed).
#   cgroup   : MinIO runs in the cgroup v2 leaf /sys/fs/cgroup/qualslow (entry.sh, SLOW=cgroup); `io.max` on the
#              loop device's major:minor caps its write bytes/s. Everything MinIO writes, witness included, is capped.
# Nothing else is accepted: no sleep(), no CPU throttling, no network latency. When neither mechanism is available the
# preflight emits {"scenario":"slow-W","status":"NOT RUN","reason":...} and the process exits non-zero.
# ---------------------------------------------------------------------------------------------------------------
SLOW_ENV = "/run/qual/devices.env"      # written by entry.sh: SLOW_MODE=dm|cgroup|none, LOOPDEV, DATADEV, SLOW_REASON
DM_NAME, CG_DIR = "qualslow", "/sys/fs/cgroup/qualslow"
WITNESS_TIMEOUT_S, WITNESS_INTERVAL_S = 5, 1.0

def is_slow(kw): return "throttle_ms" in kw or "io_max_wbps" in kw

def slow_env():
    env = {}
    if os.path.exists(SLOW_ENV):
        for line in open(SLOW_ENV):
            if "=" in line: k, v = line.rstrip("\n").split("=", 1); env[k] = v
    return env

def data_source():
    return subprocess.run(["findmnt", "-no", "SOURCE", "/data"], capture_output=True, text=True).stdout.strip()

def dm_targets():
    return subprocess.run(["dmsetup", "targets"], capture_output=True, text=True).stdout

def preflight(mode):
    """Returns (ok, detail dict). `mode` is 'dm' or 'cgroup'; it never degrades to something else."""
    env = slow_env(); d = {"mode": mode, "data_source": data_source(), "entry_env": env}
    if mode == "dm":
        tg = dm_targets(); d["dmsetup_targets_has_delay"] = ("delay" in tg.split())
        d["data_on_mapper"] = d["data_source"] == f"/dev/mapper/{DM_NAME}"
        if not d["dmsetup_targets_has_delay"]: return False, {**d, "reason": "dmsetup targets does not list `delay` (kernel built without CONFIG_DM_DELAY, or module absent)"}
        if not d["data_on_mapper"]: return False, {**d, "reason": f"/data is not mounted from /dev/mapper/{DM_NAME}; start the container with SLOW=1 or SLOW=dm"}
        table = subprocess.run(["dmsetup", "table", DM_NAME], capture_output=True, text=True).stdout.strip()
        d["dm_table"] = table
        if " delay " not in f" {table} ": return False, {**d, "reason": f"{DM_NAME} is not a delay target: {table}"}
        return True, d
    if mode == "cgroup":
        try:
            ctl = open("/sys/fs/cgroup/cgroup.controllers").read().split()
        except OSError as e: return False, {**d, "reason": f"cgroup v2 not mounted at /sys/fs/cgroup: {e}"}
        d["controllers"] = ctl
        if "io" not in ctl: return False, {**d, "reason": "cgroup v2 `io` controller not available"}
        if not os.path.exists(f"{CG_DIR}/io.max"): return False, {**d, "reason": f"{CG_DIR}/io.max absent; start the container with SLOW=cgroup (entry.sh enables +io and creates the leaf)"}
        procs = open(f"{CG_DIR}/cgroup.procs").read().split(); d["cgroup_procs"] = procs
        if not procs: return False, {**d, "reason": f"no process in {CG_DIR}: MinIO must run inside the throttled cgroup"}
        mm = env.get("LOOP_MAJMIN") or loop_majmin(env.get("LOOPDEV", "")); d["loop_majmin"] = mm
        if not mm: return False, {**d, "reason": "cannot determine the loop device major:minor behind /data"}
        try:
            with open(f"{CG_DIR}/io.max", "w") as f: f.write(f"{mm} wbps=max\n")
        except OSError as e: return False, {**d, "reason": f"io.max not writable for {mm}: {e}"}
        d["io_max"] = open(f"{CG_DIR}/io.max").read().strip() or "(empty: every limit is max)"
        return True, d
    return False, {**d, "reason": f"unknown slow mode {mode!r}"}

def loop_majmin(dev):
    try: st = os.stat(dev); return f"{os.major(st.st_rdev)}:{os.minor(st.st_rdev)}"
    except OSError: return None

def throttle_on(mode, throttle_ms=None, io_max_wbps=None):
    if mode == "dm":
        parts = subprocess.run(["dmsetup", "table", DM_NAME], capture_output=True, text=True).stdout.split()
        # "0 <sectors> delay <dev> <off> <rdelay> [<wdev> <woff> <wdelay>]" -> reads 0 ms, writes throttle_ms
        start, sectors, dev, off = parts[0], parts[1], parts[3], parts[4]
        table = f"{start} {sectors} delay {dev} {off} 0 {dev} {off} {int(throttle_ms)}"
        dm_reload(table); return table
    with open(f"{CG_DIR}/io.max", "w") as f: f.write(f"{slow_env().get('LOOP_MAJMIN') or loop_majmin(slow_env().get('LOOPDEV',''))} wbps={int(io_max_wbps)}\n")
    return open(f"{CG_DIR}/io.max").read().strip()

def throttle_off(mode):
    if mode == "dm":
        parts = subprocess.run(["dmsetup", "table", DM_NAME], capture_output=True, text=True).stdout.split()
        if len(parts) >= 5 and parts[2] == "delay": dm_reload(f"{parts[0]} {parts[1]} delay {parts[3]} {parts[4]} 0")
        return
    mm = slow_env().get("LOOP_MAJMIN") or loop_majmin(slow_env().get("LOOPDEV", ""))
    if mm and os.path.exists(f"{CG_DIR}/io.max"):
        with open(f"{CG_DIR}/io.max", "w") as f: f.write(f"{mm} wbps=max\n")

def dm_reload(table):
    env = {**os.environ, "DM_DISABLE_UDEV": "1"}
    subprocess.run(["dmsetup", "suspend", "--noflush", DM_NAME], check=True, env=env)
    subprocess.run(["dmsetup", "load", DM_NAME, "--table", table], check=True, env=env)
    subprocess.run(["dmsetup", "resume", DM_NAME], check=True, env=env)

def witness_loop(stop, out, t_ref):
    """Issue a 1-byte PUT every ~1 s with a 5 s timeout; record issue/complete times relative to t_ref."""
    while not stop.is_set():
        issued = time.monotonic(); rec = {"issued_s": round(issued - t_ref, 3), "completed_s": None, "outcome": None}
        try:
            code = simple("PUT", sign("PUT", f"_probe/{uuid.uuid4().hex}", extra={"content-length": "1"}), b"x",
                          {"Content-Length": "1"}, timeout=WITNESS_TIMEOUT_S)
            rec["outcome"] = f"completed:{code}"
            if code == 200: rec["completed_s"] = round(time.monotonic() - t_ref, 3)
        except Exception as e: rec["outcome"] = f"blocked:{type(e).__name__}"
        out.append(rec); stop.wait(max(0.0, WITNESS_INTERVAL_S - (time.monotonic() - issued)))

def slow_poll(key, t_ref, until, st):
    """Strict HEAD + on-disk poll; fills st['first_visible_s'] / st['first_on_disk_s'] (relative to t_ref).
    Each fact is stamped when its check RETURNS, never when it was issued: a HEAD can block on the in-flight commit,
    so the issue time would antedate the commit."""
    while time.monotonic() < until:
        if st["first_on_disk_s"] is None and on_disk(key): st["first_on_disk_s"] = round(time.monotonic() - t_ref, 3)
        try:
            if state(key):
                if st["first_visible_s"] is None: st["first_visible_s"] = round(time.monotonic() - t_ref, 3)
            else: st["absent_checks"] += 1
        except Exception: st["poll_errors"] += 1
        if st["first_visible_s"] is not None and st["first_on_disk_s"] is not None: return
        time.sleep(0.05)

def slow_trial(scenario, i, throttle_ms=None, io_max_wbps=None, client="W", throttle_at="after_ack",
               throttle_after_ms=0, hold_s=45, poll_s=120):
    if client != "W": raise ValueError("slow-W keeps the client connected and silent (client must be 'W')")
    mode = "dm" if throttle_ms is not None else "cgroup"
    ok, d = preflight(mode)
    if not ok:
        emit(scenario="slow-W", status="NOT RUN", reason=d["reason"], detail=d); sys.exit(2)
    key = f"{scenario}/{i}-{uuid.uuid4().hex[:8]}/raw.eml"
    try:
        # `before_put` throttles the body write itself (the ack then arrives under throttle); `after_ack` (the freeze
        # scenarios' phase model) throttles throttle_after_ms after the kernel acknowledged the full body.
        if throttle_at == "before_put": applied = throttle_on(mode, throttle_ms, io_max_wbps); t_throttled = time.monotonic()
        s, t0, t_acked = upload(key)
        if throttle_at != "before_put":
            time.sleep(throttle_after_ms / 1000); applied = throttle_on(mode, throttle_ms, io_max_wbps); t_throttled = time.monotonic()
        disk_at_throttle = on_disk(key)
        st = {"first_visible_s": None, "first_on_disk_s": None, "absent_checks": 0, "poll_errors": 0}
        witnesses, stop = [], threading.Event()
        wt = threading.Thread(target=witness_loop, args=(stop, witnesses, t_acked), daemon=True); wt.start()
        # Poll (and witness) while throttled, for hold_s after the ack; stop early once the commit has landed, since
        # nothing more can be learned about THIS upload's ordering after that.
        slow_poll(key, t_acked, t_acked + hold_s, st)
        disk_before_unthrottle = on_disk(key)
        throttle_off(mode); t_unthrottled = time.monotonic()
        stop.set(); wt.join(timeout=WITNESS_TIMEOUT_S + 1)
        slow_poll(key, t_acked, t_unthrottled + poll_s, st)         # a commit still pending must be given time to land
        s.close()
    finally:
        throttle_off(mode)                                            # never leave the data device throttled
    # Both checks are strict proofs that the commit (the final rename) has happened; the earlier one bounds it best,
    # since each poll lags the event by up to its 50 ms interval (a HEAD 200 needs xl.meta in place; a 503 is an error).
    proofs = [v for v in (st["first_on_disk_s"], st["first_visible_s"]) if v is not None]
    commit_s = min(proofs) if proofs else None
    completed = [w for w in witnesses if w["completed_s"] is not None]
    # Every witness is issued after the upload. The residual: a witness that completed BEFORE the upload's commit landed.
    before = [w for w in completed if commit_s is not None and w["completed_s"] < commit_s]
    emit(scenario=scenario, i=i, key=key, client=client, slow_mode=mode, throttle_ms=throttle_ms, io_max_wbps=io_max_wbps,
         throttle_at=throttle_at, throttle_after_ms=throttle_after_ms if throttle_at != "before_put" else None,
         throttle_applied=applied, hold_s=hold_s, body_ack_s=round(t_acked - t0, 3),
         throughput_mib_s=round(SIZE / 1048576 / max(t_acked - t0, 1e-6), 1),
         disk_at_throttle=disk_at_throttle, disk_before_unthrottle=disk_before_unthrottle, disk_after_poll=on_disk(key),
         throttled_for_s=round(t_unthrottled - t_throttled, 3), throttled_after_ack_for_s=round(t_unthrottled - t_acked, 3),
         first_visible_at=st["first_visible_s"], first_on_disk_at=st["first_on_disk_s"], commit_at=commit_s,
         visible_after_thaw_s=(None if st["first_visible_s"] is None else round(max(0.0, st["first_visible_s"] - (t_unthrottled - t_acked)), 3)),
         absent_checks_before_visible=st["absent_checks"], poll_errors=st["poll_errors"],
         witnesses=witnesses, witnesses_issued=len(witnesses), witnesses_completed=len(completed),
         witnesses_blocked=sum(1 for w in witnesses if w["outcome"] and w["outcome"].startswith("blocked")),
         witnesses_completed_before_commit=len(before),
         commit_after_completed_witness=bool(before),
         commit_lag_after_first_completed_witness_s=(round(commit_s - min(w["completed_s"] for w in before), 3) if before else None),
         commit_lag_after_last_completed_witness_s=(round(commit_s - max(w["completed_s"] for w in before), 3) if before else None),
         never_committed=(commit_s is None))

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--preflight":           # harness.py --preflight dm|cgroup|auto
        want = sys.argv[2] if len(sys.argv) > 2 else "auto"
        modes = ["dm", "cgroup"] if want == "auto" else [want]
        results = []
        for m in modes:
            ok, d = preflight(m); results.append(d)
            if ok: emit(scenario="slow-W", status="PREFLIGHT OK", mode=m, detail=d); sys.exit(0)
        emit(scenario="slow-W", status="NOT RUN", reason="; ".join(f"{r['mode']}: {r['reason']}" for r in results), detail=results)
        sys.exit(2)
    try: simple("PUT", sign("PUT", ""))
    except Exception: pass
    plan = json.loads(sys.argv[1])
    for sc, n, kw in plan:
        for i in range(n): trial(sc, i, **kw)
