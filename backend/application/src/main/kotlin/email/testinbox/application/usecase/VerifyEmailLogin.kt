package email.testinbox.application.usecase

import email.testinbox.application.identity.EmailLoginCrypto
import email.testinbox.application.identity.SessionCredential
import email.testinbox.application.port.EmailLoginEvent
import email.testinbox.application.port.EmailLoginObservations
import email.testinbox.application.port.EmailLogins
import email.testinbox.application.port.HumanAuthenticationLock
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.identity.VerifiedLoginIdentity
import java.time.Clock
import java.util.UUID

class VerifyEmailLogin(
    private val store: EmailLogins,
    private val registration: HumanAuthenticationLock,
    private val transactions: TransactionRunner,
    private val crypto: EmailLoginCrypto,
    private val resolve: ResolveVerifiedIdentity,
    private val issue: IssueHumanSession,
    private val clock: Clock,
    private val observations: EmailLoginObservations = EmailLoginObservations.NOOP,
) {
    sealed interface Result {
        class Verified(
            val session: IssueHumanSession.Issued,
        ) : Result {
            override fun toString(): String = "EmailLoginVerified(session=[REDACTED])"
        }

        data object Rejected : Result

        data object DifferentMethod : Result

        data object CapacityUnavailable : Result
    }

    fun execute(
        id: UUID,
        browserToken: String?,
        code: String,
        replacing: String? = null,
    ): Result {
        val browser = SessionCredential.parse(browserToken) ?: return rejected()
        val result =
            transactions.required {
                registration.lock()
                val challenge = store.lockChallenge(id) ?: return@required Result.Rejected
                if (!crypto.matches(challenge.browserHash, crypto.browserHash(browser)) || !challenge.usableAt(clock.instant())) {
                    return@required Result.Rejected
                }
                if (!code.matches(Regex("[0-9]{8}")) || !crypto.matches(challenge.codeVerifier, crypto.codeVerifier(id, code))) {
                    store.failedAttempt(id)
                    return@required Result.Rejected
                }
                store.consume(id)
                when (val identity = resolve.execute(VerifiedLoginIdentity.email(challenge.email))) {
                    is ResolveVerifiedIdentity.Result.Resolved -> {
                        issue
                            .execute(identity.accountId, replacing)
                            ?.let(Result::Verified) ?: Result.Rejected
                    }

                    ResolveVerifiedIdentity.Result.DifferentMethod -> {
                        Result.DifferentMethod
                    }

                    ResolveVerifiedIdentity.Result.CapacityUnavailable -> {
                        Result.CapacityUnavailable
                    }

                    // Only a challenge created before the address check could reach here.
                    ResolveVerifiedIdentity.Result.ServiceAddress -> {
                        Result.Rejected
                    }
                }
            }
        observations.record(
            when (result) {
                is Result.Verified -> EmailLoginEvent.VERIFIED
                Result.Rejected -> EmailLoginEvent.REJECTED
                Result.DifferentMethod -> EmailLoginEvent.DIFFERENT_METHOD
                Result.CapacityUnavailable -> EmailLoginEvent.CAPACITY_UNAVAILABLE
            },
        )
        return result
    }

    private fun rejected(): Result.Rejected {
        observations.record(EmailLoginEvent.REJECTED)
        return Result.Rejected
    }
}
