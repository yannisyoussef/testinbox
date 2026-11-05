#!/usr/bin/env bash
# Proves scripts/adr035-metadata-accounting.py classifies every allocation the way the PROPOSED ADR-035 note
# ("one definition of MinIO metadata") says, on a fake MinIO data tree. The expected figures are computed
# independently from an explicit list of paths per class, with the allocated size the filesystem reports, so the
# suite holds on any filesystem (ext4 in CI; directories report 0 blocks on APFS). Each class is also mutated on its
# own: an object's own bytes never move the metadata figure, every unattributed class does. The walk fails closed on
# a layout it cannot measure, survives a directory depth beyond Python's recursion limit, and counts a data directory
# its object's xl.meta does not reference.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ACC="$SCRIPT_DIR/adr035-metadata-accounting.py"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0
ok() { echo "ok   — $1"; pass=$((pass + 1)); }
bad() { echo "FAIL — $1"; shift; [ $# -eq 0 ] || printf '%s\n' "$@" | sed 's/^/       /'; fail=$((fail + 1)); }

M="$TMP/mnt"; D="$M/data"; S="$D/.minio.sys"; K="$D/testinbox"
W1="$K/0b6c3f1e-0000-4000-8000-00000000ws01"; I1="$W1/0b6c3f1e-0000-4000-8000-0000000inb01"
mk() { mkdir -p "$(dirname "$1")"; head -c "$2" /dev/zero > "$1"; }
# mkmeta <path> <size> <uuid…>: an xl.meta whose DDir entries are 16-byte msgpack bins of the given data-dir ids.
mkmeta() {
    python3 - "$@" <<'EOF'
import os, sys, uuid
p, size, ids = sys.argv[1], int(sys.argv[2]), sys.argv[3:]
os.makedirs(os.path.dirname(p), exist_ok=True)
body = b"XL2 \x01\x00\x03\x00" + b"".join(b"\xa4DDir\xc4\x10" + uuid.UUID(i).bytes for i in ids)
open(p, "wb").write(body + b"\0" * max(0, size - len(body)))
EOF
}
DD1="3c000000-0000-4000-8000-000000000dd1"; ORPH="3c000000-0000-4000-8000-0000000000ff"
mkdir -p "$M/lost+found" "$S/multipart" "$K"
mk "$S/format.json" 300
mk "$S/buckets/testinbox/.usage-cache.bin" 9000
mk "$S/buckets/testinbox/.metacache/c1/block-0.s2" 70000
mk "$S/tmp/5d1c0000-staging/9f00-dd/part.1" 200000                       # an in-flight upload's staging: in M
mk "$S/tmp/.trash/aa00-deleted/x/raw.eml/xl.meta" 5000                    # trash: W, not M
mk "$S/tmp/.trash/aa00-deleted/x/raw.eml/77-dd/part.1" 300000
# msg1: an inline raw.eml and a large attachment (xl.meta + data dir); msg3: a message whose object is gone.
mk "$I1/m1/raw.eml/xl.meta" 3000
mkmeta "$I1/m1/attachments/a1/xl.meta" 600 "$DD1"
mk "$I1/m1/attachments/a1/$DD1/part.1" 400000
mk "$I1/m1/raw.eml/stray.tmp" 5000                                        # not part of the object: stray
mkdir -p "$I1/m2" "$W1/inbox-drained" "$I1/m3/raw.eml"                    # residue: no object below
mk "$I1/m3/junk" 7000
mk "$K/_probe/ing-1/0e00-probe/xl.meta" 400                               # a probe object (rule P, debt)
mk "$K/straytop.txt" 2000
# Grow the inbox directory past one block (ext4): 300 drained message directories, all unoccupied.
for i in $(seq 1 300); do mkdir "$I1/drained-message-directory-with-a-uuid-long-name-$i"; done

# expected <B>: the figures from the explicit class lists, sized by lstat (st_blocks * 512).
expected() {
    python3 - "$1" "$M" <<'EOF'
import os, sys
B, M = int(sys.argv[1]), sys.argv[2]
D = f"{M}/data"; S = f"{D}/.minio.sys"; K = f"{D}/testinbox"
W1 = f"{K}/0b6c3f1e-0000-4000-8000-00000000ws01"; I1 = f"{W1}/0b6c3f1e-0000-4000-8000-0000000inb01"
a = lambda p: os.lstat(p).st_blocks * 512
def tree(p, skip=None):
    out = [p]
    if os.path.isdir(p) and not os.path.islink(p):
        for n in os.listdir(p):
            q = os.path.join(p, n)
            if q != skip:
                out += tree(q, skip)
    return out
trash = f"{S}/tmp/.trash"
probe = [f"{K}/_probe", f"{K}/_probe/ing-1"]
probe_obj = f"{K}/_probe/ing-1/0e00-probe"
live_probe = os.path.isfile(f"{probe_obj}/xl.meta")
A1 = f"{I1}/m1/attachments/a1"
occupied = [W1, I1, f"{I1}/m1", f"{I1}/m1/raw.eml", f"{I1}/m1/attachments", A1,
            f"{A1}/3c000000-0000-4000-8000-000000000dd1"] + (probe + [probe_obj] if live_probe else [])
unoccupied = [f"{I1}/m2", f"{W1}/inbox-drained", f"{I1}/m3", f"{I1}/m3/raw.eml"] + ([] if live_probe else probe) + \
             [f"{I1}/{n}" for n in os.listdir(I1) if n.startswith("drained-message-directory")]
ex = lambda ps: [p for p in ps if os.path.lexists(p)]
stray = [f"{I1}/m1/raw.eml/stray.tmp", f"{I1}/m3/junk", f"{K}/straytop.txt"] + \
        ex([f"{I1}/m2/leftover.bin", f"{I1}/m1/attachments/a1/xl.meta.bkp"])
u = {"fsRoot": sum(a(p) for p in [M, f"{M}/lost+found", D] + ex([f"{M}/other-data.bin"])), "bucketRoot": a(K),
     "occupiedGrowth": sum(max(0, a(p) - B) for p in occupied),
     "unoccupiedDirectories": sum(a(p) for p in unoccupied), "strayFiles": sum(a(p) for p in stray),
     "unreferencedDataDirs": sum(a(q) for p in ex([f"{A1}/3c000000-0000-4000-8000-0000000000ff", f"{A1}/not-a-uuid"])
                                 for q in tree(p))}
sys_paths = tree(S, skip=trash)          # .trash and its contents excluded ...
trash_paths = tree(trash)                # ... .trash itself first
sys_bytes = sum(a(p) for p in sys_paths) + a(trash)   # the .trash directory's own blocks stay in M (annex §3.6)
print(f"{sys_bytes} {sum(u.values())} {sum(a(p) for p in trash_paths)} {len(sys_paths) + len(trash_paths)} "
      f"{len(trash_paths) - 1} {len(sys_paths) + 1} True " + " ".join(f"{k}={v}" for k, v in sorted(u.items())))
EOF
}
# actual <B>: the same line from the implementation under test.
actual() {
    python3 "$ACC" --mount "$M" --data-dir "$D" --bucket testinbox --block-size "$1" > "$TMP/actual.json" || return
    python3 - "$TMP/actual.json" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))
assert d["metadataBytes"] == d["minioSysBytes"] + d["unattributedBytes"], "metadataBytes is not the sum"
assert d["metadataBytesScope"] == "minio-sys+unattributed" and d["metadataInodesScope"] == "minio-sys", "scopes"
assert d["metadataInodes"] == d["trashInodes"] + d["metadataInodesExclTrash"], "inode split"
print(f"{d['minioSysBytes']} {d['unattributedBytes']} {d['trashBytes']} {d['metadataInodes']} {d['trashInodes']} "
      f"{d['metadataInodesExclTrash']} {d['bucketPresent']} " + " ".join(f"{k}={v}" for k, v in sorted(d["unattributed"].items())))
EOF
}
check() {  # check <case> <B>
    local e a
    e="$(expected "$2")"; a="$(actual "$2")"
    if [ -n "$e" ] && [ "$e" = "$a" ]; then ok "$1"; else bad "$1" "expected: $e" "actual:   $a"; fi
}
figure() { python3 "$ACC" --mount "$M" --data-dir "$D" --bucket testinbox --block-size "$1" | python3 -c "import json,sys; print(json.load(sys.stdin)['$2'])"; }

