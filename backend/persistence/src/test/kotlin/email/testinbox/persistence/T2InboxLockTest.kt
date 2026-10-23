package email.testinbox.persistence

import email.testinbox.domain.InboxId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The T2 inbox lock of the filesystem-containment contract (§2.4, T2 row;
 * TI-STORAGE-006E PR D) on PostgreSQL: `FOR SHARE` with the state read in the
 * same statement, so the retention sweep's state change cannot commit while T2
 * holds the inbox, and T2 sees the state it commits under.
 */
class T2InboxLockTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase
    private lateinit var reservations: JdbcStorageReservations

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
        reservations = JdbcStorageReservations(db.jdbc, db.transactions)
    }

    @Test
    fun `T2 locks inboxes FOR SHARE and reads their state - a retention state change waits for T2`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val t2 =
                pool.submit<Map<InboxId, email.testinbox.domain.inbox.InboxState>> {
                    db.transactions.execute {
                        reservations.lockInboxes(listOf(InboxId(inbox))).also {
                            held.countDown()
                            release.await(30, TimeUnit.SECONDS)
                        }
                    }
                }
            held.await(30, TimeUnit.SECONDS)
            val expire =
                pool.submit {
                    db.jdbc
                        .sql("UPDATE inbox SET state = 'EXPIRED' WHERE id = ?")
                        .param(inbox)
                        .update()
                }
            db.awaitBlockedSessions() // FOR KEY SHARE would let this UPDATE through
            release.countDown()
            t2.get(30, TimeUnit.SECONDS) shouldBe mapOf(InboxId(inbox) to email.testinbox.domain.inbox.InboxState.ACTIVE)
            expire.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        db.transactions.execute { reservations.lockInboxes(listOf(InboxId(inbox))) } shouldBe
            mapOf(InboxId(inbox) to email.testinbox.domain.inbox.InboxState.EXPIRED)
    }
}
