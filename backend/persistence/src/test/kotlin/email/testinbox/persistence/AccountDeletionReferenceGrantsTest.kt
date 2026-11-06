package email.testinbox.persistence

import email.testinbox.domain.identity.AccountDeletionStage
import email.testinbox.domain.identity.HumanAccountId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.testcontainers.utility.MountableFile
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * The V23 deletion workflow record under the shipped reference grants
 * (`deploy/database/roles.reference.sql`, applied with psql exactly as Ops
 * does): the API role holds nothing on `account_deletion`, so it can neither
 * read nor write it, while the operator path (`JdbcAccountLifecycle` as the
 * schema owner) still opens and advances a record. The rest of the role model
 * is [StorageRoleModelTest]'s.
 */
class AccountDeletionReferenceGrantsTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private val suffix = UUID.randomUUID().toString().take(8)
    private val api = "ti_api_$suffix"
    private val ingestion = "ti_ingest_$suffix"
    private val monitor = "ti_monitor_$suffix"

    private fun LedgerTestDatabase.roleSource(role: String) =
        SimpleDriverDataSource(org.postgresql.Driver(), postgres.jdbcUrl.substringBeforeLast('/') + "/" + name, role, "pw-$role")

    private fun LedgerTestDatabase.login(role: String) {
        jdbc.sql("CREATE ROLE $role LOGIN PASSWORD 'pw-$role'").update()
        jdbc.sql("GRANT CONNECT ON DATABASE $name TO $role").update()
    }

    private fun LedgerTestDatabase.applyReferenceWithPsql() {
        val reference =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "deploy/database/roles.reference.sql") }
                .first { it.isFile }
        val inContainer = "/tmp/roles.reference.$suffix.sql"
        postgres.copyFileToContainer(MountableFile.forHostPath(reference.path), inContainer)
        val result =
            postgres.execInContainer(
                "psql",
                "-X",
                "-q",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                postgres.username,
                "-d",
                name,
                "-v",
                "api_role=$api",
                "-v",
                "ingestion_role=$ingestion",
                "-v",
                "monitor_role=$monitor",
                "-f",
                inContainer,
            )
        withClue(result.stderr) { result.exitCode shouldBe 0 }
    }

    private fun LedgerTestDatabase.holdsAnything(role: String): Boolean =
        jdbc
            .sql(
                """
                SELECT bool_or(has_table_privilege(:role, 'public.account_deletion', p))
                       OR has_any_column_privilege(:role, 'public.account_deletion', 'SELECT,INSERT,UPDATE,REFERENCES')
                  FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER']) p
                """.trimIndent(),
            ).param("role", role)
            .query(Boolean::class.javaObjectType)
            .single()

    private fun failure(block: () -> Unit): String {
        val e = runCatching(block).exceptionOrNull() ?: error("expected a refusal")
        return generateSequence(e) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }

    @Test
    fun `under psql the API can neither read nor write the deletion record, while the operator path still runs`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        listOf(api, ingestion, monitor).forEach { db.login(it) }
        db.applyReferenceWithPsql()
        // No privilege at all, table or column, for either deployable.
        db.holdsAnything(api) shouldBe false
        db.holdsAnything(ingestion) shouldBe false

        // The operator path (JdbcAccountLifecycle, as the schema owner) opens and advances a record.
        val account = HumanAccountId(UUID.randomUUID())
        val at = Instant.parse("2026-11-04T10:00:00Z")
        val owner = JdbcAccountLifecycle(JdbcClient.create(db.dataSource))
        SpringTransactionRunner(DataSourceTransactionManager(db.dataSource)).required {
            owner.openDeletion(account, at)
            owner.advanceDeletion(account, AccountDeletionStage.CONTENT_DELETING, at)
        }
        checkNotNull(owner.deletion(account)).stage shouldBe AccountDeletionStage.CONTENT_DELETING
        owner.pendingDeletions().map { it.accountId } shouldBe listOf(account)

        // The API role is refused by the grant itself, for reads as well as writes, and through the adapter.
        val source = db.roleSource(api)
        val asApi = JdbcClient.create(source)
        val refused =
            listOf(
                "SELECT account_id, requested_at FROM account_deletion",
                "SELECT count(*) FROM account_deletion",
                "INSERT INTO account_deletion (account_id, stage, requested_at, stage_changed_at) " +
                    "VALUES ('${UUID.randomUUID()}', 'DRAINING', now(), now())",
                "UPDATE account_deletion SET stage = 'ERASED', erased_at = now() WHERE account_id = '${account.value}'",
                "DELETE FROM account_deletion",
                "TRUNCATE account_deletion",
            )
        for (statement in refused) {
            withClue(statement) { failure { asApi.sql(statement).query().listOfRows() } shouldContain "permission denied" }
        }
        failure { JdbcAccountLifecycle(asApi).deletion(account) } shouldContain "permission denied"
        failure { JdbcAccountLifecycle(asApi).pendingDeletions() } shouldContain "permission denied"
        failure {
            SpringTransactionRunner(DataSourceTransactionManager(source)).required {
                JdbcAccountLifecycle(asApi).openDeletion(account, at)
            }
        } shouldContain "permission denied"
        // ...while the API's own lifecycle readiness reads are unaffected.
        JdbcLifecyclePrivilegeProbe(asApi, "api").api() shouldBe null

        // Nothing the API attempted changed the operator's record.
        checkNotNull(owner.deletion(account)).stage shouldBe AccountDeletionStage.CONTENT_DELETING
        db.jdbc
            .sql("SELECT count(*) FROM account_deletion")
            .query(Long::class.javaObjectType)
            .single() shouldBe 1
    }
}
