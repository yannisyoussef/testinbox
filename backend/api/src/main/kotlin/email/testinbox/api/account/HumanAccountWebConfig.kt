package email.testinbox.api.account

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class HumanAccountWebConfig(
    private val boundary: HumanAccountBoundaryFilter,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(
            object : HandlerInterceptor {
                override fun preHandle(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    handler: Any,
                ): Boolean {
                    // Use the resolved controller too, not just raw URL text:
                    // encoded/matrix paths must obey the same disabled/origin gate.
                    val isAccountHandler =
                        handler is HandlerMethod &&
                            handler.beanType.packageName.startsWith("email.testinbox.api.account")
                    return !isAccountHandler || boundary.allow(request, response)
                }
            },
        )
    }
}
