package email.testinbox.benchmark

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.EscrowInputs
import email.testinbox.application.port.EscrowRegrantKind
import email.testinbox.application.port.IncompleteUpload
import email.testinbox.application.port.PhysicalFailureKind
import email.testinbox.application.port.ReservedUpload
import email.testinbox.application.port.ServerTime
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.port.UploadOutcome
import email.testinbox.application.storage.AdmissionLocking
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.FootprintWiring
import email.testinbox.application.storage.GuardedStorage
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.storage.StorageNode
import email.testinbox.application.storage.StorageNodeLifecycle
import email.testinbox.application.storage.StorageProtocol
import email.testinbox.application.storage.WriteSlots
import email.testinbox.application.usecase.CompactStorageLedger
import email.testinbox.application.usecase.EscrowAdmission
import email.testinbox.application.usecase.ExpireInboxes
import email.testinbox.application.usecase.RefillStorageEscrow
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.EscrowSlowPath
import email.testinbox.persistence.JdbcEscrowAdmission
import email.testinbox.persistence.JdbcExactAddressReservations
import email.testinbox.persistence.JdbcInboxRepository
import email.testinbox.persistence.JdbcMessageRepository
import email.testinbox.persistence.JdbcStorageAdmission
import email.testinbox.persistence.JdbcStorageAmbiguity
import email.testinbox.persistence.JdbcStorageLedger
import email.testinbox.persistence.JdbcStorageReservations
import email.testinbox.persistence.SpringTransactionRunner
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Per-event timings, attached to the worker thread that runs the event so the
 * timing wrappers below can report into it without the protocol objects
 * knowing anything about the benchmark.
 */
class EventTimings {
    @Volatile var t1EndNanos: Long = 0

    @Volatile var t1Nanos: Long = 0

    @Volatile var t2Nanos: Long = 0

    @Volatile var lockWaitNanos: Long = 0

    @Volatile var slotWaitNanos: Long = 0

    /** `--t1-steps` / `--focused` only ([HoldTiming]): T1's steps, stamped as they happen; empty otherwise. */
    val steps = T1StepClock()

    /** `--escrow-variant` only: the fast path's steps ([HoldTiming], [TimingEscrowStore]). */
    val escrow = EscrowStepClock()

    /** `--escrow-variant` only: the stub `t1_schedule` spend ([ScheduleStubHook]), inside completion − due. */
    @Volatile var scheduleNanos: Long = 0

    /** Stub spends of this event: one per T1 attempt (OD-5). Written on the event's own thread only. */
    @Volatile var scheduleSpends: Int = 0

    companion object {
        val current: ThreadLocal<EventTimings?> = ThreadLocal()
    }
}

/** Times every T1 the real store runs. The store is the real `JdbcStorageAdmission`; nothing is changed, only observed. */
class TimingAdmissionStore(
    private val delegate: StorageAdmissionStore,
) : StorageAdmissionStore {
    override fun <R : Any> admit(
        scope: StorageAdmissionScope,
        decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
    ): R {
        val started = System.nanoTime()
        EventTimings.current
            .get()
            ?.steps
            ?.begin(started)
        try {
            return delegate.admit(scope, decide)
        } finally {
            val end = System.nanoTime()
            EventTimings.current.get()?.let {
                // An escrow event that escalated: T1 began with its fast attempt (0 under mode a).
                val begun = if (it.escrow.escalated && it.escrow.start != 0L) it.escrow.start else started
                it.t1Nanos = end - begun
                it.t1EndNanos = end
            }
        }
    }
}

/** Times every transaction `GuardedStorage` opens: with a generous policy that is exactly T2. */
class TimingTransactionRunner(
    private val delegate: TransactionRunner,
) : TransactionRunner {
    override fun <T> required(block: () -> T): T {
        val started = System.nanoTime()
        try {
            return delegate.required(block)
        } finally {
            EventTimings.current.get()?.let { it.t2Nanos = System.nanoTime() - started }
        }
    }
}

