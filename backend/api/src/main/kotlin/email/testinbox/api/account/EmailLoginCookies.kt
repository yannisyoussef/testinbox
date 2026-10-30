package email.testinbox.api.account

import email.testinbox.application.identity.SessionCredential
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseCookie
import java.time.Duration

object EmailLoginCookies {
    const val NAME: String = "__Host-ti_login"

    fun ambiguous(request: HttpServletRequest): Boolean = request.cookies.orEmpty().count { it.name == NAME } > 1

    fun browser(request: HttpServletRequest): SessionCredential? =
        request.cookies
            .orEmpty()
            .filter { it.name == NAME }
            .singleOrNull()
            ?.value
            ?.let(SessionCredential::parse)

    fun set(
        browser: SessionCredential,
        lifetime: Duration,
    ): String = cookie(browser.value, lifetime)

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
