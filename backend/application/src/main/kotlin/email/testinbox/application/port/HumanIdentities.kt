package email.testinbox.application.port

import email.testinbox.domain.identity.ContactEmail
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.VerifiedLoginIdentity
import java.time.Instant

/** One DB lock serializes registration and the bounded transient login inventory. */
fun interface HumanAuthenticationLock {
    fun lock()
}

interface HumanIdentities {
    fun find(identity: VerifiedLoginIdentity): HumanAccountId?

    fun findByContact(email: ContactEmail): HumanAccountId?

    fun count(): Long

    fun create(
        id: HumanAccountId,
        identity: VerifiedLoginIdentity,
        at: Instant,
    )
}
