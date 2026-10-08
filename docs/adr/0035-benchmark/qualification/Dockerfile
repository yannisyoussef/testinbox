FROM debian:bookworm-slim
# dmsetup: device-mapper (dm-delay) for the ADR-035 §9a slow-W scenario; util-linux supplies losetup/findmnt/blockdev/fsfreeze.
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends util-linux e2fsprogs dmsetup python3 curl ca-certificates >/dev/null && rm -rf /var/lib/apt/lists/*
