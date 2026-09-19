package email.testinbox.ingestion.ops

import email.testinbox.ingestion.config.IngestionProperties
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * The gateway's own guard. Tested separately from the API's rather than
 * assumed equivalent: they are separate processes with separate environments,
 * and the failure this catches — a gateway left pointing at a local object
 * store while the API points at staging — is invisible from the API side.
 *
 * As on the API side, the "correctly configured node starts" case is what pins
 * the `testinbox.deployment.*` property names: misspell one and the block
 * binds to defaults, `environment` is null, and the guard silently disables
 * itself.
 */
class IngestionDeploymentSafetyCheckTest {
    @Configuration
    @EnableConfigurationProperties(IngestionProperties::class, DataSourceProperties::class)
    class UnderTest {
        @Bean
        fun ingestionDeploymentSafetyCheck(
            properties: IngestionProperties,
            dataSourceProperties: DataSourceProperties,
            environment: Environment,
        ) = IngestionDeploymentSafetyCheck(properties, dataSourceProperties, environment)
    }

    private val runner = ApplicationContextRunner().withUserConfiguration(UnderTest::class.java)

    private val deployed =
        arrayOf(
            "testinbox.deployment.environment=staging",
            "testinbox.deployment.proxy-read-timeout=120s",
            "testinbox.deployment.git-sha=abc123",
            "testinbox.mail-domain=staging.testinbox.email",
            "spring.datasource.url=jdbc:postgresql://db.staging.internal:5432/testinbox",
            "spring.datasource.username=testinbox_staging",
            "spring.datasource.password=fixture-not-a-real-db-password--1",
            "testinbox.storage.endpoint=https://objects.staging.internal",
            "testinbox.storage.access-key=fixture-not-a-real-s3-access-key",
            "testinbox.storage.secret-key=fixture-not-a-real-s3-secret----1",
        )

    @Test
    fun `a correctly configured gateway starts - and this pins every property name`() {
        runner.withPropertyValues(*deployed).run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(IngestionProperties::class.java)
            properties.deployment.environment shouldBe "staging"
            properties.deployment.proxyReadTimeout?.toSeconds() shouldBe 120L
        }
    }

    @Test
    fun `a gateway left pointing at a local object store fails startup`() {
        runner.withPropertyValues(*deployed, "testinbox.storage.endpoint=http://localhost:9000").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain "testinbox.storage.endpoint"
        }
    }

    @Test
    fun `an ingress that would cut a long poll short fails startup`() {
        runner.withPropertyValues(*deployed, "testinbox.deployment.proxy-read-timeout=60s").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain "testinbox.proxy-read-timeout"
        }
    }

    private val production =
        arrayOf(
            "spring.profiles.active=production",
            "testinbox.deployment.environment=production",
            "testinbox.deployment.proxy-read-timeout=100s",
            "testinbox.deployment.edge-request-ceiling=100s",
            "testinbox.mail-domain=inbox.testinbox.email",
            "testinbox.storage.create-bucket=false",
            "spring.datasource.url=jdbc:postgresql://db.prod.internal:5432/testinbox",
            "spring.datasource.username=testinbox_prod",
            "spring.datasource.password=fixture-not-a-real-db-password--1",
            "testinbox.storage.endpoint=https://objects.prod.internal",
            "testinbox.storage.access-key=fixture-not-a-real-s3-access-key",
            "testinbox.storage.secret-key=fixture-not-a-real-s3-secret----1",
        )

    @Test
    fun `a correctly configured production gateway starts without a public base URL`() {
        // The gateway terminates SMTP; the production public-origin rule is
        // the API's, and must not be applied here by accident.
        runner.withPropertyValues(*production).run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `a production gateway that would create its own bucket fails startup (ADR-034)`() {
        runner.withPropertyValues(*production, "testinbox.storage.create-bucket=true").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain "testinbox.storage.create-bucket"
        }
    }

    @Test
    fun `a production gateway on staging configuration fails startup`() {
        runner.withPropertyValues(*production, "spring.profiles.active=staging").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain "spring.profiles.active"
        }
    }

    @Test
    fun `a deployed profile with a blank environment name is refused`() {
        runner.withPropertyValues("spring.profiles.active=staging", "testinbox.deployment.environment=").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain "testinbox.deployment.environment is not set"
        }
    }

    @Test
    fun `the guard is inert when the gateway is not deployed`() {
        runner
            .withPropertyValues("spring.datasource.password=testinbox", "testinbox.storage.access-key=testinbox")
            .run { context -> assertThat(context).hasNotFailed() }
    }
}
