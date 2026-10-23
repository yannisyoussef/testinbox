package email.testinbox.application.storage

import java.time.Duration

/**
 * ADR-035 revision 5 protocol constants. They are not configuration: each is
 * derived in the ADR (§5, §7, §9), and changing one changes the physical
 * bound's proof.
 */
object StorageProtocol {
    /**
     * `E`: the presigned write window, `write_deadline_at = t0 + E` (§5). One
     * value: T1 sets the deadline with it and the URL expires with it.
     */
    val WRITE_WINDOW: Duration = email.testinbox.application.usecase.StorageAdmission.WRITE_WINDOW

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

    /** A generation whose heartbeat is older than this, and that did not shut down cleanly, is dead (§9). */
    val STALE_HEARTBEAT: Duration = Duration.ofMinutes(5)

    /** `max-concurrent-writes`: write slots per node (§5). */
    const val MAX_CONCURRENT_WRITES = 16

    /** `max-concurrent-events-per-workspace` = slots / 4 (§4). */
    const val MAX_EVENTS_PER_WORKSPACE = MAX_CONCURRENT_WRITES / 4

    /** `W_slot`: the bounded wait for a write slot (§4). */
    val SLOT_WAIT: Duration = Duration.ofSeconds(10)

    /** The capability a node publishes in `storage_node` and its `application_name` (§14). */
    const val CAPABILITY = "storage-v1"

    /**
     * The identity of the physical upload protocol implementation (ADR-035
     * §9a, TI-STORAGE-006 §14): presigned single-part PUT with the signed
     * `content-length` and `If-None-Match: *` headers, one attempt, total
     * wall-clock `T_put`, abort by RST, no pipelining, direct connection.
     *
     * It is compared against the qualification record a deployment declares,
     * so it MUST change whenever a change could invalidate that record:
     * presigning semantics, signed headers, retry behaviour, the PUT
     * implementation, `T_put`, the RST, pipelining or the connection path.
     * It is deliberately not derived from a build timestamp or a Git SHA,
     * which change without the protocol changing. `FencedUploaderVersionTest`
     * pins the behaviour this string names.
     *
     * v2 (Ops E8, 2026-10-09): the response is read concurrently with the body,
     * and a non-2xx answer before the body is complete ends the attempt by RST.
     * Records qualified with v1 no longer match, by design (re-qualify, §9a).
     */
    const val UPLOAD_IMPLEMENTATION_VERSION = "adr035-presigned-put-v2"

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

    /** TI-STORAGE-006 §22: a non-OFF node re-checked the §14 barrier and found it broken. Infrastructure, never capacity. */
    ACTIVATION_VIOLATED,

    /**
     * TI-STORAGE-006E: under `ALL`, the global footprint rules cannot be
     * evaluated (untrusted counts, no or an invalid observation, overflow).
     * Infrastructure, never capacity (contract §2.1).
     */
    FOOTPRINT_UNAVAILABLE,
}

class StorageUnavailableException(
    val reason: StorageUnavailableReason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
