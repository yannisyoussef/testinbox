package email.testinbox.persistence

import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.domain.identity.BetaParticipantCap
import email.testinbox.domain.identity.HumanAccountId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * M0: the configured participant cap N (`testinbox.beta.max-participants`)
 * through the real use case, independent adapters and PostgreSQL row locks.
 * The database guard stays at thirty; N is enforced by the use case after the
 * cohort lock it already takes. Synchronization is by latches and by observing
 * PostgreSQL lock waiters, never by sleeping.
 */
class BetaParticipantCapTest : PersistenceIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var transactions: TransactionRunner

    private val clock =
        email.testinbox.application.Clocks
            .utcMicros()

    private val ten = BetaParticipantCap(10)

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("DELETE FROM human_account").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open = true, capacity_ready = true").update()
    }

    @AfterEach
    fun close() {
        jdbc.sql("UPDATE beta_cohort SET admissions_open = false, capacity_ready = false").update()
    }

    private fun admission(
        cap: BetaParticipantCap,
        store: BetaAdmissions = JdbcBetaAdmissions(jdbc),
    ) = AdmitBetaParticipant(
        store,
        transactions,
        clock,
        email.testinbox.application.storage.CohortStorageReadiness {
            true
        },
        cap,
    )

    private fun applicant(): HumanAccountId {
        val id = HumanAccountId(UUID.randomUUID())
        jdbc
            .sql(
                "INSERT INTO human_account (id, contact_email, contact_verified_at, state, created_at) VALUES (:id, :email, now(), 'ACTIVE', now())",
            ).param("id", id.value)
            .param("email", "${id.value}@example.invalid")
            .update()
        return id
    }

    private fun admitted(
        cap: BetaParticipantCap,
        id: HumanAccountId = applicant(),
    ) = admission(cap).execute(id).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()

    private fun count(table: String): Long = jdbc.sql("SELECT count(*) FROM $table").query(Long::class.javaObjectType).single()

    /** Backends of this database blocked on a heavyweight lock (here: the cohort row). */
    private fun awaitLockWaiters(expected: Int) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(20)).until {
            jdbc
                .sql("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'")
                .query(Int::class.javaObjectType)
                .single() >= expected
        }
    }

    @Test
    fun `N = 10 admits ten and refuses the eleventh with the same Full outcome as thirty, spending nothing`() {
        val places = List(10) { admitted(ten).admission.place }
        places shouldBe (1..10).toList()
        val workspaces = count("workspace")
        admission(ten).execute(applicant()) shouldBe AdmitBetaParticipant.Result.Full
        count("beta_admission") shouldBe 10
        count("participant_workspace") shouldBe 10
        count("workspace") shouldBe workspaces
    }

    @Test
    fun `the cap does not change precedence - closed and ineligible answer as before, a full cohort still lets participants in`() {
        val participant = applicant()
        val original = admitted(ten, participant).admission
        repeat(9) { admitted(ten) }
        jdbc.sql("UPDATE beta_cohort SET admissions_open = false").update()
        admission(ten).execute(applicant()) shouldBe AdmitBetaParticipant.Result.Closed
        admission(ten).execute(HumanAccountId(UUID.randomUUID())) shouldBe AdmitBetaParticipant.Result.Ineligible
        admitted(ten, participant).let {
            it.newlyAdmitted shouldBe false
            it.admission shouldBe original
        }
    }

    @Test
    fun `lowering N below the admitted count evicts nobody and only stops new admissions`() {
        val participants = List(12) { applicant() }
        val originals = participants.map { admitted(BetaParticipantCap.HARD_LIMIT, it).admission }
        // The operator lowers N to 10 with 12 places already taken.
        participants.zip(originals).forEach { (id, original) ->
            val again = admitted(ten, id)
            again.newlyAdmitted shouldBe false
            again.admission shouldBe original
        }
        admission(ten).execute(applicant()) shouldBe AdmitBetaParticipant.Result.Full
        count("beta_admission") shouldBe 12
        count("participant_workspace") shouldBe 12
        // Raising it back resumes at the next unconsumed place; nothing was recycled.
        admitted(BetaParticipantCap.HARD_LIMIT).admission.place shouldBe 13
    }

    @Test
    fun `at N - 1, a second admission waits on the cohort lock while the first is between its place read and insert`() {
        repeat(9) { admitted(ten) }
        val first = applicant()
        val second = applicant()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = JdbcBetaAdmissions(jdbc)
        // The first admission reads place 10 and then stops BEFORE inserting it: the window a race would need.
        val held =
            object : BetaAdmissions by store {
                override fun nextPlace(): Int? {
                    val place = store.nextPlace()
                    inside.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                    return place
                }
            }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val one = executor.submit<AdmitBetaParticipant.Result> { admission(ten, held).execute(first) }
            check(inside.await(20, TimeUnit.SECONDS))
            val two = executor.submit<AdmitBetaParticipant.Result> { admission(ten, JdbcBetaAdmissions(jdbc)).execute(second) }
            awaitLockWaiters(1) // the second admission is queued on the cohort row the first holds
            two.isDone shouldBe false
            release.countDown()
            one
                .get(20, TimeUnit.SECONDS)
                .shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()
                .admission.place shouldBe 10
            two.get(20, TimeUnit.SECONDS) shouldBe AdmitBetaParticipant.Result.Full
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        count("beta_admission") shouldBe 10
        count("participant_workspace") shouldBe 10
    }

    @Test
    fun `at N - 1, two admissions released together behind a held cohort lock admit exactly one`() {
        repeat(9) { admitted(ten) }
        val applicants = List(2) { applicant() }
        val workspaces = count("workspace")
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            // A third transaction holds the cohort row, so both admissions are parked on it before either reads a place.
            val holder =
                executor.submit {
                    transactions.required {
                        jdbc.sql("SELECT id FROM beta_cohort FOR UPDATE").query(String::class.java).list()
                        locked.countDown()
                        check(release.await(20, TimeUnit.SECONDS))
                    }
                }
            check(locked.await(20, TimeUnit.SECONDS))
            val results = applicants.map { id -> executor.submit<AdmitBetaParticipant.Result> { admission(ten).execute(id) } }
            awaitLockWaiters(2)
            release.countDown()
            holder.get(20, TimeUnit.SECONDS)
            val outcomes = results.map { it.get(20, TimeUnit.SECONDS) }
            outcomes.count { it is AdmitBetaParticipant.Result.Admitted } shouldBe 1
            outcomes.count { it == AdmitBetaParticipant.Result.Full } shouldBe 1
            outcomes
                .filterIsInstance<AdmitBetaParticipant.Result.Admitted>()
                .single()
                .admission.place shouldBe 10
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        count("beta_admission") shouldBe 10
        count("workspace") shouldBe workspaces + 1
    }
}
