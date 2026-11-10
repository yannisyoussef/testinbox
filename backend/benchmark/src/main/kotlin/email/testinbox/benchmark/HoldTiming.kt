package email.testinbox.benchmark

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource

/**
 * The focused A′ measurement's lock-hold timer (`--focused`, #124 6684712608), never
 * gate evidence: a JDBC proxy over the scenario's pool that OBSERVES T1's statements
 * on the event's thread and changes none of them. The T1 adapter is production's
 * `JdbcStorageAdmission`, unchanged and unsubclassed.
 *
 * Per event, client side, in [EventTimings]:
 * - **hold**: from the return of `SELECT pg_advisory_xact_lock(…)` to the return of
 *   the transaction's `Connection.commit()`. The server holds `(35, 1)` from the grant
 *   to the commit; the client sees the grant half a round trip later and the release
 *   half a round trip earlier than the commit's return, so this is the server's hold
 *   to within about one round trip, and the same definition before and after any change.
 * - **snapshot**, **insert**, **commit**: each statement's client-observed time
 *   (execution, transfer and driver), the three steps inside the hold.
 *
 * Only the first transaction after a lock grant is T1's; T2's statements on the same
 * thread come after its commit and are not recorded.
 *
 * Every execution of the snapshot statement, event or not, is also counted on its
 * physical connection in the [ConnectionLedger] (the connection the pool's proxy
 * wraps: Hikari's `unwrap(Connection)` returns its driver connection), and a T1 records
 * that ordinal, so it can be attributed to a cold or a warm connection ([T1Planning]).
 */
object HoldTiming {
    private const val LOCK_PREFIX = "SELECT pg_advisory_xact_lock("
    private const val SNAPSHOT_MARK = "SELECT 'GLOBAL' AS scope"
    private const val INSERT_PREFIX = "INSERT INTO storage_reservation"

    fun wrap(
        dataSource: DataSource,
        ledger: ConnectionLedger = ConnectionLedger(),
    ): DataSource = proxy(dataSource, DataSource::class.java, DataSourceHandler(dataSource, ledger))

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(
        target: Any,
        type: Class<T>,
        handler: InvocationHandler,
    ): T = Proxy.newProxyInstance(target.javaClass.classLoader ?: type.classLoader, arrayOf(type), handler) as T

    /** Invokes [method] on [target], rethrowing the target's own exception rather than a reflection wrapper. */
    private fun forward(
        target: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private class DataSourceHandler(
        private val target: DataSource,
        private val ledger: ConnectionLedger,
    ) : InvocationHandler {
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            val result = forward(target, method, args)
            return if (method.name == "getConnection" && result is Connection) {
                proxy(result, Connection::class.java, ConnectionHandler(result, physical(result), ledger))
            } else {
                result
            }
        }

        /** The driver connection under the pool's proxy, or the connection itself where nothing is wrapped. */
        private fun physical(connection: Connection): Any =
            runCatching { connection.unwrap(Connection::class.java) }.getOrNull() ?: connection
    }

    private class ConnectionHandler(
        private val target: Connection,
        private val physical: Any,
        private val ledger: ConnectionLedger,
    ) : InvocationHandler {
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            if (method.name == "commit") {
                val timings = EventTimings.current.get()
                val started = System.nanoTime()
                val result = forward(target, method, args)
                val end = System.nanoTime()
                timings?.steps?.commit(started, end)
                return result
            }
            val result = forward(target, method, args)
            val step = (args?.firstOrNull() as? String)?.let(::kindOf)
            return if (method.name == "prepareStatement" && result is PreparedStatement && step != null) {
                val counted = if (step == Step.SNAPSHOT) Counted(ledger, physical, args!!.first() as String) else null
                proxy(result, PreparedStatement::class.java, StatementHandler(result, step, counted))
            } else {
                result
            }
        }

        private fun kindOf(sql: String): Step? =
            when {
                sql.startsWith(LOCK_PREFIX) -> Step.LOCK
                sql.contains(SNAPSHOT_MARK) -> Step.SNAPSHOT
                sql.trimStart().startsWith(INSERT_PREFIX) -> Step.INSERT
                else -> null
            }
    }

    /** A snapshot statement's place in the ledger: which physical connection, which text. */
    private class Counted(
        val ledger: ConnectionLedger,
        val physical: Any,
        val sql: String,
    )

    private class StatementHandler(
        private val target: PreparedStatement,
        private val step: Step,
        private val counted: Counted?,
    ) : InvocationHandler {
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            if (!method.name.startsWith("execute")) return forward(target, method, args)
            val ordinal = counted?.let { it.ledger.executed(it.physical, it.sql) } ?: 0
            val timings = EventTimings.current.get() ?: return forward(target, method, args)
            val started = System.nanoTime()
            val result = forward(target, method, args)
            timings.steps.statement(step, started, System.nanoTime(), ordinal)
            return result
        }
    }

    enum class Step { LOCK, SNAPSHOT, INSERT }
}
