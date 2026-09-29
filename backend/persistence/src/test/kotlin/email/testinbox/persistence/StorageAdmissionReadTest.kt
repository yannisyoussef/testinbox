package email.testinbox.persistence

import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration

/**
 * ADR-035 §4: every figure T1 decides on is `base + Σdelta + Σreservation`,
 * read in one snapshot, for the global scope and for each involved workspace
 * and inbox.
 */
class StorageAdmissionReadTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var fx: AdmissionFixture

    @BeforeEach
    fun database() {
        fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
    }

    /**
     * Every non-empty combination of the three sums, seeded with distinct
     * powers of two: each figure then decodes to exactly the parts it
     * included, and a missing or double-counted part shows in the value.
     */
    @ParameterizedTest(name = "parts {0} (1 = base, 2 = delta, 4 = reservation)")
    @ValueSource(ints = [1, 2, 4, 3, 5, 6, 7])
    fun `base, delta and reservations are all counted, in every combination`(parts: Int) {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val base = 1L
        val delta = 20L
        val reserved = 400L
        if (parts and 1 != 0) fx.base(ws, inbox, base)
        if (parts and 2 != 0) fx.delta(ws, inbox, delta)
        if (parts and 4 != 0) fx.reservation(ws, inbox, reserved)
        val expected =
            (if (parts and 1 != 0) base else 0) + (if (parts and 2 != 0) delta else 0) + (if (parts and 4 != 0) reserved else 0)

        val snapshot = fx.snapshot(setOf(ws), setOf(inbox))

        fx.used(snapshot, ws, inbox) shouldBe Triple(expected, expected, expected)
        snapshot.workspaces.getValue(WorkspaceId(ws)).reservedBytes shouldBe (if (parts and 4 != 0) reserved else 0)
    }

    @Test
    fun `a scope with no base row reads its deltas and reservations, and a scope with nothing reads zero`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val empty = db.workspace()
        val emptyInbox = db.inbox(empty)
        fx.delta(ws, inbox, 7)
        fx.reservation(ws, inbox, 11)

        val snapshot = fx.snapshot(setOf(ws, empty), setOf(inbox, emptyInbox))

        fx.used(snapshot, ws, inbox) shouldBe Triple(18L, 18L, 18L)
        snapshot.workspaces.getValue(WorkspaceId(empty)).usedBytes shouldBe 0
        snapshot.inboxes
            .getValue(InboxId(emptyInbox))
            .usage.usedBytes shouldBe 0
    }

    @Test
    fun `a cascaded delta with no inbox counts for its workspace and the global figure only`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        fx.delta(ws, null, 50)

        fx.used(fx.snapshot(setOf(ws), setOf(inbox)), ws, inbox) shouldBe Triple(50L, 50L, 0L)
    }

    @Test
    fun `RESERVED and RELEASING both count, however old, in every scope`() {
        // Stale is not free (ADR-035 I5): only a release deletes the row.
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        fx.reservation(ws, inbox, 100, state = "RESERVED", createdAgo = Duration.ofDays(3))
        fx.reservation(ws, inbox, 30, state = "RELEASING", createdAgo = Duration.ofDays(30))

        fx.used(fx.snapshot(setOf(ws), setOf(inbox)), ws, inbox) shouldBe Triple(130L, 130L, 130L)
    }

    @Test
    fun `the global figure counts every workspace, not only the involved ones`() {
        val db = fx.db
        val involved = db.workspace()
        val inbox = db.inbox(involved)
        repeat(3) {
            val other = db.workspace()
            val otherInbox = db.inbox(other)
            fx.base(other, otherInbox, 1_000)
            fx.delta(other, otherInbox, 100)
            fx.reservation(other, otherInbox, 10)
        }

        fx.used(fx.snapshot(setOf(involved), setOf(inbox)), involved, inbox) shouldBe Triple(3_330L, 0L, 0L)
    }

    @Test
    fun `a compaction between two reads changes nothing, because the bytes are the same folded or not`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val sibling = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 300, attachments = listOf(40, 2))
        db.message(ws, sibling, rawBytes = 90)
        fx.reservation(ws, inbox, 55)

        val before = fx.snapshot(setOf(ws), setOf(inbox, sibling))
        db.ledger.compact(batch = 1).foldedRows shouldBe 1 // part-way
        val partway = fx.snapshot(setOf(ws), setOf(inbox, sibling))
        db.ledger.compact(batch = 1_000)
        val after = fx.snapshot(setOf(ws), setOf(inbox, sibling))

        withClue("unfolded, part-folded and fully folded read identically") {
            listOf(before, partway, after).map { fx.used(it, ws, inbox) }.distinct() shouldBe
                listOf(Triple(342L + 90 + 55, 342L + 90 + 55, 342L + 55))
            listOf(before, partway, after)
                .map {
                    it.inboxes
                        .getValue(InboxId(sibling))
                        .usage.usedBytes
                }.distinct() shouldBe listOf(90L)
        }
    }

    @Test
    fun `the snapshot names each inbox's current owner, or none for a missing inbox`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val gone = db.inbox(ws)
        db.hardDeleteInbox(gone)

        val snapshot = fx.snapshot(setOf(ws), setOf(inbox, gone))

        snapshot.inboxes.getValue(InboxId(inbox)).owner shouldBe WorkspaceId(ws)
        snapshot.inboxes.getValue(InboxId(gone)).owner shouldBe null
    }

    @Test
    fun `t0 is the database clock of the admission transaction`() {
        val before =
            fx.db.jdbc
                .sql("SELECT now()")
                .query(java.time.OffsetDateTime::class.java)
                .single()
                .toInstant()
        val ws = fx.db.workspace()
        val snapshot = fx.snapshot(setOf(ws), setOf(fx.db.inbox(ws)))
        val after =
            fx.db.jdbc
                .sql("SELECT now()")
                .query(java.time.OffsetDateTime::class.java)
                .single()
                .toInstant()

        (snapshot.t0 in before..after) shouldBe true
    }
}
