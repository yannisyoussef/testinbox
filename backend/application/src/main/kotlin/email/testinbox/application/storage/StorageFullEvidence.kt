package email.testinbox.application.storage

import email.testinbox.application.port.FilesystemObservations
import java.time.Duration

/**
 * The containment contract §8 gate on a `STORAGE_FULL` trial: the newest Ops
 * observation is no older than [maxAge] (and not from the future) and shows at
 * least [reserveBytes] available. Anything else (no observation, a stale one,
 * a database error) is "no evidence", and the breaker stays shut without
 * consuming a trial or a slot.
 */
class StorageFullEvidence(
    private val observations: FilesystemObservations,
    private val maxAge: Duration,
    private val reserveBytes: Long,
) : () -> Boolean {
    init {
        require(!maxAge.isNegative && !maxAge.isZero) { "A_obs must be positive, was $maxAge" }
        require(reserveBytes > 0) { "R_ops must be positive, was $reserveBytes" }
    }

    override fun invoke(): Boolean {
        val newest = observations.newest() ?: return false
        return !newest.age.isNegative && newest.age <= maxAge && newest.availBytes >= reserveBytes
    }
}
