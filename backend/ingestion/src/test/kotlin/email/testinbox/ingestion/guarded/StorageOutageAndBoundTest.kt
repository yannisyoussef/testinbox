package email.testinbox.ingestion.guarded

import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.storage.IngestSyncHook
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
import org.testcontainers.DockerClientFactory
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration

/**
 * ADR-035 §17 tests 31, 35 and 52:
 * - storage down answers `451` for the whole `DATA`, and the sender's retry
 *   after recovery is admitted, with no tenant refusal persisted;
 * - the owner's physical proof, run in isolation (a dedicated database and
 *   bucket, a small G, enforcement switched on inside this test only):
 *   `listed ≤ committed + reserved` at every checkpoint, and finally
 *   `G − H − f ≤ listed ≤ G − H`.
 */
class StorageOutageAndBoundTest {
    private fun smtp(
        harness: GuardedIngestHarness,
        recipient: String,
    ): String {
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
        try {
            return Socket("127.0.0.1", port).use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                val out = socket.getOutputStream()

                fun reply(): String {
                    var line: String
                    do {
                        line = reader.readLine()
                    } while (line.length > 3 && line[3] == '-')
                    return line
                }

                fun send(command: String): String {
                    out.write("$command\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                    return reply()
                }
                reply()
                send("EHLO sender.example")
                send("MAIL FROM:<sender@example.com>")
                send("RCPT TO:<$recipient>")
                send("DATA")
                out.write(GuardedIngestHarness.mime())
                val dataReply = send("\r\n.")
                send("QUIT")
                dataReply
            }
        } finally {
            gateway.stop()
        }
    }

    @Test
    fun `storage down answers 451 for the whole DATA, and the retry after recovery is admitted`() {
        GuardedIngestHarness(tPut = Duration.ofSeconds(2)).use { h ->
            val (inbox, address) = h.inbox(h.workspace())
            val docker = DockerClientFactory.instance().client()
            val minio = GuardedIngestHarness.minio.containerId
            docker.pauseContainerCmd(minio).exec()
            val down =
                try {
                    smtp(h, address)
                } finally {
                    docker.unpauseContainerCmd(minio).exec()
                }

            down.startsWith("451") shouldBe true
            h.messageCount() shouldBe 0
            h.breaker.isOpen shouldBe true

            Thread.sleep(h.breaker.currentBackoff.toMillis() + 100) // the breaker's own backoff is what is waited out
            val retried = smtp(h, address)

            retried.startsWith("250") shouldBe true
            h.messages.listVisible(inbox).size shouldBe 1
            h.refusalCount() shouldBe 0 // an outage is never a tenant refusal
            h.breaker.isOpen shouldBe false
        }
    }

    @Test
    fun `the physical bound holds at every checkpoint, and fills to within one copy of G minus H`() {
        val f =
            GuardedIngestHarness().use { calibration ->
                val (_, address) = calibration.inbox(calibration.workspace())
                calibration.deliver(listOf(address)).accepted.size shouldBe 1
                calibration.committedBytes()
            }
        // Workspace 3f, inbox = workspace, cap = G − H = 10f (H = 2f).
        val g = 12 * f
        val h2 = 2 * f
        val policy = StorageCapacityPolicy(3 * f, InboxShare.of("1"), g, h2)
        var crashNext = false
        val hook =
            object : IngestSyncHook {
                override fun beforeCommit() {
                    if (crashNext) {
                        crashNext = false
                        throw IllegalStateException("crash at beforeCommit")
                    }
                }
            }
        GuardedIngestHarness(enforcement = StorageEnforcement.ALL, policy = policy, hook = hook).use { h ->
            // Versioning off (ADR-035 §9 Ops precondition): a deleted object must really be gone.
            val versioning = h.s3.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(h.bucket).build()).status()
            (versioning == null || versioning == BucketVersioningStatus.SUSPENDED) shouldBe true

            fun checkpoint(name: String) =
                withClue("$name: listed ${h.listedBytes()} committed ${h.committedBytes()} reserved ${h.reservedBytes()}") {
                    (h.listedBytes() <= h.committedBytes() + h.reservedBytes()) shouldBe true
                }

            val workspaces = List(6) { h.workspace() }

            fun refused(reason: String) = h.count("SELECT count(*) FROM inbox_storage WHERE last_refusal_reason = '$reason'")
            // One workspace past its own limit (3 copies fit, the 4th is refused)...
            repeat(4) { h.deliver(listOf(h.inbox(workspaces[0]).second)) }
            // ...a crash mid-fill with cleanup paused: its uploaded objects stay reserved...
            crashNext = true
            runCatching { h.deliver(listOf(h.inbox(workspaces[1]).second)) }
            checkpoint("after crash at beforeCommit, cleanup paused")
            // ...then everyone until the global cap refuses.
            var i = 0
            while (refused("SERVICE_CAPACITY") == 0L) {
                h.deliver(listOf(h.inbox(workspaces[1 + i++ % 5]).second))
                check(i < 100) { "never reached the global cap" }
            }
            val refusedWorkspace = refused("WORKSPACE_LIMIT").toInt()
            (refusedWorkspace > 0) shouldBe true
            checkpoint("after fill")

            h.backdate(Duration.ofMinutes(30))
            h.releaseCycle().released shouldBe 1 // the crashed event's reservation, and its objects
            checkpoint("after cleanup")

            val listed = h.listedBytes()
            withClue("listed $listed, cap ${g - h2}, f $f") {
                (listed <= g - h2) shouldBe true
                (listed >= g - h2 - f) shouldBe true
            }
        }
    }
}
