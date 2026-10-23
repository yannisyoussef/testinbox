package email.testinbox.application.port

import java.time.Duration

/**
 * The pending deletion-debt rows of the filesystem-containment contract §5.2
 * (TI-STORAGE-006E PR D): written BEFORE a deletion that no row trigger
 * charges (the orphan sweep, the ambiguity verifier, the witness and breaker
 * probes), resolved AFTER the key is proven absent.
 */
interface RowFreeDebtStore {
    /**
     * In ONE transaction under the global admission lock, as T1 is: if a
     * pending row for [key] exists already, true (a retry adds nothing to D and
     * needs no new admission). Otherwise reads the footprint inputs with T1's
     * definitions, asks [decide], and records the pending row of [bytes] in
     * [objects] only if it says yes. Returns whether the deletion may proceed.
     */
    fun admit(
        key: String,
        bytes: Long,
        objects: Long,
        source: String,
        decide: (ObservedFootprint?) -> Boolean,
    ): Boolean

    /** Re-stamps the pending row of [key] under a new order (it was proven absent). False when none was pending. */
    fun resolve(key: String): Boolean

    /** Keys of pending rows recorded more than [age] ago, at most [limit]: candidates for the resolver pass. */
    fun pendingOlderThan(
        age: Duration,
        limit: Int,
    ): List<String>
}
