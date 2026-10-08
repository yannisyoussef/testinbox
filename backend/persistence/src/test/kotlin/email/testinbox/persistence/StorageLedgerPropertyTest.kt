package email.testinbox.persistence

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.random.Random

/**
 * ADR-035 §10 as a property: whatever sequence of appends, duplicates,
 * deletes, cascades, updates, compactions and reconciliations runs, every
 * workspace accounts exactly for its rows at every step.
 *
 * Every seed is fixed, so a failure names its seed and step and reproduces
 * exactly. There is no unseeded randomness here.
 *
 * Inbox figures are asserted too, except for inboxes a *direct* message delete
 * has touched. ADR-035 §10 accepts that such an inbox over-counts until
 * reconciliation repairs it, so those inboxes are tracked, and every
 * reconciliation step must restore them exactly.
 */
class StorageLedgerPropertyTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private enum class Op { APPEND, DUPLICATE, NEW_INBOX, DELETE_ATTACHMENT, DELETE_MESSAGE, CASCADE, RESIZE, COMPACT, RECONCILE }

    private val weighted =
        listOf(
            Op.APPEND to 35,
            Op.DUPLICATE to 8,
            Op.NEW_INBOX to 6,
            Op.DELETE_ATTACHMENT to 6,
            Op.DELETE_MESSAGE to 5,
            Op.CASCADE to 6,
            Op.RESIZE to 6,
            Op.COMPACT to 20,
            Op.RECONCILE to 8,
        ).flatMap { (op, weight) -> List(weight) { op } }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = [1, 7, 42, 2026, 35_035])
    fun `accounting stays exact under any sequence of operations`(seed: Long) {
        val random = Random(seed)
        val db = LedgerTestDatabase.create(postgres, admin)
        val workspaces = List(3) { db.workspace() }
        val inboxes = workspaces.flatMap { ws -> List(2) { ws to db.inbox(ws) } }.toMutableList()
        val providerIds = mutableListOf<Pair<Pair<UUID, UUID>, String>>()
        val tainted = mutableSetOf<UUID>()

        repeat(STEPS) { step ->
            val op = weighted[random.nextInt(weighted.size)]
            val context = "seed $seed, step $step, op $op"
            withClue(context) {
                when (op) {
                    Op.APPEND -> {
                        inboxes.randomOrNull(random)?.let { target ->
                            val pmid = if (random.nextBoolean()) "evt-${random.nextInt()}" else null
                            db.message(
                                target.first,
                                target.second,
                                rawBytes = random.nextLong(1, 20_000),
                                attachments = List(random.nextInt(0, 4)) { random.nextLong(1, 5_000) },
                                providerMessageId = pmid,
                            )
                            if (pmid != null) providerIds += target to pmid
                        }
                    }

                    Op.DUPLICATE -> {
                        providerIds.randomOrNull(random)?.let { (target, pmid) ->
                            if (target in inboxes) {
                                // A reprocessed provider event is skipped by the conflict
                                // while its original row exists (ADR-026). If a direct delete
                                // removed that row, the replay is a genuine new insert.
                                val originalExists =
                                    db.jdbc
                                        .sql("SELECT count(*) FROM message WHERE provider_message_id = ?")
                                        .param(pmid)
                                        .query(Long::class.java)
                                        .single() > 0
                                val inserted = db.message(target.first, target.second, rawBytes = 123_456, providerMessageId = pmid)
                                (inserted == null) shouldBe originalExists
                            }
                        }
                    }

                    Op.NEW_INBOX -> {
                        workspaces.random(random).let { ws -> inboxes += ws to db.inbox(ws) }
                    }

                    Op.DELETE_ATTACHMENT -> {
                        db.jdbc.sql("DELETE FROM attachment WHERE id = (SELECT id FROM attachment ORDER BY id LIMIT 1)").update()
                    }

                    Op.DELETE_MESSAGE -> {
                        db.jdbc
                            .sql("DELETE FROM message WHERE id = (SELECT id FROM message ORDER BY id LIMIT 1) RETURNING inbox_id")
                            .query(UUID::class.java)
                            .optional()
                            .ifPresent { tainted += it }
                    }

                    Op.CASCADE -> {
                        inboxes.randomOrNull(random)?.let { target ->
                            db.hardDeleteInbox(target.second)
                            inboxes -= target
                            tainted -= target.second
                        }
                    }

                    Op.RESIZE -> {
                        // V8 (contract §2.4): a size is written once. A resize would move
                        // L with no debt row, so it is refused, and the ledger moves nothing.
                        val delta = random.nextLong(-5, 500)
                        val exists =
                            db.jdbc
                                .sql("SELECT count(*) FROM message WHERE raw_size_bytes + ? >= 0 AND ? <> 0")
                                .params(delta, delta)
                                .query(Long::class.java)
                                .single() > 0
                        val resized =
                            runCatching {
                                db.jdbc
                                    .sql(
                                        "UPDATE message SET raw_size_bytes = raw_size_bytes + ? WHERE id = (SELECT id FROM message ORDER BY id DESC LIMIT 1)",
                                    ).param(delta)
                                    .update()
                            }
                        if (exists && delta != 0L) check(resized.isFailure) { "a resize was not refused" }
                    }

                    Op.COMPACT -> {
                        db.ledger.compact(batch = random.nextInt(1, 50))
                    }

                    Op.RECONCILE -> {
                        db.ledger.repairDrift()
                        tainted.clear()
                        // After a repair, everything is exact, with no exclusions.
                        db.ledger.findDrift() shouldBe emptyList()
                    }
                }
                db.assertInvariant(context, excludedInboxes = tainted)
            }
        }
        db.ledger.compact(batch = 100_000)
        db.ledger.repairDrift()
        db.assertInvariant("seed $seed, final")
        println("ADR-035 property seed $seed: $STEPS steps, ${db.liveInboxes().size} live inboxes, invariant held at every step")
    }

    private fun <T> List<T>.randomOrNull(random: Random): T? = if (isEmpty()) null else this[random.nextInt(size)]

    private companion object {
        const val STEPS = 250
    }
}
