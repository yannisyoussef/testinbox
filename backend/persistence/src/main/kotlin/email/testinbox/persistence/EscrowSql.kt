package email.testinbox.persistence

import org.springframework.jdbc.core.support.AbstractSqlTypeValue
import java.sql.Connection
import java.time.Duration

/**
 * The slot-lane escrow's statement texts (ADR-038 PROPOSED; M1 prototype). The
 * functions they call exist only where V28-proto was applied: the storage
 * benchmark's disposable database and the prototype tests' throwaway databases.
 *
 * [REGRANT] is the ONE global regrant (§7, "How the slow path is built"): an
 * ArchUnit field-access rule lets only [EscrowSlowPath] read it, within T1's own
 * transaction (the slow path) or the keeper's refill-only one.
 */
object EscrowSql {
    /** The drain fence: `(35, 1)`, then every lane FOR UPDATE, then the transaction-local fence flag. */
    const val FENCE = "SELECT storage_t1_fence(true)"

    /** The slow path's rank-6 locks: the event's scope rows, created when missing, in key order. */
    const val LOCK_SCOPES = "SELECT storage_escrow_lock_scopes(CAST(:ws AS uuid[]), CAST(:ib AS uuid[]))"

    /**
     * The one regrant: the lanes, then each of the event's scope rows, each from the truth
     * read after the insert. A real static field, not a `const` (which the compiler would
     * inline into every reader), so the field-access rule in `EscrowRuleTest` sees each read.
     */
    @JvmField
    val REGRANT: String =
        "SELECT * FROM storage_escrow_regrant(CAST(:ws AS uuid[]), CAST(:ib AS uuid[]), :ready, :reason, :binding, :maxStored, " +
            "CAST(:share AS numeric), :block, :den, :omax)"

    /** The fast path's lock statement (§4.2 step 3). [JdbcEscrowAdmission] reads it; its prefix is what timing proxies match. */
    const val LOCK =
        "SELECT * FROM storage_escrow_lock(:node, :lane, CAST(:ws AS uuid[]), CAST(:wsp AS bigint[]), CAST(:wsf AS bigint[]), " +
            "CAST(:ib AS uuid[]), CAST(:ibp AS bigint[]), :binding, :maxStored, CAST(:share AS numeric), :block, :den, :omax)"

    /** The fast path's configure step: every bound in ONE round trip, the lock statement's wait at L_w. */
    fun configure(
        scopeWait: Duration,
        statementTimeout: Duration,
        idle: Duration,
    ): String =
        "SET TRANSACTION ISOLATION LEVEL READ COMMITTED; SET LOCAL synchronous_commit = on; " +
            "SET LOCAL lock_timeout = '${scopeWait.toMillis()}ms'; SET LOCAL statement_timeout = '${statementTimeout.toMillis()}ms'; " +
            "SET LOCAL idle_in_transaction_session_timeout = '${idle.toMillis()}ms'"

    /** `storage_escrow_admit` and T1's COMMIT in ONE round trip, at mode a's lock timeout. */
    fun admit(lockTimeout: Duration): String =
        "SET LOCAL lock_timeout = '${lockTimeout.toMillis()}ms'; " +
            "CALL storage_escrow_admit(:node, :lane, CAST(:m AS uuid[]), CAST(:w AS uuid[]), CAST(:i AS uuid[]), CAST(:k AS text[]), " +
            ":kpc, :bytes, CAST(:created AS timestamptz), CAST(:deadline AS timestamptz), :generation, :binding, :block, :den, :omax);\n" +
            "COMMIT"

    /** One SQL array bind of [type]. */
    internal fun array(
        type: String,
        values: List<Any>,
    ): AbstractSqlTypeValue =
        object : AbstractSqlTypeValue() {
            override fun createTypeValue(
                connection: Connection,
                sqlType: Int,
                typeName: String?,
            ): Any = connection.createArrayOf(type, values.toTypedArray())
        }
}
