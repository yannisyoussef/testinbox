package email.testinbox.application.usecase

import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.identity.LoginProvider
import email.testinbox.domain.identity.VerifiedLoginIdentity

/** Only the maintained protocol adapter's verified identity may reach this operation. */
class SignInWithVerifiedProvider(
    private val resolve: ResolveVerifiedIdentity,
    private val issue: IssueHumanSession,
    private val transactions: TransactionRunner,
) {
    sealed interface Result {
        class SignedIn(
            val session: IssueHumanSession.Issued,
        ) : Result {
            override fun toString(): String = "ProviderSignedIn(session=[REDACTED])"
        }

        data object Rejected : Result

        data object DifferentMethod : Result

        data object CapacityUnavailable : Result
    }

    fun execute(
        identity: VerifiedLoginIdentity,
        replacing: String? = null,
    ): Result {
        if (identity.provider == LoginProvider.EMAIL) return Result.Rejected
        return transactions.required {
            when (val resolved = resolve.execute(identity)) {
                is ResolveVerifiedIdentity.Result.Resolved -> {
                    issue.execute(resolved.accountId, replacing)?.let(Result::SignedIn)
                        ?: Result.Rejected
                }

                ResolveVerifiedIdentity.Result.DifferentMethod -> {
                    Result.DifferentMethod
                }

                ResolveVerifiedIdentity.Result.CapacityUnavailable -> {
                    Result.CapacityUnavailable
                }

                // A provider account whose verified email is a TestInbox inbox address.
                ResolveVerifiedIdentity.Result.ServiceAddress -> {
                    Result.Rejected
                }
            }
        }
    }
}
