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
  - experiments/A1iii.json (A1 (iii), the bounded measurement) is missing, or is not PASS once re-judged from the
    harness's raw statvfs pairs: Δ(f_files − f_ffree) <= Δ(f_blocks − f_bfree) per phase (0B, 1B, 2-512B), per layout
    within each phase, per layout over the run and overall; >= 20 000 objects, >= 2 000 in each of 0B and 1B; every
    object in its phase's class (the 0 B / 1 B attachment messages' raw.eml deferred to the 2-512 B phase);
  - two sources of one identity element disagree with each other or with the FIXED values (--expected);
  - the recordId derived from the evidence differs from MANIFEST.json or from the gate F evidence's
    qualificationRecordId;
  - E7 is carried from another corpus without MANIFEST.e7.mode = "carried" and an amendment reference that is
    present in ADR-035's text (W3 question A5: both outcomes are supported, neither is silent);
  - a metadata figure (E7 inodes; E2 and E7 bytes; the gate F evidence) is not counted by the one definition
    (scopes minio-sys and minio-sys+unattributed), or a declared budget is below its measurement;
  - a raw (vi) census listing is anywhere in the bundle (by path, or by `find -printf` content): it names object keys
    and stays with Ops off-repo; the bundle carries census/summary.json only, and (vi) is NOT RUN unless the listing
    is verified at cut (--census-raw, sha256 and recomputed counts) or the summary carries an Ops attestation bound to
    its sha256 and dated no earlier than the census it attests (attestation.at >= the top-level takenAt);
  - the §9a venue is neither the final filesystem nor Ops' own loop image on / under dm-delay, labelled
    "loop-image-on-root + dm-delay(slow-W)" and equal to the final filesystem in type, features, block and inode size
    (verdict R3).
Nothing measured is typed by hand: every record value is read from a bundle file, derived from bundle files by a
rule stated in PROVENANCE.md, or is a constant of the artifact (CODE). Exit 2 is a usage error.

Usage:
  scripts/build-qualification-record.py --bundle <dir> [--expected <json>] [--out-root <dir>] [--repo <dir>] [--check]
                                         [--census-raw <off-repo listing>]
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
# ONE metadata-inode counting definition for E7, the record, the declaration and gate F: the inodes under
# <data>/.minio.sys only. Bucket directories are per-object inodes charged by i_max = 6 (ADR-035 annex §3.5).
METADATA_INODES_SCOPE = "minio-sys"
# The same count without .minio.sys/tmp/.trash's contents: accepted ONLY with the Ops fs-monitor's scope-independent
# inode-headroom latch PROVEN, not declared: E10's drill recorded that latch firing (measurements.inodeHeadroomLatch
# "PASS") with the fs-monitor build it ran (measurements.fsMonitorSha256), and the gate F evidence names the SAME
# fs-monitor build (monitor.fsMonitorSha256) and states it armed (monitor.inodeHeadroomLatchArmed, additional only).
METADATA_INODES_SCOPE_EXCL_TRASH = "minio-sys-excl-trash"
METADATA_INODES_SCOPES = (METADATA_INODES_SCOPE, METADATA_INODES_SCOPE_EXCL_TRASH)
# ONE metadata-bytes counting definition for E2, E7, the record, the declaration (M), gate F and the fs-monitor:
# .minio.sys without .trash, plus every allocation outside .minio.sys that no object is charged for
# (scripts/adr035-metadata-accounting.py; ADR-035 amendment note of 2026-11-05). Per-object directories are inside O_max (§3.1).
METADATA_BYTES_SCOPE = "minio-sys+unattributed"
METADATA_BYTES_EXPERIMENTS = ("E2", "E7")  # E2: after the partial drain; E7: the peak over 24 h
# Verdict R3: the §9a sweep runs on the final filesystem, or on Ops' own loop image on / under dm-delay (the slow-W
# mechanism needs a device-mapper target it can delay), labelled exactly so in venue.json and identical in every
# identity element but the uuid and the geometry. Nothing else may differ.
NINE_A_OWN_IMAGE = "loop-image-on-root + dm-delay(slow-W)"
C_MAX_S = 15 * 60  # ADR-035 §9: C_max = 15 min, measured from the writer's RST
SLOW_W_MIN_TRIALS = 18  # plan-slow.json
SWEEP_MIN_GAP_MIN = 17
# The Ops qualification-check normalization (infinity-core infra/testinbox/storage/qualification-check.py).
NORMALIZATION = "ti-qc-runtime-config-v1"
SUBSYSTEMS = ("api", "drive", "storage_class", "scanner")
TIMEOUT_NAME = re.compile(r"^MINIO_.*(TIMEOUT|DEADLINE|KEEP_?ALIVE)", re.I)
TIMEOUT_FLAG = re.compile(r"timeout|deadline|keep-?alive", re.I)
SHA256_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
HEX64_RE = re.compile(r"^[0-9a-f]{64}$")
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
# --------------------------------------------------------------------------- A1 (vi) inode census (C2), off-repo listing
# The raw census listing (`find <mount> -xdev -printf '%i %n %b %s %y %p\n'`) names production object keys, and with
# participants it would carry tenant workspace/inbox identifiers. It NEVER enters the bundle, so it never enters the
# repository: the committed bundle carries census/summary.json only (counts, the reconciliation, the C3 steps, and the
# listing's sha256 and size). The listing stays with Ops off-repo (0700) and is verified at cut time with --census-raw;
# without that, the summary must carry an Ops attestation bound to the listing's sha256, and the attestation may not
# predate the census it attests (attestation.at >= the top-level takenAt; equal is accepted; agreed with Ops, #12 1653).
# Neither -> (vi) NOT RUN.
CENSUS_SUMMARY = "census/summary.json"
CENSUS_SCHEMA = "testinbox.n1-census-summary/1"
# C2 says (vi) runs before any deletion or cleanup of the (iii), E2 and E7 objects. A census taken after an
# experiment's own defined drain (E2's 99 % teardown) is a LITERAL DEVIATION: the record discloses it in these exact
# words and never states C2 as satisfied for that experiment (#124 comments 6675182070, 6675203675).
C2_REQUIRED_CENSUS = ("E2",)
C2_DEVIATION = "literal-deviation-disclosed"
C2_SATISFIED = "satisfied"
DRAIN_DISCLOSURE = ("(vi) for {exp} was taken after its defined drain; no census preceded it. The drain's trash was "
                    "largely purged before the census ({n} entries remained under `.trash`), so most of it was never counted.")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
RAW_LISTING_PATH = re.compile(r"(^|/)census/(?!summary\.json$)|(^|/)[^/]*census[^/]*(raw|listing|find)[^/]*$", re.I)
RAW_LISTING_LINE = re.compile(rb"^\d+ \d+ \d+ \d+ [fdlpscbD] /", re.M)
RAW_SNIFF_BYTES = 1 << 16


def refuse_raw_listings(bundle):
    """No raw census listing (by path or by content) may sit in the bundle: everything in it is committed."""
    bad = []
    for root, _, files in os.walk(bundle):
        for f in files:
            rel = os.path.relpath(os.path.join(root, f), bundle)
            if rel == "SHA256SUMS":
                continue
            if RAW_LISTING_PATH.search(rel):
                bad.append(f"{rel} is a raw census listing path: the listing stays with Ops off-repo, never in the bundle")
                continue
            with open(os.path.join(root, f), "rb") as fh:
                head = fh.read(RAW_SNIFF_BYTES)
            if len(RAW_LISTING_LINE.findall(head)) >= 3:
                bad.append(f"{rel} contains `find -printf '%i %n %b %s %y %p'` lines: a raw census listing (object-key "
                           "paths) must never be committed")
    if bad:
        raise Refusal("\n".join(f"  ✗ {x}" for x in bad))


