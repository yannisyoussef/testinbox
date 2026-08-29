package email.testinbox.domain.inbox

import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import java.time.Instant
import java.util.UUID

/**
 * Reservation state for EXACT-mode local-parts (ADR-021).
 *
 * Cooldown is modeled as an explicit state plus [availableAt] — time drives
 * state transitions via guarded transactional reclaim; the uniqueness
 * constraint covers the stable states ACTIVE and COOLDOWN and its predicate
 * is deterministic (never a function of now()).
 */
enum class ReservationStatus { ACTIVE, COOLDOWN, RELEASED }

data class ExactReservation(
    val id: UUID,
    val workspaceId: WorkspaceId,
    val localPart: String,
    val inboxId: InboxId,
    val status: ReservationStatus,
    val reservedAt: Instant,
    /** When a COOLDOWN reservation becomes reclaimable. Null while ACTIVE. */
    val availableAt: Instant?,
)
