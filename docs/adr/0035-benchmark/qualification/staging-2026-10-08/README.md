# ADR-035 §9a qualification — staging host (amd64), 2026-10-08 (TI-STORAGE-006C)

Raw evidence for `backend/storage/src/main/resources/adr035-qualification/staging-amd64-vmi2932906-2026-10-08.json`.
Run by Ops ON THE STAGING HOST with the repository's own harness, unchanged (`../run-slow.sh`, `../harness.py`,
`../sweep.py`, `../entry.sh`, `../Dockerfile` at `c89cab2`). Nothing here was typed by hand: the record is generated
from these files by `gen-record.py` (included), which hashes the harness MinIO's admin configuration with the Ops
`qualification-check` normalization (`ti-qc-runtime-config-v1`, infinity-core `infra/testinbox/storage/qualification-check.py`).

## Combination

| Element | Value | Source file |
|---|---|---|
| MinIO binary | `/usr/bin/minio` of the amd64 member `sha256:3f97c565…64bb`, extracted from the estate registry mirror by digest; sha256 in `*/minio-binary.sha256` | `binary-source.txt` |
| Release / commit | from the harness MinIO's own `mc admin info` | `*/harness-minio-capture.txt` |
| Mode | single node, single drive (`minio server /data --address 127.0.0.1:9000`), no `MINIO_*` timeout variables, no timeout flags | `*/harness-container.txt` |
| Runtime admin config | `mc admin config get` api / drive / storage_class / scanner; single-drive MinIO has **no `storage_class` subsystem** (recorded as `<unknown-subsystem>`, hashed) | `*/harness-minio-capture.txt` |
| Data filesystem | ext4 on a dm-delay mapper over a loop device, mounted `rw,relatime` | `*/mount.txt`, `*/devices.env` |
| Kernel | the staging host's own | `*/uname.txt` |
| Network | client and MinIO in the same container over loopback; no proxy, no TLS | — |

The live staging MinIO matches every element: same member digest, same release/commit/mode, same hashed runtime
config, ext4 `rw,relatime` on a dedicated loop-image filesystem (`/srv/testinbox/minio`), same kernel, direct path.

## Passes (one container layout, dm-delay preflight OK, `--mode dm`)

| Pass | Plan | Directory |
|---|---|---|
| base / freeze / phase | `plan.json` | `main/` |
| on-disk freeze (v3) | `plan3.json` | `v3/` |
| **slow-W** (dm-delay 500 ms / 2000 ms, before-put and after-ack) | `plan-slow.json` | `slow/` |

Each pass was swept strictly by `sweep.py` at least 17 min after its last trial (`orchestrator.log`).

## Results

| | Value |
|---|---|
| Trials | 129 of 15 MiB (base/freeze/phase 88, v3 23, slow-W 18) |
| **Late commits** (absent on disk throughout, then present) | **0** |
| base-W visible | 30 / 30 (latest 0.293 s after the full-body acknowledgement) |
| base-R100ms aborted by the RST before commit, never present (sweep-confirmed) | 5 of 30 — a correct abort, not a late commit |
| Storage witness during a freeze | blocked in **51 of 51** |
| v3 uploads caught before commit / committed after the thaw | 23 / **0** |
| **slow-W** (dm-delay, 500 ms, 2000 ms) | 18 trials, all committed; commit landed after a later-issued, already-completed witness in 15 (A_F's residual); **max lag 4.613 s** vs `C_max` = 900 s; 16 witnesses blocked under the 2 000 ms delay |
| Strict sweeps (keys / appeared after the poll window / minutes after the last trial) | main 88/0/19.0 · v3 23/0/19.0 · slow 18/0/19.0 |
| Runtime-config hash (harness MinIO) = live staging MinIO | `sha256:b0821e88…c558` — identical (`qc-live-dryrun.json`: `valid=1` against the live server) |

Measurement resolution: commit times are bounded by the harness's 50 ms poll. Difference from the laptop run: on this
host MinIO had not yet written any frozen upload (the laptop had 16 of 23 already committed before the freeze); none
of them committed after the thaw either.

## Provenance limits (stated, not claimed away)

- The image INDEX digest `sha256:bbac…e00d` (the repository pin) cannot be re-resolved from the staging host: the
  GHCR mirror is private to this host, and quay.io no longer serves the upstream index `a1ea29fa…015e`. The tie
  index → amd64 member `3f97c565…` rests on ADR-035 §9a's table and the estate `infra/MIRRORS.md`. What IS verified
  by content addressing: the harness binary and the live staging MinIO are the same member `3f97c565…`.
- Harness root credentials `qual`/`qualqualqual` are the harness's own throwaway credentials (entry.sh), not secrets.
