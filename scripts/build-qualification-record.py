#!/usr/bin/env python3
"""Build the ADR-035 §9a production-host qualification record from the Ops N1 evidence bundle (W4, R2).

Offline. It reads ONE directory, the N1 evidence bundle Ops delivers, and writes ONE new directory:

    docs/adr/0035-benchmark/qualification/production-<date>/
        record.json      the record, in the shipped shape (schemaVersion 1, QualificationRecords.parse)
        PROVENANCE.md    every member, its value, its label (PROD-QUAL / PROD-NOW / DERIVED / CODE /
                         CARRIED-AMENDMENT) and the evidence file it was read from
        SHA256SUMS       of record.json and PROVENANCE.md
        evidence/        the bundle, copied byte for byte (with its own SHA256SUMS)

It never ships anything: adding the record to backend/storage/src/main/resources/adr035-qualification/ and its
index.txt is a separate, reviewed step (docs/adr/0035-benchmark/qualification/RECORD-CUT.md).

It REFUSES (exit 1, nothing written) when
  - SHA256SUMS is missing, malformed, does not list every file, lists a file that is absent, or a digest differs;
  - a required file or value is missing or has the wrong type;
  - an experiment (E1-E11) or the §9a sweep is not PASS, recomputed from the raw harness output where it can be;
  - two sources of one identity element disagree with each other or with the FIXED values (--expected);
  - the recordId derived from the evidence differs from MANIFEST.json or from the gate F evidence's
    qualificationRecordId;
  - E7 is carried from another corpus without MANIFEST.e7.mode = "carried" and an amendment reference that is
    present in ADR-035's text (W3 question A5: both outcomes are supported, neither is silent).
Nothing measured is typed by hand: every record value is read from a bundle file, derived from bundle files by a
rule stated in PROVENANCE.md, or is a constant of the artifact (CODE). Exit 2 is a usage error.

Usage:
  scripts/build-qualification-record.py --bundle <dir> [--expected <json>] [--out-root <dir>] [--repo <dir>] [--check]
  scripts/build-qualification-record.py --evaluate-9a <dir>     (recompute a §9a sweep verdict only; prints JSON)
"""
import argparse, datetime, hashlib, json, os, re, shlex, shutil, sys

REPO_DEFAULT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
QUAL_REL = "docs/adr/0035-benchmark/qualification"
EXPECTED_REL = f"{QUAL_REL}/production-expected.json"
PROTOCOL_REL = "backend/application/src/main/kotlin/email/testinbox/application/storage/StorageProtocol.kt"
ADR_REL = "docs/adr/0035-physical-storage-bound-at-ingest.md"

BUNDLE_SCHEMA = "testinbox.n1-bundle/1"
EXPERIMENT_SCHEMA = "testinbox.n1-experiment/1"
EXPECTED_SCHEMA = "testinbox.production-expected/1"
FS_EVIDENCE_SCHEMA = "testinbox.filesystem-evidence/1"
EXPERIMENTS = [f"E{i}" for i in range(1, 12)]
# Experiments that exercise the APPLICATION (enforcing lab instance or the v2 uploader): they must name the
# candidate artifact they ran (app verdict 2.1 R3/R4, D3). E1-E4 and E7 run the harness against MinIO only.
APP_EXPERIMENTS = {"E5", "E6", "E8", "E9", "E10", "E11"}
OMAX_EXPERIMENTS = ("E1", "E2", "E3")
C_MAX_S = 15 * 60  # ADR-035 §9: C_max = 15 min, measured from the writer's RST
SLOW_W_MIN_TRIALS = 18  # plan-slow.json
SWEEP_MIN_GAP_MIN = 17
# The Ops qualification-check normalization (infinity-core infra/testinbox/storage/qualification-check.py).
NORMALIZATION = "ti-qc-runtime-config-v1"
SUBSYSTEMS = ("api", "drive", "storage_class", "scanner")
TIMEOUT_NAME = re.compile(r"^MINIO_.*(TIMEOUT|DEADLINE|KEEP_?ALIVE)", re.I)
TIMEOUT_FLAG = re.compile(r"timeout|deadline|keep-?alive", re.I)
SHA256_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
ISO_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
PROCEDURE = ("docs/adr/0035-benchmark/minio-probes/QUALIFICATION.md; qualification/run-slow.sh --mode dm with plan.json, "
             "plan3.json and plan-slow.json, each swept after >= 17 min; E1-E11 per ADR-035 Amendment 2 annex §10 and "
             "the WS-N1 amendment notes; bundle layout docs/adr/0035-benchmark/qualification/RECORD-CUT.md")


class Refusal(Exception):
    pass


class Problems:
    """Collects every problem, so one run names all of them; any problem refuses the build."""

    def __init__(self):
        self.items = []

    def add(self, msg):
        self.items.append(msg)

    def check(self, cond, msg):
        if not cond:
            self.add(msg)
        return cond

    def raise_if_any(self):
        if self.items:
            raise Refusal("\n".join(f"  ✗ {p}" for p in self.items))


def iso(s):
    return datetime.datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)


def fmt(t):
    return t.strftime("%Y-%m-%dT%H:%M:%SZ")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------------------------------------- SHA256SUMS (first)
def verify_sums(bundle):
    sums = os.path.join(bundle, "SHA256SUMS")
    if not os.path.isfile(sums):
        raise Refusal("  ✗ SHA256SUMS is missing: the bundle cannot be verified, nothing is read")
    listed, bad = {}, []
    for n, line in enumerate(open(sums, encoding="utf-8"), 1):
        if not line.strip():
            continue
        m = re.match(r"^([0-9a-f]{64}) [ *](.+)$", line.rstrip("\n"))
        if not m:
            bad.append(f"SHA256SUMS line {n} is not '<sha256>  <path>'")
            continue
        rel = os.path.normpath(m.group(2).removeprefix("./"))
        if rel.startswith("..") or os.path.isabs(rel):
            bad.append(f"SHA256SUMS line {n} names a path outside the bundle: {m.group(2)}")
            continue
        listed[rel] = m.group(1)
    present = set()
    for root, dirs, files in os.walk(bundle):
        for f in files:
            rel = os.path.relpath(os.path.join(root, f), bundle)
            if rel != "SHA256SUMS":
                present.add(rel)
    for rel in sorted(set(listed) - present):
        bad.append(f"SHA256SUMS lists {rel}, which is not in the bundle")
    for rel in sorted(present - set(listed)):
        bad.append(f"{rel} is in the bundle but not in SHA256SUMS")
    for rel in sorted(set(listed) & present):
        if sha256_file(os.path.join(bundle, rel)) != listed[rel]:
            bad.append(f"SHA256 mismatch: {rel}")
    if bad:
        raise Refusal("\n".join(f"  ✗ {b}" for b in bad))
    return len(listed)


