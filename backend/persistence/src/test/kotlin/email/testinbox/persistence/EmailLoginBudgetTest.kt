package email.testinbox.persistence

import email.testinbox.application.usecase.PurgeEmailLoginState
import email.testinbox.application.usecase.RequestEmailLogin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EmailLoginBudgetTest : EmailLoginTestSupport() {
    @Test
    fun `a premature resend returns only its remaining delay without invalidating the code`() {
        val original = start()
        clock.advance(10)
        val limited =
            request()
                .execute(original.email, "198.51.100.2", original.accepted.browser.value)
                .shouldBeInstanceOf<RequestEmailLogin.Result.Throttled>()
        limited.retryAfter shouldBe Duration.ofSeconds(50)
        count("email_login_challenge") shouldBe 1
        finish(original).shouldBeInstanceOf<email.testinbox.application.usecase.VerifyEmailLogin.Result.Verified>()
    }

    @Test
    fun `three recipient requests per quarter hour include different browsers and sources`() {
        val address = email()
        repeat(3) {
            request().execute(address, "198.51.100.$it").shouldBeInstanceOf<RequestEmailLogin.Result.Accepted>()
            clock.advance(60)
        }
        request()
            .execute(address, "198.51.100.99")
            .shouldBeInstanceOf<RequestEmailLogin.Result.Throttled>()
            .retryAfter shouldBe Duration.ofMinutes(15)
        sent.size shouldBe 3
        clock.advance(900)
        request().execute(address, "198.51.100.99").shouldBeInstanceOf<RequestEmailLogin.Result.Accepted>()
    }

    @Test
    fun `one source cannot evade its sending budget by changing recipient`() {
        repeat(RequestEmailLogin.MAX_PER_SOURCE) {
            request().execute(email(), "198.51.100.1").shouldBeInstanceOf<RequestEmailLogin.Result.Accepted>()
        }
        request().execute(email(), "198.51.100.1").shouldBeInstanceOf<RequestEmailLogin.Result.Throttled>()
        request().execute(email(), "198.51.100.2").shouldBeInstanceOf<RequestEmailLogin.Result.Accepted>()
        sent.size shouldBe RequestEmailLogin.MAX_PER_SOURCE + 1
    }

    @Test
    fun `the global hourly budget spans all recipients sources and request nodes`() {
        repeat(RequestEmailLogin.MAX_PER_HOUR) { n ->
            request().execute(email(), "198.51.100.${n / 10}").shouldBeInstanceOf<RequestEmailLogin.Result.Accepted>()
        }
        request().execute(email(), "203.0.113.1").shouldBeInstanceOf<RequestEmailLogin.Result.Throttled>()
        sent.size shouldBe 200
        clock.advance(3600)
        request().execute(email(), "203.0.113.1").shouldBeInstanceOf<RequestEmailLogin.Result.Accepted>()
        count("email_login_challenge") shouldBe 201
    }

    @Test
    fun `retained challenge cardinality is hard bounded even when hourly budget is clear`() {
        val at = clock.instant().minusSeconds(7200)
        jdbc
            .sql(
                """
                INSERT INTO email_login_challenge
                    (id,email,browser_hash,source_hash,code_verifier,created_at,expires_at,attempts_remaining,state)
                SELECT gen_random_uuid(), n || '@example.invalid', repeat('a',64), repeat('b',64), repeat('c',64),
                    :at, :expires, 5, 'FAILED' FROM generate_series(1,:total) n
                """.trimIndent(),
            ).param("at", Timestamps.toDb(at))
            .param("expires", Timestamps.toDb(at.plusSeconds(600)))
            .param("total", RequestEmailLogin.MAX_RETAINED)
            .update()
        request().execute(email(), "198.51.100.1").shouldBeInstanceOf<RequestEmailLogin.Result.Throttled>()
        count("email_login_challenge") shouldBe 5000
        sent.size shouldBe 0
        clock.advance(22 * 3600)
        request().execute(email(), "198.51.100.1").shouldBeInstanceOf<RequestEmailLogin.Result.Accepted>()
        count("email_login_challenge") shouldBe 1
    }

    @Test
    fun `cleanup removes expired login personal data without needing another login request`() {
        start()
        clock.advance(24 * 3600)
        PurgeEmailLoginState(store, registration, transactions, clock).execute()
        count("email_login_challenge") shouldBe 0
        count("human_account") shouldBe 0
    }

    @Test
    fun `concurrent requests for one recipient spend one send rather than bypassing resend limits`() {
        val address = email()
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val results =
            try {
                val futures =
                    (1..8).map { n ->
                        executor.submit<RequestEmailLogin.Result> {
                            ready.countDown()
                            check(go.await(10, TimeUnit.SECONDS))
                            request().execute(address, "198.51.100.$n")
                        }
                    }
                check(ready.await(10, TimeUnit.SECONDS))
                go.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                go.countDown()
                executor.shutdownNow()
            }
        results.count { it is RequestEmailLogin.Result.Accepted } shouldBe 1
        results.count { it is RequestEmailLogin.Result.Throttled } shouldBe 7
        sent.size shouldBe 1
        count("email_login_challenge") shouldBe 1
        count("human_auth_gate") shouldBe 1
    }

    @Test
    fun `an omitted transient registration lock recreates safely after restore`() {
        count("human_auth_gate") shouldBe 0
        start()
        count("human_auth_gate") shouldBe 1
        jdbc.sql("DELETE FROM human_auth_gate").update()
        start()
        count("human_auth_gate") shouldBe 1
        count("email_login_challenge") shouldBe 2
    }
}
