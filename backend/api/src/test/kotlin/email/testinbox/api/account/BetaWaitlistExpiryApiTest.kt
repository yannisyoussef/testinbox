package email.testinbox.api.account

import email.testinbox.api.ApiIntegrationTestBase
import email.testinbox.application.Clocks
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.persistence.JdbcHumanSessions
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * TI-DELIVERY-002b decision 5: the configured 30-day waitlist retention is
 * enforced end to end in the running API, by the scheduled maintenance task
 * (registered with the scheduler, no sign-up traffic needed) and by the
 * request path, against real PostgreSQL. Same context as the other account suites.
 */
@TestPropertySource(
    properties = [
        "testinbox.accounts.enabled=true", "testinbox.accounts.public-origin=https://console.example.invalid",
        "testinbox.limits.wait.capacity=2", "testinbox.limits.wait.refill-per-second=0.01",
    ],
)
class BetaWaitlistExpiryApiTest : ApiIntegrationTestBase() {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    lateinit var capacity: email.testinbox.application.storage.CohortStorageReadiness

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var context: ApplicationContext

    @Autowired lateinit var properties: BetaWaitlistProperties

    @Autowired lateinit var maintenance: BetaWaitlistMaintenance

    @Autowired lateinit var sessions: JdbcHumanSessions

    private val created = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        created.forEach { jdbc.sql("DELETE FROM human_account WHERE id = ?").param(it).update() }
    }

    private fun applicant(): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO human_account VALUES (?, ?, now(), 'ACTIVE', now())").params(id, "$id@example.invalid").update()
        created += id
        return id
    }

    /** An entry that joined [daysAgo] days ago under the configured retention. */
    private fun waiting(daysAgo: Long): UUID {
        val id = applicant()
        val retention = properties.retention.seconds
        jdbc
            .sql(
                """
                INSERT INTO beta_waitlist (account_id, place, joined_at, expires_at)
                SELECT ?, (SELECT min(s) FROM generate_series(1, 1000) s WHERE NOT EXISTS (SELECT 1 FROM beta_waitlist WHERE place = s)),
                       now() - make_interval(days => ?), now() - make_interval(days => ?) + make_interval(secs => ?)
                """.trimIndent(),
            ).params(id, daysAgo.toInt(), daysAgo.toInt(), retention.toDouble())
            .update()
        return id
    }

    private fun waitingCount(id: UUID): Long =
        jdbc
            .sql("SELECT count(*) FROM beta_waitlist WHERE account_id = ?")
            .param(id)
            .query(Long::class.java)
            .single()

    @Test
    fun `the shipped retention is thirty days and the purge is a registered scheduled task`() {
        properties.retention shouldBe Duration.ofDays(30)
        val tasks = context.getBeansOfType(ScheduledTaskHolder::class.java).values.flatMap { it.scheduledTasks }
        val purge =
            tasks.single {
                it.task.runnable
                    .toString()
                    .endsWith("${BetaWaitlistMaintenance::class.java.simpleName}.run")
            }
        (purge.task as FixedDelayTask).intervalDuration shouldBe Duration.ofMinutes(1)
    }

    @Test
    fun `the scheduled purge removes entries older than the retention and keeps younger ones`() {
        val old = waiting(daysAgo = 31)
        val young = waiting(daysAgo = 29)
        maintenance.run()
        waitingCount(old) shouldBe 0
        waitingCount(young) shouldBe 1
    }

    @Test
    fun `any applicant's waitlist request also purges expired entries`() {
        val old = waiting(daysAgo = 31)
        val reader = applicant()
        val session = checkNotNull(IssueHumanSession(sessions, transactions(), Clocks.utcMicros()).execute(HumanAccountId(reader)))
        val response =
            HttpClient.newHttpClient().send(
                HttpRequest
                    .newBuilder(URI(url("/account/waitlist")))
                    .header("Cookie", "${HumanSessionCookies.NAME}=${session.credential.value}")
                    .header("Origin", "https://console.example.invalid")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        response.statusCode() shouldBe 200
        waitingCount(old) shouldBe 0
    }

    private fun transactions() = context.getBean(email.testinbox.application.port.TransactionRunner::class.java)
}
