#!/usr/bin/env python3
"""Generate the ADR-035 §9a staging qualification record from RAW harness output only (TI-STORAGE-006C).

Inputs (all produced by docs/adr/0035-benchmark/qualification/run-slow.sh on the staging host):
  <Q>/{main,v3,slow}/results-slow.jsonl   trials
  <Q>/{main,v3,slow}/sweep-slow.jsonl     strict sweeps >= 17 min after the last trial
  <Q>/slow/harness-minio-capture.txt      `mc admin info --json` + `mc admin config get` of the HARNESS MinIO
  <Q>/slow/{mount,uname}.txt, minio-binary.sha256, devices.env
The runtime-config hash is computed by infinity-core infra/testinbox/storage/qualification-check.py's own
normalize_runtime_config (ti-qc-runtime-config-v1), so the record and the Ops check can only agree honestly.
Nothing measured is typed by hand. Eligibility is decided by the criteria below; any failure is listed.
"""
import importlib.util, json, os, re, sys, datetime

Q = sys.argv[1] if len(sys.argv) > 1 else "/opt/infinity/workspace/ti-storage-006c/qual"
QC = "/home/deploy/infinity-core/infra/testinbox/storage/qualification-check.py"
C_MAX_S = 15 * 60          # ADR-035 §9: C_max = 15 min, measured from the writer's RST
spec = importlib.util.spec_from_file_location("qc", QC); qc = importlib.util.module_from_spec(spec); spec.loader.exec_module(qc)


def jl(path):
    return [json.loads(l) for l in open(path) if l.strip().startswith("{")]


passes = {p: {"results": jl(f"{Q}/{p}/results-slow.jsonl"), "sweep": jl(f"{Q}/{p}/sweep-slow.jsonl")} for p in ("main", "v3", "slow")}
rows = [r for p in passes.values() for r in p["results"]]
reasons, stats = [], {}

# --- freeze / base passes (main, v3): no late commit, every freeze-time witness blocked, all base visible
fb = passes["main"]["results"] + passes["v3"]["results"]
late = [r["key"] for r in fb if r.get("late_after_absent") or r.get("committed_after_thaw")]
base = [r for r in fb if r["scenario"].startswith("base")]
# base-W (client silent, response never read) must ALWAYS commit; base-R (RST 100 ms after the body) may be cut
# before its commit — an abort that never appears is correct, and the strict sweep proves none appeared late.
base_invisible = [r["key"] for r in base if r["scenario"].startswith("base-W") and r.get("visible_after_thaw_s") is None]
base_r_aborted = [r["key"] for r in base if not r["scenario"].startswith("base-W") and r.get("visible_after_thaw_s") is None]
wit = [r.get("witness_during_freeze") for r in fb if r.get("witness_during_freeze") is not None]
wit_not_blocked = [w for w in wit if not str(w).startswith("blocked")]
v3_caught = [r for r in passes["v3"]["results"] if r.get("disk_at_freeze") is False]
stats.update(freezeAndBaseTrials=len(fb), lateCommits=len(late), baseTrials=len(base), baseInvisible=len(base_invisible),
             baseRAbortedNeverCommitted=len(base_r_aborted), witnessDuringFreeze=len(wit), witnessNotBlocked=len(wit_not_blocked), v3CaughtBeforeCommit=len(v3_caught),
             v3CaughtThenCommitted=sum(1 for r in v3_caught if r.get("disk_after_poll")),
             maxBaseVisibleS=max((r["visible_after_thaw_s"] for r in base if r.get("visible_after_thaw_s") is not None), default=None))
if late: reasons.append(f"{len(late)} late commit(s) in freeze/base passes: {late[:5]}")
if base_invisible: reasons.append(f"{len(base_invisible)} base-W upload(s) never visible")
if wit_not_blocked: reasons.append(f"{len(wit_not_blocked)} storage witness(es) completed during a freeze")