# ------------------------------------------------------------------------------------------------ small readers
class Bundle:
    def __init__(self, root, problems):
        self.root, self.p = root, problems

    def path(self, rel):
        return os.path.join(self.root, rel)

    def text(self, rel):
        if not self.p.check(os.path.isfile(self.path(rel)), f"required file {rel} is missing"):
            return None
        t = open(self.path(rel), encoding="utf-8").read()
        return t if self.p.check(t.strip() != "", f"{rel} is empty") else None

    def json(self, rel):
        t = self.text(rel)
        if t is None:
            return None
        try:
            return json.loads(t)
        except ValueError as e:
            self.p.add(f"{rel} is not valid JSON ({e})")
            return None


def need(p, doc, key, kind, where):
    """A typed required member; records a problem and returns None when absent or mistyped."""
    v = doc.get(key) if isinstance(doc, dict) else None
    ok = {
        "str": isinstance(v, str) and v != "",
        "int": isinstance(v, int) and not isinstance(v, bool),
        "pos": isinstance(v, int) and not isinstance(v, bool) and v > 0,
        "bool": isinstance(v, bool),
        "list": isinstance(v, list),
        "dict": isinstance(v, dict),
        "iso": isinstance(v, str) and bool(ISO_RE.match(v)),
        "sha": isinstance(v, str) and bool(SHA256_RE.match(v)),
    }[kind]
    if not ok:
        p.add(f"{where}: '{key}' is missing or not {kind} (got {v!r})")
        return None
    return v


def kv_lines(text):
    """n1-fs-baseline.sh output: '     key=value' lines, '# n1-fs-baseline phase=… at=… host=…', '# verdict …'."""
    kv = {}
    for line in text.splitlines():
        m = re.match(r"^     ([A-Za-z0-9_.\-]+)=(.*)$", line)
        if m:
            kv[m.group(1)] = m.group(2)
    head = re.search(r"^# n1-fs-baseline phase=(\S+) at=(\S+) host=(\S+)", text, re.M)
    verdict = re.search(r"^# verdict (.*)$", text, re.M)
    return kv, head, verdict.group(1).strip() if verdict else None


def dumpe2fs_field(text, name):
    m = re.search(rf"^{re.escape(name)}:\s*(.*)$", text, re.M)
    return m.group(1).strip() if m else None


def normalize_runtime_config(outputs):
    """Port of qualification-check.py normalize_runtime_config (ti-qc-runtime-config-v1), byte for byte in output."""
    lines = []
    for sub in SUBSYSTEMS:
        rc, out, err = outputs[sub]
        if rc == 0:
            for raw in out.splitlines():
                toks = shlex.split(raw.strip())
                if not toks:
                    continue
                head = toks[0]
                for tok in toks[1:]:
                    if "=" not in tok:
                        raise ValueError(f"{sub}: unparsable token {tok!r}")
                    k, v = tok.split("=", 1)
                    lines.append(f"{head}.{k}={v}")
        elif "unknown subsystem" in (out + err):
            lines.append(f"{sub}=<unknown-subsystem>")
        else:
            raise ValueError(f"mc admin config get {sub} failed: {(err or out).strip()[:200]}")
    text = "\n".join(sorted(lines))
    return "sha256:" + hashlib.sha256(text.encode()).hexdigest(), text


def server_info(info):
    """`mc admin info --json`'s info object → the record's elements, as qualification-check derives them."""
    servers = info.get("servers", [])
    drives = sum(len(s.get("drives", [])) for s in servers)
    version = servers[0].get("version", "") if servers else ""
    backend = info.get("backend", {})
    mode = ("single-node-single-drive" if len(servers) == 1 and drives == 1 and backend.get("backendType") == "Erasure"
            else f"other(servers={len(servers)},drives={drives},backend={backend.get('backendType')})")
    commits = {s.get("commitID") for s in servers}
    return {"release": "RELEASE." + version.replace(":", "-") if version else "",
            "commitId": commits.pop() if len(commits) == 1 else "inconsistent", "mode": mode, "driveCount": drives}