check "every class sized as the note defines it (B = filesystem block size)" 4096
check "the first-block rule: with B = 512 every occupied directory's blocks beyond 512 B are unattributed" 512

if [ "$(figure 4096 minioSysBytes)" -gt 0 ] && [ "$(figure 4096 trashBytes)" -gt 0 ]; then
    ok ".minio.sys and .trash are both measured (and kept apart)"
else bad ".minio.sys or .trash measured as 0"; fi

# Mutations: an object's own allocations never move the metadata figure; every unattributed class does.
before="$(figure 4096 metadataBytes)"
mk "$I1/m1/attachments/a1/$DD1/part.1" 2000000
mk "$I1/m1/raw.eml/xl.meta" 90000
mk "$S/tmp/.trash/aa00-deleted/x/raw.eml/77-dd/part.1" 2000000
if [ "$(figure 4096 metadataBytes)" = "$before" ]; then ok "an object's part file, its xl.meta and trash never move metadataBytes"
else bad "object or trash bytes moved metadataBytes ($before → $(figure 4096 metadataBytes))"; fi
check "the classes still hold after the objects grew" 4096

mutate() {  # mutate <case> <field> <command…>: the command must raise the field
    local c="$1" f="$2" b; shift 2
    b="$(figure 4096 "$f")"; "$@"
    if [ "$(figure 4096 "$f")" -gt "$b" ]; then ok "$c"; else bad "$c ($f stayed $b)"; fi
}
mutate "a stray file in the bucket tree is unattributed" unattributedBytes mk "$I1/m2/leftover.bin" 50000
mutate "a stray file beside an object's xl.meta is unattributed" unattributedBytes mk "$I1/m1/attachments/a1/xl.meta.bkp" 50000
mutate "a file in .minio.sys/tmp (in-flight staging or residue) is MinIO metadata" minioSysBytes mk "$S/tmp/77aa-staging/dd/part.1" 50000
mutate "a file in .minio.sys/multipart is MinIO metadata" minioSysBytes mk "$S/multipart/abc/upload/part.1" 50000
mutate "a file beside lost+found on the mount is unattributed (fsRoot)" unattributedBytes mk "$M/other-data.bin" 50000
mutate "a file in .trash counts as an inode under .minio.sys" metadataInodes mk "$S/tmp/.trash/bb00/f" 10
mutate "... and as a trash inode, reported apart" trashInodes mk "$S/tmp/.trash/bb01/f" 10
b="$(figure 4096 metadataInodesExclTrash)"; mk "$S/tmp/.trash/bb02/f" 10
if [ "$(figure 4096 metadataInodesExclTrash)" = "$b" ]; then ok "a trash inode never moves metadataInodesExclTrash"
else bad "a trash inode moved metadataInodesExclTrash"; fi
# An old data dir an overwrite left behind (the reviewer's 15 MB orphan part.1): not referenced by xl.meta, so counted.
mutate "an unreferenced data dir inside an object dir is counted in full" unattributedBytes mk "$I1/m1/attachments/a1/$ORPH/part.1" 2000000
mutate "a non-UUID subdirectory of an object dir is counted in full" unattributedBytes mk "$I1/m1/attachments/a1/not-a-uuid/f" 30000
b="$(figure 4096 unattributedBytes)"; mkmeta "$I1/m1/attachments/a1/xl.meta" 600 "$DD1" "$ORPH"
if [ "$(figure 4096 unattributedBytes)" -lt "$b" ]; then ok "once xl.meta references it, that data dir is the object's again"
else bad "a referenced data dir stayed unattributed"; fi
mkmeta "$I1/m1/attachments/a1/xl.meta" 600 "$DD1"
b="$(figure 4096 unattributedBytes)"; chmod 000 "$I1/m1/attachments/a1/xl.meta"
if [ "$(id -u)" = 0 ]; then ok "an unreadable xl.meta references nothing (skipped: root reads anything)"
elif [ "$(figure 4096 unattributedBytes)" -gt "$b" ]; then ok "an unreadable xl.meta references nothing: its data dirs are counted (fail safe)"
else bad "an unreadable xl.meta still attributed its data dir"; fi
chmod 644 "$I1/m1/attachments/a1/xl.meta"
check "the classes hold after every mutation" 4096

