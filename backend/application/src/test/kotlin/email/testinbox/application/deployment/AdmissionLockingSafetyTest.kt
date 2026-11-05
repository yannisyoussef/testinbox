package email.testinbox.application.deployment

import email.testinbox.application.storage.AdmissionLocking
import email.testinbox.application.storage.FilesystemDeclarations
import email.testinbox.application.storage.QualificationFixtures
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * `testinbox.storage.admission-locking` (PROPOSED share-partitioned T1, gate D W1).
 * Owner decision 2026-11-05: implementation and testing authorized, release not.
 * GLOBAL (mode a) is the default and never refused; COHORT_PARTITIONED is refused
 * in every environment but `lab` (issue #102 precedent), and wherever it could not engage.
 */
class AdmissionLockingSafetyTest {
    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    private val staging =
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
            bootstrapApiKey = null,
            waitWindowCap = Duration.ofSeconds(60),
            proxyReadTimeout = Duration.ofSeconds(120),
            edgeRequestCeiling = null,
            limitsEnabled = true,
            activeProfiles = setOf("staging"),
        )

    private val all =
        StorageDeclarations(
            enforcement = StorageEnforcement.ALL,
            globalLimitBytes = 40 * gib,
            declaredBucketQuotaBytes = 50 * gib,
            declaredMaxIngestionProcesses = 2,
            inboxShare = "0.25",
            backendIdentity = QualificationFixtures.identity,
            qualificationRecords = listOf(QualificationFixtures.laptopLike, QualificationFixtures.eligible),
            expectedApiNodes = setOf("api-1"),
            expectedIngestionNodes = setOf("ingest-1", "ingest-2"),
            filesystem =
                FilesystemDeclarations(
                    blockSizeBytes = 4096,
                    objectOverheadMaxBytes = 24 * 1024,
                    globalFootprintLimitBytes = 20 * gib,
                    deletionDebtBudgetBytes = 8 * gib,
                    metadataBudgetBytes = 256 * mib,
                    operationalReserveBytes = 3 * gib,
                    capacityBytes = 48 * gib,
                    inodes = 48 * gib / 4096,
                    observationMaxAge = Duration.ofMinutes(15),
                    probeBudgetBytes = 64 * mib,
                    monitorRole = "testinbox_monitor",
                ),
        )

    private fun locking(
        settings: DeploymentSettings,
        storage: StorageDeclarations,
    ) = DeploymentSafety.validate(settings.copy(storage = storage)).filter { it.setting == "testinbox.storage.admission-locking" }

    @Test
    fun `GLOBAL is the default and never refused, in any mode or environment`() {
        StorageDeclarations().admissionLocking shouldBe AdmissionLocking.GLOBAL
        all.admissionLocking shouldBe AdmissionLocking.GLOBAL
        for (mode in StorageEnforcement.entries) {
            locking(staging, all.copy(enforcement = mode)).shouldBeEmpty()
            locking(staging.copy(environment = "production"), all.copy(enforcement = mode)).shouldBeEmpty()
        }
    }

    private val lab = staging.copy(environment = "lab", mailDomain = "lab.testinbox.test", activeProfiles = setOf("lab"))

    @Test
    fun `COHORT_PARTITIONED under ALL with a declared filesystem is accepted in the lab, and only there`() {
        val partitioned = all.copy(admissionLocking = AdmissionLocking.COHORT_PARTITIONED)
        locking(lab, partitioned).shouldBeEmpty()
        for (environment in listOf("staging", "production", "rehearsal", "")) {
            locking(staging.copy(environment = environment), partitioned).map { it.problem }.single() shouldContain
                "in environment '$environment'"
        }
    }

    @Test
    fun `COHORT_PARTITIONED is refused in production whatever else is declared`() {
        val refused = locking(staging.copy(environment = "production"), all.copy(admissionLocking = AdmissionLocking.COHORT_PARTITIONED))
        refused.map { it.problem }.single() shouldContain "PROPOSED ADR-035"
    }

    @Test
    fun `COHORT_PARTITIONED is refused wherever its guard could not engage`() {
        for (mode in listOf(StorageEnforcement.OFF, StorageEnforcement.TENANT_LIMITS)) {
            locking(lab, all.copy(enforcement = mode, admissionLocking = AdmissionLocking.COHORT_PARTITIONED))
                .map { it.problem }
                .single() shouldContain "only ALL with a declared filesystem"
        }
        val undeclared =
            locking(staging, all.copy(filesystem = FilesystemDeclarations(), admissionLocking = AdmissionLocking.COHORT_PARTITIONED))
        undeclared.any { "without a declared filesystem" in it.problem } shouldBe true
    }
}
