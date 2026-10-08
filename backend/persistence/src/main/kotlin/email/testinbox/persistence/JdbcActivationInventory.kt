package email.testinbox.persistence

import email.testinbox.application.port.ActivationInventory
import email.testinbox.application.port.DatabaseSession
import email.testinbox.application.port.StorageNodeRow
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The activation barrier's reads (ADR-035 §14 Phase 3 (a)), under the
 * application role's own privileges.
 *
 * Verified against PostgreSQL 16: a role without `pg_read_all_stats` reads
 * the `application_name`, `usename` and `backend_type` of its OWN role's
 * sessions, while other roles' sessions come back with those columns hidden
 * and so fall out of this query. The result is therefore exactly the
 * application role's client sessions in this database: the set ADR-035 §14 (a)
 * allowlists, since an old binary can only connect as that role. No broader
 * privilege is granted (TI-STORAGE-006 §17); other roles and other databases
 * are Ops's part of the inventory, read by `scripts/check-storage-activation.sh`
 * under whatever privileges Ops give it (with `--application-role` to apply
 * the same scope).
 */
class JdbcActivationInventory(
    private val jdbc: JdbcClient,
) : ActivationInventory {
    override fun sessions(): List<DatabaseSession> =
        jdbc
            .sql(
                """
                SELECT coalesce(application_name, ''), coalesce(usename, '')
                  FROM pg_stat_activity
                 WHERE datname = current_database()
                   AND backend_type = 'client backend'
                 ORDER BY 1, 2
                """.trimIndent(),
            ).query { rs, _ -> DatabaseSession(rs.getString(1), rs.getString(2)) }
            .list()

    override fun nodes(): List<StorageNodeRow> =
        jdbc
            .sql("SELECT node_id, capability, heartbeat_at, clean_shutdown FROM storage_node ORDER BY node_id, heartbeat_at DESC")
            .query { rs, _ ->
                StorageNodeRow(
                    nodeId = rs.getString(1),
                    capability = rs.getString(2),
                    heartbeatAt = rs.getObject(3, OffsetDateTime::class.java).toInstant(),
                    cleanShutdown = rs.getBoolean(4),
                )
            }.list()

    override fun applicationRole(): String =
        jdbc
            .sql("SELECT current_user")
            .query(String::class.java)
            .single()

    override fun now(): Instant =
        jdbc
            .sql("SELECT clock_timestamp()")
            .query(OffsetDateTime::class.java)
            .single()
            .toInstant()
}