# --- slow-W: the residual commit lag after a later-issued completed witness must stay within C_max
slow = [r for r in passes["slow"]["results"] if str(r.get("scenario", "")).startswith("slow-")]
lags = [r["commit_lag_after_first_completed_witness_s"] for r in slow if r.get("commit_lag_after_first_completed_witness_s") is not None]
over = [r["key"] for r in slow if (r.get("commit_lag_after_first_completed_witness_s") or 0) > C_MAX_S]
stats.update(slowTrials=len(slow), slowMode=sorted({r.get("slow_mode") for r in slow}), slowThrottlesMs=sorted({r.get("throttle_ms") for r in slow}),
             slowCommitAfterCompletedWitness=sum(1 for r in slow if r.get("commit_after_completed_witness")),
             slowNeverCommitted=sum(1 for r in slow if r.get("never_committed")),
             slowMaxCommitLagAfterWitnessS=max(lags) if lags else None, slowWitnessesBlocked=sum(r.get("witnesses_blocked", 0) for r in slow))
if len(slow) < 18: reasons.append(f"slow-W ran {len(slow)} trials, plan-slow.json requires 18")
if not stats["slowMode"] or stats["slowMode"] != ["dm"]: reasons.append(f"slow-W mechanism {stats['slowMode']} is not dm-delay")
if over: reasons.append(f"{len(over)} slow-W commit(s) landed more than C_max after a completed witness: {over[:5]}")

# --- sweeps: every key re-checked strictly; nothing appeared after its poll window; sweep >= 17 min after trials
for p, d in passes.items():
    summ = [s for s in d["sweep"] if s.get("summary")]
    if not summ: reasons.append(f"{p}: sweep has no summary (aborted on a check error?)"); continue
    s = summ[-1]
    stats[f"{p}SweepKeys"] = s["keys"]; stats[f"{p}AppearedAfterPollWindow"] = s["appeared_after_poll_window"]
    if s["keys"] != len(d["results"]): reasons.append(f"{p}: sweep checked {s['keys']} of {len(d['results'])} keys")
    if s["appeared_after_poll_window"]: reasons.append(f"{p}: {s['appeared_after_poll_window']} object(s) appeared after the poll window")
tl = {}
for line in open(f"{Q}/orchestrator.log"):
    m = re.match(r"^(\S+) (pass (\w+) trials done|sweep (\w+) start)", line)
    if m: tl[(m.group(3) or m.group(4), "done" if m.group(3) else "sweep")] = datetime.datetime.fromisoformat(m.group(1).replace("Z", "+00:00"))
for p in passes:
    if (p, "done") in tl and (p, "sweep") in tl:
        gap = (tl[(p, "sweep")] - tl[(p, "done")]).total_seconds() / 60
        stats[f"{p}SweepAfterMin"] = round(gap, 1)
        if gap < 17: reasons.append(f"{p}: sweep only {gap:.1f} min after the last trial (< 17)")

# --- the combination, read from the harness run itself
cap = open(f"{Q}/slow/harness-minio-capture.txt").read()
info = json.loads(cap.split("=====CONFIG", 1)[0].strip())["info"]   # the `mc admin info --json` document
cfg_outputs, cur, rc, buf = {}, None, None, []
for line in cap.split("=====CONFIG", 1)[1].splitlines():
    m = re.match(r"^### (\w+)$", line); e = re.match(r"^### rc=(\d+)$", line)
    if m: cur, buf = m.group(1), []; continue
    if e and cur: rcv = int(e.group(1)); txt = "\n".join(buf); cfg_outputs[cur] = (rcv, txt if rcv == 0 else "", txt if rcv else ""); cur = None; continue
    if cur: buf.append(line)
cfg_hash, cfg_text = qc.normalize_runtime_config(cfg_outputs)
srv = info["servers"]
version = srv[0]["version"]; drives = sum(len(s.get("drives", [])) for s in srv)
mount = open(f"{Q}/slow/mount.txt").read().strip()
mopts = re.search(r"type (\w+) \(([^)]*)\)", mount); fstype, opts = mopts.group(1), mopts.group(2)
uname = open(f"{Q}/slow/uname.txt").read().split(); kernel, arch = uname[2], uname[-2]
binsha = open(f"{Q}/slow/minio-binary.sha256").read().split()[0]
env = json.loads(open(f"{Q}/slow/harness-container.txt").read().split(" ", 1)[0] or "[]")
timeout_env = {k: v for k, v in (e.split("=", 1) for e in env if "=" in e) if qc.TIMEOUT_NAME.search(k)}

