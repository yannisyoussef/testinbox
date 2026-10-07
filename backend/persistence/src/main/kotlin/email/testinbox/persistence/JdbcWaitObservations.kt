package email.testinbox.persistence

import email.testinbox.application.port.InboxObservation
import email.testinbox.application.port.StorageRefusalSnapshot
import email.testinbox.application.port.TenantStorageFigures
import email.testinbox.application.port.WaitObservations
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.message.Message
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * One wait evaluation's reads, in ONE short read-only REPEATABLE READ
 * transaction (ADR-035 §13c, ADR-020).
 *
 * Under READ COMMITTED each statement takes its own snapshot. The messages are
 * loaded in two statements (rows, then attachments) and the refusal record in
 * a third, so a refusal or a message committed between them would be seen by
 * one read and not the other, which is exactly the match/refusal race the
 * ADR decides by a single snapshot. REPEATABLE READ gives every statement of
 * the transaction the snapshot of its first, so the evaluation is one view:
 * `WaitObservationsTest` commits between the reads and proves it invisible,
 * and proves that a READ COMMITTED variant sees it.
 *
 * The transaction is its own (a caller's transaction would carry the wrong
 * isolation, and holding one across the park would pin a connection and a
 * snapshot for the whole window), and it ends when the evaluation returns.
 * Nothing here subscribes, parks or waits.
 *
 * `open` for the test mutants only; production never subclasses it.
 */
@Repository
open class JdbcWaitObservations(
    private val jdbc: JdbcClient,
    transactionManager: PlatformTransactionManager,
    private val messages: JdbcMessageRepository,
    private val visibility: JdbcStorageVisibility,
) : WaitObservations {
    private val transactions = TransactionTemplate(transactionManager)

    override fun <R : Any> observe(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
        evaluate: (InboxObservation) -> R,
    ): R {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "a wait evaluation must not run inside another transaction"
        }
        return checkNotNull(
            transactions.execute {
                // Stated, never inherited (the same reason JdbcStorageAdmission
                // states its own). It must be the transaction's first statement.
                jdbc.sql(isolation()).update()
                // Ownership was proven by the use case; filtering again here means
                // a future caller that skips that step still cannot match another
                // tenant's messages.
                val visible = messages.listVisible(inboxId).filter { it.workspaceId == workspaceId }
                betweenReads()
                val refusals = visibility.refusalsOf(workspaceId, inboxId)
                val observation = Observation(workspaceId, inboxId, visible, refusals)
                try {
                    evaluate(observation)
                } finally {
                    observation.closed = true
                }
            },
        )
    }

    /** The isolation statement. A test mutant weakens it to prove the suite notices. */
    protected open fun isolation(): String = "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY"

    /** Test seam between the message read and the refusal read: production is a no-op. */
    protected open fun betweenReads() {}

    private inner class Observation(
        private val workspaceId: WorkspaceId,
        private val inboxId: InboxId,
        override val messages: List<Message>,
        override val refusals: StorageRefusalSnapshot,
    ) : InboxObservation {
        /** Set when the evaluation returns: a stashed observation cannot read a later snapshot. */
        @Volatile var closed = false

        /** Inside the same transaction, so the same snapshot: the thread-bound connection is reused. */
        override fun storage(): TenantStorageFigures {
            check(!closed && TransactionSynchronizationManager.isActualTransactionActive()) {
                "storage figures are read inside the evaluation only"
            }
            val figures = visibility.read(workspaceId, setOf(inboxId))
            return TenantStorageFigures(inbox = figures.inbox(inboxId).usage, workspace = figures.workspace)
        }
    }
}