/** Records the protocol signals per event (through [EventTimings]) and in aggregate. */
class RecordingProtocolMetrics : StorageProtocolMetrics {
    val physicalFailures = ConcurrentHashMap<PhysicalFailureKind, AtomicInteger>()
    val commitFenced = AtomicInteger()

    override fun lockWait(duration: Duration) {
        EventTimings.current.get()?.let { it.lockWaitNanos = duration.toNanos() }
    }

    override fun slotWait(duration: Duration) {
        EventTimings.current.get()?.let { it.slotWaitNanos = duration.toNanos() }
    }

    override fun physicalFailure(kind: PhysicalFailureKind) {
        physicalFailures.computeIfAbsent(kind) { AtomicInteger() }.incrementAndGet()
    }

    override fun commitFenced() {
        commitFenced.incrementAndGet()
    }
}

/**
 * The upload step, stubbed: every fenced PUT is Stored instantly. The
 * benchmark measures the DATABASE protocol (slot, T1, fence rows, T2,
 * retention, compaction); `T_put` and the storage backend are qualified
 * separately (ADR-035 §9a). The real adapter's result type is used so
 * `GuardedStorage` runs its real success path.
 */
object InstantBlobStore :
    BlobStore,
    StorageInspection {
    override fun putReserved(upload: ReservedUpload): UploadOutcome = UploadOutcome.Stored

    override fun get(key: String): ByteArray? = null

    override fun delete(key: String) = Unit

    override fun deletePrefix(prefix: String) = Unit

    override fun listKeysOlderThan(
        prefix: String,
        olderThan: Instant,
    ): List<String> = emptyList()

    override fun objectExists(key: String): Boolean = false

    override fun incompleteUploadExists(key: String): Boolean = false

    override fun deleteObject(key: String) = Unit

    override fun incompleteUploads(): List<IncompleteUpload> = emptyList()

    override fun abortIncompleteUpload(upload: IncompleteUpload) = Unit

    override fun witness(probeKey: String): Boolean = true

    override fun serverTime(): ServerTime = ServerTime(Instant.now().truncatedTo(ChronoUnit.SECONDS), Duration.ZERO)

    override fun listedPayloadBytes(): Long = 0
}

/**
 * ADR-035 §11 mode `c`, the default reference: the real adapter with its
 * `pg_advisory_xact_lock` step removed. The one-statement snapshot (global
 * sums and, like production, the footprint reads included) and the
 * reservation insert are untouched. It is a measurement mutant: it lives only
 * here, never in a deployable, and the architecture suite names
 * `ProtocolAssembly` as the one exemption allowed to construct the protocol
 * outside the ingestion wiring. It is the baseline, never the implementation
 * under test.
 */
class NoLockAdmissionStore(
    jdbc: JdbcClient,
    transactions: TransactionTemplate,
    readsFootprint: Boolean,
) : JdbcStorageAdmission(jdbc, transactions, readsFootprint = readsFootprint) {
    override fun acquireAdmissionLock() = Unit
}

/**
 * The real protocol objects, assembled by hand over one pool the way
 * `IngestionWiring` assembles them in production (#120 verdict §2.3):
 * `StorageAdmission` built by `FootprintWiring.admission` over
 * `JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations))`,
 * `GuardedStorage` with the real `JdbcStorageReservations` fence,
 * `JdbcStorageAmbiguity` (also the latch) registering the node with the
 * deployment's cohort mode and binding, `WriteSlots` with production's
 * orphaned-row count under `ALL`, `StorageBreaker`, `SpringTransactionRunner`,
 * and `ExpireInboxes` over `JdbcInboxRepository` for retention. Only the
 * upload step is a stub ([InstantBlobStore]). [declarations] are
 * [HarnessDeclarations]: a filesystem is declared, as in production `ALL`.
 */
