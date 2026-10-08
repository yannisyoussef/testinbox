#!/bin/sh
# Dedicated ext4 on a loop device: fsfreeze can stall THIS filesystem without touching the Docker VM's own disk.
#
# SLOW (ADR-035 §9a slow-W) selects how the data filesystem can be THROTTLED without being frozen:
#   unset / 0 : the original layout, unchanged: `mount -o loop /work.img /data`.
#   1 / dm    : ext4 on /dev/mapper/qualslow, a dm-delay target over the loop device (write delay 0 until the harness
#               reloads the table). SLOW=1 falls back to `cgroup` when the kernel has no `delay` target; SLOW=dm does not.
#   cgroup    : MinIO runs inside the cgroup v2 leaf /sys/fs/cgroup/qualslow, so the harness can cap its write bytes/s
#               on the loop device with io.max. Requires the `io` controller (CONFIG_BLK_DEV_THROTTLING).
# The chosen mechanism (or the reason none is available) is written to /run/qual/devices.env for the harness preflight,
# which refuses to run slow-W on anything but a real block-device mechanism.
set -e
SLOW="${SLOW:-0}"
mkdir -p /run/qual /data
truncate -s 3G /work.img && mkfs.ext4 -q -F /work.img
MODE=none; REASON=""; LOOPDEV=""; DATADEV=""
if [ "$SLOW" = "0" ]; then
    mount -o loop /work.img /data
    DATADEV=$(findmnt -no SOURCE /data || true)
else
    export DM_DISABLE_UDEV=1
    if [ "$SLOW" = "1" ] || [ "$SLOW" = "dm" ]; then
        if dmsetup targets 2>/dev/null | awk '{print $1}' | grep -qx delay; then
            LOOPDEV=$(losetup --find --show /work.img)      # explicit: the mapper holds it; run-slow.sh --down detaches it
            SECTORS=$(blockdev --getsz "$LOOPDEV")
            dmsetup create qualslow --table "0 $SECTORS delay $LOOPDEV 0 0"
            DATADEV=/dev/mapper/qualslow; MODE=dm
        else
            REASON="dmsetup targets does not list delay (kernel built without CONFIG_DM_DELAY)"
            # SLOW=dm never falls back: MinIO still boots on the plain loop so the harness preflight can report NOT RUN.
            [ "$SLOW" = "dm" ] && echo "entry.sh: SLOW=dm requested but $REASON; slow-W will be NOT RUN" >&2
        fi
    fi
    if [ "$MODE" = "none" ] && { [ "$SLOW" = "1" ] || [ "$SLOW" = "cgroup" ]; }; then
        if grep -qw io /sys/fs/cgroup/cgroup.controllers 2>/dev/null; then
            # cgroup v2 "no internal processes": move ourselves to a leaf before enabling a controller on the root.
            mkdir -p /sys/fs/cgroup/qual-init && echo $$ > /sys/fs/cgroup/qual-init/cgroup.procs
            echo "+io" > /sys/fs/cgroup/cgroup.subtree_control
            mkdir -p /sys/fs/cgroup/qualslow
            MODE=cgroup
        else
            REASON="${REASON:+$REASON; }cgroup v2 io controller not available"
        fi
    fi
    if [ -n "$DATADEV" ]; then mount "$DATADEV" /data
    else mount -o loop /work.img /data; LOOPDEV=$(findmnt -no SOURCE /data); DATADEV="$LOOPDEV"; fi   # autoclear loop
fi
LOOP_MAJMIN=""
if [ -n "$LOOPDEV" ]; then LOOP_MAJMIN="$(printf '%d:%d' "0x$(stat -c %t "$LOOPDEV")" "0x$(stat -c %T "$LOOPDEV")")"; fi
printf 'SLOW=%s\nSLOW_MODE=%s\nSLOW_REASON=%s\nLOOPDEV=%s\nLOOP_MAJMIN=%s\nDATADEV=%s\n' "$SLOW" "$MODE" "$REASON" "$LOOPDEV" "$LOOP_MAJMIN" "$DATADEV" > /run/qual/devices.env
if [ "$MODE" = "cgroup" ]; then
    # MinIO is placed in the throttled leaf BEFORE it starts, so every byte it ever writes is subject to io.max.
    sh -c 'echo $$ > /sys/fs/cgroup/qualslow/cgroup.procs && MINIO_ROOT_USER=qual MINIO_ROOT_PASSWORD=qualqualqual exec /q/minio server /data --address 127.0.0.1:9000' >/q/minio.log 2>&1 &
else
    MINIO_ROOT_USER=qual MINIO_ROOT_PASSWORD=qualqualqual /q/minio server /data --address 127.0.0.1:9000 >/q/minio.log 2>&1 &
fi
for i in $(seq 1 60); do curl -sf http://127.0.0.1:9000/minio/health/ready && break; sleep 1; done
curl -sf http://127.0.0.1:9000/minio/health/ready >/dev/null || { echo "entry.sh: MinIO not ready after 60 s; see /q/minio.log" >&2; exit 1; }
mount | grep " /data " > /q/mount.txt; uname -a > /q/uname.txt
cp /run/qual/devices.env /q/devices.env        # written LAST: run-slow.sh waits for this file, so MinIO is ready by then
exec sleep infinity