scen = sorted({r["scenario"] for r in rows}, key=lambda s: [p for p in passes if any(r["scenario"] == s for r in passes[p]["results"])][0] + s)
eligible = not reasons
record = {
    "schemaVersion": 1,
    "recordId": "staging-amd64-vmi2932906-2026-10-08",
    "qualifiedAt": tl.get(("slow", "sweep"), datetime.datetime.now(datetime.timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "minio": {
        "imageIndexDigest": "sha256:bbac678936882e4033b6068efd0a19d103b219002d979087f7c5bd330172e00d",
        "platformMemberDigest": "sha256:3f97c5651cb6662b880c787a232b6b34fec8d8922e08d6617b25d241a21164bb",
        "platform": "linux/amd64",
        "release": "RELEASE." + version.replace(":", "-"),
        "commitId": srv[0]["commitID"],
        "binarySha256": binsha,
        "mode": "single-node-single-drive" if len(srv) == 1 and drives == 1 else f"other({len(srv)}x{drives})",
        "driveCount": drives,
        "timeoutEnvironment": timeout_env,
        "timeoutCliFlags": [],
        "runtimeConfig": {"normalized": qc.NORMALIZATION, "hash": cfg_hash},
        "storageClassInlineDefaults": "release-defaults",
        "storageClassInlineDefaultsBasis": "fresh data directory, no MINIO_STORAGE_CLASS_* / inline variables; single-drive MinIO exposes no storage_class admin subsystem (captured as <unknown-subsystem> in the hashed configuration)",
    },
    "host": {"kernelRelease": kernel, "architecture": arch, "filesystemType": fstype, "mountOptions": opts,
             "device": "ext4 on a dm-delay mapper over a loop device (created per run); live staging: ext4 loop image /var/lib/testinbox-minio/ext4.img mounted rw,relatime"},
    "network": {"directPath": True, "proxy": "none", "tls": False},
    "uploadImplementationVersion": "adr035-presigned-put-v1",
    "uploadImplementationNote": "harness.py implements the ADR §5 shape (presigned single-part PUT, signed content-length and If-None-Match: *, one attempt, abort by RST after TIOCOUTQ=0); the application's FencedUploader declares the same version",
    "qualification": {
        "platformClass": "staging-host",
        "platform": "the staging host itself: Contabo VPS vmi2932906, KVM, x86_64, Ubuntu 24.04, Docker 29.x; privileged harness container on the host kernel",
        "evidenceReference": "docs/adr/0035-benchmark/qualification/staging-2026-10-08/",
        "procedure": "docs/adr/0035-benchmark/minio-probes/QUALIFICATION.md; qualification/run-slow.sh --mode dm with plan.json, plan3.json and plan-slow.json, each swept after >= 17 min",
        "scenarios": scen,
        "trials": len(rows),
        "lateCommits": stats["lateCommits"],
        "slowWExecuted": len(slow) > 0,
        "slowWMechanism": "dm-delay (device-mapper delay target over the loop device), write delay 500 ms / 2000 ms",
        "slowWMaxCommitLagAfterCompletedWitnessS": stats["slowMaxCommitLagAfterWitnessS"],
        "cMaxSeconds": C_MAX_S,
    },
    "enablementEligible": eligible,
    "ineligibilityReasons": reasons,
}
out = f"{Q}/staging-amd64-vmi2932906-2026-10-08.json"
json.dump(record, open(out, "w"), indent=2, ensure_ascii=False); open(out, "a").write("\n")
json.dump({"stats": stats, "reasons": reasons, "runtimeConfigNormalized": cfg_text}, open(f"{Q}/qualification-summary.json", "w"), indent=2)
print(json.dumps({"eligible": eligible, "reasons": reasons, "hash": cfg_hash, **stats}, indent=1))
