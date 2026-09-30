package email.testinbox.ingestion.smtp

import email.testinbox.ingestion.guarded.GuardedIngestHarness
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID

/**
 * TI-STORAGE-003 §35 and §61: the live deployable runs the whole ADR-035
 * protocol, and NO configuration can make it refuse.
 *
 * The hostile setup:
 * - every plausible enforcement key is set to `ALL` (as a property, and in
 *   the relaxed environment spelling);
 * - the workspace storage limit is 100 bytes, so every copy exceeds the
 *   inbox, workspace and global ceilings it is observed against.
 *
 * Real SMTP mail is still stored. The ceilings are observed (metered as
 * unenforced), and nothing is refused or recorded as a refusal.
 */
@SpringBootTest(
    properties = [
        "testinbox.storage.enforcement=ALL",
        "testinbox.storage.enforcement-mode=ALL",
        "testinbox.storage.admission.enforcement=ALL",
        "TESTINBOX_STORAGE_ENFORCEMENT=ALL",
        "testinbox.limits.max-stored-bytes=100",
    ],
)
class EnforcementOffLiveTest {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var registry: MeterRegistry

    private fun provision(): String {
        val ws = UUID.randomUUID()
        val inbox = UUID.randomUUID()
        val address = "live-${inbox.toString().take(8)}@testinbox.local"
        jdbc.sql("INSERT INTO workspace (id, name, created_at) VALUES (?, 'w', now())").param(ws).update()
        jdbc.sql("INSERT INTO project (id, workspace_id, name, created_at) VALUES (?, ?, 'p', now())").params(ws, ws).update()
        jdbc
            .sql(
                "INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at) " +
                    "VALUES (?, ?, ?, ?, 'GENERATED', 'ACTIVE', now(), now() + interval '1 hour')",
            ).params(inbox, ws, ws, address)
            .update()
        return address
    }

    private fun counter(
        name: String,
        tag: String,
        value: String,
    ): Double =
        registry
            .find(name)
            .tag(tag, value)
            .counter()
            ?.count() ?: 0.0

    @Test
    fun `mail above every ceiling is stored, with the ceilings observed and never enforced`() {
        val addresses = List(3) { provision() }

        RawSmtpClient("localhost", smtpPort).use { smtp ->
            repeat(2) {
                smtp.send("sender@example.com", addresses, GuardedIngestHarness.mime()).code shouldBe 250
            }
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            jdbc.sql("SELECT count(*) FROM message").query(Long::class.java).single() shouldBe 6
        }
        jdbc.sql("SELECT coalesce(sum(refusal_count), 0) FROM inbox_storage").query(Long::class.java).single() shouldBe 0
        jdbc.sql("SELECT count(*) FROM storage_reservation").query(Long::class.java).single() shouldBe 0
        for (outcome in listOf("refused_inbox", "refused_workspace", "refused_global")) {
            counter("testinbox_storage_admission_total", "outcome", outcome) shouldBe 0.0
        }
        counter("testinbox_storage_admission_total", "outcome", "admitted") shouldBe 6.0
        (counter("testinbox_storage_admission_unenforced_total", "ceiling", "inbox") > 0) shouldBe true
    }

    @Test
    fun `the deployable's database sessions carry the storage-v1 capability name`() {
        // ADR-035 §14 (a): the activation barrier allowlists testinbox-%:%:storage-v1,
        // which an old binary (pgJDBC's default name) cannot match.
        jdbc
            .sql("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'testinbox-ingestion:testinbox-ingestion:storage-v1'")
            .query(Long::class.java)
            .single()
            .let { (it > 0) shouldBe true }
        jdbc
            .sql(
                "SELECT count(*) FROM storage_node WHERE capability = 'storage-v1' AND NOT clean_shutdown",
            ).query(Long::class.java)
            .single() shouldBe
            1
    }

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> = GuardedIngestHarness.postgres

        @JvmStatic
        val smtpPort: Int = ServerSocket(0).use { it.localPort }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("testinbox.smtp.port") { smtpPort }
            registry.add("testinbox.storage.endpoint") { GuardedIngestHarness.minio.s3URL }
            registry.add("testinbox.storage.access-key") { GuardedIngestHarness.ACCESS }
            registry.add("testinbox.storage.secret-key") { GuardedIngestHarness.SECRET }
            registry.add("testinbox.storage.bucket") { "live-${UUID.randomUUID().toString().take(8)}" }
            registry.add("testinbox.mail-domain") { "testinbox.local" }
        }
    }
}
