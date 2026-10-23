package email.testinbox.api.web

import email.testinbox.application.usecase.WaitForMessage
import email.testinbox.domain.limits.QuotaDimension
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import java.net.URI
import java.time.Duration

/**
 * RFC 7807 problems with stable type URIs under
 * `https://testinbox.email/problems/` (docs/api/principles.md).
 */
object Problems {
    private const val BASE = "https://testinbox.email/problems"

    /**
     * The two idempotency refusals a client must tell apart (ADR-033 §7).
     * Collapsing them into one type would invert the correct action in one of
     * the two cases: one is terminal, the other is a retry.
     */
    fun keyReused(request: jakarta.servlet.http.HttpServletRequest): ProblemDetail =
        of(
            HttpStatus.CONFLICT,
            "idempotency-key-reused",
            "Idempotency key reused",
            "This Idempotency-Key is already bound to a different request. Retrying with it cannot succeed; " +
                "use a new key.",
            request,
        )

    fun inProgress(request: jakarta.servlet.http.HttpServletRequest): ProblemDetail =
        of(
            HttpStatus.CONFLICT,
            "idempotency-request-in-progress",
            "Idempotency request in progress",
            "An identical request with this Idempotency-Key is still running. Retry with the same key.",
            request,
        )

    fun replayUnavailable(request: jakarta.servlet.http.HttpServletRequest): ProblemDetail =
        of(
            HttpStatus.CONFLICT,
            "idempotency-replay-unavailable",
            "Idempotency replay unavailable",
            "This Idempotency-Key is bound to a committed request whose result this server cannot reproduce. " +
                "Use a new key.",
            request,
        )

    /**
     * ADR-035 §13c: a wait that opted in observed a storage refusal after its
     * boundary. Rendered ENTIRELY from the use-case result, which came from
     * the deciding snapshot; this never reads the database.
     *
     * `quota`, `limit` and `current` reuse the existing quota members and are
     * present only for the two tenant scopes. `SERVICE_CAPACITY` is one bit:
     * no member of this problem carries a global figure, and the members are
     * absent, not zero (§13d). No `Retry-After`: waiting does not help
     * (ADR-027 §8).
     */
    fun storageLimitExceeded(
        result: WaitForMessage.Result.StorageLimitExceeded,
        request: HttpServletRequest,
    ): ProblemDetail =
        of(
            HttpStatus.CONFLICT,
            "storage-limit-exceeded",
            "Storage limit exceeded",
            "A storage ceiling (${result.refusalReason.name}) refused a copy for this inbox after the observed refusal count " +
                "${result.afterStorageRefusalCount}; the awaited message may have been the refused copy",
            request,
        ).also {
            it.setProperty("inboxId", result.inboxId.value)
            it.setProperty("refusalReason", result.refusalReason.name)
            it.setProperty("afterStorageRefusalCount", result.afterStorageRefusalCount)
            it.setProperty("storageRefusalCount", result.refusals.count)
            it.setProperty("lastStorageRefusalAt", result.refusals.lastAt)
            result.tenantScope?.let { scope ->
                it.setProperty("quota", QuotaDimension.STORED_BYTES.name)
                it.setProperty("limit", scope.limitBytes)
                it.setProperty("current", scope.currentBytes)
            }
        }

    fun of(
        status: HttpStatus,
        type: String,
        title: String,
        detail: String?,
        request: HttpServletRequest,
    ): ProblemDetail {
        val problem = ProblemDetail.forStatus(status)
        problem.type = URI.create("$BASE/$type")
        problem.title = title
        problem.detail = detail
        problem.setProperty("correlationId", Correlation.of(request))
        return problem
    }

    /**
     * [retryAfter] is emitted as RFC 9110 `Retry-After` (integer seconds) and
     * mirrored into the body so an SDK need not parse headers. Set it only
     * where waiting genuinely helps: a `Retry-After` on a quota refusal would
     * invite a retry loop that cannot succeed (ADR-027 §8).
     */
    @JvmOverloads
    fun respond(
        problem: ProblemDetail,
        retryAfter: Duration? = null,
    ): ResponseEntity<ProblemDetail> {
        val builder =
            ResponseEntity
                .status(problem.status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        if (retryAfter != null) {
            val seconds = maxOf(1L, retryAfter.toSeconds())
            builder.header(HttpHeaders.RETRY_AFTER, seconds.toString())
            problem.setProperty("retryAfterSeconds", seconds)
        }
        return builder.body(problem)
    }
}
