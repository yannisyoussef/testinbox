package email.testinbox.api.web

import email.testinbox.api.auth.MissingScopeException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

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

    /**
     * Spring's own request-dispatch failures already carry the right status;
     * they reach here only because [unexpected] catches `Exception` and so
     * catches everything. Left unmapped they became `500`s — a contract
     * violation, an ERROR stack trace for every client typo, and, worst,
     * genuine `500`s no longer distinguishable from mistyped URLs in the one
     * signal an operator watches.
     *
     * Logged at DEBUG: a client's mistake is not a server event. Nothing from
     * the exception reaches the body — the type and status are the whole
     * useful content, and the message can contain framework internals.
     */
    @ExceptionHandler(
        HttpRequestMethodNotSupportedException::class,
        NoHandlerFoundException::class,
        NoResourceFoundException::class,
        HttpMediaTypeNotSupportedException::class,
        HttpMediaTypeNotAcceptableException::class,
        MissingServletRequestParameterException::class,
    )
    fun dispatchFailure(
        e: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        log.debug("request could not be dispatched (correlationId={}): {}", Correlation.of(request), e.javaClass.simpleName)
        val (status, type, title) =
            when (e) {
                is HttpRequestMethodNotSupportedException -> {
                    Triple(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method not allowed")
                }

                is HttpMediaTypeNotSupportedException -> {
                    Triple(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", "Unsupported media type")
                }

                is HttpMediaTypeNotAcceptableException -> {
                    Triple(HttpStatus.NOT_ACCEPTABLE, "not-acceptable", "Not acceptable")
                }

                is MissingServletRequestParameterException -> {
                    Triple(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request")
                }

                else -> {
                    Triple(HttpStatus.NOT_FOUND, "not-found", "Not found")
                }
            }
        val problem = Problems.of(status, type, title, null, request)
        val response = Problems.respond(problem)
        // RFC 9110 makes Allow mandatory on a 405, and it is what tells the
        // client what to do instead.
        if (e is HttpRequestMethodNotSupportedException && e.supportedMethods != null) {
            return ResponseEntity
                .status(status)
                .headers(response.headers)
                .header(HttpHeaders.ALLOW, e.supportedMethods!!.joinToString(", "))
                .body(problem)
        }
        return response
    }

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
