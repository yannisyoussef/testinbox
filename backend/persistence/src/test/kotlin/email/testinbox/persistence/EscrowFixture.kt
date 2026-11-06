package email.testinbox.persistence

import email.testinbox.application.port.EscalationReason
import email.testinbox.application.port.EscrowAttempt
import email.testinbox.application.port.EscrowInputs
import email.testinbox.application.port.EscrowPath
import email.testinbox.application.port.EscrowPlan
import email.testinbox.application.port.EscrowRefillCause
import email.testinbox.application.port.EscrowRegrantKind
import email.testinbox.application.port.EscrowScope
import email.testinbox.application.port.EscrowScopeRef
import email.testinbox.application.port.EscrowView
import email.testinbox.application.port.StorageEscrowMetrics
import email.testinbox.application.port.StorageEscrowStore
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.FootprintPolicy
import email.testinbox.application.usecase.EscrowAdmission
import email.testinbox.application.usecase.RefillStorageEscrow
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.application.usecase.StorageAdmissionRequest
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.AdmissionFixture.Companion.policy
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The slot-lane escrow PROTOTYPE's fixture (ADR-038 PROPOSED; M1): the closed
 * cohort of [PartitionedAdmissionFixture] (legacy shares, an approved REQUIRED pool,
 * the expected nodes live, trust, a fresh observation) on a throwaway database to
 * which V28-proto is applied after Flyway, exactly as the benchmark harness applies
 * it. V28-proto is never on the migration path.
 */
abstract class EscrowFixture : PartitionedAdmissionFixture() {
    protected val footprintPolicy: FootprintPolicy get() = checkNotNull(EffectiveStoragePolicy.footprint(declarations))

    /** Mode a's policy for these tests; a test may narrow the global ceiling before building a [Stack]. */
    protected var capacity: StorageCapacityPolicy = policy(workspace = 2 * gib, share = "1", global = 10 * gib)

    protected val inputs: EscrowInputs
        get() = EscrowInputs(capacity.workspaceLimitBytes, capacity.inboxShare.value, footprintPolicy.model)

    protected val metrics = RecordingEscrowMetrics()

    @BeforeEach
    fun applyEscrowProto() {
        val file = Path.of(checkNotNull(System.getProperty("testinbox.escrowProtoSql")) { "the build passes the V28-proto path" })
        db.dataSource.connection.use { c -> c.createStatement().use { it.execute(Files.readString(file)) } }
    }

    /** The policy row (operator-written in production; here by the test), for the declared ingestion node. */
    protected fun setPolicy(
        qPayload: Long = 1L shl 50,
        qFootprint: Long = 1L shl 55,
        binding: String = inputs.binding,
    ) {
        val limits = footprintPolicy.limits
        val model = footprintPolicy.model
        db.jdbc
            .sql(
                "SELECT storage_escrow_set_policy(:b, :gp, :gf, :hf, :c, :pf, :m, :r, :bs, :den, :omax, :qp, :qf, 32, " +
                    "ARRAY[:node]::text[], interval '5 minutes', interval '15 minutes')",
            ).param("b", binding)
            .param("gp", capacity.globalAdmissionCapBytes)
            .param("gf", limits.globalFootprintLimitBytes)
            .param("hf", limits.finalizeBudgetBytes)
            .param("c", limits.capacityBytes)
            .param("pf", limits.probeBudgetBytes)
            .param("m", limits.metadataBudgetBytes)
            .param("r", limits.operationalReserveBytes)
            .param("bs", model.blockSizeBytes)
            .param("den", model.fragmentationDenominator)
            .param("omax", model.objectOverheadMaxBytes)
            .param("qp", qPayload)
            .param("qf", qFootprint)
            .param("node", NODE)
            .query()
            .singleRow()
    }

    /** The prototype as the benchmark assembles it: mode a's store with the escrow strategy, the fast-path adapter over it. */
    protected inner class Stack(
        val slow: EscrowSlowPath = EscrowSlowPath(db.jdbc, inputs),
        scopeWait: Duration = JdbcEscrowAdmission.DEFAULT_SCOPE_WAIT,
    ) {
        val t1 = JdbcStorageAdmission(db.jdbc, db.transactions, readsFootprint = true, escrow = slow)
        val store = JdbcEscrowAdmission(db.jdbc, db.transactions, t1, scopeWait)
        val refill = RefillStorageEscrow(store, footprintPolicy, metrics)

        fun admission(escrowStore: StorageEscrowStore = store): StorageAdmission =
            StorageAdmission(
                t1,
                capacity,
                StorageEnforcement.ALL,
                footprint = footprintPolicy,
                escrow = EscrowAdmission(escrowStore, inputs, metrics),
            )
    }

    protected fun request(
        lane: Int,
        vararg copies: Pair<UUID, UUID>,
    ) = StorageAdmissionRequest(COPY, copies.map { fx.candidate(it.first, it.second) }, NODE, AdmissionFixture.GENERATION, lane = lane)

    /** Pauses a fast path inside its transaction, after its lock statement and before its decision. */
    protected class PausedEscrow(
        private val delegate: StorageEscrowStore,
    ) : StorageEscrowStore by delegate {
        val reachedDecision = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun <R : Any> tryAdmit(
            scope: EscrowScope,
            decide: (EscrowView) -> EscrowPlan<R>,
        ): EscrowAttempt<R> =
            delegate.tryAdmit(scope) { view ->
                reachedDecision.countDown()
                check(release.await(60, TimeUnit.SECONDS)) { "never released" }
                decide(view)
            }
    }

