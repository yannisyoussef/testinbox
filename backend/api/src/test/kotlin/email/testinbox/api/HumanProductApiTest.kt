package email.testinbox.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.api.account.HumanSessionCookies
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.ReservedUpload
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.port.UploadOutcome
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.persistence.JdbcHumanSessions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@TestPropertySource(
    properties = [
        "testinbox.accounts.enabled=true", "testinbox.accounts.public-origin=https://console.example.invalid",
        "testinbox.limits.wait.capacity=2", "testinbox.limits.wait.refill-per-second=0.01",
    ],
)
class HumanProductApiTest : ApiIntegrationTestBase() {
    // This suite proves human HTTP authorization, not capacity feasibility.
    // The native ALL rehearsal exercises the real, unstubbed capacity bean.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    lateinit var capacity: email.testinbox.application.storage.CohortStorageReadiness

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var sessions: JdbcHumanSessions

    @Autowired lateinit var transactions: TransactionRunner

    @Autowired lateinit var blobs: BlobStore
    private val json = jacksonObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @BeforeEach
    fun resetCohort() {
        org.mockito.Mockito
            .`when`(capacity.ready())
            .thenReturn(true)
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open=true,capacity_ready=true").update()
    }

    @org.junit.jupiter.api.AfterEach
    fun clearFixtureAdmission() {
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open=false,capacity_ready=false").update()
        jdbc.sql("DELETE FROM storage_admission_latch WHERE reason='incompatible cohort storage node'").update()
    }

    private fun login(admit: Boolean = true): IssueHumanSession.Issued {
        val id = HumanAccountId(UUID.randomUUID())
        jdbc
            .sql("INSERT INTO human_account VALUES (:id,:email,now(),'ACTIVE',now())")
            .param("id", id.value)
            .param("email", "$id@example.invalid")
            .update()
        val session = checkNotNull(IssueHumanSession(sessions, transactions, Clock.systemUTC()).execute(id))
        if (admit) call("/account/admission", "POST", session).statusCode() shouldBe 200
        return session
    }

    private fun call(
        path: String,
        method: String = "GET",
        session: IssueHumanSession.Issued? = null,
        body: String? = null,
        extra: Map<String, String> = emptyMap(),
        withCsrf: Boolean = true,
        origin: String? = ORIGIN,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(url(path))).timeout(Duration.ofSeconds(12))
        request.header("Content-Type", "application/json")
        session?.let {
            request.header("Cookie", "${HumanSessionCookies.NAME}=${it.credential.value}")
            if (withCsrf) request.header("X-CSRF-Token", HumanSessionCookies.csrf(it.credential))
        }
        origin?.let { request.header("Origin", it) }
        extra.forEach { (name, value) -> request.header(name, value) }
        request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `only an admitted live cookie grants product authority and it never grants v1`() {
        val notAdmitted = login(false)
        val admitted = login()
        listOf("/account/product/workspace", "/%61ccount/product/workspace", "/account;probe/product/workspace").forEach { path ->
            call(path).statusCode() shouldBe 401
            call(path, extra = mapOf("Authorization" to "Bearer $adminKey")).statusCode() shouldBe 401
            call(path, session = notAdmitted).statusCode() shouldBe 401
            val response = call(path, session = admitted)
            response.statusCode() shouldBe 200
            response.headers().firstValue("Cache-Control").orElseThrow() shouldBe "no-store"
        }
        listOf("/v1/workspace", "/%761/workspace", "/v1;probe/workspace").forEach { path ->
            call(path, session = admitted).statusCode() shouldBe 401
        }
        call("/account/logout", "POST", admitted).statusCode() shouldBe 204
        call("/account/product/workspace", session = admitted).statusCode() shouldBe 401
    }

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    lateinit var handlerMapping: org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

    @Test
    fun `every human product operation authenticates before reading or mutating any resource`() {
        val operations =
            handlerMapping.handlerMethods.keys.flatMap { info ->
                info.pathPatternsCondition!!.patternValues.filter { it.startsWith("/account/product/") }.flatMap { path ->
                    info.methodsCondition.methods.map { it.name to path.replace(Regex("\\{[^}]+}"), UUID.randomUUID().toString()) }
                }
            }
        operations.size shouldBe 19
        operations.forEach { (method, path) ->
            call(
                path,
                method,
                body = if (method == "POST") "{}" else null,
                extra = mapOf("Authorization" to "Bearer $adminKey"),
            ).statusCode() shouldBe 401
        }
    }

