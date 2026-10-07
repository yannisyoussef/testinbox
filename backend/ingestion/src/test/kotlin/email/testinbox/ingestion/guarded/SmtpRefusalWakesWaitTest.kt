package email.testinbox.ingestion.guarded

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.usecase.WaitForMessage
import email.testinbox.domain.message.MessageMatcher
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.ingestion.config.IngestionProperties
import email.testinbox.ingestion.smtp.RawSmtpClient
import email.testinbox.ingestion.smtp.SmtpGateway
import email.testinbox.notification.PgListenNotifier
import email.testinbox.notification.PgListenNotifierConfig
import email.testinbox.persistence.BundledMigrations
import email.testinbox.persistence.JdbcInboxRepository
import email.testinbox.persistence.JdbcMessageRepository
import email.testinbox.persistence.JdbcSchemaHistory
import email.testinbox.persistence.JdbcStorageVisibility
import email.testinbox.persistence.JdbcWaitObservations
import email.testinbox.persistence.JdbcWaitSlots
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors

/**
 * ADR-035 §17 test 38, the SMTP half, in one process: a real `DATA` to a full
 * inbox is refused by the guarded protocol with enforcement switched on
 * INTERNALLY (never in a deployable), the §6a upsert's `pg_notify` reaches a
 * real LISTEN connection, and a parked `WaitForMessage` evaluating against the
 * same database answers STORAGE_LIMIT_EXCEEDED instead of TIMEOUT. The API
 * suite proves the HTTP rendering; this proves the path from the socket.
 */
class SmtpRefusalWakesWaitTest {
    @Test
    fun `a DATA refused by INBOX_LIMIT wakes a parked waiter through LISTEN and ends it with the refusal`() {
        // Workspace 1 MiB, share 0.5: inbox limit 512 KiB. The inbox is seeded full, so any copy is refused.
        val policy = StorageCapacityPolicy(1L shl 20, InboxShare.of("0.5"), 1L shl 40, 0)
        GuardedIngestHarness(enforcement = StorageEnforcement.TENANT_LIMITS, policy = policy).use { harness ->
            val port = ServerSocket(0).use { it.localPort }
            val properties = IngestionProperties(mailDomain = "testinbox.local", smtp = IngestionProperties.Smtp(port))
            val gateway =
                SmtpGateway(
                    harness.receive,
                    properties,
                    properties.toConfig(),
                    SchemaCompatibility(JdbcSchemaHistory(harness.jdbc), BundledMigrations.highest()),
                )
            gateway.start()
            val notifier =
                PgListenNotifier(
                    PgListenNotifierConfig(
                        jdbcUrl = harness.dataSource.jdbcUrl,
                        username = harness.dataSource.username,
                        password = harness.dataSource.password,
                        applicationName = "testinbox-listen:smtp-refusal-test:storage-v1",
                    ),
                )
            notifier.start()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val workspace = harness.workspace()
                val (inboxId, address) = harness.inbox(workspace)
                harness.jdbc
                    .sql("INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes) VALUES (?, ?, ?)")
                    .params(inboxId.value, workspace.value, policy.inboxLimitBytes)
                    .update()
                await().atMost(Duration.ofSeconds(10)).until { notifier.health().listening }
                val epochBefore = notifier.health().epoch

                val transactionManager = DataSourceTransactionManager(harness.dataSource)
                val wait =
                    WaitForMessage(
                        inboxes = JdbcInboxRepository(harness.jdbc),
                        observations =
                            JdbcWaitObservations(
                                harness.jdbc,
                                transactionManager,
                                JdbcMessageRepository(harness.jdbc),
                                JdbcStorageVisibility(harness.jdbc),
                            ),
                        notifier = notifier,
                        waitSlots = JdbcWaitSlots(harness.jdbc),
                        maxConcurrentWaits = 10,
                        clock = Clock.systemUTC(),
                        config = TestInboxConfig(mailDomain = "testinbox.local", waitWindowCap = Duration.ofSeconds(20)),
                        policy = policy,
                    )
                val result =
                    executor.submit<WaitForMessage.Result> {
                        wait.execute(
                            WaitForMessage.Command(workspace, inboxId, MessageMatcher(), timeoutSeconds = 20, afterStorageRefusalCount = 0),
                        )
                    }
                // Parked: its concurrent-wait slot is held.
                await().atMost(Duration.ofSeconds(10)).until {
                    harness.jdbc
                        .sql("SELECT count(*) FROM wait_lease")
                        .query(Long::class.java)
                        .single() == 1L
                }

                // A real SMTP DATA, refused by the guarded protocol: uniform 250, nothing stored, the refusal recorded.
                RawSmtpClient("localhost", port).use { smtp ->
                    smtp.send("sender@example.com", listOf(address), GuardedIngestHarness.mime()).code shouldBe 250
                }

                val outcome = result.get().shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()
                outcome.refusalReason shouldBe StorageRefusalReason.INBOX_LIMIT
                outcome.refusals.count shouldBe 1
                outcome.afterStorageRefusalCount shouldBe 0
                outcome.tenantScope!!.limitBytes shouldBe policy.inboxLimitBytes
                harness.messageCount() shouldBe 0
                // Woken through the live LISTEN connection, not by a reconnect or the degraded tick.
                notifier.health().listening shouldBe true
                notifier.health().epoch shouldBe epochBefore
                harness.jdbc
                    .sql("SELECT count(*) FROM wait_lease")
                    .query(Long::class.java)
                    .single() shouldBe 0
            } finally {
                executor.shutdownNow()
                notifier.close()
                gateway.stop()
            }
        }
    }
}
