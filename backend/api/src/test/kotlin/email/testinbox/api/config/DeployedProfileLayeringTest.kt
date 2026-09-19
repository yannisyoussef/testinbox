package email.testinbox.api.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Loads the REAL configuration files through Spring's config-data machinery
 * and asserts what each profile group resolves to. Nothing else does: the
 * guard tests inject flat properties, and the architecture test parses text.
 *
 * The trap this pins: `spring.profiles.active=production` expands to
 * `[production, deployed]` and the LATER profile wins, so a separate
 * `application-production.yaml` is silently shadowed by the deployed layer —
 * three of its four keys never took effect, and only `DeploymentSafety`
 * stood between that and a production node on the staging domain. The
 * overrides therefore live in a later document of the same file, which wins
 * regardless of group order, and this test is what proves they do.
 */
class DeployedProfileLayeringTest {
    private fun runner(vararg properties: String) =
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues(*properties)

    @Test
    fun `production resolves the fixed overrides over the shared deployed layer`() {
        // No TESTINBOX_MAIL_DOMAIN and no TESTINBOX_S3_CREATE_BUCKET on purpose:
        // if the deployed document won, mail-domain would be an unresolvable
        // placeholder and create-bucket would be "true".
        runner("spring.profiles.active=production").run { context ->
            val env = context.environment
            env.activeProfiles.toList() shouldBe listOf("production", "deployed")
            env.getProperty("testinbox.mail-domain") shouldBe "inbox.testinbox.email"
            env.getProperty("testinbox.storage.create-bucket") shouldBe "false"
            env.getProperty("testinbox.deployment.require-database-session-timeout") shouldBe "true"
            // Shared settings still come from the deployed layer.
            env.getProperty("spring.flyway.enabled") shouldBe "false"
            env.getProperty("management.server.port") shouldBe "9090"
        }
    }

    @Test
    fun `staging resolves exactly the shared deployed layer, with no production override applied`() {
        runner("spring.profiles.active=staging", "TESTINBOX_MAIL_DOMAIN=staging.testinbox.email").run { context ->
            val env = context.environment
            env.activeProfiles.toList() shouldBe listOf("staging", "deployed")
            env.getProperty("testinbox.mail-domain") shouldBe "staging.testinbox.email"
            env.getProperty("testinbox.storage.create-bucket") shouldBe "true"
            env.getProperty("testinbox.deployment.require-database-session-timeout") shouldBe null
            env.getProperty("spring.flyway.enabled") shouldBe "false"
        }
    }

    @Test
    fun `local development activates no deployed layer at all`() {
        runner().run { context ->
            val env = context.environment
            env.activeProfiles.toList() shouldBe emptyList()
            env.getProperty("spring.flyway.enabled") shouldBe "true"
            env.getProperty("testinbox.mail-domain") shouldBe "testinbox.local"
        }
    }
}
