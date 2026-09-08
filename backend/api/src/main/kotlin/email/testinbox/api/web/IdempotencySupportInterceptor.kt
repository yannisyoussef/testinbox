package email.testinbox.api.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Refuses `Idempotency-Key` on any `/v1` route that does not honour it
 * (ADR-033 §1, TI-003 §24).
 *
 * Centralised rather than per controller, because the failure of forgetting is
 * silent and one-directional: a route that accepted and ignored the header
 * would tell a client, by saying nothing, that its retries are safe. Refusing
 * is louder and cheaper than the alternative.
 *
 * It runs after authentication, so an unauthenticated request still gets `401`
 * and the refusal never becomes a way to probe the route table.
 */
@Component
class IdempotencySupportInterceptor(
    private val objectMapper: tools.jackson.databind.ObjectMapper,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (request.getHeader(IdempotencyHeader.NAME) == null) return true
        if (IdempotentRoutes.supports(request.method, request.requestURI)) return true

        val problem =
            Problems.of(
                HttpStatus.BAD_REQUEST,
                "idempotency-not-supported",
                "Idempotency not supported",
                "${IdempotencyHeader.NAME} is not supported on this operation; it is honoured only where the " +
                    "contract declares it",
                request,
            )
        response.status = HttpStatus.BAD_REQUEST.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        // Serialised, never interpolated: the problem carries a caller-supplied
        // correlation id, and hand-built JSON would let it corrupt the body.
        objectMapper.writeValue(response.outputStream, problem)
        return false
    }
}

@Component
class IdempotencySupportWebConfig(
    private val interceptor: IdempotencySupportInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/v1/**")
    }
}
