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


## M3b — revocable sessions and account HTTP boundary

Local implementation under accepted ADR-036; no deployment or login issuer.

- `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./gradlew spotlessApply build --console=plain`:
  PASS. Full domain/application/adapter/API/SMTP/E2E regression build.
- `./gradlew detekt --no-daemon -Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home --console=plain`:
  PASS. Runtime/compilation target remains Java 25.
- `VERIFY_SCOPE=all ./scripts/verify-test-results.sh`: PASS, 1,513 tests,
  twelve modules, zero failures/errors/skips. Floors ratcheted to actual counts.
- Module counts: domain 119, application 447, architecture 44, persistence 379,
  storage 54, notification 9, observability 35, ingestion 145, API 190,
  migrator 6, benchmark 57, E2E 28.
- `LC_ALL=C ./scripts/check-backup-scope.test.sh`: PASS, 168/168. Adds
  classification-omission, forbidden-retention and genuine pg_dump session
  regressions. Scope: eight durable tables, twenty-two excluded tables.
- Migration safety: all thirteen migrations expand-only; verifier self-test
  14/14; formatting, diff and scoped Gitleaks scans pass.

Failure history retained: the first sixteen session integration cases had
12 failures because `SpringTransactionRunner` asserted nullable callbacks
non-null after commit. Its value wrapper fixes the generic adapter; three
real database tests prove nullable commit, nested/Unit values and exception
rollback. A subsequent full domain/application/architecture/persistence run
found two harness expectations: Kotlin value-class method mangling in the
new writer guard and the bundled migration-list assertion. Both corrected
without relaxing the invariant or test floor. The first API run failed before
API cases initialized because Docker Ryuk was unreachable via localhost;
explicit local IPv4 passed with resource cleanup still enabled. The final
full build uses that address and includes all cases.

Evidence: sixteen session/PostgreSQL cases (including eight independent
issuers racing for five sessions), three transaction cases, four domain
boundary cases, three credential cases, two writer-guard cases, sixteen
cookie/account HTTP cases, and two account schema-gate cases. HTTP tests use
an explicit test cookie over disposable HTTP; the cookie builder and response
assertions require Secure/HttpOnly/SameSite/host-only attributes. They do not
claim a real browser/BFF/provider callback has been verified.

## Frontend companion milestone

Local `48f8607` reuses MR !4; `ad38dea` updates only DOMPurify and its lock
entry to 3.4.17 plus the checkpoint. Clean install, typecheck, production build,
35 unit tests and all 28 fixture-backed browser tests pass. The stream-window
fixture correction still verifies two real resumes, deduplication and one
open stream. `npm audit --omit=dev`: zero vulnerabilities. No real account
proxy, email/OAuth flow or cross-repository acceptance is claimed yet.


## M3c — bounded email verification/identity core

Full `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./gradlew spotlessApply build
--console=plain`: PASS. After adding only the scheduler unit cases, the final
full `:api:test` also passes. `VERIFY_SCOPE=all` confirms 1,552 tests across
twelve modules, zero failures/errors/skips: domain 122, application 451,
architecture 46, persistence 406, API 193; other modules unchanged.

Detekt with the documented Java 21 invocation passes; the two initially
flagged contact-validation conditions were separated into named checks,
without suppressions or threshold changes. Initial Spotless failures concerned
an overlong assertion and a constructor whose lambda indentation exceeded
the line limit; both split, no formatter relaxation. The focused 27 PostgreSQL
flow/budget/identity cases and seven domain/application helper cases pass.

`LC_ALL=C ./scripts/check-backup-scope.test.sh`: 175/175. The real dump has
identity, challenge, session and registration-lock rows. Every new transient
row is excluded, and each deliberately retained variant fails for its exact
reason. Scope: nine durable, twenty-four excluded tables. Migration safety:
all fourteen expand-only. Credentials/sending are test fixtures only; no
external provider protocol, SMTP integration or real email delivery is claimed.


## M3d — email HTTP, delivery adapter and configuration boundary

Final `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./gradlew spotlessApply build
--console=plain`: PASS (1m 23s final run). Verifier: 1,570 tests across twelve
modules, zero failures/errors/skips; API floor rises from 193 to 211, other
module counts unchanged. Detekt, formatting and diff checks pass.

Eighteen new API/adapter/configuration cases include twelve email journey
cases, missing-origin/provider-config isolation, a genuine local SMTP peer
that advertises AUTH without STARTTLS (no AUTH/MAIL/RCPT/DATA or code reaches
it), and the actual shipped YAML's environment mappings. The account body
ceiling catches both declared-length and chunked oversized requests before
JSON conversion, including an encoded controller path. Two Set-Cookie values
are asserted separately; same-account re-authentication invalidates the old
cookie. A caller cannot reset the peer budget through X-Forwarded-For.

Initial HTTP failures: the fixture key had 35 bytes instead of 32, leaving
email correctly unavailable; the incomplete-config test used an HTTP helper
that omitted Origin and was correctly refused. Fixed fixture/transport only.
Overlong controller chains were split for Spotless; no rule was suppressed.

The mail adapter uses Spring context-support plus the already pinned mail
API/Angus dependencies, avoiding generic spring.mail auto-configuration.
No sending identity, host setting, password, DNS or live service was touched.
These tests do not claim external delivery, Google/GitHub protocol validation
or a real browser/BFF journey. Production flags and admissions remain off.


## M3e — bounded encrypted provider state and verified-provider transaction

Final full `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 ./gradlew build
--console=plain`: PASS (1m 26s). Final verifier: 1,596 tests across twelve
modules, zero failures/errors/skips. Counts: architecture 49, domain 124,
application 455, persistence 420, API 214; other module counts unchanged.
Detekt on Java 21 and formatting pass without suppressions or threshold
changes. Backup suite 178/178; nine tables retained, twenty-five excluded;
all fifteen migrations expand-only. Scoped secret scans find no leaks.

Four crypto-helper cases, fourteen PostgreSQL state/sign-in cases, two domain
boundary cases, three cleanup and three writer/issuer guard cases were added.
State is encrypted and contextual authentication fails for altered bytes,
provider, state hash or key. Eight independent consumers yield exactly one
request. Real limits span peers/providers and preserve consumed/replaced rate
metadata. Atomic provider identity/session rollback is verified. These are
upstream-proof-assuming application tests, not actual Google/GitHub protocol
acceptance, which remains M3f.

Failure history: an inherited test helper had a default-argument overload
ambiguity; it now has a distinct provider name. The new guard initially counted
Kotlin's issuer default-argument self-delegation as an external caller. The
corrected guard excludes self-calls only and still fails both concrete-state
and unverified-issuer negative fixtures. The initial full build had only that
guard failure; focused guard/static checks and the final full build pass.

Readonly deployment reference: infinity-core `9b4f57e70687cb64dc08447d3dd6ecf5ffd715c7`,
`infra/testinbox/reconcile.sh` gate 3b compares the candidate's backup scope and
checker to root-installed copies before migrations. No infrastructure change.
