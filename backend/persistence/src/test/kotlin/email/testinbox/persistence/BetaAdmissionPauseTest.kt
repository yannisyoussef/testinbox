package email.testinbox.persistence

import email.testinbox.application.port.BetaAdmissionControls
import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.application.usecase.ControlBetaAdmissions
import email.testinbox.application.usecase.ManageBetaWaitlist
import email.testinbox.domain.identity.BetaCohortPolicy
import email.testinbox.domain.identity.BetaWaitlistPolicy
import email.testinbox.domain.identity.HumanAccountId
import io.kotest.assertions.throwables.shouldThrow
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
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The operator admission pause and the thirty-place cap, through the real use
 * cases, independent adapters and PostgreSQL row locks. Synchronization is by
 * latches and by observing PostgreSQL lock waiters, never by sleeping.
 */
class BetaAdmissionPauseTest : PersistenceIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var transactions: TransactionRunner

    @Autowired lateinit var waitlistStore: JdbcBetaWaitlist

    private val clock =
        email.testinbox.application.Clocks
            .utcMicros()

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

    private fun admission(store: BetaAdmissions = JdbcBetaAdmissions(jdbc)) =
        AdmitBetaParticipant(
            store,
            transactions,
            clock,
            email.testinbox.application.storage.CohortStorageReadiness {
                true
            },
        )

    private fun control(controls: BetaAdmissionControls = JdbcBetaAdmissionControls(jdbc)) = ControlBetaAdmissions(controls, transactions)

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

    private fun count(table: String): Long = jdbc.sql("SELECT count(*) FROM $table").query(Long::class.javaObjectType).single()

    private fun flags(): Pair<Boolean, Boolean> =
        jdbc
            .sql("SELECT admissions_open, capacity_ready FROM beta_cohort")
            .query { rs, _ -> rs.getBoolean(1) to rs.getBoolean(2) }
            .single()

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
    fun `a pause waits for an in-flight admission and no admission commits after it`() {
        val first = applicant()
        val second = applicant()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = JdbcBetaAdmissions(jdbc)
        val held =
            object : BetaAdmissions by store {
                override fun nextPlace(): Int? {
                    inside.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                    return store.nextPlace()
                }
            }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admitted = executor.submit<AdmitBetaParticipant.Result> { admission(held).execute(first) }
            check(inside.await(20, TimeUnit.SECONDS))
            val paused = executor.submit<ControlBetaAdmissions.Transition> { control().pause() }
            awaitLockWaiters(1) // the pause is queued on the cohort row the admission holds
            paused.isDone shouldBe false
            release.countDown()
            admitted.get(20, TimeUnit.SECONDS).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>().newlyAdmitted shouldBe true
            val transition = paused.get(20, TimeUnit.SECONDS)
            transition.before.admissionsOpen shouldBe true
            transition.after.admissionsOpen shouldBe false
            transition.after.consumedPlaces shouldBe 1
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        admission().execute(second) shouldBe AdmitBetaParticipant.Result.Closed
        admission().execute(first).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>().newlyAdmitted shouldBe false
        count("beta_admission") shouldBe 1
    }

    @Test
    fun `admissions queued behind a committing pause all answer closed and spend nothing`() {
        val applicants = List(6) { applicant() }
        val workspaces = count("workspace")
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controls = JdbcBetaAdmissionControls(jdbc)
        val held =
            object : BetaAdmissionControls by controls {
                override fun setAdmissionsOpen(open: Boolean) {
                    controls.setAdmissionsOpen(open)
                    inside.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                }
            }
        val executor = Executors.newFixedThreadPool(applicants.size + 1)
        try {
            val paused = executor.submit<ControlBetaAdmissions.Transition> { control(held).pause() }
            check(inside.await(20, TimeUnit.SECONDS))
            val results: List<Future<AdmitBetaParticipant.Result>> =
                applicants.map { id -> executor.submit<AdmitBetaParticipant.Result> { admission().execute(id) } }
            awaitLockWaiters(applicants.size)
            release.countDown()
            paused.get(20, TimeUnit.SECONDS).changed shouldBe true
            results.map { it.get(20, TimeUnit.SECONDS) } shouldBe List(applicants.size) { AdmitBetaParticipant.Result.Closed }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        count("beta_admission") shouldBe 0
        count("participant_workspace") shouldBe 0
        count("workspace") shouldBe workspaces
    }

    @Test
    fun `pause keeps participants and the waitlist working and never touches capacity or places`() {
        val participants = List(3) { applicant() }
        val originals = participants.map { admission().execute(it).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>().admission }
        val waiting = applicant()
        val waitlist = ManageBetaWaitlist(JdbcBetaAdmissions(jdbc), waitlistStore, transactions, clock)
        waitlist
            .execute(
                waiting,
                ManageBetaWaitlist.Action.JOIN,
                BetaWaitlistPolicy(),
            ).shouldBeInstanceOf<ManageBetaWaitlist.Result.Waiting>()

        val paused = control().pause()
        paused.after shouldBe ControlBetaAdmissions.Status(admissionsOpen = false, capacityReady = true, consumedPlaces = 3)
        control().pause().changed shouldBe false // idempotent
        flags() shouldBe (false to true)

        // Existing participants keep their original workspace; nothing is reissued.
        participants.zip(originals).forEach { (id, original) ->
            val again = admission().execute(id).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()
            again.newlyAdmitted shouldBe false
            again.admission shouldBe original
        }
        // The waitlist stays readable and joinable; a new applicant gets the generic closed outcome.
        waitlist.execute(waiting, ManageBetaWaitlist.Action.READ).shouldBeInstanceOf<ManageBetaWaitlist.Result.Waiting>()
        val late = applicant()
        admission().execute(late) shouldBe AdmitBetaParticipant.Result.Closed
        waitlist.execute(late, ManageBetaWaitlist.Action.JOIN, BetaWaitlistPolicy()).shouldBeInstanceOf<ManageBetaWaitlist.Result.Waiting>()
        count("beta_admission") shouldBe 3

        val resumed = control().resume()
        resumed.changed shouldBe true
        resumed.after.admissionsOpen shouldBe true
        flags() shouldBe (true to true)
        admission()
            .execute(waiting)
            .shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()
            .admission.place shouldBe 4
    }

    @Test
    fun `pausing never opens storage-budget changes on its own and status needs no write authority`() {
        control().pause().after.poolChangesAllowed shouldBe false // capacity_ready is still true
        control().status() shouldBe ControlBetaAdmissions.Status(admissionsOpen = false, capacityReady = true, consumedPlaces = 0)
        jdbc.sql("UPDATE beta_cohort SET capacity_ready = false").update()
        control().status().poolChangesAllowed shouldBe true
        control().resume().after.poolChangesAllowed shouldBe false
    }

    @Test
    fun `an operator write without the database privilege is refused inside the transaction`() {
        val denied =
            object : BetaAdmissionControls by JdbcBetaAdmissionControls(jdbc) {
                override fun operatorAuthorized(): Boolean = false

                override fun setAdmissionsOpen(open: Boolean): Unit = error("must not be reached")
            }
        shouldThrow<ControlBetaAdmissions.NotAuthorized> { control(denied).pause() }
        flags() shouldBe (true to true)
        shouldThrow<IllegalStateException> { JdbcBetaAdmissionControls(jdbc).lockPolicy() }
        shouldThrow<IllegalStateException> { JdbcBetaAdmissionControls(jdbc).setAdmissionsOpen(false) }
    }

    @Test
    fun `forty applicants retrying concurrently through independent adapters fill exactly thirty places`() {
        val applicants = List(40) { applicant() }
        val workspaces = count("workspace")
        // Every applicant twice: a double-submitted button or a retried callback.
        val attempts = (applicants + applicants).shuffled()
        val ready = CountDownLatch(attempts.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(attempts.size)
        val results =
            try {
                val futures =
                    attempts.map { id ->
                        executor.submit<Pair<HumanAccountId, AdmitBetaParticipant.Result>> {
                            val useCase = admission(JdbcBetaAdmissions(jdbc))
                            ready.countDown()
                            check(start.await(20, TimeUnit.SECONDS))
                            id to useCase.execute(id)
                        }
                    }
                check(ready.await(20, TimeUnit.SECONDS))
                start.countDown()
                futures.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
        val admitted = results.filter { it.second is AdmitBetaParticipant.Result.Admitted }
        val byAccount = admitted.groupBy({ it.first }, { (it.second as AdmitBetaParticipant.Result.Admitted).admission })
        byAccount.size shouldBe BetaCohortPolicy.MAX_PARTICIPANTS
        byAccount.values.forEach { it.distinct().size shouldBe 1 } // a retry returns the original admission
        admitted.count { (it.second as AdmitBetaParticipant.Result.Admitted).newlyAdmitted } shouldBe BetaCohortPolicy.MAX_PARTICIPANTS
        byAccount.values
            .map { it.first().place }
            .sorted() shouldBe (1..BetaCohortPolicy.MAX_PARTICIPANTS).toList()
        results.filter { it.first !in byAccount.keys }.forEach { it.second shouldBe AdmitBetaParticipant.Result.Full }
        count("beta_admission") shouldBe BetaCohortPolicy.MAX_PARTICIPANTS.toLong()
        count("participant_workspace") shouldBe BetaCohortPolicy.MAX_PARTICIPANTS.toLong()
        count("workspace") shouldBe workspaces + BetaCohortPolicy.MAX_PARTICIPANTS
    }
}
