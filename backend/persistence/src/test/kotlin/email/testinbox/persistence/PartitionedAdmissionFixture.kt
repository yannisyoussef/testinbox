package email.testinbox.persistence

import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.storage.AdmissionLocking
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.FilesystemDeclarations
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.storage.StorageFootprintUnavailableException
import email.testinbox.application.usecase.ReserveCohortStorage
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.application.usecase.StorageAdmissionResult
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.AdmissionFixture.Companion.policy
import email.testinbox.persistence.AdmissionFixture.Companion.shape
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.SQLException
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

/**
 * The closed-cohort fixture and the race helpers of the share-partitioned T1
 * tests ([PartitionedAdmissionTest], [PartitionedAdmissionRegressionTest]):
 * legacy-share workspaces whose pool production's `ReserveCohortStorage`
 * approves, the expected nodes live, trust and a fresh observation.
 */
abstract class PartitionedAdmissionFixture : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    protected lateinit var db: LedgerTestDatabase
    protected lateinit var fx: AdmissionFixture
    protected val gib = 1L shl 30

    /** Each shared workspace's payload share: ten copies of [COPY] bytes. */
    protected val payloadShare = 10 * COPY
    protected val footprintShare = 1L shl 20

    protected val declarations =
        StorageDeclarations(
            enforcement = StorageEnforcement.ALL,
            globalLimitBytes = 10 * gib,
            declaredBucketQuotaBytes = 20 * gib,
            declaredMaxIngestionProcesses = 1,
            inboxShare = "1",
            expectedApiNodes = setOf("api"),
            expectedIngestionNodes = setOf("smtp"),
            filesystem =
                FilesystemDeclarations(
                    4096,
                    32768,
                    16 * gib,
                    2 * gib,
                    gib,
                    2 * gib,
                    32 * gib,
                    8_000_000,
                    Duration.ofMinutes(15),
                    256L shl 20,
                    PersistenceIntegrationTest.postgres.username,
                ),
            admissionLocking = AdmissionLocking.COHORT_PARTITIONED,
        )
    protected val tenantPolicy: StorageCapacityPolicy = policy(workspace = 2 * gib, share = "1", global = 10 * gib)

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
        fx = AdmissionFixture(db)
    }

    // --- the closed cohort -----------------------------------------------------------------------

    protected class Cohort(
        val workspaces: List<UUID>,
        val inboxes: Map<UUID, List<UUID>>,
    )

    protected val tx =
        object : TransactionRunner {
            override fun <T> required(block: () -> T): T = checkNotNull(db.transactions.execute { block() })
        }

    protected fun observation() {
        val order = db.beginObservation()
        db.jdbc
            .sql(
                """INSERT INTO storage_filesystem_observation(started_seq,source,block_size_bytes,capacity_bytes,used_bytes,avail_bytes,inodes_total,inodes_used,trash_bytes,minio_sys_bytes)
            VALUES (?,'partitioned-test',4096,?,0,?,8000000,0,0,0)""",
            ).params(order, 32 * gib, 32 * gib)
            .update()
    }

    /** [n] workspaces, every one with a legacy share, the pool reserved and approved by production's use case. */
    protected fun closedCohort(
        n: Int = 3,
        ids: List<UUID>? = null,
    ): Cohort {
        val workspaces = ids?.map(::workspaceWithId) ?: List(n) { db.workspace() }
        val inboxes = workspaces.associateWith { ws -> List(3) { db.inbox(ws) } }
        cohortInfrastructure()
        reserve(workspaces.associate { WorkspaceId(it) to LegacyStorageShare(payloadShare, footprintShare) })
        return Cohort(workspaces, inboxes)
    }

    /** The expected nodes live with the pool's binding, a trusted ledger and a fresh observation. */
    protected fun cohortInfrastructure() {
        val binding = checkNotNull(EffectiveStoragePolicy.footprint(declarations)?.cohort?.binding)
        for (node in listOf("api", "smtp")) {
            db.jdbc
                .sql(
                    "INSERT INTO storage_node(node_id,generation,capability,heartbeat_at,clean_shutdown," +
                        "cohort_capability,cohort_mode,cohort_binding) VALUES (?,?,'storage-guarded-v1',now(),false,'cohort-storage-v1','ALL',?)",
                ).params(node, UUID.randomUUID(), binding)
                .update()
        }
        db.ledger.confirmTrust() shouldBe true
        observation()
    }

    /** A workspace with a chosen id, as `LedgerTestDatabase.workspace()` makes one. */
    protected fun workspaceWithId(id: UUID): UUID {
        db.jdbc
            .sql("INSERT INTO workspace (id, name, created_at) VALUES (?, 'w', now())")
            .param(id)
            .update()
        db.jdbc
            .sql("INSERT INTO project (id, workspace_id, name, created_at) VALUES (?, ?, 'p', now())")
            .params(id, id)
            .update()
        return id
    }

    protected fun withinBound(): Boolean =
        db.jdbc
            .sql("SELECT storage_partition_within_bound()")
            .query(Boolean::class.java)
            .single()

    /** What the recorded bound leaves above 30 participant places and every legacy share. */
    protected fun headroom(): Long {
        val (payloadBound, _) = legacyBound()
        val legacy =
            db.jdbc
                .sql("SELECT coalesce(sum(payload_limit_bytes),0) FROM storage_legacy_share")
                .query(Long::class.java)
                .single()
        return payloadBound - 30L * 134217728L - legacy
    }

    protected fun reserve(legacy: Map<WorkspaceId, LegacyStorageShare>) =
        ReserveCohortStorage(JdbcCohortBudgets(db.jdbc), tx, JdbcFootprintGate(db.jdbc), declarations).execute(legacy, gib, gib)

    /** Every fallback reason the stores built by [partitioned] reported, in order. */
    protected val fallbackReasons: MutableList<email.testinbox.application.storage.PartitionFallbackReason> =
        Collections.synchronizedList(mutableListOf())

    protected fun partitioned(lockTimeout: Duration = JdbcStorageAdmission.DEFAULT_LOCK_TIMEOUT) =
        JdbcStorageAdmission(
            db.jdbc,
            db.transactions,
            lockTimeout,
            readsFootprint = true,
            locking = AdmissionLocking.COHORT_PARTITIONED,
            onFallback = { fallbackReasons += it },
        )

    protected fun modeA() = JdbcStorageAdmission(db.jdbc, db.transactions, readsFootprint = true)

    protected fun admission(store: StorageAdmissionStore) =
        StorageAdmission(store, tenantPolicy, StorageEnforcement.ALL, footprint = EffectiveStoragePolicy.footprint(declarations))

    protected fun admit(
        store: StorageAdmissionStore,
        vararg copies: Pair<UUID, UUID>,
    ): StorageAdmissionResult = admission(store).admit(fx.request(COPY, copies.map { fx.candidate(it.first, it.second) }))

    // --- concurrency helpers ---------------------------------------------------------------------

    /** Pauses inside T1, holding whatever that T1 holds, after its read and before its decision. */
    protected class Paused(
        private val delegate: StorageAdmissionStore,
    ) : StorageAdmissionStore {
        val reachedDecision = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun <R : Any> admit(
            scope: StorageAdmissionScope,
            decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
        ): R =
            delegate.admit(scope) { snapshot ->
                reachedDecision.countDown()
                check(release.await(60, TimeUnit.SECONDS)) { "never released" }
                decide(snapshot)
            }
    }

    protected fun awaitBlockedOrDone(future: Future<*>): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (future.isDone) return false
            val blocked =
                db.jdbc
                    .sql(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0",
                    ).query(Int::class.java)
                    .single()
            if (blocked > 0) return true
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        }
        error("the second operation neither blocked nor finished")
    }

    protected data class Race<T>(
        val first: StorageAdmissionResult,
        val second: T,
        val secondWasBlocked: Boolean,
    )

    /** A T1 on [first] pauses inside its transaction; [second] then runs; only after it blocked or finished does the T1 continue. */
    protected fun <T> race(
        store: StorageAdmissionStore,
        first: List<Pair<UUID, UUID>>,
        second: () -> T,
    ): Race<T> {
        val paused = Paused(store)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<StorageAdmissionResult> { admit(paused, *first.toTypedArray()) }
            check(paused.reachedDecision.await(30, TimeUnit.SECONDS)) { "the first T1 never reached its decision" }
            val b = pool.submit<T> { second() }
            val blocked = awaitBlockedOrDone(b)
            paused.release.countDown()
            return Race(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS), blocked)
        } finally {
            pool.shutdownNow()
        }
    }

    protected fun legacyBound(): Pair<Long, Long> =
        db.jdbc
            .sql("SELECT payload_capacity_bytes, footprint_capacity_bytes FROM storage_partition_bound")
            .query { rs, _ -> rs.getLong(1) to rs.getLong(2) }
            .single()

    companion object {
        const val COPY = 100L
    }
}
