package email.testinbox.benchmark

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.BlobStore
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
import email.testinbox.application.usecase.ExpireInboxes
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
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
        try {
            return delegate.admit(scope, decide)
        } finally {
            val end = System.nanoTime()
            EventTimings.current.get()?.let {
                it.t1Nanos = end - started
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
) {
    val jdbc: JdbcClient = JdbcClient.create(dataSource)
    private val transactionManager = DataSourceTransactionManager(dataSource)
    private val template = TransactionTemplate(transactionManager)
    val metrics = RecordingProtocolMetrics()
    val node = StorageNode("bench-${ProcessHandle.current().pid()}", UUID.randomUUID())

    /** Production's footprint policy for these declarations (null only if no filesystem were declared). */
    val footprint = EffectiveStoragePolicy.footprint(declarations)

    private val reservations = JdbcStorageReservations(jdbc, template)
    private val ambiguity = JdbcStorageAmbiguity(jdbc, template, declarations.enforcement, footprint?.cohort?.binding)
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

    /** The T1 adapter this mode runs: exactly production's for CHOSEN (see [admissionStore]). */
    val store: JdbcStorageAdmission = admissionStore(jdbc, template, mode, options.referenceMode, declarations)

    // Generous: nothing is refused, so every T1 inserts and every event reaches T2. The lock and the rows are what is measured.
    private val policy =
        when (mode) {
            AdmissionMode.CHOSEN, AdmissionMode.PARTITIONED -> StorageCapacityPolicy(1L shl 40, InboxShare.of("1"), 1L shl 50, 0)

            // No global ceiling: an effectively unlimited G.
            AdmissionMode.REFERENCE -> StorageCapacityPolicy(1L shl 40, InboxShare.of("1"), Long.MAX_VALUE, 0)
        }

    /** Production's construction: `FootprintWiring.admission(store, policy, declarations)`, with the store timed. */
    val admission: StorageAdmission = FootprintWiring.admission(TimingAdmissionStore(store), policy, declarations)

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

    /** The statement this mode's T1 runs: production's, or the variant's. */
    val statement: String = (store as? PartitionedAdmissionStore)?.partitionedStatement ?: store.snapshotStatement

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
        return store.admit(scope) { snapshot ->
            StorageAdmissionPlan(
                emptyList(),
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
                ),
            )
        }
    }

    fun stop() {
        lifecycle.stop(slots.poisoned())
    }

    companion object {
        /**
         * The T1 adapter for [mode], built from [declarations] exactly as
         * `IngestionWiring.storageAdmission` builds production's:
         * `JdbcStorageAdmission(jdbc, template, readsFootprint = FootprintWiring.readsFootprint(declarations))`.
         * The reference differs ONLY by the overridden lock step.
         */
        fun admissionStore(
            jdbc: JdbcClient,
            template: TransactionTemplate,
            mode: AdmissionMode,
            referenceMode: ReferenceMode,
            declarations: StorageDeclarations,
        ): JdbcStorageAdmission {
            val readsFootprint = FootprintWiring.readsFootprint(declarations)
            return when (mode) {
                AdmissionMode.CHOSEN -> {
                    JdbcStorageAdmission(jdbc, template, readsFootprint = readsFootprint)
                }

                // Benchmark-only (never a production switch): W1's share-partitioned T1.
                AdmissionMode.PARTITIONED -> {
                    PartitionedAdmissionStore(jdbc, template)
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
)
