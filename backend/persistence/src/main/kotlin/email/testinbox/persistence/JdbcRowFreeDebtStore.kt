package email.testinbox.persistence

import email.testinbox.application.port.ObservedFootprint
import email.testinbox.application.port.RowFreeDebtStore
import email.testinbox.application.port.StorageAdmissionUnavailableException
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations
import java.sql.SQLException
import java.time.Duration

/**
 * Rule (P) on PostgreSQL (filesystem-containment contract §2.1; TI-STORAGE-006E
 * PR D). The decision and the pending row share one transaction under T1's
 * admission lock (35, 1), with T1's lock timeout, so a (P) write and a T1 copy,
 * or two (P) writes, never both pass against the same snapshot.
 */
class JdbcRowFreeDebtStore(
    private val jdbc: JdbcClient,
    private val transactions: TransactionOperations,
    private val lockTimeout: Duration = JdbcStorageAdmission.DEFAULT_LOCK_TIMEOUT,
) : RowFreeDebtStore {
    override fun admit(
        key: String,
        bytes: Long,
        objects: Long,
        source: String,
        decide: (ObservedFootprint?) -> Boolean,
    ): Boolean =
        try {
            // Its own transaction, never a caller's: the pending row must commit before the
            // delete or PUT it charges.
            check(
                !org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive(),
            ) {
                "rule (P) runs in its own transaction"
            }
            checkNotNull(
                transactions.execute {
                    // T1's hygiene: READ COMMITTED (the snapshot is taken AFTER the lock), and a
                    // stalled holder of (35, 1) is cut off rather than blocking every T1.
                    jdbc.sql("SET TRANSACTION ISOLATION LEVEL READ COMMITTED").update()
                    jdbc.sql("SET LOCAL lock_timeout = '${lockTimeout.toMillis()}ms'").update()
                    jdbc
                        .sql(
                            "SET LOCAL statement_timeout = '${lockTimeout.plusSeconds(5).toMillis()}ms'; " +
                                "SET LOCAL idle_in_transaction_session_timeout = " +
                                "'${JdbcStorageAdmission.IDLE_IN_TRANSACTION.toMillis()}ms'",
                        ).update()
                    jdbc
                        .sql("SELECT pg_advisory_xact_lock(:class, :admission)")
                        .param("class", JdbcStorageAdmission.STORAGE_LOCK_CLASS)
                        .param("admission", JdbcStorageAdmission.ADMISSION_LOCK)
                        .query()
                        .listOfRows()
                    if (pending(key)) {
                        true
                    } else {
                        val observed =
                            jdbc
                                .sql("WITH ${FootprintSql.CTES} SELECT ${FootprintSql.COLUMNS}")
                                .query {
                                    rs,
                                    _,
                                    ->
                                    FootprintSql.read(rs)
                                }.single()
                        decide(observed).also { admitted ->
                            if (admitted && isProbe(key)) {
                                // The probe's own definer function: (0 B, 1 object), probe keys only.
                                // It is all the ingestion role may call (production.md).
                                check(bytes == 0L && objects == 1L) { "a probe is one empty object" }
                                jdbc
                                    .sql("SELECT storage_record_probe_debt(:key)")
                                    .param("key", key)
                                    .query()
                                    .listOfRows()
                            } else if (admitted) {
                                jdbc
                                    .sql("SELECT storage_record_pending_debt(:key, :bytes, :objects, :source)")
                                    .param("key", key)
                                    .param("bytes", bytes)
                                    .param("objects", objects)
                                    .param("source", source)
                                    .query()
                                    .listOfRows()
                            }
                        }
                    }
                },
            )
        } catch (e: DataAccessException) {
            if (isTimeout(e)) throw StorageAdmissionUnavailableException("rule (P) could not take the admission lock", e)
            throw e
        }

    override fun resolve(key: String): Boolean =
        jdbc
            .sql(if (isProbe(key)) "SELECT storage_resolve_probe_debt(:key)" else "SELECT storage_resolve_pending_debt(:key)")
            .param("key", key)
            .query(Int::class.java)
            .single() > 0

    override fun pendingOlderThan(
        age: Duration,
        limit: Int,
    ): List<String> =
        jdbc
            .sql(
                """
                SELECT object_key FROM storage_deletion_debt
                 WHERE incurred_at = 'infinity'::timestamptz
                   AND recorded_at < clock_timestamp() - make_interval(secs => :age)
                 ORDER BY recorded_at
                 LIMIT :limit
                """.trimIndent(),
            ).param("age", age.toSeconds().toDouble())
            .param("limit", limit)
            .query { rs, _ -> rs.getString(1) }
            .list()

    private fun pending(key: String): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM storage_deletion_debt WHERE object_key = :key AND incurred_at = 'infinity'::timestamptz)")
            .param("key", key)
            .query(Boolean::class.java)
            .single()

    private fun isProbe(key: String) = key.startsWith(PROBE_PREFIX)

    private fun isTimeout(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState in TIMEOUT_STATES }

    private companion object {
        val TIMEOUT_STATES = setOf("55P03", "57014", "25P03")
        const val PROBE_PREFIX = "_probe/"
    }
}
