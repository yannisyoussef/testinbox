package email.testinbox.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.api.account.EmailLoginCookies
import email.testinbox.api.account.HumanSessionCookies
import email.testinbox.application.identity.EmailLoginCode
import email.testinbox.application.port.EmailLoginDelivery
import email.testinbox.application.usecase.RequestEmailLogin
import email.testinbox.domain.identity.ContactEmail
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@TestPropertySource(
    properties = [
        "testinbox.accounts.enabled=true",
        "testinbox.accounts.public-origin=https://console.example.invalid",
        "testinbox.accounts.email.enabled=true",
        "testinbox.accounts.email.verification-key-base64=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        "testinbox.accounts.email.smtp.host=relay.example.invalid",
        "testinbox.accounts.email.smtp.username=fixture",
        "testinbox.accounts.email.smtp.password=fixture",
        "testinbox.accounts.email.smtp.from=login@example.invalid",
    ],
)
@Import(EmailLoginApiTest.MailFixture::class)
class EmailLoginApiTest : ApiIntegrationTestBase() {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var mail: RecordingLoginMail

    @Autowired lateinit var registry: MeterRegistry
    private val json = jacksonObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val origin = "https://console.example.invalid"

    @TestConfiguration
    class MailFixture {
        @Bean fun loginMail(): RecordingLoginMail = RecordingLoginMail()
    }

    class RecordingLoginMail : EmailLoginDelivery {
        val codes = ConcurrentHashMap<String, String>()
        var fail = false

