package email.testinbox.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.api.account.HumanSessionCookies
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.ChangeAccountState
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.application.usecase.RevokeAccountSessions
import email.testinbox.application.usecase.RevokeApiKey
import email.testinbox.domain.identity.AccountLifecycleAction
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.persistence.JdbcAccountLifecycle
import email.testinbox.persistence.JdbcApiKeyRepository
import email.testinbox.persistence.JdbcHumanSessions
import email.testinbox.persistence.JdbcWorkspaceQuotaState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** The HTTP contract of account lifecycle: a suspended account's sessions and keys get `401`; resume revives neither. */
@TestPropertySource(
    properties = [
        "testinbox.accounts.enabled=true", "testinbox.accounts.public-origin=https://console.example.invalid",
        "testinbox.limits.wait.capacity=2", "testinbox.limits.wait.refill-per-second=0.01",
    ],
)
class AccountLifecycleApiTest : ApiIntegrationTestBase() {
    // Same context as HumanProductApiTest: this suite proves lifecycle authorization, not capacity.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    lateinit var capacity: email.testinbox.application.storage.CohortStorageReadiness

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var sessions: JdbcHumanSessions

    @Autowired lateinit var transactions: TransactionRunner

    private val json = jacksonObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @BeforeEach
    fun openCohort() {
        org.mockito.Mockito
            .`when`(capacity.ready())
            .thenReturn(true)
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open=true,capacity_ready=true").update()
    }

    @AfterEach
    fun closeCohort() {
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open=false,capacity_ready=false").update()
        jdbc.sql("DELETE FROM storage_admission_latch WHERE reason='incompatible cohort storage node'").update()
    }

    /** The operator use case with this suite's (owner) credentials: the authority the offline command uses. */
    private val operator by lazy {
        ChangeAccountState(
            JdbcAccountLifecycle(jdbc),
            JdbcWorkspaceQuotaState(jdbc),
            RevokeAccountSessions(sessions, transactions),
            RevokeApiKey(JdbcApiKeyRepository(jdbc), Clock.systemUTC()),
            transactions,
            Clock.systemUTC(),
        )
    }

    private fun account(): HumanAccountId {
        val id = HumanAccountId(UUID.randomUUID())
        jdbc
            .sql("INSERT INTO human_account VALUES (:id,:email,now(),'ACTIVE',now())")
            .param("id", id.value)
            .param("email", "$id@example.invalid")
            .update()
        return id
    }

    private fun session(id: HumanAccountId) =
        checkNotNull(
            IssueHumanSession(
                sessions,
                transactions,
                email.testinbox.application.Clocks
                    .utcMicros(),
            ).execute(id),
        )

    private fun call(
        path: String,
        method: String = "GET",
        session: IssueHumanSession.Issued? = null,
        body: String? = null,
        bearer: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(url(path))).timeout(Duration.ofSeconds(12))
        request.header("Content-Type", "application/json")
        session?.let {
            request.header("Cookie", "${HumanSessionCookies.NAME}=${it.credential.value}")
            request.header("X-CSRF-Token", HumanSessionCookies.csrf(it.credential))
        }
        request.header("Origin", ORIGIN)
        bearer?.let { request.header("Authorization", "Bearer $it") }
        request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun mintKey(session: IssueHumanSession.Issued): HttpResponse<String> =
        call("/account/product/api-keys", "POST", session, """{"name":"SDK","scopes":["inboxes:write","messages:read"]}""")

    private fun keyOf(response: HttpResponse<String>): String {
        response.statusCode() shouldBe 201
        return json.readTree(response.body())["key"].asText()
    }

    @Autowired lateinit var registry: io.micrometer.core.instrument.MeterRegistry