    // --- the invariants of §5, from the authoritative rows, never from the grants' stamped limits --------

    /** L_P: mode a's global live payload. */
    protected fun livePayload(): Long =
        db.jdbc
            .sql(
                "SELECT (SELECT coalesce(sum(base_bytes),0) FROM workspace_storage_account) + " +
                    "(SELECT coalesce(sum(bytes),0) FROM storage_delta) + (SELECT coalesce(sum(bytes),0) FROM storage_reservation)",
            ).query(Long::class.java)
            .single()

    /** Σ spendable lane payload grants (current epoch and generation, ready, valid, escrow enabled). */
    protected fun spendableLanePayload(): Long =
        db.jdbc
            .sql(
                "SELECT coalesce(sum(l.payload_avail),0) FROM storage_escrow_lane l, storage_admission_epoch e " +
                    "WHERE e.id = 1 AND l.epoch = e.epoch AND l.generation = e.generation AND l.ready " +
                    "AND l.valid_until > clock_timestamp() AND e.escrow_enabled",
            ).query(Long::class.java)
            .single()

    /**
     * E4 (`L_P + Σ g ≤ G − H`), and E1/E2 for every spendable scope row, against mode
     * a's own limits derived here from [capacity] and the share rows.
     */
    protected fun assertEscrowInvariants(context: String = "") {
        withClue("E4 $context") { (livePayload() + spendableLanePayload()) shouldBeLessThanOrEqual capacity.globalAdmissionCapBytes }
        val rows =
            db.jdbc
                .sql(
                    """
                    SELECT s.kind, s.scope_id, s.payload_avail,
                           CASE WHEN s.kind = 'W' THEN
                                coalesce((SELECT base_bytes FROM workspace_storage_account WHERE workspace_id = s.scope_id), 0)
                              + coalesce((SELECT sum(bytes) FROM storage_delta WHERE workspace_id = s.scope_id), 0)
                              + coalesce((SELECT sum(bytes) FROM storage_reservation WHERE workspace_id = s.scope_id), 0)
                           ELSE
                                coalesce((SELECT base_bytes FROM inbox_storage WHERE inbox_id = s.scope_id), 0)
                              + coalesce((SELECT sum(bytes) FROM storage_delta WHERE inbox_id = s.scope_id), 0)
                              + coalesce((SELECT sum(bytes) FROM storage_reservation WHERE inbox_id = s.scope_id), 0)
                           END AS used,
                           coalesce((SELECT payload_limit_bytes FROM storage_legacy_share WHERE workspace_id =
                                     CASE WHEN s.kind = 'W' THEN s.scope_id
                                          ELSE (SELECT workspace_id FROM inbox WHERE id = s.scope_id) END),
                                    :max) AS share
                      FROM storage_escrow_scope s, storage_admission_epoch e
                     WHERE e.id = 1 AND s.generation = e.generation AND s.binding = :binding AND s.active
                       AND (s.kind = 'I' OR s.scope_epoch = e.scope_epoch)
                    """.trimIndent(),
                ).param("max", capacity.workspaceLimitBytes)
                .param("binding", inputs.binding)
                .query { rs, _ ->
                    Triple(
                        "${rs.getString(1)}:${rs.getObject(2)}",
                        rs.getLong(3) + rs.getLong(4),
                        minOf(capacity.workspaceLimitBytes, rs.getLong(5)),
                    )
                }.list()
        rows.forEach { (row, usedPlusGrant, limit) ->
            // The inbox share is 1 here, so an inbox's limit is its workspace's.
            withClue("E1/E2 $row $context") { usedPlusGrant shouldBeLessThanOrEqual limit }
        }
    }

    protected class RecordingEscrowMetrics : StorageEscrowMetrics {
        val paths = ConcurrentHashMap<EscrowPath, AtomicLong>()
        val escalations = ConcurrentHashMap<EscalationReason, AtomicLong>()
        val refills = ConcurrentHashMap<EscrowRefillCause, AtomicLong>()
        val regrants = ConcurrentHashMap<EscrowRegrantKind, AtomicLong>()

        override fun path(path: EscrowPath) {
            paths.computeIfAbsent(path) { AtomicLong() }.incrementAndGet()
        }

        override fun escalation(reason: EscalationReason) {
            escalations.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet()
        }

        override fun inlineRefill(
            kind: EscrowScopeRef.Kind,
            cause: EscrowRefillCause,
            duration: Duration,
        ) {
            refills.computeIfAbsent(cause) { AtomicLong() }.incrementAndGet()
        }

        override fun regrant(
            kind: EscrowRegrantKind,
            duration: Duration,
            granted: Boolean,
        ) {
            regrants.computeIfAbsent(kind) { AtomicLong() }.incrementAndGet()
        }

        fun taken(p: EscrowPath): Long = paths[p]?.get() ?: 0

        fun escalated(r: EscalationReason): Long = escalations[r]?.get() ?: 0
    }

    companion object {
        /** The declared ingestion node of [PartitionedAdmissionFixture]: its lanes are the ones granted. */
        const val NODE = "smtp"
    }
}
