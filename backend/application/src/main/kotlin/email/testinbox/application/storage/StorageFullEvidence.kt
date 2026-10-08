package email.testinbox.application.storage

import email.testinbox.application.port.FilesystemObservations
import email.testinbox.application.port.FilesystemSnapshot
import email.testinbox.domain.storage.FootprintModel
import java.time.Duration
import java.time.Instant

/**
 * What reopens a `STORAGE_FULL` breaker (filesystem-containment contract §8).
 * [tripped] is told of every STORAGE_FULL trip; [evidence] answers whether a
 * real-event trial may be issued now. The default is [NEVER].
 */
interface StorageFullGate {
    fun tripped()

    fun evidence(): Boolean

    companion object {
        /** No monitor: only a restart clears a STORAGE_FULL trip. */
        val NEVER: StorageFullGate = of { false }

        /** A gate that ignores trips: for tests of the breaker alone. */
        fun of(evidence: () -> Boolean): StorageFullGate =
            object : StorageFullGate {
                override fun tripped() = Unit

                override fun evidence() = evidence()
            }
    }
}

/**
 * The contract §8 evidence: the newest Ops observation
 * - began AFTER the latest STORAGE_FULL trip (on the database clock), so an
 *   observation the 507/ENOSPC just contradicted can never license a trial,
 *   and each failed trial needs a NEW observation before the next one;
 * - is no older than [maxAge], measured from when it began, and not from
 *   the future;
 * - shows at least [reserveBytes] available AND [inodeReserve] free inodes,
 *   since MinIO answers ENOSPC on inode exhaustion too.
 *
 * Anything else (no observation, a database error) is "no evidence", and the
 * breaker stays shut without consuming a trial or a slot. A negative answer
 * is cached for [negativeCacheFor], so a blocked node does not query the
 * database on every DATA.
 */
class StorageFullEvidence(
    private val observations: FilesystemObservations,
    private val maxAge: Duration,
    private val reserveBytes: Long,
    private val inodeReserve: Long,
    private val negativeCacheFor: Duration = Duration.ofSeconds(5),
    private val nanoTime: () -> Long = System::nanoTime,
) : StorageFullGate {
    init {
        require(!maxAge.isNegative && !maxAge.isZero) { "A_obs must be positive, was $maxAge" }
        require(reserveBytes > 0) { "R_ops must be positive, was $reserveBytes" }
        require(inodeReserve > 0) { "the inode reserve must be positive, was $inodeReserve" }
    }

    @Volatile private var markNeeded = false
    private var tripMark: Instant? = null
    private var negativeUntil: Long? = null

    override fun tripped() {
        markNeeded = true
    }

    @Synchronized
    override fun evidence(): Boolean {
        val mark = markNeeded
        negativeUntil?.let { until -> if (!mark && nanoTime() - until < 0) return false }
        // Taken BEFORE the read: a trip during the read sets the flag again for next time.
        markNeeded = false
        val snapshot = observations.snapshot()
        if (mark) tripMark = maxOf(tripMark ?: snapshot.databaseNow, snapshot.databaseNow)
        val holds = holds(snapshot)
        negativeUntil = if (holds) null else nanoTime() + negativeCacheFor.toNanos()
        return holds
    }

    private fun holds(snapshot: FilesystemSnapshot): Boolean {
        val newest = snapshot.newest ?: return false
        val after = tripMark?.let { newest.startedAt.isAfter(it) } ?: true
        return after &&
            !newest.age.isNegative &&
            newest.age <= maxAge &&
            newest.availBytes >= reserveBytes &&
            newest.inodesFree >= inodeReserve
    }

    companion object {
        /** The deployment's gate: A_obs, R_ops and the inode floor R_ops / B, each never below the contract's floor. */
        fun forDeclarations(
            observations: FilesystemObservations,
            filesystem: FilesystemDeclarations,
        ): StorageFullEvidence {
            val reserve = filesystem.effectiveOperationalReserveBytes
            return StorageFullEvidence(
                observations,
                filesystem.effectiveObservationMaxAge,
                reserve,
                FootprintModel.ceilDiv(reserve, filesystem.effectiveBlockSizeBytes),
            )
        }
    }
}
