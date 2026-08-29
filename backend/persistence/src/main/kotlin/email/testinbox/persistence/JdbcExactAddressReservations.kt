package email.testinbox.persistence

import email.testinbox.application.port.ExactAddressReservations
import email.testinbox.application.port.ReserveOutcome
import email.testinbox.domain.InboxId
import email.testinbox.domain.inbox.ExactReservation
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * ADR-021 reservation adapter. Concurrency is decided solely by
 * `ux_exact_reservation_local_part` (partial unique over ACTIVE/COOLDOWN —
 * a deterministic predicate). Cooldown expiry is a guarded transactional
 * reclaim: expired COOLDOWN rows are flipped to RELEASED before the insert;
 * two racers serialize on the row lock and exactly one insert wins.
 */
@Repository
class JdbcExactAddressReservations(private val jdbc: JdbcClient) : ExactAddressReservations {
    override fun reserve(reservation: ExactReservation, now: Instant): ReserveOutcome {
        jdbc.sql(
            """
            UPDATE exact_address_reservation
               SET status = 'RELEASED'
             WHERE local_part = :localPart AND status = 'COOLDOWN' AND available_at <= :now
            """.trimIndent(),
        )
            .param("localPart", reservation.localPart)
            .param("now", Timestamps.toDb(now))
            .update()
        // ON CONFLICT DO NOTHING against the partial unique index: the database
        // still picks exactly one winner under concurrency, but the loser's
        // transaction stays usable (needed to read back availableAt).
        val inserted =
            jdbc.sql(
                """
                INSERT INTO exact_address_reservation
                       (id, workspace_id, local_part, inbox_id, status, reserved_at, available_at)
                VALUES (:id, :workspaceId, :localPart, :inboxId, :status, :reservedAt, :availableAt)
                ON CONFLICT (local_part) WHERE status IN ('ACTIVE', 'COOLDOWN') DO NOTHING
                """.trimIndent(),
            )
                .param("id", reservation.id)
                .param("workspaceId", reservation.workspaceId.value)
                .param("localPart", reservation.localPart)
                .param("inboxId", reservation.inboxId.value)
                .param("status", reservation.status.name)
                .param("reservedAt", Timestamps.toDb(reservation.reservedAt))
                .param("availableAt", reservation.availableAt?.let(Timestamps::toDb))
                .update()
        return if (inserted == 1) {
            ReserveOutcome.Reserved
        } else {
            ReserveOutcome.Conflict(availableAtOf(reservation.localPart))
        }
    }

    override fun startCooldown(inboxId: InboxId, availableAt: Instant) {
        jdbc.sql(
            """
            UPDATE exact_address_reservation
               SET status = 'COOLDOWN', available_at = :availableAt
             WHERE inbox_id = :inboxId AND status = 'ACTIVE'
            """.trimIndent(),
        )
            .param("availableAt", Timestamps.toDb(availableAt))
            .param("inboxId", inboxId.value)
            .update()
    }

    private fun availableAtOf(localPart: String): Instant? =
        jdbc.sql(
            """
            SELECT available_at FROM exact_address_reservation
             WHERE local_part = :localPart AND status IN ('ACTIVE', 'COOLDOWN')
            """.trimIndent(),
        )
            .param("localPart", localPart)
            .query { rs, _ -> Timestamps.fromDb(rs, "available_at") }
            .optional()
            .orElse(null)
}