# ------------------------------------------------------------------------------------ §9a sweep, recomputed from raw
def evaluate_9a(q):
    """The staging gen-record.py criteria (TI-STORAGE-006C), unchanged, over <q>/{main,v3,slow} + orchestrator.log.
    Returns (stats, reasons, combination). Missing files raise Refusal."""
    def jl(path):
        if not os.path.isfile(path):
            raise Refusal(f"  ✗ §9a raw file {os.path.relpath(path, q)} is missing")
        return [json.loads(line) for line in open(path) if line.strip().startswith("{")]

    passes = {p: {"results": jl(f"{q}/{p}/results-slow.jsonl"), "sweep": jl(f"{q}/{p}/sweep-slow.jsonl")}
              for p in ("main", "v3", "slow")}
    rows = [r for d in passes.values() for r in d["results"]]
    reasons, stats = [], {}
    fb = passes["main"]["results"] + passes["v3"]["results"]
    late = [r["key"] for r in fb if r.get("late_after_absent") or r.get("committed_after_thaw")]
    base = [r for r in fb if r["scenario"].startswith("base")]
    base_invisible = [r["key"] for r in base if r["scenario"].startswith("base-W") and r.get("visible_after_thaw_s") is None]
    wit = [r.get("witness_during_freeze") for r in fb if r.get("witness_during_freeze") is not None]
    wit_not_blocked = [w for w in wit if not str(w).startswith("blocked")]
    stats.update(freezeAndBaseTrials=len(fb), lateCommits=len(late), baseInvisible=len(base_invisible),
                 witnessDuringFreeze=len(wit), witnessNotBlocked=len(wit_not_blocked))
    if late:
        reasons.append(f"{len(late)} late commit(s) in freeze/base passes: {late[:5]}")
    if base_invisible:
        reasons.append(f"{len(base_invisible)} base-W upload(s) never visible")
    if wit_not_blocked:
        reasons.append(f"{len(wit_not_blocked)} storage witness(es) completed during a freeze")
    slow = [r for r in passes["slow"]["results"] if str(r.get("scenario", "")).startswith("slow-")]
    lags = [r["commit_lag_after_first_completed_witness_s"] for r in slow
            if r.get("commit_lag_after_first_completed_witness_s") is not None]
    over = [r["key"] for r in slow if (r.get("commit_lag_after_first_completed_witness_s") or 0) > C_MAX_S]
    slow_modes = sorted({r.get("slow_mode") for r in slow})
    stats.update(slowTrials=len(slow), slowMode=slow_modes, slowThrottlesMs=sorted({r.get("throttle_ms") for r in slow}),
                 slowMaxCommitLagAfterWitnessS=max(lags) if lags else None)
    if len(slow) < SLOW_W_MIN_TRIALS:
        reasons.append(f"slow-W ran {len(slow)} trials, plan-slow.json requires {SLOW_W_MIN_TRIALS}")
    if slow_modes != ["dm"]:
        reasons.append(f"slow-W mechanism {slow_modes} is not dm-delay")
    if over:
        reasons.append(f"{len(over)} slow-W commit(s) landed more than C_max after a completed witness: {over[:5]}")
    for p, d in passes.items():
        summ = [s for s in d["sweep"] if s.get("summary")]
        if not summ:
            reasons.append(f"{p}: sweep has no summary (aborted on a check error?)")
            continue
        s = summ[-1]
        if s["keys"] != len(d["results"]):
            reasons.append(f"{p}: sweep checked {s['keys']} of {len(d['results'])} keys")
        if s["appeared_after_poll_window"]:
            reasons.append(f"{p}: {s['appeared_after_poll_window']} object(s) appeared after the poll window")
    if not os.path.isfile(f"{q}/orchestrator.log"):
        raise Refusal("  ✗ §9a raw file orchestrator.log is missing")
    tl = {}
    for line in open(f"{q}/orchestrator.log"):
        m = re.match(r"^(\S+) (pass (\w+) trials done|sweep (\w+) start)", line)
        if m:
            tl[(m.group(3) or m.group(4), "done" if m.group(3) else "sweep")] = iso(m.group(1))
    for p in passes:
        if (p, "done") in tl and (p, "sweep") in tl:
            gap = (tl[(p, "sweep")] - tl[(p, "done")]).total_seconds() / 60
            stats[f"{p}SweepAfterMin"] = round(gap, 1)
            if gap < SWEEP_MIN_GAP_MIN:
                reasons.append(f"{p}: sweep only {gap:.1f} min after the last trial (< {SWEEP_MIN_GAP_MIN})")
        else:
            reasons.append(f"{p}: orchestrator.log has no 'trials done' / 'sweep start' pair")
    passes_order = list(passes)
    scen = sorted({r["scenario"] for r in rows},
                  key=lambda s: [p for p in passes_order if any(r["scenario"] == s for r in passes[p]["results"])][0] + s)
    stats.update(trials=len(rows), scenarios=scen)
    # The combination the harness itself ran (every pass, not only slow).
    combo = {"kernels": set(), "binaries": set(), "mounts": set(), "hashes": set(), "servers": []}
    for p in passes_order:
        d = f"{q}/{p}"
        for f in ("uname.txt", "mount.txt", "minio-binary.sha256", "harness-minio-capture.txt"):
            if not os.path.isfile(f"{d}/{f}"):
                raise Refusal(f"  ✗ §9a raw file {p}/{f} is missing")
        combo["kernels"].add(open(f"{d}/uname.txt").read().split()[2])
        combo["binaries"].add(open(f"{d}/minio-binary.sha256").read().split()[0])
        mm = re.search(r"type (\w+) \(([^)]*)\)", open(f"{d}/mount.txt").read())
        combo["mounts"].add((mm.group(1), mm.group(2)) if mm else ("?", "?"))
        cap = open(f"{d}/harness-minio-capture.txt").read()
        if "=====CONFIG" not in cap:
            raise Refusal(f"  ✗ {p}/harness-minio-capture.txt has no =====CONFIG section")
        combo["servers"].append(server_info(json.loads(cap.split("=====CONFIG", 1)[0].strip())["info"]))
        outputs, cur, buf = {}, None, []
        for line in cap.split("=====CONFIG", 1)[1].splitlines():
            m = re.match(r"^### (\w+)$", line)
            e = re.match(r"^### rc=(\d+)$", line)
            if m:
                cur, buf = m.group(1), []
                continue
            if e and cur:
                rc = int(e.group(1))
                txt = "\n".join(buf)
                outputs[cur] = (rc, txt if rc == 0 else "", txt if rc else "")
                cur = None
                continue
            if cur:
                buf.append(line)
        combo["hashes"].add(normalize_runtime_config(outputs)[0])
    completed = tl.get(("slow", "sweep"))
    return stats, reasons, combo, completed


