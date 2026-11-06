#!/usr/bin/env python3
"""ADR-035 metadata accounting: the ONE counting definition of MinIO metadata, in bytes and inodes.

PROPOSED amendment note docs/adr/0035-amendment-note-metadata-inodes-PROPOSED.md ("one definition of MinIO metadata").
This file is the reference implementation. The Ops fs-monitor (`dir_blocks_and_inodes`), the gate F evidence collector
(`collect-filesystem-evidence.py`) and the N1 qualification tooling (E2, E7) copy `metadata_accounting` (with its
module-level helpers) verbatim, so gate F, the monitor and the qualification record count the same allocations.
scripts/adr035-metadata-accounting.test.sh proves the classification.

Bytes, scope `minio-sys+unattributed`:

    metadataBytes = minioSysBytes + unattributedBytes

    minioSysBytes      every inode under <data>/.minio.sys, the directory included, except the CONTENTS of
                       <data>/.minio.sys/tmp/.trash (trash is W, `trash_bytes`). The `.trash` directory's own blocks
                       stay here, as annex §3.6 puts them in M (they are also in `du -s .trash`: a few blocks counted
                       twice, never zero times).
    unattributedBytes  every allocation on the mount outside .minio.sys that no object is charged for:
      fsRoot                 every inode outside .minio.sys and the bucket (mount root, lost+found, the data directory)
      bucketRoot             every block of <data>/<bucket> itself
      occupiedGrowth         for a directory under the bucket that IS, is INSIDE, or CONTAINS an object directory:
                             every block beyond its first
      unoccupiedDirectories  for a directory under the bucket that holds no object: every block
      strayFiles             a non-directory under the bucket that is not part of an object
      unreferencedDataDirs   every subdirectory of an object directory that its xl.meta does not reference (an old
                             `<dataDir>/part.N` left by a crashed or retried overwrite), its whole subtree

    An object directory is a directory under the bucket that directly holds a regular file named `xl.meta`
    (MinIO's `<bucket>/<key>/xl.meta`). Part of an object, charged by phi (O_max and the payload): that `xl.meta`, every
    inode inside a REFERENCED data directory (`<key>/<dataDir>/part.N`), and the FIRST block of every directory that
    is, is inside, or contains an object directory. A data directory is referenced when its name is a UUID whose 16
    raw bytes occur in the object's xl.meta (MinIO stores `DDir` as a 16-byte msgpack bin). This needs no msgpack
    parser, and it fails safe: an xl.meta that cannot be read, or encodes the id another way, references nothing, so
    every data directory of that object is counted (over-counted, never under-counted).

Inodes. `metadataInodes` (scope `minio-sys`): every inode under <data>/.minio.sys, the directory and .trash included
(`find <data>/.minio.sys -xdev | wc -l`). Reported apart: `trashInodes` (the contents of .trash) and
`metadataInodesExclTrash` = `metadataInodes - trashInodes` (scope `minio-sys-excl-trash`, valid only with an armed
inode-headroom latch; see the note §8.2).

Allocated bytes are `st_blocks * 512` (what `du -B1` counts, never the apparent size). Nothing follows a symlink and
nothing crosses into another filesystem. An entry that vanishes mid-walk is skipped (MinIO renames while we walk). The
walk is iterative, so no directory depth can exhaust the interpreter's stack.

It FAILS CLOSED (exit 1, nothing printed on stdout) on ANY exception during the walk (a PermissionError, an I/O
error), and unless the mountpoint, the data directory and <data>/.minio.sys
exist, are directories, are on the mount's filesystem, and the data directory is the mountpoint or under it. A missing
bucket directory is reported as `bucketPresent: false` (legitimate only at a 0-object baseline); gate F refuses it.

Usage:
  adr035-metadata-accounting.py --mount <mountpoint> --data-dir <minio data dir> --bucket <bucket> [--block-size B]
prints one JSON object (see `metadata_accounting`). Exit 1 when the layout is refused, 2 on a usage error.
"""
import argparse, json, os, stat, sys, uuid