# An object removed (MinIO renames it into .trash) leaves its message directory holding no object: unoccupied.
b="$(figure 4096 unattributedBytes)"
mv "$K/_probe/ing-1/0e00-probe" "$S/tmp/.trash/0e00-probe"
if [ "$(figure 4096 unattributedBytes)" -ge "$b" ]; then ok "a prefix left without an object turns unoccupied (never less counted)"
else bad "removing the probe object lowered unattributedBytes"; fi
check "the classes hold after an object moved to trash" 4096

# Fails closed: exit 1 and nothing on stdout for a layout it cannot measure as defined.
refused() {  # refused <case> <regex> <args…>
    local c="$1" re="$2" out err rc; shift 2
    out="$(python3 "$ACC" "$@" 2>"$TMP/err")"; rc=$?; err="$(cat "$TMP/err")"
    if [ "$rc" = 1 ] && [ -z "$out" ] && printf '%s' "$err" | grep -qE -- "$re"; then ok "$c"; else bad "$c (exit $rc)" "$out" "$err"; fi
}
refused "a missing mountpoint is refused" "mountpoint .* does not exist" --mount "$TMP/nope" --data-dir "$D" --bucket testinbox
mkdir -p "$TMP/nosys/data/testinbox"
refused "a data dir without .minio.sys is refused" ".minio.sys .* does not exist" --mount "$TMP/nosys" --data-dir "$TMP/nosys/data" --bucket testinbox
mkdir -p "$TMP/filesys/data"; : > "$TMP/filesys/data/.minio.sys"
refused "a .minio.sys that is not a directory is refused" "not a directory" --mount "$TMP/filesys" --data-dir "$TMP/filesys/data" --bucket testinbox
refused "a missing data dir is refused" "data directory .* does not exist" --mount "$M" --data-dir "$M/absent" --bucket testinbox
mkdir -p "$TMP/outside/.minio.sys"
refused "a data dir outside the mountpoint is refused" "is not the mountpoint .* or under it" --mount "$M" --data-dir "$TMP/outside" --bucket testinbox
mkdir -p "$TMP/nobucket/.minio.sys"; : > "$TMP/filebucket"; mkdir -p "$TMP/fb/.minio.sys"; : > "$TMP/fb/testinbox"
refused "a bucket that is not a directory is refused" "bucket .* is not a directory" --mount "$TMP/fb" --data-dir "$TMP/fb" --bucket testinbox
# Another filesystem: the mountpoint / and a data dir on a different st_dev (a tmpfs /dev/shm on Linux, the APFS data
# volume under / on macOS). Run wherever such a pair exists.
other=""
for cand in "$TMP" /dev/shm; do
    if [ -d "$cand" ] && [ -w "$cand" ] && [ "$(python3 -c "import os,sys; print(os.lstat('/').st_dev != os.lstat(sys.argv[1]).st_dev)" "$cand")" = True ]; then
        other="$cand/adr035-otherfs-$$"; break
    fi
