package email.testinbox.persistence

import email.testinbox.application.port.BetaWaitlist
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.application.usecase.ManageBetaWaitlist
import email.testinbox.domain.identity.BetaWaitlistEntry
import email.testinbox.domain.identity.BetaWaitlistPolicy
import email.testinbox.domain.identity.HumanAccountId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BetaWaitlistTest : PersistenceIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var admissions: JdbcBetaAdmissions

    @Autowired lateinit var store: JdbcBetaWaitlist

    @Autowired lateinit var tx: TransactionRunner
    private val now = Instant.parse("2026-10-29T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val policy = BetaWaitlistPolicy()

    @BeforeEach fun reset() {
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("DELETE FROM human_account").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open=false,capacity_ready=false").update()
    }

    private fun manage(
        repository: BetaWaitlist = store,
        at: Instant = now,
    ) = ManageBetaWaitlist(admissions, repository, tx, Clock.fixed(at, ZoneOffset.UTC))

    private fun applicant(
        verified: Boolean = true,
        state: String = "ACTIVE",
    ): HumanAccountId {
        val id = HumanAccountId(UUID.randomUUID())
        jdbc
            .sql(
                """
                INSERT INTO human_account(id,contact_email,contact_verified_at,state,created_at)
                VALUES(:id,:email,CASE WHEN :verified THEN :now ELSE NULL END,:state,:now)
                """.trimIndent(),
            ).param("id", id.value)
            .param("email", "${id.value}@example.invalid")
            .param("verified", verified)
            .param("state", state)
            .param("now", Timestamps.toDb(now))
            .update()
        return id
    }

    private fun join(
        id: HumanAccountId,
        selected: BetaWaitlistPolicy? = policy,
    ) = manage().execute(id, ManageBetaWaitlist.Action.JOIN, selected)

    private fun count(table: String): Long = jdbc.sql("SELECT count(*) FROM $table").query(Long::class.javaObjectType).single()

    @Test fun `explicit join is idempotent without admission authority or extending retention`() {
        val id = applicant()
        val workspaces = count("workspace")
        manage().execute(id, ManageBetaWaitlist.Action.READ) shouldBe ManageBetaWaitlist.Result.NotWaiting
        count("beta_waitlist") shouldBe 0
        val first = join(id).shouldBeInstanceOf<ManageBetaWaitlist.Result.Waiting>()
        first.entry.joinedAt shouldBe now
        first.entry.expiresAt shouldBe now.plus(Duration.ofDays(30))
        manage(at = now.plusSeconds(60)).execute(id, ManageBetaWaitlist.Action.JOIN, policy) shouldBe first
        count("beta_waitlist") shouldBe 1
        count("beta_admission") shouldBe 0
        count("workspace") shouldBe workspaces
    }

    @Test fun `withdraw and repeated withdraw remove only this account`() {
        val a = applicant()
        val b = applicant()
        join(a)
        join(b)
        repeat(2) { manage().execute(a, ManageBetaWaitlist.Action.WITHDRAW) shouldBe ManageBetaWaitlist.Result.NotWaiting }
        store.find(a) shouldBe null
        store.find(b).shouldBeInstanceOf<BetaWaitlistEntry>()
        count("beta_waitlist") shouldBe 1
    }

    @Test fun `missing unverified suspended and deletion pending accounts cannot enroll`() {
        val ids =
            listOf(
                HumanAccountId(UUID.randomUUID()),
                applicant(false),
                applicant(state = "SUSPENDED"),
                applicant(state = "DELETION_PENDING"),
            )
        ids.forEach { join(it) shouldBe ManageBetaWaitlist.Result.Ineligible }
        count("beta_waitlist") shouldBe 0
    }

    @Test fun `invalid join policy fails closed but existing entries may be read or withdrawn`() {
        val id = applicant()
        join(id, null) shouldBe ManageBetaWaitlist.Result.Unavailable
        count("beta_waitlist") shouldBe 0
        val first = join(id)
        join(id, null) shouldBe first
        manage().execute(id, ManageBetaWaitlist.Action.WITHDRAW) shouldBe ManageBetaWaitlist.Result.NotWaiting
    }

    @Test fun `scheduled purge removes expired and admitted entries even with no further signup`() {
        val expired = applicant()
        val admitted = applicant()
        val waiting = applicant()
        join(expired, BetaWaitlistPolicy(Duration.ofDays(1)))
        join(admitted)
        join(waiting)
        jdbc.sql("UPDATE beta_cohort SET admissions_open=true,capacity_ready=true").update()
        AdmitBetaParticipant(
            admissions,
            tx,
            clock,
            email.testinbox.application.storage.CohortStorageReadiness {
                true
            },
        ).execute(admitted).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()
        manage(at = now.plus(Duration.ofDays(1))).purge()
        store.find(expired) shouldBe null
        store.find(admitted) shouldBe null
        store.find(waiting).shouldBeInstanceOf<BetaWaitlistEntry>()
        manage().execute(admitted, ManageBetaWaitlist.Action.READ) shouldBe ManageBetaWaitlist.Result.Admitted
    }

    @Test fun `the configured thirty-day retention removes older entries and keeps younger ones, on the timer and on requests`() {
        val old = applicant()
        val young = applicant()
        val reader = applicant()
        // Joined with the shipped policy (30 days), 31 and 29 days before "now".
        manage(at = now.minus(Duration.ofDays(31))).execute(old, ManageBetaWaitlist.Action.JOIN, policy)
        manage(at = now.minus(Duration.ofDays(29))).execute(young, ManageBetaWaitlist.Action.JOIN, policy)
        store.find(old)!!.expiresAt shouldBe now.minus(Duration.ofDays(1))
        count("beta_waitlist") shouldBe 2

        // The scheduled path (BetaWaitlistMaintenance calls exactly this) with no sign-up traffic.
        manage().purge()
        store.find(old) shouldBe null
        store.find(young).shouldBeInstanceOf<BetaWaitlistEntry>()

        // The boundary is inclusive of expiry: an entry expires at joined + 30 days, not later.
        manage(at = now.plus(Duration.ofDays(1))).purge()
        store.find(young) shouldBe null

        // The request path purges first too: any applicant's read removes an expired entry.
        val late = applicant()
        manage(at = now.minus(Duration.ofDays(30))).execute(late, ManageBetaWaitlist.Action.JOIN, policy)
        manage().execute(reader, ManageBetaWaitlist.Action.READ) shouldBe ManageBetaWaitlist.Result.NotWaiting
        store.find(late) shouldBe null
        count("beta_waitlist") shouldBe 0
    }

    @Test fun `concurrent applicants across independent use cases cannot overspend the last place`() {
        val entrants = List(12) { applicant() }
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(12).use { executor ->
            val tasks =
                entrants.map { id ->
                    executor.submit<ManageBetaWaitlist.Result> {
                        start.await(10, TimeUnit.SECONDS) shouldBe true
                        manage().execute(id, ManageBetaWaitlist.Action.JOIN, BetaWaitlistPolicy(maximum = 1))
                    }
                }
            start.countDown()
            val results = tasks.map { it.get(20, TimeUnit.SECONDS) }
            results.count { it is ManageBetaWaitlist.Result.Waiting } shouldBe 1
            results.count { it == ManageBetaWaitlist.Result.Unavailable } shouldBe 11
        }
        count("beta_waitlist") shouldBe 1
        count("beta_admission") shouldBe 0
    }

    @Test fun `a full thousand entry waitlist refuses entry and SQL cannot allocate place 1001`() {
        jdbc
            .sql(
                """
                INSERT INTO human_account(id,contact_email,contact_verified_at,created_at)
                SELECT gen_random_uuid(),'waitlist-'||n||'@example.invalid',:now,:now FROM generate_series(1,1000) n
                """.trimIndent(),
            ).param("now", Timestamps.toDb(now))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO beta_waitlist(account_id,place,joined_at,expires_at)
                SELECT id,row_number() OVER(ORDER BY contact_email),:now,:expires FROM human_account
                """.trimIndent(),
            ).param("now", Timestamps.toDb(now))
            .param("expires", Timestamps.toDb(now.plus(Duration.ofDays(30))))
            .update()
        val id = applicant()
        join(id) shouldBe ManageBetaWaitlist.Result.Unavailable
        count("beta_waitlist") shouldBe 1000
        shouldThrow<DataIntegrityViolationException> {
            jdbc
                .sql(
                    """
                    INSERT INTO beta_waitlist(account_id,place,joined_at,expires_at) VALUES(:id,1001,:now,:expires)
                    """.trimIndent(),
                ).param("id", id.value)
                .param("now", Timestamps.toDb(now))
                .param("expires", Timestamps.toDb(now.plusSeconds(3600)))
                .update()
        }
    }

    @Test fun `failed insert rolls back and account deletion cascades its personal entry`() {
        val id = applicant()
        val failing =
            object : BetaWaitlist by store {
                override fun insert(entry: BetaWaitlistEntry) {
                    store.insert(entry)
                    error("fixture failure after insert")
                }
            }
        shouldThrow<IllegalStateException> { manage(failing).execute(id, ManageBetaWaitlist.Action.JOIN, policy) }
        store.find(id) shouldBe null
        join(id)
        jdbc.sql("DELETE FROM human_account WHERE id=:id").param("id", id.value).update()
        count("beta_waitlist") shouldBe 0
    }

    @Test fun `expired entry can be rejoined explicitly with a new retention period`() {
        val id = applicant()
        val first = join(id).shouldBeInstanceOf<ManageBetaWaitlist.Result.Waiting>()
        val later = manage(at = first.entry.expiresAt)
        later.execute(id, ManageBetaWaitlist.Action.READ) shouldBe ManageBetaWaitlist.Result.NotWaiting
        val renewed = later.execute(id, ManageBetaWaitlist.Action.JOIN, policy).shouldBeInstanceOf<ManageBetaWaitlist.Result.Waiting>()
        renewed.entry.joinedAt shouldBe first.entry.expiresAt
    }

    @Test fun `writes require an active database transaction`() {
        val id = applicant()
        shouldThrow<IllegalStateException> { store.insert(BetaWaitlistEntry(id, 1, now, now.plusSeconds(3600))) }
        shouldThrow<IllegalStateException> { store.remove(id) }
        shouldThrow<IllegalStateException> { store.nextPlace(1000) }
        shouldThrow<IllegalStateException> { store.purge(now) }
    }

    @Test
    fun `lowering the configured ceiling counts entries above the new slot range`() {
        val a = applicant()
        val b = applicant()
        val c = applicant()
        join(a)
        join(b)
        manage().execute(a, ManageBetaWaitlist.Action.WITHDRAW)
        // Place 1 is free, but place 2 still consumes the whole one-entry budget.
        join(c, BetaWaitlistPolicy(maximum = 1)) shouldBe ManageBetaWaitlist.Result.Unavailable
        count("beta_waitlist") shouldBe 1
        manage().execute(b, ManageBetaWaitlist.Action.WITHDRAW)
        join(c, BetaWaitlistPolicy(maximum = 1)).shouldBeInstanceOf<ManageBetaWaitlist.Result.Waiting>()
    }
}