METADATA_BYTES_SCOPE = "minio-sys+unattributed"
METADATA_INODES_SCOPE = "minio-sys"


class AccountingRefused(Exception):
    """The layout cannot be measured as defined: never report a figure for it."""


def _lstat(path):
    try:
        return os.lstat(path)
    except (FileNotFoundError, NotADirectoryError):
        return None


def _entries(path):
    try:
        with os.scandir(path) as it:
            return list(it)
    except (FileNotFoundError, NotADirectoryError):
        return []


def _subtree(path, st, dev, skip=()):
    """(allocated bytes, inode count) of path and everything under it on filesystem `dev`, minus `skip` subtrees."""
    size = count = 0
    stack = [(path, st)]
    while stack:
        p, s = stack.pop()
        if p in skip or s is None or s.st_dev != dev:
            continue
        size, count = size + s.st_blocks * 512, count + 1
        if stat.S_ISDIR(s.st_mode):
            stack.extend((e.path, _lstat(e.path)) for e in _entries(p))
    return size, count


def _referenced_data_dirs(object_dir, names):
    """The subdirectory names of an object directory that its xl.meta references (fail safe: none when unreadable)."""
    try:
        with open(os.path.join(object_dir, "xl.meta"), "rb") as f:
            meta = f.read()
    except OSError:
        return set()
    refs = set()
    for n in names:
        try:
            if uuid.UUID(n).bytes in meta:
                refs.add(n)
        except ValueError:
            pass
    return refs


