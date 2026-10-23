package email.testinbox.persistence

import email.testinbox.domain.AttachmentId
import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.message.Attachment
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.SQLException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * ADR-035 §6/§14: V6 accounting runs underneath the unchanged ingestion and
 * retention code, concurrently with compaction and reconciliation, with zero
 * deadlocks and an exact ledger at the end.
 *
 * The writers are the production repositories: `JdbcMessageRepository`, whose
 * `appendVisible` this change does not touch, and `JdbcInboxRepository.hardDelete`.
 * That is the old-binary write path of ADR-035 Phase 1. It knows nothing about
 * the ledger, and the triggers account for it anyway.
 *
 * A writer racing a retention delete of the same inbox can hit that inbox's
 * foreign key. That is expected, and in production the SMTP gateway answers
 * 451 and the sender retries. Such failures are counted, not hidden. A
 * deadlock (SQLSTATE 40P01) is the one outcome that fails this test.
 */
class StorageLedgerConcurrencyTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    @Test
    fun `ingestion, retention, compaction and reconciliation run together without deadlock or drift`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val messages = JdbcMessageRepository(db.jdbc)
        val inboxRepository = JdbcInboxRepository(db.jdbc)
        val workspaces = List(3) { db.workspace() }
        val inboxes = Collections.synchronizedList(workspaces.flatMap { ws -> List(6) { ws to db.inbox(ws) } }.toMutableList())

        val deadlocks = AtomicInteger()
        val expectedRaces = AtomicInteger()
        val unexpected = Collections.synchronizedList(mutableListOf<Throwable>())
        val appended = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(WRITERS + 3)

        fun classify(failure: Throwable) {
            val sqlErrors = generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().toList()
            val states = sqlErrors.map { it.sqlState }
            val messages = sqlErrors.joinToString(" ") { it.message.orEmpty() }
            when {
                "40P01" in states -> deadlocks.incrementAndGet()

                // Only the two named races are expected: a writer against a
                // just-deleted inbox, and a compaction pass rolled back by one
                // (ADR-035 §10). Both are retried in production. Any other
                // integrity failure (a NOT NULL or CHECK a broken trigger
                // raised, say) is unexpected, and fails the test.
                "23503" in states && EXPECTED_FKS.any { it in messages } -> expectedRaces.incrementAndGet()

                else -> unexpected += failure
            }
        }

        repeat(WRITERS) { writer ->
            pool.submit {
                val random = Random(writer.toLong())
                start.await()
                repeat(APPENDS_PER_WRITER) {
                    val (ws, inbox) = synchronized(inboxes) { inboxes.getOrNull(random.nextInt(maxOf(1, inboxes.size))) } ?: return@repeat
                    val domainInbox =
                        Fixtures.inbox(WorkspaceId(ws), ProjectId(ws)).copy(id = InboxId(inbox))
                    val base =
                        Fixtures.message(
                            domainInbox,
                            providerMessageId =
                                if (random.nextInt(4) ==
                                    0
                                ) {
                                    "evt-${random.nextInt(20)}"
                                } else {
                                    null
                                },
                        )
                    val message =
                        base.copy(
                            rawSizeBytes = random.nextLong(1, 50_000),
                            attachments =
                                List(random.nextInt(0, 3)) {
                                    Attachment(AttachmentId(UUID.randomUUID()), base.id, "a", "text/plain", random.nextLong(1, 9_000), "k")
                                },
                        )
                    runCatching { db.transactions.execute { messages.appendVisible(message) } }
                        .onSuccess { appended.incrementAndGet() }
                        .onFailure(::classify)
                }
            }
        }
        // Retention: hard-deletes inboxes one by one, exactly as the sweep does.
        pool.submit {
            start.await()
            repeat(6) {
                val victim = inboxes.removeAt(0)
                runCatching { inboxRepository.hardDelete(InboxId(victim.second)) }.onFailure(::classify)
            }
        }
        // The compactor, continuously.
        pool.submit {
            start.await()
            repeat(COMPACTIONS) { runCatching { db.ledger.compact(batch = 7) }.onFailure(::classify) }
        }
        // Reconciliation, concurrently with all of it, including the repair
        // path that overwrites bases. A correct ledger never drifts, so every
        // run, detection and locked repair alike, must find nothing.
        val driftSeen = Collections.synchronizedList(mutableListOf<Any>())
        pool.submit {
            start.await()
            repeat(RECONCILIATIONS) {
                runCatching {
                    driftSeen.addAll(db.ledger.findDrift())
                    driftSeen.addAll(db.ledger.repairDrift())
                }.onFailure(::classify)
            }
        }

        start.countDown()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.MINUTES) shouldBe true

        unexpected shouldBe emptyList()
        deadlocks.get() shouldBe 0
        driftSeen shouldBe emptyList()
        (appended.get() > WRITERS * APPENDS_PER_WRITER / 2) shouldBe true

        // Drain, then prove: exact without any repair needed.
        db.ledger.compact(batch = 1_000_000)
        db.ledger.findDrift() shouldBe emptyList()
        db.assertInvariant("after the concurrent run")
        println(
            "ADR-035 concurrency: ${appended.get()} appends, ${expectedRaces.get()} expected FK races, " +
                "${deadlocks.get()} deadlocks, ledger exact",
        )
    }

    private companion object {
        const val WRITERS = 8
        const val APPENDS_PER_WRITER = 60
        const val COMPACTIONS = 200
        const val RECONCILIATIONS = 20

        /** The FKs the two expected races violate (see the class comment). */
        val EXPECTED_FKS = listOf("message_inbox_id_fkey", "attachment_message_id_fkey", "inbox_storage_inbox_id_fkey")
    }
}
