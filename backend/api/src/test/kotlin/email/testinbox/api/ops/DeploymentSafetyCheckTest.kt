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
import org.springframework.core.env.Environment

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
            environment: Environment,
        ) = DeploymentSafetyCheck(properties, dataSourceProperties, environment)
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
            "spring.datasource.password=fixture-not-a-real-db-password--1",
            "testinbox.storage.endpoint=https://objects.staging.internal",
            "testinbox.storage.access-key=fixture-not-a-real-s3-access-key",
            "testinbox.storage.secret-key=fixture-not-a-real-s3-secret----1",
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

    /** A production node, as the production document of `application-deployed.yaml` plus a correct environment would configure it. */
    private val production =
        arrayOf(
            "spring.profiles.active=production",
            "testinbox.deployment.environment=production",
            "testinbox.deployment.public-base-url=https://api.testinbox.email",
            "testinbox.deployment.proxy-read-timeout=100s",
            "testinbox.deployment.edge-request-ceiling=100s",
            "testinbox.deployment.git-sha=abc123",
            "testinbox.mail-domain=inbox.testinbox.email",
            "testinbox.storage.create-bucket=false",
            "testinbox.deployment.require-database-session-timeout=true",
            "spring.datasource.url=jdbc:postgresql://db.prod.internal:5432/testinbox",
            "spring.datasource.username=testinbox_prod",
            "spring.datasource.password=fixture-not-a-real-db-password--1",
            "testinbox.storage.endpoint=https://objects.prod.internal",
            "testinbox.storage.access-key=fixture-not-a-real-s3-access-key",
            "testinbox.storage.secret-key=fixture-not-a-real-s3-secret----1",
        )

    @Test
    fun `a correctly configured production node starts - and this pins the profile and bucket wiring`() {
        runner.withPropertyValues(*production).run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(TestInboxProperties::class.java).storage.createBucket shouldBe false
        }
    }

    @Test
    fun `staging configuration labelled as production fails startup (ADR-034)`() {
        // The active profile is what the guard reads; a misspelt or absent
        // profile would leave every staging default in force.
        runner.withPropertyValues(*production, "spring.profiles.active=staging").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain "spring.profiles.active"
        }
    }

    @Test
    fun `an environment variable cannot re-enable bucket creation in production`() {
        // The profile document sets false; the point is that an override does
        // not win silently — it fails the node instead.
        runner.withPropertyValues(*production, "testinbox.storage.create-bucket=true").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain "testinbox.storage.create-bucket"
        }
    }

    @Test
    fun `production on the staging mail domain fails startup`() {
        runner.withPropertyValues(*production, "testinbox.mail-domain=staging.testinbox.email").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain "testinbox.mail-domain"
        }
    }

    @Test
    fun `production without a declared ingress ceiling fails startup`() {
        runner.withPropertyValues(*production, "testinbox.deployment.edge-request-ceiling=").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain "testinbox.deployment.edge-request-ceiling"
        }
    }

    @Test
    fun `a deployed profile with a blank environment name is refused, not silently unguarded`() {
        // With TESTINBOX_ENVIRONMENT set but empty, every check used to be skipped.
        runner.withPropertyValues("spring.profiles.active=production", "testinbox.deployment.environment=").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain "testinbox.deployment.environment is not set"
        }
    }

    @Test
    fun `an environment variable cannot turn production's session enforcement into reporting`() {
        runner
            .withPropertyValues(*production, "testinbox.deployment.require-database-session-timeout=false")
            .run { context ->
                assertThat(context).hasFailed()
                context.startupFailure!!.stackTraceToString() shouldContain "require-database-session-timeout"
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
