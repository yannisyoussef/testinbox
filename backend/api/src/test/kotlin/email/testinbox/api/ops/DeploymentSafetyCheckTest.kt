package email.testinbox.api.ops

import email.testinbox.api.config.TestInboxProperties
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The startup guard has to be tested through Spring, not by calling the
 * validator: the validator is already covered by `DeploymentSafetyTest`, and
 * every interesting way this breaks is in the wiring.
 *
 * Concretely, this pins two things nothing else does. First, that a violation
 * actually **fails context startup** rather than being logged and ignored.
 * Second, and more subtly, the **property names**: if `testinbox.deployment.*`
 * or any key under it were misspelled, the whole block would silently bind to
 * defaults, `environment` would be null, and the guard would quietly turn
 * itself off — the exact silence it exists to remove. The
 * "correctly configured deployment starts" case fails if any name drifts.
 */
class DeploymentSafetyCheckTest {
    @Configuration
    @EnableConfigurationProperties(TestInboxProperties::class, DataSourceProperties::class)
    class UnderTest {
        @Bean
        fun deploymentSafetyCheck(
            properties: TestInboxProperties,
            dataSourceProperties: DataSourceProperties,
        ) = DeploymentSafetyCheck(properties, dataSourceProperties)
    }

    private val runner = ApplicationContextRunner().withUserConfiguration(UnderTest::class.java)

    /** The values a deployed node is supposed to be given. */
    private val deployed =
        arrayOf(
            "testinbox.deployment.environment=staging",
            "testinbox.deployment.public-base-url=https://api.staging.testinbox.email",
            "testinbox.deployment.proxy-read-timeout=120s",
            "testinbox.deployment.git-sha=abc123",
            "testinbox.deployment.image-digest=sha256:abc",
            "testinbox.mail-domain=staging.testinbox.email",
            "spring.datasource.url=jdbc:postgresql://db.staging.internal:5432/testinbox",
            "spring.datasource.username=testinbox_staging",
            "spring.datasource.password=S3EbcYQ0mSJEc0kL0iEsIeAgQdvvS7yl",
            "testinbox.storage.endpoint=https://objects.staging.internal",
            "testinbox.storage.access-key=AKIAEXAMPLESTAGINGKEY",
            "testinbox.storage.secret-key=0kL0iEsIeAgQdvvS7ylS3EbcYQ0mSJEc",
        )

    @Test
    fun `a correctly configured deployed node starts - and this pins every property name`() {
        runner.withPropertyValues(*deployed).run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(TestInboxProperties::class.java)
            // Bound, not defaulted: a renamed key would leave these null and
            // silently disable the guard rather than failing anything.
            properties.deployment.environment shouldBe "staging"
            properties.deployment.proxyReadTimeout?.toSeconds() shouldBe 120L
            properties.deployment.publicBaseUrl shouldBe "https://api.staging.testinbox.email"
            properties.deployment.gitSha shouldBe "abc123"
        }
    }

    @Test
    fun `local-development credentials fail startup rather than being logged`() {
        runner
            .withPropertyValues(
                *deployed,
                "spring.datasource.password=testinbox",
                "testinbox.storage.endpoint=http://localhost:9000",
            ).run { context ->
                assertThat(context).hasFailed()
                val message = context.startupFailure!!.stackTraceToString()
                message shouldContain "spring.datasource.password"
                message shouldContain "testinbox.storage.endpoint"
            }
    }

    @Test
    fun `an ingress that would cut a long poll short fails startup`() {
        runner
            .withPropertyValues(*deployed, "testinbox.wait-window-cap=60s", "testinbox.deployment.proxy-read-timeout=60s")
            .run { context ->
                assertThat(context).hasFailed()
                context.startupFailure!!.stackTraceToString() shouldContain "testinbox.proxy-read-timeout"
            }
    }

    @Test
    fun `the guard is inert when the process is not deployed`() {
        // Local development runs on exactly the values rejected above.
        runner
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://localhost:5432/testinbox",
                "spring.datasource.password=testinbox",
            ).run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `the startup failure never carries a secret value`() {
        runner
            .withPropertyValues(*deployed, "spring.datasource.password=testinbox123", "testinbox.bootstrap.api-key=tk_e2e_acceptance_key")
            .run { context ->
                assertThat(context).hasFailed()
                val message = context.startupFailure!!.stackTraceToString()
                message shouldContain "testinbox.bootstrap.api-key"
                message shouldNotContain "tk_e2e_acceptance_key"
                message shouldNotContain "testinbox123"
            }
    }

    @Test
    fun `disabled limits fail startup on a deployed node (ADR-027)`() {
        runner.withPropertyValues(*deployed, "testinbox.limits.enabled=false").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain "testinbox.limits.enabled"
        }
    }
}
