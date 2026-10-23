#!/bin/sh
# ADR-035 §9a slow-W runner: a privileged container with the loop device and device-mapper available.
#
#   run-slow.sh [--mode auto|dm|cgroup] [--out DIR] --preflight-only     # only check that a real throttle exists
#   run-slow.sh [--mode ...] [--out DIR] --smoke                          # 2 short trials (minutes), NOT a qualification
#   run-slow.sh [--mode ...] [--out DIR] [--plan FILE]                    # the full plan; then, >= 17 min later:
#   run-slow.sh --out DIR --sweep                                         # strict re-check of every key (sweep.py)
#   run-slow.sh --down                                                    # remove the container
#
# The MinIO binary is extracted from the pinned mirror image BY DIGEST (never by tag) and its sha256 is recorded.
# The harness never substitutes sleep, CPU throttling or network latency: without dm-delay or cgroup v2 io.max the
# preflight emits {"scenario":"slow-W","status":"NOT RUN",...} and this script exits non-zero.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
IMAGE_DIGEST="ghcr.io/yannisyoussef/testinbox-mirror/minio@sha256:bbac678936882e4033b6068efd0a19d103b219002d979087f7c5bd330172e00d"
NAME=ti-qual-slow; MODE=auto; OUT=""; PLAN=""; ACTION=run
while [ $# -gt 0 ]; do
    case "$1" in
        --mode) MODE=$2; shift 2;;
        --out) OUT=$2; shift 2;;
        --plan) PLAN=$2; shift 2;;
        --preflight-only) ACTION=preflight; shift;;
        --smoke) ACTION=smoke; shift;;
        --sweep) ACTION=sweep; shift;;
        --down) ACTION=down; shift;;
        *) echo "unknown argument: $1" >&2; exit 64;;
    esac
done
[ -n "$OUT" ] || OUT="${TMPDIR:-/tmp}/ti-qual-slow"
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
case "$MODE" in auto) SLOW=1;; dm|cgroup) SLOW=$MODE;; *) echo "--mode must be auto|dm|cgroup" >&2; exit 64;; esac

down() {   # best effort: a mapper/loop device set up by entry.sh outlives the container in the Docker VM unless detached
    docker exec "$NAME" sh -c 'umount /data 2>/dev/null; DM_DISABLE_UDEV=1 dmsetup remove qualslow 2>/dev/null; L=$(sed -n "s/^LOOPDEV=//p" /run/qual/devices.env); [ -n "$L" ] && losetup -d "$L" 2>/dev/null; true' >/dev/null 2>&1 || true
    docker rm -f "$NAME" >/dev/null 2>&1 || true
}
if [ "$ACTION" = down ]; then down; exit 0; fi
if [ "$ACTION" = sweep ]; then
    docker exec -e RESULTS=/q/results-slow.jsonl "$NAME" sh -c 'cd /h && python3 sweep.py' | tee "$OUT/sweep-slow.jsonl"
    echo "sweep-slow done $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$OUT/timeline-slow.txt"; exit 0
fi

echo "== building the qualification image"
docker build -q -t ti-qual-slow "$HERE" >/dev/null
if [ ! -x "$OUT/minio" ]; then
    echo "== extracting the pinned MinIO binary from $IMAGE_DIGEST"
    CID=$(docker create "$IMAGE_DIGEST"); docker cp "$CID:/usr/bin/minio" "$OUT/minio"; docker rm -f "$CID" >/dev/null
fi
(cd "$OUT" && { sha256sum minio 2>/dev/null || shasum -a 256 minio; } > minio-binary.sha256)
echo "   binary: $(cat "$OUT/minio-binary.sha256")"
if ! grep -q "$(cut -d' ' -f1 "$OUT/minio-binary.sha256")" "$HERE/minio-binary.sha256"; then
    echo "   NOTE: this binary differs from the laptop arm64 reference in $HERE/minio-binary.sha256 (expected on another platform)."
fi
down; rm -f "$OUT/devices.env" "$OUT/mount.txt" "$OUT/uname.txt"
echo "== starting $NAME (privileged, SLOW=$SLOW)"
docker run -d --privileged --name "$NAME" -e SLOW="$SLOW" -v "$OUT:/q" -v "$HERE:/h:ro" ti-qual-slow sh /h/entry.sh >/dev/null
for i in $(seq 1 90); do [ -s "$OUT/devices.env" ] && break; docker ps -q --no-trunc --filter "name=^/$NAME$" | grep -q . || break; sleep 1; done
[ -s "$OUT/devices.env" ] || { echo "entry.sh did not report its devices (MinIO not ready?); docker logs $NAME:" >&2; docker logs "$NAME" >&2 || true; exit 1; }
cat "$OUT/devices.env"
CHOSEN=$(sed -n 's/^SLOW_MODE=//p' "$OUT/devices.env")

echo "== preflight"
set +e
docker exec -e OUT=/q/preflight-slow.jsonl "$NAME" sh -c "cd /h && python3 harness.py --preflight $( [ "$MODE" = auto ] && echo auto || echo "$MODE" )"
RC=$?; set -e
if [ $RC -ne 0 ]; then echo "slow-W: NOT RUN (preflight failed, exit $RC)"; exit $RC; fi
[ "$ACTION" = preflight ] && exit 0

if [ -z "$PLAN" ]; then
    if [ "$ACTION" = smoke ]; then
        if [ "$CHOSEN" = dm ]; then PLAN='[["slow-smoke-dm500ms-W", 1, {"throttle_ms": 500, "client": "W", "hold_s": 20, "poll_s": 60}], ["slow-smoke-dm500ms-before-W", 1, {"throttle_ms": 500, "client": "W", "throttle_at": "before_put", "hold_s": 20, "poll_s": 60}]]'
        else PLAN='[["slow-smoke-cg1MiBps-W", 1, {"io_max_wbps": 1048576, "client": "W", "hold_s": 20, "poll_s": 60}], ["slow-smoke-cg1MiBps-before-W", 1, {"io_max_wbps": 1048576, "client": "W", "throttle_at": "before_put", "hold_s": 20, "poll_s": 60}]]'; fi
    elif [ "$CHOSEN" = dm ]; then PLAN=$(cat "$HERE/plan-slow.json")
    else PLAN=$(cat "$HERE/plan-slow-cgroup.json"); fi
elif [ -f "$PLAN" ]; then PLAN=$(cat "$PLAN"); fi
RESULTS=$( [ "$ACTION" = smoke ] && echo /q/smoke-slow.jsonl || echo /q/results-slow.jsonl )
echo "slow start $(date -u +%Y-%m-%dT%H:%M:%SZ) mode=$CHOSEN action=$ACTION" >> "$OUT/timeline-slow.txt"
docker exec -e OUT="$RESULTS" "$NAME" sh -c "cd /h && python3 harness.py '$PLAN'"
echo "slow done $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$OUT/timeline-slow.txt"
echo "== results in $OUT$(echo "$RESULTS" | sed 's#^/q##'). For a qualification run, sweep >= 17 min after the last trial: $0 --out $OUT --sweep"
