package email.testinbox.ingestion.guarded

import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.usecase.ChangeAccountState
import email.testinbox.application.usecase.RevokeAccountSessions
import email.testinbox.application.usecase.RevokeApiKey
import email.testinbox.domain.identity.AccountLifecycleAction
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.ingestion.config.IngestionProperties
import email.testinbox.ingestion.smtp.SmtpGateway
import email.testinbox.persistence.BundledMigrations
import email.testinbox.persistence.JdbcAccountLifecycle
import email.testinbox.persistence.JdbcApiKeyRepository
import email.testinbox.persistence.JdbcHumanSessions
import email.testinbox.persistence.JdbcSchemaHistory
import email.testinbox.persistence.JdbcWorkspaceQuotaState
import email.testinbox.persistence.SpringTransactionRunner
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.time.Clock
import java.util.UUID

/**
 * Account lifecycle through real SMTP, PostgreSQL and object storage
 * (ACCOUNT-LIFECYCLE.md "Ingestion and in-flight mail"). A suspended
 * participant's recipient, whether found at resolution or only at T1, is a
 * distinct discard: nothing is reserved, uploaded, refused or reported as a
 * duplicate, and the SMTP transcript is byte-identical to an unknown
 * recipient's. The prepare/T1 race is driven deterministically through the
 * guarded path's `afterSlot` seam, which runs after recipient resolution and
 * before T1: no sleeps.
 */
class SmtpAccountLifecycleTest {
    /** Runs one armed action at the after-slot seam (after resolution, before T1). */
    private class ArmedHook : IngestSyncHook {
        @Volatile var action: (() -> Unit)? = null

        @Volatile var fired = false

        override fun afterSlot() {
            action?.let {
                action = null
                it()
                fired = true
            }
        }
    }

    private class Scenario(
        val harness: GuardedIngestHarness,
        val gateway: SmtpGateway,
        val port: Int,
        val hook: ArmedHook,
    ) : AutoCloseable {
        private var places = 0

        val operator =
            SpringTransactionRunner(DataSourceTransactionManager(harness.dataSource)).let { tx ->
                ChangeAccountState(
                    JdbcAccountLifecycle(harness.jdbc),
                    JdbcWorkspaceQuotaState(harness.jdbc),
                    RevokeAccountSessions(JdbcHumanSessions(harness.jdbc), tx),
                    RevokeApiKey(JdbcApiKeyRepository(harness.jdbc), Clock.systemUTC()),
                    tx,
                )
            }

        /** An admitted, verified, ACTIVE participant with one inbox; returns its account and address. */
        fun participant(): Pair<HumanAccountId, String> {
            val workspace = harness.workspace()
            val (_, address) = harness.inbox(workspace)
            val account = UUID.randomUUID()
            val place = ++places
            harness.jdbc
                .sql("INSERT INTO human_account (id, contact_email, contact_verified_at, created_at) VALUES (?, ?, now(), now())")
                .params(account, "$account@example.invalid")
                .update()
            harness.jdbc
                .sql(
                    "INSERT INTO beta_admission (cohort_id, place, account_id, workspace_id, project_id, admitted_at) " +
                        "VALUES ('beta-30', ?, ?, ?, ?, now())",
                ).params(place, account, workspace.value, workspace.value)
                .update()
            harness.jdbc
                .sql("INSERT INTO participant_workspace (workspace_id, cohort_id, place, allocated_at) VALUES (?, 'beta-30', ?, now())")
                .params(workspace.value, place)
                .update()
            return HumanAccountId(account) to address
        }

        fun suspend(account: HumanAccountId) {
            operator.execute(account, AccountLifecycleAction.SUSPEND).shouldBeInstanceOf<ChangeAccountState.Result.Done>()
        }

        /** Messages, reservations, listed object bytes, refusals: everything a discard must leave untouched. */
        fun footprint(): List<Long> =
            listOf(
                harness.messageCount(),
                harness.count("SELECT count(*) FROM storage_reservation"),
                harness.listedBytes(),
                harness.refusalCount(),
            )

        override fun close() {
            gateway.stop()
            harness.close()
        }
    }

    private fun scenario(): Scenario {
        val hook = ArmedHook()
        val harness = GuardedIngestHarness(hook = hook)
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
        return Scenario(harness, gateway, port, hook)
    }

    private fun transcript(
        port: Int,
        recipients: List<String>,
    ): List<String> =
        Socket("127.0.0.1", port).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val out = socket.getOutputStream()
            val lines = mutableListOf<String>()

            fun reply() {
                while (true) {
                    val line = reader.readLine() ?: return
                    lines += line
                    if (line.length < 4 || line[3] != '-') return
                }
            }

            fun send(command: String) {
                out.write("$command\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                reply()
            }
            reply() // banner
            send("EHLO sender.example")
            send("MAIL FROM:<sender@example.com>")
            recipients.forEach { send("RCPT TO:<$it>") }
            send("DATA")
            out.write(GuardedIngestHarness.mime())
            send("\r\n.")
            send("QUIT")
            lines
        }

    private fun unknown() = "nobody-${UUID.randomUUID().toString().take(8)}@testinbox.local"

    @Test
    fun `an active participant receives mail and its transcript is the unknown recipient's`() {
        scenario().use { s ->
            val (_, address) = s.participant()
            val delivered = transcript(s.port, listOf(address))
            s.harness.messageCount() shouldBe 1
            delivered shouldBe scenario().use { u -> transcript(u.port, listOf(unknown())) }
        }
    }

    @Test
    fun `a participant suspended before the event is discarded at resolution with the uniform reply`() {
        val reference = scenario().use { u -> transcript(u.port, listOf(unknown())) }
        scenario().use { s ->
            val (account, address) = s.participant()
            s.suspend(account)
            val reply = transcript(s.port, listOf(address))
            reply shouldBe reference
            reply.any { it.startsWith("250") } shouldBe true
            // Never reached the storage path: the seam before T1 did not run.
            s.hook.fired shouldBe false
            s.footprint() shouldBe listOf(0L, 0L, 0L, 0L)
        }
    }

    @Test
    fun `a suspension between recipient preparation and T1 is a distinct discard with the uniform reply`() {
        val reference = scenario().use { u -> transcript(u.port, listOf(unknown())) }
        scenario().use { s ->
            val (account, address) = s.participant()
            // The recipient resolves while ACTIVE; the operator's transition commits
            // after preparation and before T1 takes the (35, 1) fence.
            s.hook.action = { s.suspend(account) }
            val reply = transcript(s.port, listOf(address))
            s.hook.fired shouldBe true
            reply shouldBe reference
            // No reservation, no upload, no storage-refusal attribution, no message, no duplicate.
            s.footprint() shouldBe listOf(0L, 0L, 0L, 0L)
            s.harness.metrics.events
                .count { it == "admission:INACTIVE_DISCARDED" } shouldBe 1
        }
    }

    @Test
    fun `in one event, the suspended copy is discarded and the active copy is stored, with the same transcript`() {
        val reference = scenario().use { u -> transcript(u.port, listOf(unknown(), unknown())) }
        scenario().use { s ->
            val (_, active) = s.participant()
            val (suspended, address) = s.participant()
            s.hook.action = { s.suspend(suspended) }
            val reply = transcript(s.port, listOf(active, address))
            s.hook.fired shouldBe true
            withClue("mixed transcript") { reply shouldBe reference }
            s.harness.messageCount() shouldBe 1
            s.harness.count("SELECT count(*) FROM storage_reservation") shouldBe 0
            s.harness.refusalCount() shouldBe 0
        }
    }
}