def census_from_listing(path, first_inode):
    """(sha256, bytes, distinct inodes, blockless inodes, {tmp, trash, multipart}, listed inodes numbered below
    `first_inode`) recomputed from the raw listing."""
    inodes, blockless, entries = set(), set(), {"tmp": 0, "trash": 0, "multipart": 0}
    with open(path, "rb") as f:
        data = f.read()
    for n, line in enumerate(data.decode("utf-8", "surrogateescape").splitlines(), 1):
        parts = line.split(" ", 5)
        if len(parts) != 6 or not all(x.isdigit() for x in parts[:4]):
            raise Refusal(f"  ✗ --census-raw line {n} is not `%i %n %b %s %y %p`")
        ino, blocks, p = parts[0], int(parts[2]), parts[5]
        inodes.add(ino)
        if blocks == 0:
            blockless.add(ino)
        if "/.minio.sys/tmp/.trash/" in p:
            entries["trash"] += 1
        elif "/.minio.sys/tmp/" in p and not p.endswith("/.minio.sys/tmp/.trash"):
            entries["tmp"] += 1  # entries inside tmp, neither .trash itself nor anything under it
        elif "/.minio.sys/multipart/" in p:
            entries["multipart"] += 1
    listed_below = sum(1 for i in inodes if int(i) < first_inode)  # normally 1: the root, inode 2
    return hashlib.sha256(data).hexdigest(), len(data), len(inodes), len(blockless), entries, listed_below


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


# ------------------------------------------------------------ A1 (iii) bounded measurement, re-judged from raw statvfs
# #124 6675379211 as amended by 6675394064 (binding), built by Ops as the `a1iii` mode of the N1 harness (#124 6698831482
# §1-§2) and bundled as experiments/A1iii.json with the harness output in its rawFiles. The JSON's verdict and figures
# are never used: every ratio is recomputed here from the raw statvfs pairs, every count from the segment records.
A1III = "A1iii"
A1III_REL = f"experiments/{A1III}.json"
A1III_PHASES = ("0B", "1B", "2-512B")  # one phase per C1 class, in this order
A1III_CLASS = {"0B": (0, 0), "1B": (1, 1), "2-512B": (2, 512)}
A1III_MIN_TOTAL, A1III_MIN_SMALL = 20000, 2000  # >= 20 000 objects, >= 2 000 in each of 0B and 1B
A1III_MAX_ATTACHMENTS = 8
A1III_LAYOUTS = ("attachments", "single")
# The harness's segment roles (e1e3e4.py a1iii_plan). Anything else is refused: a role says where a raw.eml went.
A1III_ROLE_SINGLE = "raw.eml"                                          # single-object message, every phase
A1III_ROLE_WITH_RAW = "raw.eml+attachments"                            # attachment message, 2-512B only
A1III_ROLE_DEFERRING = "attachments (raw.eml deferred)"                # attachment message, 0B / 1B only
A1III_ROLE_DEFERRED = "deferred raw.eml (0 B / 1 B attachment messages)"  # their raw.eml, 2-512B only
A1III_STATVFS = ("files", "ffree", "blocks", "bfree", "frsize")
# The harness's record fields, exactly as Ops gave them (#124 6700351383 item 4); a later field is additive only.
A1III_FIELDS = {
    "segment": ("record", "phase", "size", "layout", "role", "objects", "messages", "inboxes", "attachmentsPerMessage",
                "putFailures", "failureSample", "before", "after", "dInodes", "dBlocks", "ratio"),
    "phase": ("record", "phase", "description", "objects", "before", "after", "dInodes", "dBlocks", "ratio"),
    "summary": ("record", "experiment", "lab", "startedAt", "completedAt", "kernel", "bootId", "workspace", "population",
                "before", "after", "perPhase", "perPhaseLayout", "perLayout", "perSize", "overall", "putFailures",
                "problems", "PASS"),
}
# Every phase record's description states the deferred raw.eml reading the app accepted (#124 6699827210 (1)), verbatim.
A1III_DESCRIPTION = ("for attachment-layout messages in the 0 B and 1 B phases, the message's own raw.eml is written "
                     "and accounted in the 2–512 B phase")
# The A1 (vi) census taken straight after A1 (iii): its own censuses entry, 0-15 min after A1iii completed (C2).
A1III_CENSUS_MAX_LAG_S = 15 * 60
# The app team's YES to Ops' reading of amendment (b) (#124 6699827210), quoted by the record.
A1III_DEFERRED_RULE = ("deferred raw.eml: the raw.eml of each 0 B / 1 B attachment-layout message is written and "
                       "accounted in the 2-512 B phase, so every object stays inside its phase's class")


def a1iii_delta(b, a):
    """Δ(f_files − f_ffree) and Δ(f_blocks − f_bfree) (blocks of f_frsize) between two raw statvfs samples."""
    di = (a["files"] - a["ffree"]) - (b["files"] - b["ffree"])
    db = (a["blocks"] - a["bfree"]) - (b["blocks"] - b["bfree"])
    return {"dInodes": di, "dBlocks": db, "ratio": round(di / db, 6) if db > 0 else None}


def a1iii_sum(ds):
    di, db = sum(d["dInodes"] for d in ds), sum(d["dBlocks"] for d in ds)
    return {"dInodes": di, "dBlocks": db, "ratio": round(di / db, 6) if db > 0 else None}


def a1iii_ok(d):
    """Ratio <= 1, judged on the integers. Δblocks <= 0 proves nothing and fails."""
    return d["dBlocks"] > 0 and d["dInodes"] <= d["dBlocks"]


