package email.testinbox.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.api.account.HumanSessionCookies
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.persistence.JdbcHumanSessions
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** Cookies are supplied explicitly over the disposable HTTP transport; production cookies are Secure. */
@TestPropertySource(properties = ["testinbox.accounts.enabled=true", "testinbox.accounts.public-origin=https://console.example.invalid"])
class HumanAccountsApiTest : ApiIntegrationTestBase() {
    // This suite proves human HTTP authorization, not capacity feasibility.
    // The native ALL rehearsal exercises the real, unstubbed capacity bean.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    lateinit var capacity: email.testinbox.application.storage.CohortStorageReadiness

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var sessions: JdbcHumanSessions

    @Autowired lateinit var transactions: TransactionRunner
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = jacksonObjectMapper()

    @BeforeEach
    fun closeCohort() {
        org.mockito.Mockito
            .`when`(capacity.ready())
            .thenReturn(true)
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open=false, capacity_ready=false").update()
    }

    @org.junit.jupiter.api.AfterEach
    fun clearFixtureAdmission() {
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open=false,capacity_ready=false").update()
        jdbc.sql("DELETE FROM storage_admission_latch WHERE reason='incompatible cohort storage node'").update()
    }

    private fun login(): Pair<HumanAccountId, IssueHumanSession.Issued> {
        val id = HumanAccountId(UUID.randomUUID())
        jdbc
            .sql(
                """
                INSERT INTO human_account (id, contact_email, contact_verified_at, state, created_at)
                VALUES (:id, :email, now(), 'ACTIVE', now())
                """.trimIndent(),
            ).param("id", id.value)
            .param("email", "${id.value}@example.invalid")
            .update()
        return id to
            checkNotNull(
                IssueHumanSession(
                    sessions,
                    transactions,
                    email.testinbox.application.Clocks
                        .utcMicros(),
                ).execute(id),
            )
    }

    private fun call(
        path: String,
        method: String = "GET",
        session: IssueHumanSession.Issued? = null,
        origin: String? = null,
        csrf: String? = null,
        body: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(url(path))).timeout(Duration.ofSeconds(10))
        session?.let { request.header("Cookie", "${HumanSessionCookies.NAME}=${it.credential.value}") }
        origin?.let { request.header("Origin", it) }
        csrf?.let { request.header(HumanSessionCookies.CSRF_HEADER, it) }
        extra.forEach { (name, value) -> request.header(name, value) }
        request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(
        path: String,
        session: IssueHumanSession.Issued,
        body: String? = null,
    ): HttpResponse<String> =
        call(path, "POST", session, "https://console.example.invalid", HumanSessionCookies.csrf(session.credential), body)

    private fun assertPrivate(response: HttpResponse<String>) {
        response.headers().firstValue("Cache-Control").orElse("") shouldBe "no-store"
        response.headers().firstValue("Access-Control-Allow-Origin").isPresent shouldBe false
    }

    @Test
    fun `session and API key authority are separate in both directions`() {
        val (_, session) = login()
        val response = call("/account/session", session = session)
        response.statusCode() shouldBe 200
        assertPrivate(response)
        val view = json.readTree(response.body())
        view["admission"].asText() shouldBe "NOT_ADMITTED"
        view.has("workspaceId") shouldBe false
        view.has("projectId") shouldBe false
        response.body().contains(session.credential.value) shouldBe false
        view["csrfToken"].asText() shouldBe HumanSessionCookies.csrf(session.credential)
        call("/account/session", extra = mapOf("Authorization" to "Bearer $adminKey")).statusCode() shouldBe 401
        call("/v1/workspace", session = session).statusCode() shouldBe 401
        call("/v1/workspace", extra = mapOf("Authorization" to "Bearer $adminKey")).statusCode() shouldBe 200
    }

    @Test
    fun `a cookie resolves its own account despite foreign account and workspace headers`() {
        val (first, _) = login()
        val (second, session) = login()
        val response =
            call(
                "/account/session?accountId=${first.value}",
                session = session,
                extra = mapOf("X-Workspace-Id" to UUID.randomUUID().toString(), "X-Account-Id" to first.value.toString()),
            )
        response.statusCode() shouldBe 200
        json.readTree(response.body())["contactEmail"].asText() shouldBe "${second.value}@example.invalid"
    }

    @Test
    fun `duplicate or missing cookies never select a principal and errors remain private`() {
        val (_, first) = login()
        val (_, second) = login()
        val duplicate =
            call(
                "/account/session",
                extra =
                    mapOf(
                        "Cookie" to
                            "${HumanSessionCookies.NAME}=${first.credential.value}; ${HumanSessionCookies.NAME}=${second.credential.value}",
                    ),
            )
        duplicate.statusCode() shouldBe 401
        assertPrivate(duplicate)
        val absent = call("/account/session")
        absent.statusCode() shouldBe 401
        assertPrivate(absent)
    }

