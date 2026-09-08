package email.testinbox.persistence

import email.testinbox.application.port.TransactionRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactionRunner(
    transactionManager: PlatformTransactionManager,
) : TransactionRunner {
    private val template =
        TransactionTemplate(transactionManager).apply {
            // Asserted, not inherited (ADR-033 §2). READ COMMITTED is a
            // precondition of the idempotency claim: under REPEATABLE READ the
            // claim's `ON CONFLICT DO UPDATE` raises `40001` instead of
            // blocking on the row, which would surface as a `500` on every
            // concurrent duplicate rather than the `409` the contract
            // promises — and it would do so only on a server whose
            // `default_transaction_isolation` had been tuned, so no test
            // would catch it.
            //
            // This is the Postgres default, so setting it changes nothing
            // today. The point is that it stops depending on a server-side
            // setting no part of this repository controls.
            isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        }

    override fun <T> required(block: () -> T): T = template.execute { block() }!!
}