def metadata_accounting(mount, data_dir, bucket, block_size=None):
    """ADR-035 metadata accounting (bytes scope minio-sys+unattributed, inodes scope minio-sys). Copy verbatim."""
    mount, data_dir = os.path.realpath(mount), os.path.realpath(data_dir)
    sys_dir = os.path.join(data_dir, ".minio.sys")
    trash_dir = os.path.join(sys_dir, "tmp", ".trash")
    bucket_dir = os.path.join(data_dir, bucket)
    mount_st, data_st, sys_st = _lstat(mount), _lstat(data_dir), _lstat(sys_dir)
    for name, path, st in (("mountpoint", mount, mount_st), ("data directory", data_dir, data_st),
                           (".minio.sys", sys_dir, sys_st)):
        if st is None or not stat.S_ISDIR(st.st_mode):
            raise AccountingRefused(f"the {name} {path} does not exist or is not a directory")
    dev = mount_st.st_dev
    if data_dir != mount and not data_dir.startswith(mount.rstrip("/") + "/"):
        raise AccountingRefused(f"the data directory {data_dir} is not the mountpoint {mount} or under it")
    for name, path, st in (("data directory", data_dir, data_st), (".minio.sys", sys_dir, sys_st)):
        if st.st_dev != dev:
            raise AccountingRefused(f"the {name} {path} is on another filesystem than the mountpoint {mount}")
    B = block_size or os.statvfs(mount).f_frsize
    out = {"fsRoot": 0, "bucketRoot": 0, "occupiedGrowth": 0, "unoccupiedDirectories": 0, "strayFiles": 0,
           "unreferencedDataDirs": 0}

    # .minio.sys: everything but the CONTENTS of .trash; the .trash directory's own blocks stay in M (annex §3.6).
    trash_st = _lstat(trash_dir)
    sys_bytes, sys_inodes = _subtree(sys_dir, sys_st, dev, skip=(trash_dir,))
    trash_bytes = trash_inodes = 0
    if trash_st is not None and trash_st.st_dev == dev:
        own = trash_st.st_blocks * 512
        sys_bytes, sys_inodes = sys_bytes + own, sys_inodes + 1
        trash_bytes, total = _subtree(trash_dir, trash_st, dev)
        trash_inodes = total - 1
    out["fsRoot"], _ = _subtree(mount, mount_st, dev, skip=(sys_dir, bucket_dir))

    bucket_st = _lstat(bucket_dir)
    bucket_present = bucket_st is not None
    if bucket_present:
        if not stat.S_ISDIR(bucket_st.st_mode) or bucket_st.st_dev != dev:
            raise AccountingRefused(f"the bucket {bucket_dir} is not a directory on the mount's filesystem")
        out["bucketRoot"] = bucket_st.st_blocks * 512

        class Frame:
            __slots__ = ("st", "inside", "occupied", "pending", "parent")

        def open_dir(path, st, inside, parent, refs):
            """Classify one directory's files now and return its frame, or None when the whole subtree is counted
            already (an unreferenced data directory). `refs` is the parent object directory's referenced set."""
            kids = _entries(path)
            holds_meta = any(e.name == "xl.meta" and e.is_file(follow_symlinks=False) for e in kids)
            if refs is not None and not inside and not holds_meta:
                if os.path.basename(path) in refs:
                    inside = True
                else:
                    out["unreferencedDataDirs"] += _subtree(path, st, dev)[0]
                    return None
            is_object = holds_meta and not inside
            f = Frame()
            f.st, f.inside, f.parent, f.occupied, f.pending = st, inside, parent, inside or is_object, []
            subdirs = []
            for e in kids:
                if e.is_dir(follow_symlinks=False):
                    s = _lstat(e.path)
                    if s is not None and s.st_dev == dev:
                        subdirs.append((e.path, e.name, s))
                elif not (inside or (is_object and e.name == "xl.meta" and e.is_file(follow_symlinks=False))):
                    s = _lstat(e.path)
                    out["strayFiles"] += s.st_blocks * 512 if s is not None else 0
            child_refs = _referenced_data_dirs(path, [n for _, n, _ in subdirs]) if is_object and subdirs else (
                set() if is_object else None)
            f.pending = [(p, s, child_refs) for p, _, s in subdirs]
            return f

        def close_dir(f):
            own = f.st.st_blocks * 512
            if f.occupied:
                out["occupiedGrowth"] += max(0, own - B)
            else:
                out["unoccupiedDirectories"] += own
            if f.parent is not None and f.occupied:
                f.parent.occupied = True

        for e in _entries(bucket_dir):
            s = _lstat(e.path)
            if s is None or s.st_dev != dev:
                continue
            if not stat.S_ISDIR(s.st_mode):
                out["strayFiles"] += s.st_blocks * 512
                continue
            stack = [open_dir(e.path, s, False, None, None)]
            while stack:
                top = stack[-1]
                if top.pending:
                    p, ps, refs = top.pending.pop()
                    child = open_dir(p, ps, top.inside, top, refs)
                    if child is not None:
                        stack.append(child)
                else:
                    close_dir(stack.pop())

    unattributed = sum(out.values())
    return {"metadataBytesScope": METADATA_BYTES_SCOPE, "metadataBytes": sys_bytes + unattributed,
            "minioSysBytes": sys_bytes, "unattributedBytes": unattributed, "unattributed": out,
            "bucketPresent": bucket_present, "trashBytes": trash_bytes,
            "metadataInodesScope": METADATA_INODES_SCOPE, "metadataInodes": sys_inodes + trash_inodes,
            "trashInodes": trash_inodes, "metadataInodesExclTrash": sys_inodes, "blockSizeBytes": B}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--mount", required=True, help="the dedicated filesystem's mountpoint")
    ap.add_argument("--data-dir", required=True, help="MinIO's data directory (holds .minio.sys and the bucket)")
    ap.add_argument("--bucket", required=True)
    ap.add_argument("--block-size", type=int, help="B; default statvfs f_frsize of the mount")
    a = ap.parse_args()
    if a.block_size is not None and a.block_size <= 0:
        print("error: --block-size must be positive", file=sys.stderr)
        return 2
    try:
        result = metadata_accounting(a.mount, a.data_dir, a.bucket, a.block_size)
    except AccountingRefused as e:
        print(f"adr035-metadata-accounting: REFUSED — {e}; no figure reported", file=sys.stderr)
        return 1
    except Exception as e:  # any other failure mid-walk (PermissionError, I/O error, ...): never a partial figure
        print(f"adr035-metadata-accounting: FAILED — {type(e).__name__}: {e}; no figure reported", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=1, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
