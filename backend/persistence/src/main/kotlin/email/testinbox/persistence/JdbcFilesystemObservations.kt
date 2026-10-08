package email.testinbox.persistence

import email.testinbox.application.port.FilesystemObservations
import email.testinbox.application.port.FilesystemSnapshot
import email.testinbox.application.port.ObservedFilesystem
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration

/**
 * Reads the database clock and the newest `storage_filesystem_observation`
 * (V8) in one statement. "Newest" is the latest `started_at`, as the debt
 * ledger reads it. Its age is measured from `started_at` by `clock_timestamp()`
 * (not `now()`, which a caller's long transaction would freeze), the clock
 * that wrote it, never the JVM's.
 */
class JdbcFilesystemObservations(
    private val jdbc: JdbcClient,
) : FilesystemObservations {
    override fun snapshot(): FilesystemSnapshot =
        jdbc
            .sql(
                """
                SELECT n.now, o.started_at, o.avail_bytes, o.inodes_free,
                       (extract(epoch FROM (n.now - o.started_at)) * 1000)::bigint AS age_ms
                  FROM (SELECT clock_timestamp() AS now) n
                  LEFT JOIN LATERAL (
                      SELECT started_at, avail_bytes, inodes_total - inodes_used AS inodes_free
                        FROM storage_filesystem_observation
                       ORDER BY started_at DESC, id DESC
                       LIMIT 1
                  ) o ON true
                """.trimIndent(),
            ).query { rs, _ ->
                val startedAt = Timestamps.fromDb(rs, "started_at")
                FilesystemSnapshot(
                    databaseNow = checkNotNull(Timestamps.fromDb(rs, "now")),
                    newest =
                        startedAt?.let {
                            ObservedFilesystem(
                                startedAt = it,
                                age = Duration.ofMillis(rs.getLong("age_ms")),
                                availBytes = rs.getLong("avail_bytes"),
                                inodesFree = rs.getLong("inodes_free"),
                            )
                        },
                )
            }.single()
}
