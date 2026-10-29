package email.testinbox.application.usecase

import email.testinbox.application.identity.EmailLoginCode
import email.testinbox.application.identity.EmailLoginCrypto
import email.testinbox.application.identity.SessionCredential
import email.testinbox.application.port.EmailLoginDelivery
import email.testinbox.application.port.EmailLoginEvent
import email.testinbox.application.port.EmailLoginObservations
import email.testinbox.application.port.EmailLoginVolume
import email.testinbox.application.port.EmailLogins
import email.testinbox.application.port.HumanAuthenticationLock
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.identity.ContactEmail
import email.testinbox.domain.identity.EmailLoginChallenge
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class RequestEmailLogin(
    private val store: EmailLogins,
    private val registration: HumanAuthenticationLock,
    private val transactions: TransactionRunner,
    private val crypto: EmailLoginCrypto,
    private val delivery: EmailLoginDelivery,
    private val clock: Clock,
    private val observations: EmailLoginObservations = EmailLoginObservations.NOOP,
    /** TestInbox's own inbound domains: a code is never sent to one of its inboxes. */
    private val serviceDomains: email.testinbox.domain.identity.ServiceMailDomains,
) {
    sealed interface Result {
        class Accepted(
            val id: UUID,
            val browser: SessionCredential,
            val expiresAt: Instant,
        ) : Result {
            override fun toString(): String = "EmailChallengeAccepted(id=$id, browser=[REDACTED], expiresAt=$expiresAt)"
        }

        data class Throttled(
            val retryAfter: Duration,
        ) : Result

        /**
         * The address is a TestInbox inbox. Refused like a malformed address: it is a
         * property of the address alone, so it reveals nothing about any account.
         */
        data object ServiceAddress : Result
    }

    fun execute(
        email: ContactEmail,
        verifiedSource: String,
        browserToken: String? = null,
    ): Result {
        if (serviceDomains.owns(email)) return Result.ServiceAddress
        val browser = SessionCredential.parse(browserToken) ?: SessionCredential.generate()
        val code = EmailLoginCode.generate()
        val challenge =
            transactions.required {
                registration.lock()
                val now = clock.instant()
                store.purge(now.minus(EmailLoginChallenge.RETENTION))
                val source = crypto.sourceHash(verifiedSource)
                val volume = store.volume(email, source, now)
                retryAfter(volume, now)?.let { return@required Preparation.Limited(it) }
                val id = UUID.randomUUID()
                val pending =
                    EmailLoginChallenge(
                        id,
                        email,
                        crypto.browserHash(browser),
                        source,
                        crypto.codeVerifier(id, code.value),
                        now,
                        now.plus(EmailLoginChallenge.LIFETIME),
                    )
                store.replaceForBrowser(pending.browserHash)
                store.insert(pending)
                Preparation.Queued(pending)
            }
        if (challenge is Preparation.Limited) {
            observations.record(EmailLoginEvent.THROTTLED)
            return Result.Throttled(challenge.retryAfter)
        }
        val pending = (challenge as Preparation.Queued).challenge
        // Never hold the global registration lock over an external SMTP call.
        val sent =
            try {
                delivery.send(email, code)
                true
            } catch (failure: Exception) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                false
            }
        transactions.required { store.finishDelivery(pending.id, sent) }
        observations.record(if (sent) EmailLoginEvent.SENT else EmailLoginEvent.DELIVERY_FAILED)
        // Delivery failure has the same public shape; its closed-enum metric is not optional in live wiring.
        return Result.Accepted(pending.id, browser, pending.expiresAt)
    }

    private sealed interface Preparation {
        data class Queued(
            val challenge: EmailLoginChallenge,
        ) : Preparation

        data class Limited(
            val retryAfter: Duration,
        ) : Preparation
    }

    private fun retryAfter(
        volume: EmailLoginVolume,
        now: Instant,
    ): Duration? {
        val resendAt = volume.recipientLastAt?.plus(RESEND_DELAY)
        return when {
            volume.retained >= MAX_RETAINED -> EmailLoginChallenge.RETENTION
            volume.lastHour >= MAX_PER_HOUR -> Duration.ofHours(1)
            volume.recipientQuarterHour >= MAX_PER_RECIPIENT || volume.sourceQuarterHour >= MAX_PER_SOURCE -> Duration.ofMinutes(15)
            resendAt != null && now < resendAt -> Duration.between(now, resendAt)
            else -> null
        }
    }

    companion object {
        const val MAX_RETAINED: Int = 5_000
        const val MAX_PER_HOUR: Int = 200
        const val MAX_PER_RECIPIENT: Int = 3
        const val MAX_PER_SOURCE: Int = 60
        val RESEND_DELAY: Duration = Duration.ofSeconds(60)
    }
}
