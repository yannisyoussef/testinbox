package email.testinbox.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

private const val ALLOWED = "https://docs.example.test"
private const val LOCAL_DOCS = "http://localhost:3000"

/**
 * Over a real socket, because CORS is headers on the wire: what a browser on
 * the docs site sees when its "Try it" calls `/v1` (docs/api/principles.md #11).
 *
 * The INBOX_CREATE budget is two and refills far too slowly to heal mid-test,
 * so "a preflight was charged" and "a refusal carries CORS" are both decisive
 * rather than timing-dependent. Every budget assertion owns its workspace.
 */
@TestPropertySource(
    properties = [
        "testinbox.cors.allowed-origins=$ALLOWED,$LOCAL_DOCS",
        "testinbox.limits.inbox-create.capacity=2",
        "testinbox.limits.inbox-create.refill-per-second=0.01",
    ],
)
class ApiCorsApiTest : ApiIntegrationTestBase() {
    @Autowired lateinit var registry: MeterRegistry

    @Autowired lateinit var jdbc: JdbcClient

    private val json = jacksonObjectMapper()

    private fun preflight(
        path: String,
        origin: String = ALLOWED,
        method: String = "POST",
        requestHeaders: String? = "authorization, content-type, idempotency-key",
        extra: Map<String, String> = emptyMap(),
    ): HttpResponse<String> =
        send(path, "OPTIONS", origin = origin) {
            header("Access-Control-Request-Method", method)
            requestHeaders?.let { header("Access-Control-Request-Headers", it) }
            extra.forEach { (name, value) -> header(name, value) }
        }

    private fun authFailures(): Double =
        registry
            .find("testinbox_api_key_auth_total")
            .counters()
            .sumOf { it.count() }

    private fun rateDecisions(): Double =
        registry
            .find("testinbox_rate_decision_total")
            .counters()
            .sumOf { it.count() }

    private fun idempotencyRecords(): Long =
        jdbc
            .sql("SELECT count(*) AS c FROM idempotency_record")
            .query { rs, _ -> rs.getLong("c") }
            .single()

    // --- preflight -----------------------------------------------------------

    @Test
    fun `a preflight from an allowed origin is answered with exactly the allow headers and no credentials`() {
        val response = preflight("/v1/inboxes")
        response.statusCode() shouldBe 200
        val headers = response.headers()
        headers.firstValue("Access-Control-Allow-Origin").orElse(null) shouldBe ALLOWED
        headers.firstValue("Access-Control-Allow-Methods").orElse(null) shouldBe "GET,POST,DELETE"
        headers
            .firstValue("Access-Control-Allow-Headers")
            .orElse("")
            .lowercase()
            .split(",")
            .map { it.trim() } shouldContainAll listOf("authorization", "content-type", "idempotency-key")
        headers.firstValue("Access-Control-Max-Age").orElse(null) shouldBe "600"
        headers.firstValue("Access-Control-Allow-Credentials").isPresent shouldBe false
        headers.allValues("Vary").joinToString(",") shouldContain "Origin"
        response.body() shouldBe ""
    }

    @Test
    fun `the event stream's resume header and the correlation header may be sent cross-origin`() {
        val response =
            preflight("/v1/workspace/events", method = "GET", requestHeaders = "authorization, last-event-id, x-correlation-id")
        response.statusCode() shouldBe 200
        response.headers().firstValue("Access-Control-Allow-Origin").orElse(null) shouldBe ALLOWED
    }

    @Test
    fun `every configured origin is allowed, each echoed exactly`() {
        preflight("/v1/inboxes", origin = LOCAL_DOCS)
            .headers()
            .firstValue("Access-Control-Allow-Origin")
            .orElse(null) shouldBe LOCAL_DOCS
    }

    @Test
    fun `a preflight from any other origin is a static problem with no allow headers`() {
        for (origin in listOf("https://evil.example", "https://docs.example.test.evil.example", "http://docs.example.test", "null")) {
            assertRefused(preflight("/v1/inboxes", origin = origin), origin)
        }
    }

    @Test
    fun `a non-canonical spelling of an allowed origin is refused at preflight, as the actual request would be`() {
        // Spring's own matcher ignores case and a trailing slash; the filter does
        // not, so a preflight is never granted for an Origin the actual request
        // would not be answered for. Browsers never send either form.
        for (origin in listOf("HTTPS://DOCS.EXAMPLE.TEST", "https://docs.example.test/")) {
            assertRefused(preflight("/v1/inboxes", origin = origin), origin)
            assertNoCors(send("/v1/inboxes", "GET", origin = origin, key = adminKey).headers())
        }
    }

