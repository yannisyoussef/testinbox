package email.testinbox.api.web

import email.testinbox.api.auth.MissingScopeException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(MissingScopeException::class)
    fun missingScope(
        e: MissingScopeException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        Problems.respond(
            Problems.of(
                HttpStatus.FORBIDDEN,
                "missing-scope",
                "Missing scope",
                "This operation requires the '${e.scope.wire}' scope",
                request,
            ),
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(
        // Deliberately unused: parser detail must never reach the client.
        ignored: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        Problems.respond(
            Problems.of(
                HttpStatus.BAD_REQUEST,
                "invalid-request",
                "Invalid request",
                "Malformed request body",
                request,
            ),
        )

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(
        // Deliberately unused: an unparseable id is reported as a plain 404.
        ignored: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        Problems.respond(
            Problems.of(
                HttpStatus.NOT_FOUND,
                "not-found",
                "Not found",
                null,
                request,
            ),
        )

    @ExceptionHandler(Exception::class)
    fun unexpected(
        e: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        log.error("unhandled exception (correlationId={})", Correlation.of(request), e)
        return Problems.respond(
            Problems.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal error",
                "An unexpected error occurred",
                request,
            ),
        )
    }

    private companion object {
        val log = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
