package email.testinbox.application.port

import email.testinbox.application.identity.EmailLoginCode
import email.testinbox.domain.identity.ContactEmail
import email.testinbox.domain.identity.EmailLoginChallenge
import java.time.Instant
import java.util.UUID

data class EmailLoginVolume(
    val retained: Long,
    val lastHour: Long,
    val recipientQuarterHour: Long,
    val sourceQuarterHour: Long,
    val recipientLastAt: Instant?,
)

interface EmailLogins {
    fun purge(before: Instant)

    fun volume(
        email: ContactEmail,
        sourceHash: String,
        now: Instant,
    ): EmailLoginVolume

    fun replaceForBrowser(browserHash: String)

    fun insert(challenge: EmailLoginChallenge)

    /** Only PENDING can become SENT/FAILED; delayed sends cannot revive a replacement. */
    fun finishDelivery(
        id: UUID,
        sent: Boolean,
    )

    fun lockChallenge(id: UUID): EmailLoginChallenge?

    fun failedAttempt(id: UUID)

    fun consume(id: UUID)
}

/** Separate transactional mail, never inbound TestInbox SMTP or contact forwarding. */
fun interface EmailLoginDelivery {
    fun send(
        email: ContactEmail,
        code: EmailLoginCode,
    )
}

enum class EmailLoginEvent { SENT, DELIVERY_FAILED, THROTTLED, VERIFIED, REJECTED, DIFFERENT_METHOD, CAPACITY_UNAVAILABLE }

fun interface EmailLoginObservations {
    fun record(event: EmailLoginEvent)

    companion object {
        val NOOP: EmailLoginObservations = EmailLoginObservations { }
    }
}
