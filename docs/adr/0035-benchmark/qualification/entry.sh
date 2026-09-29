#!/bin/sh
# Dedicated ext4 on a loop device: fsfreeze can stall THIS filesystem without touching the Docker VM's own disk.
set -e
truncate -s 3G /work.img && mkfs.ext4 -q -F /work.img && mkdir -p /data && mount -o loop /work.img /data
MINIO_ROOT_USER=qual MINIO_ROOT_PASSWORD=qualqualqual /q/minio server /data --address 127.0.0.1:9000 >/q/minio.log 2>&1 &
for i in $(seq 1 60); do curl -sf http://127.0.0.1:9000/minio/health/ready && break; sleep 1; done
mount | grep " /data " > /q/mount.txt; uname -a > /q/uname.txt
exec sleep infinity