        override fun send(
            email: ContactEmail,
            code: EmailLoginCode,
        ) {
            codes[email.value] = code.value
            if (fail) error("fixture provider unavailable")
        }
    }

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("DELETE FROM human_account").update()
        jdbc.sql("DELETE FROM email_login_challenge").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open=false,capacity_ready=false").update()
        mail.codes.clear()
        mail.fail = false
    }

    private fun call(
        path: String,
        body: String? = null,
        cookie: String? = null,
        suppliedOrigin: String? = origin,
        chunked: Boolean = false,
        extra: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(url(path))).timeout(Duration.ofSeconds(10))
        cookie?.let { builder.header("Cookie", it) }
        suppliedOrigin?.let { builder.header("Origin", it) }
        extra.forEach { (name, value) -> builder.header(name, value) }
        if (body != null) {
            builder.header("Content-Type", "application/json")
            val bytes = body.toByteArray()
            builder.POST(
                if (chunked) {
                    HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) }
                } else {
                    HttpRequest.BodyPublishers.ofString(body)
                },
            )
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun email() = "${UUID.randomUUID()}@example.invalid"

    private fun start(address: String): HttpResponse<String> = call("/account/email/request", """{"email":"$address"}""")

    private fun browser(response: HttpResponse<String>): String =
        response
            .headers()
            .allValues("Set-Cookie")
            .single()
            .substringBefore(';')

    private fun payload(
        response: HttpResponse<String>,
        code: String,
    ): String = """{"challengeId":"${json.readTree(response.body())["challengeId"].asText()}","code":"$code"}"""

    private fun verified(address: String): HttpResponse<String> {
        val challenge = start(address)
        challenge.statusCode() shouldBe 202
        return call("/account/email/verify", payload(challenge, mail.codes.getValue(address)), browser(challenge))
    }

    @Test
    fun `request is generic binds the browser and does not allocate an account or workspace`() {
        val before = jdbc.sql("SELECT count(*) FROM workspace").query(Long::class.javaObjectType).single()
        val address = email()
        val response = start(address)
        response.statusCode() shouldBe 202
        response.headers().firstValue("Cache-Control").orElse("") shouldBe "no-store"
        val cookie = response.headers().allValues("Set-Cookie").single()
        listOf("__Host-ti_login=", "Secure", "HttpOnly", "Path=/", "SameSite=Lax").forEach { cookie.contains(it) shouldBe true }
        cookie.contains("Domain", ignoreCase = true) shouldBe false
        response.body().contains(mail.codes.getValue(address)) shouldBe false
        response.body().contains(browser(response).substringAfter('=')) shouldBe false
        jdbc.sql("SELECT count(*) FROM human_account").query(Long::class.javaObjectType).single() shouldBe 0
        jdbc.sql("SELECT count(*) FROM workspace").query(Long::class.javaObjectType).single() shouldBe before
    }

    @Test
    fun `verification sets two separate cookies and enters only the verified not admitted state`() {
        val address = email()
        val response = verified(address)
        response.statusCode() shouldBe 204
        response.body() shouldBe ""
        val cookies = response.headers().allValues("Set-Cookie")
        cookies.size shouldBe 2
        val session = cookies.single { it.startsWith(HumanSessionCookies.NAME + "=") }.substringBefore(';')
        cookies.single { it.startsWith(EmailLoginCookies.NAME + "=") }.contains("Max-Age=0") shouldBe true
        val view = call("/account/session", cookie = session)
        view.statusCode() shouldBe 200
        json.readTree(view.body())["contactEmail"].asText() shouldBe address
        json.readTree(view.body())["admission"].asText() shouldBe "NOT_ADMITTED"
        call("/v1/workspace", cookie = session).statusCode() shouldBe 401
    }

    @Test
    fun `missing wrong browser and replay have the same verification refusal`() {
        val address = email()
        val challenge = start(address)
        val body = payload(challenge, mail.codes.getValue(address))
        val absent = call("/account/email/verify", body)
        absent.statusCode() shouldBe 401
        val other = start(email())
        val wrong = call("/account/email/verify", body, browser(other))
        wrong.statusCode() shouldBe 401
        json.readTree(wrong.body())["type"] shouldBe json.readTree(absent.body())["type"]
        call("/account/email/verify", body, browser(challenge)).statusCode() shouldBe 204
        val replay = call("/account/email/verify", body, browser(challenge))
        replay.statusCode() shouldBe 401
        json.readTree(replay.body())["type"] shouldBe json.readTree(absent.body())["type"]
    }

    @Test
    fun `five invalid string attempts exhaust the code through HTTP`() {
        val address = email()
        val challenge = start(address)
        repeat(5) { call("/account/email/verify", payload(challenge, "wrong"), browser(challenge)).statusCode() shouldBe 401 }
        call("/account/email/verify", payload(challenge, mail.codes.getValue(address)), browser(challenge)).statusCode() shouldBe 401
    }

    @Test
    fun `the exact origin is required even before account existence is checked`() {
        listOf(null, "https://evil.invalid", "https://console.example.invalid.evil.invalid").forEach { supplied ->
            call("/account/email/request", """{"email":"${email()}"}""", suppliedOrigin = supplied).statusCode() shouldBe 403
        }
        mail.codes.size shouldBe 0
    }

    @Test
    fun `unexpected identity fields malformed contacts and ambiguous cookies are refused`() {
        call("/account/email/request", """{"email":"${email()}","accountId":"${UUID.randomUUID()}"}""").statusCode() shouldBe 400
        call("/account/email/request", """{"email":"not an email"}""").statusCode() shouldBe 400
        call("/account/email/verify", """{"challengeId":"bad","code":"12345678"}""").statusCode() shouldBe 400
        val challenge = start(email())
        val duplicate = "${browser(challenge)}; ${browser(challenge)}"
        call("/account/email/request", """{"email":"${email()}"}""", cookie = duplicate).statusCode() shouldBe 400
    }

    @Test
    fun `a TestInbox inbox address is refused exactly like a malformed one, and no code is sent to it`() {
        val inbox = call("/account/email/request", """{"email":"alice@testinbox.local"}""")
        val malformed = call("/account/email/request", """{"email":"not an email"}""")
        inbox.statusCode() shouldBe 400
        json.readTree(inbox.body())["type"] shouldBe json.readTree(malformed.body())["type"]
        mail.codes.keys.none { it.endsWith("@testinbox.local") } shouldBe true
    }

    @Test
    fun `a premature resend returns a bounded retry hint and preserves the earlier code`() {
        val address = email()
        val challenge = start(address)
        val limited = call("/account/email/request", """{"email":"$address"}""", browser(challenge))
        limited.statusCode() shouldBe 429
        (
            limited
                .headers()
                .firstValue("Retry-After")
                .orElseThrow()
                .toInt() in 1..60
        ) shouldBe true
        call("/account/email/verify", payload(challenge, mail.codes.getValue(address)), browser(challenge)).statusCode() shouldBe 204
    }

    @Test
    fun `forwarded source headers cannot reset the verified peer sending budget`() {
        repeat(RequestEmailLogin.MAX_PER_SOURCE) { n ->
            call(
                "/account/email/request",
                """{"email":"${email()}"}""",
                extra = mapOf("X-Forwarded-For" to "198.51.100.$n"),
            ).statusCode() shouldBe 202
        }
        call(
            "/account/email/request",
            """{"email":"${email()}"}""",
            extra = mapOf("X-Forwarded-For" to "203.0.113.99"),
        ).statusCode() shouldBe 429
        mail.codes.size shouldBe RequestEmailLogin.MAX_PER_SOURCE
    }

    @Test
    fun `fixed and chunked oversized JSON is refused before allocation or sending including encoded paths`() {
        val huge = """{"email":"${"x".repeat(5000)}@example.invalid"}"""
        call("/account/email/request", huge).statusCode() shouldBe 413
        call("/account/email/request", huge, chunked = true).statusCode() shouldBe 413
        call("/%61ccount/email/request", huge, chunked = true).statusCode() shouldBe 413
        mail.codes.size shouldBe 0
    }

    @Test
    fun `delivery failure is generic but increments the live closed enum failure metric`() {
        val counter = registry.get("testinbox_email_login_total").tag("outcome", "delivery_failed").counter()
        val before = counter.count()
        mail.fail = true
        val address = email()
        val response = start(address)
        response.statusCode() shouldBe 202
        response.body().contains("fixture provider") shouldBe false
        counter.count() shouldBe before + 1
        call("/account/email/verify", payload(response, mail.codes.getValue(address)), browser(response)).statusCode() shouldBe 401
    }

    @Test
    fun `signing in again to the same account revokes the prior browser credential`() {
        val address = email()
        val first =
            verified(address)
                .headers()
                .allValues("Set-Cookie")
                .single { it.startsWith(HumanSessionCookies.NAME + "=") }
                .substringBefore(';')
        // Fixture time passes without sleeping or changing the real clock;
        // move both challenge timestamps together to preserve its lifetime constraint.
        jdbc
            .sql(
                """
                UPDATE email_login_challenge SET created_at=created_at-interval '61 seconds',
                    expires_at=expires_at-interval '61 seconds'
                """.trimIndent(),
            ).update()
        val challenge = start(address)
        val response = call("/account/email/verify", payload(challenge, mail.codes.getValue(address)), "${browser(challenge)}; $first")
        response.statusCode() shouldBe 204
        val rotated =
            response
                .headers()
                .allValues("Set-Cookie")
                .single { it.startsWith(HumanSessionCookies.NAME + "=") }
                .substringBefore(';')
        (rotated == first) shouldBe false
        call("/account/session", cookie = first).statusCode() shouldBe 401
        call("/account/session", cookie = rotated).statusCode() shouldBe 200
        jdbc.sql("SELECT count(*) FROM human_identity").query(Long::class.javaObjectType).single() shouldBe 1
    }

    @Test
    fun `switching accounts receives a fresh cookie scoped to the newly verified account`() {
        val firstAddress = email()
        val first =
            verified(firstAddress)
                .headers()
                .allValues("Set-Cookie")
                .single { it.startsWith(HumanSessionCookies.NAME + "=") }
                .substringBefore(';')
        val secondAddress = email()
        val challenge = start(secondAddress)
        val second =
            call(
                "/account/email/verify",
                payload(challenge, mail.codes.getValue(secondAddress)),
                "${browser(challenge)}; $first",
            )
        second.statusCode() shouldBe 204
        val newCookie =
            second
                .headers()
                .allValues(
                    "Set-Cookie",
                ).single { it.startsWith(HumanSessionCookies.NAME + "=") }
                .substringBefore(';')
        (newCookie == first) shouldBe false
        json.readTree(call("/account/session", cookie = newCookie).body())["contactEmail"].asText() shouldBe secondAddress
    }

    @Test
    fun `email readiness does not advertise unconfigured providers or contact information`() {
        val response = call("/account/methods")
        response.statusCode() shouldBe 200
        response.body() shouldBe """{"methods":["email"]}"""
        jdbc.sql("SELECT count(*) FROM human_account").query(Long::class.javaObjectType).single() shouldBe 0
    }
}
