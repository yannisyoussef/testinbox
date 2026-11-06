package email.testinbox.api.config

import email.testinbox.application.LimitsProperties
import email.testinbox.application.deployment.DeploymentSettings
import email.testinbox.application.deployment.IngestEnvelopeSafety
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.Environment
import java.time.Duration

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

    private fun limits(env: Environment): LimitsProperties =
        Binder.get(env).bind("testinbox.limits", LimitsProperties::class.java).orElseGet {
            LimitsProperties()
        }

    /** The resolved envelope, under ALL with the approved R_gated 31 and L 1. */
    private fun envelopeViolations(
        limits: LimitsProperties,
        rGated: Double = 31.0,
        legacy: Int = 1,
    ) = IngestEnvelopeSafety.check(
        DeploymentSettings(
            environment = "production",
            mailDomain = "inbox.testinbox.email",
            databaseUrl = "jdbc:postgresql://db.internal:5432/testinbox",
            databaseUsername = "testinbox",
            databasePassword = "fixture-not-a-real-db-password--1",
            storageEndpoint = "https://objects.internal",
            storageAccessKey = "fixture-not-a-real-s3-access-key",
            storageSecretKey = "fixture-not-a-real-s3-secret----1",
            publicBaseUrl = "https://api.testinbox.email",
            bootstrapApiKey = null,
            waitWindowCap = Duration.ofSeconds(60),
            proxyReadTimeout = Duration.ofSeconds(95),
            edgeRequestCeiling = Duration.ofSeconds(100),
            limitsEnabled = limits.enabled,
            ingest = limits.ingest,
            ingestPerInbox = limits.ingestPerInbox,
            storage =
                StorageDeclarations(
                    enforcement = StorageEnforcement.ALL,
                    gatedExpectedRate = rGated,
                    declaredLegacyWorkspaces = legacy,
                ),
        ),
    )

    @Test
    fun `staging and production resolve the closed-beta INGEST envelope, and it fits the approved R_gated 31 (ADR-035 amendment D)`() {
        for (profile in listOf("staging", "production")) {
            runner("spring.profiles.active=$profile", "TESTINBOX_MAIL_DOMAIN=staging.testinbox.email").run { context ->
                val limits = limits(context.environment)
                limits.ingest shouldBe LimitsProperties.RateProperties(capacity = 60, refillPerSecond = 1.0)
                limits.ingestPerInbox shouldBe LimitsProperties.RateProperties(capacity = 30, refillPerSecond = 0.5)
                envelopeViolations(limits).shouldBeEmpty()
                // R_gated and L have no default: an undeclared one binds to nothing, and ALL refuses it.
                context.environment.getProperty("testinbox.storage.gated-expected-rate").isNullOrEmpty() shouldBe true
                context.environment.getProperty("testinbox.storage.declared-legacy-workspaces").isNullOrEmpty() shouldBe true
            }
        }
    }

    @Test
    fun `an environment override of the envelope reaches the binding, and DeploymentSafety then judges it`() {
        runner(
            "spring.profiles.active=production",
            "TESTINBOX_LIMITS_INGEST_REFILL_PER_SECOND=2.0",
            "TESTINBOX_STORAGE_GATED_EXPECTED_RATE=31",
        ).run { context ->
            val limits = limits(context.environment)
            limits.ingest.refillPerSecond shouldBe 2.0
            context.environment.getProperty("testinbox.storage.gated-expected-rate") shouldBe "31"
            // r is pinned at the owner's 1 copy/s: no R_gated, however declared, admits 2.0 under ALL.
            envelopeViolations(limits).map { it.setting } shouldContain IngestEnvelopeSafety.WORKSPACE_REFILL
            envelopeViolations(limits, rGated = 62.0).map { it.setting } shouldContain IngestEnvelopeSafety.WORKSPACE_REFILL
        }
        // A LOWER refill is an override the envelope accepts as it is.
        runner(
            "spring.profiles.active=production",
            "TESTINBOX_LIMITS_INGEST_REFILL_PER_SECOND=0.5",
            "TESTINBOX_LIMITS_INGEST_PER_INBOX_REFILL_PER_SECOND=0.25",
        ).run { context -> envelopeViolations(limits(context.environment)).shouldBeEmpty() }
    }

    @Test
    fun `local development keeps the library INGEST defaults`() {
        runner().run { context ->
            val limits = limits(context.environment)
            limits.ingest shouldBe LimitsProperties().ingest
            limits.ingestPerInbox shouldBe LimitsProperties().ingestPerInbox
        }
    }
}
