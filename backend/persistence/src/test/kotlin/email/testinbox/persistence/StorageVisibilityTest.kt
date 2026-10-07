package email.testinbox.persistence

import email.testinbox.application.port.CorruptStorageStateException
import email.testinbox.application.port.InboxStorageFigures
import email.testinbox.application.port.StorageRefusalSnapshot
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.storage.StorageUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * ADR-035 §13a/§13b through the real adapter (§17 test 46, TI-STORAGE-004
 * §47): `StorageUsage` equals the accounting, split correctly between stored
 * and reserved, in every combination, with a missing base row reading as
 * zero and every reservation state counting.
 */
class StorageVisibilityTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var fx: AdmissionFixture
    private lateinit var visibility: JdbcStorageVisibility

    @BeforeEach
    fun database() {
        fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
        visibility = JdbcStorageVisibility(fx.db.jdbc)
    }

    private fun read(
        ws: UUID,
        vararg inboxes: UUID,
    ) = visibility.read(WorkspaceId(ws), inboxes.map(::InboxId).toSet())

    private fun refuse(
        inbox: UUID,
        reason: StorageRefusalReason,
    ) {
        val reservations = JdbcStorageReservations(fx.db.jdbc, fx.db.transactions)
        fx.db.transactions.executeWithoutResult { reservations.recordRefusals(mapOf(InboxId(inbox) to reason)) }
    }

    @ParameterizedTest(name = "parts {0} (1 = base, 2 = delta, 4 = reservation)")
    @ValueSource(ints = [1, 2, 4, 3, 5, 6, 7])
    fun `stored is base plus delta and reserved is the reservations, in every combination, for the workspace and the inbox`(parts: Int) {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        if (parts and 1 != 0) fx.base(ws, inbox, 1)
        if (parts and 2 != 0) fx.delta(ws, inbox, 20)
        if (parts and 4 != 0) fx.reservation(ws, inbox, 400)
        val stored = (if (parts and 1 != 0) 1L else 0) + (if (parts and 2 != 0) 20L else 0)
        val reserved = if (parts and 4 != 0) 400L else 0

        val figures = read(ws, inbox)

        figures.workspace shouldBe StorageUsage(stored, reserved)
        figures.inbox(InboxId(inbox)).usage shouldBe StorageUsage(stored, reserved)
        figures.inbox(InboxId(inbox)).refusals shouldBe StorageRefusalSnapshot.NONE
    }

    @Test
    fun `a missing base row reads as zero while that scope's deltas and reservations still count`() {
        val db = fx.db
        val ws = db.workspace() // no workspace_storage_account row: created after the backfill
        val inbox = db.inbox(ws) // no inbox_storage row either
        fx.delta(ws, inbox, 7)
        fx.reservation(ws, inbox, 11, state = "RELEASING")

        val figures = read(ws, inbox)

        figures.workspace shouldBe StorageUsage(7, 11)
        figures.inbox(InboxId(inbox)) shouldBe InboxStorageFigures(StorageUsage(7, 11), StorageRefusalSnapshot.NONE)
        // And a scope with nothing at all is zero, not absent.
        val empty = db.inbox(ws)
        read(ws, empty).inbox(InboxId(empty)) shouldBe InboxStorageFigures.EMPTY
        read(db.workspace()).workspace shouldBe StorageUsage.ZERO
    }

    @Test
    fun `RESERVED and RELEASING both count as reserved, however old`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        fx.reservation(ws, inbox, 100, state = "RESERVED", createdAgo = java.time.Duration.ofDays(3))
        fx.reservation(ws, inbox, 200, state = "RELEASING", createdAgo = java.time.Duration.ofDays(30))

        val figures = read(ws, inbox)
        figures.workspace shouldBe StorageUsage(0, 300)
        figures.inbox(InboxId(inbox)).usage shouldBe StorageUsage(0, 300)
    }

    @Test
    fun `the workspace figure is the sum over its inboxes plus inbox-less deltas, and inboxes are told apart`() {
        val db = fx.db
        val ws = db.workspace()
        val a = db.inbox(ws)
        val b = db.inbox(ws)
        fx.base(ws, a, 10)
        fx.inboxBase(ws, b, 5)
        fx.workspaceBase(ws, 5)
        fx.delta(ws, a, 1)
        fx.delta(ws, null, 100) // a cascaded attachment delete: workspace only
        fx.reservation(ws, b, 1_000)

        val figures = read(ws, a, b)
        figures.workspace shouldBe StorageUsage(10 + 5 + 1 + 100, 1_000)
        figures.inbox(InboxId(a)).usage shouldBe StorageUsage(11, 0)
        figures.inbox(InboxId(b)).usage shouldBe StorageUsage(5, 1_000)
    }

    @Test
    fun `another workspace's inbox reads as empty, and another workspace's rows never enter the figures`() {
        val db = fx.db
        val mine = db.workspace()
        val theirs = db.workspace()
        val theirInbox = db.inbox(theirs)
        fx.base(theirs, theirInbox, 1_000)
        fx.reservation(theirs, theirInbox, 500)
        refuse(theirInbox, StorageRefusalReason.INBOX_LIMIT)

        val figures = read(mine, theirInbox)
        figures.workspace shouldBe StorageUsage.ZERO
        figures.inbox(InboxId(theirInbox)) shouldBe InboxStorageFigures.EMPTY
        // Even an inbox id of a workspace that has nothing to do with the caller is simply absent/empty.
        figures.inboxes.getValue(InboxId(theirInbox)) shouldBe InboxStorageFigures.EMPTY
    }

    @Test
    fun `the refusal record is read with the figures, written by the real §6a upsert`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        read(ws, inbox).inbox(InboxId(inbox)).refusals shouldBe StorageRefusalSnapshot.NONE

        refuse(inbox, StorageRefusalReason.INBOX_LIMIT)
        refuse(inbox, StorageRefusalReason.WORKSPACE_LIMIT)

        val refusals = read(ws, inbox).inbox(InboxId(inbox)).refusals
        refusals.count shouldBe 2
        refusals.lastReason shouldBe StorageRefusalReason.WORKSPACE_LIMIT
        refusals.lastAt shouldBe
            db.jdbc
                .sql(
                    "SELECT last_refusal_at FROM inbox_storage WHERE inbox_id = ?",
                ).param(inbox)
                .query(java.time.OffsetDateTime::class.java)
                .single()
                .toInstant()
        // The refusal-only read agrees.
        visibility.refusalsOf(WorkspaceId(ws), InboxId(inbox)) shouldBe refusals
    }

    @Test
    fun `a refusal row that contradicts itself fails closed instead of inventing a reason`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)

        fun set(sql: String) =
            db.jdbc
                .sql(
                    "INSERT INTO inbox_storage (inbox_id, workspace_id) VALUES (:i, :w) ON CONFLICT (inbox_id) DO NOTHING",
                ).param("i", inbox)
                .param("w", ws)
                .update()
                .also {
                    db.jdbc
                        .sql("UPDATE inbox_storage SET $sql WHERE inbox_id = :i")
                        .param("i", inbox)
                        .update()
                }

        set("refusal_count = 2, last_refusal_at = now(), last_refusal_reason = NULL")
        shouldThrow<CorruptStorageStateException> { read(ws, inbox) }
        shouldThrow<CorruptStorageStateException> { visibility.refusalsOf(WorkspaceId(ws), InboxId(inbox)) }

        set("refusal_count = 2, last_refusal_at = NULL, last_refusal_reason = 'INBOX_LIMIT'")
        shouldThrow<CorruptStorageStateException> { read(ws, inbox) }

        set("refusal_count = 2, last_refusal_at = now(), last_refusal_reason = 'SOMETHING_NEW'")
        shouldThrow<CorruptStorageStateException> { read(ws, inbox) }

        set("refusal_count = -1, last_refusal_at = NULL, last_refusal_reason = NULL")
        shouldThrow<CorruptStorageStateException> { read(ws, inbox) }

        // A zero count has no last refusal, whatever stray metadata the row carries.
        set("refusal_count = 0, last_refusal_at = now(), last_refusal_reason = 'INBOX_LIMIT'")
        read(ws, inbox).inbox(InboxId(inbox)).refusals shouldBe StorageRefusalSnapshot.NONE
    }

    @Test
    fun `a compaction between two reads changes nothing, because the bytes are the same folded or not`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        fx.base(ws, inbox, 100)
        repeat(3) { fx.delta(ws, inbox, 10) }
        fx.reservation(ws, inbox, 50)

        val before = read(ws, inbox)
        db.ledger.compact(batch = 1).foldedRows shouldBe 1 // part-way
        val during = read(ws, inbox)
        db.ledger.compact(batch = 1_000)
        val after = read(ws, inbox)

        withClue("one statement sees each fold wholly or not at all") {
            listOf(before, during, after).map { it.workspace }.toSet() shouldBe setOf(StorageUsage(130, 50))
            listOf(before, during, after).map { it.inbox(InboxId(inbox)).usage }.toSet() shouldBe setOf(StorageUsage(130, 50))
        }
    }

    @Test
    fun `the read takes neither the admission lock nor the ledger lock`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        // Hold both ADR-035 advisory locks from another session for the whole read.
        db.dataSource.connection.use { other ->
            other.autoCommit = false
            other.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(35, 1), pg_advisory_xact_lock(35, 2)") }
            try {
                // Would block (and the test would hang) if visibility took either lock.
                read(ws, inbox).workspace shouldBe StorageUsage.ZERO
            } finally {
                other.rollback()
            }
        }
    }

    @Test
    fun `the global figure is not in the statement, so it cannot be returned`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val stranger = db.workspace()
        fx.base(stranger, db.inbox(stranger), 1_000_000)
        val figures = read(ws, inbox)
        figures.workspace shouldBe StorageUsage.ZERO
        // Structural: the adapter's SQL names no unscoped sum over the account, delta or reservation tables.
        val sql =
            JdbcStorageVisibility::class.java
                .getResourceAsStream(
                    "JdbcStorageVisibility.class",
                )!!
                .readBytes()
                .toString(Charsets.ISO_8859_1)
        sql shouldContain "workspace_id = :workspace"
        sql shouldNotContain "FROM workspace_storage_account)"
    }

    @Test
    fun `query plan sanity - reservations are read through their workspace and inbox indexes`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        // A representative backlog: thousands of reservation rows and unfolded deltas across many tenants.
        repeat(40) {
            val w = db.workspace()
            val i = db.inbox(w)
            db.jdbc
                .sql(
                    """
                    INSERT INTO storage_reservation (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at, write_deadline_at, node_id, generation)
                    SELECT gen_random_uuid(), :w, :i, ARRAY['k'], 10, 'RESERVED', now(), now() + interval '2 minutes', 'seed', gen_random_uuid()
                      FROM generate_series(1, 50)
                    """.trimIndent(),
                ).param("w", w)
                .param("i", i)
                .update()
            db.jdbc
                .sql("INSERT INTO storage_delta (workspace_id, inbox_id, bytes) SELECT :w, :i, 1 FROM generate_series(1, 50)")
                .param("w", w)
                .param("i", i)
                .update()
        }
        fx.reservation(ws, inbox, 5)
        db.jdbc.sql("ANALYZE storage_reservation; ANALYZE storage_delta; ANALYZE inbox_storage; ANALYZE workspace_storage_account").update()

        val plan =
            db.jdbc
                .sql(
                    """
                    EXPLAIN SELECT sum(bytes) FROM storage_reservation WHERE workspace_id = :w
                    """.trimIndent(),
                ).param("w", ws)
                .query(String::class.java)
                .list()
                .joinToString("\n")
        withClue(plan) {
            plan shouldContain "ix_storage_reservation_workspace"
        }
        val inboxPlan =
            db.jdbc
                .sql(
                    "EXPLAIN SELECT inbox_id, sum(bytes) FROM storage_reservation WHERE workspace_id = :w AND inbox_id IN (:i) GROUP BY inbox_id",
                ).param("w", ws)
                .param("i", listOf(inbox))
                .query(String::class.java)
                .list()
                .joinToString("\n")
        withClue(inboxPlan) {
            (inboxPlan.contains("ix_storage_reservation_inbox") || inboxPlan.contains("ix_storage_reservation_workspace")) shouldBe true
        }
        // The read itself is correct against the backlog.
        read(ws, inbox).inbox(InboxId(inbox)).usage shouldBe StorageUsage(0, 5)
    }
}
