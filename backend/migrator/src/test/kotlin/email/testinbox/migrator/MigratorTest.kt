package email.testinbox.migrator

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID

/**
 * The migration executor is the one deployment step that cannot be rolled back
 * by redeploying a previous digest (ADR-028/029), so its contract is tested
 * directly: it applies exactly the migrations bundled in this artifact, it
 * upgrades a populated previous schema without losing rows, it reports a
 * non-zero code when the schema is not where it claimed to leave it, and it
 * fails loudly — never silently — when it cannot reach the database.
 */
class MigratorTest {
    private companion object {
        /**
         * An explicit, distinctive password. Testcontainers' default is the
         * string `test`, which would make the credential-leak assertion below
         * simultaneously meaningless (every message "contains" no `test`) and
         * brittle (any `latest` or `testinbox` substring would fail it).
         */
        const val DB_PASSWORD = "fixture-not-a-real-db-password--2"

        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").withPassword(DB_PASSWORD).also { it.start() }
    }

    private fun freshDatabaseUrl(): String {
        val name = "mig_${UUID.randomUUID().toString().replace("-", "")}"
        val admin =
            JdbcClient.create(
                SimpleDriverDataSource(org.postgresql.Driver(), postgres.jdbcUrl, postgres.username, postgres.password),
            )
        admin.sql("CREATE DATABASE $name").update()
        return postgres.jdbcUrl.substringBeforeLast('/') + "/" + name
    }

    private fun jdbcFor(url: String) =
        JdbcClient.create(SimpleDriverDataSource(org.postgresql.Driver(), url, postgres.username, postgres.password))

    private fun runMigrator(
        url: String,
        vararg extra: String,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(MigratorApplication::class.java).run(
            "--spring.datasource.url=$url",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            *extra,
        )

    @Test
    fun `an empty database is migrated to the version this artifact bundles, and reports success`() {
        val url = freshDatabaseUrl()
        runMigrator(url).use { context ->
            reportOutcome(context.getBean(Flyway::class.java)) shouldBe 0
        }
        val jdbc = jdbcFor(url)
        jdbc
            .sql("SELECT max(version::int) FROM flyway_schema_history WHERE success AND version IS NOT NULL")
            .query(Int::class.java)
            .single() shouldBe 3
        // The migrator is the sole executor (ADR-029 §1): the tables the
        // application needs exist afterwards, without any application running.
        jdbc.sql("SELECT to_regclass('public.message') IS NOT NULL").query(Boolean::class.java).single() shouldBe true
    }

    @Test
    fun `a populated previous schema is upgraded in place`() {
        val url = freshDatabaseUrl()
        val dataSource = SimpleDriverDataSource(org.postgresql.Driver(), url, postgres.username, postgres.password)
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("1")
            .load()
            .migrate()

        val jdbc = jdbcFor(url)
        val workspace = UUID.randomUUID()
        jdbc.sql("INSERT INTO workspace (id, name, created_at) VALUES (?, 'legacy', now())").param(workspace).update()

        runMigrator(url).use { context ->
            reportOutcome(context.getBean(Flyway::class.java)) shouldBe 0
        }
        jdbc
            .sql("SELECT count(*) FROM workspace WHERE id = ?")
            .param(workspace)
            .query(Int::class.java)
            .single() shouldBe 1
    }

    @Test
    fun `a run that leaves migrations pending exits non-zero`() {
        // The deployment gate must not pass on a half-applied schema, whatever
        // makes it half-applied — here, a target that stops short.
        val url = freshDatabaseUrl()
        runMigrator(url, "--spring.flyway.target=1").use { context ->
            reportOutcome(context.getBean(Flyway::class.java)) shouldBe EXIT_STILL_PENDING
        }
    }

    @Test
    fun `a failed migration recorded in history exits non-zero`() {
        val url = freshDatabaseUrl()
        runMigrator(url).use { reportOutcome(it.getBean(Flyway::class.java)) shouldBe 0 }
        jdbcFor(url)
            .sql(
                """
                INSERT INTO flyway_schema_history
                    (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                VALUES (99, '4', 'broken', 'SQL', 'V4__broken.sql', 0, 'test', 1, false)
                """.trimIndent(),
            ).update()
        runMigrator(url, "--spring.flyway.validate-on-migrate=false", "--spring.flyway.ignore-migration-patterns=*:*").use {
            reportOutcome(it.getBean(Flyway::class.java)) shouldBe EXIT_FAILED_HISTORY
        }
    }

    @Test
    fun `an unreachable database aborts startup rather than exiting successfully`() {
        shouldThrowAny { runMigrator("jdbc:postgresql://127.0.0.1:1/nonexistent").close() }
    }

    @Test
    fun `a rejected credential aborts startup without putting the credential in the failure`() {
        // ADR-029 §7. This is the case where a credential is actually in play:
        // the driver has one, the server refuses it, and the resulting chain of
        // messages is what lands in a deployment log far more widely readable
        // than the secret store.
        val failure =
            shouldThrowAny {
                SpringApplicationBuilder(MigratorApplication::class.java)
                    .run(
                        "--spring.datasource.url=${postgres.jdbcUrl}",
                        "--spring.datasource.username=${postgres.username}",
                        "--spring.datasource.password=$DB_PASSWORD-wrong",
                    ).close()
            }
        val rendered =
            generateSequence<Throwable>(failure) { it.cause }
                .joinToString("\n") { "${it.javaClass.name}: ${it.message.orEmpty()}" }
        rendered shouldNotContain DB_PASSWORD
    }
}
