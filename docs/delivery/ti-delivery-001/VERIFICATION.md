# TI-DELIVERY-001 — milestone verification

Repository checks only; no production hosts, DNS, users or credentials changed.

## M0 — original baseline, 2026-10-29

Base `408c3f3dc5520f914f866e342f50e4437c15e8ac`.

`./gradlew :domain:test :application:test :architecture:test spotlessCheck
--console=plain` passed: 577 tests, zero failures/errors/skips. Domain and
application reports were restored from Gradle cache; architecture executed.
Discovery/checkpoint saved as local commit `4a79dca`.

## M2 — console backend integration and cleanup

Existing PR #107 integrated locally as `72d5c5a`. Full backend build passed
with 1,449 tests, including real PostgreSQL, pinned MinIO and E2E cases.

Three new stream failure cases failed against the inherited code: opening
inbox-read failure, opening message-read failure and failed unsubscribe.
After the fix, all pass, including a combined read/cleanup failure that keeps
the original error and reports cleanup as suppressed. Complete backend build
and result verifier passed with 1,452 tests. Formatting, Java-21 Detekt,
fourteen verifier self-tests, migration checks and Gitleaks passed.
Fix and explicit owner acceptance of ADR-036 saved as `da8acef`.

## M3a — dormant account and cohort transaction

Runtime changes: framework-free admission model; one transactional
`AdmitBetaParticipant`; PostgreSQL adapter; expand-only V12; three explicitly
classified control-plane tables. No endpoint, OAuth client, session, sending
credential or production feature flag was enabled.

### Real PostgreSQL proofs

Thirteen `BetaAdmissionsTest` cases cover:

- admissions closed by default and separately refused without capacity readiness;
- missing/unverified/suspended/deletion-pending accounts denied without provisioning;
- workspace/project/membership atomic creation;
- an admitted account retaining its workspace while admissions are paused/full;
- suspension refusing a retry;
- eight simultaneous callbacks creating one workspace for one account;
- eight independent adapters racing for the thirtieth place: one admitted,
  seven Full results, no extra workspace;
- provisioning failure rolling back every new row and preserving its place;
- deleted-account tombstones not freeing places;
- PostgreSQL refusing place 31 and a second cohort independently of Kotlin;
- a membership refusing another workspace's project;
- locking adapter calls refusing autocommit misuse.

Existing `SchemaUpgradeTest` passes with bundled V1–V12 and the compatibility
proofs for existing data. Two architecture tests enforce the single writer
and demonstrate that a direct call to the concrete adapter is caught.

### Completed commands and results

From `backend/`:

```sh
./gradlew spotlessApply build --console=plain
./gradlew spotlessApply :architecture:test --console=plain
./gradlew detekt --no-daemon \
  -Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  --console=plain
```

All passed. The full build preceded moving the admission architecture tests
to their own class; the focused architecture and static checks passed again
after that move. Production code was unchanged by the move. Existing
LargeClass limits were preserved, not suppressed or raised.

From the repository root:

```sh
VERIFY_SCOPE=all ./scripts/verify-test-results.sh
./scripts/verify-test-results.test.sh
./scripts/check-migration-safety.sh
./scripts/check-backup-scope.sh --classification backend/persistence/src/main/resources/db/migration
LC_ALL=C ./scripts/check-backup-scope.test.sh
git diff --check
```

All passed: 1,467 tests across twelve backend modules, zero skipped,
failures or errors; fourteen verifier self-tests; twelve expand-only
migrations; 8 durable/21 excluded table classifications; 165 backup-scope
cases including genuine PostgreSQL dumps with the new control-plane data.
Changed runtime source directories were also scanned with Gitleaks; no leaks.

The first backup-fixture run exposed four fixtures still listing the old
five-table control plane; those now include all three new durable tables.
The existing two BOM cases fail under the laptop's default multibyte locale;
they pass under `LC_ALL=C`. The backup gate itself was not weakened or changed.
The recorded passing invocation explicitly supplies that byte-oriented locale.

| Module | Tests |
|---|---:|
| domain | 115 |
| application | 444 |
| architecture | 42 |
| persistence | 360 |
| storage | 54 |
| notification | 9 |
| observability | 35 |
| ingestion | 145 |
| api | 172 |
| migrator | 6 |
| benchmark | 57 |
| e2e | 28 |

## Still unproven / incomplete

Human login, session lifecycle, provider callback validation, email delivery,
waitlist, real authenticated console, suspension across API/ingestion,
account deletion/restore, effective cohort storage fairness, browser checks,
independent delivery reviews and live activation. M3a is a tested dormant
foundation, not a claim that the thirty-person beta is ready to release.
