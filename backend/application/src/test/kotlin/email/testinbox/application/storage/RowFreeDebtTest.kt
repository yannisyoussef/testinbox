package email.testinbox.application.storage

import email.testinbox.application.port.IncompleteUpload
import email.testinbox.application.port.ObservedFootprint
import email.testinbox.application.port.RowFreeDebtStore
import email.testinbox.application.port.ServerTime
import email.testinbox.application.port.StorageInspection
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.FootprintModel
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Rule (P) as a use case (filesystem-containment contract §2.1; TI-STORAGE-006E
 * PR D): who decides, on what, and what a refusal does to the deletion — before
 * any database. `JdbcRowFreeDebtStoreTest` proves the lock and the row.
 */
class RowFreeDebtTest {
    private val model = FootprintModel.REFERENCE

    private class Store(
        var observed: ObservedFootprint?,
    ) : RowFreeDebtStore {
        val recorded = mutableListOf<Triple<String, Long, String>>()
        val resolved = mutableListOf<String>()
        var stale = listOf<String>()

        override fun admit(
            key: String,
            bytes: Long,
            objects: Long,
            source: String,
            decide: (ObservedFootprint?) -> Boolean,
        ): Boolean = decide(observed).also { if (it) recorded += Triple(key, bytes, source) }

        override fun resolve(key: String) = resolved.add(key)

        override fun pendingOlderThan(
            age: Duration,
            limit: Int,
        ) = stale
    }

    private fun observed(
        live: Long = 0,
        trusted: Boolean = true,
    ) = ObservedFootprint(live, 1, 0, 0, trusted, 0, 1, 0, 4096, Long.MAX_VALUE, "testinbox_monitor")

    private fun policy(capacity: Long) =
        FootprintPolicy(model, FootprintAdmission.Limits(Long.MAX_VALUE / 4, 0, 0, 0, capacity, 0), "testinbox_monitor", 0)

    @Test
    fun `under ALL a deletion is admitted exactly while rule P holds on the aggregate, and refused one byte past it`() {
        val store = Store(observed(live = 10_000))
        val fits = model.bound(10_000 + 4096, 2)
        RowFreeDebt(store, policy(fits), StorageEnforcement.ALL).beforeDelete("k1", 4096, "orphan-sweep") shouldBe true
        RowFreeDebt(store, policy(fits - 1), StorageEnforcement.ALL).beforeDelete("k2", 4096, "orphan-sweep") shouldBe false
        store.recorded shouldBe listOf(Triple("k1", 4096L, "orphan-sweep"))
    }

    @Test
    fun `under ALL an untrusted ledger, or an object whose size is unknown, is never deleted`() {
        RowFreeDebt(Store(observed(trusted = false)), policy(Long.MAX_VALUE / 4), StorageEnforcement.ALL)
            .beforeDelete("k", 1, "ambiguity-verifier") shouldBe false
        RowFreeDebt(Store(observed()), policy(Long.MAX_VALUE / 4), StorageEnforcement.ALL)
            .beforeDelete("k", null, "orphan-sweep") shouldBe false
    }

    @Test
    fun `under TENANT_LIMITS and OFF the row is recorded and nothing is refused`() {
        listOf(StorageEnforcement.TENANT_LIMITS, StorageEnforcement.OFF).forEach { mode ->
            val store = Store(observed(trusted = false))
            RowFreeDebt(store, policy(1), mode).beforeDelete("k", 4096, "witness") shouldBe true
            store.recorded.size shouldBe 1
            RowFreeDebt(store, policy(1), mode).beforeDelete("u", null, "orphan-sweep") shouldBe true // unsized: deleted, uncharged
        }
    }

    @Test
    fun `without a declared filesystem nothing is recorded or refused - the pre-PR-D behaviour`() {
        val store = Store(null)
        RowFreeDebt(store, null, StorageEnforcement.ALL).beforeDelete("k", 4096, "witness") shouldBe true
        store.recorded.size shouldBe 0
    }

    private class Inspection(
        var present: Boolean,
    ) : StorageInspection {
        var witnesses = 0

        override fun objectExists(key: String) = present

        override fun incompleteUploadExists(key: String) = false

        override fun deleteObject(key: String) {
            present = false
        }

        override fun incompleteUploads() = emptyList<IncompleteUpload>()

        override fun abortIncompleteUpload(upload: IncompleteUpload) = Unit

        override fun witness(probeKey: String): Boolean {
            witnesses++
            return true
        }

        override fun serverTime(): ServerTime = error("unused")

        override fun listedPayloadBytes() = 0L
    }

    @Test
    fun `a probe refused by rule P is never written, and the witness reads as failed`() {
        val inner = Inspection(present = false)
        RowFreeDebt(Store(observed()), policy(1), StorageEnforcement.ALL).guard(inner).witness("_probe/x") shouldBe false
        inner.witnesses shouldBe 0
    }

    @Test
    fun `an admitted probe records its row first, and resolves it once the probe is proven gone`() {
        val store = Store(observed())
        val inner = Inspection(present = false)
        RowFreeDebt(store, policy(Long.MAX_VALUE / 4), StorageEnforcement.ALL).guard(inner).witness("_probe/x") shouldBe true
        inner.witnesses shouldBe 1
        store.recorded.single().second shouldBe 0L
        store.resolved shouldBe listOf("_probe/x")
    }

    @Test
    fun `the resolver resolves a stale pending row only once its key is proven absent`() {
        val store = Store(observed()).apply { stale = listOf("gone", "still-there") }
        val inspection =
            object : StorageInspection by Inspection(false) {
                override fun objectExists(key: String) = key == "still-there"
            }
        RowFreeDebt(store, policy(Long.MAX_VALUE / 4), StorageEnforcement.ALL).resolveStale(inspection) shouldBe 1
        store.resolved shouldBe listOf("gone")
    }
}