    @Test
    fun `missing wrong and another session CSRF tokens cannot admit or log out`() {
        val (_, session) = login()
        val (_, other) = login()
        listOf(null, "wrong", HumanSessionCookies.csrf(other.credential), session.credential.value).forEach { csrf ->
            listOf("/account/admission", "/account/logout").forEach { path ->
                val response = call(path, "POST", session, "https://console.example.invalid", csrf)
                response.statusCode() shouldBe 403
                assertPrivate(response)
            }
        }
        call("/account/session", session = session).statusCode() shouldBe 200
        jdbc.sql("SELECT count(*) FROM beta_admission").query(Long::class.javaObjectType).single() shouldBe 0
    }

    @Test
    fun `the exact configured origin is required regardless of forwarded host headers`() {
        val (_, session) = login()
        listOf(
            null,
            "null",
            "https://console.example.invalid.evil.invalid",
            "http://console.example.invalid",
            "https://console.example.invalid/",
            "https://evil.invalid",
        ).forEach { origin ->
            val response =
                call(
                    "/account/admission",
                    "POST",
                    session,
                    origin,
                    HumanSessionCookies.csrf(session.credential),
                    extra = mapOf("X-Forwarded-Host" to "console.example.invalid", "X-Forwarded-Proto" to "https"),
                )
            response.statusCode() shouldBe 403
            assertPrivate(response)
        }
        val duplicated =
            call(
                "/account/admission",
                "POST",
                session,
                "https://console.example.invalid",
                HumanSessionCookies.csrf(session.credential),
                extra = mapOf("Origin" to "https://evil.invalid"),
            )
        duplicated.statusCode() shouldBe 403
    }

    @Test
    fun `encoded and matrix paths cannot bypass the resolved controller origin boundary`() {
        val (_, session) = login()
        listOf("/account;probe/admission", "/%61ccount/admission").forEach { path ->
            val response = call(path, "POST", session, "https://evil.invalid", HumanSessionCookies.csrf(session.credential))
            response.statusCode() shouldBe 403
            assertPrivate(response)
        }
    }

    @Test
    fun `admission requires open policy and capacity then returns the same private membership`() {
        val (_, session) = login()
        json.readTree(post("/account/admission", session).body())["status"].asText() shouldBe "CLOSED"
        jdbc.sql("UPDATE beta_cohort SET admissions_open=true").update()
        json.readTree(post("/account/admission", session).body())["status"].asText() shouldBe "CAPACITY_UNAVAILABLE"
        jdbc.sql("UPDATE beta_cohort SET capacity_ready=true").update()
        org.mockito.Mockito
            .`when`(capacity.ready())
            .thenReturn(false)
        json.readTree(post("/account/admission", session).body())["status"].asText() shouldBe "CAPACITY_UNAVAILABLE"
        org.mockito.Mockito
            .`when`(capacity.ready())
            .thenReturn(true)
        val accepted = post("/account/admission", session)
        accepted.statusCode() shouldBe 200
        val membership = json.readTree(accepted.body())
        membership["status"].asText() shouldBe "ADMITTED"
        UUID.fromString(membership["workspaceId"].asText())
        UUID.fromString(membership["projectId"].asText())
        jdbc.sql("UPDATE beta_cohort SET admissions_open=false, capacity_ready=false").update()
        json.readTree(post("/account/admission", session).body()) shouldBe membership
        val view = json.readTree(call("/account/session", session = session).body())
        view["workspaceId"] shouldBe membership["workspaceId"]
        view["projectId"] shouldBe membership["projectId"]
        view["admission"].asText() shouldBe "ADMITTED"
    }

    @Test
    fun `client supplied provisioning fields and query parameters are refused`() {
        val (_, session) = login()
        post("/account/admission", session, """{"workspaceId":"${UUID.randomUUID()}","admissionsOpen":true}""").statusCode() shouldBe 400
        post("/account/admission?accountId=${UUID.randomUUID()}", session).statusCode() shouldBe 400
        post("/account/logout", session, "{}").statusCode() shouldBe 400
        call("/account/session", session = session).statusCode() shouldBe 200
    }

    @Test
    fun `logout expires the secure cookie revokes immediately and is idempotent`() {
        val (_, session) = login()
        val (_, other) = login()
        val response = post("/account/logout", session)
        response.statusCode() shouldBe 204
        assertPrivate(response)
        val cookie = response.headers().firstValue("Set-Cookie").orElseThrow()
        listOf("__Host-ti_session=", "Path=/", "Max-Age=0", "Secure", "HttpOnly", "SameSite=Lax")
            .forEach { cookie.contains(it) shouldBe true }
        cookie.contains("Domain", ignoreCase = true) shouldBe false
        call("/account/session", session = session).statusCode() shouldBe 401
        post("/account/logout", session).statusCode() shouldBe 204
        call("/account/logout", "POST", origin = "https://console.example.invalid").statusCode() shouldBe 204
        call("/account/session", session = other).statusCode() shouldBe 200
    }

