package email.testinbox.application.storage

import email.testinbox.application.port.RowFreeDebtStore
import email.testinbox.application.port.StorageInspection
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageScope
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Rule (P) of the filesystem-containment contract (§2.1; TI-STORAGE-006E PR D):
 * every deletion no row trigger charges — the orphan sweep, the ambiguity
 * verifier, the witness and breaker probes — records a PENDING debt row of its
 * object BEFORE deleting it, and that write is itself an admission:
 *
 * ```
 * (P)  F(L + D + x) + W + H_F + M + R_ops ≤ C_fs
 * ```
 *
 * under T1's lock and with T1's preconditions. Refused (or not evaluable) under
 * `ALL`, the deletion does not happen: a probe is skipped, a late object stays
 * where it is and keeps its ambiguity row and write slot (fail closed). Under
 * `OFF` and `TENANT_LIMITS` the pending row is recorded and nothing is refused.
 * After the key is proven absent, [afterProvenAbsent] resolves the row under a
 * new order.
 */
class RowFreeDebt(
    private val store: RowFreeDebtStore,
    private val footprint: FootprintPolicy?,
    private val enforcement: StorageEnforcement,
) {
    /** Whether this deployment charges row-free deletions at all (a declared filesystem): only then are objects sized. */
    val charges: Boolean get() = footprint != null

    /**
     * Whether [key] (of [bytes], or unknown when null) may be deleted now. An
     * unsized object cannot be charged: refused under `ALL`, deleted without a
     * row otherwise (the pre-PR-D behaviour).
     */
    fun beforeDelete(
        key: String,
        bytes: Long?,
        source: String,
        objects: Long = 1,
    ): Boolean {
        // Without a declared filesystem there is no footprint, and nothing here: the
        // pre-PR-D behaviour, and no probe rows that no observation would compact.
        if (footprint == null) return true
        val enforced = enforcement.enforces(StorageScope.GLOBAL)
        if (bytes == null) {
            if (enforced) log.warn("storage_row_free_unsized key not deleted: its size is unknown and rule (P) cannot charge it")
            return !enforced
        }
        return store
            .admit(key, bytes, objects, source) { observed ->
                if (!enforced) {
                    true
                } else {
                    val fp = checkNotNull(footprint)
                    fp.unavailability(observed) == null &&
                        FootprintAdmission.decideRowFreeDebt(
                            fp.model,
                            fp.limits,
                            FootprintAdmission.Snapshot(
                                FootprintAdmission.Load(checkNotNull(observed).liveBytes, observed.liveObjects),
                                FootprintAdmission.Load(observed.debtBytes, observed.debtObjects),
                                observed.trashBytes,
                            ),
                            FootprintAdmission.Load(bytes, objects),
                        ) == FootprintAdmission.Verdict.ADMITTED
                }
            }.also { admitted ->
                if (!admitted) log.warn("storage_row_free_refused source={} rule (P) refused; the object is kept", source)
            }
    }

    /** The key was deleted and proven absent: its pending row gets its order. */
    fun afterProvenAbsent(key: String) {
        if (footprint != null) store.resolve(key)
    }

    /**
     * The resolver pass for pending rows whose writer crashed or whose probe PUT
     * was ambiguous: once older than [olderThan] (`T_verify`, so a late PUT has
     * had its chance), a key proven absent is resolved. A key still present stays
     * pending: it is covered, and the sweep deletes it under the same row.
     */
    fun resolveStale(
        inspection: StorageInspection,
        olderThan: Duration = StorageProtocol.T_VERIFY,
        limit: Int = 500,
    ): Int =
        if (footprint == null) {
            0
        } else {
            store.pendingOlderThan(olderThan, limit).count { key ->
                runCatching { !inspection.objectExists(key) && !inspection.incompleteUploadExists(key) }.getOrDefault(false) &&
                    store.resolve(key)
            }
        }

    /**
     * [inspection] whose witness probe is admitted by rule (P) first: a pending
     * `(0 B, 1 object)` row before the PUT, resolved after the probe is proven
     * gone. Refused, the probe is skipped and the witness reads as failed. A PUT
     * that fails leaves the row pending for [resolveStale].
     */
    fun guard(inspection: StorageInspection): StorageInspection =
        object : StorageInspection by inspection {
            override fun witness(probeKey: String): Boolean {
                if (!beforeDelete(probeKey, 0, "witness")) return false
                val listed = inspection.witness(probeKey)
                if (runCatching { !inspection.objectExists(probeKey) }.getOrDefault(false)) afterProvenAbsent(probeKey)
                return listed
            }
        }

    companion object {
        private val log = LoggerFactory.getLogger(RowFreeDebt::class.java)

        /** No debt control: every deletion proceeds and nothing is recorded (tests, and the pre-PR-D path). */
        val NONE: RowFreeDebt =
            RowFreeDebt(
                object : RowFreeDebtStore {
                    override fun admit(
                        key: String,
                        bytes: Long,
                        objects: Long,
                        source: String,
                        decide: (email.testinbox.application.port.ObservedFootprint?) -> Boolean,
                    ) = true

                    override fun resolve(key: String) = false

                    override fun pendingOlderThan(
                        age: Duration,
                        limit: Int,
                    ) = emptyList<String>()
                },
                null,
                StorageEnforcement.OFF,
            )
    }
}
