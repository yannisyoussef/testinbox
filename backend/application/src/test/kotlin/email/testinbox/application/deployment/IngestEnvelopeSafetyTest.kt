package email.testinbox.application.deployment

import email.testinbox.application.LimitsProperties
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * ADR-035 §11 amendment D (owner decision 2026-11-06): `ALL` is refused while the
 * configured per-workspace INGEST refill r × the mail-receiving workspaces
 * (30 participant places + L) exceeds R_gated, the expected rate of the passing
 * gate D evidence; while R_gated or L is undeclared; while the burst exceeds the
 * approved b = 60; and, in every mode, while a per-inbox value exceeds its
 * workspace's. Each refusal is exercised on its own.
 */
class IngestEnvelopeSafetyTest {
    private val beta = LimitsProperties.RateProperties(capacity = 60, refillPerSecond = 1.0)
    private val betaPerInbox = LimitsProperties.RateProperties(capacity = 30, refillPerSecond = 0.5)

    private val settings =
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
            proxyReadTimeout = Duration.ofSeconds(120),
            edgeRequestCeiling = Duration.ofSeconds(100),
            limitsEnabled = true,
            ingest = beta,
            ingestPerInbox = betaPerInbox,
            storage =
                StorageDeclarations(
                    enforcement = StorageEnforcement.ALL,
                    gatedExpectedRate = 31.0,
                    declaredLegacyWorkspaces = 1,
                ),
        )

    private fun all(
        rGated: Double? = 31.0,
        legacy: Int? = 1,
        ingest: LimitsProperties.RateProperties = beta,
        perInbox: LimitsProperties.RateProperties = betaPerInbox,
        limitsEnabled: Boolean = true,
    ) = settings.copy(
        ingest = ingest,
        ingestPerInbox = perInbox,
        limitsEnabled = limitsEnabled,
        storage = settings.storage.copy(gatedExpectedRate = rGated, declaredLegacyWorkspaces = legacy),
    )

    private fun envelope(settings: DeploymentSettings) = IngestEnvelopeSafety.check(settings)

    private fun only(
        settings: DeploymentSettings,
        key: String,
        problem: String,
    ) {
        val found = envelope(settings)
        found.map { it.setting } shouldBe listOf(key)
        found.single().problem shouldContain problem
    }

    @Test
    fun `the approved beta envelope - r 1, b 60, 30 places plus L 1, R_gated 31 - starts ALL`() {
        envelope(all()).shouldBeEmpty()
        IngestEnvelopeSafety.mailReceivingWorkspaces(all().storage) shouldBe 31
    }

    @Test
    fun `R_gated undeclared refuses ALL - there is no default, least of all 260`() =
        only(all(rGated = null), IngestEnvelopeSafety.GATED_RATE, "is not declared")

    @Test
    fun `L undeclared refuses ALL - the population is never guessed`() =
        only(all(legacy = null), IngestEnvelopeSafety.LEGACY, "is not declared")

    @Test
    fun `a refill whose r x N exceeds R_gated refuses ALL, with the arithmetic`() {
        only(
            all(ingest = beta.copy(refillPerSecond = 2.0)),
            IngestEnvelopeSafety.WORKSPACE_REFILL,
            "r × N = 2.0 × 31 (30 participant places + 1 legacy) = 62 events/s, above R_gated 31.0",
        )
        // Today's library default, 10/s, is exactly the 310/s worst case the decision paper measured against.
        only(
            all(ingest = beta.copy(refillPerSecond = 10.0), perInbox = betaPerInbox),
            IngestEnvelopeSafety.WORKSPACE_REFILL,
            "= 310 events/s",
        )
    }

    @Test
    fun `admitting one more legacy workspace than the gate covered refuses ALL until gate D runs at the new R`() {
        only(all(legacy = 2), IngestEnvelopeSafety.WORKSPACE_REFILL, "1.0 × 32")
        envelope(all(legacy = 2, rGated = 32.0)).shouldBeEmpty()
    }

    @Test
    fun `the inequality is exact at the boundary and immune to binary rounding`() {
        envelope(all(rGated = 31.0)).shouldBeEmpty()
        only(all(rGated = 30.999), IngestEnvelopeSafety.WORKSPACE_REFILL, "above R_gated 30.999")
        // 0.1 × 30 is 3.0000000000000004 in binary floating point; the declared 3.0 must still hold.
        envelope(
            all(
                legacy = 0,
                rGated = 3.0,
                ingest = beta.copy(refillPerSecond = 0.1),
                perInbox = betaPerInbox.copy(refillPerSecond = 0.05),
            ),
        ).shouldBeEmpty()
    }

    @Test
    fun `a burst above the approved b of 60 refuses ALL - gate D does not measure bursts`() {
        only(all(ingest = beta.copy(capacity = 61)), IngestEnvelopeSafety.WORKSPACE_CAPACITY, "approved per-workspace INGEST burst b is 60")
        envelope(all(ingest = beta.copy(capacity = 60))).shouldBeEmpty()
    }

    @Test
    fun `disabled limits under ALL leave INGEST unbounded and refuse`() =
        only(all(limitsEnabled = false), IngestEnvelopeSafety.WORKSPACE_REFILL, "unbounded")

    @Test
    fun `an infinite refill under ALL is refused, not skipped`() =
        only(
            all(
                ingest = beta.copy(refillPerSecond = Double.POSITIVE_INFINITY),
                perInbox = betaPerInbox,
            ),
            IngestEnvelopeSafety.WORKSPACE_REFILL,
            "unbounded refill",
        )

    @Test
    fun `a per-inbox budget above its workspace's is refused in every mode`() {
        for (mode in StorageEnforcement.entries) {
            val over = all(perInbox = betaPerInbox.copy(capacity = 61)).let { it.copy(storage = it.storage.copy(enforcement = mode)) }
            envelope(over).map { it.setting } shouldContain IngestEnvelopeSafety.INBOX_CAPACITY
            val faster =
                all(
                    perInbox = betaPerInbox.copy(refillPerSecond = 1.5),
                ).let { it.copy(storage = it.storage.copy(enforcement = mode)) }
            envelope(faster).map { it.setting } shouldContain IngestEnvelopeSafety.INBOX_REFILL
        }
        // Equal is allowed (LimitsConfig agrees); only above is refused.
        envelope(all(perInbox = beta)).shouldBeEmpty()
    }

    @Test
    fun `the library defaults keep the per-inbox budget inside the workspace's`() {
        val defaults = LimitsProperties()
        envelope(
            settings.copy(
                ingest = defaults.ingest,
                ingestPerInbox = defaults.ingestPerInbox,
                storage = StorageDeclarations(),
            ),
        ).shouldBeEmpty()
    }

    @Test
    fun `OFF and TENANT_LIMITS need no envelope declaration and keep the library defaults`() {
        val defaults = LimitsProperties()
        for (mode in listOf(StorageEnforcement.OFF, StorageEnforcement.TENANT_LIMITS)) {
            envelope(
                settings.copy(
                    ingest = defaults.ingest,
                    ingestPerInbox = defaults.ingestPerInbox,
                    storage = StorageDeclarations(enforcement = mode),
                ),
            ).shouldBeEmpty()
        }
    }

    @Test
    fun `declared values must be well-formed in every mode`() {
        for (mode in StorageEnforcement.entries) {
            for (bad in listOf(0.0, -31.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                val found = envelope(all(rGated = bad).let { it.copy(storage = it.storage.copy(enforcement = mode)) })
                found.map { it.setting } shouldContain IngestEnvelopeSafety.GATED_RATE
            }
            for (bad in listOf(-1, 33)) {
                val found = envelope(all(legacy = bad).let { it.copy(storage = it.storage.copy(enforcement = mode)) })
                found.map { it.setting } shouldContain IngestEnvelopeSafety.LEGACY
            }
        }
        // 32 is the most a pool can hold (CohortStorageRuntime.MAX_LEGACY_WORKSPACES); 0 is a beta with no legacy workspace.
        envelope(all(legacy = 32, rGated = 62.0)).shouldBeEmpty()
        envelope(all(legacy = 0, rGated = 30.0)).shouldBeEmpty()
    }

    @Test
    fun `DeploymentSafety reports every envelope gap with the rest of the startup report`() {
        val found = DeploymentSafety.validate(all(rGated = null, legacy = null, ingest = beta.copy(capacity = 300, refillPerSecond = 10.0)))
        val keys = found.map { it.setting }
        keys shouldContain IngestEnvelopeSafety.GATED_RATE
        keys shouldContain IngestEnvelopeSafety.LEGACY
        keys shouldContain IngestEnvelopeSafety.WORKSPACE_CAPACITY
        DeploymentSafety.describe(found) shouldContain "testinbox.storage.gated-expected-rate is not declared"
    }
}
