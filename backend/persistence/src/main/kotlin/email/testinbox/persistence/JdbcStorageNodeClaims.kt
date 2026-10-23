package email.testinbox.persistence

import email.testinbox.application.port.StorageNodeClaims
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * A node id is claimed with a SESSION-level advisory lock, held on a
 * dedicated connection for the life of the process. A second process with
 * the same id fails to start. If the session dies, PostgreSQL releases the
 * lock, and [StorageNodeClaims.Claim.held] reports it.
 *
 * The key is a single bigint derived from the id, a key space disjoint from
 * the two-int ADR-035 locks `(35, 1)` and `(35, 2)`.
 */
class JdbcStorageNodeClaims(
    private val dataSource: DataSource,
) : StorageNodeClaims {
    override fun claim(nodeId: String): StorageNodeClaims.Claim? {
        val connection = dataSource.connection
        try {
            connection.autoCommit = true
            val acquired =
                connection.prepareStatement("SELECT pg_try_advisory_lock($KEY)").use { statement ->
                    statement.setString(1, nodeId)
                    statement.executeQuery().use { it.next() && it.getBoolean(1) }
                }
            if (!acquired) {
                connection.close()
                return null
            }
            return Held(connection, nodeId)
        } catch (e: SQLException) {
            runCatching { connection.close() }
            throw e
        }
    }

    private class Held(
        private val connection: Connection,
        private val nodeId: String,
    ) : StorageNodeClaims.Claim {
        override fun held(): Boolean = runCatching { connection.isValid(VALIDATION_SECONDS) }.getOrDefault(false)

        override fun close() {
            // Unlock explicitly: a pooled connection returns to the pool with
            // its session, and a session-level lock would go with it.
            runCatching {
                connection.prepareStatement("SELECT pg_advisory_unlock($KEY)").use { statement ->
                    statement.setString(1, nodeId)
                    statement.executeQuery().close()
                }
            }
            runCatching { connection.close() }
        }
    }

    private companion object {
        const val KEY = "hashtextextended('testinbox-storage-node:' || ?, 35)"
        const val VALIDATION_SECONDS = 2
    }
}