    @Test
    fun `a preflight for a method or header the API does not use is not granted it`() {
        assertRefused(preflight("/v1/inboxes", method = "PUT"), ALLOWED)
        assertRefused(preflight("/v1/inboxes", requestHeaders = "cookie"), ALLOWED)
        // A mixed request is answered with only the allowed subset; the browser
        // then refuses the actual request because x-csrf-token is not granted.
        val mixed = preflight("/v1/inboxes", requestHeaders = "authorization, x-csrf-token")
        mixed.statusCode() shouldBe 200
        mixed.headers().firstValue("Access-Control-Allow-Headers").orElse(null) shouldBe "authorization"
    }

    @Test
    fun `a preflight is never authenticated, counted as an auth outcome or rate decision, or charged`() {
        val tenant = provisionIsolatedWorkspace("cors-preflight-free")
        // One of the two INBOX_CREATE tokens spent.
        post("/v1/inboxes", "{}", key = tenant.apiKey).statusCode.value() shouldBe 201
        val auth = authFailures()
        val decisions = rateDecisions()

        // A browser never puts a credential on a preflight; these do, so that
        // reaching authentication or the limiter would be visible in both the
        // counters and the remaining budget.
        repeat(5) {
            for (credential in listOf(tenant.apiKey, "not-a-key", "ti_k1_garbage")) {
                val response = preflight("/v1/inboxes", extra = mapOf("Authorization" to "Bearer $credential"))
                response.statusCode() shouldBe 200
                response.headers().firstValue("RateLimit-Remaining").isPresent shouldBe false
            }
        }
        // Without a credential at all — the realistic browser preflight — it is not a 401 either.
        preflight("/v1/workspace", method = "GET", requestHeaders = "authorization").statusCode() shouldBe 200

        authFailures() shouldBe auth
        rateDecisions() shouldBe decisions
        // The second token is still there: fifteen preflights cost nothing.
        val second = send("/v1/inboxes", "POST", body = "{}", key = tenant.apiKey)
        second.statusCode() shouldBe 201
        second.headers().firstValue("RateLimit-Remaining").orElse(null) shouldBe "0"
    }

    @Test
    fun `a preflight never touches idempotency, even when it names a key`() {
        val before = idempotencyRecords()
        repeat(3) {
            preflight(
                "/v1/inboxes",
                extra = mapOf("Idempotency-Key" to "cors-preflight-${UUID.randomUUID()}", "Authorization" to "Bearer $adminKey"),
            ).statusCode() shouldBe 200
        }
        idempotencyRecords() shouldBe before
    }

    @Test
    fun `a preflight is answered for any v1 path, so it reveals nothing about the route table`() {
        preflight("/v1/no-such-route", method = "GET").statusCode() shouldBe 200
        preflight("/v1/inboxes/${UUID.randomUUID()}", method = "DELETE").statusCode() shouldBe 200
    }

    // --- actual requests -------------------------------------------------------

    @Test
    fun `a successful cross-origin request carries the allow-origin and every documented header is exposed`() {
        val tenant = provisionIsolatedWorkspace("cors-success")
        val created =
            send("/v1/inboxes", "POST", body = "{}", key = tenant.apiKey) {
                header("Idempotency-Key", "cors-create-${UUID.randomUUID()}")
            }
        created.statusCode() shouldBe 201
        assertCors(created.headers())
        created.headers().firstValue("Idempotency-Replayed").orElse(null) shouldBe "false"

        val id = json.readTree(created.body())["id"].asText()
        val read = send("/v1/inboxes/$id", "GET", key = tenant.apiKey)
        read.statusCode() shouldBe 200
        assertCors(read.headers())
    }

    @Test
    fun `a missing or wrong key is still 401, and the problem is readable by the page`() {
        for (key in listOf(null, "not-a-key")) {
            val response = send("/v1/inboxes", "GET", key = key)
            response.statusCode() shouldBe 401
            response.headers().firstValue("Content-Type").orElse("") shouldStartWith "application/problem+json"
            json.readTree(response.body())["type"].asText() shouldBe "https://testinbox.email/problems/unauthorized"
            assertCors(response.headers())
        }
    }

    @Test
    fun `another tenant's inbox is the same 404 problem, with CORS`() {
        val inboxId = json.readTree(post("/v1/inboxes", "{}").body)["id"].asText()
        val response = send("/v1/inboxes/$inboxId", "GET", key = otherWorkspaceKey)
        response.statusCode() shouldBe 404
        json.readTree(response.body())["type"].asText() shouldBe "https://testinbox.email/problems/inbox-not-found"
        assertCors(response.headers())
    }

