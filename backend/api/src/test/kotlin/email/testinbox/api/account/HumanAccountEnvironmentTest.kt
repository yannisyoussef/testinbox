package email.testinbox.api.account

import email.testinbox.application.identity.EmailLoginCrypto
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource
import java.util.Base64

/** Read the real shipped YAML; a missing/wrong env mapping must fail before a host window. */
class HumanAccountEnvironmentTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HumanAccountProperties::class, EmailLoginProperties::class, ProviderAccountProperties::class)
    class PropertiesOnly

    private fun context(values: Map<String, Any> = emptyMap()): ApplicationContextRunner =
        ApplicationContextRunner().withUserConfiguration(PropertiesOnly::class.java).withInitializer { context ->
            val sources = context.environment.propertySources
            sources.addFirst(SystemEnvironmentPropertySource("account-test-env", values))
            YamlPropertySourceLoader().load("shipped-api-yaml", ClassPathResource("application.yaml")).forEach(sources::addLast)
        }

    @Test
    fun `shipped defaults leave human accounts and email disabled without credentials`() {
        context().run { context ->
            context.getBean(HumanAccountProperties::class.java).enabled shouldBe false
            val email = context.getBean(EmailLoginProperties::class.java)
            email.enabled shouldBe false
            email.verificationKeyBase64 shouldBe ""
            email.smtp.username shouldBe ""
            email.smtp.password shouldBe ""
        }
    }

    @Test
    fun `documented environment names bind origin verifier and API only SMTP settings`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        context(
            mapOf(
                "TESTINBOX_ACCOUNTS_ENABLED" to "true",
                "TESTINBOX_ACCOUNTS_PUBLIC_ORIGIN" to "https://console.example.invalid",
                "TESTINBOX_ACCOUNTS_EMAIL_ENABLED" to "true",
                "TESTINBOX_ACCOUNTS_EMAIL_VERIFICATION_KEY_BASE64" to key,
                "TESTINBOX_ACCOUNTS_EMAIL_SMTP_HOST" to "relay.example.invalid",
                "TESTINBOX_ACCOUNTS_EMAIL_SMTP_PORT" to "587",
                "TESTINBOX_ACCOUNTS_EMAIL_SMTP_USERNAME" to "fixture",
                "TESTINBOX_ACCOUNTS_EMAIL_SMTP_PASSWORD" to "fixture",
                "TESTINBOX_ACCOUNTS_EMAIL_SMTP_FROM" to "login@example.invalid",
            ),
        ).run { context ->
            context.getBean(HumanAccountProperties::class.java).canonicalOrigin shouldBe "https://console.example.invalid"
            val email = context.getBean(EmailLoginProperties::class.java)
            email.enabled shouldBe true
            EmailLoginCrypto.fromBase64(email.verificationKeyBase64)
            email.smtp.host shouldBe "relay.example.invalid"
            email.smtp.username shouldBe "fixture"
            email.smtp.password shouldBe "fixture"
            email.smtp.from shouldBe "login@example.invalid"
            SmtpLoginDelivery.validate(email.smtp)
        }
    }

    @Test
    fun `documented OAuth variables bind to the shipped disabled defaults and separate API secrets`() {
        context().run { context ->
            val oauth = context.getBean(ProviderAccountProperties::class.java)
            oauth.stateKeyBase64 shouldBe ""
            oauth.google.enabled shouldBe false
            oauth.github.enabled shouldBe false
        }
        context(
            mapOf(
                "TESTINBOX_ACCOUNTS_OAUTH_STATE_KEY_BASE64" to "fixture-state-key",
                "TESTINBOX_ACCOUNTS_OAUTH_GOOGLE_ENABLED" to "true",
                "TESTINBOX_ACCOUNTS_OAUTH_GOOGLE_CLIENT_ID" to "google-id",
                "TESTINBOX_ACCOUNTS_OAUTH_GOOGLE_CLIENT_SECRET" to "google-credential",
                "TESTINBOX_ACCOUNTS_OAUTH_GITHUB_ENABLED" to "true",
                "TESTINBOX_ACCOUNTS_OAUTH_GITHUB_CLIENT_ID" to "github-id",
                "TESTINBOX_ACCOUNTS_OAUTH_GITHUB_CLIENT_SECRET" to "github-credential",
            ),
        ).run { context ->
            val oauth = context.getBean(ProviderAccountProperties::class.java)
            oauth.stateKeyBase64 shouldBe "fixture-state-key"
            oauth.google.configured() shouldBe true
            oauth.google.clientId shouldBe "google-id"
            oauth.google.clientSecret shouldBe "google-credential"
            oauth.github.configured() shouldBe true
            oauth.github.clientId shouldBe "github-id"
            oauth.github.clientSecret shouldBe "github-credential"
            oauth.toString().contains("fixture-state-key") shouldBe false
            oauth.google.toString().contains("google-credential") shouldBe false
        }
    }
}