    @Test
    fun `suspension takes effect on the next read and cannot be bypassed by admission`() {
        val (id, session) = login()
        jdbc.sql("UPDATE human_account SET state='SUSPENDED' WHERE id=:id").param("id", id.value).update()
        call("/account/session", session = session).statusCode() shouldBe 401
        post("/account/admission", session).statusCode() shouldBe 401
    }

    @Test
    fun `unknown account routes stay uncached without widening CORS`() {
        val response = call("/account/not-a-route")
        response.statusCode() shouldBe 404
        assertPrivate(response)
    }

    @Test
    fun `metadata hot polling is bounded across normalized routes while logout remains usable`() {
        val (id, session) = login()
        call("/account/session", session = session).statusCode() shouldBe 200
        jdbc
            .sql("UPDATE human_request_budget SET requests=60,window_start=now()+interval '1 minute' WHERE account_id=:id")
            .param("id", id.value)
            .update()
        for (path in listOf("/account/session", "/%61ccount/waitlist", "/account;probe/waitlist")) {
            val refused = call(path, session = session)
            refused.statusCode() shouldBe 429
            assertPrivate(refused)
            (
                refused
                    .headers()
                    .firstValue("Retry-After")
                    .orElseThrow()
                    .toLong() > 0
            ) shouldBe true
        }
        post("/account/admission", session).statusCode() shouldBe 429
        post("/account/logout", session).statusCode() shouldBe 204
        call("/account/session", session = session).statusCode() shouldBe 401
    }

    @Test
    fun `waitlist requires explicit opt in reveals only own state and never grants a workspace`() {
        val (account, session) = login()
        val before = jdbc.sql("SELECT count(*) FROM workspace").query(Long::class.javaObjectType).single()
        val read = call("/account/waitlist", session = session)
        read.statusCode() shouldBe 200
        json.readTree(read.body())["status"].asText() shouldBe "NOT_WAITING"
        val joined = post("/account/waitlist", session)
        joined.statusCode() shouldBe 200
        val view = json.readTree(joined.body())
        view["status"].asText() shouldBe "WAITING"
        view.fieldNames().asSequence().toSet() shouldBe setOf("status", "joinedAt", "expiresAt")
        joined.body().contains(account.value.toString()) shouldBe false
        assertPrivate(joined)
        post("/account/waitlist", session).body() shouldBe joined.body()
        call("/v1/workspace", session = session).statusCode() shouldBe 401
        jdbc
            .sql("SELECT count(*) FROM beta_admission WHERE account_id=:id")
            .param("id", account.value)
            .query(Long::class.javaObjectType)
            .single() shouldBe 0
        jdbc.sql("SELECT count(*) FROM workspace").query(Long::class.javaObjectType).single() shouldBe before
    }

    @Test
    fun `waitlist mutations enforce exact origin CSRF and refuse caller supplied account data`() {
        val (_, session) = login()
        for (method in listOf("POST", "DELETE")) {
            call("/account/waitlist", method, session).statusCode() shouldBe 403
            call("/account/waitlist", method, session, "https://evil.invalid", HumanSessionCookies.csrf(session.credential))
                .statusCode() shouldBe 403
            call("/account/waitlist", method, session, "https://console.example.invalid", "wrong").statusCode() shouldBe 403
            call("/account/waitlist", method, origin = "https://console.example.invalid").statusCode() shouldBe 401
        }
        post("/account/waitlist", session, """{"accountId":"${UUID.randomUUID()}"}""").statusCode() shouldBe 400
        post("/account/waitlist?email=other@example.invalid", session).statusCode() shouldBe 400
        call("/account/waitlist", extra = mapOf("Authorization" to "Bearer $adminKey")).statusCode() shouldBe 401
    }

    @Test
    fun `waitlist withdrawal cannot remove another account or extend expired participation`() {
        val (first, a) = login()
        val (second, b) = login()
        post("/account/waitlist", a).statusCode() shouldBe 200
        post("/account/waitlist", b).statusCode() shouldBe 200
        val deleted =
            call(
                "/account/waitlist",
                "DELETE",
                a,
                "https://console.example.invalid",
                HumanSessionCookies.csrf(a.credential),
                extra = mapOf("X-Account-Id" to second.value.toString()),
            )
        deleted.statusCode() shouldBe 204
        deleted.body() shouldBe ""
        jdbc
            .sql("SELECT count(*) FROM beta_waitlist WHERE account_id=:id")
            .param("id", first.value)
            .query(Long::class.javaObjectType)
            .single() shouldBe 0
        json.readTree(call("/account/waitlist", session = b).body())["status"].asText() shouldBe "WAITING"
        jdbc
            .sql(
                """
                UPDATE beta_waitlist SET joined_at=joined_at-interval '31 days',expires_at=expires_at-interval '31 days'
                WHERE account_id=:id
                """.trimIndent(),
            ).param("id", second.value)
            .update()
        json.readTree(call("/account/waitlist", session = b).body())["status"].asText() shouldBe "NOT_WAITING"
        post("/account/waitlist", b).statusCode() shouldBe 200
        json.readTree(call("/account/session", session = b).body())["admission"].asText() shouldBe "NOT_ADMITTED"
    }
}
