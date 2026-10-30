package email.testinbox.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.api.account.LocalLoginProvider
import email.testinbox.api.account.ProviderProtocols
import email.testinbox.application.identity.EmailLoginCode
import email.testinbox.application.port.EmailLoginDelivery
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.domain.identity.BetaCohortPolicy
import email.testinbox.domain.identity.ContactEmail
import email.testinbox.domain.identity.HumanAccountId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The thirty-place cap is independent of the sign-in method (ADR-036 §4):
 * sessions minted by Google, GitHub and email codes race the last places
 * over real HTTP, and PostgreSQL's cohort lock admits exactly the remainder.
 */
@TestPropertySource(
    properties = [
        "testinbox.accounts.enabled=true",
        "testinbox.accounts.public-origin=https://console.example.invalid",
        "testinbox.accounts.oauth.state-key-base64=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        "testinbox.accounts.email.enabled=true",
        "testinbox.accounts.email.verification-key-base64=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        "testinbox.accounts.email.smtp.host=relay.example.invalid",
        "testinbox.accounts.email.smtp.username=fixture",
        "testinbox.accounts.email.smtp.password=fixture",
        "testinbox.accounts.email.smtp.from=login@example.invalid",
    ],
)
@Import(AdmissionAcrossSignInMethodsApiTest.Fixture::class)
class AdmissionAcrossSignInMethodsApiTest : ApiIntegrationTestBase() {
    // This suite proves the cap across sign-in methods, not storage feasibility.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    lateinit var capacity: email.testinbox.application.storage.CohortStorageReadiness

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var provider: LocalLoginProvider

    @Autowired lateinit var mail: Codes

    @Autowired lateinit var admit: AdmitBetaParticipant

    private val json = jacksonObjectMapper()
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    private val origin = "https://console.example.invalid"

    class Codes : EmailLoginDelivery {
        val codes = ConcurrentHashMap<String, String>()

        override fun send(
            email: ContactEmail,
            code: EmailLoginCode,
        ) {
            codes[email.value] = code.value
        }
    }

    @TestConfiguration
    class Fixture {
        @Bean fun localProvider(): LocalLoginProvider = LocalLoginProvider()

        @Bean fun providerProtocols(provider: LocalLoginProvider): ProviderProtocols = provider.protocols()

        @Bean fun loginMail(): Codes = Codes()
    }

    @AfterAll fun closeProvider() {
        provider.close()
    }

    @BeforeEach
    fun open() {
        org.mockito.Mockito
            .`when`(capacity.ready())
            .thenReturn(true)
        clean()
        jdbc.sql("UPDATE beta_cohort SET admissions_open=true,capacity_ready=true").update()
    }

    @AfterEach
    fun clean() {
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("DELETE FROM human_account").update()
        jdbc.sql("DELETE FROM email_login_challenge").update()
        jdbc.sql("DELETE FROM provider_login_state").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open=false,capacity_ready=false").update()
    }

