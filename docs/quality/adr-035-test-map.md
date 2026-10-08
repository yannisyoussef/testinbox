# ADR-035 §17 test map

Where each test of ADR-035 §17 lives, and which slice delivers it. "Deferred"
tests belong to slices that add a public surface. None of them protects the
live guarded ingest path, which TI-STORAGE-003 turned on with enforcement OFF.

Suite abbreviations. The directories hold the Kotlin tests:

| Code | Suite | Directory |
|---|---|---|
| **P** | persistence | `backend/persistence/src/test/.../persistence/` |
| **S** | storage | `backend/storage/src/test/.../storage/fenced/` |
| **I** | ingestion | `backend/ingestion/src/test/.../ingestion/guarded/` and `.../smtp/` |
| **A** | application | `backend/application/src/test/...` |
| **API** | api | `backend/api/src/test/...` |
| **AR** | architecture | `backend/architecture/src/test/...` |

| # | What | Status | Where |
|---|---|---|---|
| 1 | Ledger = derivation (inserts, conflicts, deletes, cascades, updates, recompute) | TI-STORAGE-001 | P `StorageLedgerTriggerTest` |
| 2 | Ledger property test | TI-STORAGE-001 | P `StorageLedgerPropertyTest` |
| 3 | Attachments count twice; a parse failure costs raw only | TI-STORAGE-001 / 003 | P `StorageLedgerTriggerTest`; I `GuardedIngestEdgeCasesTest` (parse failure) |
| 4 | V6 backfill, lock order, `lock_timeout` | TI-STORAGE-001 | P `StorageV6MigrationTest` |
| 5 | Compaction; a T1 snapshot across a compaction | TI-STORAGE-001 / 002 | P `StorageLedgerCompactionTest`, `StorageAdmissionSnapshotTest` |
| 6 | Reconciliation | TI-STORAGE-001 | P `StorageReconciliationTest` |
| 7 | Inbox, workspace and `G − H` boundaries | TI-STORAGE-002 | P `StorageAdmissionDecisionTest` |
| 8 | `INBOX_LIMIT` precedence; the share | TI-STORAGE-002 | P `StorageAdmissionDecisionTest`; domain `StorageCapacityPolicyTest` |
| 9 | Mixed event: envelope prefix, no upload for refused copies, one `250` | TI-STORAGE-002 / 003 | P `StorageAdmissionDecisionTest`; I `GuardedIngestProtocolTest` (refused copy not uploaded); I `SmtpAntiOracleTest` |
| 10 | Global exhaustion mid-event | TI-STORAGE-002 | P `StorageAdmissionDecisionTest` |
| 11 | Global concurrency (lock held; over-admission mutant) | TI-STORAGE-002 | P `StorageAdmissionConcurrencyTest` |
| 12 | Single snapshot (latched demo, statement count) | TI-STORAGE-002 | P `StorageAdmissionSnapshotTest`, `StorageAdmissionTransactionTest` |
| 13 | Unknown-only skips slot and T1; `lock_timeout` is a `451`; `synchronous_commit` | TI-STORAGE-002 / 003 | P `StorageAdmissionTransactionTest`; I `GuardedIngestEdgeCasesTest` |
| 14 | A scope over its limit at activation: readable, nothing admitted, recovers after retention | Engine TI-STORAGE-002; refusal under `TENANT_LIMITS`/`ALL` proven internally in TI-STORAGE-006; live recovery after retention is an **enablement gate** (needs a real non-OFF deployment) | P `StorageAdmissionDecisionTest` (over-limit admits nothing); I `EnforcementModesTest` (TENANT_LIMITS: `INBOX_LIMIT`/`WORKSPACE_LIMIT` refuse and `SERVICE_CAPACITY` stays observational; ALL: all three refuse; a set activation guard is the infrastructure refusal `ACTIVATION_VIOLATED`, mapped to `451` by the gateway like every `StorageUnavailableException`, never a refusal record) |
| 15 | Slot fairness, `W_slot` `451` with no reservation, no deadline spent queueing | TI-STORAGE-003 | I `GuardedIngestProtocolTest` (fairness, slot timeout, T1 only after the slot) |
| 16 | Fence: an expired start `403`, a size mismatch `403`, a replay `412` (object unchanged); URLs never logged | TI-STORAGE-003 | S `FencedUploadTest` |
| 17 | Owner: crash after upload, before T2 | TI-STORAGE-003 | I `GuardedIngestCrashTest` B, C |
| 18 | Owner: expiry before any upload | TI-STORAGE-003 | I `GuardedIngestCrashTest` A |
| 19 | A late object at `afterDeleteBeforeList` | TI-STORAGE-003 | I `GuardedIngestCleanupTest` |
| 20 | T2 fenced by cleanup, both orders | TI-STORAGE-003 | P `StorageProtocolPersistenceTest`; I `GuardedIngestCleanupTest` |
| 21 | Response swallowed after the full body: ambiguous, slot kept, no inline release | TI-STORAGE-003 | S `FencedUploadTest`; I `GuardedIngestCrashTest` D |
| 22 | Stalled body, `T_put`: RST on the wire, no object | TI-STORAGE-003 | S `FencedUploadTest` |
| 23 | `5xx` and reset are ambiguous; `2xx`, `403`, `411`, `412` and quota `400` are definitive | TI-STORAGE-003 | S `FencedUploadTest` (canned answers) |
| 24 | The per-exact-key multipart proof (M2 regression); the bucket-wide guard | TI-STORAGE-003 | I `GuardedIngestCleanupTest` |
| 25 | Ambiguity slots: 16 block; breaker cycles and restarts do not free them; an unclean shutdown leaves keyless rows | TI-STORAGE-003 | I `GuardedIngestCrashTest` E; P `StorageProtocolPersistenceTest` |
| 26 | A late object latches; every node `451`s before T1; only an operator clears it | TI-STORAGE-003 | I `GuardedIngestCleanupTest`, `GuardedIngestProtocolTest` (latch) |
| 27 | ADR-026 duplicate; storage down during cleanup; inbox deleted while `RESERVED`; two cleaners; path [D] | TI-STORAGE-003 | I `GuardedIngestProtocolTest` (duplicate), `GuardedIngestEdgeCasesTest`; P `StorageProtocolPersistenceTest` (two cleaners) |
| 28 | `OrphanBlobSweep` single statement | TI-STORAGE-003 | P `StorageProtocolPersistenceTest` (`isOrphan`); A `LifecycleTest` |
| 29 | Clock offset: breaker opens, releases suspend, then are pushed back | TI-STORAGE-003 | I `GuardedIngestCleanupTest` |
| 30 | Breaker kinds: a quota trial is a real event; the zero-byte probe for the others | TI-STORAGE-003 | I `GuardedIngestProtocolTest` |
| 31 | Outage plus edge retries, then recovery | TI-STORAGE-003 | I `StorageOutageAndBoundTest` |
| 32 | Deterministic lock order, including an old-binary path | TI-STORAGE-001 / 003 | P `StorageLedgerConcurrencyTest`, `StorageProtocolPersistenceTest` (T2 against retention) |
| 33 | Owner: anti-oracle transcript | TI-STORAGE-003 | I `SmtpAntiOracleTest` |
| 34 | Quota classification by fault injection | TI-STORAGE-003 | S `FencedUploadTest`; I `GuardedIngestProtocolTest` |
| 35 | MinIO stopped: `451`, then admission after restart | TI-STORAGE-003 | I `StorageOutageAndBoundTest` (container paused and resumed: a hung storage, asserted AMBIGUOUS and persisted); refused connections: `GuardedIngestProtocolTest` (unreachable, asserted UNAVAILABLE) |
| 36 | `552`, `553` and the unknown `250` unchanged; gateway cap = `contract.yaml` | TI-STORAGE-003 | I `SmtpIngestionIntegrationTest` |
| 37 | Owner: refusal before the first wait, cursor 0 → `409` at once, no slot | TI-STORAGE-004 | API `WaitStorageRefusalApiTest` (owner test 37, refusal by the real guarded protocol); A `WaitForMessageStorageRefusalTest` |
| 38 | Refusal during the wait, via real ingestion: `409`, not `TIMEOUT` | TI-STORAGE-004 | I `SmtpRefusalWakesWaitTest` (a real SMTP `DATA` refused with internal `TENANT_LIMITS`, the §6a `pg_notify` through a live LISTEN connection, a parked `WaitForMessage` ends with the refusal); API `WaitStorageRefusalApiTest` (owner test 38 over HTTP: `GuardedRefusals` runs `GuardedStorage` downstream of recipient resolution, LISTEN proven live with no reconnect across the call, so the wake is the notify and not the degraded tick) |
| 39 | Refusal after the match committed: `MATCHED`; the next wait with the unadvanced cursor gets `409` | TI-STORAGE-004 | API `WaitStorageRefusalApiTest`; A `WaitForMessageStorageRefusalTest` |
| 40 | Match racing a refusal, both orders, one snapshot decides | TI-STORAGE-004 | API `WaitStorageRefusalApiTest` (the `WaitSyncHook` seam commits between check and recheck); A `WaitForMessageStorageRefusalTest`; P `WaitObservationsTest` (REPEATABLE READ, with a READ COMMITTED mutant that sees the split) |
| 41 | Cursor edges: equal, above (clamped once), negative (`400`), omitted (never `409`, `TIMEOUT` carries the count) | TI-STORAGE-004 | API `WaitStorageRefusalApiTest`; A `WaitForMessageStorageRefusalTest` (including `Long.MAX_VALUE`) |
| 42 | The echo comes from the deciding snapshot; a refusal committed after it is not echoed | TI-STORAGE-004 | API `WaitStorageRefusalApiTest` (`afterDecision` seam), A `WaitForMessageStorageRefusalTest` |
| 43 | Chained calls, independent callers, restart | TI-STORAGE-004 (raw REST); TI-STORAGE-005 (SDK) | API `WaitStorageRefusalApiTest` (caller-managed cursor, two credentials, no cursor table or column in the schema); TS `src/storage.test.ts` and JVM `StorageSdkTest` (two Inbox objects keep independent cursors, a fresh object seeds from the server's count, a persisted boundary resumes explicitly, each chained window carries the current cursor) |
| 44 | Precedence: `409` before slot `429` with no slot consumed; another inbox's refusal; LISTEN killed; `404`/`410`; request-rate `429` first | TI-STORAGE-004 | API `WaitStoragePrecedenceApiTest` (one slot per workspace, `pg_terminate_backend` on the LISTEN session), `RateLimitOrderingTest` |
| 45 | SDK storage behaviour: typed error, cursor seeded from the Inbox, never advanced on MATCHED or TIMEOUT, advanced on a surfaced `409` and on an explicit boundary (monotonic, concurrent-safe), `observeStorageRefusals = false`, `getWorkspaceStorage`, snapshots, unknown reason, older-server omission, Java interop, Node 22/24/26, Java 17/21/25, count floors | TI-STORAGE-005 | TS `sdk/typescript/src/storage.test.ts` (28, mocked fetch incl. out-of-order concurrent 409s); JVM `StorageSdkTest` (26, scripted HttpServer incl. gated concurrent 409s), `StorageProtocolRobustnessTest` (3: a wrong-typed or undecodable storage member on a `200` or a storage-limit `409` is a typed protocol error, never a serialisation exception or a generic conflict, cursor untouched), `StorageRefusalCursorTest` (atomic max under 8-thread ramp contention and under barrier-released crossing advances, with a last-writer-wins mutant proven caught by the crossing harness), `JavaInteropTest` (compiled Java against the new surface); e2e `SdkStorageAcceptanceTest` (JVM: real `409` from the API with the cursor advanced before the handler, opt-out, `getWorkspaceStorage` vs raw REST and seeded accounting, fresh-vs-old snapshots), `TsSdkIntegrationTest` + `live.test.ts` (TS: real `409` on an object that already exists via the harness handshake, seeded cursor, lower explicit boundary, opt-out, `getWorkspaceStorage`); matrices and floors in `.github/workflows/ci.yml` |
| 46 | `StorageUsage` for the workspace and the inbox equals the accounting; inbox `availableBytes` is the minimum headroom; no global figure in any tenant response; idempotent replay returns live fields; the contract stays additive | TI-STORAGE-004 | P `StorageVisibilityTest` (every base/delta/reservation combination, missing base row, `RESERVED` and `RELEASING`, tenant scoping, corrupt row, concurrent compaction, no advisory lock, plan sanity); A `StorageQueriesTest`, `EffectiveStoragePolicyTest`; API `StorageVisibilityApiTest`, `StorageDisclosureTest`; AR `DependencyRuleTest` (one policy construction site); CI `openapi-breaking-check.sh` |
| 47 | Metric cardinality, including `STORAGE_LIMIT_EXCEEDED` | TI-STORAGE-003 / 004 | observability `MetricCardinalityTest`; A `WaitForMessageStorageRefusalTest` (the outcome is recorded) |
| 48 | `DeploymentSafety` with enforcement ON | TI-STORAGE-006 | A `StorageEnforcementSafetyTest` (the §46 negative matrix, one reason per test: missing/invalid G, missing Q, Q one byte below the fuse, missing/zero process count, H overflow, H ≥ G, share ≤ 0 and > 1, missing share, missing churn, missing identity, no shipped record, unknown record, each of 11 contract-element mismatches, upload-implementation mismatch, an ineligible match, non-OFF outside a declared environment; plus the §47 OFF matrix); domain `BucketQuotaFuseTest` (10 % = 10 % of G, exact boundary ± 1 byte, large H, churn, overflow); A `QualificationMatchTest` (every element invalidates; eligibility re-derived); S `QualificationRecordsTest` (strict loading; the shipped laptop record is complete and NOT eligible), `FencedUploaderVersionTest`; API `DeploymentSafetyCheckTest` and I `IngestionDeploymentSafetyCheckTest` (the real property names: TENANT_LIMITS refused with every missing key named, ALL refused without an environment, the laptop identity refused, unknown value fails to bind, OFF default); I `EnforcementOffLiveTest` (default OFF through real SMTP; misspelled keys do nothing; mode gauge `off`); scripts `check-storage-enforcement-off.sh` + `.test.sh` (no committed environment may turn it on) |
| 49 | Activation barrier | TI-STORAGE-006 (tooling and runtime checks); the REAL run against a deployed estate is an **enablement gate** | A `ActivationBarrierTest` (pgJDBC default, `testinbox-listen`, wrong capability suffix, empty name; migrator and `ops:` exclusions; missing api/ingestion node, stale heartbeat, clean shutdown, wrong capability, extra undeclared node, undeclared inventory = NOT RUN; the watch: OFF observes without a violation, TENANT_LIMITS/ALL raise the gauge and fail closed, then clear); P `JdbcActivationInventoryTest` (sessions read by name under the application role, node rows, DB clock, `reservedBytes`); I `EnforcementModesTest` (guard → `451` before slot and T1), `EnforcementOffLiveTest` (session names); API `SweepSchedulerTest` (the watch runs on every cleanup pass, verified); I `StorageNodeRuntimeActivationTest` (the watch runs at start and on every heartbeat; OFF observes, non-OFF sets the guard); scripts `check-storage-activation.sh` + `.test.sh` (every gate A–E and Q fails independently on fixtures; gate E production BLOCKED against this repository's real `master`) |
| 50 | ArchUnit: no unfenced payload write (sync or async client, presigner, transfer manager); only the guarded protocol calls the fenced write; layers; one policy construction and one T1 construction | TI-STORAGE-003 / 006 | AR `DependencyRuleTest` (fixtures proving each rule fails). The `backend/benchmark` module is on the architecture classpath and its `ProtocolAssembly` is the ONE named exemption from the policy and T1 construction rules: a measurement CLI, not a deployable |
| 51 | Migration gate and backup scope | TI-STORAGE-001 | `scripts/check-migration-safety.test.sh`, `scripts/check-backup-scope.test.sh` |
| 52 | Owner: the physical proof | TI-STORAGE-003 | I `StorageOutageAndBoundTest` (isolated database and bucket, enforcement internal) |
| 53 | Pre-EOF stall ended by MinIO's idle timeout | TI-STORAGE-003 | S `FencedUploadTest` (MinIO's own end of the held request is observed within the window) |
| 54 | No release while the witness is blocked | TI-STORAGE-003 | I `GuardedIngestEdgeCasesTest` |
| 55 | `qualification_valid = 0` latches | **Ops / enablement gate**: the latch exists and every node honours it (`451` before T1, no refusal record); the Ops `qualification-check` that publishes the signal and sets the latch is not in this repository — its input/output contract is in `docs/architecture/storage-activation.md`, and `check-storage-activation.sh --qualification-valid-metric` consumes its verdict | I `GuardedIngestProtocolTest` (the latch); S `QualificationRecordsTest` (the record the checker compares against) |

§18 implementation gates, TI-STORAGE-003:

| Gate | Where |
|---|---|
| 1. Presigner: explicit clock, signed `content-length` and `if-none-match`, on the pinned MinIO | S `FencedUploadTest` |
| 2. `T_put` total wall-clock and RST abort, proven with the TCP proxy: a stall, a peer trickling a response forever (no inactivity timeout can fire), and a write blocked by a full TCP window (no read timeout covers it) | S `FencedUploadTest` |
| 3. URL redaction, by log capture | S `FencedUploadTest` |
| 4. The tests above, with ratcheted minima | `scripts/verify-test-results.sh` |
| 5. The staging `deploy.sh` rollback-floor check; two ADR-035 floors (`c84ddd7`, first guarded ingest; `d4e38b2`, the TI-STORAGE-003 safety floor) | `scripts/check-rollback-floors.test.sh` (cases A–E on the real history), `scripts/deploy-preflight.test.sh` |

Review hardening (TI-STORAGE-003 §55–§58), each with its own test:

| Property | Where |
|---|---|
| The late-object latch commits on its own; each cleanup row is its own transaction | P `StorageProtocolPersistenceTest`, I `GuardedIngestEdgeCasesTest` |
| A dead generation's started keys each get a per-key proof at `verify_at` (coverage rows) | P `StorageProtocolPersistenceTest`, I `GuardedIngestCrashTest` F |
| The orphan sweep latches on an orphan that was ambiguous within 24 h | I `GuardedIngestCleanupTest` |
| A live generation declared dead re-registers at its next heartbeat | P `StorageProtocolPersistenceTest` |
| Refusal upserts lock in PostgreSQL (unsigned) uuid order | P `StorageProtocolPersistenceTest` |
| A clock-offset hold is durable (in the rows), covers `RESERVED` and `RELEASING`, survives a restart, never compounds, and leaves the no-skew path unchanged; a hold that cannot be written blocks every release (and keeps the node breaker open) until it is; its row locks are ordered and bounded (TI-STORAGE-003b P1-1) | P `StorageProtocolPersistenceTest`, I `ClockOffsetDurabilityTest` A–E and more |
| A recorded clock episode survives a crash before its hold: `RESERVED` and `RELEASING` cannot release early in a fresh process; recovery is idempotent; observations never shorten the horizon; normal cleanup resumes (TI-STORAGE-003c) | I `ClockOffsetDurabilityTest` 1, 2, 5; P `StorageProtocolPersistenceTest` 3, 4 |
| Any throwable after upload start is ambiguous: persisted or the slot poisoned, the reservation charged, the generation left unclean, H kept across a restart (TI-STORAGE-003b P1-2) | I `UploadErrorTest` |
| Latch and breaker are checked before recipients resolve: one `451` for everyone | I `GuardedIngestEdgeCasesTest` |
| A reservation fenced before its first byte uploads nothing | I `GuardedIngestEdgeCasesTest` |
| HTTPS verifies the storage host name | S `FencedUploadTest` |
| An early definitive answer survives a failed body write | S `FencedUploadTest` |
| Breaker kinds accumulate; backoff doubling and cap; slot all-or-nothing, poison, and the out-of-lock ambiguity read | A `StorageBreakerTest`, `WriteSlotsTest` |

TI-STORAGE-004 (authenticated visibility and the raw REST wait cursor; live enforcement still OFF):

| Property | Where |
|---|---|
| T1 and the API derive the effective policy from one factory (`EffectiveStoragePolicy`); no other main class constructs a `StorageCapacityPolicy` | A `EffectiveStoragePolicyTest`, AR `DependencyRuleTest`, API `StorageVisibilityApiTest` |
| Every wait evaluation is one short read-only REPEATABLE READ snapshot (messages, attachments, refusal record, and the `409` figures on demand), never held while parked, never inside a caller's transaction | P `WaitObservationsTest` |
| `IngestionWiring` still constructs `StorageEnforcement.OFF` as a literal and no configuration can refuse | I `EnforcementOffLiveTest` (unchanged) |
| The legacy wait (no cursor) can never receive `409`, with refusals already recorded and with one arriving while parked | API `WaitApiTest`, `WaitStorageRefusalApiTest`; A `WaitForMessageStorageRefusalTest`; e2e `StorageVisibilityAcceptanceTest`, `TsSdkIntegrationTest` |
| `GET /v1/workspace/storage` is `READ` by an explicit rule, needs `messages:read`, and takes no workspace id from path, query or header | API `RouteCoverageTest`, `StorageVisibilityApiTest` |
| A `SERVICE_CAPACITY` `409` carries no `quota`, `limit` or `current` member, and no tenant response names a global, node or reservation-level figure | API `StorageDisclosureTest` |
| A refusal row that contradicts itself fails closed instead of presenting a made-up reason | P `StorageVisibilityTest` |

Filesystem containment, PR C (TI-STORAGE-006E; contract PROPOSED in #82):

| Property | Where |
|---|---|
| `507 XMinioStorageFull` and a `500` naming ENOSPC are `STORAGE_FULL`; a plain `500`, a `503` and a bare `507` are not | S `StorageFullClassificationTest` |
| `STORAGE_FULL` needs a real-event trial, is never reopened by a timer, and consumes no trial while there is no evidence; the evidence is read only when a trial could be due; a failing check is no evidence | A `StorageBreakerTest` |
| Evidence = the newest observation, younger than *A_obs*, not from the future, with `avail ≥ R_ops`; undeclared figures fall back to the contract's floors | A `StorageFullEvidenceTest`; P `FilesystemObservationsTest` |
| On the real stack: ambiguous and charged, the same `451` for known and unknown recipients, no trial without evidence, recovery through a real event; `500` ENOSPC vs plain `500` | I `StorageFullBreakerTest` |
| `DeploymentSafety`: the fuse is no longer load-bearing; every filesystem declaration is required under non-OFF; `G_F + D_budget + M + R_ops ≤ C_fs`; `R_ops ≥ max(5 %, 2 GiB)`; one inode per block; `H_F < G_F`; malformed figures refused in every mode | A `StorageEnforcementSafetyTest`; API/I `DeploymentSafetyCheckTest`, `IngestionDeploymentSafetyCheckTest` |
