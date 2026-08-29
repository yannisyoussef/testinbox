package email.testinbox.application.usecase

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.ExactAddressReservations
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import java.time.Clock

/**
 * Explicit early teardown. Marks the inbox DELETED (hard delete of rows and
 * blobs happens in the bounded async sweep, ADR-009) and starts the EXACT
 * local-part cooldown (ADR-021).
 */
class DeleteInbox(
    private val inboxes: InboxRepository,
    private val reservations: ExactAddressReservations,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val config: TestInboxConfig,
) {
    sealed interface Result {
        data object Deleted : Result

        data object NotFound : Result
    }

    fun execute(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
    ): Result {
        val now = clock.instant()
        return tx.required {
            val inbox = inboxes.markDeleted(workspaceId, inboxId, now) ?: return@required Result.NotFound
            if (inbox.addressMode == AddressMode.EXACT) {
                reservations.startCooldown(inboxId, now.plus(config.exactCooldown))
            }
            Result.Deleted
        }
    }
}