    @Test
    fun `human event streams use the existing bounded stream handler without widening cache or credentials`() {
        val session = login()
        val stream = call("/account/product/workspace/events", session = session)
        stream.statusCode() shouldBe 200
        stream.headers().firstValue("Content-Type").orElseThrow() shouldContain "text/event-stream"
        stream.headers().firstValue("Cache-Control").orElseThrow() shouldBe "no-store"
        stream.body() shouldContain "event: end"
        stream.body().contains(session.credential.value) shouldBe false
    }

    @Test
    fun `product mutations require exact origin and CSRF before parsing bodies including normalized paths`() {
        val session = login()
        listOf("/account/product/inboxes", "/%61ccount/product/inboxes", "/account;probe/product/inboxes").forEach { path ->
            call(path, "POST", session, "malformed", withCsrf = false).statusCode() shouldBe 403
            call(path, "POST", session, "malformed", origin = null).statusCode() shouldBe 403
            call(path, "POST", session, "malformed", origin = "https://evil.invalid").statusCode() shouldBe 403
            call(path, "POST", session, "{}", extra = mapOf("X-CSRF-Token" to "wrong")).statusCode() shouldBe 403
            call(path, "POST", session, "{}").statusCode() shouldBe 201
        }
        val id = UUID.randomUUID()
        call("/account/product/inboxes/$id", "DELETE", session, withCsrf = false).statusCode() shouldBe 403
        call("/account/product/inboxes/$id/messages/wait", "POST", session, "{}", withCsrf = false).statusCode() shouldBe 403
        call("/account/product/api-keys", "POST", session, "{}", withCsrf = false).statusCode() shouldBe 403
    }

    @Test
    fun `private product creates replay safely and reject cross-workspace reads and deletes`() {
        val first = login()
        val second = login()
        val idem = mapOf("Idempotency-Key" to "human-inbox-${UUID.randomUUID()}")
        val created = call("/account/product/inboxes", "POST", first, "{}", idem)
        created.statusCode() shouldBe 201
        val inbox = json.readTree(created.body())
        val id = inbox["id"].asText()
        val replay = call("/account/product/inboxes", "POST", first, "{}", idem)
        replay.statusCode() shouldBe 201
        replay.headers().firstValue("Idempotency-Replayed").orElseThrow() shouldBe "true"
        json.readTree(replay.body())["id"].asText() shouldBe id
        listOf("", "/messages").forEach { suffix ->
            call("/account/product/inboxes/$id$suffix", session = second).statusCode() shouldBe 404
        }
        call("/account/product/inboxes/$id", "DELETE", second).statusCode() shouldBe 404
        val own = call("/account/product/inboxes/$id", session = first, extra = mapOf("X-Workspace-Id" to UUID.randomUUID().toString()))
        own.statusCode() shouldBe 200
        call("/account/product/inboxes/$id", "DELETE", first).statusCode() shouldBe 204
    }

    @Test
    fun `human key management has separate provenance one-time reveal and actor-bound replay`() {
        val session = login()
        val foreign = login()
        val idem = mapOf("Idempotency-Key" to "human-key-${UUID.randomUUID()}")
        val body = """{"name":"SDK","scopes":["api-keys:manage","messages:read","inboxes:write"],"expiresInSeconds":2592000}"""
        val created = call("/account/product/api-keys", "POST", session, body, idem)
        created.statusCode() shouldBe 201
        val key = json.readTree(created.body())
        val id = key["apiKey"]["id"].asText()
        val credential = key["key"].asText()
        credential.startsWith("ti_k1_") shouldBe true
        val replay = call("/account/product/api-keys", "POST", session, body, idem)
        replay.statusCode() shouldBe 409
        replay.body() shouldContain "idempotency-secret-not-replayable"
        replay.body().contains(credential) shouldBe false
        val machineReplay = post("/v1/api-keys", body, credential).statusCode.value()
        machineReplay shouldBe 201
        // A real machine credential from this workspace cannot claim the human's prior result.
        val response = call("/v1/api-keys", "POST", body = body, extra = idem + ("Authorization" to "Bearer $credential"))
        response.statusCode() shouldBe 409
        response.body() shouldContain "idempotency-key-reused"
        call("/account/product/api-keys/$id", session = foreign).statusCode() shouldBe 404
        call("/account/product/api-keys/$id", "DELETE", foreign).statusCode() shouldBe 404
        val metadata = call("/account/product/api-keys/$id", session = session)
        metadata.body().contains(credential) shouldBe false
        jdbc
            .sql("SELECT created_by_human_account_id IS NOT NULL AND created_by_api_key_id IS NULL FROM api_key WHERE id=:id")
            .param("id", UUID.fromString(id))
            .query(Boolean::class.javaObjectType)
            .single() shouldBe true
        call("/account/logout", "POST", session).statusCode() shouldBe 204
        get("/v1/workspace", credential).statusCode.value() shouldBe 200
    }

