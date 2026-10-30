package email.testinbox.api

import email.testinbox.api.account.AccountClientSource
import email.testinbox.api.account.LocalLoginProvider
import email.testinbox.api.account.ProviderProtocols
import email.testinbox.application.identity.EmailLoginCode
import email.testinbox.application.port.EmailLoginDelivery
import email.testinbox.application.usecase.RecordProviderLoginState
import email.testinbox.application.usecase.RequestEmailLogin
import email.testinbox.domain.identity.ContactEmail
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The trusted client-address contract between the website's /account proxy
 * and the API, over real HTTP, for both sign-in budget consumers.
 */
@TestPropertySource(
    properties = [
        "testinbox.accounts.enabled=true",
        "testinbox.accounts.public-origin=https://console.example.invalid",
        "testinbox.accounts.client-address-secret=" + ClientAddressBudgetApiTest.SECRET,
        "testinbox.accounts.oauth.state-key-base64=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        "testinbox.accounts.email.enabled=true",
        "testinbox.accounts.email.verification-key-base64=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        "testinbox.accounts.email.smtp.host=relay.example.invalid",
        "testinbox.accounts.email.smtp.username=fixture",
        "testinbox.accounts.email.smtp.password=fixture",
        "testinbox.accounts.email.smtp.from=login@example.invalid",
    ],
)
@Import(ClientAddressBudgetApiTest.Fixture::class)
class ClientAddressBudgetApiTest : ApiIntegrationTestBase() {
    companion object {
        const val SECRET = "Zt4q9Lx2Wm7Rk1Pv8Hn3Bc6Yd0Sf5Gj-_Ae"
    }

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var provider: LocalLoginProvider

    @Autowired lateinit var mail: Sent

    @Autowired lateinit var registry: MeterRegistry

    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    class Sent : EmailLoginDelivery {
        val count = AtomicInteger()

        override fun send(
            email: ContactEmail,
            code: EmailLoginCode,
        ) {
            count.incrementAndGet()
        }
    }

    @TestConfiguration
    class Fixture {
        @Bean fun localProvider(): LocalLoginProvider = LocalLoginProvider()

        @Bean fun providerProtocols(provider: LocalLoginProvider): ProviderProtocols = provider.protocols()

        @Bean fun loginMail(): Sent = Sent()
    }