done
if [ -n "$other" ]; then
    mkdir -p "$other/.minio.sys"
    refused "a data dir on another filesystem than the mountpoint is refused" "another filesystem" --mount / --data-dir "$other" --bucket testinbox
    rm -rf "$other"
else ok "a data dir on another filesystem (skipped: no second filesystem writable here)"; fi
out="$(python3 "$ACC" --mount "$TMP/nobucket" --data-dir "$TMP/nobucket" --bucket testinbox)"; rc=$?
if [ "$rc" = 0 ] && [ "$(printf '%s' "$out" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["bucketPresent"], d["unattributed"]["bucketRoot"])')" = "False 0" ]; then
    ok "a missing bucket is reported explicitly as bucketPresent: false (a 0-object baseline), never silently"
else bad "missing bucket (exit $rc)" "$out"; fi
python3 "$ACC" --mount "$M" --data-dir "$D" --bucket testinbox --block-size 0 >/dev/null 2>&1; rc=$?
if [ "$rc" = 2 ]; then ok "a non-positive block size is a usage error (exit 2)"; else bad "block size 0 exit $rc"; fi

# Iterative walk: a 1 100-deep chain of directories with an object at the bottom (beyond the default recursion limit
# of 1 000). Needs PATH_MAX > ~2 300 (Linux: 4 096); skipped where the platform cannot even create the path.
DP="$TMP/deep"; mkdir -p "$DP/.minio.sys/x" "$DP/testinbox"
if python3 - "$DP/testinbox" <<'EOF' 2>/dev/null
import os, sys
p = sys.argv[1]
for _ in range(1100):          # one mkdir at a time: os.makedirs itself recurses
    p = os.path.join(p, "d")
    os.mkdir(p)
open(os.path.join(p, "xl.meta"), "wb").write(b"x")
EOF
then
    out="$(python3 "$ACC" --mount "$DP" --data-dir "$DP" --bucket testinbox 2>&1)"; rc=$?
    if [ "$rc" = 0 ] && [ "$(printf '%s' "$out" | python3 -c 'import json,sys; print(json.load(sys.stdin)["unattributed"]["unoccupiedDirectories"])')" = 0 ]; then
        ok "a 1 100-deep directory chain is walked iteratively, every ancestor of the object occupied"
    else bad "deep tree (exit $rc)" "$(printf '%s' "$out" | tail -3)"; fi
else ok "a 1 100-deep directory chain (skipped: this platform's PATH_MAX cannot hold it)"; fi

echo
echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