    @Test
    fun `a rate refusal carries CORS, so the page can read Retry-After`() {
        val tenant = provisionIsolatedWorkspace("cors-429")
        repeat(2) { send("/v1/inboxes", "POST", body = "{}", key = tenant.apiKey).statusCode() shouldBe 201 }
        val limited = send("/v1/inboxes", "POST", body = "{}", key = tenant.apiKey)
        limited.statusCode() shouldBe 429
        assertCors(limited.headers())
        limited.headers().firstValue("Retry-After").isPresent shouldBe true
        json.readTree(limited.body())["type"].asText() shouldBe "https://testinbox.email/problems/rate-limit-exceeded"
    }

    @Test
    fun `the workspace event stream opens cross-origin for a fetch-based client`() {
        val response =
            client.send(
                request("/v1/workspace/events", "GET", key = adminKey) { header("Accept", "text/event-stream") },
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        response.body().use { body ->
            response.statusCode() shouldBe 200
            response.headers().firstValue("Content-Type").orElse("") shouldStartWith "text/event-stream"
            assertCors(response.headers())
            body.readNBytes("retry: 1000".length).decodeToString() shouldBe "retry: 1000"
        }
    }

    @Test
    fun `a request from another origin, or none, is served exactly as before, without CORS but varying on Origin`() {
        for (origin in listOf("https://evil.example", null)) {
            val response = send("/v1/inboxes", "GET", origin = origin, key = adminKey)
            response.statusCode() shouldBe 200
            assertNoCors(response.headers())
            // A shared cache must not replay this response to an allowed origin, or the reverse.
            response
                .headers()
                .allValues("Vary")
                .flatMap { it.split(",") }
                .map { it.trim() } shouldContain "Origin"
        }
    }

    // --- scope -------------------------------------------------------------------

    @Test
    fun `account routes never get CORS, even from an allowed origin`() {
        for (path in listOf("/account/session", "/account/product/messages", "/v1/../account/session", "/v1;x/../account/session")) {
            assertNoCors(send(path, "GET").headers())
            assertNoCors(preflight(path, method = "GET").headers())
        }
    }

    @Test
    fun `actuator never gets CORS, even from an allowed origin`() {
        val health = send("/actuator/health", "GET")
        health.statusCode() shouldBe 200
        assertNoCors(health.headers())
        assertNoCors(preflight("/actuator/health", method = "GET").headers())
    }

    // --- helpers -------------------------------------------------------------------

    private fun assertCors(headers: HttpHeaders) {
        headers.firstValue("Access-Control-Allow-Origin").orElse(null) shouldBe ALLOWED
        headers.firstValue("Access-Control-Allow-Credentials").isPresent shouldBe false
        val exposed =
            headers
                .allValues("Access-Control-Expose-Headers")
                .flatMap { it.split(",") }
                .map { it.trim().lowercase() }
        exposed shouldContainAll EXPOSED
    }

    private fun assertRefused(
        response: HttpResponse<String>,
        origin: String,
    ) {
        response.statusCode() shouldBe 403
        assertNoCors(response.headers())
        response.headers().firstValue("Content-Type").orElse("") shouldStartWith "application/problem+json"
        val problem = json.readTree(response.body())
        problem["type"].asText() shouldBe "https://testinbox.email/problems/cors-request-refused"
        problem["status"].asInt() shouldBe 403
        problem["correlationId"].asText().isNotBlank() shouldBe true
        // Never echoed ("null" is skipped: it is also a JSON literal).
        if (origin != "null") response.body().contains(origin) shouldBe false
    }

    private fun assertNoCors(headers: HttpHeaders) {
        headers
            .map()
            .keys
            .filter { it.lowercase().startsWith("access-control-") }
            .shouldBeEmpty()
    }

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    private fun request(
        path: String,
        method: String,
        origin: String? = ALLOWED,
        body: String? = null,
        key: String? = null,
        customize: HttpRequest.Builder.() -> Unit = {},
    ): HttpRequest =
        HttpRequest
            .newBuilder(URI.create(url(path)))
            .timeout(Duration.ofSeconds(20))
            .apply {
                origin?.let { header("Origin", it) }
                key?.let { header("Authorization", "Bearer $it") }
                if (body != null) header("Content-Type", "application/json")
                customize()
            }.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .build()

    private fun send(
        path: String,
        method: String,
        origin: String? = ALLOWED,
        body: String? = null,
        key: String? = null,
        customize: HttpRequest.Builder.() -> Unit = {},
    ): HttpResponse<String> = client.send(request(path, method, origin, body, key, customize), HttpResponse.BodyHandlers.ofString())

    private companion object {
        val EXPOSED =
            listOf(
                "x-correlation-id",
                "x-api-stability",
                "ratelimit-limit",
                "ratelimit-remaining",
                "ratelimit-reset",
                "retry-after",
                "idempotency-replayed",
                "content-disposition",
                "allow",
            )
    }
}