def judge_a1iii(recs, geometry):
    """Re-judge the e1e3e4.py `a1iii` output (a JSON list of segment, phase and summary records) from its raw statvfs
    pairs. `geometry` is the final filesystem's {files, blocks, frsize}: every sample must be of that filesystem.
    Returns (result, reasons); result is None when the output cannot be judged at all."""
    def is_int(v):
        return isinstance(v, int) and not isinstance(v, bool)

    if not isinstance(recs, list) or not all(isinstance(r, dict) for r in recs):
        return None, ["the A1iii harness output is not a list of records"]
    summ = [r for r in recs if r.get("record") == "summary"]
    phases = [r for r in recs if r.get("record") == "phase"]
    segs = [r for r in recs if r.get("record") == "segment"]
    if len(summ) != 1 or not phases or not segs:
        return None, [f"the A1iii harness output has {len(summ)} summary, {len(phases)} phase and {len(segs)} segment "
                      "records: one summary and both kinds of measurement are required (run incomplete)"]
    summ = summ[0]
    why = []
    # Structure first: nothing below is judged on a missing or mistyped field.
    for kind, rs in (("summary", [summ]), ("phase", phases), ("segment", segs)):
        for i, r in enumerate(rs):
            missing = [k for k in A1III_FIELDS[kind] if k not in r]
            if missing:
                why.append(f"{kind} record {i}: missing {missing} (the harness writes {list(A1III_FIELDS[kind])})")
    names = [ph.get("phase") for ph in phases]
    if names != list(A1III_PHASES):
        why.append(f"phases {names}, expected {list(A1III_PHASES)} (one per C1 class, in order)")
    for i, ph in enumerate(phases):
        if not (is_int(ph.get("objects")) and ph["objects"] >= 0):
            why.append(f"phase record {i}: objects {ph.get('objects')!r} is not a count")
        if not (isinstance(ph.get("description"), str) and A1III_DESCRIPTION in ph["description"]):
            why.append(f"phase record {i} ({ph.get('phase')}): its description does not state, verbatim, "
                       f"\"{A1III_DESCRIPTION}\"")
    for i, s in enumerate(segs):
        apm = s.get("attachmentsPerMessage")
        if not (s.get("phase") in A1III_CLASS and s.get("layout") in A1III_LAYOUTS and isinstance(s.get("role"), str)
                and is_int(s.get("size")) and is_int(s.get("objects")) and s["objects"] > 0
                and is_int(s.get("messages")) and s["messages"] >= 0 and is_int(s.get("putFailures"))
                and isinstance(apm, list) and all(is_int(k) for k in apm)):
            why.append(f"segment record {i}: needs phase (one of {list(A1III_CLASS)}), layout (one of {list(A1III_LAYOUTS)}), "
                       "role, size, objects > 0, messages, putFailures and attachmentsPerMessage")
    samples = [(f"phase {ph.get('phase')} {k}", ph.get(k)) for ph in phases for k in ("before", "after")]
    samples += [(f"segment {s.get('phase')}/{s.get('size')} B/{s.get('layout')} {k}", s.get(k)) for s in segs for k in ("before", "after")]
    samples += [(f"summary {k}", summ.get(k)) for k in ("before", "after")]
    for where, sv in samples:
        if not (isinstance(sv, dict) and all(is_int(sv.get(k)) for k in A1III_STATVFS)
                and isinstance(sv.get("at"), str) and ISO_RE.match(sv["at"])):
            why.append(f"{where}: not a raw statvfs sample (integer {', '.join(A1III_STATVFS)} and an ISO-8601 UTC 'at')")
    if why:
        return None, why
    # Every sample is of the final filesystem (statvfs geometry), so no ratio can come from another one.
    for where, sv in samples:
        for k, want in geometry.items():
            if sv[k] != want:
                why.append(f"{where}: f_{k} {sv[k]} is not the final filesystem's {want}")

    # The population: every object in its phase's class; the deferred raw.eml rule; the counts.
    deferring_messages, deferred_objects, multi = 0, 0, 0
    for s in segs:
        ph, where = s["phase"], f"segment {s['phase']}/{s['size']} B/{s['layout']}"
        lo, hi = A1III_CLASS[ph]
        if not lo <= s["size"] <= hi:
            why.append(f"{where}: {s['objects']} objects of {s['size']} B, outside the phase's class {lo}..{hi} B")
        apm, n, m = s["attachmentsPerMessage"], s["objects"], s["messages"]
        if any(not 1 <= k <= A1III_MAX_ATTACHMENTS for k in apm):
            why.append(f"{where}: a message outside 1..{A1III_MAX_ATTACHMENTS} attachments")
        multi += sum(1 for k in apm if k >= 2)
        small = ph != "2-512B"
        if s["layout"] == "single":
            if not (s["role"] == A1III_ROLE_SINGLE and apm == [] and n == m):
                why.append(f"{where}: role {s['role']!r}, {m} messages for {n} objects: a single-object message is one raw.eml")
        elif s["role"] == A1III_ROLE_DEFERRING and small:
            deferring_messages += m
            if not (len(apm) == m and sum(apm) == n):
                why.append(f"{where}: {n} objects are not exactly the {sum(apm)} attachments of its {m} messages "
                           f"({A1III_DEFERRED_RULE})")
        elif s["role"] == A1III_ROLE_WITH_RAW and not small:
            if not (len(apm) == m and m + sum(apm) == n):
                why.append(f"{where}: {n} objects are not the {m} raw.eml + {sum(apm)} attachments of its messages")
        elif s["role"] == A1III_ROLE_DEFERRED and not small:
            deferred_objects += n
            if not (m == 0 and apm == []):
                why.append(f"{where}: a deferred raw.eml segment carries messages or attachments")
        else:
            why.append(f"{where}: role {s['role']!r} is not allowed in phase {ph} ({A1III_DEFERRED_RULE})")
    if deferred_objects != deferring_messages:
        why.append(f"{deferred_objects} deferred raw.eml written in the 2-512B phase for {deferring_messages} 0 B / 1 B "
                   f"attachment messages ({A1III_DEFERRED_RULE})")
    if multi < 1:
        why.append("no message with several attachments")
    failures = sum(s["putFailures"] for s in segs)
    if failures:
        why.append(f"{failures} PUTs failed: the population is incomplete")
    objects = {n: sum(s["objects"] for s in segs if s["phase"] == n) for n in A1III_PHASES}
    for ph in phases:
        if ph["phase"] in objects and ph["objects"] != objects[ph["phase"]]:
            why.append(f"phase {ph['phase']}: its object count {ph['objects']} differs from its segments' {objects[ph['phase']]}")
    total = sum(objects.values())
    if total < A1III_MIN_TOTAL:
        why.append(f"{total} objects < {A1III_MIN_TOTAL}")
    for n in ("0B", "1B"):
        if objects[n] < A1III_MIN_SMALL:
            why.append(f"phase {n}: {objects[n]} objects < {A1III_MIN_SMALL}")
    layouts = {n: sorted({s["layout"] for s in segs if s["phase"] == n}) for n in A1III_PHASES}
    for n, ls in layouts.items():
        if ls != sorted(A1III_LAYOUTS):
            why.append(f"phase {n}: layouts {ls}, not single-object messages + attachments")

    # The measurement windows: each segment inside its phase's settled pair; the summary pair is the run's.
    window = {ph["phase"]: (iso(ph["before"]["at"]), iso(ph["after"]["at"])) for ph in phases}
    for s in segs:
        b, a = iso(s["before"]["at"]), iso(s["after"]["at"])
        lo, hi = window.get(s["phase"], (None, None))
        if lo is None or not (lo <= b <= a <= hi):
            why.append(f"segment {s['phase']}/{s['size']} B/{s['layout']}: its statvfs pair is not inside its phase's")
    if summ["before"] != phases[0]["before"] or summ["after"] != phases[-1]["after"]:
        why.append("the summary's statvfs pair is not the first phase's before / the last phase's after")

    # The ratios, from the raw pairs: every phase, every layout in each phase, every layout over the run, overall.
    per_phase = {ph["phase"]: a1iii_delta(ph["before"], ph["after"]) for ph in phases}
    sd = [dict(s, **a1iii_delta(s["before"], s["after"])) for s in segs]
    per_phase_layout = {n: {l: a1iii_sum([s for s in sd if s["phase"] == n and s["layout"] == l]) for l in ls}
                        for n, ls in layouts.items()}
    per_layout = {l: a1iii_sum([s for s in sd if s["layout"] == l]) for l in sorted({s["layout"] for s in sd})}
    overall = a1iii_delta(summ["before"], summ["after"])
    judged = ([(f"phase {n}", d) for n, d in per_phase.items()]
              + [(f"phase {n} layout {l}", d) for n, ls in per_phase_layout.items() for l, d in ls.items()]
              + [(f"layout {l} over the run", d) for l, d in per_layout.items()] + [("overall", overall)])
    for where, d in judged:
        if not a1iii_ok(d):
            why.append(f"{where}: Δinodes {d['dInodes']} > Δblocks {d['dBlocks']} (or Δblocks <= 0): ratio > 1, "
                       "recomputed from the raw statvfs pairs")
    return {"startedAt": summ.get("startedAt"), "completedAt": summ.get("completedAt"), "kernel": summ.get("kernel"),
            "bootId": summ.get("bootId"), "harnessPass": summ.get("PASS"), "harnessProblems": summ.get("problems"),
            "experiment": summ.get("experiment"), "objects": total, "objectsPerPhase": objects,
            "ratioPerPhase": per_phase, "ratioPerPhaseLayout": per_phase_layout, "ratioPerLayout": per_layout,
            "ratioOverall": overall, "sampleTimes": sorted({sv["at"] for _, sv in samples})}, why


