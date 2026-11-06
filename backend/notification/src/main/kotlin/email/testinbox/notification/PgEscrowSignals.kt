package email.testinbox.notification

import email.testinbox.application.port.EscrowSignals
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.Properties
import java.util.concurrent.locks.LockSupport

/**
 * The slot-lane escrow keeper's wake-up source (structural-admission-design.md
 * §4.5; ADR-038 PROPOSED, M1 prototype): `LISTEN storage_escrow` on a
 * SESSION-scoped connection of its own, opened with `DriverManager`, never through
 * a pool or a transaction-mode pooler (a pooler accepts LISTEN and never delivers).
 *
 * While the connection is down, [await] waits out its timeout and reports nothing,
 * so the keeper falls back to its bounded poll; [degraded] says so. The next
 * [await] reconnects. Called from the keeper's one thread only.
 */
class PgEscrowSignals(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
    private val applicationName: String = "testinbox-escrow-keeper",
) : EscrowSignals {
    private var connection: Connection? = null

    @Volatile override var degraded: Boolean = true
        private set

    override fun await(timeout: Duration): Boolean {
        val pg =
            try {
                listening()
            } catch (e: java.sql.SQLException) {
                lost(e)
                LockSupport.parkNanos(timeout.toNanos())
                return false
            }
        return try {
            val notifications = pg.getNotifications(timeout.toMillis().toInt().coerceAtLeast(1))
            !notifications.isNullOrEmpty()
        } catch (e: java.sql.SQLException) {
            lost(e)
            false
        }
    }

    private fun listening(): PGConnection {
        connection?.let { return it.unwrap(PGConnection::class.java) }
        val props =
            Properties().apply {
                setProperty("user", username)
                setProperty("password", password)
                setProperty("ApplicationName", applicationName)
            }
        val opened = DriverManager.getConnection(jdbcUrl, props)
        opened.createStatement().use { it.execute("LISTEN $CHANNEL") }
        connection = opened
        degraded = false
        return opened.unwrap(PGConnection::class.java)
    }

    private fun lost(e: Exception) {
        if (!degraded) log.warn("escrow LISTEN lost, polling: {}", e.message)
        degraded = true
        runCatching { connection?.close() }
        connection = null
    }

    override fun close() {
        runCatching { connection?.close() }
        connection = null
    }

    private companion object {
        const val CHANNEL = "storage_escrow"
        val log = LoggerFactory.getLogger(PgEscrowSignals::class.java)
    }
}
