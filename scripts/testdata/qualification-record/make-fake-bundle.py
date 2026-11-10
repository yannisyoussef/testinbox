#!/usr/bin/env python3
"""Writes a FAKE, complete N1 evidence bundle for scripts/build-qualification-record.test.sh.

Every value is plainly fake (host fake-host-0, kernel 0.0.0-fake, uuid 00000000-…-0000000fa4e0, digests of fa4e…,
year 2000) so nothing here can be mistaken for production evidence. Deterministic: the same arguments always write
the same bytes, so the generator's output can be compared with a committed golden record.

    make-fake-bundle.py <out-dir> [--e7 final-filesystem|carried] [--venue-9a final-filesystem|own-image]
writes <out-dir>/bundle/ (with SHA256SUMS), <out-dir>/census-raw.txt (the (vi) raw listing: OUTSIDE the bundle, as Ops
holds it off-repo), <out-dir>/expected.json and <out-dir>/repo/ (a minimal repository
root: StorageProtocol.kt and an ADR-035 text that, with --e7 carried, contains the fake amendment note).
"""
import argparse, hashlib, json, os, shutil, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
REAL_REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
HOST = "fake-host-0"
KERNEL = "0.0.0-fake"
UUID = "00000000-0000-4000-8000-0000000fa4e0"
VENUE_UUID = "00000000-0000-4000-8000-00000009a4e0"  # Ops' own §9a loop image (R3), plainly fake
MEMBER = "sha256:" + "fa4e" * 16
BINARY = "fa4e" * 16
COMMIT = "fa4e" * 10
VERSION = "2000-01-01T00:00:00Z"
RELEASE = "RELEASE.2000-01-01T00-00-00Z"
IMAGE = "/var/lib/fake/fake.img"
GEOM = "16777216"  # statvfs f_blocks = the gate F fixture's, so the real gate F can evaluate the fake record
# The image's block count = inode count (mkfs -i 4096, as on prod); f_blocks is below it by the ext4 overhead, so
# f_files − f_blocks is the A1 (ii) margin before baseline (prod: 866,139). FAKE: 1,048,576.
INODES = "17825792"
E4_E0 = 30  # FAKE baseline e0 on a fresh filesystem
LAB_E4_REL = "docs/adr/0035-benchmark/qualification/lab-e4-fake-2000-01-01/runs/e4-e4.json"
LAB_E4_BYTES = b'{"FAKE": "lab full-scale E4 run output", "PASS_inodes_remain_when_blocks_exhausted": true}\n'
FEATURES = "has_journal ext_attr resize_inode dir_index filetype needs_recovery extent 64bit flex_bg sparse_super large_file huge_file dir_nlink extra_isize metadata_csum"
CAND = {"sourceSha": "fa4e" * 10, "apiDigest": "sha256:" + "a" * 64, "ingestionDigest": "sha256:" + "b" * 64}
AMENDMENT = "Amendment note (2000-01-01, FAKE-E7-CARRY)"
NORMALIZED = "api.fake=1\ndrive.max_timeout=30s\nscanner.speed=default\nstorage_class=<unknown-subsystem>"
CFG_HASH = "sha256:" + hashlib.sha256(NORMALIZED.encode()).hexdigest()


def w(root, rel, content):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content if isinstance(content, str) else json.dumps(content, indent=2) + "\n")


def baseline(phase, at):
    kv = [("kernel", KERNEL), ("arch", "x86_64"), ("mount.source", "/dev/loop9"), ("mount.fstype", "ext4"),
          ("mount.options", "rw,relatime"), ("loop.backing", IMAGE), ("loop.discard_max_bytes", "0"), ("fs.uuid", UUID),
          ("fs.block_count", INODES), ("fs.inode_count", INODES), ("fs.block_size", "4096"), ("fs.inode_size", "256"),
          ("fs.features", FEATURES), ("statvfs.frsize", "4096"), ("statvfs.blocks", GEOM), ("statvfs.files", INODES),
          ("image.hole_blocks", "0"), ("mount.other_entries", "none")]
    return (f"# n1-fs-baseline phase={phase} at={at} host={HOST}\n"
            + "".join(f"     {k}={v}\nOK   {k}: fake\n" for k, v in kv) + "# verdict OK\n")


