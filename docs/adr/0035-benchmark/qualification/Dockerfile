FROM debian:bookworm-slim
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends util-linux e2fsprogs python3 curl ca-certificates >/dev/null && rm -rf /var/lib/apt/lists/*
