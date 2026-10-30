package email.testinbox.ingestion.guarded

import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.FilesystemDeclarations
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.usecase.ReserveCohortStorage
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.FootprintModel
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.domain.storage.ParticipantStorageShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.ingestion.config.IngestionProperties
import email.testinbox.ingestion.smtp.SmtpGateway
import email.testinbox.persistence.BundledMigrations
import email.testinbox.persistence.JdbcCohortBudgets
import email.testinbox.persistence.JdbcFootprintGate
import email.testinbox.persistence.JdbcSchemaHistory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.UUID

/**
 * TI-DELIVERY-002b acceptance item 3: a participant at its ADR-037 share gets,
 * over REAL SMTP, exactly the transcript an unknown recipient gets, and nothing
 * of the refused copy is persisted.
 *
 * Real adapters throughout (the harness), under `ALL`, with a cohort pool
 * reserved by the real `ReserveCohortStorage` over a trusted ledger, a monitor
 * observation and cohort-capable nodes — the pool is READY, so a refusal here is
 * the per-workspace share, never a `451` for an unready pool. One participant
 * sits exactly at its 128 MiB payload share, another one object short of its
 * 256 MiB footprint share. Their next message is answered `250` like an unknown
 * recipient's, stores no message row, uploads no object, reserves nothing, and
 * leaves only the existing private refusal record (`inbox_storage`), with the
 * reasons `WORKSPACE_LIMIT` and `WORKSPACE_FOOTPRINT_LIMIT`. A legacy workspace
 * in the same pool still receives, so the pool really was admitting.
 *
 * TEST ONLY: no deployable is configured this way.
 */
class SmtpCohortQuotaRefusalTest {
    private val gib = 1L shl 30
    private lateinit var harness: GuardedIngestHarness
    private lateinit var gateway: SmtpGateway
    private var port = 0
    private lateinit var declarations: StorageDeclarations

    private lateinit var control: String
    private lateinit var payloadFull: String
    private lateinit var footprintFull: String
    private lateinit var payloadFullInbox: UUID
    private lateinit var footprintFullInbox: UUID

    @BeforeEach
    fun pool() {
        val probe = GuardedIngestHarness()
        val monitor =
            probe.jdbc
                .sql("SELECT session_user::text")
                .query(String::class.java)
                .single()
        probe.close()
        declarations =
            StorageDeclarations(
                enforcement = StorageEnforcement.ALL,
                globalLimitBytes = 10 * gib,
                declaredMaxIngestionProcesses = 1,
                inboxShare = "1",
                expectedApiNodes = setOf("api"),
                expectedIngestionNodes = setOf("ingest-test"),
                filesystem =
                    FilesystemDeclarations(
                        4096,
                        32768,
                        16 * gib,
                        2 * gib,
                        gib,
                        2 * gib,
                        32 * gib,
                        8_000_000,
                        Duration.ofMinutes(15),
                        256L shl 20,
                        monitor,
                    ),
            )
        val footprint = checkNotNull(EffectiveStoragePolicy.footprint(declarations))
        val binding = checkNotNull(footprint.cohort?.binding)
        harness =
            GuardedIngestHarness(
                enforcement = StorageEnforcement.ALL,
                policy =
                    StorageCapacityPolicy(
                        2 * gib,
                        InboxShare.of("1"),
                        10 * gib,
                        EffectiveStoragePolicy.finalizeBudget(declarations).bytes,
                    ),
                footprint = footprint,
                cohortMode = StorageEnforcement.ALL,
                cohortBinding = binding,
            )
        harness.jdbc
            .sql(
                "INSERT INTO storage_node(node_id,generation,capability,heartbeat_at,clean_shutdown," +
                    "cohort_capability,cohort_mode,cohort_binding) " +
                    "VALUES ('api',?,'storage-guarded-v1',now(),false,'cohort-storage-v1','ALL',?)",
            ).params(UUID.randomUUID(), binding)
            .update()

        val legacy = harness.workspace()
        control = harness.inbox(legacy).second
        val payloadWs = participant(1)
        val (pInbox, pAddress) = harness.inbox(payloadWs)
        payloadFullInbox = pInbox.value
        payloadFull = pAddress
        val footprintWs = participant(2)
        val (fInbox, fAddress) = harness.inbox(footprintWs)
        footprintFullInbox = fInbox.value
        footprintFull = fAddress

        // Exactly the 128 MiB payload share, held by ANOTHER inbox of the workspace: eight 15 MiB
        // messages and one of 8 MiB. The recipient inbox itself is empty, so the workspace share
        // (not the inbox's own limit, which is narrower and refused first) is what refuses.
        val filled = harness.inbox(payloadWs).first.value
        repeat(8) { messages(payloadWs, filled, 1, 15L shl 20) }
        messages(payloadWs, filled, 1, 8L shl 20)
        // The most one-byte objects whose footprint still fits 256 MiB: any further object exceeds it.
        val model = FootprintModel(4096, 32768)
        var n = 0L
        while (model.bound(n + 1, n + 1) <= ParticipantStorageShare.FOOTPRINT_BYTES) n++
        messages(footprintWs, fInbox.value, n, 1)

        harness.jdbc
            .sql("SELECT storage_confirm_footprint_trust()")
            .query(Boolean::class.java)
            .single() shouldBe true
        val walk =
            harness.jdbc
                .sql("SELECT storage_begin_observation()")
                .query(Long::class.java)
                .single()
        harness.jdbc
            .sql(
                """INSERT INTO storage_filesystem_observation(started_seq,source,block_size_bytes,capacity_bytes,used_bytes,avail_bytes,inodes_total,inodes_used,trash_bytes,minio_sys_bytes)
                VALUES (?,'cohort-smtp-test',4096,?,0,?,8000000,0,0,0)""",
            ).params(walk, 32 * gib, 32 * gib)
            .update()
        ReserveCohortStorage(JdbcCohortBudgets(harness.jdbc), harness.transactions, JdbcFootprintGate(harness.jdbc), declarations)
            .execute(mapOf(WorkspaceId(legacy.value) to LegacyStorageShare(gib, 2 * gib)), gib, gib)
        val observed = JdbcFootprintGate(harness.jdbc).observe()
        withClue("the pool must be READY, or a refusal would be an unready pool's 451") {
            (footprint.unavailability(observed) == null && footprint.cohort?.ready(observed?.cohort) == true) shouldBe true
        }

        port = ServerSocket(0).use { it.localPort }
        val properties = IngestionProperties(mailDomain = "testinbox.local", smtp = IngestionProperties.Smtp(port))
        gateway =
            SmtpGateway(
                harness.receive,
                properties,
                properties.toConfig(),
                SchemaCompatibility(JdbcSchemaHistory(harness.jdbc), BundledMigrations.highest()),
            )
        gateway.start()
    }

