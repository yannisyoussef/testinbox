package email.testinbox.ingestion.guarded

import email.testinbox.application.storage.FootprintPolicy
import email.testinbox.application.storage.FootprintUnavailability
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.FootprintModel
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * TI-STORAGE-006E PR D against the REAL adapters and the pinned MinIO, in an
 * isolated database and bucket. TEST ONLY: no deployable is configured this way,
 * and the committed environments stay OFF.
 *
 * - Under `ALL`, untrusted counts, no observation, or an observation the declared
 *   monitor did not write answer the whole `DATA` `451` BEFORE recipient
 *   resolution: a known and an unknown recipient get the same answer, and nothing
 *   is reserved or uploaded.
 * - With trusted counts and a valid observation, the global footprint rules refuse
 *   on the exact aggregate as `SERVICE_CAPACITY`, a `250`-class outcome recorded
 *   on the inbox, with no upload.
 * - Under `TENANT_LIMITS` the same states are observational.
 */
class FootprintEnforcementTest {
    private val open = mutableListOf<GuardedIngestHarness>()
    private val model = FootprintModel.REFERENCE

    @AfterEach
    fun close() = open.forEach { it.close() }

    private fun policy(
        capacity: Long,
        monitor: String,
    ) = FootprintPolicy(
        model,
        FootprintAdmission.Limits(
            globalFootprintLimitBytes = Long.MAX_VALUE / 4,
            finalizeBudgetBytes = 0,
            metadataBudgetBytes = 0,
            operationalReserveBytes = 0,
            capacityBytes = capacity,
            probeBudgetBytes = 0,
        ),
        monitorRole = monitor,
        capacityBytes = 0,
    )

    private fun harness(
        enforcement: StorageEnforcement,
        capacity: Long = Long.MAX_VALUE / 4,
        monitor: String = "postgres_role_of_the_test",
    ): GuardedIngestHarness {
        val probe = GuardedIngestHarness(enforcement = StorageEnforcement.OFF).also { open += it }
        val role =
            probe.jdbc
                .sql("SELECT session_user::text")
                .query(String::class.java)
                .single()
        return GuardedIngestHarness(
            enforcement = enforcement,
            footprint = policy(capacity, if (monitor == "postgres_role_of_the_test") role else monitor),
        ).also { open += it }
    }

    @Test
    fun `the T1 backstop - a check that passed before resolution, then an untrusted snapshot in T1 - answers the same 451`() {
        val probe = GuardedIngestHarness().also { open += it }
        val role =
            probe.jdbc
                .sql("SELECT session_user::text")
                .query(String::class.java)
                .single()
        val valid =
            email.testinbox.application.port
                .ObservedFootprint(0, 0, 0, 0, true, 0, Long.MAX_VALUE, 0, 4096, Long.MAX_VALUE, role)
        val h =
            GuardedIngestHarness(
                enforcement = StorageEnforcement.ALL,
                footprint = policy(Long.MAX_VALUE / 4, role),
                footprintGate = { valid }, // the race: the precheck saw a valid state
            ).also { open += it }
        h.observe() // the counts stay untrusted in the database
        val (_, a) = h.inbox(h.workspace())

        assertThrows<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.FOOTPRINT_UNAVAILABLE
        h.metrics.events shouldContain "footprint:${FootprintUnavailability.UNTRUSTED}"
        h.reservations() shouldBe 0
    }

    @Test
    fun `write slots exhausted by rows no live node answers for are a 451 before resolution - known and unknown alike`() {
        val h = harness(StorageEnforcement.ALL)
        h.trust()
        h.observe()
        repeat(16) { h.ambiguity.record("replaced-node-$it", "k$it", 1, java.time.Duration.ofHours(1)) }
        val (_, known) = h.inbox(h.workspace())

        listOf(listOf(known), listOf("nobody-${System.nanoTime()}@inbox.testinbox.email")).forEach { recipients ->
            assertThrows<StorageUnavailableException> { h.deliver(recipients) }.reason shouldBe StorageUnavailableReason.SLOT_WAIT
        }
        h.reservations() shouldBe 0
    }

    private fun GuardedIngestHarness.trust() {
        jdbc.sql("UPDATE storage_footprint_trust SET trusted_epoch = distrust_epoch WHERE id = 1").update() shouldBe 1
    }

