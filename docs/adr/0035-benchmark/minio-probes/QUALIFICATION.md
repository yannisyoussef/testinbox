# A_F qualification: laptop, reference topology, arm64

This is ADR-035 §9a's qualification record, from TI-DEC-001a §4. The harness, plans, raw results, sweep output and
environment captures are in [`../qualification/`](../qualification/).

**This is empirical evidence, not a mathematical proof.** It qualifies one combination only: the arm64 member of
the pinned image, on a laptop. It does **not** qualify the production host. §18 gate 7a requires the production
combination (amd64, filesystem owned by Ops) to be qualified on its own.

## Combination qualified

| Element | Value |
|---|---|
| MinIO binary | `minio` extracted from `ghcr.io/yannisyoussef/testinbox-mirror/minio@sha256:bbac678936882e4033b6068efd0a19d103b219002d979087f7c5bd330172e00d` (arm64 member `sha256:54d3d6a0…8194`). `minio version RELEASE.2025-04-22T22-12-26Z (commit-id=0d7408fc9969caf07de6a8c3a84f9fbb10a6739e)`, runtime go1.24.2. Binary sha256 is in `minio-binary.sha256`. |
| Mode | single node, single drive (`minio server /data`), listening on `127.0.0.1:9000` |
| Configuration | defaults. No `MINIO_*` timeout variables, no CLI timeout flags, no `mc admin config` changes (fresh data directory). |
| Data filesystem | a dedicated ext4 on a loop device (`/work.img on /data type ext4 (rw,relatime)`), created per run so that `fsfreeze` can stall it without touching the Docker VM's disk |
| Kernel | `6.12.76-linuxkit` aarch64 (Docker Desktop VM on an Apple Silicon laptop) |
| Network path | the client runs in the same container, over loopback. No proxy, no TLS. |
| Upload implementation | the ADR §5 shape: presigned single-part PUT of **15 MiB** (the largest permitted), signed `content-length` and `If-None-Match: *`, one attempt. The client waits until the kernel reports the whole body acknowledged (`TIOCOUTQ = 0`), so a later RST cannot truncate the body. The abort is by RST (`SO_LINGER 0`). |

The container runs the pinned **binary** on a Debian userland, not the image's own filesystem. The binary is statically
linked, so the bytes under test are the pinned release. The userland only supplies `fsfreeze`, `mkfs` and Python.

## Method

**Strict existence checks.** Existence is checked through MinIO (a presigned `HEAD`) and, from the second run on,
**directly on disk** (`/data/<bucket>/<key>/xl.meta`, which can be read while the filesystem is frozen).
- A `HEAD` returning `200` means present, and `404` means absent. Anything else is an **error**, never "absent".
- MinIO answers `503` while its drive is frozen, so only the on-disk check can tell when a commit happened.

**A late commit** is an object that is **absent on disk throughout the freeze and present after it**: the "absent at
an earlier check, then appears later" event.

**Scenarios:**

| Scenario | What happens |
|---|---|
| `base-W` | the response is never read (the outcome is ambiguous to the client) |
| `base-R100ms` | RST 100 ms after the full body is acknowledged |
| `freeze{45,120}-W` | `fsfreeze -f /data` immediately after the full body is acknowledged. The client stays **connected and silent** (the "frozen writer" case). Thaw after 45 or 120 s. |
| `freeze{45,120}-R2s` | the same freeze, with an RST 2 s into it |
| `phase{1,3,5,10,20}ms-R2s` | the freeze starts 1–20 ms after acknowledgement, to vary where in the commit it lands |
| `v3-*` | the freeze scenarios repeated, with direct on-disk checks at the freeze, after the RST, before the thaw, and after the poll |

**Storage witness.** During every freeze, a 1-byte probe `PUT` with a 5 s client timeout checks whether a *new*
commit can complete.

**Final sweeps.** `sweep.py` re-checks every key strictly, after more than `S` = 17 min.
- The first run was swept 21.5 min after its last trial.
- The `v3` run was swept 18.4 min after its last trial. A premature sweep at 4.7 min is kept as
  `sweep-v3-interim-4.7min.jsonl` and is **not** counted.

