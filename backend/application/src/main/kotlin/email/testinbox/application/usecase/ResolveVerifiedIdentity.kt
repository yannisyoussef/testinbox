package email.testinbox.application.usecase

import email.testinbox.application.port.HumanAuthenticationLock
import email.testinbox.application.port.HumanIdentities
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.VerifiedLoginIdentity
import java.time.Clock
import java.util.UUID

/** Only verified provider/code proof may reach here; email equality never links identities. */
class ResolveVerifiedIdentity(
    private val identities: HumanIdentities,
    private val registration: HumanAuthenticationLock,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    /** TestInbox's own inbound domains: never a login or contact identity (see [ServiceMailDomains]). */
    private val serviceDomains: email.testinbox.domain.identity.ServiceMailDomains,
) {
    sealed interface Result {
        data class Resolved(
            val accountId: HumanAccountId,
        ) : Result

        data object DifferentMethod : Result

        data object CapacityUnavailable : Result

        /** The verified email is a TestInbox inbox address: refused before any account is found or made. */
        data object ServiceAddress : Result
    }

    fun execute(identity: VerifiedLoginIdentity): Result =
        transactions.required {
            if (serviceDomains.owns(identity.email)) return@required Result.ServiceAddress
            registration.lock()
            identities.find(identity)?.let { return@required Result.Resolved(it) }
            if (identities.findByContact(identity.email) != null) return@required Result.DifferentMethod
            if (identities.count() >= MAX_ACCOUNTS) return@required Result.CapacityUnavailable
            val id = HumanAccountId(UUID.randomUUID())
            identities.create(id, identity, clock.instant())
            Result.Resolved(id)
        }

    companion object {
        /** Total verified-account inventory cap; the waitlist has its own separate entry bound. */
        const val MAX_ACCOUNTS: Int = 1_030
    }
}
