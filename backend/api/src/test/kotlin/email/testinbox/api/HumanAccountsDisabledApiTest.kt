package email.testinbox.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class HumanAccountsDisabledApiTest : ApiIntegrationTestBase() {
    @Test
    fun `default configuration disables account routes including normalized paths and unknown routes`() {
        val client = HttpClient.newHttpClient()
        listOf("/account/session", "/account/not-a-route", "/account;probe/session", "/%61ccount/session").forEach { path ->
            val request = HttpRequest.newBuilder(URI(url(path))).header("Authorization", "Bearer $adminKey").build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 503
            response.headers().firstValue("Cache-Control").orElse("") shouldBe "no-store"
            response.body() shouldContain "accounts-unavailable"
        }
        get("/v1/workspace").statusCode.value() shouldBe 200
    }
}