class ProtocolAssembly(
    dataSource: DataSource,
    private val options: BenchmarkOptions,
    val mode: AdmissionMode,
    val declarations: StorageDeclarations,
    /** Runs on the PARTITIONED variant's closed cohort (any mode): the node must be the declared ingestion node. */
    onClosedCohort: Boolean = false,
) {
    val jdbc: JdbcClient = JdbcClient.create(dataSource)
    private val transactionManager = DataSourceTransactionManager(dataSource)
    private val template = TransactionTemplate(transactionManager)
    val metrics = RecordingProtocolMetrics()

    /**
     * The PARTITIONED variant's node is the declared ingestion node: its approved
     * pool requires every live node to be an expected one with the pool's binding
     * (production's `nodesReady`), exactly as in a real partitioned deployment.
     */
    val node =
        StorageNode(
            if (mode == AdmissionMode.PARTITIONED || onClosedCohort) {
                declarations.expectedIngestionNodes.single()
            } else {
                "bench-${ProcessHandle.current().pid()}"
            },
            UUID.randomUUID(),
        )

    /** Production's footprint policy for these declarations (null only if no filesystem were declared). */
    val footprint = EffectiveStoragePolicy.footprint(declarations)

    private val reservations = JdbcStorageReservations(jdbc, template)
    private val ambiguity =
        JdbcStorageAmbiguity(jdbc, template, declarations.enforcement, footprint?.cohort?.binding, declarations.admissionLocking)
    private val lifecycle = StorageNodeLifecycle(ambiguity, node, options.slots).also { it.start() }
    val slots =
        WriteSlots(
            options.slots,
            options.slotsPerWorkspace,
            StorageProtocol.SLOT_WAIT,
            orphaned =
                FootprintWiring.orphanedSlots(declarations) {
                    ambiguity.unresolvedOrphaned(StorageProtocol.STALE_HEARTBEAT)
                },
        ) { ambiguity.unresolvedFor(node.nodeId) }

    // Generous: nothing is refused, so every T1 inserts and every event reaches T2. The lock and the rows are what is measured.
    private val policy =
        when (mode) {
            AdmissionMode.CHOSEN, AdmissionMode.PARTITIONED, AdmissionMode.ESCROW -> {
                StorageCapacityPolicy(
                    1L shl 40,
                    InboxShare.of("1"),
                    1L shl 50,
                    0,
                )
            }

            // No global ceiling: an effectively unlimited G.
            AdmissionMode.REFERENCE -> {
                StorageCapacityPolicy(1L shl 40, InboxShare.of("1"), Long.MAX_VALUE, 0)
            }
        }

    /** `--escrow-variant`: the escrow's closed-label signals for this scenario (NON-GATE). */
    val escrowMetrics = RecordingEscrowMetrics()

    /** The escrow's application inputs: mode a's own policy and footprint model (the scope rows' binding). */
    val escrowInputs: EscrowInputs? =
        if (mode ==
            AdmissionMode.ESCROW
        ) {
            EscrowInputs(policy.workspaceLimitBytes, policy.inboxShare.value, checkNotNull(footprint).model)
        } else {
            null
        }

    /** The T1 adapter this mode runs: exactly production's for CHOSEN (see [admissionStore]); for ESCROW, mode a with the slow-path strategy. */
    val store: JdbcStorageAdmission =
        admissionStore(
            jdbc,
            template,
            mode,
            options.referenceMode,
            declarations,
            escrowInputs?.let { inputs ->
                EscrowSlowPath(jdbc, inputs) { regrant, duration ->
                    escrowMetrics.regrant(EscrowRegrantKind.SLOW_PATH, duration, regrant.granted)
                }
            },
        ) { reason ->
            fallbackReasons
                .computeIfAbsent(reason.name) {
                    java.util.concurrent.atomic
                        .AtomicLong()
                }.incrementAndGet()
        }

    /** Every fallback of a partitioned T1 to mode a, by reason (what production's metric would count). */
    val fallbackReasons = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /**
     * ADR-027 INGEST as production charges it (per inbox, then per workspace), with
     * production's default limits: the timing-probe scenario's flood and probes go
     * through it, as delivery does before T1.
     */
    val ingestLimiter: email.testinbox.application.port.RateLimiter by lazy {
        val limits =
            email.testinbox.application
                .LimitsProperties()
                .toConfig()
        email.testinbox.persistence.JdbcRateLimiter(jdbc, transactionManager) { category, perInbox -> limits.rateFor(category, perInbox) }
    }

    /** ESCROW: the stub `t1_schedule` (benchmark-only, correctness 11; one token per T1 attempt, OD-5). */
    private val scheduleStub: ScheduleStub? = if (mode == AdmissionMode.ESCROW) ScheduleStub(jdbc) else null

    /** ESCROW: the fast-path adapter over [store] (injected, never constructed by it, M3). */
    val escrowStore: JdbcEscrowAdmission? = escrowInputs?.let { JdbcEscrowAdmission(jdbc, template, store) }

    /** ESCROW: the keeper's use case. */
    val escrowRefill: RefillStorageEscrow? = escrowStore?.let { RefillStorageEscrow(it, checkNotNull(footprint), escrowMetrics) }

    /**
     * Production's construction: `FootprintWiring.admission(store, policy, declarations)`, with the store timed.
     * ESCROW (NON-GATE prototype): the same use case with its escrow part; the fast path is timed as T1 too.
     */
    val admission: StorageAdmission =
        if (escrowStore != null) {
            StorageAdmission(
                TimingAdmissionStore(store),
                policy,
                declarations.enforcement,
                footprint = footprint,
                escrow = EscrowAdmission(TimingEscrowStore(escrowStore, scheduleStub), checkNotNull(escrowInputs), escrowMetrics),
            )
        } else {
            FootprintWiring.admission(TimingAdmissionStore(store), policy, declarations)
        }

    val guarded =
        GuardedStorage(
            admission = admission,
            reservations = reservations,
            ambiguity = ambiguity,
            latch = ambiguity,
            blobs = InstantBlobStore,
            inspection = InstantBlobStore,
            slots = slots,
            breaker = StorageBreaker(),
            node = node,
            transactions = TimingTransactionRunner(SpringTransactionRunner(transactionManager)),
            clock = reservations,
            metrics = metrics,
            // ESCROW: the stub t1_schedule spend after the slot, before T1 (benchmark-only, correctness 11).
            hook = scheduleStub?.let(::ScheduleStubHook) ?: email.testinbox.application.storage.IngestSyncHook.NONE,
        )

    val messages = JdbcMessageRepository(jdbc)

    /** Retention, one inbox per sweep so each call is one hard delete and its latency is one retention sample. */
    val retention =
        ExpireInboxes(
            JdbcInboxRepository(jdbc),
            JdbcExactAddressReservations(jdbc),
            InstantBlobStore,
            SpringTransactionRunner(transactionManager),
            Clock.systemUTC(),
            TestInboxConfig(mailDomain = "bench.test", sweepBatchSize = 1),
        )

    val compactor = CompactStorageLedger(JdbcStorageLedger(jdbc, template))

    /** The statement this mode's T1 runs: production's mode a statement, or production's partitioned one. */
    val statement: String = store.partitionedStatement ?: store.snapshotStatement

    /**
     * The production-faithfulness probe, run before every scenario: one real
     * T1 of this assembly's store over one existing inbox, which reserves
     * nothing. It proves the footprint statement ran (the snapshot carries the
     * footprint figures) and, under `ALL`, that production's footprint policy
     * finds them evaluable, so no event of the scenario fails as an unready
     * deployment instead of being measured.
     */
    fun probe(inbox: SeededInbox): T1Probe {
        val scope = StorageAdmissionScope(setOf(WorkspaceId(inbox.workspaceId)), setOf(InboxId(inbox.inboxId)))
        // Probes run alone, before the scenario: the counter's step is this probe's.
        val partitionedBefore = store.partitionedAdmissions
        val probe =
            store.admit(scope) { snapshot ->
                StorageAdmissionPlan(
                    escrowNotReady = footprint?.unavailability(snapshot.footprint)?.name?.lowercase(),
                    reservations = emptyList(),
                    outcome =
                        T1Probe(
                            mode = mode,
                            storeClass = store.javaClass.name,
                            enforcement = declarations.enforcement,
                            readsFootprint = store.readsFootprint,
                            footprintRead = snapshot.footprint != null,
                            footprintUnavailable = footprint?.unavailability(snapshot.footprint)?.name,
                            cohortRequired = snapshot.footprint?.cohort?.required,
                            snapshotStatementSha256 = sha256(statement),
                            budgetedWorkspaces = snapshot.budgets.size,
                            inactiveWorkspaces = snapshot.inactiveWorkspaces.size,
                            locking = store.locking,
                        ),
                )
            }
        return probe.copy(partitioned = store.partitionedAdmissions == partitionedBefore + 1)
    }

    /**
     * ESCROW: the policy row, as the operator's pool reserve would write it (§4.1), from this
     * assembly's own policy and footprint declarations: `G − H`, rules (G)/(C)/(P)'s figures,
     * the model, `Q_lane` (256 MiB payload, §9.4; footprint 4 × that), `K_max` = 32, and this
     * node as the one ingestion node lanes exist for.
     */
    fun setEscrowPolicy() {
        val inputs = checkNotNull(escrowInputs)
        val fp = checkNotNull(footprint)
        jdbc
            .sql(
                "SELECT storage_escrow_set_policy(:b, :gp, :gf, :hf, :c, :pf, :m, :r, :bs, :den, :omax, :qp, :qf, :k, " +
                    "ARRAY[:node]::text[], interval '5 minutes', make_interval(secs => :age))",
            ).param("b", inputs.binding)
            .param("gp", policy.globalAdmissionCapBytes)
            .param("gf", fp.limits.globalFootprintLimitBytes)
            .param("hf", fp.limits.finalizeBudgetBytes)
            .param("c", fp.limits.capacityBytes)
            .param("pf", fp.limits.probeBudgetBytes)
            .param("m", fp.limits.metadataBudgetBytes)
            .param("r", fp.limits.operationalReserveBytes)
            .param("bs", fp.model.blockSizeBytes)
            .param("den", fp.model.fragmentationDenominator)
            .param("omax", fp.model.objectOverheadMaxBytes)
            .param("qp", ESCROW_Q_LANE_PAYLOAD)
            .param("qf", 4 * ESCROW_Q_LANE_PAYLOAD)
            .param("k", ESCROW_K_MAX)
            .param("node", node.nodeId)
            .param("age", checkNotNull(declarations.filesystem.observationMaxAge).seconds.toDouble())
            .query()
            .singleRow()
    }

    /**
     * ESCROW integrity (G5, reduced to the prototype): E4 (`L_P + Σ spendable lane payload ≤ G − H`)
     * and E1/E2 for every spendable scope row against mode a's limits derived here, never the stamped ones.
     */
    fun escrowAudit(): Map<String, Long> {
        val inputs = checkNotNull(escrowInputs)
        val e4Slack =
            jdbc
                .sql(
                    "SELECT :cap - ((SELECT coalesce(sum(base_bytes),0) FROM workspace_storage_account) + " +
                        "(SELECT coalesce(sum(bytes),0) FROM storage_delta) + (SELECT coalesce(sum(bytes),0) FROM storage_reservation) + " +
                        "(SELECT coalesce(sum(l.payload_avail),0) FROM storage_escrow_lane l, storage_admission_epoch e WHERE e.id = 1 " +
                        "AND l.epoch = e.epoch AND l.generation = e.generation AND l.ready AND l.valid_until > clock_timestamp()))",
                ).param("cap", policy.globalAdmissionCapBytes)
                .query(Long::class.java)
                .single()
        val scopeViolations =
            jdbc
                .sql(
                    """
                    SELECT count(*) FROM storage_escrow_scope s, storage_admission_epoch e
                     WHERE e.id = 1 AND s.kind = 'W' AND s.active AND s.binding = :binding AND s.generation = e.generation
                       AND s.scope_epoch = e.scope_epoch
                       AND coalesce((SELECT base_bytes FROM workspace_storage_account WHERE workspace_id = s.scope_id), 0)
                         + coalesce((SELECT sum(bytes) FROM storage_delta WHERE workspace_id = s.scope_id), 0)
                         + coalesce((SELECT sum(bytes) FROM storage_reservation WHERE workspace_id = s.scope_id), 0)
                         + s.payload_avail
                         > least(:max, coalesce((SELECT payload_limit_bytes FROM participant_workspace WHERE workspace_id = s.scope_id),
                                                (SELECT payload_limit_bytes FROM storage_legacy_share WHERE workspace_id = s.scope_id), :max))
                    """.trimIndent(),
                ).param("binding", inputs.binding)
                .param("max", policy.workspaceLimitBytes)
                .query(Long::class.java)
                .single()
        return linkedMapOf(
            "e4SlackBytes" to e4Slack,
            "workspaceRowsOverLimit" to scopeViolations,
            "violations" to (if (e4Slack < 0) 1 else 0) + scopeViolations,
        )
    }

    /** One production heartbeat of this assembly's node. */
    fun heartbeat() = lifecycle.heartbeat()

    fun stop() {
        lifecycle.stop(slots.poisoned())
    }

    companion object {
        /** `Q_lane` (§9.4's proposed value) and `K_max` (one process plus one stranded generation). */
        const val ESCROW_Q_LANE_PAYLOAD = 256L shl 20
        const val ESCROW_K_MAX = 32

        /**
         * The T1 adapter for [mode], built from [declarations] exactly as
         * `IngestionWiring.storageAdmission` builds production's:
         * `JdbcStorageAdmission(jdbc, template, readsFootprint = FootprintWiring.readsFootprint(declarations),
         * locking = declarations.admissionLocking)`. The reference differs ONLY by the overridden lock step.
         */
        fun admissionStore(
            jdbc: JdbcClient,
            template: TransactionTemplate,
            mode: AdmissionMode,
            referenceMode: ReferenceMode,
            declarations: StorageDeclarations,
            escrow: EscrowSlowPath? = null,
            onFallback: (email.testinbox.application.storage.PartitionFallbackReason) -> Unit = {},
        ): JdbcStorageAdmission {
            val readsFootprint = FootprintWiring.readsFootprint(declarations)
            return when (mode) {
                // NON-GATE prototype: mode a's adapter with the escrow strategy (null under GLOBAL, as everywhere else).
                AdmissionMode.ESCROW -> {
                    JdbcStorageAdmission(
                        jdbc,
                        template,
                        readsFootprint = readsFootprint,
                        locking = declarations.admissionLocking,
                        escrow = checkNotNull(escrow) { "the escrow variant needs its slow-path strategy" },
                    )
                }

                // Both exactly IngestionWiring.storageAdmission's construction: the switch is the
                // declared `admission-locking`, GLOBAL for CHOSEN, COHORT_PARTITIONED for the variant.
                AdmissionMode.CHOSEN, AdmissionMode.PARTITIONED -> {
                    JdbcStorageAdmission(
                        jdbc,
                        template,
                        readsFootprint = readsFootprint,
                        locking = declarations.admissionLocking,
                        onFallback = onFallback,
                    )
                }

                AdmissionMode.REFERENCE -> {
                    if (referenceMode == ReferenceMode.NO_LOCK) {
                        NoLockAdmissionStore(jdbc, template, readsFootprint)
                    } else {
                        JdbcStorageAdmission(jdbc, template, readsFootprint = readsFootprint)
                    }
                }
            }
        }

        fun sha256(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** What the production-faithfulness probe found, recorded per scenario in the evidence. */
data class T1Probe(
    val mode: AdmissionMode,
    val storeClass: String,
    val enforcement: StorageEnforcement,
    val readsFootprint: Boolean,
    val footprintRead: Boolean,
    /** Production's `FootprintPolicy.unavailability` on the probe's snapshot: null means evaluable. */
    val footprintUnavailable: String?,
    val cohortRequired: Boolean?,
    val snapshotStatementSha256: String,
    /** Workspaces of the probed scope with a share (participant or legacy), and inactive ones. */
    val budgetedWorkspaces: Int = 0,
    val inactiveWorkspaces: Int = 0,
    /** The store's declared lock protocol, and whether the probe's T1 was decided on the partitioned path. */
    val locking: AdmissionLocking = AdmissionLocking.GLOBAL,
    val partitioned: Boolean = false,
)
