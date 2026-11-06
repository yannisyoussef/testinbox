package email.testinbox.application.deployment

import email.testinbox.application.storage.AdmissionAccounting
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
 * `testinbox.storage.admission-accounting` (slot-lane escrow, ADR-038 PROPOSED; M1
 * prototype). The owner authorized design and prototype work, not production
 * activation: GLOBAL (mode a) is the default and never refused; ESCROW is refused in
 * every environment but `lab`, and wherever it could not engage (anything but `ALL`
 * with a declared filesystem and GLOBAL locking), as COHORT_PARTITIONED is.
 */
class AdmissionAccountingSafetyTest {
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
    private val lab = staging.copy(environment = "lab", mailDomain = "lab.testinbox.test", activeProfiles = setOf("lab"))

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
    private val escrow = all.copy(admissionAccounting = AdmissionAccounting.ESCROW)

    private fun accounting(
        settings: DeploymentSettings,
        storage: StorageDeclarations,
    ) = DeploymentSafety.validate(settings.copy(storage = storage)).filter { it.setting == "testinbox.storage.admission-accounting" }

    @Test
    fun `GLOBAL is the default and never refused, in any mode or environment`() {
        StorageDeclarations().admissionAccounting shouldBe AdmissionAccounting.GLOBAL
        for (mode in StorageEnforcement.entries) {
            accounting(staging, all.copy(enforcement = mode)).shouldBeEmpty()
            accounting(staging.copy(environment = "production"), all.copy(enforcement = mode)).shouldBeEmpty()
        }
    }

    @Test
    fun `ESCROW under ALL with a declared filesystem and GLOBAL locking is accepted in the lab, and only there`() {
        accounting(lab, escrow).shouldBeEmpty()
        for (environment in listOf("staging", "production", "rehearsal", "")) {
            accounting(staging.copy(environment = environment), escrow).map { it.problem }.single() shouldContain
                "in environment '$environment'"
        }
        accounting(staging.copy(environment = "production"), escrow).single().problem shouldContain "PROPOSED ADR-038"
    }

    @Test
    fun `ESCROW is refused wherever it could not engage - outside ALL, without a filesystem, or with partitioned locking`() {
        for (mode in listOf(StorageEnforcement.OFF, StorageEnforcement.TENANT_LIMITS)) {
            accounting(lab, escrow.copy(enforcement = mode)).map { it.problem }.single() shouldContain "only ALL with a declared filesystem"
        }
        accounting(lab, escrow.copy(filesystem = FilesystemDeclarations())).any { "without a declared filesystem" in it.problem } shouldBe
            true
        accounting(lab, escrow.copy(admissionLocking = AdmissionLocking.COHORT_PARTITIONED)).map { it.problem }.single() shouldContain
            "admission-locking = GLOBAL"
    }
}