# ---------------------------------------------------------------------------------------------------- the build
def build(bundle_dir, expected, repo):
    p = Problems()
    b = Bundle(bundle_dir, p)
    X = expected
    xm, xf, xn = X["minio"], X["filesystem"], X["network"]
    labels = []  # (member, value, label, evidence)

    def label(member, value, lab, evidence):
        labels.append((member, value, lab, evidence))

    # --- code constants
    proto = open(os.path.join(repo, PROTOCOL_REL), encoding="utf-8").read()
    mver = re.search(r'UPLOAD_IMPLEMENTATION_VERSION\s*=\s*"([^"]+)"', proto)
    upload_version = mver.group(1) if mver else None
    p.check(upload_version, f"{PROTOCOL_REL} declares no UPLOAD_IMPLEMENTATION_VERSION")

    # --- manifest
    man = b.json("MANIFEST.json") or {}
    need(p, man, "schema", "str", "MANIFEST.json") and p.check(man["schema"] == BUNDLE_SCHEMA,
                                                                 f"MANIFEST.json schema is not {BUNDLE_SCHEMA}")
    man_rid = need(p, man, "recordId", "str", "MANIFEST.json")
    host = need(p, man, "host", "str", "MANIFEST.json")
    p.check(host is None or host == X["host"], f"MANIFEST.json host '{host}' is not the expected host '{X['host']}'")
    cand = need(p, man, "candidate", "dict", "MANIFEST.json") or {}
    cand_sha = need(p, cand, "sourceSha", "str", "MANIFEST.json candidate")
    p.check(cand_sha is None or re.fullmatch(r"[0-9a-f]{40}", cand_sha), "MANIFEST.json candidate.sourceSha is not a 40-hex commit")
    need(p, cand, "apiDigest", "sha", "MANIFEST.json candidate")
    need(p, cand, "ingestionDigest", "sha", "MANIFEST.json candidate")
    e7 = need(p, man, "e7", "dict", "MANIFEST.json") or {}
    e7_mode = e7.get("mode")
    p.check(e7_mode in ("final-filesystem", "carried"), f"MANIFEST.json e7.mode is '{e7_mode}', not final-filesystem or carried")
    if e7_mode == "carried":
        amend = need(p, e7, "amendment", "str", "MANIFEST.json e7")
        adr = open(os.path.join(repo, ADR_REL), encoding="utf-8").read()
        p.check(amend is None or amend in adr,
                f"E7 is carried, but its amendment '{amend}' is not in {ADR_REL}: the owner's dated amendment must merge first (W3 A5)")

    # --- post-reboot identity: the n1-fs-baseline outputs
    bl = {}
    for phase, rel in (("post-reboot", "identity/fs-baseline-post-reboot.txt"),
                       ("post-cleanup", "identity/fs-baseline-post-cleanup.txt")):
        t = b.text(rel)
        if t is None:
            continue
        kv, head, verdict = kv_lines(t)
        p.check(verdict == "OK", f"{rel}: verdict is '{verdict}', not OK")
        if p.check(head is not None, f"{rel}: no '# n1-fs-baseline phase=… at=…' header"):
            p.check(head.group(1) == phase, f"{rel}: phase is '{head.group(1)}', not {phase}")
            p.check(head.group(3) == X["host"], f"{rel}: host is '{head.group(3)}', not {X['host']}")
            kv["_at"] = head.group(2)
        for key, want in (("kernel", X["kernelRelease"]), ("arch", X["architecture"]), ("mount.fstype", xf["type"]),
                          ("mount.options", xf["mountOptions"]), ("loop.backing", xf["mountSource"]),
                          ("fs.uuid", xf["uuid"]), ("fs.block_size", str(xf["blockSize"])),
                          ("fs.block_count", str(xf["blockCount"])), ("fs.inode_count", str(xf["inodeCount"])),
                          ("fs.inode_size", str(xf["inodeSize"])), ("fs.features", xf["features"]),
                          ("statvfs.blocks", str(xf["statvfsBlocks"])), ("statvfs.files", str(xf["inodeCount"])),
                          ("loop.discard_max_bytes", "0"), ("image.hole_blocks", "0")):
            p.check(kv.get(key) == want, f"{rel}: {key} is '{kv.get(key)}', expected '{want}'")
        if phase == "post-cleanup":
            p.check(kv.get("mount.other_entries") == "none",
                    f"{rel}: mount.other_entries is '{kv.get('mount.other_entries')}': the N1 scratch tree must be gone")
        bl[phase] = kv
    reboot_at = iso(bl["post-reboot"]["_at"]) if "_at" in bl.get("post-reboot", {}) and ISO_RE.match(bl["post-reboot"]["_at"]) else None
    cleanup_at = iso(bl["post-cleanup"]["_at"]) if "_at" in bl.get("post-cleanup", {}) and ISO_RE.match(bl["post-cleanup"]["_at"]) else None
    p.check(reboot_at is not None, "identity/fs-baseline-post-reboot.txt: header 'at=' is not an ISO-8601 UTC instant")
    p.check(cleanup_at is not None, "identity/fs-baseline-post-cleanup.txt: header 'at=' is not an ISO-8601 UTC instant")

    d2 = b.text("identity/dumpe2fs-h.txt")
    if d2 is not None:
        for name, want in (("Filesystem UUID", xf["uuid"]), ("Filesystem features", xf["features"]),
                           ("Block count", str(xf["blockCount"])), ("Inode count", str(xf["inodeCount"])),
                           ("Block size", str(xf["blockSize"])), ("Inode size", str(xf["inodeSize"]))):
            p.check(dumpe2fs_field(d2, name) == want, f"identity/dumpe2fs-h.txt: {name} is '{dumpe2fs_field(d2, name)}', expected '{want}'")
        feats = (dumpe2fs_field(d2, "Filesystem features") or "").split()
        p.check("inline_data" not in feats and "bigalloc" not in feats, "identity/dumpe2fs-h.txt: inline_data or bigalloc is enabled")
    b.text("identity/itable-zeroed.txt")        # app verdict 2.1 (f): the explanation, committed
    uid_text = b.text("identity/minio-process-uid.txt")  # app verdict 2.1 (f): the MinIO process uid
    b.text("identity/upload-impl-identity.txt")
    uit = b.text("identity/upload-impl-identity.txt") or ""
    p.check(upload_version is None or upload_version in uit,
            f"identity/upload-impl-identity.txt does not name {upload_version} (the candidate's uploader)")
    p.check(cand_sha is None or cand_sha in uit, "identity/upload-impl-identity.txt does not name the candidate sourceSha")

    # --- production MinIO: qualification-check observation + raw docker/registry/admin outputs
    qc = b.json("identity/qualification-check-observed.json") or {}
    obs = qc.get("observed") or {}
    p.check(isinstance(obs, dict) and obs, "identity/qualification-check-observed.json has no 'observed' map")
    p.check(qc.get("normalization") == NORMALIZATION, f"identity/qualification-check-observed.json normalization is not {NORMALIZATION}")
    norm_text = qc.get("runtimeConfigNormalized")
    if p.check(isinstance(norm_text, str) and norm_text, "identity/qualification-check-observed.json has no runtimeConfigNormalized text"):
        h = "sha256:" + hashlib.sha256(norm_text.encode()).hexdigest()
        p.check(h == obs.get("minio.runtimeConfig.hash"),
                "identity/qualification-check-observed.json: sha256(runtimeConfigNormalized) differs from the observed hash")

    ci = b.json("identity/minio-container-inspect.json")
    ii = b.json("identity/minio-image-inspect.json")
    member = None
    if isinstance(ci, list) and ci and isinstance(ii, list) and ii:
        p.check(ci[0].get("Image") == ii[0].get("Id"), "minio-container-inspect Image is not minio-image-inspect Id")
        rds = [rd.split("@", 1)[1] for rd in (ii[0].get("RepoDigests") or []) if "@" in rd]
        member = rds[0] if rds else None
        p.check(member is not None, "identity/minio-image-inspect.json has no RepoDigests")
        p.check(len(set(rds)) <= 1, f"identity/minio-image-inspect.json RepoDigests disagree: {rds}")
        env = dict(e.split("=", 1) for e in (ci[0].get("Config") or {}).get("Env", []) if "=" in e)
        timeout_env = {k: v for k, v in env.items() if TIMEOUT_NAME.search(k)}
        timeout_flags = [a for a in (ci[0].get("Args") or []) if TIMEOUT_FLAG.search(a)]
        sc_env = sorted(k for k in env if k.startswith("MINIO_STORAGE_CLASS") or "INLINE" in k.upper())
    else:
        p.add("identity/minio-container-inspect.json and minio-image-inspect.json must be `docker inspect` arrays")
        timeout_env, timeout_flags, sc_env = None, None, None

    raw_path = b.path("identity/minio-imagetools-raw.json")
    index_digest, platform = None, None
    raw = b.json("identity/minio-imagetools-raw.json")
    if isinstance(raw, dict):
        index_digest = "sha256:" + sha256_file(raw_path)  # content addressing: the raw bytes ARE the index
        hits = [m for m in raw.get("manifests", []) if (m.get("platform") or {}).get("os") == "linux"
                and (m.get("platform") or {}).get("architecture") == "amd64"]
        p.check(len(hits) == 1, "identity/minio-imagetools-raw.json does not list exactly one linux/amd64 member")
        if hits:
            p.check(hits[0].get("digest") == member, f"the index lists linux/amd64 → {hits[0].get('digest')}, the running member is {member}")
            platform = "linux/amd64"

    mb = b.text("identity/minio-binary.txt") or ""
    msha = re.search(r"^([0-9a-f]{64})\s+\S*minio\s*$", mb, re.M)
    mver2 = re.search(r"minio version (RELEASE\.\S+) \(commit-id=([0-9a-f]{40})\)", mb)
    p.check(msha is not None, "identity/minio-binary.txt has no '<sha256>  /usr/bin/minio' line")
    p.check(mver2 is not None, "identity/minio-binary.txt has no 'minio version RELEASE… (commit-id=…)' line")
    ai = b.json("identity/minio-admin-info.json")
    si = server_info(ai["info"]) if isinstance(ai, dict) and isinstance(ai.get("info"), dict) else None
    p.check(si is not None, "identity/minio-admin-info.json is not `mc admin info --json` output")

    # One element, every source, and the FIXED value: any disagreement refuses.
    def agree(element, fixed, sources):
        vals = {src: v for src, v in sources.items()}
        bad = {src: v for src, v in vals.items() if v != fixed}
        p.check(not bad, f"identity element {element}: expected {fixed!r}, but " +
                ", ".join(f"{src} says {v!r}" for src, v in bad.items()))

    agree("minio.imageIndexDigest", xm["imageIndexDigest"], {"imagetools raw (sha256 of the bytes)": index_digest})
    agree("minio.platformMemberDigest", xm["platformMemberDigest"],
          {"image inspect RepoDigests": member, "qualification-check": obs.get("minio.platformMemberDigest")})
    agree("minio.release", xm["release"], {"minio --version": mver2.group(1) if mver2 else None,
                                           "mc admin info": si and si["release"], "qualification-check": obs.get("minio.release")})
    agree("minio.commitId", xm["commitId"], {"minio --version": mver2.group(2) if mver2 else None,
                                             "mc admin info": si and si["commitId"], "qualification-check": obs.get("minio.commitId")})
    agree("minio.binarySha256", xm["binarySha256"], {"sha256sum /usr/bin/minio": msha.group(1) if msha else None})
    agree("minio.mode", xm["mode"], {"mc admin info": si and si["mode"], "qualification-check": obs.get("minio.mode")})
    agree("minio.driveCount", str(xm["driveCount"]), {"mc admin info": si and str(si["driveCount"]),
                                                      "qualification-check": obs.get("minio.driveCount")})
    canon = lambda d: " ".join(f"{k}={v}" for k, v in sorted(d.items())) or "none"
    agree("minio.timeoutEnvironment", canon(xm["timeoutEnvironment"]),
          {"container Env": canon(timeout_env) if timeout_env is not None else None,
           "qualification-check": obs.get("minio.timeoutEnvironment")})
    agree("minio.timeoutCliFlags", " ".join(xm["timeoutCliFlags"]) or "none",
          {"container Args": (" ".join(timeout_flags) or "none") if timeout_flags is not None else None,
           "qualification-check": obs.get("minio.timeoutCliFlags")})
    agree("minio.storageClassInlineDefaults", xm["storageClassInlineDefaults"],
          {"container Env": ("release-defaults" if not sc_env else "custom:" + ",".join(sc_env)) if sc_env is not None else None,
           "qualification-check": obs.get("minio.storageClassInlineDefaults")})
    agree("minio.runtimeConfig.hash", xm["runtimeConfigHash"], {"qualification-check": obs.get("minio.runtimeConfig.hash")})
    agree("host.kernelRelease", X["kernelRelease"], {"qualification-check": obs.get("host.kernelRelease"),
                                                     "fs-baseline post-reboot": bl.get("post-reboot", {}).get("kernel")})
    agree("host.filesystemType", xf["type"], {"qualification-check": obs.get("host.filesystemType")})
    agree("host.mountOptions", xf["mountOptions"], {"qualification-check": obs.get("host.mountOptions")})
    agree("network.directPath", str(xn["directPath"]).lower(), {"qualification-check": obs.get("network.directPath")})
    agree("network.proxy", xn["proxy"], {"qualification-check": obs.get("network.proxy")})
    endpoint = ((qc.get("path") or {}).get("endpoint") or "") if isinstance(qc.get("path"), dict) else ""
    p.check(endpoint.startswith("http://") == (not xn["tls"]),
            f"qualification-check path.endpoint '{endpoint}' does not match tls={xn['tls']}")

    # --- §9a sweep on the qualification MinIO, recomputed from raw
    venue = b.json("qualification-9a/venue.json") or {}
    stats, reasons9a, combo, sweep_at = ({}, ["not evaluated"], None, None)
    try:
        stats, reasons9a, combo, sweep_at = evaluate_9a(b.path("qualification-9a"))
    except Refusal as e:
        p.add(str(e).strip().lstrip("✗ "))
    for r in reasons9a:
        p.add(f"§9a sweep is not PASS: {r}")
    if combo:
        agree("§9a kernel", X["kernelRelease"], {f"pass uname.txt #{i}": k for i, k in enumerate(sorted(combo["kernels"]))} or {"uname.txt": None})
        p.check(len(combo["kernels"]) == 1, f"§9a passes ran on different kernels: {sorted(combo['kernels'])}")
        p.check(combo["binaries"] == {xm["binarySha256"]}, f"§9a harness MinIO binary {sorted(combo['binaries'])} is not {xm['binarySha256']}")
        p.check(combo["hashes"] == {xm["runtimeConfigHash"]}, f"§9a harness MinIO runtime config {sorted(combo['hashes'])} is not {xm['runtimeConfigHash']}")
        p.check(all(m == (xf["type"], xf["mountOptions"]) for m in combo["mounts"]),
                f"§9a harness data mount {sorted(combo['mounts'])} is not {xf['type']} ({xf['mountOptions']})")
        for s in combo["servers"]:
            p.check(s["release"] == xm["release"] and s["commitId"] == xm["commitId"] and s["mode"] == xm["mode"]
                    and s["driveCount"] == xm["driveCount"], f"§9a harness MinIO server info {s} is not the expected combination")
    for key, want in (("filesystemUuid", xf["uuid"]), ("kernelRelease", X["kernelRelease"]),
                      ("minioPlatformMemberDigest", xm["platformMemberDigest"])):
        p.check(venue.get(key) == want, f"qualification-9a/venue.json {key} is {venue.get(key)!r}, expected {want!r}")
    mech = need(p, venue, "slowWMechanism", "str", "qualification-9a/venue.json")

    # --- E1-E11
    exps, ran_after, completions = {}, [], []
    for name in EXPERIMENTS:
        rel = f"experiments/{name}.json"
        e = b.json(rel)
        if e is None:
            continue
        exps[name] = e
        p.check(e.get("schema") == EXPERIMENT_SCHEMA, f"{rel}: schema is not {EXPERIMENT_SCHEMA}")
        p.check(e.get("experiment") == name, f"{rel}: experiment is {e.get('experiment')!r}, not {name}")
        p.check(e.get("verdict") == "PASS", f"{name} is not PASS (verdict {e.get('verdict')!r})")
        s, c = need(p, e, "startedAt", "iso", rel), need(p, e, "completedAt", "iso", rel)
        need(p, e, "criterion", "str", rel)
        carried = name == "E7" and e7_mode == "carried"
        if carried:
            p.check(e.get("venue") == "carried", f"{rel}: MANIFEST says E7 is carried, the experiment says venue {e.get('venue')!r}")
            need(p, e, "sourceEvidence", "str", rel)
        else:
            p.check(e.get("venue") == "final-filesystem", f"{rel}: venue is {e.get('venue')!r}, not final-filesystem")
            p.check(e.get("filesystemUuid") == xf["uuid"], f"{rel}: filesystemUuid {e.get('filesystemUuid')!r} is not {xf['uuid']}")
            p.check(e.get("kernelRelease") == X["kernelRelease"], f"{rel}: kernelRelease {e.get('kernelRelease')!r} is not {X['kernelRelease']}")
            if s and c:
                ran_after.append((name, iso(s)))
                completions.append(iso(c))
        if s and c:
            p.check(iso(c) >= iso(s), f"{rel}: completedAt is before startedAt")
        p.check(e.get("minioPlatformMemberDigest") == xm["platformMemberDigest"],
                f"{rel}: minioPlatformMemberDigest {e.get('minioPlatformMemberDigest')!r} is not {xm['platformMemberDigest']}")
        if name in APP_EXPERIMENTS:
            a = e.get("artifact") or {}
            p.check(a == cand, f"{rel}: artifact {a!r} is not the MANIFEST candidate {cand!r} (app verdict 2.1 R3/R4, D3)")
        raws = e.get("rawFiles")
        if p.check(isinstance(raws, list) and raws, f"{rel}: rawFiles must list the run output"):
            for r in raws:
                p.check(isinstance(r, str) and os.path.isfile(b.path(r)), f"{rel}: raw file {r!r} is not in the bundle")
    for name, started in ran_after:
        p.check(reboot_at is None or started >= reboot_at, f"{name} started {fmt(started)}, before the post-reboot baseline")
        p.check(cleanup_at is None or cleanup_at >= started, f"{name} started after the post-cleanup baseline")
    for done in completions:
        p.check(cleanup_at is None or cleanup_at >= done, f"an experiment completed {fmt(done)}, after the post-cleanup baseline")

    # O_max from E1-E3: the value their PASS criterion was evaluated against; every worst case within it.
    omax_vals, worst = set(), []
    for name in OMAX_EXPERIMENTS:
        m = (exps.get(name) or {}).get("measurements") or {}
        o = need(p, m, "oMaxBytes", "pos", f"experiments/{name}.json measurements")
        w = need(p, m, "worstObjectOverheadBytes", "int", f"experiments/{name}.json measurements")
        if o is not None:
            omax_vals.add(o)
        if o is not None and w is not None:
            p.check(w <= o, f"{name}: worst per-object overhead {w} exceeds the O_max {o} it was evaluated against")
            worst.append(w)
    p.check(len(omax_vals) <= 1, f"E1-E3 were evaluated against different O_max values {sorted(omax_vals)}")
    omax = omax_vals.pop() if len(omax_vals) == 1 else None
    if omax is not None:
        p.check(omax % xf["blockSize"] == 0 and omax >= 6 * xf["blockSize"],
                f"O_max {omax} is not a multiple of B covering at least 6 blocks (gate F would refuse every declaration)")
    m7 = (exps.get("E7") or {}).get("measurements") or {}
    metadata_inodes = need(p, m7, "metadataInodesMeasured", "pos", "experiments/E7.json measurements")

    # --- recordId and qualifiedAt (DERIVED), and the gate F evidence that must name the same record
    qualified_at = max([t for t in completions] + ([sweep_at] if sweep_at else []), default=None)
    record_id = f"production-amd64-{X['host']}-{qualified_at.strftime('%Y-%m-%d')}" if qualified_at else None
    if sweep_at:
        p.check(reboot_at is None or sweep_at >= reboot_at, "the §9a sweep ran before the post-reboot baseline")
    p.check(record_id is not None and man_rid == record_id,
            f"MANIFEST.json recordId {man_rid!r} is not the derived {record_id!r} (production-amd64-<host>-<date of qualifiedAt>)")
    fe = b.json("gate-f/filesystem-evidence.json") or {}
    p.check(fe.get("schema") == FS_EVIDENCE_SCHEMA, f"gate-f/filesystem-evidence.json schema is not {FS_EVIDENCE_SCHEMA}")
    p.check(fe.get("qualificationRecordId") == record_id,
            f"gate F evidence qualificationRecordId {fe.get('qualificationRecordId')!r} ≠ recordId {record_id!r} (app verdict 2.1 (d))")
    ffs = fe.get("filesystem") or {}
    for k in ("uuid", "type", "mountOptions", "mountSource"):
        p.check(ffs.get(k) == xf[k], f"gate F evidence filesystem.{k} {ffs.get(k)!r} is not {xf[k]!r}")
    st = fe.get("statvfs") or {}
    p.check(st.get("blocks") == xf["statvfsBlocks"] and st.get("files") == xf["inodeCount"] and st.get("frsize") == xf["blockSize"],
            f"gate F evidence statvfs {st} is not the expected geometry")
    p.check((fe.get("mount") or {}).get("otherDataOnMount") is False and fe.get("otherEntriesOnMount", []) == [],
            "gate F evidence: something other than MinIO data is on the mount (N1 cleanup incomplete)")
    p.check((fe.get("preallocation") or {}).get("holeBytes") == 0, "gate F evidence: preallocation.holeBytes is not 0")
    dcl = fe.get("declared") or {}
    p.check(omax is None or (isinstance(dcl.get("objectOverheadBytes"), int) and dcl["objectOverheadBytes"] >= omax),
            f"gate F evidence declares O = {dcl.get('objectOverheadBytes')}, below the qualified O_max {omax}")
    p.check(metadata_inodes is None or (isinstance(dcl.get("metadataBudgetInodes"), int) and dcl["metadataBudgetInodes"] >= metadata_inodes),
            f"gate F evidence declares an inode budget {dcl.get('metadataBudgetInodes')}, below the E7 measurement {metadata_inodes}")
    if fe.get("collectedAt") and ISO_RE.match(str(fe.get("collectedAt"))) and qualified_at:
        p.check(iso(fe["collectedAt"]) >= qualified_at, "gate F evidence was collected before the qualification completed")

    p.raise_if_any()

    # ------------------------------------------------------------------------------------------- the record
    date = qualified_at.strftime("%Y-%m-%d")
    evidence_ref = f"{QUAL_REL}/production-{date}/"
    bp = bl["post-reboot"]
    record = {
        "schemaVersion": 1,
        "recordId": record_id,
        "qualifiedAt": fmt(qualified_at),
        "minio": {
            "imageIndexDigest": index_digest,
            "platformMemberDigest": member,
            "platform": platform,
            "release": xm["release"],
            "commitId": xm["commitId"],
            "binarySha256": xm["binarySha256"],
            "mode": xm["mode"],
            "driveCount": xm["driveCount"],
            "timeoutEnvironment": timeout_env,
            "timeoutCliFlags": timeout_flags,
            "runtimeConfig": {"normalized": NORMALIZATION, "hash": obs["minio.runtimeConfig.hash"]},
            "storageClassInlineDefaults": obs["minio.storageClassInlineDefaults"],
            "storageClassInlineDefaultsBasis": "no MINIO_STORAGE_CLASS_* / inline variables in the container environment; "
                                               "single-drive MinIO exposes no storage_class admin subsystem (captured as "
                                               "<unknown-subsystem> in the hashed configuration)",
        },
        "host": {
            "kernelRelease": obs["host.kernelRelease"],
            "architecture": bp["arch"],
            "filesystemType": obs["host.filesystemType"],
            "mountOptions": obs["host.mountOptions"],
            "device": (f"{bp['mount.fstype']} (block size {bp['fs.block_size']}, {bp['fs.block_count']} blocks = "
                       f"{bp['fs.inode_count']} inodes, inode size {bp['fs.inode_size']}, features: {bp['fs.features']}) on "
                       f"{bp['mount.source']} backed by the preallocated image {bp['loop.backing']} ({bp['image.hole_blocks']} "
                       f"FIEMAP hole blocks, discard_max_bytes {bp['loop.discard_max_bytes']}), mounted {bp['mount.options']}"),
        },
        "network": {"directPath": obs["network.directPath"] == "true", "proxy": obs["network.proxy"], "tls": endpoint.startswith("https://")},
        "uploadImplementationVersion": upload_version,
        "uploadImplementationNote": (f"E5, E6, E8-E11 ran the application candidate {cand['sourceSha']} (api {cand['apiDigest']}, "
                                     f"ingestion {cand['ingestionDigest']}), whose FencedUploader declares {upload_version}; "
                                     "the §9a sweep and E1-E4/E7 ran the repository harness against MinIO"),
        "qualification": {
            "platformClass": "production-host",
            "platform": (f"{X['host']} itself ({bp['arch']}, kernel {obs['host.kernelRelease']}); qualification MinIO of member "
                         f"{member} with its data on the final filesystem {xf['uuid']}"),
            "evidenceReference": evidence_ref,
            "procedure": PROCEDURE,
            "scenarios": stats["scenarios"],
            "trials": stats["trials"],
            "lateCommits": stats["lateCommits"],
            "slowWExecuted": stats["slowTrials"] > 0,
            "slowWMechanism": mech,
            "slowWMaxCommitLagAfterCompletedWitnessS": stats["slowMaxCommitLagAfterWitnessS"],
            "cMaxSeconds": C_MAX_S,
            "experimentArtifact": dict(cand),
        },
        "filesystem": {
            "uuid": ffs["uuid"],
            "type": ffs["type"],
            "mountOptions": ffs["mountOptions"],
            "mountSource": ffs["mountSource"],
            "objectOverheadMaxBytes": omax,
            "metadataInodesMeasured": metadata_inodes,
            "metadataInodesBasis": ("E7 on the final filesystem" if e7_mode == "final-filesystem"
                                    else f"E7 carried under {e7['amendment']}"),
            "experiments": {n: exps[n]["verdict"] for n in EXPERIMENTS},
        },
        "enablementEligible": True,
        "ineligibilityReasons": [],
    }

    # --------------------------------------------------------------------------------------------- provenance
    PQ, PN, DV, CD = "PROD-QUAL", "PROD-NOW", "DERIVED", "CODE"
    ev = "evidence/"
    label("`recordId`", record_id, DV, f"`production-amd64-<host>-<date of qualifiedAt>`; equals `{ev}MANIFEST.json` and `{ev}gate-f/filesystem-evidence.json` qualificationRecordId")
    label("`qualifiedAt`", fmt(qualified_at), DV, f"latest of the §9a slow sweep start (`{ev}qualification-9a/orchestrator.log`) and every on-filesystem experiment's completedAt")
    label("`minio.imageIndexDigest`", index_digest, DV, f"sha256 of `{ev}identity/minio-imagetools-raw.json` (`docker buildx imagetools inspect --raw`), which lists linux/amd64 → the running member")
    label("`minio.platformMemberDigest`, `platform`", f"{member}, {platform}", PN, f"`{ev}identity/minio-image-inspect.json` RepoDigests = `{ev}identity/qualification-check-observed.json`")
    label("`minio.release`, `commitId`", f"{xm['release']}, {xm['commitId']}", PN, f"`{ev}identity/minio-binary.txt` = `{ev}identity/minio-admin-info.json` = qualification-check")
    label("`minio.binarySha256`", xm["binarySha256"], PN, f"`{ev}identity/minio-binary.txt`; the §9a harness binary is the same (`{ev}qualification-9a/*/minio-binary.sha256`)")
    label("`minio.mode`, `driveCount`", f"{xm['mode']}, {xm['driveCount']}", PN, f"`{ev}identity/minio-admin-info.json` (`mc admin info --json`)")
    label("`minio.timeoutEnvironment`, `timeoutCliFlags`", f"{json.dumps(timeout_env)}, {json.dumps(timeout_flags)}", PN, f"`{ev}identity/minio-container-inspect.json` Env/Args = qualification-check")
    label("`minio.runtimeConfig`", f"{NORMALIZATION}, {obs['minio.runtimeConfig.hash']}", PN, f"`{ev}identity/qualification-check-observed.json` (sha256 of runtimeConfigNormalized re-verified); the §9a harness MinIO hashes the same")
    label("`minio.storageClassInlineDefaults`", obs["minio.storageClassInlineDefaults"], PN, f"`{ev}identity/minio-container-inspect.json` (no storage-class/inline variables)")
    label("`host.kernelRelease`, `architecture`", f"{obs['host.kernelRelease']}, {bp['arch']}", PN, f"`{ev}identity/fs-baseline-post-reboot.txt` = qualification-check")
    label("`host.filesystemType`, `mountOptions`, `device`", f"{obs['host.filesystemType']}, {obs['host.mountOptions']}", PN, f"`{ev}identity/fs-baseline-post-reboot.txt`, `{ev}identity/dumpe2fs-h.txt`")
    label("`network.directPath`, `proxy`, `tls`", f"{record['network']['directPath']}, {record['network']['proxy']}, {record['network']['tls']}", PN, f"`{ev}identity/qualification-check-observed.json` (`path`)")
    label("`uploadImplementationVersion`", upload_version, CD, f"`StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION`; candidate named in `{ev}identity/upload-impl-identity.txt`")
    label("`qualification.platformClass`", "production-host", CD, "the class of a record qualified on the production host itself (ADR-035 §18 gate 7a)")
    label("`qualification.scenarios`, `trials`, `lateCommits`, `slowWExecuted`, `slowWMaxCommitLagAfterCompletedWitnessS`",
          f"{len(stats['scenarios'])} scenarios, {stats['trials']} trials, {stats['lateCommits']} late, slow-W {stats['slowTrials']} trials, max lag {stats['slowMaxCommitLagAfterWitnessS']} s",
          PQ, f"recomputed from `{ev}qualification-9a/{{main,v3,slow}}/` raw results and sweeps (staging gen-record.py criteria)")
    label("`qualification.slowWMechanism`", mech, PQ, f"`{ev}qualification-9a/venue.json`; every slow trial's slow_mode is dm")
    label("`qualification.cMaxSeconds`, `procedure`", f"{C_MAX_S}", CD, "ADR-035 §9 C_max; the repository procedure")
    label("`qualification.experimentArtifact`", json.dumps(cand), PQ, f"`{ev}MANIFEST.json`; every app-level experiment names it")
    label("`filesystem.uuid`, `type`, `mountOptions`, `mountSource`", f"{ffs['uuid']}, {ffs['type']}, {ffs['mountOptions']}, {ffs['mountSource']}", PN,
          f"`{ev}gate-f/filesystem-evidence.json` = `{ev}identity/fs-baseline-post-*.txt` = `{ev}identity/dumpe2fs-h.txt`")
    label("`filesystem.objectOverheadMaxBytes`", f"{omax} (worst measured {max(worst)})", PQ, f"`{ev}experiments/E1.json`, `E2.json`, `E3.json` measurements: the O_max their criterion was evaluated against")
    label("`filesystem.metadataInodesMeasured`", str(metadata_inodes),
          PQ if e7_mode == "final-filesystem" else "CARRIED-AMENDMENT",
          f"`{ev}experiments/E7.json`" + ("" if e7_mode == "final-filesystem" else f" (source {exps['E7']['sourceEvidence']}; authority: {e7['amendment']})"))
    for n in EXPERIMENTS:
        e = exps[n]
        carried = n == "E7" and e7_mode == "carried"
        label(f"`filesystem.experiments.{n}`", e["verdict"], "CARRIED-AMENDMENT" if carried else PQ,
              f"`{ev}experiments/{n}.json` ({e['startedAt']} → {e['completedAt']}" + (f", artifact {e['artifact']['sourceSha'][:12]}" if n in APP_EXPERIMENTS else "") + ")")
    label("`enablementEligible`, `ineligibilityReasons`", "true, []", DV, "every check of scripts/build-qualification-record.py passed")

    prov = [f"# Production-host qualification record `{record_id}` — provenance",
            "",
            "Generated by `scripts/build-qualification-record.py` from the Ops N1 evidence bundle copied byte for byte under",
            "`evidence/` (its `SHA256SUMS` verified before anything was read). Nothing in `record.json` was typed by hand.",
            "Shipping it is a separate step (`docs/adr/0035-benchmark/qualification/RECORD-CUT.md`).",
            "",
            "Labels: **PROD-QUAL** measured on the final production filesystem after the reboot, in the qualification window,",
            "naming the artifact; **PROD-NOW** observed read-only on the production host after the reboot; **DERIVED** computed",
            "from bundle files by the stated rule; **CODE** a constant of the artifact or the ADR;",
            "**CARRIED-AMENDMENT** carried from another corpus under a dated, owner-accepted ADR-035 amendment. No member is LAB",
            "or POST-REBOOT.",
            "",
            "| Record member | Value | Label | Evidence |",
            "|---|---|---|---|"]
    prov += [f"| {m} | `{v}` | {lab} | {e} |" for m, v, lab, e in labels]
    prov += ["",
             "## Host facts committed with the evidence (app verdict 2.1 (f))",
             "",
             f"- MinIO process uid: `{uid_text.strip().splitlines()[0]}` (`evidence/identity/minio-process-uid.txt`).",
             "- `itable_zeroed`: see `evidence/identity/itable-zeroed.txt`.",
             f"- Post-reboot baseline {bl['post-reboot']['_at']}, post-cleanup baseline {bl['post-cleanup']['_at']}; "
             f"gate F evidence collected {fe.get('collectedAt')}.",
             ""]
    return record, "\n".join(prov), date


