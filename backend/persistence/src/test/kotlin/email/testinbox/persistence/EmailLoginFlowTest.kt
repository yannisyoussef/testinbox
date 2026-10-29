package email.testinbox.persistence

import email.testinbox.application.identity.SessionCredential
import email.testinbox.application.port.EmailLoginDelivery
import email.testinbox.application.port.EmailLoginEvent
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.usecase.AuthenticateHumanSession
import email.testinbox.application.usecase.RequestEmailLogin
import email.testinbox.application.usecase.VerifyEmailLogin
import email.testinbox.domain.identity.EmailChallengeState
import email.testinbox.domain.identity.HumanSession
import email.testinbox.domain.identity.LoginProvider
import email.testinbox.domain.identity.VerifiedLoginIdentity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EmailLoginFlowTest : EmailLoginTestSupport() {
    @Test
    fun `request stores only keyed proof and no account or workspace`() {
        val before = count("workspace")
        val start = start()
        val row = transactions.required { store.lockChallenge(start.accepted.id) }
        checkNotNull(row).codeVerifier shouldBe crypto.codeVerifier(start.accepted.id, start.code)
        row.browserHash shouldBe crypto.browserHash(start.accepted.browser)
        row.sourceHash shouldBe crypto.sourceHash("198.51.100.1")
        row.state shouldBe EmailChallengeState.SENT
        row.attemptsRemaining shouldBe 5
        row.toString().contains(start.code) shouldBe false
        count("human_account") shouldBe 0
        count("workspace") shouldBe before
        events.toList() shouldBe listOf(EmailLoginEvent.SENT)
    }

    @Test
    fun `verified code creates one account and session but never auto admits`() {
        val start = start()
        val verified = finish(start).shouldBeInstanceOf<VerifyEmailLogin.Result.Verified>()
        count("human_account") shouldBe 1
        count("human_identity") shouldBe 1
        count("human_session") shouldBe 1
        count("beta_admission") shouldBe 0
        checkNotNull(AuthenticateHumanSession(sessions, transactions, clock).execute(verified.session.credential.value))
            .account.contactEmail shouldBe start.email.value
        finish(start) shouldBe VerifyEmailLogin.Result.Rejected
        count("human_session") shouldBe 1
    }

    @Test
    fun `a code cannot be used in another browser or for another challenge`() {
        val first = start()
        val second = start()
        verifier().execute(first.accepted.id, second.accepted.browser.value, first.code) shouldBe VerifyEmailLogin.Result.Rejected
        verifier().execute(first.accepted.id, null, first.code) shouldBe VerifyEmailLogin.Result.Rejected
        verifier().execute(UUID.randomUUID(), first.accepted.browser.value, first.code) shouldBe VerifyEmailLogin.Result.Rejected
        val row = transactions.required { store.lockChallenge(first.accepted.id) }
        checkNotNull(row).attemptsRemaining shouldBe 5
        finish(first).shouldBeInstanceOf<VerifyEmailLogin.Result.Verified>()
    }

    @Test
    fun `five wrong codes exhaust even when their format is malformed`() {
        val start = start()
        repeat(5) { verifier().execute(start.accepted.id, start.accepted.browser.value, "bad") shouldBe VerifyEmailLogin.Result.Rejected }
        val row = transactions.required { store.lockChallenge(start.accepted.id) }
        checkNotNull(row).attemptsRemaining shouldBe 0
        row.state shouldBe EmailChallengeState.EXHAUSTED
        finish(start) shouldBe VerifyEmailLogin.Result.Rejected
        count("human_account") shouldBe 0
    }

    @Test
    fun `expiry is exclusive and backwards time refuses without account creation`() {
        val expired = start()
        clock.advance(600)
        finish(expired) shouldBe VerifyEmailLogin.Result.Rejected
        val future = start()
        clock.advance(-1)
        finish(future) shouldBe VerifyEmailLogin.Result.Rejected
        count("human_account") shouldBe 0
    }

    @Test
    fun `resend replaces only that browser and cannot cancel another browser's usable code`() {
        val address = email()
        val original = start(address)
        clock.advance(60)
        val otherBrowser = start(address)
        clock.advance(60)
        val replacement = start(address, original.accepted.browser.value)
        finish(original) shouldBe VerifyEmailLogin.Result.Rejected
        finish(otherBrowser).shouldBeInstanceOf<VerifyEmailLogin.Result.Verified>()
        finish(replacement).shouldBeInstanceOf<VerifyEmailLogin.Result.Verified>()
        count("human_account") shouldBe 1
        count("human_session") shouldBe 2
    }

    @Test
    fun `delivery failure is generic observable and cannot verify even a known code`() {
        var code = ""
        val accepted =
            request(
                EmailLoginDelivery { _, secret ->
                    code = secret.value
                    error("provider failure")
                },
            ).execute(email(), "198.51.100.2")
                .shouldBeInstanceOf<RequestEmailLogin.Result.Accepted>()
        verifier().execute(accepted.id, accepted.browser.value, code) shouldBe VerifyEmailLogin.Result.Rejected
        events.contains(EmailLoginEvent.DELIVERY_FAILED) shouldBe true
        count("human_account") shouldBe 0
    }

    @Test
    fun `a delayed successful send never revives a replaced pending challenge`() {
        val address = email()
        val binding = SessionCredential.generate()
        var oldCode = ""
        var replacement: Started? = null
        val delayed =
            request(
                EmailLoginDelivery { _, code ->
                    oldCode = code.value
                    clock.advance(60)
                    replacement = start(address, binding.value)
                },
            ).execute(address, "198.51.100.1", binding.value).shouldBeInstanceOf<RequestEmailLogin.Result.Accepted>()
        transactions.required { store.lockChallenge(delayed.id) }?.state shouldBe EmailChallengeState.REPLACED
        verifier().execute(delayed.id, binding.value, oldCode) shouldBe VerifyEmailLogin.Result.Rejected
        finish(checkNotNull(replacement)).shouldBeInstanceOf<VerifyEmailLogin.Result.Verified>()
    }

    @Test
    fun `email equality cannot add a new login method to an existing provider identity`() {
        val address = email()
        resolver().execute(VerifiedLoginIdentity(LoginProvider.GOOGLE, "https://accounts.google.com", "stable-subject", address))
        val attempt = start(address)
        finish(attempt) shouldBe VerifyEmailLogin.Result.DifferentMethod
        count("human_account") shouldBe 1
        count("human_identity") shouldBe 1
        count("human_session") shouldBe 0
    }

    @Test
    fun `suspension prevents new email sessions without reactivating an account`() {
        val start = start()
        finish(start).shouldBeInstanceOf<VerifyEmailLogin.Result.Verified>()
        jdbc.sql("UPDATE human_account SET state='SUSPENDED'").update()
        clock.advance(60)
        val again = start(start.email)
        finish(again) shouldBe VerifyEmailLogin.Result.Rejected
        count("human_account") shouldBe 1
        count("human_session") shouldBe 1
    }

    @Test
    fun `a session insert failure rolls back code consumption and new identity`() {
        val start = start()
        val broken =
            object : HumanSessions by sessions {
                override fun insert(session: HumanSession) {
                    sessions.insert(session)
                    error("session insert failed")
                }
            }
        shouldThrow<IllegalStateException> { verifier(broken).execute(start.accepted.id, start.accepted.browser.value, start.code) }
        count("human_account") shouldBe 0
        count("human_identity") shouldBe 0
        count("human_session") shouldBe 0
        finish(start).shouldBeInstanceOf<VerifyEmailLogin.Result.Verified>()
    }

    @Test
    fun `independent concurrent verifiers consume one code exactly once`() {
        val start = start()
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val results =
            try {
                val futures =
                    (1..8).map {
                        executor.submit<VerifyEmailLogin.Result> {
                            ready.countDown()
                            check(go.await(10, TimeUnit.SECONDS))
                            finish(start)
                        }
                    }
                check(ready.await(10, TimeUnit.SECONDS))
                go.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                go.countDown()
                executor.shutdownNow()
            }
        results.count { it is VerifyEmailLogin.Result.Verified } shouldBe 1
        results.count { it == VerifyEmailLogin.Result.Rejected } shouldBe 7
        count("human_account") shouldBe 1
        count("human_identity") shouldBe 1
        count("human_session") shouldBe 1
    }
}