def capture(objects):
    info = {"status": "success", "info": {"mode": "online", "objects": {"count": objects},
            "backend": {"backendType": "Erasure", "onlineDisks": 1},
            "servers": [{"state": "online", "version": VERSION, "commitID": COMMIT, "drives": [{"path": "/data"}]}]}}
    return (json.dumps(info) + "\n=====CONFIG\n### api\napi fake=1 \n### rc=0\n### drive\ndrive max_timeout=30s \n### rc=0\n"
            "### storage_class\nmc: <ERROR> Unable to get server '[storage_class]' config: unknown subsystem: storage_class.\n"
            "### rc=1\n### scanner\nscanner speed=default \n### rc=0\n")


def harness_pass(root, name, rows):
    d = f"qualification-9a/{name}"
    w(root, f"{d}/results-slow.jsonl", "".join(json.dumps(r) + "\n" for r in rows))
    sweep = [{"key": r["key"], "scenario": r["scenario"], "exists_at_sweep": True, "appeared_after_poll_window": False} for r in rows]
    w(root, f"{d}/sweep-slow.jsonl", "".join(json.dumps(s) + "\n" for s in sweep + [{"summary": True, "keys": len(rows), "appeared_after_poll_window": 0}]))
    w(root, f"{d}/uname.txt", f"Linux fake {KERNEL} #0-Fake SMP x86_64 GNU/Linux\n")
    w(root, f"{d}/mount.txt", "/dev/mapper/fakeslow on /data type ext4 (rw,relatime)\n")
    w(root, f"{d}/minio-binary.sha256", f"{BINARY}  minio\n")
    w(root, f"{d}/harness-minio-capture.txt", capture(len(rows)))


def experiment(n, e7_mode):
    carried = n == 7 and e7_mode == "carried"
    e = {"schema": "testinbox.n1-experiment/1", "experiment": f"E{n}", "verdict": "PASS",
         "criterion": f"FAKE criterion text for E{n}", "startedAt": f"2000-01-01T{n:02d}:00:00Z",
         "completedAt": f"2000-01-01T{n:02d}:30:00Z", "venue": "carried" if carried else "final-filesystem",
         "filesystemUuid": "fake-staging-uuid" if carried else UUID, "kernelRelease": "fake-staging-kernel" if carried else KERNEL,
         "minioPlatformMemberDigest": MEMBER, "artifact": dict(CAND) if n in (5, 6, 8, 9, 10, 11) else None,
         "measurements": {}, "rawFiles": [f"experiments/E{n}/run.log"] + ([f"experiments/E{n}/a1vi-census.txt"] if n in (2, 7) else [])}
    if n in (1, 2, 3):
        e["measurements"] = {"oMaxBytes": 24576, "worstObjectOverheadBytes": 20000 + n}
    if n == 4:
        # E4 production-host (ADR-035 A1): (i)/(ii) read-only before A1 (iii), the (vi) census at 12:10, the (iv) ENOSPC
        # run at 12:25 (a census summary step; after a census at the +15 min limit, 12:21); the members agreed with Ops (#12 1701, 1710).
        e["startedAt"], e["completedAt"] = "2000-01-01T11:50:00Z", "2000-01-01T12:40:00Z"
        m = int(INODES) - int(GEOM) - E4_E0
        e["measurements"] = {
            "e0": E4_E0, "marginInodes": m, "marginRatio": round(m / int(GEOM), 6),
            "enospc": {"ffreeAtEnospc": 30530, "exhaustedAt": "f_bavail", "answers": {"507": 827, "ENOSPC": 0},
                       "minioUid": 1000, "ballastBytes": 68719476736 - 1073741824, "bandBytes": 1073741824},
            "labFullScaleEvidence": {"path": LAB_E4_REL, "sha256": hashlib.sha256(LAB_E4_BYTES).hexdigest()}}
    if n == 2:
        e["measurements"].update(metadataBytesMeasured=41943040, metadataBytesScope="minio-sys+unattributed")
    if n == 7:
        e["measurements"] = {"metadataInodesMeasured": 157, "metadataInodesScope": "minio-sys",
                             "metadataBytesMeasured": 52428800, "metadataBytesScope": "minio-sys+unattributed"}
    if carried:
        e["startedAt"], e["completedAt"] = "1999-12-01T00:00:00Z", "1999-12-02T00:00:00Z"
        e["sourceEvidence"] = "fake/staging/e7-corpus/"
    return e