def write_out(record, prov, date, bundle, out_root):
    out = os.path.join(out_root, f"production-{date}")
    if os.path.exists(out):
        raise Refusal(f"  ✗ {out} already exists; a record directory is never overwritten")
    os.makedirs(out)
    shutil.copytree(bundle, os.path.join(out, "evidence"))
    with open(os.path.join(out, "record.json"), "w", encoding="utf-8") as f:
        f.write(json.dumps(record, indent=2, ensure_ascii=False) + "\n")
    with open(os.path.join(out, "PROVENANCE.md"), "w", encoding="utf-8") as f:
        f.write(prov)
    with open(os.path.join(out, "SHA256SUMS"), "w", encoding="utf-8") as f:
        for n in ("PROVENANCE.md", "record.json"):
            f.write(f"{sha256_file(os.path.join(out, n))}  {n}\n")
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--bundle")
    ap.add_argument("--expected", help=f"the FIXED values (default {EXPECTED_REL})")
    ap.add_argument("--out-root", help=f"default <repo>/{QUAL_REL}")
    ap.add_argument("--repo", default=REPO_DEFAULT)
    ap.add_argument("--check", action="store_true", help="verify and print the record; write nothing")
    ap.add_argument("--evaluate-9a", metavar="DIR", help="recompute a §9a sweep verdict from raw harness output and exit")
    a = ap.parse_args()
    try:
        if a.evaluate_9a:
            stats, reasons, combo, at = evaluate_9a(a.evaluate_9a)
            print(json.dumps({"verdict": "PASS" if not reasons else "FAIL", "reasons": reasons, **stats,
                              "kernels": sorted(combo["kernels"]), "binaries": sorted(combo["binaries"]),
                              "runtimeConfigHashes": sorted(combo["hashes"]), "mounts": sorted(map(list, combo["mounts"])),
                              "servers": combo["servers"], "slowSweepStart": fmt(at) if at else None}, indent=1))
            return 0 if not reasons else 1
        if not a.bundle:
            ap.print_usage(sys.stderr)
            return 2
        if not os.path.isdir(a.bundle):
            print(f"error: bundle {a.bundle} is not a directory", file=sys.stderr)
            return 2
        expected = json.load(open(a.expected or os.path.join(a.repo, EXPECTED_REL), encoding="utf-8"))
        if expected.get("schema") != EXPECTED_SCHEMA:
            print(f"error: --expected is not {EXPECTED_SCHEMA}", file=sys.stderr)
            return 2
        n = verify_sums(a.bundle)
        record, prov, date = build(a.bundle, expected, a.repo)
        if a.check:
            print(json.dumps(record, indent=2, ensure_ascii=False))
            print(f"build-qualification-record: OK ({n} files verified), recordId {record['recordId']} (--check: nothing written)", file=sys.stderr)
            return 0
        out = write_out(record, prov, date, a.bundle, a.out_root or os.path.join(a.repo, QUAL_REL))
        print(f"build-qualification-record: OK ({n} files verified) → {out}/record.json, recordId {record['recordId']}")
        return 0
    except Refusal as e:
        print("build-qualification-record: REFUSED — no record written", file=sys.stderr)
        print(str(e), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
