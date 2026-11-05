#!/usr/bin/env python3
"""Writes a FAKE, complete N1 evidence bundle for scripts/build-qualification-record.test.sh.

Every value is plainly fake (host fake-host-0, kernel 0.0.0-fake, uuid 00000000-…-0000000fa4e0, digests of fa4e…,
year 2000) so nothing here can be mistaken for production evidence. Deterministic: the same arguments always write
the same bytes, so the generator's output can be compared with a committed golden record.

    make-fake-bundle.py <out-dir> [--e7 final-filesystem|carried]
writes <out-dir>/bundle/ (with SHA256SUMS), <out-dir>/expected.json and <out-dir>/repo/ (a minimal repository
root: StorageProtocol.kt and an ADR-035 text that, with --e7 carried, contains the fake amendment note).
"""
import argparse, hashlib, json, os, shutil

HERE = os.path.dirname(os.path.abspath(__file__))
REAL_REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
HOST = "fake-host-0"
KERNEL = "0.0.0-fake"
UUID = "00000000-0000-4000-8000-0000000fa4e0"
MEMBER = "sha256:" + "fa4e" * 16
BINARY = "fa4e" * 16
COMMIT = "fa4e" * 10
VERSION = "2000-01-01T00:00:00Z"
RELEASE = "RELEASE.2000-01-01T00-00-00Z"
IMAGE = "/var/lib/fake/fake.img"
GEOM = "16777216"  # = the gate F fixture's statvfs, so the real gate F can evaluate the fake record
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
          ("fs.block_count", GEOM), ("fs.inode_count", GEOM), ("fs.block_size", "4096"), ("fs.inode_size", "256"),
          ("fs.features", FEATURES), ("statvfs.frsize", "4096"), ("statvfs.blocks", GEOM), ("statvfs.files", GEOM),
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
         "measurements": {}, "rawFiles": [f"experiments/E{n}/run.log"]}
    if n in (1, 2, 3):
        e["measurements"] = {"oMaxBytes": 24576, "worstObjectOverheadBytes": 20000 + n}
    if n == 2:
        e["measurements"].update(metadataBytesMeasured=41943040, metadataBytesScope="minio-sys+unattributed")
    if n == 7:
        e["measurements"] = {"metadataInodesMeasured": 157, "metadataInodesScope": "minio-sys",
                             "metadataBytesMeasured": 52428800, "metadataBytesScope": "minio-sys+unattributed"}
    if carried:
        e["startedAt"], e["completedAt"] = "1999-12-01T00:00:00Z", "1999-12-02T00:00:00Z"
        e["sourceEvidence"] = "fake/staging/e7-corpus/"
    return e


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--e7", choices=("final-filesystem", "carried"), default="final-filesystem")
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
                                       f"Block count:              {GEOM}\nInode count:              {GEOM}\n"
                                       "Block size:               4096\nInode size:               256\n")
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
    w(root, "qualification-9a/venue.json", {"filesystemUuid": UUID, "kernelRelease": KERNEL, "minioPlatformMemberDigest": MEMBER,
                                            "slowWMechanism": "FAKE dm-delay, write delay 500 ms / 2000 ms"})
    for n in range(1, 12):
        w(root, f"experiments/E{n}.json", experiment(n, a.e7))
        w(root, f"experiments/E{n}/run.log", f"FAKE run output E{n}\n")
    # Gate F evidence: the repository's gate F fixture with the fake filesystem and this record id.
    ev = json.load(open(os.path.join(REAL_REPO, "scripts/testdata/storage-activation/filesystem/evidence-good.json")))
    ev.update(qualificationRecordId=rid, qualificationValid=1, collectedAt="2000-01-01T23:30:00Z")
    ev["filesystem"] = {"uuid": UUID, "type": "ext4", "mountOptions": "rw,relatime", "mountSource": IMAGE}
    ev["preallocation"]["holeBytes"] = 0
    ev["otherEntriesOnMount"] = []
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
                       "blockCount": int(GEOM), "inodeCount": int(GEOM), "inodeSize": 256, "statvfsBlocks": int(GEOM), "features": FEATURES},
        "network": {"directPath": True, "proxy": "none", "tls": False}})
    rel_proto = "backend/application/src/main/kotlin/email/testinbox/application/storage/StorageProtocol.kt"
    rel_adr = "docs/adr/0035-physical-storage-bound-at-ingest.md"
    w(os.path.join(out, "repo"), rel_proto, open(os.path.join(REAL_REPO, rel_proto)).read())
    w(os.path.join(out, "repo"), rel_adr, "# FAKE ADR-035 text\n" + (f"\n## {AMENDMENT}\n" if a.e7 == "carried" else ""))


if __name__ == "__main__":
    main()
