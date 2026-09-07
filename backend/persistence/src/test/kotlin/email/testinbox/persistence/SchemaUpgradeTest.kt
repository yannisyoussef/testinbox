package email.testinbox.persistence

import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.deployment.SchemaVersion
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-029 §6 / TI-DEPLOY-001 §24: a migration must be proven against the
 * *previous* schema carrying data, not only against an empty database.
 *
 * Testing only the empty case would pass for a migration that silently drops
 * every existing row, or that adds a NOT NULL column with no default — the two
 * failure modes that actually break a deployment.
 *
 * Each case runs on its own freshly created database on the shared container,
 * so the upgrade starts from a genuinely empty catalogue rather than from
 * whatever the Spring context already migrated.
 */
class SchemaUpgradeTest : PersistenceIntegrationTest() {
    @Autowired lateinit var shared: JdbcClient

    private fun freshDatabase(): DataSource {
        val name = "upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        shared.sql("CREATE DATABASE $name").update()
        return SimpleDriverDataSource(
            org.postgresql.Driver(),
            postgres.jdbcUrl.substringBeforeLast('/') + "/" + name,
            postgres.username,
            postgres.password,
        )
    }

    private fun flyway(
        dataSource: DataSource,
        target: String? = null,
    ): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .let { if (target != null) it.target(target) else it }
            .load()

    /** Rows a V1 deployment would already be holding when the upgrade lands. */
    private fun seedV1Data(jdbc: JdbcClient): Triple<UUID, UUID, UUID> {
        val workspace = UUID.randomUUID()
        val project = UUID.randomUUID()
        val inbox = UUID.randomUUID()
        val message = UUID.randomUUID()
        jdbc.sql("INSERT INTO workspace (id, name, created_at) VALUES (?, 'legacy', now())").param(workspace).update()
        jdbc
            .sql("INSERT INTO project (id, workspace_id, name, created_at) VALUES (?, ?, 'legacy', now())")
            .params(project, workspace)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at)
                VALUES (?, ?, ?, 'legacy@testinbox.local', 'GENERATED', 'ACTIVE', now(), now() + interval '1 hour')
                """.trimIndent(),
            ).params(inbox, workspace, project)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO message (
                    id, workspace_id, inbox_id, received_at, provider, provider_message_id,
                    envelope_from, envelope_to, raw_object_key, raw_size_bytes,
                    content_fingerprint, parse_status
                ) VALUES (?, ?, ?, now(), 'ses', 'evt-1', 'sut@example.com',
                          'legacy@testinbox.local', 'k/raw.eml', 10, 'fp', 'OK')
                """.trimIndent(),
            ).params(message, workspace, inbox)
            .update()
        return Triple(workspace, inbox, message)
    }

    @Test
    fun `data written under V1 survives the upgrade to the current schema`() {
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)

        flyway(dataSource, target = "1").migrate()
        val (workspace, inbox, message) = seedV1Data(jdbc)

        val result = flyway(dataSource).migrate()
        result.success shouldBe true
        // V1 was already applied; the upgrade applies only what came after it.
        result.migrationsExecuted shouldBe (BundledMigrations.versions().size - 1)

        jdbc
            .sql("SELECT count(*) FROM workspace WHERE id = ?")
            .param(workspace)
            .query(Int::class.java)
            .single() shouldBe 1
        jdbc
            .sql("SELECT count(*) FROM inbox WHERE id = ?")
            .param(inbox)
            .query(Int::class.java)
            .single() shouldBe 1
        jdbc
            .sql("SELECT provider_message_id FROM message WHERE id = ?")
            .param(message)
            .query(String::class.java)
            .single() shouldBe "evt-1"
    }

    @Test
    fun `the upgraded schema carries the ADR-026 recipient-scoped delivery index, not the V1 one`() {
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)
        flyway(dataSource, target = "1").migrate()
        seedV1Data(jdbc)
        flyway(dataSource).migrate()

        val indexes =
            jdbc
                .sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
                .query(String::class.java)
                .list()
        indexes shouldContainAll listOf("ux_message_provider_delivery")
        indexes.contains("ux_message_provider_event") shouldBe false
    }

    @Test
    fun `a node bundling the current migrations reports ready only once the upgrade has run`() {
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)
        val bundled = BundledMigrations.highest()!!
        val compatibility = SchemaCompatibility(JdbcSchemaHistory(jdbc), bundled)

        // ADR-029 §4: nothing migrated yet — the history table does not exist.
        compatibility.status().compatible shouldBe false
        compatibility.status().detail shouldContain "have not run"

        flyway(dataSource, target = "1").migrate()
        val atV1 = compatibility.status()
        atV1.compatible shouldBe false
        atV1.applied shouldBe SchemaVersion("1")

        flyway(dataSource).migrate()
        val upgraded = compatibility.status()
        upgraded.compatible shouldBe true
        upgraded.applied shouldBe bundled
    }

    @Test
    fun `every bundled migration is discoverable from the artifact`() {
        // If this scan silently found nothing, the compatibility policy would
        // read "no migrations bundled" and wave every schema through.
        BundledMigrations.versions().map { it.raw } shouldBe listOf("1", "2", "3")
    }
}
