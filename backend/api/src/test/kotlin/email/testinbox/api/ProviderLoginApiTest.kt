package email.testinbox.api

import email.testinbox.api.account.HumanSessionCookies
import email.testinbox.api.account.LocalLoginProvider
import email.testinbox.api.account.ProviderLoginCookies
import email.testinbox.api.account.ProviderProtocols
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@TestPropertySource(
    properties = [
        "testinbox.accounts.enabled=true", "testinbox.accounts.public-origin=https://console.example.invalid",
        "testinbox.accounts.oauth.state-key-base64=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
    ],
)
@Import(ProviderLoginApiTest.Fixture::class)
class ProviderLoginApiTest : ApiIntegrationTestBase() {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var provider: LocalLoginProvider
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    @TestConfiguration
    class Fixture {
        @Bean fun localProvider(): LocalLoginProvider = LocalLoginProvider()

        @Bean fun providerProtocols(provider: LocalLoginProvider): ProviderProtocols = provider.protocols()
    }

    @AfterAll fun closeProvider() {
        provider.close()
    }

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("DELETE FROM human_account").update()
        jdbc.sql("DELETE FROM provider_login_state").update()
        provider.mode = "valid"
        provider.exchanges.set(0)
        provider.forbiddenRedirects.set(0)
        provider.pkceValid = false
        provider.email = "private@example.invalid"
        provider.githubId = 123456
    }

    data class Attempt(
        val state: String,
        val cookie: String,
    )

    private fun call(
        path: String,
        cookie: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(url(path))).timeout(Duration.ofSeconds(15))
        cookie?.let { builder.header("Cookie", it) }
        extra.forEach { (name, value) -> builder.header(name, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun begin(
        name: String,
        cookie: String? = null,
    ): Attempt {
        val response =
            call(
                "/account/oauth/authorize/$name",
                cookie,
                mapOf(
                    "X-Forwarded-Host" to "attacker.example.invalid",
                    "Forwarded" to "host=attacker.example.invalid;proto=http",
                ),
            )
        response.statusCode() shouldBe 302
        response.headers().firstValue("Cache-Control").orElse("") shouldBe "no-store"
        val location = URI(response.headers().firstValue("Location").orElseThrow())
        val parameters = LocalLoginProvider.parameters(location.rawQuery)
        parameters.getValue("redirect_uri") shouldBe "https://console.example.invalid/account/oauth/callback/$name"
        parameters.getValue("code_challenge_method") shouldBe "S256"
        parameters.getValue("scope").split(' ').toSet() shouldBe
            if (name == "google") setOf("openid", "email") else setOf("read:user", "user:email")
        provider.nonce = parameters["nonce"] ?: ""
        provider.challenge = parameters.getValue("code_challenge")
        val raw = response.headers().allValues("Set-Cookie").single()
        listOf("Secure", "HttpOnly", "Path=/", "SameSite=Lax", "Max-Age=600").forEach { raw.contains(it) shouldBe true }
        raw.contains("Domain", ignoreCase = true) shouldBe false
        raw.contains("JSESSIONID") shouldBe false
        return Attempt(parameters.getValue("state"), raw.substringBefore(';'))
    }

    private fun complete(
        name: String,
        attempt: Attempt,
        extra: String = "",
    ): HttpResponse<String> =
        call(
            "/account/oauth/callback/$name?state=${URLEncoder.encode(attempt.state, Charsets.UTF_8)}&code=fixture-code$extra",
            attempt.cookie,
        )

    private fun count(table: String): Long = jdbc.sql("SELECT count(*) FROM $table").query(Long::class.javaObjectType).single()

    private fun location(response: HttpResponse<String>): String = response.headers().firstValue("Location").orElse("")

    private fun noIdentity() {
        count("human_account") shouldBe 0
        count("human_identity") shouldBe 0
        count("human_session") shouldBe 0
        count("beta_admission") shouldBe 0
    }

    @ParameterizedTest
    @ValueSource(strings = ["google", "github"])
    fun `real protocol callback verifies identity issues only a human cookie and rejects replay`(name: String) {
        val attempt = begin(name)
        val response = complete(name, attempt)
        response.statusCode() shouldBe 302
        location(response) shouldBe "https://console.example.invalid/console"
        provider.pkceValid shouldBe true
        provider.exchanges.get() shouldBe 1
        val cookies = response.headers().allValues("Set-Cookie")
        cookies.size shouldBe 2
        cookies.any { it.startsWith("${ProviderLoginCookies.NAME}=;") && it.contains("Max-Age=0") } shouldBe true
        cookies.none { it.contains("JSESSIONID") || it.contains("fixture-access") } shouldBe true
        val session = cookies.single { it.startsWith("${HumanSessionCookies.NAME}=") }.substringBefore(';')
        call("/account/session", session).statusCode() shouldBe 200
        call("/v1/workspace", session).statusCode() shouldBe 401
        count("human_account") shouldBe 1
        count("human_session") shouldBe 1
        count("beta_admission") shouldBe 0
        jdbc.sql("SELECT subject FROM human_identity").query(String::class.java).single() shouldBe
            if (name == "google") "google-subject" else "123456"
        jdbc.sql("SELECT contact_email FROM human_account").query(String::class.java).single() shouldBe
            if (name == "google") "fixture@gmail.com" else "private@example.invalid"
        jdbc
            .sql(
                "SELECT count(*) FROM provider_login_state WHERE encrypted_request IS NOT NULL",
            ).query(Long::class.javaObjectType)
            .single() shouldBe
            0
        location(complete(name, attempt)) shouldBe "https://console.example.invalid/login?error=verification-failed"
        provider.exchanges.get() shouldBe 1
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "signature", "issuer", "audience", "nonce", "missing-nonce",
            "expired", "unverified", "external", "token-error", "redirect",
        ],
    )
    fun `invalid Google proof cannot create an account session or admission`(mode: String) {
        val attempt = begin("google")
        provider.mode = mode
        val response = complete("google", attempt)
        response.statusCode() shouldBe 302
        location(response) shouldBe "https://console.example.invalid/login?error=verification-failed"
        response.headers().allValues("Set-Cookie").none { it.startsWith("${HumanSessionCookies.NAME}=") } shouldBe true
        noIdentity()
        provider.forbiddenRedirects.get() shouldBe 0
        provider.exchanges.get() shouldBe 1
        complete("google", attempt)
        provider.exchanges.get() shouldBe 1
    }

    @ParameterizedTest
    @ValueSource(strings = ["unverified", "missing-email", "invalid-id", "token-error", "redirect"])
    fun `GitHub requires numeric immutable identity and verified primary contact`(mode: String) {
        val attempt = begin("github")
        provider.mode = mode
        location(complete("github", attempt)) shouldBe "https://console.example.invalid/login?error=verification-failed"
        noIdentity()
        provider.forbiddenRedirects.get() shouldBe 0
    }

    @Test
    fun `authoritative Workspace contact may sign in`() {
        val attempt = begin("google")
        provider.mode = "hosted"
        location(complete("google", attempt)) shouldBe "https://console.example.invalid/console"
        jdbc.sql("SELECT contact_email FROM human_account").query(String::class.java).single() shouldBe "user@example.invalid"
    }

    @Test
    fun `wrong browser and provider do not consume the real attempt`() {
        val attempt = begin("google")
        location(complete("google", attempt.copy(cookie = "${ProviderLoginCookies.NAME}=${"a".repeat(43)}"))) shouldBe
            "https://console.example.invalid/login?error=verification-failed"
        location(complete("github", attempt)) shouldBe "https://console.example.invalid/login?error=verification-failed"
        provider.exchanges.get() shouldBe 0
        noIdentity()
        location(complete("google", attempt)) shouldBe "https://console.example.invalid/console"
    }

    @Test
    fun `expired state never reaches the token endpoint`() {
        val attempt = begin("github")
        jdbc
            .sql(
                "UPDATE provider_login_state SET created_at=created_at-interval '11 minutes', expires_at=expires_at-interval '11 minutes'",
            ).update()
        location(complete("github", attempt)) shouldBe "https://console.example.invalid/login?error=verification-failed"
        provider.exchanges.get() shouldBe 0
        noIdentity()
    }

    @Test
    fun `same contact in another provider never links identities`() {
        location(complete("google", begin("google"))) shouldBe "https://console.example.invalid/console"
        provider.email = "fixture@gmail.com"
        location(complete("github", begin("github"))) shouldBe "https://console.example.invalid/login?error=different-signin-method"
        count("human_account") shouldBe 1
        count("human_identity") shouldBe 1
        count("human_session") shouldBe 1
    }

    @Test
    fun `denial consumes state and all public errors omit provider details`() {
        val attempt = begin("github")
        val response =
            call(
                "/account/oauth/callback/github?state=${attempt.state}&error=access_denied&error_description=SECRET-PROVIDER-DETAIL",
                attempt.cookie,
            )
        location(response) shouldBe "https://console.example.invalid/login?error=verification-failed"
        response.body().contains("SECRET-PROVIDER-DETAIL") shouldBe false
        provider.exchanges.get() shouldBe 0
        noIdentity()
        location(complete("github", attempt)) shouldBe "https://console.example.invalid/login?error=verification-failed"
    }

    @Test
    fun `duplicate unknown oversized and ambiguous input fails before provider I O`() {
        val attempt = begin("github")
        for (suffix in listOf("&state=${attempt.state}", "&code=other", "&returnTo=https://evil.invalid", "&code=${"a".repeat(4096)}")) {
            complete("github", attempt, suffix).statusCode() shouldBe 400
        }
        complete("github", attempt.copy(cookie = "${attempt.cookie}; ${attempt.cookie}")).statusCode() shouldBe 400
        call("/account/oauth/authorize/github?returnTo=https://evil.invalid").statusCode() shouldBe 400
        call("/account/oauth/authorize/unknown").statusCode() shouldBe 400
        provider.exchanges.get() shouldBe 0
        noIdentity()
        location(complete("github", attempt)) shouldBe "https://console.example.invalid/console"
    }

    @Test
    fun `wrong code consumes the attempt and cannot be retried with a good code`() {
        val attempt = begin("github")
        val response = call("/account/oauth/callback/github?state=${attempt.state}&code=wrong", attempt.cookie)
        location(response) shouldBe "https://console.example.invalid/login?error=verification-failed"
        provider.exchanges.get() shouldBe 1
        location(complete("github", attempt)) shouldBe "https://console.example.invalid/login?error=verification-failed"
        provider.exchanges.get() shouldBe 1
        noIdentity()
    }

    @Test
    fun `HEAD cannot start or consume a login`() {
        val start =
            HttpRequest
                .newBuilder(URI(url("/account/oauth/authorize/github")))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
        client.send(start, HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 400
        count("provider_login_state") shouldBe 0
        val attempt = begin("github")
        val callback =
            HttpRequest
                .newBuilder(URI(url("/account/oauth/callback/github?state=${attempt.state}&code=fixture-code")))
                .header("Cookie", attempt.cookie)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
        client.send(callback, HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 400
        provider.exchanges.get() shouldBe 0
        noIdentity()
        location(complete("github", attempt)) shouldBe "https://console.example.invalid/console"
    }

    @Test
    fun `method readiness exposes configured providers without creating or disclosing an account`() {
        val response = call("/account/methods")
        response.statusCode() shouldBe 200
        response.body() shouldBe """{"methods":["google","github"]}"""
        response.headers().firstValue("Cache-Control").orElse("") shouldBe "no-store"
        noIdentity()
    }
}
