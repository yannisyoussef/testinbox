package email.testinbox.ingestion.guarded

import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.ingestion.config.IngestionProperties
import email.testinbox.ingestion.smtp.SmtpGateway
import email.testinbox.persistence.BundledMigrations
import email.testinbox.persistence.JdbcSchemaHistory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * ADR-035 §17 test 33, the owner anti-oracle test. The SMTP transcript is
 * byte-identical for an admitted recipient, an unknown one, and one refused by
 * `INBOX_LIMIT`, `WORKSPACE_LIMIT` or `SERVICE_CAPACITY`, each alone and all
 * mixed in one `DATA`. Enforcement is switched on INTERNALLY here, never in a
 * deployable. The test also asserts that each refusal really happened, so it
 * cannot pass because no quota path ran.
 */
class SmtpAntiOracleTest {
    /** Exactly one copy's footprint, measured through SMTP itself (the gateway adds transport headers). */
    private val f: Long by lazy {
        scenario(GuardedIngestHarness.GENEROUS).use { calibration ->
            val (_, address) = calibration.harness.inbox(calibration.harness.workspace())
            transcript(calibration.port, listOf(address))
            calibration.harness.messageCount() shouldBe 1
            calibration.harness.committedBytes()
        }
    }

    private class Scenario(
        val harness: GuardedIngestHarness,
        val gateway: SmtpGateway,
        val port: Int,
    ) : AutoCloseable {
        override fun close() {
            gateway.stop()
            harness.close()
        }
    }

    /** Workspace limit 10f; inbox 5f; admission cap = G − H = 20f. */
    private fun policy() = StorageCapacityPolicy(10 * f, InboxShare.of("0.5"), 20 * f + 1, 1)

    private fun scenario(policy: StorageCapacityPolicy = policy()): Scenario {
        val harness = GuardedIngestHarness(enforcement = StorageEnforcement.ALL, policy = policy)
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
        return Scenario(harness, gateway, port)
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

    private data class Recipients(
        val admitted: String,
        val unknown: String,
        val inboxFull: String,
        val workspaceFull: String,
        val serviceFull: String,
    )

    /** One recipient per outcome. The global cap leaves room for exactly one copy. */
    private fun recipients(h: GuardedIngestHarness): Recipients {
        val (_, admitted) = h.inbox(h.workspace())
        val inboxWs = h.workspace()
        val (inboxFullId, inboxFull) = h.inbox(inboxWs)
        h.jdbc
            .sql("INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes) VALUES (?, ?, ?)")
            .params(inboxFullId.value, inboxWs.value, 5 * f)
            .update()
        val fullWs = h.workspace()
        val (_, workspaceFull) = h.inbox(fullWs)
        h.jdbc
            .sql("INSERT INTO workspace_storage_account (workspace_id, base_bytes) VALUES (?, ?)")
            .params(fullWs.value, 10 * f)
            .update()
        val (_, serviceFull) = h.inbox(h.workspace())
        // Global: 10f (the full workspace) + 9f of filler = 19f, so one more copy fits the 20f cap.
        val filler = h.workspace()
        h.jdbc
            .sql("INSERT INTO workspace_storage_account (workspace_id, base_bytes) VALUES (?, ?)")
            .params(filler.value, 9 * f)
            .update()
        return Recipients(admitted, "nobody-${System.nanoTime()}@testinbox.local", inboxFull, workspaceFull, serviceFull)
    }

    @Test
    fun `the SMTP transcript is byte-identical whatever the storage outcome, alone or mixed`() {
        val transcripts = mutableMapOf<String, List<String>>()
        val outcomes = mutableMapOf<String, Triple<Long, Long, Map<String, Long>>>()

        fun run(
            name: String,
            pick: (Recipients) -> List<String>,
        ) = scenario().use { s ->
            val r = recipients(s.harness)
            transcripts[name] = transcript(s.port, pick(r))
            val reasons =
                s.harness.jdbc
                    .sql("SELECT last_refusal_reason, count(*) FROM inbox_storage WHERE refusal_count > 0 GROUP BY 1")
                    .query { rs, _ -> rs.getString(1) to rs.getLong(2) }
                    .list()
                    .toMap()
            outcomes[name] = Triple(s.harness.messageCount(), s.harness.refusalCount(), reasons)
        }

        run("admitted") { listOf(it.admitted) }
        run("unknown") { listOf(it.unknown) }
        run("inbox") { listOf(it.inboxFull) }
        run("workspace") { listOf(it.workspaceFull) }
        run("service") { listOf(it.admitted, it.serviceFull) } // the admitted copy takes the last room first
        run("mixed") { listOf(it.admitted, it.unknown, it.inboxFull, it.workspaceFull, it.serviceFull) }
        // Same-sized references with nothing stored at all.
        run("unknown x2") { listOf(it.unknown, "x-${it.unknown}") }
        run("unknown x5") { r -> List(5) { i -> "u$i-${r.unknown}" } }

        // Every refusal path really ran...
        outcomes["admitted"] shouldBe Triple(1L, 0L, emptyMap())
        outcomes["unknown"] shouldBe Triple(0L, 0L, emptyMap())
        outcomes["inbox"] shouldBe Triple(0L, 1L, mapOf("INBOX_LIMIT" to 1L))
        outcomes["workspace"] shouldBe Triple(0L, 1L, mapOf("WORKSPACE_LIMIT" to 1L))
        outcomes["service"] shouldBe Triple(1L, 1L, mapOf("SERVICE_CAPACITY" to 1L))
        outcomes["mixed"] shouldBe Triple(1L, 3L, mapOf("INBOX_LIMIT" to 1L, "WORKSPACE_LIMIT" to 1L, "SERVICE_CAPACITY" to 1L))

        // ...and not one byte of SMTP differs from a same-sized event where nothing was stored at all.
        val single = transcripts.getValue("unknown")
        single.any { it.startsWith("250") } shouldBe true
        for (name in listOf("admitted", "inbox", "workspace")) withClue(name) { transcripts.getValue(name) shouldBe single }
        withClue("service") { transcripts.getValue("service") shouldBe transcripts.getValue("unknown x2") }
        withClue("mixed") { transcripts.getValue("mixed") shouldBe transcripts.getValue("unknown x5") }
    }
}