# ---------------------------------------------------------------------------------------------------- the build
def build(bundle_dir, expected, repo, census_raw=None):
    p = Problems()
    b = Bundle(bundle_dir, p)
    X = expected
    xm, xf, xn, xd = X["minio"], X["filesystem"], X["network"], X["declared"]
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
    # R3: the venue is the final filesystem, or Ops' own loop image on / under dm-delay, explicitly labelled, whose
    # filesystem has the final filesystem's type, features, block size and inode size (read from its dumpe2fs -h).
    venue_label, venue_uuid = venue.get("9a"), venue.get("filesystemUuid")
    own_image = False
    if venue_uuid == xf["uuid"]:
        p.check(venue_label in (None, "final-filesystem"),
                f"qualification-9a/venue.json names the final filesystem {xf['uuid']} but labels the venue {venue_label!r}")
    elif venue_label == NINE_A_OWN_IMAGE:
        own_image = True
        p.check(isinstance(venue_uuid, str) and re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", venue_uuid),
                f"qualification-9a/venue.json filesystemUuid {venue_uuid!r} is not a filesystem uuid")
        vd = b.text("qualification-9a/venue-dumpe2fs-h.txt")
        if vd is not None:
            p.check(dumpe2fs_field(vd, "Filesystem UUID") == venue_uuid,
                    f"qualification-9a/venue-dumpe2fs-h.txt: Filesystem UUID is '{dumpe2fs_field(vd, 'Filesystem UUID')}', "
                    f"venue.json says {venue_uuid!r}")
            for name, want in (("Filesystem features", xf["features"]), ("Block size", str(xf["blockSize"])),
                               ("Inode size", str(xf["inodeSize"]))):
                p.check(dumpe2fs_field(vd, name) == want,
                        f"qualification-9a/venue-dumpe2fs-h.txt: {name} is '{dumpe2fs_field(vd, name)}', expected the final "
                        f"filesystem's '{want}' (R3: only the uuid and the geometry may differ)")
    else:
        p.add(f"qualification-9a/venue.json filesystemUuid {venue_uuid!r} is not the final filesystem {xf['uuid']!r}, and "
              f"its 9a label is {venue_label!r}, not {NINE_A_OWN_IMAGE!r} (verdict R3: only Ops' own loop image on / "
              "under dm-delay, explicitly labelled, may differ)")
    for key, want in (("kernelRelease", X["kernelRelease"]), ("minioPlatformMemberDigest", xm["platformMemberDigest"])):
        p.check(venue.get(key) == want, f"qualification-9a/venue.json {key} is {venue.get(key)!r}, expected {want!r}")
    mech = need(p, venue, "slowWMechanism", "str", "qualification-9a/venue.json")

    # --- E1-E11, then A1 (iii): the same checks for every on-filesystem experiment file
    exps, ran_after, completions = {}, [], []

    def experiment_file(name, rel, e, carried):
        p.check(e.get("schema") == EXPERIMENT_SCHEMA, f"{rel}: schema is not {EXPERIMENT_SCHEMA}")
        p.check(e.get("experiment") == name, f"{rel}: experiment is {e.get('experiment')!r}, not {name}")
        p.check(e.get("verdict") == "PASS", f"{name} is not PASS (verdict {e.get('verdict')!r})")
        # A PASS that also lists problems contradicts itself (#124 6672561295 Q9): refuse rather than pick one.
        probs = e.get("problems")
        p.check(not (e.get("verdict") == "PASS" and probs not in (None, [], {}, "")),
                f"{rel}: verdict PASS but 'problems' is non-empty ({probs!r})")
        # One file, one boot: a bootId holding several values (a list, or a per-window map such as the combined E9
        # ALL+OFF file) must hold one value only (the N1 condition: running kernel, boot id unchanged).
        def _flat(v):
            if isinstance(v, dict):
                return [x for w in v.values() for x in _flat(w)]
            if isinstance(v, list):
                return [x for w in v for x in _flat(w)]
            return [v]
        boots = _flat(e["bootId"]) if "bootId" in e else []
        p.check(len({json.dumps(b, sort_keys=True) for b in boots}) <= 1,
                f"{rel}: bootId holds different values {boots!r}: the boot id must be unchanged within one file")
        s, c = need(p, e, "startedAt", "iso", rel), need(p, e, "completedAt", "iso", rel)
        need(p, e, "criterion", "str", rel)
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

    for name in EXPERIMENTS:
        rel = f"experiments/{name}.json"
        e = b.json(rel)
        if e is None:
            continue
        exps[name] = e
        experiment_file(name, rel, e, carried=name == "E7" and e7_mode == "carried")

    # A1 (iii) bounded measurement: required, never carried, harness-only (MinIO, no application), and re-judged here
    # from the harness's raw statvfs pairs; the file's own verdict and figures are checked, never used.
    a1 = b.json(A1III_REL)
    a1iii, a1iii_raw = None, None
    if a1 is not None:
        experiment_file(A1III, A1III_REL, a1, carried=False)
        art = a1.get("artifact")
        p.check(art is None or art == cand,
                f"{A1III_REL}: artifact {art!r} is neither null (harness-only) nor the MANIFEST candidate {cand!r}")
        # The harness output is the one rawFile that is a JSON list holding the a1iii summary record.
        found = []
        for r in a1.get("rawFiles") if isinstance(a1.get("rawFiles"), list) else []:
            if not (isinstance(r, str) and r.endswith(".json") and os.path.isfile(b.path(r))):
                continue
            try:
                doc = json.load(open(b.path(r), encoding="utf-8"))
            except ValueError:
                continue
            if isinstance(doc, list) and any(isinstance(x, dict) and x.get("record") == "summary"
                                             and x.get("experiment") == A1III for x in doc):
                found.append((r, doc))
        if p.check(len(found) == 1, f"{A1III_REL}: rawFiles must hold exactly one e1e3e4.py a1iii output (a JSON list with "
                                    f"the A1iii summary record), found {[r for r, _ in found]}"):
            a1iii_raw, recs = found[0]
            res, why = judge_a1iii(recs, {"files": xf["inodeCount"], "blocks": xf["statvfsBlocks"], "frsize": xf["blockSize"]})
            for w in why:
                p.add(f"A1iii re-judged from {a1iii_raw}: {w}")
            if res is not None:
                p.check(res["experiment"] == A1III, f"{a1iii_raw}: summary experiment is {res['experiment']!r}, not {A1III}")
                p.check(res["harnessPass"] is True and res["harnessProblems"] in ([], None),
                        f"{a1iii_raw}: the harness itself reports PASS {res['harnessPass']!r}, problems {res['harnessProblems']!r}")
                p.check(res["kernel"] == X["kernelRelease"], f"{a1iii_raw}: the harness ran on kernel {res['kernel']!r}, not {X['kernelRelease']}")
                p.check(isinstance(res["bootId"], str) and res["bootId"] != "", f"{a1iii_raw}: the summary has no bootId")
                # A1iii.json carries no top-level bootId: the harness's is measurements.harnessBootId (#124 6700351383 item 8).
                hb = (a1.get("measurements") or {}).get("harnessBootId") if isinstance(a1.get("measurements"), dict) else None
                p.check(hb == res["bootId"],
                        f"{A1III_REL}: measurements.harnessBootId {hb!r} is not the harness summary's bootId {res['bootId']!r}")
                for k in ("startedAt", "completedAt"):
                    p.check(a1.get(k) == res[k], f"{A1III_REL}: {k} {a1.get(k)!r} is not the harness summary's {res[k]!r}")
                if all(isinstance(a1.get(k), str) and ISO_RE.match(a1[k]) for k in ("startedAt", "completedAt")):
                    outside = [t for t in res["sampleTimes"] if not iso(a1["startedAt"]) <= iso(t) <= iso(a1["completedAt"])]
                    p.check(not outside, f"{a1iii_raw}: statvfs samples at {outside[:3]} lie outside startedAt..completedAt")
                # The file's own figures must be the ones recomputed here (a disagreement means another raw file).
                m = a1.get("measurements") if isinstance(a1.get("measurements"), dict) else {}
                def fig(v):  # a ratio figure is exactly {dInodes, dBlocks, ratio}; maps of them compare member by member
                    if isinstance(v, dict) and "dInodes" in v:
                        return {k: v.get(k) for k in ("dInodes", "dBlocks", "ratio")}
                    return {k: fig(x) for k, x in v.items()} if isinstance(v, dict) else v
                claims = [("objects", m.get("objects"), res["objects"]),
                          ("objectsPerPhase", m.get("objectsPerPhase"), res["objectsPerPhase"])]
                claims += [(k, fig(m.get(k)), fig(res[k])) for k in ("ratioOverall", "ratioPerPhase", "ratioPerPhaseLayout", "ratioPerLayout")]
                for k, claimed, mine in claims:
                    p.check(claimed == mine, f"{A1III_REL}: measurements.{k} {claimed!r} is not the re-judged {mine!r} (from {a1iii_raw})")
                if not why:
                    a1iii = res
    for name, started in ran_after:
        p.check(reboot_at is None or started >= reboot_at, f"{name} started {fmt(started)}, before the post-reboot baseline")
        p.check(cleanup_at is None or cleanup_at >= started, f"{name} started after the post-cleanup baseline")
    for done in completions:
        p.check(cleanup_at is None or cleanup_at >= done, f"an experiment completed {fmt(done)}, after the post-cleanup baseline")

    # --- A1 (vi) inode census (C2, C3), summary committed, listing off-repo
    cs = b.json(CENSUS_SUMMARY) or {}
    census = None
    # A1iii's own census, as experiments/A1iii.json names it (measurements.census, #124 6700351383).
    a1m = (a1 or {}).get("measurements") if isinstance((a1 or {}).get("measurements"), dict) else {}
    a1c = a1m.get("census") if isinstance(a1m.get("census"), dict) else None
    if cs:
        p.check(cs.get("schema") == CENSUS_SCHEMA, f"{CENSUS_SUMMARY}: schema is not {CENSUS_SCHEMA}")
        lst, cnt, rec_ = cs.get("listing") or {}, cs.get("census") or {}, cs.get("reconciliation") or {}
        cust = lst.get("custody") or {}
        taken = need(p, cs, "takenAt", "iso", CENSUS_SUMMARY)
        sha = lst.get("sha256")
        p.check(isinstance(sha, str) and HEX64.match(sha), f"{CENSUS_SUMMARY}: listing.sha256 is not 64 hex")
        need(p, lst, "bytes", "pos", f"{CENSUS_SUMMARY} listing")
        p.check(cust.get("location") == "off-repo" and cust.get("mode") == "0700" and isinstance(cust.get("heldBy"), str)
                and cust.get("heldBy") and isinstance(cust.get("retainedUntil"), str) and cust.get("retainedUntil"),
                f"{CENSUS_SUMMARY}: listing.custody must be heldBy <Ops>, location off-repo, mode 0700, retainedUntil <when>")
        distinct = need(p, cnt, "distinctInodes", "pos", f"{CENSUS_SUMMARY} census")
        blk = need(p, cnt, "blocklessInodes", "int", f"{CENSUS_SUMMARY} census")
        ent = cnt.get("entries") or {}
        for k in ("tmp", "trash", "multipart"):
            need(p, ent, k, "int", f"{CENSUS_SUMMARY} census.entries")
        used = need(p, rec_, "inodesUsed", "pos", f"{CENSUS_SUMMARY} reconciliation")
        below = need(p, rec_, "inodesBelowFirstInode", "int", f"{CENSUS_SUMMARY} reconciliation")
        # The reserved inodes 1..First−1 include the root (inode 2), which the listing ALSO lists: count it once
        # (#124 6700446089). listedBelowFirstInode = listed distinct inodes numbered below First inode (normally 1).
        listed_below = need(p, cnt, "listedBelowFirstInode", "int", f"{CENSUS_SUMMARY} census")
        p.check(blk == 0, f"(vi) census: {blk} blockless inodes, not 0 (A1 (vi))")
        first = dumpe2fs_field(d2 or "", "First inode")
        p.check(first is not None and first.isdigit() and below == int(first) - 1,
                f"{CENSUS_SUMMARY}: inodesBelowFirstInode {below} is not dumpe2fs First inode − 1 ({first})")
        p.check(listed_below is None or below is None or 0 <= listed_below <= below,
                f"{CENSUS_SUMMARY}: census.listedBelowFirstInode {listed_below} is not between 0 and inodesBelowFirstInode {below}")
        gap = None
        if None not in (distinct, used, below, listed_below) and all(isinstance(ent.get(k), int) for k in ("tmp", "multipart")):
            gap = used - (distinct + below - listed_below)
            allowance = ent["tmp"] + ent["multipart"]
            p.check(abs(gap) <= allowance,
                    f"(vi) census: f_files − f_ffree {used} differs from the listed {distinct} + {below} reserved − "
                    f"{listed_below} listed reserved by {gap}, beyond the {allowance} in-flight tmp/multipart entries (C2)")
        steps = cs.get("steps")
        if p.check(isinstance(steps, list) and steps, f"{CENSUS_SUMMARY}: steps must list (i)–(vi) with kernel, boot id, uuid (C3)"):
            boots = {st.get("bootId") for st in steps if isinstance(st, dict)}
            p.check(all(isinstance(st, dict) and st.get("kernelRelease") == X["kernelRelease"] for st in steps),
                    f"{CENSUS_SUMMARY}: a step's kernelRelease is not {X['kernelRelease']} (C3)")
            p.check(all(isinstance(st, dict) and st.get("filesystemUuid") == xf["uuid"] for st in steps),
                    f"{CENSUS_SUMMARY}: a step's filesystemUuid is not {xf['uuid']} (C3)")
            p.check(len(boots) == 1 and None not in boots and "" not in boots,
                    f"{CENSUS_SUMMARY}: the steps' boot ids {sorted(map(str, boots))} are not one (C3: boot id unchanged)")
            p.check(a1iii is None or a1iii["bootId"] in boots,
                    f"{a1iii_raw}: the A1iii harness ran on boot {(a1iii or {}).get('bootId')!r}, not the census steps' "
                    f"{sorted(map(str, boots))} (C3: boot id unchanged)")
            p.check(a1 is None or (a1c or {}).get("bootId") in boots,
                    f"{A1III_REL}: measurements.census.bootId {(a1c or {}).get('bootId')!r} is not the census steps' "
                    f"{sorted(map(str, boots))} (C3: boot id unchanged)")
        if taken and ISO_RE.match(taken):
            t = iso(taken)
            p.check(reboot_at is None or t >= reboot_at, "(vi) census was taken before the post-reboot baseline")
            p.check(cleanup_at is None or t <= cleanup_at, "(vi) census was taken after the post-cleanup baseline (C2: before any cleanup)")
            e2c = (exps.get("E2") or {}).get("completedAt")
            p.check(not (isinstance(e2c, str) and ISO_RE.match(e2c)) or t >= iso(e2c),
                    "(vi) census was taken before E2 completed (it runs on the E2/E7 population)")
        # Per-experiment censuses: each states whether it was taken after the experiment's own defined drain.
        disclosures, c2_status = [], {}
        per = cs.get("censuses")
        if p.check(isinstance(per, list), f"{CENSUS_SUMMARY}: censuses must list each census with its experiment and afterDefinedDrain"):
            by_exp = {}
            for i, c in enumerate(per):
                where = f"{CENSUS_SUMMARY} censuses[{i}]"
                exp = need(p, c, "experiment", "str", where)
                if exp is None:
                    continue
                p.check(exp not in by_exp, f"{where}: experiment {exp} has more than one census entry")
                by_exp[exp] = c
                after = c.get("afterDefinedDrain")
                if not p.check(isinstance(after, bool), f"{where}: afterDefinedDrain for {exp} is {after!r}, not true or false"):
                    continue
                derived = C2_DEVIATION if after else C2_SATISFIED
                p.check(c.get("c2Status") in (None, derived),
                        f"{where}: c2Status {c.get('c2Status')!r} for {exp}, but afterDefinedDrain is {str(after).lower()}: "
                        f"C2 is never 'satisfied' for a census taken after its defined drain")
                c2_status[exp] = derived
                lb, eb = c.get("listedBelowFirstInode"), c.get("inodesBelowFirstInode")
                p.check(isinstance(lb, int) and not isinstance(lb, bool) and isinstance(eb, int) and 0 <= lb <= eb,
                        f"{where}: listedBelowFirstInode {lb!r} for {exp} is missing or not between 0 and inodesBelowFirstInode {eb!r}")
                p.check("blocklessInodes" not in c or c["blocklessInodes"] == 0,
                        f"{where}: the {exp} census found {c.get('blocklessInodes')!r} blockless inodes, not 0 (A1 (vi))")
                if after:
                    n = (c.get("entries") or {}).get("trash")
                    if p.check(isinstance(n, int) and not isinstance(n, bool) and n >= 0,
                               f"{where}: entries.trash is missing for {exp}: the disclosure needs the .trash count"):
                        disclosures.append(DRAIN_DISCLOSURE.format(exp=exp, n=n))
            for exp in C2_REQUIRED_CENSUS:
                p.check(exp in by_exp and isinstance(by_exp[exp].get("afterDefinedDrain"), bool),
                        f"{CENSUS_SUMMARY}: no census entry for {exp} stating afterDefinedDrain (C2 must be disclosed, not assumed)")
            # The top level is DEFINED as the latest census in `censuses` (#124 6700351383), never as any particular
            # experiment's: it must be that entry, member by member. The order of A1 (iii) is judged on its own entry.
            dated = [c for c in per if isinstance(c, dict) and isinstance(c.get("takenAt"), str) and ISO_RE.match(c["takenAt"])]
            if p.check(per and len(dated) == len(per), f"{CENSUS_SUMMARY}: every censuses entry needs an ISO-8601 UTC takenAt"):
                latest = max(dated, key=lambda c: iso(c["takenAt"]))
                ll, le = latest.get("listing") or {}, latest.get("entries") or {}
                keys3 = ("tmp", "trash", "multipart")
                for k, top, entry in (("takenAt", taken, latest.get("takenAt")),
                                      ("listing.sha256", sha, ll.get("sha256")),
                                      ("listing.bytes", lst.get("bytes"), ll.get("bytes")),
                                      ("census.distinctInodes", distinct, latest.get("distinctInodes")),
                                      ("census.blocklessInodes", blk, latest.get("blocklessInodes")),
                                      ("census.entries", {k3: ent.get(k3) for k3 in keys3}, {k3: le.get(k3) for k3 in keys3}),
                                      ("reconciliation.inodesUsed", used, latest.get("inodesUsed")),
                                      ("reconciliation.inodesBelowFirstInode", below, latest.get("inodesBelowFirstInode")),
                                      ("census.listedBelowFirstInode", listed_below, latest.get("listedBelowFirstInode"))):
                    p.check(top == entry, f"{CENSUS_SUMMARY}: top-level {k} {top!r} is not the latest census's "
                                          f"({latest.get('experiment')}: {entry!r}); the top level is defined as the latest census")
                p.check("topLevelCensusOf" not in cs or cs["topLevelCensusOf"] == latest.get("experiment"),
                        f"{CENSUS_SUMMARY}: topLevelCensusOf {cs.get('topLevelCensusOf')!r} is not the latest census "
                        f"{latest.get('experiment')!r}")
            # A1 (vi) straight after A1 (iii): its own entry, 0-15 min after A1iii completed, bound to A1iii.json.
            ac = by_exp.get(A1III)
            if p.check(isinstance(ac, dict), f"{CENSUS_SUMMARY}: no census entry for {A1III}: the (vi) census must be taken "
                                             "straight after A1 (iii), before any cleanup (C2)") and a1 is not None:
                where, at, done = f"{CENSUS_SUMMARY} censuses[{A1III}]", ac.get("takenAt"), a1.get("completedAt")
                if (p.check(isinstance(at, str) and bool(ISO_RE.match(at)), f"{where}: takenAt {at!r} is not ISO-8601 UTC")
                        and isinstance(done, str) and ISO_RE.match(done)):
                    lag = (iso(at) - iso(done)).total_seconds()
                    p.check(0 <= lag <= A1III_CENSUS_MAX_LAG_S,
                            f"{where}: taken at {at}, {lag:+.0f} s from A1iii's completedAt {done}: it must be taken 0-15 min "
                            "after A1 (iii) completed (C2)")
                p.check(ac.get("experimentCompletedAt") == done,
                        f"{where}: experimentCompletedAt {ac.get('experimentCompletedAt')!r} is not A1iii's completedAt {done!r}")
                p.check(isinstance(ac.get("blocklessInodes"), int) and not isinstance(ac["blocklessInodes"], bool)
                        and ac["blocklessInodes"] == 0, f"{where}: blocklessInodes {ac.get('blocklessInodes')!r}, not 0 (A1 (vi))")
                p.check(ac.get("kernelRelease") == X["kernelRelease"] and ac.get("filesystemUuid") == xf["uuid"],
                        f"{where}: kernel {ac.get('kernelRelease')!r} / filesystem {ac.get('filesystemUuid')!r} is not "
                        f"{X['kernelRelease']} / {xf['uuid']} (C3)")
                if p.check(a1c is not None, f"{A1III_REL}: measurements.census (A1iii's own census) is missing"):
                    acs = (ac.get("listing") or {}).get("sha256")
                    p.check(at == a1c.get("takenAt"),
                            f"{where}: takenAt {at!r} is not {A1III_REL} measurements.census.takenAt {a1c.get('takenAt')!r}")
                    p.check(isinstance(acs, str) and bool(HEX64.match(acs)) and acs == a1c.get("listingSha256"),
                            f"{where}: listing.sha256 {acs!r} is not {A1III_REL} measurements.census.listingSha256 "
                            f"{a1c.get('listingSha256')!r}")
                    p.check(ac.get("bootId") == a1c.get("bootId"),
                            f"{where}: bootId {ac.get('bootId')!r} is not {A1III_REL} measurements.census.bootId {a1c.get('bootId')!r}")
        verification = None
        if census_raw:
            if os.path.realpath(census_raw).startswith(os.path.realpath(bundle_dir) + os.sep):
                p.add("--census-raw lies inside the bundle: the listing must stay off-repo, outside the bundle")
            elif not os.path.isfile(census_raw):
                p.add(f"--census-raw {census_raw} is not a file")
            else:
                try:
                    if not (first is not None and first.isdigit()):
                        raise Refusal("  ✗ --census-raw: dumpe2fs First inode is unreadable, listedBelowFirstInode cannot be recomputed")
                    rsha, rbytes, rdistinct, rblk, rent, rbelow = census_from_listing(census_raw, int(first))
                    p.check(rsha == sha and rbytes == lst.get("bytes"),
                            f"--census-raw sha256/size {rsha}/{rbytes} is not the summary's {sha}/{lst.get('bytes')}")
                    p.check((rdistinct, rblk, rent, rbelow) == (distinct, blk, {k: ent.get(k) for k in ("tmp", "trash", "multipart")}, listed_below),
                            f"(vi) census recomputed from --census-raw (distinct {rdistinct}, blockless {rblk}, entries {rent}, "
                            f"listedBelowFirstInode {rbelow}) differs from the summary (distinct {distinct}, blockless {blk}, "
                            f"entries {ent}, listedBelowFirstInode {listed_below})")
                    verification = "listing verified at cut (--census-raw)"
                except Refusal as e:
                    p.add(str(e).strip().lstrip("✗ "))
        else:
            att = cs.get("attestation") or {}
            if p.check(isinstance(att.get("by"), str) and att.get("by") and isinstance(att.get("at"), str) and ISO_RE.match(att.get("at") or "")
                       and att.get("listingSha256") == sha,
                       "(vi) census NOT RUN: no --census-raw to verify the listing, and no Ops attestation bound to its sha256 "
                       f"({CENSUS_SUMMARY} attestation.by, .at, .listingSha256)") \
                    and p.check(not (isinstance(taken, str) and ISO_RE.match(taken)) or iso(att["at"]) >= iso(taken),
                                f"(vi) census attestation.at {att['at']} predates the attested census takenAt {taken} "
                                f"({CENSUS_SUMMARY}: an attestation may not predate the census it attests)"):
                verification = f"summary attested by {att['by']} at {att['at']} (listing not re-verified here)"
        if verification:
            census = {"listingSha256": sha, "listingBytes": lst.get("bytes"),
                      "listingCustody": f"held by {cust.get('heldBy')} off-repo, 0700, retained until {cust.get('retainedUntil')}",
                      "verification": verification, "takenAt": taken, "blocklessInodes": blk, "reconciliationGap": gap,
                      "c2Status": dict(sorted(c2_status.items())), "disclosures": disclosures}
    else:
        p.add(f"(vi) census NOT RUN: {CENSUS_SUMMARY} is missing (A1 (vi); the raw listing itself is never in the bundle)")

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
    inodes_scope = m7.get("metadataInodesScope")
    p.check(inodes_scope in METADATA_INODES_SCOPES,
            f"experiments/E7.json measurements.metadataInodesScope is {inodes_scope!r}, not one of "
            f"{METADATA_INODES_SCOPES} (.minio.sys only; bucket directories are per-object inodes charged by i_max)")
    # Metadata bytes: E2 after its partial drain and E7's 24 h peak, both counted by the one definition. The record
    # carries the larger; gate F refuses a declared M below it.
    metadata_bytes = []
    for name in METADATA_BYTES_EXPERIMENTS:
        m = (exps.get(name) or {}).get("measurements") or {}
        v = need(p, m, "metadataBytesMeasured", "pos", f"experiments/{name}.json measurements")
        p.check(m.get("metadataBytesScope") == METADATA_BYTES_SCOPE,
                f"experiments/{name}.json measurements.metadataBytesScope is {m.get('metadataBytesScope')!r}, not "
                f"{METADATA_BYTES_SCOPE!r} (per-object directory blocks are charged by O_max, never also by M)")
        if v is not None:
            metadata_bytes.append(v)
    metadata_bytes_max = max(metadata_bytes) if len(metadata_bytes) == len(METADATA_BYTES_EXPERIMENTS) else None

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
    fe_inodes_scope = (fe.get("minio") or {}).get("metadataInodesScope")
    p.check(fe_inodes_scope in METADATA_INODES_SCOPES,
            f"gate F evidence minio.metadataInodesScope is {fe_inodes_scope!r}, not one of {METADATA_INODES_SCOPES}: "
            "the collector must count .minio.sys only (RECORD-CUT.md, collector change)")
    p.check(fe_inodes_scope == inodes_scope,
            f"gate F evidence minio.metadataInodesScope {fe_inodes_scope!r} differs from E7's {inodes_scope!r}: the record "
            "and the evidence must count inodes over one scope (gate F refuses a mismatch)")
    # The inode-headroom latch drill (E10). Optional with scope minio-sys; when given it must be complete.
    m10 = (exps.get("E10") or {}).get("measurements") or {}
    fe_mon = fe.get("monitor") or {}
    drill = None
    if "inodeHeadroomLatch" in m10 or "fsMonitorSha256" in m10:
        ok_drill = p.check(m10.get("inodeHeadroomLatch") == "PASS" and HEX64_RE.match(str(m10.get("fsMonitorSha256") or "")),
                           f"experiments/E10.json: inodeHeadroomLatch {m10.get('inodeHeadroomLatch')!r} / fsMonitorSha256 "
                           f"{m10.get('fsMonitorSha256')!r} is not a PASS drill of a named fs-monitor build (64 hex)")
        if ok_drill:
            drill = {"result": "PASS", "fsMonitorSha256": m10["fsMonitorSha256"]}
    if METADATA_INODES_SCOPE_EXCL_TRASH in (inodes_scope, fe_inodes_scope):
        p.check(drill is not None,
                f"{METADATA_INODES_SCOPE_EXCL_TRASH} is used but experiments/E10.json records no PASS inode-headroom latch "
                "drill (measurements.inodeHeadroomLatch, fsMonitorSha256): only minio-sys is acceptable (note §8.2)")
        p.check(drill is None or fe_mon.get("fsMonitorSha256") == drill["fsMonitorSha256"],
                f"{METADATA_INODES_SCOPE_EXCL_TRASH} is used but gate F evidence monitor.fsMonitorSha256 "
                f"{fe_mon.get('fsMonitorSha256')!r} is not the fs-monitor build E10 drilled "
                f"{drill['fsMonitorSha256'] if drill else None!r}")
        p.check(fe_mon.get("inodeHeadroomLatchArmed") is True,
                f"{METADATA_INODES_SCOPE_EXCL_TRASH} is used but gate F evidence monitor.inodeHeadroomLatchArmed is not true")
    p.check((fe.get("minio") or {}).get("bucketPresent") is True,
            "gate F evidence minio.bucketPresent is not true: production pre-provisions the bucket, so the collector "
            "measured the wrong directory or nothing")
    p.check((fe.get("minio") or {}).get("metadataBytesScope") == METADATA_BYTES_SCOPE,
            f"gate F evidence minio.metadataBytesScope is {(fe.get('minio') or {}).get('metadataBytesScope')!r}, not "
            f"{METADATA_BYTES_SCOPE!r}: the collector must count unattributed allocations only (RECORD-CUT.md, collector change)")
    dcl = fe.get("declared") or {}
    p.check(omax is None or (isinstance(dcl.get("objectOverheadBytes"), int) and dcl["objectOverheadBytes"] >= omax),
            f"gate F evidence declares O = {dcl.get('objectOverheadBytes')}, below the qualified O_max {omax}")
    p.check(metadata_inodes is None or (isinstance(dcl.get("metadataBudgetInodes"), int) and dcl["metadataBudgetInodes"] >= metadata_inodes),
            f"gate F evidence declares an inode budget {dcl.get('metadataBudgetInodes')}, below the E7 measurement {metadata_inodes}")
    # M is FIXED for production (owner decision 2026-11-05: 1 GiB): the evidence must declare exactly it, and the
    # measured metadata is compared against that budget (never a PASS by itself: E7 is judged when it completes).
    p.check(dcl.get("metadataBudgetBytes") == xd["metadataBudgetBytes"],
            f"gate F evidence declares M = {dcl.get('metadataBudgetBytes')}, not the production M "
            f"{xd['metadataBudgetBytes']} (production-expected.json declared.metadataBudgetBytes)")
    p.check(metadata_bytes_max is None or (isinstance(dcl.get("metadataBudgetBytes"), int) and dcl["metadataBudgetBytes"] >= metadata_bytes_max),
            f"gate F evidence declares M = {dcl.get('metadataBudgetBytes')}, below the E2/E7 metadata bytes measurement {metadata_bytes_max}")
    if fe.get("collectedAt") and ISO_RE.match(str(fe.get("collectedAt"))) and qualified_at:
        p.check(iso(fe["collectedAt"]) >= qualified_at, "gate F evidence was collected before the qualification completed")

    if a1iii is None and not p.items:
        p.add(f"{A1III_REL}: A1 (iii) was not judged")  # unreachable: every path to None records a problem
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
                         f"{member} with its data on the final filesystem {xf['uuid']}"
                         + (f"; the §9a sweep on Ops' own loop image {venue_uuid} on / under dm-delay (venue 9a: "
                            f"{NINE_A_OWN_IMAGE}; same kernel, member, runtime configuration, filesystem type, features, "
                            "block and inode size)" if own_image else "")),
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
            "metadataInodesScope": inodes_scope,
            "metadataInodesBasis": ("E7 on the final filesystem" if e7_mode == "final-filesystem"
                                    else f"E7 carried under {e7['amendment']}"),
            **({"inodeHeadroomLatchDrill": drill} if drill else {}),
            "inodeCensus": census,
            "inodeBoundedMeasurement": {
                "experiment": A1III,
                "completedAt": a1iii["completedAt"],
                "objects": a1iii["objects"],
                "objectsPerPhase": a1iii["objectsPerPhase"],
                "ratioPerPhase": a1iii["ratioPerPhase"],
                "ratioOverall": a1iii["ratioOverall"],
                "basis": ("A1 (iii) on the final filesystem, re-judged from the harness's raw statvfs pairs: "
                          "Δ(f_files − f_ffree) <= Δ(f_blocks − f_bfree), in blocks of f_frsize, in every phase (one per C1 "
                          "class), every layout within each phase, every layout over the run and overall; "
                          f"deferred raw.eml, as every phase record states: \"{A1III_DESCRIPTION}\""),
            },
            "metadataBytesMeasured": metadata_bytes_max,
            "metadataBytesScope": METADATA_BYTES_SCOPE,
            "experiments": {n: exps[n]["verdict"] for n in EXPERIMENTS},
        },
        "enablementEligible": True,
        "ineligibilityReasons": [],
    }

    # --------------------------------------------------------------------------------------------- provenance
    PQ, PN, DV, CD = "PROD-QUAL", "PROD-NOW", "DERIVED", "CODE"
    ev = "evidence/"
    label("`recordId`", record_id, DV, f"`production-amd64-<host>-<date of qualifiedAt>`; equals `{ev}MANIFEST.json` and `{ev}gate-f/filesystem-evidence.json` qualificationRecordId")
    label("`qualifiedAt`", fmt(qualified_at), DV, f"latest of the §9a slow sweep start (`{ev}qualification-9a/orchestrator.log`) and every on-filesystem experiment's completedAt (`{ev}experiments/E*.json` and `{ev}{A1III_REL}`)")
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
    label("`qualification.slowWMechanism`", mech, PQ, f"`{ev}qualification-9a/venue.json`; every slow trial's slow_mode is dm"
          + (f"; venue 9a `{NINE_A_OWN_IMAGE}` on filesystem {venue_uuid} (`{ev}qualification-9a/venue-dumpe2fs-h.txt`: the "
             "final filesystem's type, features, block and inode size)" if own_image else ""))
    label("`qualification.cMaxSeconds`, `procedure`", f"{C_MAX_S}", CD, "ADR-035 §9 C_max; the repository procedure")
    label("`qualification.experimentArtifact`", json.dumps(cand), PQ, f"`{ev}MANIFEST.json`; every app-level experiment names it")
    label("`filesystem.uuid`, `type`, `mountOptions`, `mountSource`", f"{ffs['uuid']}, {ffs['type']}, {ffs['mountOptions']}, {ffs['mountSource']}", PN,
          f"`{ev}gate-f/filesystem-evidence.json` = `{ev}identity/fs-baseline-post-*.txt` = `{ev}identity/dumpe2fs-h.txt`")
    label("`filesystem.objectOverheadMaxBytes`", f"{omax} (worst measured {max(worst)})", PQ, f"`{ev}experiments/E1.json`, `E2.json`, `E3.json` measurements: the O_max their criterion was evaluated against")
    label("`filesystem.metadataInodesMeasured`, `metadataInodesScope`", f"{metadata_inodes}, {inodes_scope}",
          PQ if e7_mode == "final-filesystem" else "CARRIED-AMENDMENT",
          f"`{ev}experiments/E7.json`" + ("" if e7_mode == "final-filesystem" else f" (source {exps['E7']['sourceEvidence']}; authority: {e7['amendment']})"))
    label("`filesystem.metadataBytesMeasured`, `metadataBytesScope`", f"{metadata_bytes_max}, {METADATA_BYTES_SCOPE}",
          PQ if e7_mode == "final-filesystem" else "CARRIED-AMENDMENT",
          f"the larger of `{ev}experiments/E2.json` (after the partial drain) and `{ev}experiments/E7.json` (24 h peak) "
          "`measurements.metadataBytesMeasured`, both counted by `scripts/adr035-metadata-accounting.py`")
    for n in EXPERIMENTS:
        e = exps[n]
        carried = n == "E7" and e7_mode == "carried"
        label(f"`filesystem.experiments.{n}`", e["verdict"], "CARRIED-AMENDMENT" if carried else PQ,
              f"`{ev}experiments/{n}.json` ({e['startedAt']} → {e['completedAt']}" + (f", artifact {e['artifact']['sourceSha'][:12]}" if n in APP_EXPERIMENTS else "") + ")")
    label("`filesystem.inodeCensus`", f"{census['listingSha256'][:12]}…, {census['verification']}", PQ,
          f"`{ev}{CENSUS_SUMMARY}` (A1 (vi), C2, C3). The raw listing is NOT in the evidence: {census['listingCustody']}")
    pr = a1iii["ratioPerPhase"]
    label("`filesystem.inodeBoundedMeasurement`",
          f"{a1iii['objects']} objects (" + ", ".join(f"{n} {a1iii['objectsPerPhase'][n]}" for n in A1III_PHASES)
          + f"); ratio overall {a1iii['ratioOverall']['ratio']}, per phase "
          + ", ".join(f"{n} {pr[n]['ratio']}" for n in A1III_PHASES) + f"; completed {a1iii['completedAt']}", PQ,
          f"`{ev}{A1III_REL}` ({a1['startedAt']} → {a1['completedAt']}), re-judged from the raw statvfs pairs of "
          f"`{ev}{a1iii_raw}` (its own verdict and figures are checked, never used): every phase, every layout within "
          f"each phase, every layout over the run and overall ≤ 1; ≥ {A1III_MIN_TOTAL} objects, ≥ {A1III_MIN_SMALL} in each "
          f"of 0B and 1B; every object in its phase's class; {A1III_DEFERRED_RULE}")
    label("`filesystem.inodeCensus.c2Status`, `disclosures`", json.dumps(census["c2Status"]), DV,
          "per census: `afterDefinedDrain` true gives literal-deviation-disclosed and the exact disclosure, never "
          "satisfied. " + (" ".join(census["disclosures"]) if census["disclosures"] else "No disclosure."))
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
    ap.add_argument("--census-raw", metavar="FILE", help="the raw (vi) census listing, held by Ops off-repo (never inside the "
                                                         "bundle): verified against census/summary.json at cut time")
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
        m_fixed = (expected.get("declared") or {}).get("metadataBudgetBytes")
        if not isinstance(m_fixed, int) or isinstance(m_fixed, bool) or m_fixed <= 0:
            print("error: --expected has no positive declared.metadataBudgetBytes (the production M)", file=sys.stderr)
            return 2
        n = verify_sums(a.bundle)
        refuse_raw_listings(a.bundle)
        record, prov, date = build(a.bundle, expected, a.repo, a.census_raw)
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