    private fun send(
        path: String,
        cookie: String? = null,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(url(path))).timeout(Duration.ofSeconds(20))
        cookie?.let { builder.header("Cookie", it) }
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (body != null) {
            builder.header("Content-Type", "application/json").header("Origin", origin)
            builder.POST(HttpRequest.BodyPublishers.ofString(body))
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun sessionCookie(response: HttpResponse<String>): String =
        response
            .headers()
            .allValues("Set-Cookie")
            .single { it.startsWith("__Host-ti_session=") }
            .substringBefore(';')

    private fun emailSession(): String {
        val address = "${UUID.randomUUID()}@example.invalid"
        val challenge = send("/account/email/request", body = """{"email":"$address"}""")
        challenge.statusCode() shouldBe 202
        val browser =
            challenge
                .headers()
                .allValues("Set-Cookie")
                .single()
                .substringBefore(';')
        val id = json.readTree(challenge.body())["challengeId"].asText()
        val verified = send("/account/email/verify", browser, """{"challengeId":"$id","code":"${mail.codes.getValue(address)}"}""")
        verified.statusCode() shouldBe 204
        return sessionCookie(verified)
    }

    private fun providerSession(name: String): String {
        if (name == "google") {
            provider.googleSubject = "google-${UUID.randomUUID()}"
            provider.googleEmail = "fixture.${UUID.randomUUID().toString().take(8)}@gmail.com"
        } else {
            provider.githubId += 1
            provider.email = "${UUID.randomUUID()}@example.invalid"
        }
        val start = send("/account/oauth/authorize/$name")
        start.statusCode() shouldBe 302
        val parameters = LocalLoginProvider.parameters(URI(start.headers().firstValue("Location").orElseThrow()).rawQuery)
        provider.nonce = parameters["nonce"] ?: ""
        provider.challenge = parameters.getValue("code_challenge")
        val attempt =
            start
                .headers()
                .allValues("Set-Cookie")
                .single()
                .substringBefore(';')
        val state = URLEncoder.encode(parameters.getValue("state"), Charsets.UTF_8)
        val callback = send("/account/oauth/callback/$name?state=$state&code=fixture-code", attempt)
        callback.statusCode() shouldBe 302
        callback.headers().firstValue("Location").orElse("") shouldBe "$origin/console"
        return sessionCookie(callback)
    }

    private fun csrf(cookie: String): String = json.readTree(send("/account/session", cookie).body())["csrfToken"].asText()

    private fun fixtureParticipant() {
        val id = HumanAccountId(UUID.randomUUID())
        jdbc
            .sql("INSERT INTO human_account VALUES (:id,:email,now(),'ACTIVE',now())")
            .param("id", id.value)
            .param("email", "$id@example.invalid")
            .update()
        admit.execute(id).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()
    }

    @Test
    fun `Google GitHub and email sessions racing for the last places never exceed thirty`() {
        repeat(BetaCohortPolicy.MAX_PARTICIPANTS - 10) { fixtureParticipant() }
        val sessions = List(5) { emailSession() } + List(5) { providerSession("google") } + List(5) { providerSession("github") }
        val tokens = sessions.associateWith(::csrf)
        jdbc
            .sql("SELECT count(DISTINCT provider) FROM human_identity")
            .query(Int::class.javaObjectType)
            .single() shouldBe 3

        // Every session submits twice: a double click and a retried request.
        val attempts = (sessions + sessions).shuffled()
        val ready = CountDownLatch(attempts.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(attempts.size)
        val responses =
            try {
                attempts
                    .map { session ->
                        executor.submit<Pair<String, HttpResponse<String>>> {
                            ready.countDown()
                            check(start.await(20, TimeUnit.SECONDS))
                            session to send("/account/admission", session, "", mapOf("X-CSRF-Token" to tokens.getValue(session)))
                        }
                    }.also {
                        check(ready.await(20, TimeUnit.SECONDS))
                        start.countDown()
                    }.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                start.countDown()
                executor.shutdownNow()
            }

        responses.forEach { (_, response) -> response.statusCode() shouldBe 200 }
        val views = responses.map { (session, response) -> session to json.readTree(response.body()) }
        val admitted =
            views.filter { it.second["status"].asText() == "ADMITTED" }.groupBy(
                { it.first },
                { it.second["workspaceId"].asText() },
            )
        admitted.size shouldBe 10
        admitted.values.forEach { it.distinct().size shouldBe 1 } // a retry returns the original workspace
        admitted.values
            .map { it.first() }
            .distinct()
            .size shouldBe 10
        views.filter { it.first !in admitted.keys }.forEach { it.second["status"].asText() shouldBe "FULL" }
        jdbc.sql("SELECT count(*) FROM beta_admission").query(Int::class.javaObjectType).single() shouldBe 30
        jdbc
            .sql("SELECT count(DISTINCT place) FROM beta_admission WHERE place BETWEEN 1 AND 30")
            .query(Int::class.javaObjectType)
            .single() shouldBe 30
        jdbc.sql("SELECT count(*) FROM participant_workspace").query(Int::class.javaObjectType).single() shouldBe 30
    }
}
