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
            databasePassword = "fixture-not-a-real-db-password--1",
            storageEndpoint = "https://objects.staging.internal",
            storageAccessKey = "fixture-not-a-real-s3-access-key",
            storageSecretKey = "fixture-not-a-real-s3-secret----1",
            publicBaseUrl = "https://api.staging.testinbox.email",
            // Long enough and varied enough to pass the entropy floor, while
            // reading unmistakably as a fixture: a base64-shaped literal here
            // satisfies the rule but trips the secret scanner, and allowlisting
            // the file would blunt a gate over a test constant.
            bootstrapApiKey = "not-a-real-secret-ABCDEFGHIJKLMNOP-0123456789",
            waitWindowCap = Duration.ofSeconds(60),
            proxyReadTimeout = Duration.ofSeconds(120),
            edgeRequestCeiling = null,
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
        // A distinct set, not a list: "short" is both too short and too
        // repetitive, and reporting every problem at once is the point — an
        // operator should not have to fix them one restart at a time.
        settingsOf(DeploymentSafety.validate(safe.copy(bootstrapApiKey = "short"))).toSet() shouldBe
            setOf("testinbox.bootstrap.api-key")
        settingsOf(DeploymentSafety.validate(safe.copy(bootstrapApiKey = "tk_e2e_" + "x".repeat(40)))).toSet() shouldBe
            setOf("testinbox.bootstrap.api-key")
    }

    @Test
    fun `a long but low-entropy bootstrap key is refused`() {
        // ADR-032 §2's case for SHA-256 over a password KDF rests on the secret
        // being high-entropy. Managed keys get that by construction; the
        // bootstrap credential gets it only if the operator supplies it, and it
        // is the one row with unconditional `api-keys:manage`.
        settingsOf(DeploymentSafety.validate(safe.copy(bootstrapApiKey = "a".repeat(60)))).toSet() shouldBe
            setOf("testinbox.bootstrap.api-key")
        settingsOf(DeploymentSafety.validate(safe.copy(bootstrapApiKey = "correcthorse".repeat(5)))).toSet() shouldBe
            setOf("testinbox.bootstrap.api-key")
    }

    @Test
    fun `a bootstrap key using the reserved credential prefix is refused`() {
        // It would be routed to the managed-credential path, which resolves
        // only `kind = 'MANAGED'` rows — so the break-glass credential would be
        // silently dead, discovered during the outage it exists to resolve.
        settingsOf(
            DeploymentSafety.validate(
                safe.copy(bootstrapApiKey = "ti_staging_bootstrap_Qm9vdHN0cmFwU2VjcmV0RXhhbXBsZTQ3Wg"),
            ),
        ).toSet() shouldBe setOf("testinbox.bootstrap.api-key")
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
                // A JDBC URL legitimately carries inline credentials, so it is
                // as much a secret as the password field beside it.
                databaseUrl = "jdbc:postgresql://svc:fixture-inline-url-credential@localhost:5432/testinbox",
                databasePassword = "testinbox",
                storageSecretKey = "testinbox123",
                bootstrapApiKey = "tk_e2e_acceptance_key",
            )
        val message = DeploymentSafety.describe(DeploymentSafety.validate(leaky))
        message shouldContain "spring.datasource.url"
        message shouldContain "spring.datasource.password"
        message shouldContain "testinbox.bootstrap.api-key"
        message shouldNotContain "testinbox123"
        message shouldNotContain "tk_e2e_acceptance_key"
        message shouldNotContain "fixture-inline-url-credential"
    }

    @Test
    fun `a wait window the environment's ingress cannot hold is refused`() {
        // ADR-030: Cloudflare cuts a request at ~100s on the deployed path, and
        // no application setting raises that. Without this check, a 90s window
        // with a declared 120s proxy timeout passes every validation and then
        // returns 524 for every full-window wait in production.
        // Note the declared proxy timeout: on the deployed path it must be the
        // real 100s ceiling, not the 120s the nginx reference topology uses.
        // Copying that number across is exactly the mistake this catches.
        val cloudflare =
            safe.copy(edgeRequestCeiling = Duration.ofSeconds(100), proxyReadTimeout = Duration.ofSeconds(100))

        DeploymentSafety.validate(cloudflare.copy(waitWindowCap = Duration.ofSeconds(60))).shouldBeEmpty()

        val tooWide =
            DeploymentSafety.validate(
                cloudflare.copy(waitWindowCap = Duration.ofSeconds(90), proxyReadTimeout = Duration.ofSeconds(120)),
            )
        settingsOf(tooWide) shouldBe listOf("testinbox.wait-window-cap", "testinbox.proxy-read-timeout")
        tooWide.first().problem shouldContain "cuts a request at 100s"
    }

    @Test
    fun `70s is the exact boundary the ceiling allows`() {
        // 70s + the 30s margin is exactly 100s. One second more is not.
        val cloudflare =
            safe.copy(edgeRequestCeiling = Duration.ofSeconds(100), proxyReadTimeout = Duration.ofSeconds(100))
        DeploymentSafety
            .validate(cloudflare.copy(waitWindowCap = Duration.ofSeconds(70), proxyReadTimeout = Duration.ofSeconds(100)))
            .shouldBeEmpty()
        settingsOf(
            DeploymentSafety.validate(
                cloudflare.copy(waitWindowCap = Duration.ofSeconds(71), proxyReadTimeout = Duration.ofSeconds(101)),
            ),
        ) shouldBe listOf("testinbox.wait-window-cap", "testinbox.proxy-read-timeout")
    }

    @Test
    fun `an environment with no ingress ceiling is unconstrained by it`() {
        // The provider-neutral nginx topology owns its own edge.
        DeploymentSafety
            .validate(
                safe.copy(edgeRequestCeiling = null, waitWindowCap = Duration.ofSeconds(300), proxyReadTimeout = Duration.ofSeconds(400)),
            ).shouldBeEmpty()
    }
}
