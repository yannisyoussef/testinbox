package email.testinbox.persistence

import email.testinbox.application.port.StorageAccountingOverflowException
import email.testinbox.application.port.StorageAdmissionInputException
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageAdmissionUnavailableException
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.application.usecase.StorageAdmissionResult
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.AdmissionFixture.Companion.policy
import email.testinbox.persistence.AdmissionFixture.Companion.shape
import email.testinbox.persistence.AdmissionScenarios.F
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-035 T1 as a transaction: its settings, its lock, its single read, its
 * atomic insert, its clock, and how it fails.
 */
class StorageAdmissionTransactionTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var fx: AdmissionFixture

    @BeforeEach
    fun database() {
        fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
    }

    /** Runs [probe] inside T1, between the snapshot read and the insert, on T1's own connection. */
    private class InsideT1(
        private val delegate: StorageAdmissionStore,
        private val probe: (StorageUsageSnapshot) -> Unit,
    ) : StorageAdmissionStore {
        override fun <R : Any> admit(
            scope: StorageAdmissionScope,
            decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
        ): R =
            delegate.admit(scope) { snapshot ->
                probe(snapshot)
                decide(snapshot)
            }
    }

    private fun one(sql: String): String =
        fx.db.jdbc
            .sql(sql)
            .query(String::class.java)
            .single()

    // --- F. atomicity -----------------------------------------------------------------------

    @Test
    fun `one failing reservation insert rolls back the whole event, so 50 admitted copies commit as 50 or 0`() {
        val db = fx.db
        val ws = db.workspace()
        val candidates = List(50) { fx.candidate(ws, db.inbox(ws)) }
        // The 30th copy's message id is already reserved (the ADR-026 duplicate
        // that T2 would catch later): its insert violates the primary key.
        db.jdbc
            .sql(
                """
                INSERT INTO storage_reservation (message_id, workspace_id, inbox_id, object_keys, bytes, state,
                                                 created_at, write_deadline_at, node_id, generation)
                VALUES (?, ?, ?, ARRAY['k'], 1, 'RESERVED', now(), now() + interval '2 minutes', 'other', gen_random_uuid())
                """.trimIndent(),
            ).params(candidates[29].messageId.value, ws, candidates[29].inboxId.value)
            .update()

        shouldThrow<DuplicateKeyException> {
            fx.admission(StorageCapacityPolicyFixtures.GENEROUS).admit(fx.request(F, candidates))
        }

        fx.reservationCount() shouldBe 1 // only the pre-existing row: none of the 49 others committed
    }

    // --- G. empty ------------------------------------------------------------------------------

    @Test
    fun `an event with no candidate takes no lock, reads nothing and writes nothing`() {
        val recording = RecordingDataSource(fx.db.dataSource)
        val admission =
            StorageAdmission(
                JdbcStorageAdmission(recording.jdbc, recording.transactions),
                StorageCapacityPolicyFixtures.GENEROUS,
                StorageEnforcement.ALL,
            )

        admission.admit(fx.request(F, emptyList())) shouldBe StorageAdmissionResult.NOTHING_TO_ADMIT

        recording.statements.shouldBeEmpty()
    }

    // --- one statement (ADR-035 §17 test 12) ---------------------------------------------------------------

    @Test
    fun `T1 issues exactly one accounting read, however many candidates the event has`() {
        val db = fx.db
        val workspaces = List(10) { db.workspace() }
        for (candidates in listOf(1, 10, 50)) {
            val recording = RecordingDataSource(db.dataSource)
            val admission =
                StorageAdmission(
                    JdbcStorageAdmission(recording.jdbc, recording.transactions),
                    StorageCapacityPolicyFixtures.GENEROUS,
                    StorageEnforcement.ALL,
                )
            val event = List(candidates) { i -> workspaces[i % 10].let { fx.candidate(it, db.inbox(it)) } }

            admission.admit(fx.request(F, event)).admitted.size shouldBe candidates

            val statements = recording.statements.map { it.trim().lowercase() }
            statements.count { "storage_delta" in it || "workspace_storage_account" in it || "sum(" in it } shouldBe 1
            // isolation; two SET LOCAL batches (commit + lock timeout, statement + idle timeout); lock; read; insert
            statements.size shouldBe 6
            statements[3] shouldStartWith "select pg_advisory_xact_lock"
            statements[5] shouldStartWith "insert into storage_reservation"
        }
    }

    @Test
    fun `an event whose copies are all refused reads once and inserts nothing`() {
        val db = fx.db
        val ws = db.workspace()
        val recording = RecordingDataSource(db.dataSource)
        val admission =
            StorageAdmission(
                JdbcStorageAdmission(recording.jdbc, recording.transactions),
                policy(workspace = 5, global = 1_000),
                StorageEnforcement.ALL,
            )

        admission.admit(fx.request(F, listOf(fx.candidate(ws, db.inbox(ws))))).shape() shouldBe listOf("INBOX_LIMIT")

        recording.statements.size shouldBe 5
        recording.statements.none { it.trim().lowercase().startsWith("insert") } shouldBe true
    }

    // --- H. database clock ---------------------------------------------------------------------------

    @Test
    fun `created_at is T1's database t0 and the deadline is exactly t0 plus 120 s`() {
        val db = fx.db
        val ws = db.workspace()
        var transactionNow: OffsetDateTime? = null
        val store =
            InsideT1(fx.store()) {
                transactionNow =
                    db.jdbc
                        .sql("SELECT now()")
                        .query(OffsetDateTime::class.java)
                        .single()
            }

        val result =
            fx
                .admission(
                    StorageCapacityPolicyFixtures.GENEROUS,
                    store = store,
                ).admit(fx.request(F, listOf(fx.candidate(ws, db.inbox(ws)))))

        result.t0 shouldBe transactionNow!!.toInstant()
        val (createdAt, window) =
            db.jdbc
                .sql("SELECT created_at, extract(epoch FROM write_deadline_at - created_at) AS w FROM storage_reservation")
                .query { rs, _ -> rs.getObject("created_at", OffsetDateTime::class.java).toInstant() to rs.getBigDecimal("w") }
                .single()
        createdAt shouldBe result.t0
        window.compareTo(java.math.BigDecimal(120)) shouldBe 0
        // The use case has no clock to consult: StorageAdmission takes none.
        StorageAdmission::class.java.declaredFields.none { java.time.Clock::class.java.isAssignableFrom(it.type) } shouldBe true
    }

    // --- I. transaction -------------------------------------------------------------------------------

    @Test
    fun `T1 states its own isolation, synchronous_commit and lock timeout, and holds the (35, 1) lock`() {
        val db = fx.db
        // Hostile database defaults: T1 must not inherit any of them.
        listOf(
            "ALTER DATABASE ${db.name} SET synchronous_commit = off",
            "ALTER DATABASE ${db.name} SET default_transaction_isolation = 'serializable'",
            "ALTER DATABASE ${db.name} SET lock_timeout = 0",
        ).forEach { db.jdbc.sql(it).update() }
        one("SHOW synchronous_commit") shouldBe "off" // a new connection sees the hostile default
        val ws = db.workspace()
        val seen = mutableMapOf<String, String>()
        val store =
            InsideT1(fx.store()) {
                seen["isolation"] = one("SHOW transaction_isolation")
                seen["synchronous_commit"] = one("SHOW synchronous_commit")
                seen["lock_timeout"] = one("SHOW lock_timeout")
                seen["statement_timeout"] = one("SHOW statement_timeout")
                seen["idle_in_transaction_session_timeout"] = one("SHOW idle_in_transaction_session_timeout")
                seen["admission lock"] =
                    one(
                        """
                        SELECT count(*)::text FROM pg_locks
                         WHERE locktype = 'advisory' AND classid = 35 AND objid = 1 AND objsubid = 2
                           AND granted AND pid = pg_backend_pid()
                        """.trimIndent(),
                    )
                seen["ledger lock"] =
                    one(
                        "SELECT count(*)::text FROM pg_locks WHERE locktype = 'advisory' AND classid = 35 AND objid = 2 AND pid = pg_backend_pid()",
                    )
            }

        fx.admission(StorageCapacityPolicyFixtures.GENEROUS, store = store).admit(fx.request(F, listOf(fx.candidate(ws, db.inbox(ws)))))

        seen shouldBe
            mapOf(
                "isolation" to "read committed",
                "synchronous_commit" to "on",
                "lock_timeout" to "5s",
                "statement_timeout" to "10s",
                "idle_in_transaction_session_timeout" to "10s",
                "admission lock" to "1",
                "ledger lock" to "0",
            )
        fx.reservationCount() shouldBe 1
    }

    @Test
    fun `a held admission lock times T1 out as unavailable, never as SERVICE_CAPACITY, and reserves nothing`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val holder = db.openTransaction()
        holder.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(35, 1)") }
        try {
            val started = System.nanoTime()
            val failure =
                shouldThrow<StorageAdmissionUnavailableException> {
                    fx
                        .admission(StorageCapacityPolicyFixtures.GENEROUS, store = fx.store(lockTimeout = Duration.ofMillis(300)))
                        .admit(fx.request(F, listOf(fx.candidate(ws, inbox))))
                }
            (System.nanoTime() - started < Duration.ofSeconds(4).toNanos()) shouldBe true
            generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<java.sql.SQLException>()
                .map { it.sqlState }
                .toList() shouldContainExactly listOf("55P03")
        } finally {
            holder.rollback()
            holder.close()
        }
        fx.reservationCount() shouldBe 0
        // With the lock free, the same request is admitted.
        fx
            .admission(StorageCapacityPolicyFixtures.GENEROUS)
            .admit(fx.request(F, listOf(fx.candidate(ws, inbox))))
            .admitted.size shouldBe 1
    }

    @Test
    fun `the production lock timeout is 5 s, and no timeout can render as '0ms', which means none`() {
        JdbcStorageAdmission.DEFAULT_LOCK_TIMEOUT shouldBe Duration.ofSeconds(5)
        shouldThrow<IllegalArgumentException> { fx.store(lockTimeout = Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { fx.store(lockTimeout = Duration.ofNanos(500_000)) }
    }

    @Test
    fun `T1 refuses to join a caller's transaction, and reserves nothing`() {
        // Joined, it would hold (35, 1) until the caller commits, and its
        // reservations would stay uncommitted while objects are written.
        val db = fx.db
        val ws = db.workspace()
        val admission = fx.admission(StorageCapacityPolicyFixtures.GENEROUS)

        shouldThrow<IllegalStateException> {
            db.transactions.execute {
                db.jdbc
                    .sql("SELECT 1")
                    .query(Int::class.java)
                    .single()
                admission.admit(fx.request(F, listOf(fx.candidate(ws, db.inbox(ws)))))
            }
        }

        fx.reservationCount() shouldBe 0
    }

    @Test
    fun `stored usage beyond 64 bits fails closed as an accounting overflow, not as capacity`() {
        val db = fx.db
        val a = db.workspace()
        val b = db.workspace()
        fx.workspaceBase(a, Long.MAX_VALUE / 2 + 1)
        fx.workspaceBase(b, Long.MAX_VALUE / 2 + 1) // the global sum no longer fits a Long

        shouldThrow<StorageAccountingOverflowException> {
            fx.admission(StorageCapacityPolicyFixtures.GENEROUS).admit(fx.request(F, listOf(fx.candidate(a, db.inbox(a)))))
        }
        fx.reservationCount() shouldBe 0
    }

    // --- L. consistency ----------------------------------------------------------------------------

    @Test
    fun `an inbox that does not exist, was deleted, or belongs to another workspace fails T1 closed`() {
        val db = fx.db
        val ws = db.workspace()
        val other = db.workspace()
        val deleted = db.inbox(ws).also(db::hardDeleteInbox)
        val admission = fx.admission(StorageCapacityPolicyFixtures.GENEROUS)
        val valid = fx.candidate(ws, db.inbox(ws))

        for (bad in listOf(fx.candidate(ws, UUID.randomUUID()), fx.candidate(ws, deleted), fx.candidate(ws, db.inbox(other)))) {
            // A valid copy in the same event is not admitted either: T1 is one decision.
            shouldThrow<StorageAdmissionInputException> { admission.admit(fx.request(F, listOf(valid, bad))) }
        }
        fx.reservationCount() shouldBe 0
    }
}
