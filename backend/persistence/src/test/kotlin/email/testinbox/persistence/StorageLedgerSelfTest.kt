package email.testinbox.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * The accounting checks can fail (repository philosophy: a gate is only
 * trustworthy if something proves it can fail).
 *
 * Each case plants one plausible defect in a *throwaway* database:
 * - a trigger dropped;
 * - a function replaced with a wrong one;
 * - a compaction that deletes deltas without folding them.
 *
 * It then shows two things: the independent invariant checker every other
 * accounting test relies on reports it, and reconciliation finds it and
 * repairs it. No production code is broken to do this: the defects exist
 * only in each test's own database, which is discarded.
 */
class StorageLedgerSelfTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
    }

    private fun assertDetectedAndRepaired() {
        shouldThrow<AssertionError> { db.assertInvariant("with a planted defect") }
        db.ledger.findDrift().shouldNotBeEmpty()
        db.ledger.repairDrift().shouldNotBeEmpty()
        db.assertInvariant("after reconciliation repaired the planted defect")
    }

    @Test
    fun `a missing message delete trigger is detected`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100)
        db.jdbc.sql("DROP TRIGGER storage_ledger_message_delete ON message").update()

        db.hardDeleteInbox(inbox)

        assertDetectedAndRepaired()
    }

    @Test
    fun `wrong attachment accounting is detected`() {
        // An attachment charged as if it were part of the raw message only:
        // the single-count mistake ADR-035 §2 explicitly rules out.
        db.jdbc
            .sql(
                """
                CREATE OR REPLACE FUNCTION storage_ledger_attachment() RETURNS trigger LANGUAGE plpgsql AS
                ${'$'}${'$'} BEGIN RETURN NULL; END ${'$'}${'$'}
                """.trimIndent(),
            ).update()
        val ws = db.workspace()
        val inbox = db.inbox(ws)

        db.message(ws, inbox, rawBytes = 100, attachments = listOf(40))

        db.accountedWorkspace(ws) shouldBe 100 // 40 short
        assertDetectedAndRepaired()
    }

    @Test
    fun `a skipped cascade decrement is detected`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, attachments = listOf(25))
        db.jdbc.sql("DROP TRIGGER storage_ledger_attachment_delete ON attachment").update()

        db.hardDeleteInbox(inbox)

        db.accountedWorkspace(ws) shouldBe 25 // the cascade's attachment bytes were never decremented
        assertDetectedAndRepaired()
    }

    @Test
    fun `an incorrect compaction that discards deltas is detected`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 500)

        // The defect a faulty compactor would have: deltas deleted, never folded.
        db.jdbc.sql("DELETE FROM storage_delta").update()

        db.accountedWorkspace(ws) shouldBe 0
        assertDetectedAndRepaired()
    }

    @Test
    fun `a correct ledger passes the same checks`() {
        // The control: the detections above come from the planted defects, not
        // from a checker that fails everything.
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 500, attachments = listOf(20))
        db.hardDeleteInbox(db.inbox(ws))
        db.ledger.compact(batch = 100)

        db.assertInvariant("control")
        db.ledger.findDrift() shouldBe emptyList()
    }
}
