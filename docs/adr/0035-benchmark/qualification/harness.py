"""ADR-035 A_F qualification, revision 2 (TI-DEC-001a §4, after the focused storage re-review).
Runs INSIDE the privileged qualification container: pinned MinIO binary on a dedicated loop-mounted ext4 at /data,
client on loopback (no proxy). fsfreeze stalls the data filesystem to create the strongest late-finalization case.
Every existence check is strict: 200 = present, 404 = absent, anything else is an ERROR (never counted as absent)."""
import datetime, fcntl, hashlib, hmac, json, os, random, socket, struct, subprocess, sys, termios, time, urllib.parse, uuid
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

if __name__ == "__main__":
    try: simple("PUT", sign("PUT", ""))
    except Exception: pass
    plan = json.loads(sys.argv[1])
    for sc, n, kw in plan:
        for i in range(n): trial(sc, i, **kw)
