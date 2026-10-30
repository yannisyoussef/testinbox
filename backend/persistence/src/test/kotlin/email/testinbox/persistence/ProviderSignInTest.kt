package email.testinbox.persistence

import email.testinbox.application.port.HumanSessions
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.application.usecase.SignInWithVerifiedProvider
import email.testinbox.domain.identity.HumanSession
import email.testinbox.domain.identity.LoginProvider
import email.testinbox.domain.identity.VerifiedLoginIdentity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Assumes upstream proof; framework signature/state/nonce tests belong to the protocol adapter. */
class ProviderSignInTest : EmailLoginTestSupport() {
    private fun signIn(sessionStore: HumanSessions = sessions) =
        SignInWithVerifiedProvider(resolver(), IssueHumanSession(sessionStore, transactions, clock), transactions)

    private fun identity() = VerifiedLoginIdentity(LoginProvider.GITHUB, "https://github.com", "12345", email())

    @Test
    fun `verified provider proof gets one account and a session with no participant allocation`() {
        val identity = identity()
        signIn().execute(identity).shouldBeInstanceOf<SignInWithVerifiedProvider.Result.SignedIn>()
        signIn().execute(identity).shouldBeInstanceOf<SignInWithVerifiedProvider.Result.SignedIn>()
        count("human_account") shouldBe 1
        count("human_identity") shouldBe 1
        count("human_session") shouldBe 2
        count("beta_admission") shouldBe 0
    }

    @Test
    fun `a matching contact cannot link methods and email cannot enter the provider issuer`() {
        val identity = identity()
        resolver().execute(VerifiedLoginIdentity.email(identity.email))
        signIn().execute(identity) shouldBe SignInWithVerifiedProvider.Result.DifferentMethod
        signIn().execute(VerifiedLoginIdentity.email(identity.email)) shouldBe SignInWithVerifiedProvider.Result.Rejected
        count("human_session") shouldBe 0
    }

    @Test
    fun `a suspended identity cannot start another provider session`() {
        val identity = identity()
        signIn().execute(identity)
        jdbc.sql("UPDATE human_account SET state='SUSPENDED'").update()
        signIn().execute(identity) shouldBe SignInWithVerifiedProvider.Result.Rejected
        count("human_session") shouldBe 1
    }

    @Test
    fun `failure to issue the provider session rolls back its new identity and account`() {
        val broken =
            object : HumanSessions by sessions {
                override fun insert(session: HumanSession) {
                    sessions.insert(session)
                    error("provider session failed")
                }
            }
        shouldThrow<IllegalStateException> { signIn(broken).execute(identity()) }
        count("human_account") shouldBe 0
        count("human_identity") shouldBe 0
        count("human_session") shouldBe 0
    }
}
