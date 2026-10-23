package email.testinbox.benchmark

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/**
 * The database the benchmark runs against: a throwaway local container, or a
 * remote database the operator named, optionally created here through the
 * maintenance connection. Whether THIS run created it is part of the safety
 * decision, so it is established here and nowhere else.
 */
class BenchmarkTarget private constructor(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val databaseName: String,
    val createdByHarness: Boolean,
    private val container: PostgreSQLContainer<*>?,
) : AutoCloseable {
    fun pool(maxSize: Int): HikariDataSource =
        HikariDataSource().apply {
            jdbcUrl = this@BenchmarkTarget.jdbcUrl
            username = this@BenchmarkTarget.username
            password = this@BenchmarkTarget.password
            maximumPoolSize = maxSize
            poolName = "storage-benchmark"
        }

    /** Tenant rows per table, 0 for a table that does not exist yet (a database created but not migrated). */
    fun tenantRowCounts(jdbc: JdbcClient): Map<String, Long> =
        SafetyPreflight.TENANT_TABLES.associateWith { table ->
            val exists =
                jdbc
                    .sql("SELECT to_regclass(:name) IS NOT NULL")
                    .param("name", "public.$table")
                    .query(Boolean::class.java)
                    .single()
            // Table names come from the fixed list above, never from input.
            if (exists) jdbc.sql("SELECT count(*) FROM $table").query(Long::class.java).single() else 0L
        }

    fun migrate(dataSource: javax.sql.DataSource) {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun postgresVersion(jdbc: JdbcClient): String = jdbc.sql("SELECT version()").query(String::class.java).single()

    fun maxConnections(jdbc: JdbcClient): Int =
        jdbc
            .sql("SHOW max_connections")
            .query(String::class.java)
            .single()
            .toInt()

    override fun close() {
        container?.stop()
    }

    companion object {
        private const val LOCAL_DATABASE = "testinbox_bench_local"
        private const val LOCAL_MAX_CONNECTIONS = 300
        private val IDENTIFIER = Regex("^[a-z_][a-z0-9_]{0,62}$")

        /** A throwaway `postgres:16-alpine`, sized so a 100-worker scenario fits in `max_connections`. */
        fun local(): BenchmarkTarget {
            val container =
                PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName(LOCAL_DATABASE)
                    .withCommand("postgres", "-c", "max_connections=$LOCAL_MAX_CONNECTIONS")
            container.start()
            return BenchmarkTarget(
                container.jdbcUrl,
                container.username,
                container.password,
                LOCAL_DATABASE,
                createdByHarness = true,
                container,
            )
        }

        /**
         * The database [jdbcUrl] names. With [create], it is created through
         * the server's `postgres` maintenance database first, and must not
         * exist yet: an existing database is never adopted as "created here".
         */
        fun remote(
            jdbcUrl: String,
            username: String,
            password: String,
            create: Boolean,
        ): BenchmarkTarget {
            val name = databaseNameOf(jdbcUrl)
            if (create) createDatabase(jdbcUrl.substringBeforeLast('/') + "/postgres", username, password, name)
            return BenchmarkTarget(jdbcUrl, username, password, name, createdByHarness = create, container = null)
        }

        /** `CREATE DATABASE` through the maintenance database. The name must not exist yet and must be a plain identifier. */
        private fun createDatabase(
            maintenanceUrl: String,
            username: String,
            password: String,
            name: String,
        ) {
            if (!IDENTIFIER.matches(
                    name,
                )
            ) {
                throw UsageException("'$name' is not a plain lowercase identifier; refusing to CREATE DATABASE it")
            }
            DriverManager.getConnection(maintenanceUrl, username, password).use { c ->
                if (databaseExists(
                        c,
                        name,
                    )
                ) {
                    throw UsageException("database '$name' already exists; --create-database never adopts an existing one")
                }
                // Validated against IDENTIFIER above; CREATE DATABASE takes no bind parameter.
                c.createStatement().use { it.execute("CREATE DATABASE \"$name\"") }
            }
        }

        private fun databaseExists(
            connection: java.sql.Connection,
            name: String,
        ): Boolean =
            connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use { st ->
                st.setString(1, name)
                st.executeQuery().use { it.next() }
            }

        fun databaseNameOf(jdbcUrl: String): String {
            val path = jdbcUrl.substringAfter("://", "").substringAfter('/', "").substringBefore('?')
            if (path.isBlank()) throw UsageException("the JDBC URL names no database: $jdbcUrl")
            return path
        }
    }
}
