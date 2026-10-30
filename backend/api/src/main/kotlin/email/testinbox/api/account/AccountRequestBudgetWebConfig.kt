package email.testinbox.api.account

import email.testinbox.api.web.Problems
import email.testinbox.application.usecase.ChargeAccountRequest
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.ObjectMapper

/** The resolved account metadata route is charged before its handler/body. Logout is always usable. */
@Configuration
class AccountRequestBudgetWebConfig(
    private val charge: ChargeAccountRequest,
    private val mapper: ObjectMapper,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry
            .addInterceptor(
                object : HandlerInterceptor {
                    override fun preHandle(
                        request: HttpServletRequest,
                        response: HttpServletResponse,
                        handler: Any,
                    ): Boolean {
                        val route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()
                        if (route !in setOf("/account/session", "/account/admission", "/account/waitlist")) return true
                        val decision = charge.execute(HumanSessionCookies.credential(request)?.value) ?: return true
                        if (decision.allowed) return true
                        response.status = 429
                        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
                        response.setHeader("Retry-After", decision.retryAfter.seconds.toString())
                        mapper.writeValue(
                            response.outputStream,
                            Problems.of(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "account-rate-limit-exceeded",
                                "Too many account requests",
                                null,
                                request,
                            ),
                        )
                        return false
                    }
                },
            ).order(-150)
    }
}
