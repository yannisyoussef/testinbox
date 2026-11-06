package email.testinbox.ingestion.guarded

import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.UploadOutcome
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.ingestion.config.IngestionProperties
import email.testinbox.ingestion.smtp.SmtpGateway
import email.testinbox.persistence.BundledMigrations
import email.testinbox.persistence.JdbcSchemaHistory
import email.testinbox.storage.fenced.TcpFaultProxy
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-035 §17 test 33, the owner anti-oracle test. The SMTP transcript is
 * byte-identical for an admitted recipient, an unknown one, and one refused by
 * `INBOX_LIMIT`, `WORKSPACE_LIMIT` or `SERVICE_CAPACITY`, each alone and all
 * mixed in one `DATA`. Enforcement is switched on INTERNALLY here, never in a
 * deployable. The test also asserts that each refusal really happened, so it
 * cannot pass because no quota path ran.
 *
 * The fault scenarios (2026-11-06) hold the same property while storage is
 * broken and while it recovers: a known recipient, an unknown one and one at
 * its ceiling get byte-identical transcripts from the first event after the
 * fault was found until a non-tenant witness proves storage back. The
 * breaker runs on a manual clock (its real schedule, no sleeps).
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

    private fun scenario(
        policy: StorageCapacityPolicy = policy(),
        breaker: StorageBreaker? = null,
        viaProxy: Boolean = false,
        inspectionOverride: ((StorageInspection) -> StorageInspection)? = null,
        uploadFault: () -> UploadOutcome? = { null },
    ): Scenario {
        val harness =
            if (breaker == null) {
                GuardedIngestHarness(enforcement = StorageEnforcement.ALL, policy = policy)
            } else {
                GuardedIngestHarness(
                    enforcement = StorageEnforcement.ALL,
                    policy = policy,
                    breaker = breaker,
                    viaProxy = viaProxy,
                    inspectionOverride = inspectionOverride,
                    uploadFault = uploadFault,
                )
            }
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
            // Fail, never hang the build, if no reply comes (see SmtpAccountLifecycleTest).
            socket.soTimeout = 30_000
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

    // --- global storage faults: uniform before resolution, recovery included (ADR-025, ADR-035 §8/§12) ---

    private var nanos = 0L

    /** The production schedule (15 s doubling to 2 min) on a manual clock. */
    private val breaker = StorageBreaker(Duration.ofSeconds(15), Duration.ofMinutes(2), nanoTime = { nanos })

    private fun backoffExpires() {
        nanos += breaker.currentBackoff.toNanos()
    }

    /** Room for every copy globally: a fault's released reservations must not turn recovery into a refusal. */
    private fun roomy() = StorageCapacityPolicy(10 * f, InboxShare.of("0.5"), 100 * f + 1, 1)

    /** Storage as the witness sees it: refused while [down] (the same store, the same credentials). */
    private val down = AtomicBoolean(false)
    private val witnesses = AtomicInteger()
    private val witnessFollowsStore = { real: StorageInspection ->
        object : StorageInspection by real {
            override fun witness(probeKey: String): Boolean {
                witnesses.incrementAndGet()
                check(!down.get()) { "storage refused the witness" }
                return real.witness(probeKey)
            }
        }
    }

    private fun Scenario.proxyAnswers(
        status: String,
        code: String,
    ) {
        val body = "<Error><Code>$code</Code></Error>"
        harness.proxy!!.mode = TcpFaultProxy.Mode.RESPOND
        harness.proxy.canned = "HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
    }

    /** Every recipient's transcript in [order], asserted byte-identical, and that one transcript. */
    private fun Scenario.identical(
        label: String,
        order: List<String>,
    ): List<String> {
        val transcripts = order.associateWith { transcript(port, listOf(it)) }
        val first = transcripts.getValue(order.first())
        for ((recipient, t) in transcripts) withClue("$label: $recipient") { t shouldBe first }
        return first
    }

    private fun List<String>.dataReply(): String = this[this.size - 2] // the reply to the final ".", before QUIT's

    @Test
    fun `half-open - known and unknown get byte-identical transcripts in every window until a witness succeeds`() {
        scenario(roomy(), breaker, inspectionOverride = witnessFollowsStore, uploadFault = {
            if (down.get()) UploadOutcome.NotStarted else null
        }).use { s ->
            val r = recipients(s.harness)
            down.set(true)
            // The onset: the first event to reach storage is the one that finds it unreachable.
            transcript(s.port, listOf(r.admitted)).dataReply().startsWith("451") shouldBe true
            breaker.openKinds shouldBe setOf(StorageBreaker.Kind.UNAVAILABLE)

            repeat(4) { window ->
                backoffExpires() // half-open: the backoff has expired, the store is still down
                val order = if (window % 2 == 0) listOf(r.unknown, r.admitted) else listOf(r.admitted, r.unknown)
                s.identical("window $window", order).dataReply().startsWith("451") shouldBe true
            }
            witnesses.get() shouldBe 4 // one non-tenant proof per window, whoever came first
            s.harness.messageCount() shouldBe 0

            down.set(false)
            backoffExpires()
            s.identical("recovered", listOf(r.unknown, r.admitted)).dataReply().startsWith("250") shouldBe true
            breaker.isOpen shouldBe false
            s.harness.messageCount() shouldBe 1 // the known recipient's copy was delivered
        }
    }

    @Test
    fun `QUOTA half-open - an at-ceiling recipient and an under-ceiling one get byte-identical transcripts`() {
        scenario(roomy(), breaker, viaProxy = true, inspectionOverride = witnessFollowsStore).use { s ->
            val r = recipients(s.harness)
            s.proxyAnswers("400 Bad Request", "XMinioAdminBucketQuotaExceeded")
            down.set(true) // a bucket at its hard quota refuses even the zero-byte witness
            transcript(s.port, listOf(r.admitted)).dataReply().startsWith("451") shouldBe true
            breaker.openKinds shouldBe setOf(StorageBreaker.Kind.QUOTA)

            repeat(3) { window ->
                backoffExpires()
                val order =
                    if (window % 2 == 0) {
                        listOf(r.workspaceFull, r.admitted, r.inboxFull, r.unknown)
                    } else {
                        listOf(r.admitted, r.workspaceFull, r.unknown, r.inboxFull)
                    }
                s.identical("window $window", order).dataReply().startsWith("451") shouldBe true
            }
            s.harness.refusalCount() shouldBe 0 // no ceiling was consulted while storage was broken

            s.harness.proxy!!.mode = TcpFaultProxy.Mode.PASS
            down.set(false)
            backoffExpires()
            s.identical("recovered", listOf(r.workspaceFull, r.admitted)).dataReply().startsWith("250") shouldBe true
            breaker.isOpen shouldBe false
            s.harness.messageCount() shouldBe 1
            s.harness.refusalCount() shouldBe 1 // the ceiling refusal, recorded as always once storage works
        }
    }

    @Test
    fun `DENIED - after one 403 known and unknown get the same pre-resolution 451, and the witness recovers it`() {
        scenario(roomy(), breaker, viaProxy = true, inspectionOverride = witnessFollowsStore).use { s ->
            val r = recipients(s.harness)
            s.proxyAnswers("403 Forbidden", "AccessDenied")
            down.set(true) // revoked credentials refuse the witness too
            transcript(s.port, listOf(r.admitted)).dataReply().startsWith("451") shouldBe true
            val writes = s.harness.fencedWrites.size

            // At once, with no backoff passed, and then in every half-open window.
            s.identical("open", listOf(r.unknown, r.admitted, r.workspaceFull)).dataReply().startsWith("451") shouldBe true
            breaker.openKinds shouldBe setOf(StorageBreaker.Kind.DENIED)
            repeat(2) { window ->
                backoffExpires()
                s.identical("window $window", listOf(r.admitted, r.unknown)).dataReply().startsWith("451") shouldBe true
            }
            s.harness.fencedWrites.size shouldBe writes // no tenant upload while the credentials are refused

            s.harness.proxy!!.mode = TcpFaultProxy.Mode.PASS
            down.set(false)
            backoffExpires()
            s.identical("recovered", listOf(r.unknown, r.admitted)).dataReply().startsWith("250") shouldBe true
            breaker.isOpen shouldBe false
            s.harness.messageCount() shouldBe 1
        }
    }
}
