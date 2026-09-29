package email.testinbox.persistence

import email.testinbox.application.port.InboxStorageUsage
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageUsage
import email.testinbox.persistence.AdmissionFixture.Companion.policy
import email.testinbox.persistence.AdmissionFixture.Companion.shape
import email.testinbox.persistence.AdmissionScenarios.F
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-035 §4 "Why one statement", and §17 tests 5 and 12.
 *
 * Two atomic moves shift bytes between the three sums while T1 may be
 * reading: T2 moves a reservation into the ledger (reservation → delta), and
 * the compactor folds deltas into base rows (delta → base). A read split
 * across statements can see a move's source already gone and its destination
 * not yet counted, and under-count. The real adapter's single statement
 * cannot.
 *
 * The split read here is a test-only mutant. Each move is committed by a
 * separate session at a controlled point between its statements; no timing
 * decides it.
 */
class StorageAdmissionSnapshotTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var fx: AdmissionFixture

    @BeforeEach
    fun database() {
        fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
    }

    /**
     * The mutant: base, then deltas, then reservations, as three statements,
     * with [afterBase] and [afterDelta] run between them.
     */
    private class SplitReadAdmission(
        private val jdbc: JdbcClient,
        fx: AdmissionFixture,
        private val afterBase: () -> Unit = {},
        private val afterDelta: () -> Unit = {},
    ) : JdbcStorageAdmission(fx.db.jdbc, fx.db.transactions) {
        override fun readSnapshot(scope: StorageAdmissionScope): StorageUsageSnapshot {
            val ws = scope.workspaceIds.map { it.value }
            val ib = scope.inboxIds.map { it.value }
            val base =
                sums(
                    "SELECT 'W', workspace_id, base_bytes FROM workspace_storage_account UNION ALL SELECT 'I', inbox_id, base_bytes FROM inbox_storage",
                )
            afterBase()
            val delta =
                sums(
                    "SELECT 'W', workspace_id, sum(bytes) FROM storage_delta GROUP BY workspace_id UNION ALL SELECT 'I', inbox_id, sum(bytes) FROM storage_delta WHERE inbox_id IS NOT NULL GROUP BY inbox_id",
                )
            afterDelta()
            val reserved =
                sums(
                    "SELECT 'W', workspace_id, sum(bytes) FROM storage_reservation GROUP BY workspace_id UNION ALL SELECT 'I', inbox_id, sum(bytes) FROM storage_reservation GROUP BY inbox_id",
                )

            fun committed(key: Pair<String, UUID>) = (base[key] ?: 0) + (delta[key] ?: 0)

            fun total(map: Map<Pair<String, UUID>, Long>) = map.filterKeys { it.first == "W" }.values.sum()
            val owners =
                ib.associateWith { id ->
                    jdbc
                        .sql("SELECT workspace_id FROM inbox WHERE id = ?")
                        .param(id)
                        .query(UUID::class.java)
                        .optional()
                        .orElse(null)
                }
            return StorageUsageSnapshot(
                t0 = Instant.now(),
                global = StorageUsage(total(base) + total(delta), total(reserved)),
                workspaces = ws.associate { WorkspaceId(it) to StorageUsage(committed("W" to it), reserved["W" to it] ?: 0) },
                inboxes =
                    ib.associate {
                        InboxId(it) to
                            InboxStorageUsage(owners[it]?.let(::WorkspaceId), StorageUsage(committed("I" to it), reserved["I" to it] ?: 0))
                    },
            )
        }

        private fun sums(sql: String): Map<Pair<String, UUID>, Long> =
            jdbc
                .sql(sql)
                .query { rs, _ -> (rs.getString(1) to rs.getObject(2, UUID::class.java)) to rs.getLong(3) }
                .list()
                .toMap()
    }

    /** Runs [then] after the real single-statement read, inside T1. */
    private class AfterRead(
        private val delegate: StorageAdmissionStore,
        private val then: () -> Unit,
    ) : StorageAdmissionStore {
        override fun <R : Any> admit(
            scope: StorageAdmissionScope,
            decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
        ): R =
            delegate.admit(scope) {
                then()
                decide(it)
            }
    }

    /** Inbox limit 100. The inbox holds 45 in base plus 50 elsewhere, so a copy of 10 does not fit. */
    private val policy = policy(workspace = 1_000, share = "0.1", global = 1_000_000)

    private fun elsewhere(block: () -> Unit) {
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit(block).get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    /** Commits what T2 does to one reservation: the reservation goes, and its message's bytes become a delta. */
    private fun t2Moves(
        reservation: UUID,
        ws: UUID,
        inbox: UUID,
    ) = elsewhere {
        fx.db.openTransaction().use { c ->
            c.prepareStatement("DELETE FROM storage_reservation WHERE message_id = ?").use {
                it.setObject(1, reservation)
                it.executeUpdate() shouldBe 1
            }
            c.prepareStatement("INSERT INTO storage_delta (workspace_id, inbox_id, bytes) VALUES (?, ?, 50)").use {
                it.setObject(1, ws)
                it.setObject(2, inbox)
                it.executeUpdate()
            }
            c.commit()
        }
    }

    private fun compactorFolds() =
        elsewhere {
            fx.db.ledger
                .compact(batch = 1_000)
                .foldedRows shouldBe 1
        }

    // --- T2: reservation → delta ------------------------------------------------------------------------

    @Test
    fun `a split read under-counts a reservation T2 moves into the ledger between its statements, and over-admits`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        fx.base(ws, inbox, 45)
        val reservation = fx.reservation(ws, inbox, 50)
        val mutant = SplitReadAdmission(db.jdbc, fx, afterDelta = { t2Moves(reservation, ws, inbox) })

        fx.admission(policy, StorageEnforcement.ON, mutant).admit(fx.request(F, listOf(fx.candidate(ws, inbox)))).shape() shouldBe
            listOf("admitted")

        fx.used(fx.snapshot(setOf(ws), setOf(inbox)), ws, inbox).third shouldBe 105 // over the inbox limit of 100
    }

    @Test
    fun `the single statement sees a T2 move wholly before or wholly after, and refuses either way`() {
        for (moveAfterRead in listOf(false, true)) {
            val fresh = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
            val db = fresh.db
            val ws = db.workspace()
            val inbox = db.inbox(ws)
            fresh.base(ws, inbox, 45)
            val reservation = fresh.reservation(ws, inbox, 50)
            fx = fresh
            val store = if (moveAfterRead) AfterRead(fresh.store()) { t2Moves(reservation, ws, inbox) } else fresh.store()
            if (!moveAfterRead) t2Moves(reservation, ws, inbox)

            fresh
                .admission(
                    policy,
                    StorageEnforcement.ON,
                    store,
                ).admit(fresh.request(F, listOf(fresh.candidate(ws, inbox))))
                .shape() shouldBe
                listOf("INBOX_LIMIT")
        }
    }

    // --- the compactor: delta → base ----------------------------------------------------------------------

    @Test
    fun `a split read under-counts a delta the compactor folds between its statements, and over-admits`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        fx.base(ws, inbox, 45)
        fx.delta(ws, inbox, 50)
        val mutant = SplitReadAdmission(db.jdbc, fx, afterBase = { compactorFolds() })

        fx.admission(policy, StorageEnforcement.ON, mutant).admit(fx.request(F, listOf(fx.candidate(ws, inbox)))).shape() shouldBe
            listOf("admitted")

        fx.used(fx.snapshot(setOf(ws), setOf(inbox)), ws, inbox).third shouldBe 105
    }

    @Test
    fun `the single statement sees a compaction wholly before or wholly after, and refuses either way`() {
        for (foldAfterRead in listOf(false, true)) {
            val fresh = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
            val db = fresh.db
            val ws = db.workspace()
            val inbox = db.inbox(ws)
            fresh.base(ws, inbox, 45)
            fresh.delta(ws, inbox, 50)
            fx = fresh
            val store = if (foldAfterRead) AfterRead(fresh.store()) { compactorFolds() } else fresh.store()
            if (!foldAfterRead) compactorFolds()

            fresh
                .admission(
                    policy,
                    StorageEnforcement.ON,
                    store,
                ).admit(fresh.request(F, listOf(fresh.candidate(ws, inbox))))
                .shape() shouldBe
                listOf("INBOX_LIMIT")
        }
    }
}
