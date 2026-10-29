package email.testinbox.api

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["testinbox.accounts.enabled=true", "testinbox.accounts.public-origin=http://invalid.example"])
class HumanAccountsMisconfiguredApiTest : ApiIntegrationTestBase() {
    @Test
    fun `an invalid human origin closes accounts while the existing Bearer API stays operational`() {
        get("/account/session").statusCode.value() shouldBe 503
        get("/v1/workspace").statusCode.value() shouldBe 200
    }
}

@TestPropertySource(
    properties = [
        "testinbox.accounts.enabled=true", "testinbox.accounts.public-origin=https://console.example.invalid",
        "testinbox.accounts.email.enabled=true",
    ],
)
class EmailAccountsMisconfiguredApiTest : ApiIntegrationTestBase() {
    @Test
    fun `missing delivery and verifier credentials disable email without taking down the Bearer API`() {
        val request =
            java.net.http.HttpRequest
                .newBuilder(java.net.URI(url("/account/email/request")))
                .header("Origin", "https://console.example.invalid")
                .header("Content-Type", "application/json")
                .POST(
                    java.net.http.HttpRequest.BodyPublishers
                        .ofString("""{"email":"person@example.invalid"}"""),
                ).build()
        val response =
            java.net.http.HttpClient
                .newHttpClient()
                .send(
                    request,
                    java.net.http.HttpResponse.BodyHandlers
                        .ofString(),
                )
        response.statusCode() shouldBe 503
        get("/v1/workspace").statusCode.value() shouldBe 200
    }
}
