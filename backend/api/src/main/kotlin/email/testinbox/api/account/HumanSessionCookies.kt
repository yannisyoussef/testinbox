package email.testinbox.api.account

import email.testinbox.application.identity.SessionCredential
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseCookie
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Browser credentials are never returned in JSON or readable by JavaScript. */
object HumanSessionCookies {
    const val NAME: String = "__Host-ti_session"
    const val CSRF_HEADER: String = "X-CSRF-Token"

    fun credential(request: HttpServletRequest): SessionCredential? {
        val matching = request.cookies.orEmpty().filter { it.name == NAME }
        // Ambiguous duplicates must not select different principals across parsers.
        return matching.singleOrNull()?.value?.let(SessionCredential::parse)
    }

    fun set(
        credential: SessionCredential,
        lifetime: Duration,
    ): String = cookie(credential.value, lifetime)

    fun clear(): String = cookie("", Duration.ZERO)

    /** A domain-separated CSRF token, bound to this 256-bit HttpOnly secret. */
    fun csrf(credential: SessionCredential): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(credential.value.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal("testinbox-human-session-csrf-v1".toByteArray(Charsets.US_ASCII)))
    }

    fun csrfMatches(
        request: HttpServletRequest,
        credential: SessionCredential,
    ): Boolean {
        val presented = request.getHeaders(CSRF_HEADER).toList().singleOrNull() ?: return false
        if (!presented.matches(Regex("[A-Za-z0-9_-]{43}"))) return false
        return MessageDigest.isEqual(csrf(credential).toByteArray(Charsets.US_ASCII), presented.toByteArray(Charsets.US_ASCII))
    }

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
