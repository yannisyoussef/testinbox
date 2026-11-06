# MinIO mirror index bytes (DERIVED/REGISTRY)

Fetched 2026-11-05T04:07:13Z by the app team (no Ops host holds a GHCR token), as agreed on #124:

```
docker buildx imagetools inspect --raw \
  ghcr.io/yannisyoussef/testinbox-mirror/minio@sha256:bbac678936882e4033b6068efd0a19d103b219002d979087f7c5bd330172e00d \
  > minio-imagetools-raw.json
```

- `sha256(minio-imagetools-raw.json)` = `bbac678936882e4033b6068efd0a19d103b219002d979087f7c5bd330172e00d` (the index digest pinned by the app and the staging record).
- `linux/amd64` member: `sha256:3f97c5651cb6662b880c787a232b6b34fec8d8922e08d6617b25d241a21164bb` (the member production runs).
- `linux/arm64` member: `sha256:54d3d6a0a58fb25b4e9943d1db3828d3b4de44666f911381b4fda57175488194`.

This is the index of the app team's GHCR mirror, rebuilt from the upstream members (amd64 and arm64 only; see
`docs/dev/third-party-mirrors.md`). It differs from upstream quay's index `a1ea29fa…`; the members are identical.

For the N1 bundle, Ops copies `minio-imagetools-raw.json` byte for byte to `identity/minio-imagetools-raw.json`. The record
generator hashes it and checks that its amd64 entry equals the running container's member (`docker inspect`).
