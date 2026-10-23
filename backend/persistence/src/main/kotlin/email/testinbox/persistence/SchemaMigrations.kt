package email.testinbox.persistence

import email.testinbox.application.deployment.AppliedSchema
import email.testinbox.application.deployment.SchemaHistory
import email.testinbox.application.deployment.SchemaVersion
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * The migration versions packaged in *this* artifact (ADR-029 §4).
 *
 * `classpath*:` so it resolves identically from a build directory during tests
 * and from inside a Spring Boot jar at run time — the deployed case is the one
 * that matters, and a scanner that only works in tests would report "no
 * migrations bundled", which the compatibility policy reads as "nothing to
 * check".
 */
object BundledMigrations {
    private val VERSION = Regex("""^V(\d+(?:[._]\d+)*)__.*\.sql$""")

    fun versions(classLoader: ClassLoader = BundledMigrations::class.java.classLoader): List<SchemaVersion> =
        PathMatchingResourcePatternResolver(classLoader)
            .getResources("classpath*:db/migration/V*__*.sql")
            .mapNotNull { resource ->
                resource.filename
                    ?.let(VERSION::find)
                    ?.groupValues
                    ?.get(1)
            }.map(::SchemaVersion)
            .sorted()

    fun highest(classLoader: ClassLoader = BundledMigrations::class.java.classLoader): SchemaVersion? = versions(classLoader).maxOrNull()
}

/** Reads `flyway_schema_history` without assuming it exists. */
class JdbcSchemaHistory(
    private val jdbc: JdbcClient,
) : SchemaHistory {
    override fun read(): AppliedSchema {
        val present =
            jdbc
                .sql("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")
                .query(Boolean::class.java)
                .single()
        if (!present) return AppliedSchema.ABSENT

        val rows =
            jdbc
                .sql("SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL")
                .query { rs, _ -> rs.getString("version") to rs.getBoolean("success") }
                .list()
        return AppliedSchema(
            successfulVersions = rows.filter { it.second }.map { it.first },
            failedVersions = rows.filterNot { it.second }.map { it.first },
            historyPresent = true,
        )
    }
}
