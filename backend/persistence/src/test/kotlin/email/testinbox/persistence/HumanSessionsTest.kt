package email.testinbox.persistence

import email.testinbox.application.identity.SessionCredential
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.AuthenticateHumanSession
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.application.usecase.RevokeAccountSessions
import email.testinbox.application.usecase.RevokeHumanSession
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real transactions and independent adapters, not a simulated session store. */
class HumanSessionsTest : PersistenceIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var store: JdbcHumanSessions

    @Autowired lateinit var transactions: TransactionRunner

    private val clock = SessionClock()

    private fun issue(repository: HumanSessions = store) = IssueHumanSession(repository, transactions, clock)

    private fun auth(repository: HumanSessions = store) = AuthenticateHumanSession(repository, transactions, clock)

    private fun account(
        state: String = "ACTIVE",
        verified: Boolean = true,
    ): HumanAccountId {
        val id = HumanAccountId(UUID.randomUUID())
        jdbc
            .sql(
                """
                INSERT INTO human_account (id, contact_email, contact_verified_at, state, created_at)
                VALUES (:id, :email, CASE WHEN :verified THEN :at ELSE NULL END, :state, :at)
                """.trimIndent(),
            ).param("id", id.value)
            .param("email", "${id.value}@example.invalid")
            .param("verified", verified)
            .param("at", Timestamps.toDb(clock.instant()))
            .param("state", state)
            .update()
        return id
    }

    private fun count(id: HumanAccountId): Long =
        jdbc
            .sql("SELECT count(*) FROM human_session WHERE account_id = :id")
            .param("id", id.value)
            .query(Long::class.javaObjectType)
            .single()

    private fun issued(id: HumanAccountId = account()): IssueHumanSession.Issued = checkNotNull(issue().execute(id))

    @Test
    fun `session persists only a verifier and never allocates a participant workspace`() {
        val id = account()
        val session = issued(id)
        session.credential.value.length shouldBe 43
        val stored = transactions.required { store.lockSession(id, session.credential.verifier()) }
        checkNotNull(stored).expiresAt shouldBe clock.instant().plusSeconds(7 * 24 * 3600)
        stored.verifier shouldBe session.credential.verifier()
        jdbc
            .sql("SELECT count(*) FROM beta_admission WHERE account_id = :id")
            .param("id", id.value)
            .query(Long::class.javaObjectType)
            .single() shouldBe 0
        val columns =
            jdbc
                .sql(
                    """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'human_session'
                    """.trimIndent(),
                ).query(String::class.java)
                .list()
                .toSet()
        columns shouldBe setOf("verifier", "account_id", "created_at", "expires_at", "last_seen_at")
    }

    @Test
    fun `missing unverified suspended and deletion pending accounts cannot get sessions`() {
        listOf(HumanAccountId(UUID.randomUUID()), account(verified = false), account("SUSPENDED"), account("DELETION_PENDING"))
            .forEach { id ->
                issue().execute(id) shouldBe null
                count(id) shouldBe 0
            }
    }

    @Test
    fun `authentication resolves its account from the verifier and refreshes only idle time`() {
        val id = account()
        val session = issued(id)
        clock.advanceSeconds(23 * 3600)
        val authenticated = checkNotNull(auth().execute(session.credential.value))
        authenticated.account.id shouldBe id
        authenticated.account.contactEmail shouldBe "${id.value}@example.invalid"
        val row = transactions.required { store.lockSession(id, session.credential.verifier()) }
        checkNotNull(row).lastSeenAt shouldBe clock.instant()
        row.expiresAt shouldBe session.expiresAt
        clock.advanceSeconds(23 * 3600)
        checkNotNull(auth().execute(session.credential.value)).account.id shouldBe id
    }

    @Test
    fun `idle expiry refuses at exactly twenty four hours and removes the row`() {
        val id = account()
        val session = issued(id)
        clock.advanceSeconds(24 * 3600)
        auth().execute(session.credential.value) shouldBe null
        count(id) shouldBe 0
    }

    @Test
    fun `activity never extends the seven day absolute expiry`() {
        val session = issued()
        repeat(13) {
            clock.advanceSeconds(12 * 3600)
            checkNotNull(auth().execute(session.credential.value)).expiresAt shouldBe session.expiresAt
        }
        clock.advanceSeconds(12 * 3600)
        auth().execute(session.credential.value) shouldBe null
    }

    @Test
    fun `a backwards clock fails closed rather than extending a session`() {
        val session = issued()
        clock.advanceSeconds(-1)
        auth().execute(session.credential.value) shouldBe null
    }

    @Test
    fun `malformed and unknown credentials never authenticate`() {
        val mustNotRead =
            object : HumanSessions by store {
                override fun findAccountId(verifier: String): HumanAccountId? = error("malformed token reached storage")
            }
        listOf(null, "", " ", "a".repeat(42), "a".repeat(44), "x/".repeat(22), "a".repeat(43) + "\n")
            .forEach { auth(mustNotRead).execute(it) shouldBe null }
        auth().execute(SessionCredential.generate().value) shouldBe null
    }

    @Test
    fun `logout is immediate on another adapter and is safe to retry`() {
        val session = issued()
        val other = JdbcHumanSessions(jdbc)
        val revoke = RevokeHumanSession(other, transactions)
        revoke.execute(session.credential.value)
        revoke.execute(session.credential.value)
        revoke.execute(null)
        auth().execute(session.credential.value) shouldBe null
    }

    @Test
    fun `suspension deletion pending and loss of verification block an already issued cookie`() {
        listOf("state = 'SUSPENDED'", "state = 'DELETION_PENDING'", "contact_verified_at = NULL").forEach { change ->
            val id = account()
            val session = issued(id)
            // Fixed test-owned SQL fragments, never an application parameter.
            jdbc.sql("UPDATE human_account SET $change WHERE id = :id").param("id", id.value).update()
            auth().execute(session.credential.value) shouldBe null
            count(id) shouldBe 0
        }
    }

    @Test
    fun `deleting an account cascades every session and cannot resurrect it`() {
        val id = account()
        val first = issued(id)
        issued(id)
        jdbc.sql("DELETE FROM human_account WHERE id = :id").param("id", id.value).update()
        count(id) shouldBe 0
        auth().execute(first.credential.value) shouldBe null
    }

    @Test
    fun `rotation invalidates the previous cookie without affecting another account`() {
        val id = account()
        val previous = issued(id)
        val foreign = issued()
        val next = checkNotNull(issue().execute(id, previous.credential.value))
        auth().execute(previous.credential.value) shouldBe null
        checkNotNull(auth().execute(next.credential.value)).account.id shouldBe id
        issue().execute(id, foreign.credential.value)
        checkNotNull(auth().execute(foreign.credential.value))
    }

    @Test
    fun `failed rotation restores the previous session and the per account bound`() {
        val id = account()
        val previous = issued(id)
        val broken =
            object : HumanSessions by store {
                override fun insert(session: HumanSession) {
                    store.insert(session)
                    error("write failed")
                }
            }
        shouldThrow<IllegalStateException> { issue(broken).execute(id, previous.credential.value) }
        count(id) shouldBe 1
        checkNotNull(auth().execute(previous.credential.value)).account.id shouldBe id
    }

    @Test
    fun `serial issuance evicts the oldest and expiry cleanup keeps no stale sessions`() {
        val id = account()
        val first = issued(id)
        repeat(5) {
            clock.advanceSeconds(1)
            issued(id)
        }
        count(id) shouldBe 5
        auth().execute(first.credential.value) shouldBe null
        clock.advanceSeconds(24 * 3600)
        val fresh = issued(id)
        count(id) shouldBe 1
        checkNotNull(auth().execute(fresh.credential.value)).account.id shouldBe id
    }

    @Test
    fun `concurrent issuers respect the five session bound across independent adapters`() {
        val id = account()
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val issued =
            try {
                val futures =
                    (1..8).map {
                        executor.submit<IssueHumanSession.Issued> {
                            val independent = issue(JdbcHumanSessions(jdbc))
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            checkNotNull(independent.execute(id))
                        }
                    }
                check(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
        count(id) shouldBe HumanSession.MAX_PER_ACCOUNT.toLong()
        issued.count { auth().execute(it.credential.value) != null } shouldBe HumanSession.MAX_PER_ACCOUNT
    }

    @Test
    fun `revoke all affects exactly one account and no future new session`() {
        val id = account()
        val first = issued(id)
        val second = issued(id)
        val other = issued()
        RevokeAccountSessions(store, transactions).execute(id)
        auth().execute(first.credential.value) shouldBe null
        auth().execute(second.credential.value) shouldBe null
        checkNotNull(auth().execute(other.credential.value))
        checkNotNull(auth().execute(issued(id).credential.value))
    }

    @Test
    fun `locking and mutation operations refuse outside a transaction`() {
        val id = account()
        val credential = SessionCredential.generate()
        shouldThrow<IllegalStateException> { store.lockAccount(id) }
        shouldThrow<IllegalStateException> { store.lockSession(id, credential.verifier()) }
        shouldThrow<IllegalStateException> { store.makeRoom(id, clock.instant()) }
        shouldThrow<IllegalStateException> { store.touch(credential.verifier(), clock.instant()) }
        shouldThrow<IllegalStateException> { store.delete(credential.verifier()) }
        shouldThrow<IllegalStateException> { store.deleteAll(id) }
        shouldThrow<IllegalStateException> {
            store.insert(HumanSession(credential.verifier(), id, clock.instant(), clock.instant().plusSeconds(60), clock.instant()))
        }
    }
}

private class SessionClock : Clock() {
    private var current = Instant.parse("2026-10-29T12:00:00Z")

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }

    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
