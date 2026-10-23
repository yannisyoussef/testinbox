package email.testinbox.ingestion.guarded

import email.testinbox.application.port.StorageInspection
import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * The remaining ADR-035 §17 cases that protect the live guarded path:
 * - test 3: a parse failure costs the raw bytes only;
 * - test 13: an unknown-only event takes no slot and no T1, and the lock
 *   timeout is a `451`;
 * - test 27: storage down during cleanup; an inbox hard-deleted while
 *   `RESERVED`; path [D];
 * - test 54: no release while the witness is blocked.
 */
class GuardedIngestEdgeCasesTest {
    private val harnesses = mutableListOf<GuardedIngestHarness>()

    @AfterEach
    fun close() = harnesses.forEach { it.close() }

    private fun track(h: GuardedIngestHarness) = h.also { harnesses += it }

    private val crashBeforeCommit =
        object : IngestSyncHook {
            override fun beforeCommit(): Unit = error("died before T2")
        }

    @Test
    fun `a message that fails to parse costs its raw bytes only, stored through the fence`() {
        val h = track(GuardedIngestHarness())
        val (inbox, a) = h.inbox(h.workspace())
        val raw = "X-Broken: yes\r\nContent-Type: multipart/mixed; boundary=\r\n\r\ngarbage".toByteArray()

        h.deliver(listOf(a), raw).accepted.size shouldBe 1

        h.fencedWrites.size shouldBe 1 // raw.eml only: no attachment was extracted
        h.committedBytes() shouldBe raw.size.toLong()
        h.messages
            .listVisible(inbox)
            .single()
            .parseStatus.name shouldBe "FAILED"
    }

    @Test
    fun `an event with only unknown recipients takes no slot, no T1 and writes nothing`() {
        val steps = mutableListOf<String>()
        val h =
            track(
                GuardedIngestHarness(
                    hook =
                        object : IngestSyncHook {
                            override fun afterSlot() {
                                steps += "slot"
                            }
                        },
                ),
            )

        h.deliver(listOf("nobody-${UUID.randomUUID()}@testinbox.local")).discardedRecipients shouldBe 1

        steps.shouldBeEmpty()
        h.fencedWrites.shouldBeEmpty()
        h.reservationStates() shouldBe emptyMap()
    }

