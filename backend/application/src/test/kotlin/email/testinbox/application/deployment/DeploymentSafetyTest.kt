package email.testinbox.application.deployment

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Duration

class DeploymentSafetyTest {
    private val safe =
        DeploymentSettings(
            environment = "staging",
            mailDomain = "staging.testinbox.email",
            databaseUrl = "jdbc:postgresql://db.staging.internal:5432/testinbox",
            databaseUsername = "testinbox_staging",
            databasePassword = "S3EbcYQ0mSJEc0kL0iEsIeAgQdvvS7yl",
            storageEndpoint = "https://objects.staging.internal",
            storageAccessKey = "AKIAEXAMPLESTAGINGKEY",
            storageSecretKey = "0kL0iEsIeAgQdvvS7ylS3EbcYQ0mSJEc",
            publicBaseUrl = "https://api.staging.testinbox.email",
            bootstrapApiKey = "tk_stg_" + "x".repeat(40),
            waitWindowCap = Duration.ofSeconds(60),
            proxyReadTimeout = Duration.ofSeconds(120),
            limitsEnabled = true,
        )

    private fun settingsOf(violations: List<DeploymentViolation>) = violations.map { it.setting }

    @Test
    fun `a fully configured deployment has no violations`() {
        DeploymentSafety.validate(safe).shouldBeEmpty()
    }

    @Test
    fun `the local development defaults are all rejected at once`() {
        val local =
            safe.copy(
                mailDomain = "testinbox.local",
                databaseUrl = "jdbc:postgresql://localhost:5432/testinbox",
                databasePassword = "testinbox",
                storageEndpoint = "http://localhost:9000",
                storageAccessKey = "testinbox",
                storageSecretKey = "testinbox123",
            )
        settingsOf(DeploymentSafety.validate(local)) shouldBe
            listOf(
                "testinbox.mail-domain",
                "spring.datasource.url",
                "spring.datasource.password",
                "testinbox.storage.endpoint",
                "testinbox.storage.access-key",
                "testinbox.storage.secret-key",
            )
    }

    @Test
    fun `a loopback database or object store is refused whichever loopback spelling is used`() {
        for (host in listOf("localhost", "127.0.0.1", "0.0.0.0", "[::1]")) {
            val violations = DeploymentSafety.validate(safe.copy(databaseUrl = "jdbc:postgresql://$host:5432/testinbox"))
            settingsOf(violations) shouldBe listOf("spring.datasource.url")
        }
    }

    @Test
    fun `a hostname that merely starts with a loopback name is a real host`() {
        val remote = safe.copy(databaseUrl = "jdbc:postgresql://localhost.db.example.com:5432/testinbox")
        DeploymentSafety.validate(remote).shouldBeEmpty()
    }

    @Test
    fun `a plaintext public base URL is refused`() {
        val violations = DeploymentSafety.validate(safe.copy(publicBaseUrl = "http://api.staging.testinbox.email"))
        settingsOf(violations) shouldBe listOf("testinbox.public-base-url")
        violations.single().problem shouldContain "HTTPS"
    }

    @Test
    fun `an absent public base URL is allowed - not every deployable is publicly reachable`() {
        DeploymentSafety.validate(safe.copy(publicBaseUrl = null)).shouldBeEmpty()
    }

    @Test
    fun `a short or fixture bootstrap key is refused`() {
        settingsOf(DeploymentSafety.validate(safe.copy(bootstrapApiKey = "short"))) shouldBe
            listOf("testinbox.bootstrap.api-key")
        settingsOf(DeploymentSafety.validate(safe.copy(bootstrapApiKey = "tk_e2e_" + "x".repeat(40)))) shouldBe
            listOf("testinbox.bootstrap.api-key")
    }

    @Test
    fun `no bootstrap key at all is allowed`() {
        DeploymentSafety.validate(safe.copy(bootstrapApiKey = null)).shouldBeEmpty()
        DeploymentSafety.validate(safe.copy(bootstrapApiKey = "  ")).shouldBeEmpty()
    }

    @Test
    fun `a proxy timeout at or below the wait window is refused`() {
        // 60s window + 30s margin: 89s is short, 90s is exactly enough.
        settingsOf(DeploymentSafety.validate(safe.copy(proxyReadTimeout = Duration.ofSeconds(89)))) shouldBe
            listOf("testinbox.proxy-read-timeout")
        DeploymentSafety.validate(safe.copy(proxyReadTimeout = Duration.ofSeconds(90))).shouldBeEmpty()
    }

    @Test
    fun `an undeclared proxy timeout is refused because nothing then checks the relationship`() {
        settingsOf(DeploymentSafety.validate(safe.copy(proxyReadTimeout = null))) shouldBe
            listOf("testinbox.proxy-read-timeout")
    }

    @Test
    fun `raising the wait window without raising the proxy timeout is refused`() {
        val violations =
            DeploymentSafety.validate(
                safe.copy(waitWindowCap = Duration.ofSeconds(120), proxyReadTimeout = Duration.ofSeconds(120)),
            )
        settingsOf(violations) shouldBe listOf("testinbox.proxy-read-timeout")
        violations.single().problem shouldContain "at least 150s"
    }

    @Test
    fun `disabled limits are refused in a deployed environment (ADR-027)`() {
        settingsOf(DeploymentSafety.validate(safe.copy(limitsEnabled = false))) shouldBe listOf("testinbox.limits.enabled")
    }

    @Test
    fun `the rendered failure names settings and never echoes a secret value`() {
        val leaky =
            safe.copy(
                databasePassword = "testinbox",
                storageSecretKey = "testinbox123",
                bootstrapApiKey = "tk_e2e_acceptance_key",
            )
        val message = DeploymentSafety.describe(DeploymentSafety.validate(leaky))
        message shouldContain "spring.datasource.password"
        message shouldContain "testinbox.bootstrap.api-key"
        message shouldNotContain "testinbox123"
        message shouldNotContain "tk_e2e_acceptance_key"
    }
}
