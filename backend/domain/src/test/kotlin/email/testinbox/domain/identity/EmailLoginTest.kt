package email.testinbox.domain.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EmailLoginTest {
    @Test
    fun `contact normalization preserves plus and dot distinctions`() {
        ContactEmail.parse(" Person.Name+beta@Example.COM ")?.value shouldBe "person.name+beta@example.com"
        (ContactEmail.parse("person.name@gmail.com") == ContactEmail.parse("personname@gmail.com")) shouldBe false
        (ContactEmail.parse("person+beta@gmail.com") == ContactEmail.parse("person@gmail.com")) shouldBe false
    }

    @Test
    fun `invalid header injection quoted non ASCII and malformed contacts are rejected`() {
        listOf(
            "",
            "user",
            "@example.com",
            "user@",
            "user@@example.com",
            "user@example..com",
            ".user@example.com",
            "user..name@example.com",
            "user@-example.com",
            "user@example-.com",
            "\"user\"@example.com",
            "usér@example.com",
            "user@example.com\r\nBcc: victim@example.com",
            "a".repeat(65) + "@example.com",
            "user@" + "a".repeat(64) + ".com",
        ).forEach { ContactEmail.parse(it) shouldBe null }
    }

    @Test
    fun `immutable provider identities require their real issuer and stable subject form`() {
        val email = checkNotNull(ContactEmail.parse("user@example.invalid"))
        shouldThrow<IllegalArgumentException> { VerifiedLoginIdentity(LoginProvider.EMAIL, "evil", email.value, email) }
        shouldThrow<IllegalArgumentException> { VerifiedLoginIdentity(LoginProvider.EMAIL, "testinbox", "another@example.invalid", email) }
        shouldThrow<IllegalArgumentException> { VerifiedLoginIdentity(LoginProvider.GOOGLE, "https://evil.invalid", "123", email) }
        shouldThrow<IllegalArgumentException> {
            VerifiedLoginIdentity(
                LoginProvider.GITHUB,
                "https://github.com",
                "mutable-login-name",
                email,
            )
        }
        shouldThrow<IllegalArgumentException> { VerifiedLoginIdentity(LoginProvider.GOOGLE, "https://accounts.google.com", "", email) }
    }
}