    @Test
    fun `a T1 that cannot take the admission lock is a 451, never a capacity refusal, and reserves nothing`() {
        val h = track(GuardedIngestHarness())
        val (_, a) = h.inbox(h.workspace())
        h.dataSource.connection.use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(35, 1)") }

            shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.LOCK_TIMEOUT

            holder.rollback()
        }
        h.reservationStates() shouldBe emptyMap()
        h.metrics.events.contains("failure:LOCK_TIMEOUT") shouldBe true
    }

    @Test
    fun `storage failing during cleanup leaves the reservation RELEASING and still charged`() {
        var storageDown = false
        val flaky = { real: StorageInspection ->
            object : StorageInspection by real {
                override fun objectExists(key: String): Boolean = if (storageDown) error("storage unreachable") else real.objectExists(key)
            }
        }
        val h = track(GuardedIngestHarness(hook = crashBeforeCommit, inspectionOverride = flaky))
        val (_, a) = h.inbox(h.workspace())
        runCatching { h.deliver(listOf(a)) }
        h.backdate(Duration.ofMinutes(30))
        val cleanup = h.cleanup()
        cleanup.run() // expire + witness
        h.tick() // the release is now due

        storageDown = true
        cleanup.run().failed shouldBe 1 // logged and counted; the pass itself carries on

        h.reservationStates() shouldBe mapOf("RELEASING" to 1L)
        (h.reservedBytes() > 0) shouldBe true
        storageDown = false
        cleanup.run().released shouldBe 1
    }

    @Test
    fun `an inbox hard-deleted while its copy is RESERVED fails T2 closed, and cleanup reclaims the objects`() {
        lateinit var h: GuardedIngestHarness
        val deleteInbox =
            object : IngestSyncHook {
                override fun afterUploads() {
                    h.jdbc.sql("DELETE FROM inbox").update() // retention wins the race
                }
            }
        h = track(GuardedIngestHarness(hook = deleteInbox))
        val (_, a) = h.inbox(h.workspace())

        // T2 finds the inbox gone under its FOR SHARE lock and fences the event (TI-STORAGE-006E):
        // the gateway's 451, before any insert can fail on the vanished row.
        shouldThrow<email.testinbox.application.storage.StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe
            email.testinbox.application.storage.StorageUnavailableReason.COMMIT_FENCED

        h.messageCount() shouldBe 0
        // Every upload was Stored, definitively: the charge goes to RELEASING at
        // once (no inbox FK: it survives the inbox), and cleanup deletes the objects.
        h.reservationStates() shouldBe mapOf("RELEASING" to 1L)
        h.backdate(Duration.ofMinutes(30))
        h.releaseCycle().released shouldBe 1
        h.listedBytes() shouldBe 0
    }

    @Test
    fun `a late object latches even when another row of the same pass fails on storage`() {
        // ADR-035 §9 point 3: the latch commits on its own, before the evidence is
        // deleted, and each row is its own transaction. A batch-wide transaction
        // would roll the latch back with the failing row.
        lateinit var h: GuardedIngestHarness
        var brokenKeys = emptySet<String>()
        val flaky = { real: StorageInspection ->
            object : StorageInspection by real {
                override fun objectExists(key: String): Boolean =
                    if (key in brokenKeys) error("storage unreachable for this key") else real.objectExists(key)
            }
        }
        val lateCommit =
            object : email.testinbox.application.storage.CleanupSyncHook {
                override fun afterDeleteBeforeList(key: String) {
                    if (key !in brokenKeys) {
                        h.s3.putObject(
                            software.amazon.awssdk.services.s3.model.PutObjectRequest
                                .builder()
                                .bucket(h.bucket)
                                .key(key)
                                .build(),
                            software.amazon.awssdk.core.sync.RequestBody
                                .fromBytes(byteArrayOf(1)),
                        )
                    }
                }
            }
        h = track(GuardedIngestHarness(hook = crashBeforeCommit, inspectionOverride = flaky, cleanupHook = lateCommit))
        runCatching { h.deliver(listOf(h.inbox(h.workspace()).second)) }
        val first = h.reservationKeys().toSet()
        runCatching { h.deliver(listOf(h.inbox(h.workspace()).second)) }
        brokenKeys = h.reservationKeys().toSet() - first
        h.backdate(Duration.ofMinutes(30))

        val report = h.releaseCycle()

        report.lateObjects shouldBe 1
        report.failed shouldBe 1
        h.latched() shouldBe "late object found while releasing a reservation"
        h.reservationStates() shouldBe mapOf("RELEASING" to 2L) // the late one postponed, the failed one untouched
    }

    @Test
    fun `while the breaker is open, an event for unknown recipients gets the same 451 as one for a known inbox`() {
        // Checked before any recipient resolves: the 451/250 split would otherwise
        // tell a sender which addresses exist (ADR-025).
        val h =
            track(
                GuardedIngestHarness(
                    breaker =
                        email.testinbox.application.storage
                            .StorageBreaker(Duration.ofMinutes(1)),
                ),
            )
        val (_, known) = h.inbox(h.workspace())
        h.breaker.trip(email.testinbox.application.storage.StorageBreaker.Kind.AMBIGUOUS)

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(known)) }.reason shouldBe StorageUnavailableReason.BREAKER_OPEN
        shouldThrow<StorageUnavailableException> {
            h.deliver(listOf("nobody-${UUID.randomUUID()}@testinbox.local"))
        }.reason shouldBe StorageUnavailableReason.BREAKER_OPEN
        h.reservationStates() shouldBe emptyMap()
    }

    @Test
    fun `a reservation fenced by cleanup before its first byte uploads nothing, and is released at once`() {
        lateinit var h: GuardedIngestHarness
        val fenceFirst =
            object : IngestSyncHook {
                override fun afterAdmission() {
                    h.backdate(Duration.ofMinutes(5))
                    h.reservations.expireOverdue(Duration.ofMinutes(17)) shouldBe 1
                }
            }
        h = track(GuardedIngestHarness(hook = fenceFirst))
        val (_, a) = h.inbox(h.workspace())

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.COMMIT_FENCED

        h.fencedWrites.shouldBeEmpty() // markUploadStarted refused: not one byte sent
        h.count("SELECT count(*) FROM storage_reservation WHERE first_upload_at IS NOT NULL") shouldBe 0
        h.metrics.events.contains("fenced") shouldBe true
    }

    @Test
    fun `path D - a reservation whose id already has a message is released without deleting anything, and alarms`() {
        val h = track(GuardedIngestHarness())
        val (_, a) = h.inbox(h.workspace())
        h.deliver(listOf(a)).accepted.size shouldBe 1
        val committed =
            h.jdbc
                .sql("SELECT id FROM message")
                .query(UUID::class.java)
                .single()
        val keys = h.fencedWrites.toList()
        // Impossible by I4; forced here to prove cleanup never deletes committed content.
        h.jdbc
            .sql(
                """
                INSERT INTO storage_reservation (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at,
                                                 write_deadline_at, release_not_before, node_id, generation)
                SELECT id, workspace_id, inbox_id, ARRAY[:k]::text[], 1, 'RELEASING', now(), now(), now(), 'n', gen_random_uuid()
                  FROM message WHERE id = :id
                """.trimIndent(),
            ).param("k", keys)
            .param("id", committed)
            .update()
        h.releaseCycle().released shouldBe 1

        h.metrics.events.contains("released:RECONCILED") shouldBe true
        keys.forEach { h.inspection.objectExists(it) shouldBe true } // nothing deleted
    }

    @Test
    fun `no ambiguous reservation is released while the storage witness is blocked`() {
        var witnessWorks = false
        val stalled = { real: StorageInspection ->
            object : StorageInspection by real {
                override fun witness(probeKey: String) = witnessWorks && real.witness(probeKey)
            }
        }
        val h = track(GuardedIngestHarness(hook = crashBeforeCommit, inspectionOverride = stalled))
        val (_, a) = h.inbox(h.workspace())
        runCatching { h.deliver(listOf(a)) }
        h.backdate(Duration.ofMinutes(30))
        val cleanup = h.cleanup()

        repeat(3) {
            cleanup.run().released shouldBe 0 // however long it has been
            h.tick()
        }

        h.reservationStates() shouldBe mapOf("RELEASING" to 1L)
        witnessWorks = true
        h.releaseCycle(cleanup).released shouldBe 1 // a witness completes, issued after release_not_before
    }

    @Test
    fun `a started node publishes its storage-v1 capability generation`() {
        // ADR-035 §14 (a): the activation barrier's positive inventory. The
        // session names are proven on the real deployable (EnforcementOffLiveTest).
        val h = track(GuardedIngestHarness())
        h.count("SELECT count(*) FROM storage_node WHERE capability = 'storage-v1'") shouldBe 1
    }
}
