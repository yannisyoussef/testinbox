package email.testinbox.api

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The shipped default — `testinbox.cors.allowed-origins` empty — is the API as
 * it was before CORS existed: no `Access-Control-*` header anywhere, whatever
 * the `Origin`, and a preflight on `/v1` is just an unauthenticated request.
 *
 * Deliberately no property of its own, so it runs in the suite's shared
 * default context: the configuration every other suite (and an unset
 * environment) actually has.
 */
class ApiCorsDisabledApiTest : ApiIntegrationTestBase() {
    private val client =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    private fun send(
        path: String,
        method: String,
        preflight: Boolean = false,
    ): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create(url(path)))
                .timeout(Duration.ofSeconds(10))
                .header("Origin", "https://docs.example.test")
                .header("Authorization", "Bearer $adminKey")
                .apply { if (preflight) header("Access-Control-Request-Method", "POST") }
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun HttpResponse<String>.corsHeaders() =
        headers()
            .map()
            .keys
            .filter { it.lowercase().startsWith("access-control-") }

    @Test
    fun `with no origins configured no response carries a CORS header`() {
        for (path in listOf("/v1/inboxes", "/v1/workspace", "/account/session", "/actuator/health")) {
            send(path, "GET").corsHeaders().shouldBeEmpty()
        }
    }

    @Test
    fun `with no origins configured a v1 preflight is an unauthenticated request, as before`() {
        // Without a credential: the 401 the API always gave a keyless OPTIONS.
        // With one (which no browser sends on a preflight): still no CORS header.
        val response =
            client.send(
                HttpRequest
                    .newBuilder(URI.create(url("/v1/inboxes")))
                    .timeout(Duration.ofSeconds(10))
                    .header("Origin", "https://docs.example.test")
                    .header("Access-Control-Request-Method", "POST")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        response.statusCode() shouldBe 401
        response.corsHeaders().shouldBeEmpty()
        send("/v1/inboxes", "OPTIONS", preflight = true).corsHeaders().shouldBeEmpty()
    }
}
