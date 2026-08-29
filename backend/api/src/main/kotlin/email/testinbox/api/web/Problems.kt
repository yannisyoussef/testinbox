package email.testinbox.api.web

import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity

/**
 * RFC 7807 problems with stable type URIs under
 * `https://testinbox.email/problems/` (docs/api/principles.md).
 */
object Problems {
    private const val BASE = "https://testinbox.email/problems"

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

    fun respond(problem: ProblemDetail): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(problem.status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem)
}
