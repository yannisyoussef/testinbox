package email.testinbox.persistence

import email.testinbox.application.port.FilesystemObservations
import email.testinbox.application.port.ObservedFilesystem
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration

/**
 * Reads the newest `storage_filesystem_observation` (V8). "Newest" is the
 * latest `started_at`, as the debt ledger reads it; its age is computed by the
 * database clock, the clock that wrote `observed_at`, never the JVM's.
 */
class JdbcFilesystemObservations(
    private val jdbc: JdbcClient,
) : FilesystemObservations {
    override fun newest(): ObservedFilesystem? =
        jdbc
            .sql(
                """
                SELECT avail_bytes, (extract(epoch FROM (now() - observed_at)) * 1000)::bigint AS age_ms
                  FROM storage_filesystem_observation
                 ORDER BY started_at DESC, id DESC
                 LIMIT 1
                """.trimIndent(),
            ).query { rs, _ -> ObservedFilesystem(Duration.ofMillis(rs.getLong("age_ms")), rs.getLong("avail_bytes")) }
            .optional()
            .orElse(null)
}
