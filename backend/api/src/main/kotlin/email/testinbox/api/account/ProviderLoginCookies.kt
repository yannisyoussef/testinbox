package email.testinbox.api.account

import email.testinbox.application.identity.SessionCredential
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseCookie
import java.time.Duration

object ProviderLoginCookies {
    const val NAME: String = "__Host-ti_oauth"

    fun ambiguous(request: HttpServletRequest): Boolean = request.cookies.orEmpty().count { it.name == NAME } > 1

    fun browser(request: HttpServletRequest): SessionCredential? =
        request.cookies
            .orEmpty()
            .filter { it.name == NAME }
            .singleOrNull()
            ?.value
            ?.let(SessionCredential::parse)

    fun set(browser: SessionCredential): String = cookie(browser.value, Duration.ofMinutes(10))

    fun clear(): String = cookie("", Duration.ZERO)

    private fun cookie(
        value: String,
        lifetime: Duration,
    ): String =
        ResponseCookie
            .from(NAME, value)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(lifetime)
            .build()
            .toString()
}
