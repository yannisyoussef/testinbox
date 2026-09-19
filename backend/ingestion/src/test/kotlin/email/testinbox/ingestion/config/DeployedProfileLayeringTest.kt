package email.testinbox.ingestion.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** The gateway's copy of the API's layering proof; the files are separate, so the proof is too. */
class DeployedProfileLayeringTest {
    private fun runner(vararg properties: String) =
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues(*properties)

    @Test
    fun `production resolves the fixed overrides over the shared deployed layer`() {
        runner("spring.profiles.active=production").run { context ->
            val env = context.environment
            env.activeProfiles.toList() shouldBe listOf("production", "deployed")
            env.getProperty("testinbox.mail-domain") shouldBe "inbox.testinbox.email"
            env.getProperty("testinbox.storage.create-bucket") shouldBe "false"
            env.getProperty("spring.flyway.enabled") shouldBe "false"
            env.getProperty("management.server.port") shouldBe "9091"
        }
    }

    @Test
    fun `staging resolves exactly the shared deployed layer`() {
        runner("spring.profiles.active=staging", "TESTINBOX_MAIL_DOMAIN=staging.testinbox.email").run { context ->
            val env = context.environment
            env.activeProfiles.toList() shouldBe listOf("staging", "deployed")
            env.getProperty("testinbox.mail-domain") shouldBe "staging.testinbox.email"
            env.getProperty("testinbox.storage.create-bucket") shouldBe "true"
            env.getProperty("spring.flyway.enabled") shouldBe "false"
        }
    }
}
