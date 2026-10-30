package email.testinbox.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Bounded holds for the offline operator commands (`account-lifecycle`,
 * `admission`, `storage-budget`). They take the cohort row and the ADR-035
 * `(35, 1)` admission lock that every T1 needs, so a stalled or abandoned
 * operator session must not hold them indefinitely (ADR-035 §4 bounded
 * holds). The values are T1's own: a 5 s lock wait, a statement bound of the
 * lock wait plus 5 s, and 10 s idle inside the transaction. On expiry
 * PostgreSQL aborts the operator's transaction and nothing is committed.
 */
object OperatorTransactionBounds {
    const val LOCK_TIMEOUT_MS: Long = 5_000
    const val STATEMENT_TIMEOUT_MS: Long = 10_000
    const val IDLE_IN_TRANSACTION_MS: Long = 10_000

    /** The same bounds as PostgreSQL connection `options`, for every transaction of an operator session. */
    fun sessionOptions(): String =
        "-c lock_timeout=$LOCK_TIMEOUT_MS -c statement_timeout=$STATEMENT_TIMEOUT_MS " +
            "-c idle_in_transaction_session_timeout=$IDLE_IN_TRANSACTION_MS"

    /** Must run inside the operator's transaction, before its first lock. */
    fun apply(jdbc: JdbcClient) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "operator bounds apply to a transaction" }
        // SET takes no bind parameters; every value is a compile-time constant.
        jdbc
            .sql(
                "SET LOCAL lock_timeout = '${LOCK_TIMEOUT_MS}ms'; SET LOCAL statement_timeout = '${STATEMENT_TIMEOUT_MS}ms'; " +
                    "SET LOCAL idle_in_transaction_session_timeout = '${IDLE_IN_TRANSACTION_MS}ms'",
            ).update()
    }
}
