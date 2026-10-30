package email.testinbox.persistence

import email.testinbox.application.identity.ProviderLoginCrypto
import email.testinbox.application.identity.SessionCredential
import email.testinbox.application.usecase.ConsumeProviderLoginState
import email.testinbox.application.usecase.PurgeProviderLoginState
import email.testinbox.application.usecase.RecordProviderLoginState
import email.testinbox.domain.identity.LoginProvider
import email.testinbox.domain.identity.ProviderLoginState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProviderLoginStatesTest : EmailLoginTestSupport() {
    @Autowired lateinit var providerStore: JdbcProviderLoginStates
    private val providerCrypto = ProviderLoginCrypto(ByteArray(32) { 11 })
    private val payload = """{"nonce":"fixture-nonce","code_verifier":"fixture-pkce"}"""

    @BeforeEach
    fun clearStates() {
        jdbc.sql("DELETE FROM provider_login_state").update()
    }

    private fun recorder() = RecordProviderLoginState(JdbcProviderLoginStates(jdbc), registration, transactions, providerCrypto, clock)

    private fun consumer(crypto: ProviderLoginCrypto = providerCrypto) =
        ConsumeProviderLoginState(JdbcProviderLoginStates(jdbc), transactions, crypto, clock)

    private data class Started(
        val state: String,
        val browser: String,
    ) {
        override fun toString(): String = "ProviderStarted([REDACTED])"
    }

    private fun startProvider(
        browser: String? = null,
        provider: LoginProvider = LoginProvider.GOOGLE,
    ): Started {
        val state = SessionCredential.generate().value
        val recorded =
            recorder()
                .execute(provider, state, payload, "198.51.100.1", browser)
                .shouldBeInstanceOf<RecordProviderLoginState.Result.Recorded>()
        return Started(state, recorded.browser.value)
    }

    @Test
    fun `state is encrypted at rest and atomically erased on its first matching consumption`() {
        val start = startProvider()
        val encoded = jdbc.sql("SELECT encrypted_request FROM provider_login_state").query(String::class.java).single()
        encoded.contains("fixture-pkce") shouldBe false
        encoded.contains(start.browser) shouldBe false
        consumer().execute(LoginProvider.GOOGLE, start.state, start.browser) shouldBe payload
        jdbc
            .sql("SELECT count(*) FROM provider_login_state WHERE encrypted_request IS NOT NULL")
            .query(Long::class.javaObjectType)
            .single() shouldBe 0
        consumer().execute(LoginProvider.GOOGLE, start.state, start.browser) shouldBe null
    }

    @Test
    fun `another browser provider or state cannot spend the legitimate attempt`() {
        val start = startProvider()
        consumer().execute(LoginProvider.GOOGLE, start.state, SessionCredential.generate().value) shouldBe null
        consumer().execute(LoginProvider.GITHUB, start.state, start.browser) shouldBe null
        consumer().execute(LoginProvider.GOOGLE, SessionCredential.generate().value, start.browser) shouldBe null
        consumer().execute(LoginProvider.EMAIL, start.state, start.browser) shouldBe null
        consumer().execute(LoginProvider.GOOGLE, "invalid", start.browser) shouldBe null
        consumer().execute(LoginProvider.GOOGLE, start.state, start.browser) shouldBe payload
    }

    @Test
    fun `expired and backwards clock callbacks cannot load protocol material`() {
        val expired = startProvider()
        clock.advance(600)
        consumer().execute(LoginProvider.GOOGLE, expired.state, expired.browser) shouldBe null
        val future = startProvider()
        clock.advance(-1)
        consumer().execute(LoginProvider.GOOGLE, future.state, future.browser) shouldBe null
    }

    @Test
    fun `one browser restart replaces its attempt across providers without affecting another browser`() {
        val first = startProvider()
        val other = startProvider()
        val replacement = startProvider(first.browser, LoginProvider.GITHUB)
        consumer().execute(LoginProvider.GOOGLE, first.state, first.browser) shouldBe null
        consumer().execute(LoginProvider.GOOGLE, other.state, other.browser) shouldBe payload
        consumer().execute(LoginProvider.GITHUB, replacement.state, replacement.browser) shouldBe payload
    }

    @Test
    fun `a wrong encryption key or tampered database ciphertext cannot resume authentication`() {
        val first = startProvider()
        consumer(ProviderLoginCrypto(ByteArray(32) { 12 })).execute(LoginProvider.GOOGLE, first.state, first.browser) shouldBe null
        consumer().execute(LoginProvider.GOOGLE, first.state, first.browser) shouldBe null
        val other = startProvider()
        jdbc.sql("UPDATE provider_login_state SET encrypted_request='invalid'").update()
        consumer().execute(LoginProvider.GOOGLE, other.state, other.browser) shouldBe null
    }

    @Test
    fun `eight independent consumers return the encrypted protocol request exactly once`() {
        val start = startProvider()
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val results =
            try {
                val futures =
                    (1..8).map {
                        executor.submit<String?> {
                            val independent = consumer()
                            ready.countDown()
                            check(go.await(10, TimeUnit.SECONDS))
                            independent.execute(LoginProvider.GOOGLE, start.state, start.browser)
                        }
                    }
                check(ready.await(10, TimeUnit.SECONDS))
                go.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                go.countDown()
                executor.shutdownNow()
            }
        results.count { it == payload } shouldBe 1
        results.count { it == null } shouldBe 7
    }

    @Test
    fun `source hourly and retained row ceilings include consumed and replaced attempts`() {
        repeat(RecordProviderLoginState.MAX_PER_SOURCE) { startProvider() }
        recorder().execute(LoginProvider.GOOGLE, SessionCredential.generate().value, payload, "198.51.100.1") shouldBe
            RecordProviderLoginState.Result.Limited
        // Different peers still share the same database-wide hourly ceiling.
        repeat(RecordProviderLoginState.MAX_PER_HOUR - RecordProviderLoginState.MAX_PER_SOURCE) { n ->
            recorder()
                .execute(LoginProvider.GOOGLE, SessionCredential.generate().value, payload, "203.0.113.${n / 10}")
                .shouldBeInstanceOf<RecordProviderLoginState.Result.Recorded>()
        }
        recorder().execute(LoginProvider.GOOGLE, SessionCredential.generate().value, payload, "192.0.2.1") shouldBe
            RecordProviderLoginState.Result.Limited
    }

    @Test
    fun `the retained inventory refuses excess then recovers only after its retention window`() {
        val at = clock.instant().minusSeconds(7200)
        jdbc
            .sql(
                """
                INSERT INTO provider_login_state (state_hash,browser_hash,source_hash,provider,created_at,expires_at)
                SELECT lpad(n::text,64,'0'), repeat('a',64), repeat('b',64), 'GOOGLE', :at, :expires
                FROM generate_series(1,:total) n
                """.trimIndent(),
            ).param("at", Timestamps.toDb(at))
            .param("expires", Timestamps.toDb(at.plusSeconds(600)))
            .param("total", RecordProviderLoginState.MAX_RETAINED)
            .update()
        recorder().execute(LoginProvider.GOOGLE, SessionCredential.generate().value, payload, "198.51.100.1") shouldBe
            RecordProviderLoginState.Result.Limited
        clock.advance(22 * 3600)
        startProvider()
        count("provider_login_state") shouldBe 1
    }

    @Test
    fun `a full day of spent states at the hourly limit never blocks a new sign in`() {
        val hours = ProviderLoginState.RETENTION.toHours().toInt() - 1
        jdbc
            .sql(
                """
                INSERT INTO provider_login_state (state_hash,browser_hash,source_hash,provider,created_at,expires_at)
                SELECT lpad(n::text,64,'0'), repeat('a',64), repeat('b',64), 'GOOGLE',
                    :now - (1 + n / :perHour) * interval '1 hour', :now - (1 + n / :perHour) * interval '1 hour' + interval '600 seconds'
                FROM generate_series(0,:total - 1) n
                """.trimIndent(),
            ).param("now", Timestamps.toDb(clock.instant()))
            .param("perHour", RecordProviderLoginState.MAX_PER_HOUR)
            .param("total", RecordProviderLoginState.MAX_PER_HOUR * hours)
            .update()
        startProvider()
        count("provider_login_state") shouldBe RecordProviderLoginState.MAX_PER_HOUR * hours + 1L
    }

    @Test
    fun `cleanup erases expired PKCE material before retaining only bounded rate metadata`() {
        startProvider()
        clock.advance(600)
        PurgeProviderLoginState(providerStore, registration, transactions, clock).execute()
        count("provider_login_state") shouldBe 1
        jdbc
            .sql("SELECT count(*) FROM provider_login_state WHERE encrypted_request IS NOT NULL")
            .query(Long::class.javaObjectType)
            .single() shouldBe 0
        clock.advance(24 * 3600)
        PurgeProviderLoginState(providerStore, registration, transactions, clock).execute()
        count("provider_login_state") shouldBe 0
    }

    @Test
    fun `email cannot create provider state and adapter mutations require a transaction`() {
        shouldThrow<IllegalArgumentException> {
            recorder().execute(LoginProvider.EMAIL, SessionCredential.generate().value, payload, "198.51.100.1")
        }
        shouldThrow<IllegalStateException> { providerStore.purge(clock.instant(), clock.instant()) }
        shouldThrow<IllegalStateException> { providerStore.volume("a".repeat(64), clock.instant()) }
        shouldThrow<IllegalStateException> { providerStore.replaceForBrowser("a".repeat(64)) }
        shouldThrow<IllegalStateException> { providerStore.consume("a".repeat(64), "b".repeat(64), LoginProvider.GOOGLE, clock.instant()) }
    }
}
