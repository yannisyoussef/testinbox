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
| 14 | A scope over its limit at activation: readable, nothing admitted, recovers after retention | Engine TI-STORAGE-002; live recovery is an **enablement gate** (needs enforcement ON) | P `StorageAdmissionDecisionTest` (over-limit admits nothing) |
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
| 37–44 | The wait protocol (`409`, cursor) | **Deferred to TI-STORAGE-004** | the legacy wait is proven unchanged: API `WaitApiTest` (a refusal notify mid-wait) |
| 45 | SDK storage behaviour | **Deferred to TI-STORAGE-005** | — |
| 46 | `StorageUsage` API | **Deferred to TI-STORAGE-004** | — |
| 47 | Metric cardinality | TI-STORAGE-003 (protocol metrics); `STORAGE_LIMIT_EXCEEDED` waits for TI-STORAGE-004 | observability `MetricCardinalityTest` |
| 48 | `DeploymentSafety` with enforcement ON | **Enablement gate** (no enforcement setting exists yet) | I `EnforcementOffLiveTest` proves that no configuration can enable refusal |
| 49 | Activation barrier | **Enablement gate**; the capability names and generations it will read are published now | I `EnforcementOffLiveTest`, `GuardedIngestEdgeCasesTest` |
| 50 | ArchUnit: no unfenced payload write (sync or async client, presigner, transfer manager); only the guarded protocol calls the fenced write; layers | TI-STORAGE-003 | AR `DependencyRuleTest` (fixtures proving each rule fails) |
| 51 | Migration gate and backup scope | TI-STORAGE-001 | `scripts/check-migration-safety.test.sh`, `scripts/check-backup-scope.test.sh` |
| 52 | Owner: the physical proof | TI-STORAGE-003 | I `StorageOutageAndBoundTest` (isolated database and bucket, enforcement internal) |
| 53 | Pre-EOF stall ended by MinIO's idle timeout | TI-STORAGE-003 | S `FencedUploadTest` (MinIO's own end of the held request is observed within the window) |
| 54 | No release while the witness is blocked | TI-STORAGE-003 | I `GuardedIngestEdgeCasesTest` |
| 55 | `qualification_valid = 0` latches | **Ops / enablement gate**: the latch exists and every node honours it; the Ops `qualification-check` that sets it is not in this repository | I `GuardedIngestProtocolTest` (the latch) |

§18 implementation gates, TI-STORAGE-003:

| Gate | Where |
|---|---|
| 1. Presigner: explicit clock, signed `content-length` and `if-none-match`, on the pinned MinIO | S `FencedUploadTest` |
| 2. `T_put` total wall-clock and RST abort, proven with the TCP proxy: a stall, a peer trickling a response forever (no inactivity timeout can fire), and a write blocked by a full TCP window (no read timeout covers it) | S `FencedUploadTest` |
| 3. URL redaction, by log capture | S `FencedUploadTest` |
| 4. The tests above, with ratcheted minima | `scripts/verify-test-results.sh` |
| 5. The staging `deploy.sh` rollback-floor check | `scripts/check-rollback-floors.test.sh`, `scripts/deploy-preflight.test.sh` |

Review hardening (TI-STORAGE-003 §55–§58), each with its own test:

| Property | Where |
|---|---|
| The late-object latch commits on its own; each cleanup row is its own transaction | P `StorageProtocolPersistenceTest`, I `GuardedIngestEdgeCasesTest` |
| A dead generation's started keys each get a per-key proof at `verify_at` (coverage rows) | P `StorageProtocolPersistenceTest`, I `GuardedIngestCrashTest` F |
| The orphan sweep latches on an orphan that was ambiguous within 24 h | I `GuardedIngestCleanupTest` |
| A live generation declared dead re-registers at its next heartbeat | P `StorageProtocolPersistenceTest` |
| Refusal upserts lock in PostgreSQL (unsigned) uuid order | P `StorageProtocolPersistenceTest` |
| A clock-offset hold is durable (in the rows), covers `RESERVED` and `RELEASING`, survives a restart, never compounds, and leaves the no-skew path unchanged; a hold that cannot be written blocks every release (and keeps the node breaker open) until it is; its row locks are ordered and bounded (TI-STORAGE-003b P1-1) | P `StorageProtocolPersistenceTest`, I `ClockOffsetDurabilityTest` A–E and more |
| Any throwable after upload start is ambiguous: persisted or the slot poisoned, the reservation charged, the generation left unclean, H kept across a restart (TI-STORAGE-003b P1-2) | I `UploadErrorTest` |
| Latch and breaker are checked before recipients resolve: one `451` for everyone | I `GuardedIngestEdgeCasesTest` |
| A reservation fenced before its first byte uploads nothing | I `GuardedIngestEdgeCasesTest` |
| HTTPS verifies the storage host name | S `FencedUploadTest` |
| An early definitive answer survives a failed body write | S `FencedUploadTest` |
| Breaker kinds accumulate; backoff doubling and cap; slot all-or-nothing, poison, and the out-of-lock ambiguity read | A `StorageBreakerTest`, `WriteSlotsTest` |
