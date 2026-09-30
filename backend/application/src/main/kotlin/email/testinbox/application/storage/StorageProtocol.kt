package email.testinbox.application.storage

import java.time.Duration

/**
 * ADR-035 revision 5 protocol constants. They are not configuration: each is
 * derived in the ADR (§5, §7, §9), and changing one changes the physical
 * bound's proof.
 */
object StorageProtocol {
    /** `E`: the presigned write window, `write_deadline_at = t0 + E` (§5). */
    val WRITE_WINDOW: Duration = Duration.ofSeconds(120)

    /** `T_put`: the total wall-clock bound of one upload, enforced by the upload client (§5). */
    val T_PUT: Duration = Duration.ofSeconds(30)

    /** `ε_max`: the largest tolerated DB↔storage clock offset (§5). */
    val EPSILON_MAX: Duration = Duration.ofSeconds(30)

    /** `C_max`: qualified, NOT server-enforced (§7, §9a). */
    val C_MAX: Duration = Duration.ofMinutes(15)

    /** `S = ε_max + T_put + 30.25 s + C_max`, rounded up to 17 min (§7). */
    val SETTLE: Duration = Duration.ofMinutes(17)

    /** `C_drain`: after a completed witness, before an ambiguous release (§7). Empirical. */
    val C_DRAIN: Duration = Duration.ofSeconds(60)

    /** `T_verify`: when a persisted ambiguity is verified, always at least S (§9). */
    val T_VERIFY: Duration = Duration.ofMinutes(60)

    /** `max-concurrent-writes`: write slots per node (§5). */
    const val MAX_CONCURRENT_WRITES = 16

    /** `max-concurrent-events-per-workspace` = slots / 4 (§4). */
    const val MAX_EVENTS_PER_WORKSPACE = MAX_CONCURRENT_WRITES / 4

    /** `W_slot`: the bounded wait for a write slot (§4). */
    val SLOT_WAIT: Duration = Duration.ofSeconds(10)

    /** The capability a node publishes in `storage_node` and its `application_name` (§14). */
    const val CAPABILITY = "storage-v1"

    init {
        check(T_VERIFY >= SETTLE) { "T_verify must be at least S" }
        check(SETTLE >= EPSILON_MAX.plus(T_PUT).plusMillis(30_250).plus(C_MAX)) { "S must cover ε_max + T_put + 30.25 s + C_max" }
    }
}

/**
 * Why the storage path could not take an event. Every case is infrastructure,
 * never capacity: the whole `DATA` is answered `451` and the sender's MTA
 * retries (ADR-035 §12). None of them is ever reported as `SERVICE_CAPACITY`.
 */
enum class StorageUnavailableReason {
    LATCHED,
    BREAKER_OPEN,
    SLOT_WAIT,
    LOCK_TIMEOUT,
    UPLOAD_FAILED,
    COMMIT_FENCED,
}

class StorageUnavailableException(
    val reason: StorageUnavailableReason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
