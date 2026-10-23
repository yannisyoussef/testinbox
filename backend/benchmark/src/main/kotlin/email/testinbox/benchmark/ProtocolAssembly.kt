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
import email.testinbox.application.storage.GuardedStorage
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageNode
import email.testinbox.application.storage.StorageNodeLifecycle
import email.testinbox.application.storage.StorageProtocol
import email.testinbox.application.storage.WriteSlots
import email.testinbox.application.usecase.CompactStorageLedger
import email.testinbox.application.usecase.ExpireInboxes
import email.testinbox.application.usecase.StorageAdmission
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
 * sums included) and the reservation insert are untouched. It is a
 * measurement mutant: it lives only here, never in a deployable, and the
 * architecture suite names `ProtocolAssembly` as the one exemption allowed to
 * construct the protocol outside the ingestion wiring. It is the baseline,
 * never the implementation under test.
 */
class NoLockAdmissionStore(
    jdbc: JdbcClient,
    transactions: TransactionTemplate,
) : JdbcStorageAdmission(jdbc, transactions) {
    override fun acquireAdmissionLock() = Unit
}

/**
 * The real protocol objects, assembled by hand over one pool, the way
 * `GuardedIngestHarness` assembles them for the §17 scenarios:
 * `StorageAdmission` over `JdbcStorageAdmission`, `GuardedStorage` with the
 * real `JdbcStorageReservations` fence, `JdbcStorageAmbiguity` (also the
 * latch), `WriteSlots`, `StorageBreaker`, `SpringTransactionRunner`, and
 * `ExpireInboxes` over `JdbcInboxRepository` for retention. Only the upload
 * step is a stub ([InstantBlobStore]).
 */
class ProtocolAssembly(
    dataSource: DataSource,
    private val options: BenchmarkOptions,
    mode: AdmissionMode,
) {
    val jdbc: JdbcClient = JdbcClient.create(dataSource)
    private val transactionManager = DataSourceTransactionManager(dataSource)
    private val template = TransactionTemplate(transactionManager)
    val metrics = RecordingProtocolMetrics()
    val node = StorageNode("bench-${ProcessHandle.current().pid()}", UUID.randomUUID())

    private val reservations = JdbcStorageReservations(jdbc, template)
    private val ambiguity = JdbcStorageAmbiguity(jdbc, template)
    private val lifecycle = StorageNodeLifecycle(ambiguity, node, options.slots).also { it.start() }
    val slots = WriteSlots(options.slots, options.slotsPerWorkspace, StorageProtocol.SLOT_WAIT) { ambiguity.unresolvedFor(node.nodeId) }

    private val referenceMode: ReferenceMode? = if (mode == AdmissionMode.REFERENCE) options.referenceMode else null
    private val store: StorageAdmissionStore =
        if (referenceMode == ReferenceMode.NO_LOCK) NoLockAdmissionStore(jdbc, template) else JdbcStorageAdmission(jdbc, template)

    // Generous: nothing is refused, so every T1 inserts and every event reaches T2. The lock and the rows are what is measured.
    private val policy =
        when (mode) {
            AdmissionMode.CHOSEN -> StorageCapacityPolicy(1L shl 40, InboxShare.of("1"), 1L shl 50, 0)

            // No global ceiling: an effectively unlimited G.
            AdmissionMode.REFERENCE -> StorageCapacityPolicy(1L shl 40, InboxShare.of("1"), Long.MAX_VALUE, 0)
        }
    private val enforcement =
        when (referenceMode) {
            null -> StorageEnforcement.ALL

            // Mode c: the tenant ceilings only, no global one, and no global lock (NoLockAdmissionStore).
            ReferenceMode.NO_LOCK -> StorageEnforcement.TENANT_LIMITS

            // Same lock, every ceiling observational: a diagnostic self-comparison.
            ReferenceMode.CEILING_OFF -> StorageEnforcement.OFF
        }

    val guarded =
        GuardedStorage(
            admission = StorageAdmission(TimingAdmissionStore(store), policy, enforcement),
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

    fun stop() {
        lifecycle.stop(slots.poisoned())
    }
}
