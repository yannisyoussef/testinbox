#!/usr/bin/env python3
"""ADR-035 metadata accounting: the ONE counting definition of MinIO metadata, in bytes and inodes.

PROPOSED amendment note docs/adr/0035-amendment-note-metadata-inodes-PROPOSED.md ("one definition of MinIO metadata").
This file is the reference implementation. The Ops fs-monitor (`dir_blocks_and_inodes`), the gate F evidence collector
(`collect-filesystem-evidence.py`) and the N1 qualification tooling (E2, E7) copy `metadata_accounting` verbatim, so
gate F, the monitor and the qualification record count the same allocations. scripts/adr035-metadata-accounting.test.sh
proves the classification.

Bytes, scope `minio-sys+unattributed`:

    metadataBytes = minioSysBytes + unattributedBytes

    minioSysBytes      every inode under <data>/.minio.sys, the directory included, EXCEPT <data>/.minio.sys/tmp/.trash
                       and its subtree (trash is W, `trash_bytes`). Unchanged: the observation's `minio_sys_bytes`.
    unattributedBytes  every allocation on the mount outside .minio.sys that no object is charged for:
      fsRoot                 every inode outside .minio.sys and the bucket (mount root, lost+found, the data directory)
      bucketRoot             every block of <data>/<bucket> itself
      occupiedGrowth         for a directory under the bucket that IS, is INSIDE, or CONTAINS an object directory:
                             every block beyond its first
      unoccupiedDirectories  for a directory under the bucket that holds no object: every block
      strayFiles             a non-directory under the bucket that is not part of an object

    An object directory is a directory under the bucket that directly holds a regular file named `xl.meta`
    (MinIO's `<bucket>/<key>/xl.meta`). Part of an object, charged by phi (O_max and the payload): that `xl.meta`, every
    inode inside the object directory's subdirectories (`<key>/<dataDir>/part.N`), and the FIRST block of every directory
    that is, is inside, or contains an object directory.

Inodes, scope `minio-sys` (unchanged): every inode under <data>/.minio.sys, the directory and .trash included
(`find <data>/.minio.sys -xdev | wc -l`).

Allocated bytes are `st_blocks * 512` (what `du -B1` counts, never the apparent size). Nothing follows a symlink and
nothing crosses into another filesystem. An entry that vanishes mid-walk is skipped (MinIO renames while we walk).

Usage:
  adr035-metadata-accounting.py --mount <mountpoint> --data-dir <minio data dir> --bucket <bucket> [--block-size B]
prints one JSON object (see `metadata_accounting`). Exit 2 on a usage error.
"""
import argparse, json, os, stat, sys

METADATA_BYTES_SCOPE = "minio-sys+unattributed"
METADATA_INODES_SCOPE = "minio-sys"


def metadata_accounting(mount, data_dir, bucket, block_size=None):
    """ADR-035 metadata accounting (bytes scope minio-sys+unattributed, inodes scope minio-sys). Copy verbatim."""
    mount, data_dir = os.path.realpath(mount), os.path.realpath(data_dir)
    sys_dir = os.path.join(data_dir, ".minio.sys")
    trash_dir = os.path.join(sys_dir, "tmp", ".trash")
    bucket_dir = os.path.join(data_dir, bucket)
    dev = os.lstat(mount).st_dev
    B = block_size or os.statvfs(mount).f_frsize
    out = {"fsRoot": 0, "bucketRoot": 0, "occupiedGrowth": 0, "unoccupiedDirectories": 0, "strayFiles": 0}

    def lstat(path):
        try:
            return os.lstat(path)
        except FileNotFoundError:
            return None

    def entries(path):
        try:
            with os.scandir(path) as it:
                return list(it)
        except (FileNotFoundError, NotADirectoryError):
            return []

    def subtree(path, st, skip=()):
        """(allocated bytes, inode count) of path and everything under it on this filesystem, minus `skip` subtrees."""
        if path in skip or st is None or st.st_dev != dev:
            return 0, 0
        size, count = st.st_blocks * 512, 1
        if stat.S_ISDIR(st.st_mode):
            for e in entries(path):
                ds, dc = subtree(e.path, lstat(e.path), skip)
                size, count = size + ds, count + dc
        return size, count

    def bucket_dir_walk(path, st, inside_object):
        """Classify one directory under the bucket; True if it is, is inside, or contains an object directory.
        Only directories and possible strays are stat'ed: an object's own files are charged by phi, not here."""
        kids = entries(path)
        is_object = any(e.name == "xl.meta" and e.is_file(follow_symlinks=False) for e in kids)
        occupied = inside_object or is_object
        for e in kids:
            if e.is_dir(follow_symlinks=False):
                s = lstat(e.path)
                if s is not None and s.st_dev == dev:
                    occupied = bucket_dir_walk(e.path, s, inside_object or is_object) or occupied
            elif not (inside_object or (is_object and e.name == "xl.meta" and e.is_file(follow_symlinks=False))):
                s = lstat(e.path)
                out["strayFiles"] += s.st_blocks * 512 if s is not None else 0
        own = st.st_blocks * 512
        if occupied:
            out["occupiedGrowth"] += max(0, own - B)
        else:
            out["unoccupiedDirectories"] += own
        return occupied

    sys_st, bucket_st = lstat(sys_dir), lstat(bucket_dir)
    sys_bytes, sys_inodes = subtree(sys_dir, sys_st, skip=(trash_dir,))
    trash_bytes, trash_inodes = subtree(trash_dir, lstat(trash_dir) if sys_st is not None else None)
    sys_inodes += trash_inodes
    out["fsRoot"], _ = subtree(mount, lstat(mount), skip=(sys_dir, bucket_dir))
    if bucket_st is not None and stat.S_ISDIR(bucket_st.st_mode):
        out["bucketRoot"] = bucket_st.st_blocks * 512
        for e in entries(bucket_dir):
            s = lstat(e.path)
            if s is None or s.st_dev != dev:
                continue
            if stat.S_ISDIR(s.st_mode):
                bucket_dir_walk(e.path, s, False)
            else:
                out["strayFiles"] += s.st_blocks * 512
    unattributed = sum(out.values())
    return {"metadataBytesScope": METADATA_BYTES_SCOPE, "metadataBytes": sys_bytes + unattributed,
            "minioSysBytes": sys_bytes, "unattributedBytes": unattributed, "unattributed": out,
            "trashBytes": trash_bytes, "metadataInodesScope": METADATA_INODES_SCOPE, "metadataInodes": sys_inodes,
            "blockSizeBytes": B}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--mount", required=True, help="the dedicated filesystem's mountpoint")
    ap.add_argument("--data-dir", required=True, help="MinIO's data directory (holds .minio.sys and the bucket)")
    ap.add_argument("--bucket", required=True)
    ap.add_argument("--block-size", type=int, help="B; default statvfs f_frsize of the mount")
    a = ap.parse_args()
    if not os.path.isdir(a.mount) or not os.path.isdir(a.data_dir):
        print("error: --mount and --data-dir must be directories", file=sys.stderr)
        return 2
    print(json.dumps(metadata_accounting(a.mount, a.data_dir, a.bucket, a.block_size), indent=1, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
