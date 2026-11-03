# Proposed production-host record for `adr035-presigned-put-v2` — Ops-prepared, for app-team review

**Status: PROPOSAL, not shipped.** `PROPOSED-record.json` is deliberately **not** in
`backend/storage/src/main/resources/adr035-qualification/index.txt`, says `enablementEligible: false` and lists why.
Shipping it is the app team's pull request (production.md, "Adding the record"), after the open items below. Nothing
here was measured by writing to production: every PROD-NOW value was read with read-only commands (scripts and raw
outputs in `ops-evidence/` and in the Ops workspace `stage-b/storage/evidence/`).

Labels: **PROD-NOW** measured read-only on prod-gra-01 on 2026-11-03 (kernel 6.8.0-142 running);
**POST-REBOOT** a value that is true only after prod-gra-01 reboots into 6.8.0-147-generic and is then re-measured;
**LAB** measured in a disposable lab on the staging host vmi2932906 with the identical combination; **CODE** a constant
of the artifact.

| Record member | Value | Label | Evidence |
|---|---|---|---|
| `minio.imageIndexDigest` | `sha256:bbac6789…e00d` | asserted (not resolvable from a host that pulls the member by digest; as the shipped staging record) | — |
| `minio.platformMemberDigest`, `platform` | `sha256:3f97c565…64bb`, linux/amd64 | PROD-NOW | `ops-evidence/prod-minio-identity-*`, container image digest in the Ops raw output |
| `minio.release`, `commitId`, `binarySha256` | `RELEASE.2025-04-22T22-12-26Z`, `0d7408fc…6739e`, `53e2a2cb…817c` | PROD-NOW | `ops-evidence/prod-minio-identity-*` |
| `minio.mode`, `driveCount` | single-node-single-drive, 1 | PROD-NOW (2026-10-30 `mc admin info`) | Ops `storage/evidence/prod-minio-admininfo.json` |
| `minio.timeoutEnvironment`, `timeoutCliFlags` | none, none | PROD-NOW | container env names / args |
| `minio.runtimeConfig.hash` | `sha256:b0821e88…c558` (= the shipped staging record, = the lab harness) | PROD-NOW (2026-10-30, normalizer of `qualification-check`) | Ops `storage/evidence/prod-runtime-config-hash.txt`; re-captured by the production `qualification-check` at activation |
| `host.kernelRelease` | `6.8.0-147-generic` | **POST-REBOOT** (prod runs 142 today; 147 installed) and LAB (the whole corpus is 147) | `ops-evidence/prod-kernel-*` |
| `host.architecture`, `filesystemType`, `mountOptions` | x86_64, ext4, `rw,relatime` | PROD-NOW | `ops-evidence/prod-storage-facts-*` |
| `network.directPath`, `proxy`, `tls` | true, none, false | PROD-NOW | ingestion → `http://tb-minio:9000` on `testinbox_internal`, no proxy env |
| `uploadImplementationVersion` | `adr035-presigned-put-v2` | CODE | `StorageProtocol` |
| `qualification.*` (scenarios, 129 trials, 0 late commits, slow-W dm-delay, max lag 4.607 s, C_max 900) | as in the JSON | **LAB** (§9a harness, 2026-10-30, prod MinIO binary, prod mkfs geometry, kernel 147) | Ops `storage/lab/runs/` (`gen-candidate.out`, `qualification-summary.json`) |
| `qualification.platformClass` | `production-host` | **DECISION PENDING** (PACKET O1): the sweep ran on the staging host | — |
| `filesystem.uuid`, `type`, `mountOptions`, `mountSource` | `c33e14fd-6b6f-4f05-881e-dbc4db22c964`, ext4, `rw,relatime`, `/var/lib/testinbox-minio/ext4.img` | PROD-NOW (the final filesystem; created 2026-10-27, not to be reformatted) | `ops-evidence/prod-gate-f-evidence-*` (the gate F evidence names the same four values) |
| `filesystem.objectOverheadMaxBytes` | 24,576 (6 blocks; worst measured 20,603 B) | LAB (E1–E3, kernel 147, identical geometry) | TI-STORAGE-006C `QUALIFICATION-MATRIX.md` |
| `filesystem.metadataInodesMeasured` | 157 | LAB (E7 24 h, kernel 147) | `e7b-24h-2026-10-20` |
| `filesystem.experiments` E1–E11 | PASS | LAB (E1–E4, E8 v2 on kernel 147; E5, E6c, E6d, E9 v2 ALL, E10, E11 on 201ce6cf / v2; E7 24 h) — **not on the final production filesystem** | TI-STORAGE-006C matrix; upload code byte-identical since v2 (`storage/evidence/upload-impl-identity.txt`) |

## What the evidence shows that the lab cannot (PROD-NOW, read-only)

- The real data filesystem has the qualified geometry: 4,096-byte blocks, 12,582,912 inodes for 11,716,773 usable
  blocks (`-i 4096`), the qualified ext4 features, every inode table zeroed, mounted `rw,relatime` on a loop device
  with `discard_max_bytes 0`, image fully allocated (0 FIEMAP holes).
- The Ops fs-monitor, run `--dry-run` with the production configuration, passes all six rows on it and refuses a
  configuration with another UUID or a larger C_fs (`ops-evidence/prod-fs-monitor-dryrun-*`).
- One legacy workspace (the bootstrap workspace), 0 B stored; the proposed pool FITS (`ops-evidence/capacity-proof-*`).

## Open before the record can ship (Ops / owner / app team)

1. **Reboot into 6.8.0-147-generic with a kernel hold** (O2), then re-measure: kernel, filesystem identity, geometry,
   FIEMAP, MinIO identity and runtime-config hash. Any difference from this proposal = no shipping.
2. **Venue (O1 + production.md):** accept that the §9a sweep and E1–E11 on the staging host with the identical
   combination qualify the production host and its final filesystem, or run them there (W1 for §9a; E1–E11 on the
   final filesystem would need TestInbox stopped and the production store filled to ENOSPC — not recommended).
3. **Gate F preallocation rule:** the production image is fully allocated but `st_blocks` counts the extent tree, so
   allocated (51,539,640,320) > apparent (51,539,607,552). The gate's exact equality would block it; this PR proposes
   an optional FIEMAP `holeBytes` (see the script change and its tests).
4. The production `qualification-check` reports `testinbox_storage_qualification_valid 1` against the shipped record.
