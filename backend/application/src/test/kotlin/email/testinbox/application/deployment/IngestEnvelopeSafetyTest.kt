package email.testinbox.application.deployment

import email.testinbox.application.LimitsProperties
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.identity.BetaParticipantCap
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
    fun `the pins imply the amendment's inequality - r x (30 + L) never exceeds R_gated`() {
        val worst =
            java.math
                .BigDecimal(IngestEnvelopeSafety.APPROVED_WORKSPACE_REFILL.toString())
                .multiply(java.math.BigDecimal(30 + IngestEnvelopeSafety.APPROVED_LEGACY_WORKSPACES))
        worst.compareTo(java.math.BigDecimal(IngestEnvelopeSafety.APPROVED_GATED_RATE.toString())) shouldBe 0
        IngestEnvelopeSafety.APPROVED_GATED_RATE shouldBe 31.0
    }

    @Test
    fun `a refill above the owner-approved r of 1 refuses ALL - raising it is an owner decision, never configuration`() {
        only(
            all(ingest = beta.copy(refillPerSecond = 1.0001)),
            IngestEnvelopeSafety.WORKSPACE_REFILL,
            "owner-approved per-workspace INGEST refill r is 1.0",
        )
        // The library default, 10/s: exactly the 310/s worst case the decision paper measured against.
        only(all(ingest = beta.copy(refillPerSecond = 10.0)), IngestEnvelopeSafety.WORKSPACE_REFILL, "owner decision")
        // Lowering r only lowers load: allowed, with no rerun.
        envelope(all(ingest = beta.copy(refillPerSecond = 0.5), perInbox = betaPerInbox.copy(refillPerSecond = 0.25))).shouldBeEmpty()
    }

    @Test
    fun `more than the one approved legacy workspace refuses ALL - no broader cohort is authorized`() {
        only(all(legacy = 2), IngestEnvelopeSafety.LEGACY, "did not authorize a broader cohort")
        only(all(legacy = 32), IngestEnvelopeSafety.LEGACY, "owner approved at most 1")
        envelope(all(legacy = 0)).shouldBeEmpty()
    }

    @Test
    fun `R_gated must be exactly the approved 31 - a looser or tighter declaration is refused`() {
        for (loose in listOf(30.999, 31.001, 32.0, 62.0, 260.0, 30.0)) {
            only(all(rGated = loose), IngestEnvelopeSafety.GATED_RATE, "owner approved R = 1.0 × (30 + 1) = 31.0")
        }
        envelope(all(rGated = 31.0)).shouldBeEmpty()
        // Lowering r or L lowers load and keeps R_gated at the evidence's 31: no rerun is needed.
        envelope(
            all(rGated = 31.0, legacy = 0, ingest = beta.copy(refillPerSecond = 0.1), perInbox = betaPerInbox.copy(refillPerSecond = 0.05)),
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
        // Well-formed but outside the approved envelope is only ALL's business: OFF accepts it.
        val off = all(legacy = 32, rGated = 62.0).let { it.copy(storage = it.storage.copy(enforcement = StorageEnforcement.OFF)) }
        envelope(off).shouldBeEmpty()
    }

    // --- M0: the configured participant cap N and the gateway's pool size ------------------------

    private fun capped(
        n: Int,
        rGated: Double? = 31.0,
        legacy: Int? = 1,
        pool: IngestionPoolSize? = null,
    ) = all(rGated = rGated, legacy = legacy).let {
        it.copy(storage = it.storage.copy(participantCap = BetaParticipantCap(n)), ingestionPoolSize = pool)
    }

    /** What the owner might approve for a first cohort of ten; TEST-ONLY, never in APPROVED_ENVELOPES. */
    private val tenForTest =
        ApprovedIngestEnvelope(participants = 10, refill = 1.0, burst = 60, legacy = 1, ingestionPoolSize = 8, approval = "test only")

    /** Amendment D plus the test-only entry: the derivation exercised independently of the shipped first-cohort entry. */
    private val withTen = IngestEnvelopeSafety.APPROVED_ENVELOPES.filter { it.participants == 30 } + tenForTest

    @Test
    fun `the approved set is exactly amendment D's entry and the first-cohort entry`() {
        IngestEnvelopeSafety.APPROVED_ENVELOPES.map { it.participants } shouldBe listOf(30, 10)
        val first = IngestEnvelopeSafety.APPROVED_ENVELOPES.single { it.participants == 10 }
        first.refill shouldBe 1.0
        first.burst shouldBe 60
        first.legacy shouldBe 1
        first.gatedRate.compareTo(java.math.BigDecimal("11")) shouldBe 0
        first.ingestionPoolSize shouldBe 8
        val only = IngestEnvelopeSafety.APPROVED_ENVELOPES.single { it.participants == 30 }
        only.participants shouldBe 30
        only.refill shouldBe IngestEnvelopeSafety.APPROVED_WORKSPACE_REFILL
        only.burst shouldBe IngestEnvelopeSafety.APPROVED_WORKSPACE_BURST
        only.legacy shouldBe IngestEnvelopeSafety.APPROVED_LEGACY_WORKSPACES
        only.gatedRate.compareTo(java.math.BigDecimal(IngestEnvelopeSafety.APPROVED_GATED_RATE.toString())) shouldBe 0
        // Amendment D names no pool size and no committed gate D evidence ran at the gateway's pool.
        only.ingestionPoolSize shouldBe null
    }

    @Test
    fun `unset N is thirty and the approved envelope starts ALL exactly as before`() {
        settings.storage.participantCap shouldBe BetaParticipantCap.HARD_LIMIT
        envelope(capped(30)).shouldBeEmpty()
        IngestEnvelopeSafety.expectedGatedRate(capped(30).storage)!!.compareTo(java.math.BigDecimal("31")) shouldBe 0
        IngestEnvelopeSafety.mailReceivingWorkspaces(capped(30).storage) shouldBe 31
    }

    private val amendmentDOnly = IngestEnvelopeSafety.APPROVED_ENVELOPES.filter { it.participants == 30 }

    private fun onlyAgainst(
        approved: List<ApprovedIngestEnvelope>,
        settings: DeploymentSettings,
        key: String,
        problem: String,
    ) {
        val found = IngestEnvelopeSafety.check(settings, approved)
        found.map { it.setting } shouldBe listOf(key)
        found.single().problem shouldContain problem
    }

    @Test
    fun `N = 10 is refused under ALL until the owner approves it - fail closed, naming the missing approval`() {
        // Against amendment D alone, as before the first-cohort entry: R_gated from N = 10, and the old 31, both refused.
        for (rGated in listOf(11.0, 31.0)) {
            onlyAgainst(
                amendmentDOnly,
                capped(10, rGated = rGated),
                IngestEnvelopeSafety.PARTICIPANTS,
                "no owner-approved INGEST envelope covers N = 10",
            )
        }
        val problem = IngestEnvelopeSafety.check(capped(10, rGated = 11.0), amendmentDOnly).single().problem
        problem shouldContain "is 10 but"
        problem shouldContain "r × (N + L) = 1.0 × (10 + 1) = 11.0 events/s has no owner approval"
        problem shouldContain "Approved: N = 30: R = 1.0 × (30 + 1) = 31.0"
        problem shouldContain "IngestEnvelopeSafety.APPROVED_ENVELOPES"
        IngestEnvelopeSafety.expectedGatedRate(capped(10).storage, amendmentDOnly) shouldBe null
        // Every other lowered N, against the shipped set.
        for (n in (1..29) - 10) envelope(capped(n)).map { it.setting } shouldContain IngestEnvelopeSafety.PARTICIPANTS
        // Outside ALL a lowered N only lowers admissions; it needs no envelope.
        for (mode in listOf(StorageEnforcement.OFF, StorageEnforcement.TENANT_LIMITS)) {
            envelope(capped(10).let { it.copy(storage = it.storage.copy(enforcement = mode)) }).shouldBeEmpty()
        }
    }

    @Test
    fun `with a test-only approved entry the derivation gives R = 11 and holds the deployment to it`() {
        tenForTest.gatedRate.compareTo(java.math.BigDecimal("11")) shouldBe 0
        IngestEnvelopeSafety.expectedGatedRate(capped(10).storage, withTen)!!.compareTo(java.math.BigDecimal("11")) shouldBe 0
        IngestEnvelopeSafety.check(capped(10, rGated = 11.0), withTen).shouldBeEmpty()
        // The N = 30 evidence rate is not the N = 10 one.
        val stale = IngestEnvelopeSafety.check(capped(10, rGated = 31.0), withTen)
        stale.map { it.setting } shouldBe listOf(IngestEnvelopeSafety.GATED_RATE)
        stale.single().problem shouldContain "owner approved R = 1.0 × (10 + 1) = 11.0"
        // N = 30 keeps its own entry beside it.
        IngestEnvelopeSafety.check(capped(30, rGated = 31.0), withTen).shouldBeEmpty()
        // N = 10 sizes the pre-plan population bound too.
        IngestEnvelopeSafety.mailReceivingWorkspaces(capped(10).storage) shouldBe 11
    }

    @Test
    fun `the gateway's pool must equal the approved entry's - 8 accepted, 10 or unreadable refused`() {
        IngestEnvelopeSafety.check(capped(10, rGated = 11.0, pool = IngestionPoolSize.Known(8)), withTen).shouldBeEmpty()
        val ten = IngestEnvelopeSafety.check(capped(10, rGated = 11.0, pool = IngestionPoolSize.Known(10)), withTen)
        ten.map { it.setting } shouldBe listOf(IngestEnvelopeSafety.POOL_SIZE)
        ten.single().problem shouldContain "is 10 but the approved envelope's evidence covers an ingestion pool of exactly 8"
        val unreadable = IngestEnvelopeSafety.check(capped(10, rGated = 11.0, pool = IngestionPoolSize.Unreadable("not Hikari")), withTen)
        unreadable.map { it.setting } shouldBe listOf(IngestEnvelopeSafety.POOL_SIZE)
        unreadable.single().problem shouldContain "could not be determined (not Hikari)"
        // The API runs no SMTP T1 and reports no pool: nothing to compare.
        IngestEnvelopeSafety.check(capped(10, rGated = 11.0, pool = null), withTen).shouldBeEmpty()
    }

    @Test
    fun `today the gateway cannot start ALL - amendment D approved no pool size, so even the default 8 is refused`() {
        for (pool in listOf(IngestionPoolSize.Known(8), IngestionPoolSize.Known(10), IngestionPoolSize.Unreadable("not Hikari"))) {
            only(capped(30, pool = pool), IngestEnvelopeSafety.POOL_SIZE, "the owner has approved no ingestion database pool size")
        }
        // Outside ALL the pool is not part of any envelope.
        envelope(
            capped(30, pool = IngestionPoolSize.Known(10)).let { it.copy(storage = it.storage.copy(enforcement = StorageEnforcement.OFF)) },
        ).shouldBeEmpty()
    }

    @Test
    fun `the first-cohort entry - cap 10 with pool 8 starts ALL, pool 10 or cap 12 is refused`() {
        // The API (no pool) and the gateway (pool 8) at N = 10, R_gated = 11.
        envelope(capped(10, rGated = 11.0)).shouldBeEmpty()
        envelope(capped(10, rGated = 11.0, pool = IngestionPoolSize.Known(8))).shouldBeEmpty()
        IngestEnvelopeSafety.expectedGatedRate(capped(10).storage)!!.compareTo(java.math.BigDecimal("11")) shouldBe 0
        only(
            capped(10, rGated = 11.0, pool = IngestionPoolSize.Known(10)),
            IngestEnvelopeSafety.POOL_SIZE,
            "is 10 but the approved envelope's evidence covers an ingestion pool of exactly 8",
        )
        only(
            capped(10, rGated = 11.0, pool = IngestionPoolSize.Unreadable("not Hikari")),
            IngestEnvelopeSafety.POOL_SIZE,
            "could not be determined",
        )
        only(
            capped(12, rGated = 13.0, pool = IngestionPoolSize.Known(8)),
            IngestEnvelopeSafety.PARTICIPANTS,
            "no owner-approved INGEST envelope covers N = 12",
        )
        // N = 10 at amendment D's rate is not the first cohort's evidence.
        only(capped(10, rGated = 31.0), IngestEnvelopeSafety.GATED_RATE, "owner approved R = 1.0 × (10 + 1) = 11.0")
        // Not more than the one owner workspace, and nothing above r = 1 or b = 60.
        only(capped(10, rGated = 11.0, legacy = 2), IngestEnvelopeSafety.LEGACY, "owner approved at most 1")
        envelope(capped(10, rGated = 11.0).copy(ingest = beta.copy(capacity = 61))).map { it.setting } shouldBe
            listOf(IngestEnvelopeSafety.WORKSPACE_CAPACITY)
    }

    @Test
    fun `two entries for one N are ambiguous and fail closed`() {
        val twice = withTen + tenForTest.copy(ingestionPoolSize = 16)
        IngestEnvelopeSafety.check(capped(10, rGated = 11.0), twice).map { it.setting } shouldBe listOf(IngestEnvelopeSafety.PARTICIPANTS)
        IngestEnvelopeSafety.APPROVED_ENVELOPES
            .groupBy { it.participants }
            .values
            .all { it.size == 1 } shouldBe true
    }

    @Test
    fun `an approved entry cannot be malformed`() {
        for (bad in listOf(
            { tenForTest.copy(participants = 0) },
            { tenForTest.copy(participants = 31) },
            { tenForTest.copy(refill = 0.0) },
            { tenForTest.copy(burst = 0) },
            { tenForTest.copy(legacy = -1) },
            { tenForTest.copy(ingestionPoolSize = 0) },
        )) {
            io.kotest.assertions.throwables
                .shouldThrow<IllegalArgumentException> { bad() }
        }
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
