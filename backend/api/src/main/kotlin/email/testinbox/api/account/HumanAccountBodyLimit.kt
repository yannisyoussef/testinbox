package email.testinbox.api.account

import email.testinbox.api.auth.ProductRoutes
import email.testinbox.api.web.Problems
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import java.io.ByteArrayInputStream
import java.lang.reflect.Type

class HumanAccountBodyTooLarge : RuntimeException("account request exceeds its body ceiling")

/** Runs before JSON allocation, including on chunked requests and normalized paths. */
@ControllerAdvice
class HumanAccountBodyLimit : RequestBodyAdviceAdapter() {
    override fun supports(
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean =
        parameter.containingClass.packageName.startsWith("email.testinbox.api.account") ||
            ((RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.let(ProductRoutes::isHuman) == true)

    override fun beforeBodyRead(
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): HttpInputMessage {
        val bytes = inputMessage.body.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw HumanAccountBodyTooLarge()
        return object : HttpInputMessage {
            override fun getHeaders() = inputMessage.headers

            override fun getBody() = ByteArrayInputStream(bytes)
        }
    }

    companion object {
        const val MAX_BYTES: Int = 4096
    }
}

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class HumanAccountBodyErrors {
    @ExceptionHandler(HumanAccountBodyTooLarge::class)
    fun tooLarge(request: HttpServletRequest) =
        Problems.respond(Problems.of(HttpStatus.valueOf(413), "request-too-large", "Request too large", null, request))
}
