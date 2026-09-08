package email.testinbox.api.web

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