    @AfterEach
    fun close() {
        gateway.stop()
        harness.close()
    }

    /** An ACTIVE, verified participant holding place [place], as `AdmitBetaParticipant` provisions it. */
    private fun participant(place: Int): WorkspaceId {
        val ws = harness.workspace()
        val account = UUID.randomUUID()
        harness.jdbc
            .sql(
                "INSERT INTO human_account (id, contact_email, contact_verified_at, state, created_at) VALUES (?, ?, now(), 'ACTIVE', now())",
            ).params(account, "$account@example.invalid")
            .update()
        harness.jdbc
            .sql(
                "INSERT INTO beta_admission(cohort_id,place,account_id,workspace_id,project_id,admitted_at) VALUES ('beta-30',?,?,?,?,now())",
            ).params(place, account, ws.value, ws.value)
            .update()
        harness.jdbc
            .sql("INSERT INTO participant_workspace(workspace_id,cohort_id,place,allocated_at) VALUES (?,'beta-30',?,now())")
            .params(ws.value, place)
            .update()
        return ws
    }

    /** [count] committed message rows of [bytes] each, in one statement: the ledger triggers account them. */
    private fun messages(
        workspace: WorkspaceId,
        inbox: UUID,
        count: Long,
        bytes: Long,
    ) {
        harness.jdbc
            .sql(
                """INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to, raw_object_key,
                    raw_size_bytes, content_fingerprint, parse_status)
                SELECT gen_random_uuid(), ?, ?, now(), 'smtp', 'fill@testinbox.local', 'fill', ?, 'fp', 'OK'
                  FROM generate_series(1, ?)""",
            ).params(workspace.value, inbox, bytes, count)
            .update()
    }

    private fun transcript(recipients: List<String>): List<String> =
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
            reply()
            send("EHLO sender.example")
            send("MAIL FROM:<sender@example.com>")
            recipients.forEach { send("RCPT TO:<$it>") }
            send("DATA")
            out.write(GuardedIngestHarness.mime())
            send("\r\n.")
            send("QUIT")
            lines
        }

    private data class Persisted(
        val messages: Long,
        val attachments: Long,
        val reservations: Long,
        val ambiguity: Long,
        val listed: Long,
        val uploads: Int,
    )

    private fun persisted() =
        Persisted(
            harness.messageCount(),
            harness.count("SELECT count(*) FROM attachment"),
            harness.count("SELECT count(*) FROM storage_reservation"),
            harness.unresolvedAmbiguity().toLong(),
            harness.listedBytes(),
            harness.fencedWrites.size,
        )

    private fun refusal(inbox: UUID): Pair<String?, Long> =
        harness.jdbc
            .sql("SELECT last_refusal_reason, refusal_count FROM inbox_storage WHERE inbox_id = ?")
            .param(inbox)
            .query { rs, _ -> rs.getString(1) to rs.getLong(2) }
            .optional()
            .orElse(null to 0L)

    @Test
    fun `a participant at its payload or footprint share gets the unknown recipient's 250 and nothing is persisted`() {
        val before = persisted()
        val unknown = transcript(listOf("nobody-${System.nanoTime()}@testinbox.local"))
        unknown.any { it.startsWith("250") } shouldBe true
        persisted() shouldBe before

        val payloadRefused = transcript(listOf(payloadFull))
        withClue("payload share") { payloadRefused shouldBe unknown }
        persisted() shouldBe before
        refusal(payloadFullInbox) shouldBe ("WORKSPACE_LIMIT" to 1L)

        val footprintRefused = transcript(listOf(footprintFull))
        withClue("footprint share") { footprintRefused shouldBe unknown }
        persisted() shouldBe before
        refusal(footprintFullInbox) shouldBe ("WORKSPACE_FOOTPRINT_LIMIT" to 1L)

        // Positive control: the same pool admits the legacy workspace's copy, with the same transcript.
        val admitted = transcript(listOf(control))
        withClue("admitted") { admitted shouldBe unknown }
        harness.messageCount() shouldBe before.messages + 1
        // Uploads happened for the admitted copy only: none before it, some now.
        (harness.fencedWrites.size > before.uploads) shouldBe true
    }
}
