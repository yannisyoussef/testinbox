package email.testinbox.application.port

import java.time.Duration
import java.time.Instant

/**
 * One fenced object write (ADR-035 §5): exactly [bytes] to exactly [key],
 * create-only, authorized from the reservation's database `t0` for `E`.
 *
 * The storage server enforces every part of the fence: the key, the length,
 * the start deadline, and create-only. The application never has to trust
 * itself to stop.
 */
class ReservedUpload(
    val key: String,
    val bytes: ByteArray,
    /** T1's database clock. The URL is signed at this instant, never at the node's. */
    val signedAt: Instant,
    /** `E`. An upload that STARTS after `signedAt + validFor` is refused by the server. */
    val validFor: Duration,
) {
    override fun toString(): String = "ReservedUpload(key=$key, bytes=${bytes.size})"
}

/**
 * How one upload attempt ended. There is exactly one attempt: nothing retries,
 * because a retry is a second server-side request that the finalize budget H
 * does not cover.
 *
 * [definitive] outcomes prove whether the object exists. Every other outcome
 * is [Ambiguous]: probe E5 showed that a PUT the client saw fail can still
 * have created its object.
 */
sealed interface UploadOutcome {
    val definitive: Boolean

    /** A `2xx`: the object exists, with exactly the reserved bytes. */
    data object Stored : UploadOutcome {
        override val definitive = true
    }

    /** The storage server refused the request outright. No object was created. */
    data class Refused(
        val reason: UploadRefusal,
    ) : UploadOutcome {
        override val definitive = true
    }

    /**
     * No connection was established, so no byte of the request reached
     * storage and no object can exist.
     */
    data object NotStarted : UploadOutcome {
        override val definitive = true
    }

    /** Whether the object exists is unknown. Its reservation must not be released inline. */
    data class Ambiguous(
        val kind: AmbiguityKind,
    ) : UploadOutcome {
        override val definitive = false
    }
}

/** The definitive refusals of ADR-035 §5. */
enum class UploadRefusal {
    /** `403` AccessDenied or SignatureDoesNotMatch: the URL's deadline or binding refused it. */
    DENIED,

    /** `411`: the request tried to escape the signed length. */
    LENGTH_REQUIRED,

    /** `412`: the key already exists; create-only held. */
    ALREADY_EXISTS,

    /** `400 XMinioAdminBucketQuotaExceeded`: physical, not a tenant decision. */
    QUOTA,
}

/** Why an outcome is ambiguous. */
enum class AmbiguityKind {
    /** `T_put` expired, or the response never arrived. */
    TIMEOUT,

    /** The connection was reset or ended before a complete status line. */
    CONNECTION_LOST,

    /** A `5xx`. */
    SERVER_ERROR,

    /** Any status the ADR does not list as definitive. */
    UNEXPECTED_RESPONSE,

    /**
     * The filesystem is full: `507 XMinioStorageFull`, or a `500` naming
     * ENOSPC (containment contract §8). Still ambiguous, so the reservation
     * stays charged and the slot held, but it trips the `STORAGE_FULL`
     * breaker, whose recovery needs evidence rather than a zero-byte probe.
     */
    STORAGE_FULL,
}
