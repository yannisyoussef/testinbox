# Third-party image mirrors

One image this repository depends on is served from a **mirror we own** rather
than from its upstream registry: MinIO. These are MinIO's bytes, copied — never
rebuilt, never upgraded. This document records what was copied, from where, how
equivalence is proven, and how to do it again.

## Why the mirror exists

MinIO withdrew its public container images. As of 2026-09-26:

| source | result |
|---|---|
| `quay.io/minio/minio` | `401 unauthorized` for every tag, including `latest` |
| `docker.io/minio/minio` | repository gone — the Hub API answers `object not found` |
| `mirror.gcr.io/minio/minio` | `404` (never cached this tag) |
| `public.ecr.aws/minio/minio` | `NAME_UNKNOWN` |

Six references in this repository pinned
`quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z`, so the staging rehearsal,
three Testcontainers suites, the acceptance suite and the local compose stack
all failed at image pull. The first visible symptom was
[run 36239880611](https://github.com/yannisyoussef/testinbox/actions/runs/36239880611):
`minio Error unauthorized: access to the requested resource is not authorized`.

This is a **supply-chain availability repair**, not a MinIO upgrade. The running
software is unchanged, which is the point: an upgrade would have made a version
change and an availability fix indistinguishable in the same PR.

## What is mirrored

Upstream release **`RELEASE.2025-04-22T22-12-26Z`**, upstream index
`sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e`.

| platform | upstream platform manifest | in GHCR |
|---|---|---|
| `linux/amd64` | `sha256:3f97c5651cb6662b880c787a232b6b34fec8d8922e08d6617b25d241a21164bb` | same digest |
| `linux/arm64` | `sha256:54d3d6a0a58fb25b4e9943d1db3828d3b4de44666f911381b4fda57175488194` | same digest |

```
ghcr.io/yannisyoussef/testinbox-mirror/minio@sha256:bbac678936882e4033b6068efd0a19d103b219002d979087f7c5bd330172e00d   <- what consumers pin
  ├── linux/amd64  sha256:3f97c565…64bb   (upstream member, unchanged)
  └── linux/arm64  sha256:54d3d6a0…8194   (upstream member, unchanged)
```

Per-platform provenance, as read from the verified config blobs:

| | `linux/amd64` | `linux/arm64` |
|---|---|---|
| config | `sha256:9d668e47f1fc60ea49af4203deee87a657eb1aa0e2761fee2c7c2d1df282c880` | `sha256:6c83c74c8028019d82d3f20d65cc6645fd5694b82a25dc38733df5227ffa4e8d` |
| created | `2025-04-22T22:35:01.339428192Z` | `2025-04-22T22:38:05.04745197Z` |
| entrypoint | `/usr/bin/docker-entrypoint.sh` | `/usr/bin/docker-entrypoint.sh` |
| cmd | `minio` | `minio` |
| layers | 10, digests unchanged | 10, digests unchanged |

The upstream index also had a `linux/ppc64le` member
(`sha256:106abffd1b575a2465443f8907c041e019fbed6475425bc40fffdf19a106962d`). No
byte of it was ever held here, and nothing in the estate runs it, so it is not
mirrored.

### Why the index digest differs and why that is not a weakening

The GHCR index is **newly composed**: it names two members where upstream named
three, so its digest necessarily differs. Equivalence is asserted one level
down, where it is meaningful — each *platform manifest* is served at its
original digest, so manifest, config and every layer are byte-identical by
content addressing. Nothing was re-encoded; if it had been, the digest would
have changed and the publication would have aborted.

Consumers pin the **index** digest rather than the amd64 member so that one
reference resolves natively on both an amd64 CI runner and an Apple Silicon
laptop. Pinning the amd64 member directly would have forced every local
developer into emulation.

## Where the bytes came from

Upstream was already gone when the repair was made, so neither platform could be
re-fetched from MinIO:

- **`linux/amd64`** — the Infinity Ops estate mirror
  (`registry.yvnn.is/infinity/mirror/minio/minio`, recorded in that repository's
  `infra/MIRRORS.md`), which on 2026-09-25 copied the same manifest from the
  containers it was already running. Pulled **by digest**, so the registry could
  not have substituted content.
- **`linux/arm64`** — a developer machine's content store, which still held the
  upstream index blob and the complete arm64 member.

Neither source is trusted as an assertion. The upstream index blob re-hashes to
its own digest, that index names both platform manifests, and every blob each
manifest points at was hashed before publication. So the *index itself* proves
which digests are authentic, independently of any documentation — including this
document, and including Ops's.

## Credential model

| | |
|---|---|
| CI pulls | the built-in `GITHUB_TOKEN`, `packages: read`, via `docker/login-action` |
| contributors pull | `docker login ghcr.io` with a token carrying `read:packages` |
| the Ops credential | used once, locally, interactively, and logged out by the script |
| in GitHub | **no `registry.yvnn.is` credential, ever** — nothing in this repository may depend on the Ops registry |

The package is **private**. That keeps a vendor's withdrawn bytes out of public
redistribution, and costs one `docker login` locally. It is not public merely to
avoid that login.

Three jobs pull it and therefore declare `packages: read`: `backend` and `e2e`
in `ci.yml`, and `rehearsal` in `deploy-staging.yml`. Testcontainers and compose
both pull through the Docker daemon, so it is the daemon that must hold the
credential — not Gradle, not npm.

## Retention — why these cannot silently disappear

GHCR deletes nothing on a schedule, so the risk is an accidental manual delete
or an untagged-manifest cleanup, not expiry:

- every digest carries at least one tag (`RELEASE.2025-04-22T22-12-26Z`,
  `upstream-a1ea29fa2835`, and the two per-platform tags), so no manifest is
  reachable only by digest;
- the package is owned by the user account, not by a workflow, and no automation
  in this repository has `packages: write` for it — the publishing script is run
  by a human;
- the bytes no longer exist upstream, so a delete is **unrecoverable except from
  the Ops mirror**. Treat the package as append-only.

If the package is ever lost and the Ops mirror is gone too, the arm64 member
survives only in a laptop's content store, and the release cannot be
reconstructed from any registry. That is the honest failure mode of mirroring a
withdrawn artifact.

## License

MinIO's server image is AGPL-3.0. Nothing about the code changed here: the image
carries its own `LICENSE` and copyright notices unmodified, and the mirror
publishes those same bytes.

This repository has **no `LICENSE` file, no `NOTICE` file and no third-party
notice process** to extend, so there is nothing to add an entry to; recording
the upstream origin, release and license here is the whole of it. The
distribution is private and internal — it is not offered to third parties — so
no source offer is published. **If the package is ever made public, that
conclusion has to be revisited before flipping the switch**, since public
redistribution is what attaches the corresponding-source obligation.

## Refreshing or replacing the mirror

The procedure is `scripts/mirror-minio-to-ghcr.sh`, and it is written to refuse
rather than to succeed quietly: it verifies the chain of custody before it
publishes, and aborts if a published digest differs from the original by even a
byte. To mirror a different release, change the four identity constants at the
top (release, upstream index, amd64 member, arm64 member) — the script prints
the provenance records this document needs.

The identities are overridable by `MIRROR_*` environment variables, which exists
so the procedure can be rehearsed end to end against a throwaway registry and a
still-public image before it is run with credentials. Do that; the only run that
matters should never be the first one.

Changing to a newer MinIO release is a different decision from mirroring one,
and does not belong in a mirroring commit.

## Appendix — the full provenance records

Recorded so the mirror can be re-verified from these values alone, without the
registry and without this repository's history. `scripts/mirror-minio-to-ghcr.sh`
prints exactly these lines, read out of the hashed config and manifest blobs.

`linux/amd64` — published in GHCR at the same manifest digest:

```json
{
  "platform": "linux/amd64",
  "manifest": "sha256:3f97c5651cb6662b880c787a232b6b34fec8d8922e08d6617b25d241a21164bb",
  "config": "sha256:9d668e47f1fc60ea49af4203deee87a657eb1aa0e2761fee2c7c2d1df282c880",
  "created": "2025-04-22T22:35:01.339428192Z",
  "entrypoint": ["/usr/bin/docker-entrypoint.sh"],
  "cmd": ["minio"],
  "layers": [
    "sha256:d949f716ce773b6952762453dba423b055fd2ae51e80707cbbb44b80d49a508f",
    "sha256:8c577d69454d55b6e5bffdebaaa4026898f417005e86dba1bca027935774c21f",
    "sha256:546fbfcace6536ab2e96351ffe76b9431dfbaa742a5fe7a76e6920f1d8383b71",
    "sha256:d0c8865a030236212964e817f56a5554cdebb8ecb04909756282ce5e2f2b41c4",
    "sha256:cf67644788eb8e2f7556c241a8d1d5ff89079d85bfb2fb4555fd5fc794a857c2",
    "sha256:df0d0d229f33f0af067b4345d8d577a83038467dfe6bd4832630cc292e8f66f9",
    "sha256:62481e699c81d80a3df48f9a08a8bea10918ac8981ff00b5d09a8098156ac75e",
    "sha256:68a66c893f7ce4098eac3f50758c8e2b6aa8750b979ca90345609eb3af0a95b9",
    "sha256:d7ab9c0b242a9f5411ee1b71ab98134a0e7505cd0a632b0dcf446ad43e97c570",
    "sha256:39c8ce9a80d2ca7f544f57a5a00617176a86e0760bc1aa46cf17d3f34bf3807d"
  ]
}
```

`linux/arm64` — published in GHCR at the same manifest digest:

```json
{
  "platform": "linux/arm64",
  "manifest": "sha256:54d3d6a0a58fb25b4e9943d1db3828d3b4de44666f911381b4fda57175488194",
  "config": "sha256:6c83c74c8028019d82d3f20d65cc6645fd5694b82a25dc38733df5227ffa4e8d",
  "created": "2025-04-22T22:38:05.04745197Z",
  "entrypoint": ["/usr/bin/docker-entrypoint.sh"],
  "cmd": ["minio"],
  "layers": [
    "sha256:b2ef0aec0c615d1144042fc344d2644751157176cecd09f9fee9f771ece2e731",
    "sha256:e3310e0211c0642bcdd0ce96b03b6bb815f95270fcea976cbe4cc1d9a0a988e8",
    "sha256:9030282a33d3eb1eae0b06fd82ae9b6e7e2fb77c5d1bc7b34a095fa0c5c01619",
    "sha256:b51042a873203d8bddc626558fe73bbff39dfd0265370c49ab21c61bbbfc3f53",
    "sha256:a6c6d2950a5046010cd4490248be22169564d633eda08cb9054b0cb774819e25",
    "sha256:941eef446645ae120949049a1fb32ea86261520c325f94eafeec46ab06286a90",
    "sha256:63c77d8c06afe5c44c08093fc32b000b11ce68cc0f4d005023e511159115ccec",
    "sha256:603794b1a60ea2cceaeca7d03b6cf951365e0085e56a2f434f54d00dbfd6165c",
    "sha256:32bd409fac3ee3d7cfda4dc9987e3a6813ec53824841133b5aa8b36e74d86d05",
    "sha256:3ae83705a7167660ac86e8ec243abfdb6a2a53bdbfbbda60c4353144d9d5df57"
  ]
}
```

The two `created` timestamps differ by three minutes because upstream built the
platforms separately; both belong to the same release and the same index.

## Where the mirror is referenced

Six references, all pinned to the index digest — deliberately not a mixture:

| | |
|---|---|
| `deploy/staging/compose.data.yaml` | rehearsal + staging reference data tier |
| `docker-compose.yml` | local development stack |
| `backend/storage/.../S3BlobStoreTest.kt` | object-store adapter integration tests |
| `backend/ingestion/.../SmtpIngestionIntegrationTest.kt` | SMTP ingestion integration tests |
| `backend/api/.../ApiIntegrationTestBase.kt` | API integration tests |
| `backend/e2e/.../E2eStack.kt` | black-box acceptance stack |

`mc` is **not** mirrored, because nothing here runs it as its own image: the
compose healthcheck (`mc ready local`) and `scripts/mail-edge-storage-proof.sh`
both invoke the `mc` binary that ships *inside* the MinIO image. The Ops estate
mirrors `mc` separately because its topology has an `mc` container; this
repository has none, and mirroring bytes nothing references would be a liability
rather than an asset. No verified arm64 `mc` member exists locally either, so
inventing one was never an option.