    private fun GuardedIngestHarness.observe(trash: Long = 0) {
        val walk = jdbc.sql("SELECT storage_begin_observation()").query(Long::class.java).single()
        jdbc
            .sql(
                """
                INSERT INTO storage_filesystem_observation (started_seq, source, block_size_bytes, capacity_bytes, used_bytes,
                    avail_bytes, inodes_total, inodes_used, trash_bytes, minio_sys_bytes)
                VALUES (?, 'test-monitor', 4096, 9223372036854775807, 0, 0, 1000000, 0, ?, 0)
                """.trimIndent(),
            ).params(walk, trash)
            .update()
    }

    private fun GuardedIngestHarness.reservations() = count("SELECT count(*) FROM storage_reservation")

    @Test
    fun `under ALL, untrusted counts answer 451 before recipient resolution - known and unknown recipients alike`() {
        val h = harness(StorageEnforcement.ALL)
        h.observe()
        val (_, known) = h.inbox(h.workspace())

        listOf(listOf(known), listOf("nobody-${System.nanoTime()}@inbox.testinbox.email")).forEach { recipients ->
            assertThrows<StorageUnavailableException> { h.deliver(recipients) }.reason shouldBe
                StorageUnavailableReason.FOOTPRINT_UNAVAILABLE
        }
        h.metrics.events shouldContain "footprint:${FootprintUnavailability.UNTRUSTED}"
        h.reservations() shouldBe 0
        h.fencedWrites.size shouldBe 0
    }

    @Test
    fun `under ALL, no observation, or one the declared monitor did not write, answers 451 the same way`() {
        val unobserved = harness(StorageEnforcement.ALL)
        unobserved.trust()
        val (_, a) = unobserved.inbox(unobserved.workspace())
        assertThrows<StorageUnavailableException> { unobserved.deliver(listOf(a)) }.reason shouldBe
            StorageUnavailableReason.FOOTPRINT_UNAVAILABLE

        val foreign = harness(StorageEnforcement.ALL, monitor = "testinbox_monitor")
        foreign.trust()
        foreign.observe() // written by the test's own role, not the declared monitor
        val (_, b) = foreign.inbox(foreign.workspace())
        assertThrows<StorageUnavailableException> { foreign.deliver(listOf(b)) }.reason shouldBe
            StorageUnavailableReason.FOOTPRINT_UNAVAILABLE
        foreign.metrics.events shouldContain "footprint:${FootprintUnavailability.OBSERVATION_WRITER}"
        foreign.reservations() shouldBe 0
    }

    @Test
    fun `with trusted counts and a valid observation, the aggregate refuses as SERVICE_CAPACITY - a 250-class outcome`() {
        // Room for exactly one copy of the harness's message: its footprint as one object, plus the observed trash.
        val trash = 50_000L
        val probe = harness(StorageEnforcement.OFF)
        val (_, any) = probe.inbox(probe.workspace())
        probe.deliver(listOf(any)).accepted.size shouldBe 1
        val bytes = probe.committedBytes()
        val objects = probe.count("SELECT count(*) FROM message") + probe.count("SELECT count(*) FROM attachment")
        val oneCopy = model.bound(bytes, objects) + trash

        val h = harness(StorageEnforcement.ALL, capacity = oneCopy)
        h.trust()
        h.observe(trash)
        val ws = h.workspace()
        val (_, first) = h.inbox(ws)
        val (_, second) = h.inbox(ws)

        val result = h.deliver(listOf(first, second))
        result.accepted.size shouldBe 1
        result.storageRefusedRecipients shouldBe 1
        h.count("SELECT count(*) FROM inbox_storage WHERE last_refusal_reason = 'SERVICE_CAPACITY' AND refusal_count = 1") shouldBe 1
        h.metrics.events shouldContain "admission:REFUSED_GLOBAL"
        h.messageCount() shouldBe 1
    }

    @Test
    fun `under TENANT_LIMITS the same states are observational - delivered, and the global ceiling named`() {
        val h = harness(StorageEnforcement.TENANT_LIMITS, capacity = 1)
        val (_, a) = h.inbox(h.workspace())
        h.deliver(listOf(a)).accepted.size shouldBe 1 // untrusted and unobserved: nothing refuses
        h.trust()
        h.observe()
        val (_, b) = h.inbox(h.workspace())
        h.deliver(listOf(b)).accepted.size shouldBe 1
        h.metrics.events shouldContain "unenforced:GLOBAL"
    }
}
