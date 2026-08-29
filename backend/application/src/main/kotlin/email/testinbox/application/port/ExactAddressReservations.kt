package email.testinbox.application.port

import email.testinbox.domain.InboxId
import email.testinbox.domain.inbox.ExactReservation
import java.time.Instant

sealed interface ReserveOutcome {
    data object Reserved : ReserveOutcome

    /** Lost to an existing ACTIVE/COOLDOWN reservation. [availableAt] is set when in cooldown. */
    data class Conflict(
        val availableAt: Instant?,
    ) : ReserveOutcome
}

/**
 * EXACT-mode local-part reservations (ADR-021). Concurrency ownership is
 * decided solely by the database unique constraint over the stable states
 * ACTIVE/COOLDOWN; expired cooldowns are reclaimed by a guarded
 * transactional UPDATE before insert (never a now()-dependent index
 * predicate).
 */
interface ExactAddressReservations {
    /**
     * Attempts to reserve [reservation.localPart]. Must run inside the same
     * transaction as the inbox insert so a reservation never exists without
     * its inbox. [now] drives cooldown reclaim.
     */
    fun reserve(
        reservation: ExactReservation,
        now: Instant,
    ): ReserveOutcome

    /** Moves the ACTIVE reservation held by [inboxId] into COOLDOWN until [availableAt]. */
    fun startCooldown(
        inboxId: InboxId,
        availableAt: Instant,
    )
}
