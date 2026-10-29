package email.testinbox.api.account

import email.testinbox.application.identity.SessionCredential
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration

class HumanSessionCookiesTest {
    @Test
    fun `set and clear retain host only secure HttpOnly SameSite attributes`() {
        val credential = SessionCredential.generate()
        val set = HumanSessionCookies.set(credential, Duration.ofDays(7))
        set.contains(credential.value) shouldBe true
        listOf(set, HumanSessionCookies.clear()).forEach { value ->
            listOf("Path=/", "Secure", "HttpOnly", "SameSite=Lax").forEach { value.contains(it) shouldBe true }
            value.contains("Domain", ignoreCase = true) shouldBe false
        }
    }

    @Test
    fun `CSRF is session bound distinct from bearer and verifier and rejects duplicated headers`() {
        val credential = SessionCredential.generate()
        val csrf = HumanSessionCookies.csrf(credential)
        (csrf == credential.value) shouldBe false
        (csrf == credential.verifier()) shouldBe false
        (csrf == HumanSessionCookies.csrf(SessionCredential.generate())) shouldBe false
        val request = MockHttpServletRequest()
        request.addHeader(HumanSessionCookies.CSRF_HEADER, csrf)
        HumanSessionCookies.csrfMatches(request, credential) shouldBe true
        request.addHeader(HumanSessionCookies.CSRF_HEADER, csrf)
        HumanSessionCookies.csrfMatches(request, credential) shouldBe false
    }

    @Test
    fun `duplicate cookies and malformed credentials fail closed`() {
        val request = MockHttpServletRequest()
        val credential = SessionCredential.generate()
        request.setCookies(Cookie(HumanSessionCookies.NAME, credential.value))
        HumanSessionCookies.credential(request)?.value shouldBe credential.value
        request.setCookies(Cookie(HumanSessionCookies.NAME, credential.value), Cookie(HumanSessionCookies.NAME, credential.value))
        HumanSessionCookies.credential(request) shouldBe null
        request.setCookies(Cookie(HumanSessionCookies.NAME, "invalid"))
        HumanSessionCookies.credential(request) shouldBe null
    }

    @Test
    fun `enabled accounts require one canonical HTTPS origin without credentials path or query`() {
        HumanAccountProperties().enabled shouldBe false
        HumanAccountProperties(enabled = true, publicOrigin = "https://console.example.invalid")
        listOf(
            "",
            "http://console.example.invalid",
            "https://user@console.example.invalid",
            "https://console.example.invalid/",
            "https://console.example.invalid?foo=1",
            "https://console.example.invalid#fragment",
            "https://Console.example.invalid",
            "https://console.example.invalid:443",
        ).forEach { origin ->
            HumanAccountProperties(enabled = true, publicOrigin = origin).canonicalOrigin shouldBe null
        }
    }
}