**A discarded first attempt.** The first harness is kept in the session scratchpad, not in the repository. It pulled
the image by tag instead of by digest, could truncate the body with an immediate RST, throttled `fdatasync` instead of
stalling the commit, and had a broken sweep. The focused storage review found these problems. That run was stopped
and **none of its numbers are used**.

## Results

| Scenario group | Trials | Outcome |
|---|---:|---|
| Baseline (`base-W`, `base-R100ms`) | 60 | 60 of 60 visible. Latest visibility after the full-body acknowledgement: **0.06 s**. |
| Freeze, first run (`freeze*`, `phase*`) | 28 | Where the object became visible, it did so within 0.34 s of the thaw. On-disk checks were not yet in place, so this run cannot separate "committed before the freeze" from "committed after it". |
| Freeze, on-disk run (`v3-*`) | 23 | 16 were already committed on disk when the freeze began; MinIO only refused reads (`503`) until the thaw. **7 were caught before commit.** None of those 7 ever committed after the thaw: 4 with an RST during the freeze, 3 with the client still connected and silent through freezes of 45 and 120 s. |
| **Late commits** (absent on disk throughout the freeze, then present) | 23 checked on disk | **0** |
| Objects that appeared after their poll window (strict sweeps at +21.5 and +18.4 min) | 111 | **0**. 100 were present, as seen while polling; 11 were absent throughout, including all 7 `v3` uploads caught before commit. No check errors. |
| Storage witness during a freeze | 51 | blocked in **51 of 51** |
| Upload throughput | 111 | 230–392 MiB/s |

**Totals:** 111 uploads of 15 MiB. 51 had a freeze of 45 or 120 s, which is longer than MinIO's 30 s drive deadline.

## Conclusions for ADR-035

1. **No late commit was observed.** A commit either happened before the storage stall began, or, if the stall caught
   it earlier, never happened: neither after an RST nor with a silent connected client through a stall longer than
   30 s. This matches the source reading (`FINALIZE-SOURCE.md`): the context and drive-deadline checks come before the
   final `rename(2)`.
2. **The window that remains is narrow and not exercised.** It is a `rename(2)` that is already in flight when
   storage stalls. It lasts microseconds, and no freeze timing hit it. It is not qualified by this data, and ADR-035
   does not claim it is. A_F covers it with `C_max` = 15 min, and the storage witness, the ambiguity slots (H) and the
   latch contain it.
3. **The storage witness blocks under a whole-filesystem stall.** A new commit could not complete in any of the 51
   freezes. That is the property ADR-035 §7 relies on to suspend releases during a stall. It was **not** shown for
   partial stalls (a single stuck I/O), which is exactly the residual A_F keeps.
4. **`C_max` = 15 min against an observed maximum of 0.06 s** is a margin of about 15 000×. The margin is deliberately
   large, and it is justified by the operational argument in ADR-035 §7, not by these numbers. A `rename(2)` stalled
   for minutes is a storage incident.

## Limits

- **Hardware.** This was one arm64 laptop, on a Docker Desktop VM kernel, over loopback. The production
  combination must be qualified on its own hardware.
- **Stall types.** A freeze models a whole-filesystem stall. A single stuck I/O, a failing disk or an erasure-coded
  layout is not modelled; erasure-coded layouts are excluded by the §9a contract.
- **Sample size.** The sample is 111 trials, with 7 caught before commit. It is enough to establish the model, not to
  put a number on tail behaviour.
- **Slow storage was not run.** ADR-035 §9a's `slow-W` scenario, added after this qualification ran, throttles storage
  without freezing it, with a silent connected client and the witness running concurrently. It probes A_F's residual:
  a commit still pending after a later witness has completed. **It is a required part of qualifying the production
  combination (§18 gate 7a).** No `C_drain` claim is made here beyond what was observed after thaw: pending commits
  landed within 0.34 s, at healthy throughput.