    @Test
    fun `while restore-quarantined a legacy key gets 401 counted QUARANTINED, and the gauge reports it`() {
        fun gauge() = registry.get(email.testinbox.api.ops.RestoreQuarantineGauge.NAME).gauge().value()

        fun quarantined() =
            registry
                .get("testinbox_api_key_auth_total")
                .tag("outcome", "QUARANTINED")
                .counter()
                .count()
        get("/v1/workspace").statusCode.value() shouldBe 200
        gauge() shouldBe 0.0
        val before = quarantined()
        jdbc.sql("DELETE FROM lifecycle_restore_clearance").update()
        try {
            gauge() shouldBe 1.0
            get("/v1/workspace").statusCode.value() shouldBe 401
            quarantined() shouldBe before + 1
        } finally {
            jdbc.sql("INSERT INTO lifecycle_restore_clearance (id) VALUES (1) ON CONFLICT DO NOTHING").update()
        }
        gauge() shouldBe 0.0
        get("/v1/workspace").statusCode.value() shouldBe 200
    }

    @Test
    fun `a suspended account's session and keys get 401, resume revives neither, a fresh login and key work`() {
        val id = account()
        val first = session(id)
        val second = session(id)
        call("/account/admission", "POST", first).statusCode() shouldBe 200
        val key = keyOf(mintKey(first))
        call("/v1/workspace", bearer = key).statusCode() shouldBe 200
        call("/account/product/workspace", session = second).statusCode() shouldBe 200

        operator.execute(id, AccountLifecycleAction.SUSPEND).shouldBeInstanceOf<ChangeAccountState.Result.Done>()
        call("/v1/workspace", bearer = key).statusCode() shouldBe 401
        listOf(first, second).forEach { s ->
            call("/account/product/workspace", session = s).statusCode() shouldBe 401
            call("/account/session", session = s).statusCode() shouldBe 401
            call("/account/product/inboxes", "POST", s, "{}").statusCode() shouldBe 401
        }
        // The owner's own monitoring key keeps working: lifecycle never touches legacy workspaces.
        call("/v1/workspace", bearer = adminKey).statusCode() shouldBe 200

        operator.execute(id, AccountLifecycleAction.RESUME).shouldBeInstanceOf<ChangeAccountState.Result.Done>()
        call("/v1/workspace", bearer = key).statusCode() shouldBe 401
        call("/account/product/workspace", session = first).statusCode() shouldBe 401
        val fresh = session(id)
        call("/account/product/workspace", session = fresh).statusCode() shouldBe 200
        call("/v1/workspace", bearer = keyOf(mintKey(fresh))).statusCode() shouldBe 200
    }

    @Test
    fun `signing in to another account and suspending one account leave other sessions alone`() {
        val a = account()
        val b = account()
        val aSession = session(a)
        val bSession = session(b)
        call("/account/admission", "POST", aSession).statusCode() shouldBe 200
        call("/account/admission", "POST", bSession).statusCode() shouldBe 200
        // Multiple-session policy: a new sign-in elsewhere revokes nothing.
        val aSecond = session(a)
        call("/account/product/workspace", session = aSession).statusCode() shouldBe 200
        call("/account/product/workspace", session = aSecond).statusCode() shouldBe 200
        operator.execute(b, AccountLifecycleAction.SUSPEND).shouldBeInstanceOf<ChangeAccountState.Result.Done>()
        call("/account/product/workspace", session = bSession).statusCode() shouldBe 401
        call("/account/product/workspace", session = aSession).statusCode() shouldBe 200
        // Logout revokes exactly the presented session.
        call("/account/logout", "POST", aSession).statusCode() shouldBe 204
        call("/account/product/workspace", session = aSession).statusCode() shouldBe 401
        call("/account/product/workspace", session = aSecond).statusCode() shouldBe 200
    }

    @Test
    fun `a participant workspace mints at most twenty usable keys and is told its own inventory`() {
        val id = account()
        val s = session(id)
        call("/account/admission", "POST", s).statusCode() shouldBe 200
        repeat(20) { keyOf(mintKey(s)) }
        val refused = mintKey(s)
        refused.statusCode() shouldBe 409
        refused.headers().firstValue("Retry-After").isPresent shouldBe false
        val problem = json.readTree(refused.body())
        problem["type"].asText() shouldBe "https://testinbox.email/problems/quota-exceeded"
        problem["quota"].asText() shouldBe "API_KEYS_USABLE"
        problem["limit"].asLong() shouldBe 20
        problem["current"].asLong() shouldBe 20
        refused.body() shouldContain "API_KEYS_USABLE"
    }

    private companion object {
        const val ORIGIN = "https://console.example.invalid"
    }
}
