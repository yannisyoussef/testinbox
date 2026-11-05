# Cutting the production-host qualification record (R2, `adr035-presigned-put-v2`)

This page prepares the eligible `production-host` record so that it can be cut within hours of the Ops N1 evidence
bundle. **No record is shipped by this preparation**: nothing is added to
`backend/storage/src/main/resources/adr035-qualification/` or its `index.txt`. The record PR is cut only from real
evidence, by the steps in [Cutting the record](#cutting-the-record).

Inputs this page answers to: the app-team verdict on #124 (§2.1 R1–R4), `docs/dev/production.md` ("The production
qualification record Ops must evidence"), `docs/dev/filesystem-evidence.md`, ADR-035 §9a and Amendment 2 annex §10,
the parser (`QualificationRecords.parse`, `QualificationRecord.eligibility()` / `productionGaps()`), gate Q and gate F
of `scripts/check-storage-activation.sh`, and the Ops collectors on infinity-core
`ops/testinbox-storage-activation-prep` (`collect-filesystem-evidence.py`, `qualification-check.py`, `n1/`).

The tools:

| File | What it is |
|---|---|
| `scripts/build-qualification-record.py` | the offline generator: verifies the bundle's `SHA256SUMS` first, then builds the record, `PROVENANCE.md` and the evidence copy; refuses (exit 1, nothing written) on any gap |
| `docs/adr/0035-benchmark/qualification/production-expected.json` | the **FIXED** values (below). Not a record, never shipped. A bundle that measures anything else is refused |
| `scripts/build-qualification-record.test.sh` | 28 cases on a plainly fake bundle (`scripts/testdata/qualification-record/make-fake-bundle.py`), including the real gate Q and gate F with `--environment production`, and the §9a evaluator reproducing the shipped staging record from its raw corpus |
| `backend/storage/src/test/.../GeneratedQualificationRecordTest.kt` | the generator's output on the fake bundle (golden file, byte-compared by the shell test) loads through the real `QualificationRecords.parse`, is eligible and has no production gap |

## Value status

- **FIXED**: known now. The value is in `production-expected.json`. The generator does not copy it from that file: it
  reads the value from the bundle and refuses if the two differ.
- **FROM-N1**: measured in the N1 window and read from the named bundle file.
- **DERIVED**: computed from bundle files by the stated rule.
- **CODE**: a constant of the artifact or of the ADR.

Each value also gets one provenance label in `PROVENANCE.md`:

- **PROD-QUAL**: measured on prod-gra-01's final filesystem after the reboot, in the qualification window, naming the
  artifact.
- **PROD-NOW**: observed read-only on prod-gra-01 after the reboot.
- **DERIVED** and **CODE**: as above.
- **CARRIED-AMENDMENT**: used only for E7 under outcome (b) of A5.

An eligible record has no LAB or POST-REBOOT member. The generator cannot emit either label.

## Field map

| Record field | Status | Value / rule | Source (bundle file → check) | Label |
|---|---|---|---|---|
| `schemaVersion` | CODE | `1` | `QualificationRecords.SCHEMA_VERSION` | CODE |
| `recordId` | DERIVED | `production-amd64-<host>-<UTC date of qualifiedAt>`, e.g. `production-amd64-prod-gra-01-2026-11-07` | must equal `MANIFEST.json` `recordId` **and** `gate-f/filesystem-evidence.json` `qualificationRecordId` (verdict 2.1 (d)) | DERIVED |
| `qualifiedAt` (required by the parser) | DERIVED | latest of the §9a slow sweep start (`qualification-9a/orchestrator.log`) and every on-filesystem experiment's `completedAt` | `qualification-9a/orchestrator.log`, `experiments/E*.json` | DERIVED |
| `minio.imageIndexDigest` | FIXED `sha256:bbac6789…e00d`, DERIVED | sha256 of the raw index bytes; the index must list exactly one linux/amd64 member, and that member must be the one running (verdict 2.1 (a)) | `identity/minio-imagetools-raw.json` (`docker buildx imagetools inspect --raw <mirror>@sha256:bbac…`) + `identity/minio-image-inspect.json` RepoDigests | DERIVED |
| `minio.platformMemberDigest` | FIXED `sha256:3f97c565…64bb` | image RepoDigests = qualification-check `observed` | `identity/minio-image-inspect.json`, `identity/minio-container-inspect.json` (`.Image` = image `Id`), `identity/qualification-check-observed.json` | PROD-NOW |
| `minio.platform` | FIXED `linux/amd64` | the index entry's platform | `identity/minio-imagetools-raw.json` | PROD-NOW |
| `minio.release` | FIXED `RELEASE.2025-04-22T22-12-26Z` | `minio --version` = `mc admin info` = qualification-check | `identity/minio-binary.txt`, `identity/minio-admin-info.json`, `identity/qualification-check-observed.json` | PROD-NOW |
| `minio.commitId` | FIXED `0d7408fc…6739e` | as release | as release | PROD-NOW |
| `minio.binarySha256` | FIXED `53e2a2cb…817c` | `sha256sum /usr/bin/minio` in the production container; the §9a harness binary must be the same | `identity/minio-binary.txt`, `qualification-9a/*/minio-binary.sha256` | PROD-NOW |
| `minio.mode`, `driveCount` | FIXED `single-node-single-drive`, `1` | `mc admin info --json`, derived as qualification-check does | `identity/minio-admin-info.json` = qualification-check | PROD-NOW |
| `minio.timeoutEnvironment`, `timeoutCliFlags` | FIXED `{}`, `[]` | container `Env` filtered `^MINIO_.*(TIMEOUT\|DEADLINE\|KEEP_?ALIVE)`, `Args` filtered `timeout\|deadline\|keep-?alive` (qualification-check's rules) | `identity/minio-container-inspect.json` = qualification-check | PROD-NOW |
| `minio.runtimeConfig.normalized`, `.hash` | FIXED `ti-qc-runtime-config-v1`, `sha256:b0821e88…c558` | the generator re-verifies sha256(`runtimeConfigNormalized`) = hash; the qualification MinIO's capture, re-normalized by a port of the same normalizer, must hash the same | `identity/qualification-check-observed.json`, `qualification-9a/*/harness-minio-capture.txt` | PROD-NOW |
| `minio.storageClassInlineDefaults` (+ `…Basis`) | FIXED `release-defaults` | no `MINIO_STORAGE_CLASS_*`/inline variables in the container's env | `identity/minio-container-inspect.json` = qualification-check | PROD-NOW |
| `host.kernelRelease` | FIXED `6.8.0-147-generic` (after the O2 reboot) | baseline = qualification-check = every experiment = the §9a `uname.txt` | `identity/fs-baseline-post-reboot.txt` (`kernel=`), `identity/qualification-check-observed.json`, `experiments/E*.json`, `qualification-9a/*/uname.txt` | PROD-NOW |
| `host.architecture` | FIXED `x86_64` | baseline `arch=` | `identity/fs-baseline-post-reboot.txt` | PROD-NOW |
| `host.filesystemType`, `mountOptions` | FIXED `ext4`, `rw,relatime` | baseline = qualification-check = gate F evidence | `identity/fs-baseline-post-*.txt`, `identity/qualification-check-observed.json` | PROD-NOW |
| `host.device` | DERIVED text | from the baseline geometry: `-b 4096`, 12,582,912 blocks = 12,582,912 inodes, inode size 256, features, loop, image, 0 FIEMAP hole blocks, `discard_max_bytes` 0 | `identity/fs-baseline-post-reboot.txt`, `identity/dumpe2fs-h.txt` | PROD-NOW |
| `network.directPath`, `proxy`, `tls` | FIXED `true`, `none`, `false` | qualification-check's path observation; `tls` from the endpoint scheme | `identity/qualification-check-observed.json` (`observed`, `path.endpoint`) | PROD-NOW |
| `uploadImplementationVersion` | CODE `adr035-presigned-put-v2` | `StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION` read from this checkout; the candidate's identity file must name it and the candidate SHA (verdict R4) | `identity/upload-impl-identity.txt` | CODE |
| `uploadImplementationNote` | DERIVED text | names the candidate SHA and api/ingestion digests that E5, E6, E8–E11 ran | `MANIFEST.json` `candidate` | DERIVED |
| `qualification.platformClass` | CODE | `production-host` | ADR-035 §18 gate 7a | CODE |
| `qualification.platform` | DERIVED text | host, kernel, member, final filesystem uuid | — | DERIVED |
| `qualification.evidenceReference` | DERIVED | `docs/adr/0035-benchmark/qualification/production-<date>/` | — | DERIVED |
| `qualification.procedure`, `cMaxSeconds` | CODE | the repository procedure; `900` (ADR-035 §9 C_max) | — | CODE |
| `qualification.scenarios`, `trials`, `lateCommits`, `slowWExecuted`, `slowWMaxCommitLagAfterCompletedWitnessS` | FROM-N1 (§9a sweep incl. slow-W) | **recomputed from raw** with the staging `gen-record.py` criteria: 0 late commits, every base-W visible, every freeze witness blocked, ≥ 18 dm slow-W trials, lag ≤ C_max, every key swept ≥ 17 min after its pass | `qualification-9a/{main,v3,slow}/results-slow.jsonl`, `sweep-slow.jsonl`, `orchestrator.log` | PROD-QUAL |
| `qualification.slowWMechanism` | FROM-N1 | Ops' statement; every slow trial's `slow_mode` must be `dm` | `qualification-9a/venue.json` | PROD-QUAL |
| `qualification.experimentArtifact` (additive member) | FROM-N1 | `{sourceSha, apiDigest, ingestionDigest}`; must equal what E5, E6, E8–E11 each name (verdict R3/R4, D3) | `MANIFEST.json`, `experiments/E*.json` | PROD-QUAL |
| `filesystem.uuid`, `type`, `mountOptions`, `mountSource` | FIXED `c33e14fd-6b6f-4f05-881e-dbc4db22c964`, `ext4`, `rw,relatime`, `/var/lib/testinbox-minio/ext4.img` | gate F evidence = baselines (post-reboot and post-cleanup) = `dumpe2fs -h` | `gate-f/filesystem-evidence.json`, `identity/fs-baseline-post-*.txt`, `identity/dumpe2fs-h.txt` | PROD-NOW |
| `filesystem.objectOverheadMaxBytes` (O_max) | FROM-N1 (E1–E3) | the O_max that E1–E3's PASS criterion was evaluated against (`measurements.oMaxBytes`, identical in all three). Every `worstObjectOverheadBytes` must be ≤ it. It must be a multiple of B and at least 6·B. It stays 24,576 only if the final filesystem measures within it (verdict 2.1 (g)) | `experiments/E1.json`, `E2.json`, `E3.json` | PROD-QUAL |
| `filesystem.metadataInodesMeasured` (M) | FROM-N1 (E7), or carried | `measurements.metadataInodesMeasured`; gate F evidence's declared inode budget must be ≥ it | `experiments/E7.json` | PROD-QUAL, or CARRIED-AMENDMENT |
| `filesystem.metadataInodesBasis` (additive member) | DERIVED | `E7 on the final filesystem` or `E7 carried under <amendment>` | `MANIFEST.json` `e7` | DERIVED |
| `filesystem.experiments.E1`…`E11` | FROM-N1 | each must be `PASS`, on `venue: final-filesystem`, uuid `c33e14fd…`, kernel 147, member `3f97c565…`, started after the post-reboot baseline and finished before the post-cleanup one | `experiments/E1.json` … `E11.json` + their `rawFiles` | PROD-QUAL (E7 may be CARRIED-AMENDMENT) |
| `enablementEligible`, `ineligibilityReasons` | DERIVED | `true`, `[]`. The generator writes no record otherwise | — | DERIVED |

Where the FIXED values come from:

- The MinIO member, release, commit, binary and runtime-config hash: PR #124 `ops-evidence/prod-minio-identity-20261103.txt`
  and the shipped staging record.
- The geometry and the filesystem identity: `prod-gate-f-evidence-20261103.json.txt`, `prod-facts.json`, and
  `n1-fs-baseline.sh`'s qualified geometry and feature list.
- The kernel: `prod-kernel-20261103.txt`. 6.8.0-147 is installed and held, and becomes true after the reboot.

## The N1 evidence bundle Ops delivers

One directory, delivered with `SHA256SUMS` (`sha256sum` format, relative paths). `SHA256SUMS` lists **every** file
and nothing else. Paths are fixed:

```
SHA256SUMS
MANIFEST.json                              {"schema":"testinbox.n1-bundle/1", "recordId", "host":"prod-gra-01",
                                            "candidate":{"sourceSha","apiDigest","ingestionDigest"},
                                            "e7":{"mode":"final-filesystem"} | {"mode":"carried","amendment":"<heading text>"}}
identity/fs-baseline-post-reboot.txt       n1-fs-baseline.sh, N1_PHASE=post-reboot, verdict OK
identity/fs-baseline-post-cleanup.txt      n1-fs-baseline.sh, N1_PHASE=post-cleanup, N1_ALLOW_QUAL_DIR=0, verdict OK
identity/dumpe2fs-h.txt                    dumpe2fs -h /dev/loopN (full output; the A1 (i) feature list)
identity/itable-zeroed.txt                 the explanation of itable_zeroed groups 384/385 (verdict 2.1 (f))
identity/minio-process-uid.txt             the production MinIO process uid/user (verdict 2.1 (f); A1 (iv))
identity/minio-container-inspect.json      docker inspect testinbox-minio-1
identity/minio-image-inspect.json          docker image inspect <that container's .Image>
identity/minio-imagetools-raw.json         docker buildx imagetools inspect --raw <mirror>@sha256:bbac… (bytes as served)
identity/minio-binary.txt                  docker exec testinbox-minio-1 sh -c 'sha256sum /usr/bin/minio; minio --version'
identity/minio-admin-info.json             mc admin info --json (mode, drives; verdict 2.1 (c))
identity/qualification-check-observed.json qualification-check.py --environment production --no-latch --evidence <this>
                                            (post-reboot; against any record: only `observed`, `runtimeConfigNormalized`,
                                            `normalization` and `path` are read)
identity/upload-impl-identity.txt          the candidate SHA + digests, and its StorageProtocol version line
qualification-9a/venue.json                {"filesystemUuid","kernelRelease","minioPlatformMemberDigest","slowWMechanism"}
qualification-9a/orchestrator.log          run-slow.sh's orchestrator log
qualification-9a/{main,v3,slow}/           run-slow.sh's per-pass output, unchanged: results-slow.jsonl, sweep-slow.jsonl,
                                            uname.txt, mount.txt, minio-binary.sha256, harness-minio-capture.txt (+ any others)
experiments/E1.json … E11.json             one per experiment (format below)
experiments/E<n>/…                         the raw run output each Ei.json lists in rawFiles
gate-f/filesystem-evidence.json            collect-filesystem-evidence.py --record-id <recordId>, after the N1 cleanup
```

Experiment file (`testinbox.n1-experiment/1`):

```json
{
  "schema": "testinbox.n1-experiment/1",
  "experiment": "E1",
  "verdict": "PASS",
  "criterion": "<annex §10 PASS text, as amended>",
  "startedAt": "2026-11-06T21:10:00Z",
  "completedAt": "2026-11-06T21:55:00Z",
  "venue": "final-filesystem",
  "filesystemUuid": "c33e14fd-6b6f-4f05-881e-dbc4db22c964",
  "kernelRelease": "6.8.0-147-generic",
  "minioPlatformMemberDigest": "sha256:3f97c565…",
  "artifact": null,
  "measurements": {"oMaxBytes": 24576, "worstObjectOverheadBytes": 20603},
  "rawFiles": ["experiments/E1/run.log", "experiments/E1/statvfs-deltas.jsonl"]
}
```

- `artifact` is `{sourceSha, apiDigest, ingestionDigest}` and is required for E5, E6, E8, E9, E10 and E11. It must
  equal `MANIFEST.candidate`.
- `measurements` carries `oMaxBytes` and `worstObjectOverheadBytes` in E1–E3, and `metadataInodesMeasured` in E7.
  Ops may add any other figure.

The `recordId` follows from `qualifiedAt`, so Ops can compute it before running the gate F collector:
`production-amd64-prod-gra-01-<UTC date of the later of the §9a slow-sweep start and the last experiment's
completedAt>`. A wrong guess is refused, and the refusal names the derived id.

### E7: both outcomes of W3 question A5

- **(a) Run on the final filesystem.** Set `MANIFEST.e7.mode` to `final-filesystem` and give E7 like every other
  experiment.
- **(b) Owner accepts carrying E7 from the staging corpus.** Set `MANIFEST.e7.mode` to `carried` and `amendment` to
  the exact heading of the dated ADR-035 amendment note. In `experiments/E7.json`, set `venue: "carried"` and
  `sourceEvidence` to the committed corpus path, keep the same member digest, and carry `metadataInodesMeasured`.
  - The generator refuses unless that heading is present in `docs/adr/0035-physical-storage-bound-at-ingest.md`, so
    the amendment must merge **before** the record PR.
  - The record says so in `filesystem.metadataInodesBasis`, and `PROVENANCE.md` labels E7 and M as
    `CARRIED-AMENDMENT`.

No other experiment can be carried.

## Cutting the record

Run these from a branch off `develop` (for example `app/r2-production-record`) once the bundle is in hand:

1. **Generate.** Run
   `scripts/build-qualification-record.py --bundle <bundle-dir>`.
   - It writes `docs/adr/0035-benchmark/qualification/production-<date>/`, containing `record.json`,
     `PROVENANCE.md`, `SHA256SUMS` and `evidence/`.
   - Any refusal is reported to Ops with its message. The evidence, not the generator, is then wrong. Never edit
     `record.json` by hand.
   - `--check` verifies without writing.
2. **Ship.** Run
   `cp …/production-<date>/record.json backend/storage/src/main/resources/adr035-qualification/<recordId>.json`,
   then add `<recordId>.json` to `index.txt`.
3. **Change the guard deliberately** (production.md, "Adding the record"). In `QualificationRecordsTest`, the test
   "no record shipped in this artifact can back production ALL" becomes "exactly `<recordId>` can": it asserts that
   record is eligible, has no `productionGaps()`, and carries the running `UPLOAD_IMPLEMENTATION_VERSION`.
4. **Run the gates locally.**
   - `./gradlew spotlessApply build` (from `backend/`): `QualificationRecordsTest`, `GeneratedQualificationRecordTest`
     and the safety tests.
   - `scripts/check-minio-mirror-pin.sh`: the pin `bbac…` is the record's index digest.
   - `scripts/check-storage-activation.test.sh` and `scripts/build-qualification-record.test.sh`.
   - Gate Q and gate F against the new record:
     `scripts/check-storage-activation.sh --mode ALL --environment production --backend-identity <Ops identity> --qualification-valid-metric <qc> --filesystem-evidence production-<date>/evidence/gate-f/filesystem-evidence.json --footprint-state <state>`.
     Gate Q must PASS. Gate F's identity, O_max, M and experiments rows must hold. The live gate F run with a fresh
     collection is Ops' activation item, not this PR's.
5. **Open the PR** into `develop`, titled "R2: production-host qualification record `<recordId>`". Reviewers are
   the storage CODEOWNERS plus the consolidated release review. Then release it through the normal
   `develop` → `master` → `Production handoff` path.
6. **After the release, Ops** runs `qualification-check --environment production` against the shipped record
   (`valid=1`), then gates A–G per the activation sequence. The record alone enables nothing.

The record PR does not satisfy gate D (the §11 benchmark on the corrected T1) or the owner authority for the A1
note. Both stay separate prerequisites of `ALL` (verdict §4.3).

## Schema and contract changes

**None.** The record keeps `schemaVersion` 1 and the shipped shape.

- Two members are additive: `qualification.experimentArtifact` and `filesystem.metadataInodesBasis`.
  `QualificationRecords.parse`, gate Q, gate F, `check-minio-mirror-pin.sh` and Ops' `qualification-check.py` read
  members by name and ignore both, as they already ignore `slowWMechanism` and `uploadImplementationNote`.
- No REST contract, migration or `DeploymentSafety` change.
- The only ADR text the record PR may depend on is the dated N1 amendment notes (A1/A2, and the A5 note if E7 is
  carried). They merge before it.

## Open questions for Ops

1. **§9a venue.** The harness slow-W uses dm-delay over a loop device. On prod-gra-01, which filesystem holds the
   qualification MinIO's data during the sweep? Is it the final filesystem `c33e14fd…` directly, or an image on it
   under a dm-delay mapper?
   - `venue.json.filesystemUuid` must be `c33e14fd…` (verdict R3).
   - If the data sits on a nested filesystem, state that in `venue.json` (for example
     `"nesting": "ext4 image on c33e14fd… under dm-delay"`). The record text will carry it.
2. **The experiment JSON format** above is a proposal. Can N1's tooling emit it directly, or does Ops need a converter?
   The generator fixes only the members listed; any extra member is allowed.
3. **`qualification-check-observed.json`.** Run qualification-check post-reboot with `--no-latch --evidence`
   against the PROPOSED record. Its `valid` will be 0, which is fine: only `observed` is read. Confirm the tool
   runs with that record unchanged under `--environment production`.
4. **The `imagetools --raw` source.** Which registry serves `bbac…` to the host? The PR #124 note says the host
   pulls by member digest. The raw bytes must hash to `bbac…`, from the estate mirror or GHCR. If neither can serve
   the index, the alternative in verdict 2.1 (a) is to pin production by the index digest.
5. **`itable-zeroed.txt` and the uid.** Both are free text and must not be empty. `PROVENANCE.md` quotes the first
   line of `minio-process-uid.txt`, so put the numeric uid and the user name there (for example `1000 minio`).
6. **`recordId` date.** If N1 runs over midnight UTC on 2026-11-06/07, the date is that of the later event.
   Collect gate F evidence with the derived id (see above).
