package email.testinbox.ingestion.ops

import email.testinbox.ingestion.config.IngestionProperties
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
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
            dataSource: org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource>,
        ) = IngestionDeploymentSafetyCheck(
            properties,
            dataSourceProperties,
            environment,
            properties.storageDeclarations(
                email.testinbox.storage.QualificationRecords
                    .load(),
            ),
            dataSource,
        )
    }

    /** A Hikari pool that is configured, never started: the check reads its size without a connection. */
    @Configuration
    class HikariPool {
        @Bean(destroyMethod = "")
        fun dataSource(
            @org.springframework.beans.factory.annotation.Value("\${spring.datasource.hikari.maximum-pool-size:8}") size: Int,
        ): javax.sql.DataSource =
            com.zaxxer.hikari
                .HikariDataSource()
                .apply { maximumPoolSize = size }
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

    // --- Gate D A′: T1's insert and COMMIT share one round trip, under pgJDBC's defaults only ------

    @Test
    fun `a gateway whose database URL turns on pgJDBC autosave or another query mode fails startup`() {
        for (query in listOf("autosave=always", "AutoSave=Conservative", "preferQueryMode=simple")) {
            runner
                .withPropertyValues(*deployed, "spring.datasource.url=jdbc:postgresql://db.staging.internal:5432/testinbox?$query")
                .run { context ->
                    assertThat(context).hasFailed()
                    context.startupFailure!!.stackTraceToString() shouldContain "spring.datasource.url sets"
                }
        }
    }

    @Test
    fun `a gateway whose Hikari data-source properties turn on autosave or another query mode fails startup`() {
        // The keys Hikari itself binds, so a property that reaches the driver is a property the guard sees.
        for ((key, value) in listOf("autosave" to "always", "autosave" to "CONSERVATIVE", "preferQueryMode" to "simple")) {
            runner
                .withPropertyValues(*deployed, "spring.datasource.hikari.data-source-properties.$key=$value")
                .run { context ->
                    assertThat(context).hasFailed()
                    context.startupFailure!!.stackTraceToString() shouldContain
                        "spring.datasource.hikari.data-source-properties.$key sets"
                }
        }
    }

    @Test
    fun `the gateway's own data-source properties and pgJDBC's stated defaults start`() {
        runner
            .withPropertyValues(
                *deployed,
                "spring.datasource.url=jdbc:postgresql://db.staging.internal:5432/testinbox?sslmode=require&autosave=never",
                "spring.datasource.hikari.data-source-properties.ApplicationName=testinbox-ingestion:n:storage-v1",
                "spring.datasource.hikari.data-source-properties.preferQueryMode=extended",
            ).run { context -> assertThat(context).hasNotFailed() }
    }

    // --- TI-STORAGE-006: enforcement may exist, safely ---------------------------------------------

    @Test
    fun `TENANT_LIMITS on a deployed gateway with no ADR-035 declarations fails startup, naming every missing one`() {
        runner.withPropertyValues(*deployed, "testinbox.storage.enforcement=TENANT_LIMITS").run { context ->
            assertThat(context).hasFailed()
            val message = context.startupFailure!!.stackTraceToString()
            for (key in listOf(
                "global-limit-bytes",
                "declared-max-ingestion-processes",
                "inbox-share",
                "backend-identity",
                "filesystem.block-size-bytes",
                "filesystem.object-overhead-max-bytes",
                "filesystem.global-footprint-limit-bytes",
                "filesystem.deletion-debt-budget-bytes",
                "filesystem.metadata-budget-bytes",
                "filesystem.operational-reserve-bytes",
                "filesystem.capacity-bytes",
                "filesystem.inodes",
                "filesystem.observation-max-age",
                "filesystem.probe-budget-bytes",
                "filesystem.monitor-role",
            )) {
                message shouldContain "testinbox.storage.$key"
            }
            // The legacy bucket-quota fuse is optional under Amendment 2: never reported as missing.
            message.contains("declared-bucket-quota-bytes") shouldBe false
            message.contains("measured-quota-lag-churn-bytes") shouldBe false
        }
    }

    @Test
    fun `every filesystem declaration binds on the gateway too`() {
        runner
            .withPropertyValues(
                *deployed,
                "testinbox.storage.filesystem.block-size-bytes=4096",
                "testinbox.storage.filesystem.object-overhead-max-bytes=24576",
                "testinbox.storage.filesystem.global-footprint-limit-bytes=21474836480",
                "testinbox.storage.filesystem.deletion-debt-budget-bytes=8589934592",
                "testinbox.storage.filesystem.metadata-budget-bytes=268435456",
                "testinbox.storage.filesystem.operational-reserve-bytes=3221225472",
                "testinbox.storage.filesystem.capacity-bytes=51539607552",
                "testinbox.storage.filesystem.inodes=12582912",
                "testinbox.storage.filesystem.observation-max-age=15m",
                "testinbox.storage.filesystem.probe-budget-bytes=67108864",
                "testinbox.storage.filesystem.monitor-role=testinbox_monitor",
            ).run { context ->
                assertThat(context).hasNotFailed()
                context
                    .getBean(IngestionProperties::class.java)
                    .storageDeclarations(emptyList())
                    .filesystem shouldBe
                    email.testinbox.application.storage.FilesystemDeclarations(
                        blockSizeBytes = 4096,
                        objectOverheadMaxBytes = 24576,
                        globalFootprintLimitBytes = 21474836480,
                        deletionDebtBudgetBytes = 8589934592,
                        metadataBudgetBytes = 268435456,
                        operationalReserveBytes = 3221225472,
                        capacityBytes = 51539607552,
                        inodes = 12582912,
                        observationMaxAge = java.time.Duration.ofMinutes(15),
                        probeBudgetBytes = 67108864,
                        monitorRole = "testinbox_monitor",
                    )
            }
    }

    @Test
    fun `ALL is refused on a gateway that declares no environment - no ceiling may refuse outside a validated deployment`() {
        runner.withPropertyValues("testinbox.storage.enforcement=ALL").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain "testinbox.deployment.environment"
        }
    }

    @Test
    fun `an unknown enforcement value fails to bind - there are exactly three states`() {
        runner.withPropertyValues(*deployed, "testinbox.storage.enforcement=INBOX_ONLY").run { context ->
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun `OFF remains the default, and the property default is OFF`() {
        runner.withPropertyValues(*deployed).run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(IngestionProperties::class.java).storage.enforcement shouldBe
                email.testinbox.domain.storage.StorageEnforcement.OFF
        }
    }

    // --- ADR-035 amendment D: the gated INGEST envelope ---------------------------------------------

    @Test
    fun `the envelope declarations bind on the gateway - this pins their property names`() {
        runner
            .withPropertyValues(
                *deployed,
                "testinbox.storage.gated-expected-rate=31",
                "testinbox.storage.declared-legacy-workspaces=1",
                "testinbox.limits.ingest.capacity=60",
                "testinbox.limits.ingest.refill-per-second=1.0",
                "testinbox.limits.ingest-per-inbox.capacity=30",
                "testinbox.limits.ingest-per-inbox.refill-per-second=0.5",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val properties = context.getBean(IngestionProperties::class.java)
                val declarations = properties.storageDeclarations(emptyList())
                declarations.gatedExpectedRate shouldBe 31.0
                declarations.declaredLegacyWorkspaces shouldBe 1
                properties.limits.ingest shouldBe
                    email.testinbox.application.LimitsProperties
                        .RateProperties(60, 1.0)
                properties.limits.ingestPerInbox shouldBe
                    email.testinbox.application.LimitsProperties
                        .RateProperties(30, 0.5)
            }
    }

    @Test
    fun `ALL on a gateway without R_gated and L is refused, naming both, beside every other gap`() {
        runner.withPropertyValues(*deployed, "testinbox.storage.enforcement=ALL").run { context ->
            assertThat(context).hasFailed()
            val message = context.startupFailure!!.stackTraceToString()
            message shouldContain "testinbox.storage.gated-expected-rate is not declared"
            message shouldContain "testinbox.storage.declared-legacy-workspaces is not declared"
            // The library default burst (300) is above the approved b = 60.
            message shouldContain "testinbox.limits.ingest.capacity is 300"
        }
    }

    // --- The participant cap N and the gateway's pool size (M0) ------------------------------------

    @Test
    fun `N binds on the gateway too, defaults to thirty, and only a value in 1-30 starts`() {
        runner.withPropertyValues(*deployed).run { context ->
            assertThat(context).hasNotFailed()
            context
                .getBean(IngestionProperties::class.java)
                .storageDeclarations(emptyList())
                .participantCap.value shouldBe 30
        }
        runner.withPropertyValues(*deployed, "testinbox.beta.max-participants=10").run { context ->
            assertThat(context).hasNotFailed()
            context
                .getBean(IngestionProperties::class.java)
                .storageDeclarations(emptyList())
                .participantCap.value shouldBe 10
        }
        for (bad in listOf("0", "31", "-1", "ten", "10.5")) {
            runner.withPropertyValues(*deployed, "testinbox.beta.max-participants=$bad").run { context ->
                assertThat(context).hasFailed()
                context.startupFailure!!.stackTraceToString() shouldContain "testinbox.beta.max-participants"
            }
        }
    }

    @Test
    fun `the gateway reads its effective Hikari pool size, and fails closed where it cannot`() {
        IngestionDeploymentSafetyCheck.poolSize(
            com.zaxxer.hikari
                .HikariDataSource()
                .apply { maximumPoolSize = 8 },
        ) shouldBe
            email.testinbox.application.deployment.IngestionPoolSize
                .Known(8)
        // Unset: Hikari's own default (10) is what the pool runs with, so that is the size reported, never 8.
        IngestionDeploymentSafetyCheck.poolSize(com.zaxxer.hikari.HikariDataSource()) shouldBe
            email.testinbox.application.deployment.IngestionPoolSize
                .Known(10)
        IngestionDeploymentSafetyCheck
            .poolSize(null)
            .shouldBeInstanceOf<email.testinbox.application.deployment.IngestionPoolSize.Unreadable>()
        IngestionDeploymentSafetyCheck
            .poolSize(
                org.springframework.jdbc.datasource
                    .DriverManagerDataSource(),
            ).shouldBeInstanceOf<email.testinbox.application.deployment.IngestionPoolSize.Unreadable>()
    }

    @Test
    fun `ALL on the gateway names the pool size - unapproved today, and unreadable without a Hikari pool`() {
        // Every other declaration is missing too; only the pool line matters here.
        runner
            .withUserConfiguration(HikariPool::class.java)
            .withPropertyValues(*deployed, "testinbox.storage.enforcement=ALL")
            .run { context ->
                assertThat(context).hasFailed()
                context.startupFailure!!.stackTraceToString() shouldContain
                    "spring.datasource.hikari.maximum-pool-size is 8 but the owner has approved no ingestion database pool size"
            }
        runner.withPropertyValues(*deployed, "testinbox.storage.enforcement=ALL").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain
                "spring.datasource.hikari.maximum-pool-size is undetermined (no DataSource)"
        }
    }

    @Test
    fun `a lowered N under ALL is refused on the gateway, naming the missing owner approval`() {
        runner.withPropertyValues(*deployed, "testinbox.storage.enforcement=ALL", "testinbox.beta.max-participants=10").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure!!.stackTraceToString() shouldContain
                "testinbox.beta.max-participants is 10 but no owner-approved INGEST envelope covers N = 10"
        }
    }

    @Test
    fun `a per-inbox INGEST budget above the workspace's fails startup even under OFF`() {
        runner
            .withPropertyValues(*deployed, "testinbox.limits.ingest.capacity=60", "testinbox.limits.ingest.refill-per-second=1.0")
            .run { context ->
                // The library per-inbox default (60, 2/s) refills faster than this workspace budget.
                assertThat(context).hasFailed()
                context.startupFailure!!.stackTraceToString() shouldContain "testinbox.limits.ingest-per-inbox.refill-per-second"
            }
    }
}