def att_counts(n, with_raw):
    """The harness's split of n objects into attachment-layout messages (e1e3e4.py a1iii_att_counts, #124 6698831482):
    message m carries 1 + (m % 8) attachments (+ its raw.eml when with_raw); the last is shortened, never to a lone raw.eml."""
    out, left, m, lone = [], n, 0, 2 if with_raw else 1
    while left > 0:
        take = min(1 + (m % 8) + (1 if with_raw else 0), left)
        if 0 < left - take < lone:
            take = take - 1 if take - 1 >= lone else take + 1
        out.append(take - (1 if with_raw else 0))
        left -= take
        m += 1
    return out


DESCRIPTION = ("FAKE phase description; for attachment-layout messages in the 0 B and 1 B phases, the message's own "
               "raw.eml is written and accounted in the 2–512 B phase")


def a1iii(boot, census):
    """A1 (iii) as Ops' harness writes it (`e1e3e4.py <lab> a1iii`: segment, phase and summary records with the raw
    statvfs pairs) and as n1-bundle.py wraps it (experiments/A1iii.json). 20 446 objects: per phase 1 000 single-object
    raw.eml + 1 000 attachment-layout objects per size; the 0 B / 1 B attachment messages' raw.eml (446) are written in
    the 2-512 B phase. The statvfs figures are FAKE and chosen so every ratio is < 1. It runs last (12:00-12:06), as
    window N1-A2 does, and `census` is its own (vi) census as the bundle names it (measurements.census)."""
    st = {"files": int(INODES), "ffree": int(INODES) - 40, "blocks": int(GEOM), "bfree": int(GEOM) - 5000,
          "bavail": int(GEOM) - 5000, "frsize": 4096}
    clock = [12 * 3600]  # 12:00:00, one sample every 5 s

    def sample(d_inodes=0, d_blocks=0):
        st["ffree"] -= d_inodes
        st["bfree"] -= d_blocks
        st["bavail"] -= d_blocks
        clock[0] += 5
        h, m, sec = clock[0] // 3600, clock[0] // 60 % 60, clock[0] % 60
        return dict(st, at=f"2000-01-01T{h:02d}:{m:02d}:{sec:02d}Z")

    def delta(b, a):
        di = (a["files"] - a["ffree"]) - (b["files"] - b["ffree"])
        db = (a["blocks"] - a["bfree"]) - (b["blocks"] - b["bfree"])
        return {"dInodes": di, "dBlocks": db, "ratio": round(di / db, 6)}

    plan, deferred = [], 0
    for phase, sizes in (("0B", (0,)), ("1B", (1,)), ("2-512B", (2, 16, 64, 128, 255, 256, 511, 512))):
        with_raw, segs = phase == "2-512B", []
        for size in sizes:
            segs.append(dict(size=size, layout="single", role="raw.eml", objects=1000, messages=1000, inboxes=1000, apm=[]))
            counts = att_counts(1000, with_raw)
            segs.append(dict(size=size, layout="attachments", role="raw.eml+attachments" if with_raw else "attachments (raw.eml deferred)",
                             objects=1000, messages=len(counts), inboxes=-(-len(counts) // 10), apm=counts))
            if not with_raw:
                deferred += len(counts)
        if with_raw:
            for i, size in enumerate(sizes):
                n = len(range(i, deferred, len(sizes)))
                segs.append(dict(size=size, layout="attachments", role="deferred raw.eml (0 B / 1 B attachment messages)",
                                 objects=n, messages=0, inboxes=0, apm=[]))
        plan.append((phase, segs))
    recs, phases = [], []
    for phase, segs in plan:
        pb = sample()
        for sg in segs:
            b = sample()
            a = sample(3 * sg["objects"], 3 * sg["objects"] + sg["objects"] // 20 + 1)
            recs.append({"record": "segment", "phase": phase, "size": sg["size"], "layout": sg["layout"], "role": sg["role"],
                         "objects": sg["objects"], "messages": sg["messages"], "inboxes": sg["inboxes"],
                         "attachmentsPerMessage": sg["apm"], "putFailures": 0, "failureSample": [], "before": b, "after": a,
                         **delta(b, a)})
        pa = sample(2, 4)  # settle()
        r = {"record": "phase", "phase": phase, "description": f"{phase}: {DESCRIPTION}", "objects": sum(sg["objects"] for sg in segs),
             "before": pb, "after": pa, **delta(pb, pa)}
        recs.append(r)
        phases.append(r)
    started, completed = "2000-01-01T12:00:00Z", "2000-01-01T12:06:00Z"
    overall = delta(phases[0]["before"], phases[-1]["after"])
    per_phase = {r["phase"]: {k: r[k] for k in ("dInodes", "dBlocks", "ratio")} for r in phases}
    objects = {r["phase"]: r["objects"] for r in phases}
    segs = [r for r in recs if r["record"] == "segment"]

    def total(rs):
        di, db = sum(r["dInodes"] for r in rs), sum(r["dBlocks"] for r in rs)
        return {"dInodes": di, "dBlocks": db, "ratio": round(di / db, 6)}
    per_phase_layout = {ph: {lay: total([r for r in segs if r["phase"] == ph and r["layout"] == lay]) for lay in ("attachments", "single")}
                        for ph in objects}
    per_layout = {lay: total([r for r in segs if r["layout"] == lay]) for lay in ("attachments", "single")}
    per_size = {str(z): total([r for r in segs if r["size"] == z]) for z in sorted({r["size"] for r in segs})}
    population = {"total": sum(objects.values()), "phases": {ph: {"objects": n} for ph, n in objects.items()},
                  "multiAttachmentMessages": sum(1 for r in segs for k in r["attachmentsPerMessage"] if k >= 2),
                  "messages": sum(r["messages"] for r in segs), "inboxes": sum(r["inboxes"] for r in segs)}
    recs.append({"record": "summary", "experiment": "A1iii", "lab": "fake", "startedAt": started, "completedAt": completed,
                 "kernel": KERNEL, "bootId": boot, "workspace": "fa4e0000-0000-4000-8000-0000000a1111", "population": population,
                 "before": phases[0]["before"], "after": phases[-1]["after"], "perPhase": per_phase,
                 "perPhaseLayout": per_phase_layout, "perLayout": per_layout, "perSize": per_size, "overall": overall,
                 "putFailures": 0, "problems": [], "PASS": True})
    exp = {"schema": "testinbox.n1-experiment/1", "experiment": "A1iii", "verdict": "PASS",
           "criterion": "FAKE criterion text for A1 (iii)", "startedAt": started, "completedAt": completed,
           "venue": "final-filesystem", "venueDetail": "FAKE qualification MinIO data on the final filesystem",
           "filesystemUuid": UUID, "kernelRelease": KERNEL, "minioPlatformMemberDigest": MEMBER, "artifact": None,
           "measurements": {"ratioOverall": overall, "ratioPerPhase": per_phase, "ratioPerPhaseLayout": per_phase_layout,
                            "ratioPerLayout": per_layout, "ratioPerSize": per_size, "objects": sum(objects.values()),
                            "objectsPerPhase": objects, "harnessPass": True, "harnessKernel": KERNEL, "harnessBootId": boot,
                            "census": census,
                            "rawRatioDeferralNote": "FAKE: 0 B / 1 B attachment messages get their raw.eml in the 2-512 B phase"},
           "run": "fake-run", "problems": [],
           "rawFiles": ["experiments/A1iii/a1iii-fake.json", "experiments/A1iii/a1vi-census.txt"]}
    return exp, recs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--e7", choices=("final-filesystem", "carried"), default="final-filesystem")
    ap.add_argument("--venue-9a", choices=("final-filesystem", "own-image"), default="final-filesystem")
    a = ap.parse_args()
    out = os.path.abspath(a.out)
    root = os.path.join(out, "bundle")
    shutil.rmtree(out, ignore_errors=True)
    rid = f"production-amd64-{HOST}-2000-01-01"
    manifest = {"schema": "testinbox.n1-bundle/1", "recordId": rid, "host": HOST, "candidate": CAND,
                "e7": {"mode": "final-filesystem"} if a.e7 == "final-filesystem" else {"mode": "carried", "amendment": AMENDMENT}}
    w(root, "MANIFEST.json", manifest)
    w(root, "identity/fs-baseline-post-reboot.txt", baseline("post-reboot", "2000-01-01T00:00:00Z"))
    w(root, "identity/fs-baseline-post-cleanup.txt", baseline("post-cleanup", "2000-01-01T23:00:00Z"))
    w(root, "identity/dumpe2fs-h.txt", f"Filesystem UUID:          {UUID}\nFilesystem features:      {FEATURES}\n"
                                       f"Block count:              {INODES}\nInode count:              {INODES}\n"
                                       "Block size:               4096\nInode size:               256\n"
                                       "First inode:              11\n")
    w(root, "identity/itable-zeroed.txt", "FAKE explanation of the itable_zeroed group count.\n")
    w(root, "identity/minio-process-uid.txt", "1000 fakeuser\n")
    w(root, "identity/upload-impl-identity.txt", f"FAKE candidate {CAND['sourceSha']} StorageProtocol adr035-presigned-put-v2\n")
    w(root, "identity/qualification-check-observed.json", {
        "tool": "infra/testinbox/storage/qualification-check.py", "normalization": "ti-qc-runtime-config-v1",
        "record": "fake-proposed", "valid": False, "problems": ["the record is not enablement-eligible"],
        "observed": {"minio.platformMemberDigest": MEMBER, "minio.release": RELEASE, "minio.commitId": COMMIT,
                     "minio.mode": "single-node-single-drive", "minio.driveCount": "1", "minio.timeoutEnvironment": "none",
                     "minio.timeoutCliFlags": "none", "minio.runtimeConfig.hash": CFG_HASH,
                     "minio.storageClassInlineDefaults": "release-defaults", "host.kernelRelease": KERNEL,
                     "host.filesystemType": "ext4", "host.mountOptions": "rw,relatime", "network.directPath": "true",
                     "network.proxy": "none"},
        "runtimeConfigNormalized": NORMALIZED, "path": {"endpoint": "http://fake-minio:9000", "sharedNetworks": ["fake"], "proxyEnv": []}})
    w(root, "identity/minio-container-inspect.json", [{"Image": "sha256:" + "c" * 64, "Config": {"Env": ["PATH=/usr/bin", "MINIO_ROOT_USER=fake"]},
                                                       "Args": ["server", "/data"]}])
    w(root, "identity/minio-image-inspect.json", [{"Id": "sha256:" + "c" * 64, "RepoDigests": [f"fake.invalid/minio@{MEMBER}"]}])
    w(root, "identity/minio-imagetools-raw.json", {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json",
        "manifests": [{"digest": MEMBER, "platform": {"os": "linux", "architecture": "amd64"}},
                      {"digest": "sha256:" + "d" * 64, "platform": {"os": "linux", "architecture": "arm64"}}]})
    w(root, "identity/minio-binary.txt", f"{BINARY}  /usr/bin/minio\nminio version {RELEASE} (commit-id={COMMIT})\n")
    w(root, "identity/minio-admin-info.json", json.loads(capture(0).split("=====CONFIG")[0]))
    # §9a: main (base/freeze), v3, slow (18 dm trials); sweeps 18 min after each pass.
    main_rows = [{"scenario": "base-W", "key": f"base-W/{i}", "visible_after_thaw_s": 0.1} for i in range(3)] + \
                [{"scenario": "freeze45-W", "key": f"freeze45-W/{i}", "witness_during_freeze": "blocked"} for i in range(3)]
    v3_rows = [{"scenario": "v3-freeze45-W", "key": f"v3/{i}", "disk_at_freeze": False, "witness_during_freeze": "blocked"} for i in range(2)]
    slow_rows = [{"scenario": "slow-dm500ms-W" if i % 2 else "slow-dm2000ms-before-W", "key": f"slow/{i}", "slow_mode": "dm",
                  "throttle_ms": 500 if i % 2 else 2000, "commit_lag_after_first_completed_witness_s": 1.0 + i / 10} for i in range(18)]
    harness_pass(root, "main", main_rows)
    harness_pass(root, "v3", v3_rows)
    harness_pass(root, "slow", slow_rows)
    w(root, "qualification-9a/orchestrator.log",
      "2000-01-01T01:00:00Z pass main trials done rc=0\n2000-01-01T01:18:00Z sweep main start\n"
      "2000-01-01T02:00:00Z pass v3 trials done rc=0\n2000-01-01T02:18:00Z sweep v3 start\n"
      "2000-01-01T03:00:00Z pass slow trials done rc=0\n2000-01-01T03:18:00Z sweep slow start\n")
    venue = {"filesystemUuid": UUID, "kernelRelease": KERNEL, "minioPlatformMemberDigest": MEMBER,
             "slowWMechanism": "FAKE dm-delay, write delay 500 ms / 2000 ms"}
    if a.venue_9a == "own-image":
        # R3: Ops' own loop image on / under dm-delay: another uuid and geometry, the same type, features, sizes.
        venue.update({"filesystemUuid": VENUE_UUID, "9a": "loop-image-on-root + dm-delay(slow-W)"})
        w(root, "qualification-9a/venue-dumpe2fs-h.txt", f"Filesystem UUID:          {VENUE_UUID}\nFilesystem features:      {FEATURES}\n"
                                                         "Block count:              786432\nInode count:              786432\n"
                                                         "Block size:               4096\nInode size:               256\n")
    w(root, "qualification-9a/venue.json", venue)
    # (vi) census: the raw listing goes OUTSIDE the bundle (Ops keeps it off-repo); the bundle gets the summary only.
    # This listing is the post-A1iii census, the latest one, so it is the summary's top level.
    listing = "".join(f"{i} {n} {b} {sz} {t} {pth}\n" for i, n, b, sz, t, pth in [
        (2, 4, 8, 4096, "d", "/fake/mnt"), (11, 2, 32, 16384, "d", "/fake/mnt/lost+found"),
        (12, 3, 8, 4096, "d", "/fake/mnt/qual"), (13, 5, 8, 4096, "d", "/fake/mnt/qual/.minio.sys"),
        (14, 2, 8, 300, "f", "/fake/mnt/qual/.minio.sys/format.json"), (15, 4, 8, 4096, "d", "/fake/mnt/qual/.minio.sys/tmp"),
        (16, 3, 8, 4096, "d", "/fake/mnt/qual/.minio.sys/tmp/.trash"), (17, 2, 8, 4096, "d", "/fake/mnt/qual/.minio.sys/tmp/.trash/aa00"),
        (18, 1, 8, 600, "f", "/fake/mnt/qual/.minio.sys/tmp/.trash/aa00/xl.meta"), (19, 2, 8, 4096, "d", "/fake/mnt/qual/.minio.sys/tmp/5d1c-staging"),
        (20, 2, 8, 4096, "d", "/fake/mnt/qual/.minio.sys/multipart"), (21, 3, 8, 4096, "d", "/fake/mnt/qual/fakebucket"),
        (22, 3, 8, 4096, "d", "/fake/mnt/qual/fakebucket/fake-ws"), (23, 2, 8, 4096, "d", "/fake/mnt/qual/fakebucket/fake-ws/fake-inbox"),
        (24, 2, 8, 4096, "d", "/fake/mnt/qual/fakebucket/fake-ws/fake-inbox/fake-msg"),
        (25, 2, 8, 4096, "d", "/fake/mnt/qual/fakebucket/fake-ws/fake-inbox/fake-msg/raw.eml"),
        (26, 1, 8, 412, "f", "/fake/mnt/qual/fakebucket/fake-ws/fake-inbox/fake-msg/raw.eml/xl.meta")]).encode()
    with open(os.path.join(out, "census-raw.txt"), "wb") as f:
        f.write(listing)
    lsha = hashlib.sha256(listing).hexdigest()
    boot = "fa4e0000-0000-4000-8000-00000000b007"

    def census_entry(exp, after_drain, taken, completed, sha, nbytes, lines, distinct, used, entries):
        """One `censuses` entry as n1-bundle.py census-summary writes it (#124 6700351383)."""
        return {"experiment": exp, "afterDefinedDrain": after_drain, "takenAt": taken, "experimentCompletedAt": completed,
                "run": "fake-run", "bootId": boot, "kernelRelease": KERNEL, "filesystemUuid": UUID,
                "distinctInodes": distinct, "blocklessInodes": 0, "listedBelowFirstInode": 1,
                "reconcileGap": used - (distinct + 10 - 1), "inodesUsed": used,
                "inodesBelowFirstInode": 10, "inflightTolerance": entries["tmp"] + entries["multipart"],
                "entries": entries, "entriesAsPrinted": dict(entries),
                "listing": {"sha256": sha, "bytes": nbytes, "lines": lines}, "source": f"experiments/{exp}/a1vi-census.txt"}
    censuses = [
        census_entry("E2", True, "2000-01-01T02:45:00Z", "2000-01-01T02:30:00Z", "e2" * 32, 4096, 120, 117, 126,
                     {"tmp": 0, "trash": 48, "multipart": 0}),
        census_entry("E7", False, "2000-01-01T07:35:00Z", "2000-01-01T07:30:00Z", "e7" * 32, 2048, 60, 57, 66,
                     {"tmp": 1, "trash": 2, "multipart": 0}),
        census_entry("A1iii", False, "2000-01-01T12:10:00Z", "2000-01-01T12:06:00Z", lsha, len(listing), 17, 17, 26,
                     {"tmp": 1, "trash": 2, "multipart": 0})]
    latest = censuses[-1]
    for n in range(1, 12):
        w(root, f"experiments/E{n}.json", experiment(n, a.e7))
        w(root, f"experiments/E{n}/run.log", f"FAKE run output E{n}\n")
    a1, a1_raw = a1iii(boot, {"takenAt": latest["takenAt"], "run": "fake-run", "kernelRelease": KERNEL, "bootId": boot,
                              "filesystemUuid": UUID, "distinctInodes": 17, "blocklessInodes": 0, "entries": latest["entries"],
                              "listingSha256": lsha, "maxLagSeconds": 900})
    w(root, "experiments/A1iii.json", a1)
    w(root, "experiments/A1iii/a1iii-fake.json", json.dumps(a1_raw, indent=1) + "\n")
    for c in censuses:
        w(root, c["source"], f"FAKE n1-a2-check.sh census output for {c['experiment']}: PASS\n")
    w(root, "census/summary.json", {
        "schema": "testinbox.n1-census-summary/1", "takenAt": latest["takenAt"],
        "takenAtDefinition": "FAKE: the latest census in censuses", "topLevelCensusOf": latest["experiment"],
        "listing": {"sha256": lsha, "bytes": len(listing), "lines": 17,
                    "custody": {"heldBy": "FAKE Ops", "location": "off-repo", "mode": "0700",
                                "retainedUntil": "30 days after the record PR merges"}},
        "census": {"distinctInodes": 17, "blocklessInodes": 0, "listedBelowFirstInode": 1, "entries": dict(latest["entries"])},
        "reconciliation": {"inodesUsed": 26, "inodesBelowFirstInode": 10},
        "steps": [{"step": f"(vi) after {c['experiment']}", "at": c["takenAt"], "kernelRelease": KERNEL, "bootId": boot,
                   "filesystemUuid": UUID} for c in censuses]
                 + [{"step": "(iv) ENOSPC run in the 1 GiB ballast band", "at": "2000-01-01T12:25:00Z", "kernelRelease": KERNEL,
                     "bootId": boot, "filesystemUuid": UUID}],
        "censuses": censuses,
        "attestation": {"by": "FAKE Ops", "at": "2000-01-01T22:30:00Z", "listingSha256": lsha}})
    # Gate F evidence: the repository's gate F fixture with the fake filesystem and this record id.
    ev = json.load(open(os.path.join(REAL_REPO, "scripts/testdata/storage-activation/filesystem/evidence-good.json")))
    ev.update(qualificationRecordId=rid, qualificationValid=1, collectedAt="2000-01-01T23:30:00Z")
    ev["filesystem"] = {"uuid": UUID, "type": "ext4", "mountOptions": "rw,relatime", "mountSource": IMAGE}
    ev["preallocation"]["holeBytes"] = 0
    ev["otherEntriesOnMount"] = []
    ev["statvfs"]["files"] = int(INODES)
    ev["declared"]["metadataBudgetBytes"] = 1073741824  # the production M (owner decision 2026-11-05)
    w(root, "gate-f/filesystem-evidence.json", ev)
    with open(os.path.join(root, "SHA256SUMS"), "w") as f:
        for dp, _, files in sorted(os.walk(root)):
            for fn in sorted(files):
                rel = os.path.relpath(os.path.join(dp, fn), root)
                if rel != "SHA256SUMS":
                    f.write(f"{hashlib.sha256(open(os.path.join(dp, fn), 'rb').read()).hexdigest()}  {rel}\n")
    raw = open(os.path.join(root, "identity/minio-imagetools-raw.json"), "rb").read()
    w(out, "expected.json", {
        "schema": "testinbox.production-expected/1", "host": HOST, "architecture": "x86_64", "kernelRelease": KERNEL,
        "minio": {"imageIndexDigest": "sha256:" + hashlib.sha256(raw).hexdigest(), "platformMemberDigest": MEMBER,
                  "release": RELEASE, "commitId": COMMIT, "binarySha256": BINARY, "mode": "single-node-single-drive",
                  "driveCount": 1, "timeoutEnvironment": {}, "timeoutCliFlags": [], "runtimeConfigHash": CFG_HASH,
                  "storageClassInlineDefaults": "release-defaults"},
        "filesystem": {"uuid": UUID, "type": "ext4", "mountOptions": "rw,relatime", "mountSource": IMAGE, "blockSize": 4096,
                       "blockCount": int(INODES), "inodeCount": int(INODES), "inodeSize": 256, "statvfsBlocks": int(GEOM), "features": FEATURES},
        "network": {"directPath": True, "proxy": "none", "tls": False},
        "declared": {"metadataBudgetBytes": 1073741824}})
    rel_proto = "backend/application/src/main/kotlin/email/testinbox/application/storage/StorageProtocol.kt"
    rel_adr = "docs/adr/0035-physical-storage-bound-at-ingest.md"
    w(os.path.join(out, "repo"), rel_proto, open(os.path.join(REAL_REPO, rel_proto)).read())
    w(os.path.join(out, "repo"), rel_adr, "# FAKE ADR-035 text\n" + (f"\n## {AMENDMENT}\n" if a.e7 == "carried" else ""))
    # A1 (v): the lab full-scale E4, COMMITTED in the (fake) repository, as the real one is under lab-e4-*/.
    lab = os.path.join(out, "repo", LAB_E4_REL)
    os.makedirs(os.path.dirname(lab), exist_ok=True)
    with open(lab, "wb") as f:
        f.write(LAB_E4_BYTES)
    git = ["git", "-C", os.path.join(out, "repo"), "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
           "-c", "user.name=FAKE", "-c", "user.email=fake@example.invalid"]
    env = dict(os.environ, GIT_AUTHOR_DATE="2000-01-01T00:00:00Z", GIT_COMMITTER_DATE="2000-01-01T00:00:00Z")
    for cmd in (["init", "-q"], ["add", "-A"], ["commit", "-q", "-m", "FAKE repository"]):
        subprocess.run(git + cmd, check=True, env=env, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