    @AfterAll fun closeProvider() {
        provider.close()
    }

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM email_login_challenge").update()
        jdbc.sql("DELETE FROM provider_login_state").update()
        mail.count.set(0)
    }

    private fun trusted(address: String) = mapOf(AccountClientSource.CLIENT_HEADER to address, AccountClientSource.SECRET_HEADER to SECRET)

    private fun requestCode(headers: Map<String, String> = emptyMap()): Int {
        val builder =
            HttpRequest
                .newBuilder(URI(url("/account/email/request")))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Origin", "https://console.example.invalid")
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.POST(HttpRequest.BodyPublishers.ofString("""{"email":"${UUID.randomUUID()}@example.invalid"}"""))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    private fun authorize(headers: Map<String, String> = emptyMap()): Int {
        val builder = HttpRequest.newBuilder(URI(url("/account/oauth/authorize/github"))).timeout(Duration.ofSeconds(10))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    /** Spends the shared proxy-peer budget with requests that each try to name a different client. */
    private fun exhaustPeer(spoof: (Int) -> Map<String, String>) {
        repeat(RequestEmailLogin.MAX_PER_SOURCE) { n -> requestCode(spoof(n)) shouldBe 202 }
    }

    private fun outcome(name: String) =
        registry
            .get(AccountClientSource.METRIC)
            .tag("outcome", name)
            .counter()
            .count()

    @Test
    fun `forwarded headers and a client address without the secret cannot reset the shared budget`() {
        val unauthenticated = outcome("unauthenticated")
        exhaustPeer { n -> mapOf("X-Forwarded-For" to "198.51.100.$n", AccountClientSource.CLIENT_HEADER to "198.51.100.$n") }
        requestCode(mapOf(AccountClientSource.CLIENT_HEADER to "203.0.113.99")) shouldBe 429
        requestCode(mapOf("X-Forwarded-For" to "203.0.113.98", "Forwarded" to "for=203.0.113.98")) shouldBe 429
        mail.count.get() shouldBe RequestEmailLogin.MAX_PER_SOURCE
        outcome("unauthenticated") - unauthenticated shouldBe (RequestEmailLogin.MAX_PER_SOURCE + 1).toDouble()
    }

    @Test
    fun `a wrong or duplicated secret cannot choose the source either`() {
        exhaustPeer { n ->
            mapOf(
                AccountClientSource.CLIENT_HEADER to "198.51.100.$n",
                AccountClientSource.SECRET_HEADER to SECRET.dropLast(1),
            )
        }
        requestCode(mapOf(AccountClientSource.CLIENT_HEADER to "203.0.113.99", AccountClientSource.SECRET_HEADER to SECRET + "x")) shouldBe
            429
        requestCode(mapOf(AccountClientSource.CLIENT_HEADER to "203.0.113.99", AccountClientSource.SECRET_HEADER to "")) shouldBe 429
        mail.count.get() shouldBe RequestEmailLogin.MAX_PER_SOURCE
    }

    @Test
    fun `with the right secret distinct clients get distinct budgets and the peer keeps its own`() {
        val trustedBefore = outcome("trusted")
        repeat(RequestEmailLogin.MAX_PER_SOURCE) { requestCode(trusted("198.51.100.10")) shouldBe 202 }
        requestCode(trusted("198.51.100.10")) shouldBe 429
        requestCode(trusted("198.51.100.11")) shouldBe 202
        requestCode() shouldBe 202 // the shared peer budget is separate from every named client
        outcome("trusted") - trustedBefore shouldBe (RequestEmailLogin.MAX_PER_SOURCE + 2).toDouble()
    }

    @Test
    fun `one IPv6 host cannot rotate addresses inside its 64-bit prefix`() {
        repeat(RequestEmailLogin.MAX_PER_SOURCE) { n -> requestCode(trusted("2001:db8:aa:bb::${n + 1}")) shouldBe 202 }
        requestCode(trusted("2001:db8:aa:bb:ffff:ffff:ffff:fffe")) shouldBe 429
        requestCode(trusted("2001:db8:aa:bc::1")) shouldBe 202
    }

    @Test
    fun `a malformed address with the right secret falls back to the shared peer budget`() {
        val malformed = outcome("malformed")
        val forms = listOf("example.invalid", "198.51.100.7:443", "198.51.100.7, 198.51.100.8", "[2001:db8::1]", "fe80::1%eth0")
        exhaustPeer { n -> trusted(forms[n % forms.size]) }
        requestCode() shouldBe 429
        requestCode(trusted("localhost")) shouldBe 429
        requestCode(trusted("198.51.100.20")) shouldBe 202
        outcome("malformed") - malformed shouldBe (RequestEmailLogin.MAX_PER_SOURCE + 1).toDouble()
    }

    @Test
    fun `provider authorization uses the same resolution for its source budget`() {
        repeat(RecordProviderLoginState.MAX_PER_SOURCE) { n ->
            authorize(mapOf(AccountClientSource.CLIENT_HEADER to "198.51.100.$n", "X-Forwarded-For" to "198.51.100.$n")) shouldBe 302
        }
        authorize(mapOf(AccountClientSource.CLIENT_HEADER to "203.0.113.1", AccountClientSource.SECRET_HEADER to "wrong")) shouldBe 429
        repeat(RecordProviderLoginState.MAX_PER_SOURCE) { authorize(trusted("198.51.100.30")) shouldBe 302 }
        authorize(trusted("198.51.100.30")) shouldBe 429
        authorize(trusted("2001:db8:cc:dd::1")) shouldBe 302
    }
}