    @Test
    fun `human and machine requests share workspace budgets and unsupported idempotency is charged first`() {
        val session = login()
        val key =
            json
                .readTree(
                    call(
                        "/account/product/api-keys",
                        "POST",
                        session,
                        """{"scopes":["messages:read"]}""",
                    ).body(),
                )["key"]
                .asText()
        val path = "/account/product/inboxes/${UUID.randomUUID()}/messages/wait"
        val body = """{"timeoutSeconds":1}"""
        val idem = mapOf("Idempotency-Key" to "unsupported-human-${UUID.randomUUID()}")
        call(path, "POST", session, body, idem).statusCode() shouldBe 400
        call(
            path.replace("/account/product", "/v1"),
            "POST",
            body = body,
            extra = idem + ("Authorization" to "Bearer $key"),
        ).statusCode() shouldBe 400
        val refused = call(path, "POST", session, body, idem)
        refused.statusCode() shouldBe 429
        refused.headers().firstValue("Retry-After").isPresent shouldBe true
    }

    @Test
    fun `aliased handlers bound account JSON before parsing including chunked normalized input`() {
        val session = login()
        val oversized = "[" + "x".repeat(4096)
        call("/account/product/inboxes", "POST", session, oversized).statusCode() shouldBe 413
        val request =
            HttpRequest
                .newBuilder(URI(url("/%61ccount/product/api-keys")))
                .header("Content-Type", "application/json")
                .header("Origin", ORIGIN)
                .header("Cookie", "${HumanSessionCookies.NAME}=${session.credential.value}")
                .header("X-CSRF-Token", HumanSessionCookies.csrf(session.credential))
                .POST(HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(oversized.toByteArray()) })
                .build()
        client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 413
    }

    @Test
    fun `real stored messages and raw bytes are visible only within the human workspace`() {
        val session = login()
        val foreign = login()
        val inbox = json.readTree(call("/account/product/inboxes", "POST", session, "{}").body())
        val membership = json.readTree(call("/account/product/workspace", session = session).body())
        val message =
            appendVisibleMessage(
                InboxId(UUID.fromString(inbox["id"].asText())),
                inbox["address"].asText(),
                workspaceId = WorkspaceId(UUID.fromString(membership["id"].asText())),
            )
        val raw = "From: fixture@example.invalid\r\n\r\nhuman-private-raw"
        blobs.putReserved(ReservedUpload(message.rawObjectKey, raw.toByteArray(), Instant.now(), Duration.ofSeconds(120))) shouldBe
            UploadOutcome.Stored
        listOf("", "/raw", "/attachments").forEach { suffix ->
            call("/account/product/messages/${message.id}$suffix", session = session).statusCode() shouldBe 200
            call("/account/product/messages/${message.id}$suffix", session = foreign).statusCode() shouldBe 404
        }
        val response = call("/account/product/messages/${message.id}/raw", session = session)
        response.body() shouldBe raw
        response.headers().firstValue("X-Content-Type-Options").orElseThrow() shouldBe "nosniff"
        call(
            "/account/product/inboxes/${inbox["id"].asText()}/messages/wait",
            "POST",
            session,
            """{"timeoutSeconds":1}""",
        ).statusCode() shouldBe 200
    }

    @Test
    fun `suspended accounts lose human product authority on the next request`() {
        val session = login()
        jdbc
            .sql("UPDATE human_account SET state='SUSPENDED' WHERE id=(SELECT account_id FROM human_session WHERE verifier=:verifier)")
            .param("verifier", session.credential.verifier())
            .update()
        call("/account/product/workspace", session = session).statusCode() shouldBe 401
        call("/account/product/api-keys", "POST", session, "{}").statusCode() shouldBe 401
    }

    private companion object {
        const val ORIGIN = "https://console.example.invalid"
    }
}
