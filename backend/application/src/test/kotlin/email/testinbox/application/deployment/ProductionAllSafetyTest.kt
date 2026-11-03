package email.testinbox.application.deployment

import email.testinbox.application.storage.DeclaredNode
import email.testinbox.application.storage.FilesystemDeclarations
import email.testinbox.application.storage.NodeRole
import email.testinbox.application.storage.QualificationFixtures
import email.testinbox.application.storage.QualificationRecord
import email.testinbox.application.storage.QualificationRecordSource
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * TI-DELIVERY-002b: production refuses `ALL` unless every startup-checkable
 * release condition holds (ADR-035 amendment note 2026-10-30), one refusal per
 * unmet condition, each named. The one accepting case uses
 * [QualificationFixtures.productionQualified], a TEST FIXTURE that is never
 * shipped in the artifact (`QualificationRecordsTest` proves no shipped record
 * qualifies). `TENANT_LIMITS` stays refused in production whatever is declared,
 * and `OFF` and staging are unchanged.
 */
class ProductionAllSafetyTest {
    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024
    private val enforcement = "testinbox.storage.enforcement"

    private val production =
        DeploymentSettings(
            environment = ProductionPolicy.ENVIRONMENT,
            mailDomain = ProductionPolicy.MAIL_DOMAIN,
            databaseUrl = "jdbc:postgresql://db.internal:5432/testinbox",
            databaseUsername = "testinbox_api",
            databasePassword = "fixture-not-a-real-db-password--1",
            storageEndpoint = "https://objects.internal",
            storageAccessKey = "fixture-not-a-real-s3-access-key",
            storageSecretKey = "fixture-not-a-real-s3-secret----1",
            publicBaseUrl = "https://api.testinbox.email",
            bootstrapApiKey = null,
            waitWindowCap = Duration.ofSeconds(60),
            proxyReadTimeout = Duration.ofSeconds(95),
            edgeRequestCeiling = Duration.ofSeconds(100),
            limitsEnabled = true,
            activeProfiles = setOf("production"),
            requireDatabaseSessionTimeout = true,
        )

    private val filesystem =
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
        )

    /** Every declaration complete, matched to the TEST-ONLY production-qualified record. */
    private val qualified =
        StorageDeclarations(
            enforcement = StorageEnforcement.ALL,
            globalLimitBytes = 40 * gib,
            declaredMaxIngestionProcesses = 2,
            inboxShare = "0.25",
            backendIdentity = QualificationFixtures.identity,
            qualificationRecords = listOf(QualificationFixtures.laptopLike, QualificationFixtures.productionQualified),
            expectedApiNodes = setOf("api-1"),
            expectedIngestionNodes = setOf("ingest-1", "ingest-2"),
            node = DeclaredNode("ingest-1", NodeRole.INGESTION),
            filesystem = filesystem,
        )

    private fun violations(
        storage: StorageDeclarations,
        settings: DeploymentSettings = production,
    ) = DeploymentSafety.validate(settings.copy(storage = storage))

    /** The production-ALL refusal alone, with its problem text. */
    private fun refusal(storage: StorageDeclarations): String {
        val found = violations(storage)
        found.map { it.setting } shouldContain enforcement
        return found.single { it.setting == enforcement }.problem
    }

    private fun withRecord(record: QualificationRecord) = qualified.copy(qualificationRecords = listOf(record))

    @Test
    fun `production OFF with nothing declared starts - the baseline every refusal below departs from`() {
        violations(StorageDeclarations.OFF).shouldBeEmpty()
    }

    @Test
    fun `production ALL starts only when every startup condition holds - here with a TEST fixture record`() {
        violations(qualified).shouldBeEmpty()
        QualificationFixtures.productionQualified.recordId shouldContain "TEST-FIXTURE"
    }

    @Test
    fun `production ALL is refused when no record matches - the refusal names the running upload implementation`() {
        val problem = refusal(qualified.copy(qualificationRecords = listOf(QualificationFixtures.laptopLike)))
        problem shouldContain "is ALL in production"
        problem shouldContain "'${QualificationFixtures.UPLOAD}'"
        problem shouldContain "a lab record never counts"
    }

    @Test
    fun `a production-qualified record for the previous upload implementation is refused`() {
        val v1 = QualificationFixtures.productionQualified.copy(uploadImplementationVersion = "adr035-presigned-put-v1")
        val found = violations(withRecord(v1))
        found.map { it.setting } shouldContain "testinbox.storage.backend-identity.upload-implementation-version"
        found.single { it.setting == enforcement }.problem shouldContain "no enablement-eligible qualification record"
    }

    @Test
    fun `a production-qualified record for another backend is refused`() {
        val other =
            QualificationFixtures.productionQualified.copy(
                identity = QualificationFixtures.identity.copy(kernelRelease = "6.8.0-99-generic"),
            )
        val found = violations(withRecord(other))
        found.map { it.setting } shouldContain "testinbox.storage.backend-identity"
        found.single { it.setting == enforcement }.problem shouldContain "no enablement-eligible qualification record"
    }

    @Test
    fun `an eligible staging-host record is not production evidence`() {
        // Exactly the staging shape: eligible for staging ALL, never for production ALL.
        violations(withRecord(QualificationFixtures.eligible), production.copy(environment = "staging")).let { staging ->
            // Staging refuses for its own, unrelated reasons (profile, domain); never on the production-ALL rule.
            staging.map { it.setting } shouldNotContain enforcement
        }
        val problem = refusal(withRecord(QualificationFixtures.eligible))
        problem shouldContain "record 'fixture-eligible' cannot back production ALL"
        problem shouldContain "platformClass is 'staging-host', not 'production-host'"
        problem shouldContain "carries no filesystem elements"
    }

    @Test
    fun `a lab-only record is refused in production, matching or not`() {
        val lab =
            QualificationFixtures.productionQualified.copy(
                recordId = "lab-run",
                platformClass = "lab",
                enablementEligible = false,
                source = QualificationRecordSource.LAB,
            )
        val found = violations(withRecord(lab))
        found.map { it.setting } shouldContain "testinbox.storage.qualification.lab-record-path"
        found.single { it.setting == enforcement }.problem shouldContain "no enablement-eligible qualification record"
    }

    @Test
    fun `a production-host record that is not enablement-eligible is refused`() {
        val ineligible =
            QualificationFixtures.productionQualified.copy(
                slowWExecuted = false,
                enablementEligible = false,
                ineligibilityReasons = listOf("slow-W was not executed"),
            )
        refusal(withRecord(ineligible)) shouldContain "no enablement-eligible qualification record"
    }

    @Test
    fun `each missing Amendment 2 element of the record refuses, and is named`() {
        val base = QualificationFixtures.productionQualified
        val fs = checkNotNull(base.filesystem)
        val cases =
            mapOf(
                "carries no filesystem elements" to base.copy(filesystem = null),
                "filesystem.uuid is absent" to base.copy(filesystem = fs.copy(uuid = " ")),
                "filesystem.mountSource is absent" to base.copy(filesystem = fs.copy(mountSource = "")),
                "the measured O_max, E1–E3) is absent" to base.copy(filesystem = fs.copy(objectOverheadMaxBytes = null)),
                "filesystem.metadataInodesMeasured (E7) is absent" to base.copy(filesystem = fs.copy(metadataInodesMeasured = 0)),
                "experiments E7 are not recorded as PASS" to base.copy(filesystem = fs.copy(experiments = fs.experiments - "E7")),
                "experiments E11 are not recorded as PASS" to
                    base.copy(filesystem = fs.copy(experiments = fs.experiments + ("E11" to "FAIL"))),
            )
        cases.forEach { (reason, record) -> refusal(withRecord(record)) shouldContain reason }
    }

    @Test
    fun `a record contradicting its own filesystem is refused`() {
        val fs = checkNotNull(QualificationFixtures.productionQualified.filesystem)
        refusal(withRecord(QualificationFixtures.productionQualified.copy(filesystem = fs.copy(type = "xfs")))) shouldContain
            "filesystem.type 'xfs' contradicts host.filesystemType 'ext4'"
        refusal(withRecord(QualificationFixtures.productionQualified.copy(filesystem = fs.copy(mountOptions = "rw,noatime")))) shouldContain
            "contradicts host.mountOptions 'rw,relatime'"
    }

    @Test
    fun `a declared O_max below the record's measured O_max is refused - one byte under`() {
        val measured = checkNotNull(QualificationFixtures.productionQualified.filesystem?.objectOverheadMaxBytes)
        val record =
            QualificationFixtures.productionQualified.copy(
                filesystem = QualificationFixtures.productionQualified.filesystem?.copy(objectOverheadMaxBytes = measured + 1),
            )
        refusal(withRecord(record)) shouldContain "below the O_max ${measured + 1} measured by record"
        violations(qualified).shouldBeEmpty()
    }

    @Test
    fun `capacity that cannot hold the thirty shares, the worst envelope and finalization refuses - each boundary`() {
        // G below 30 x 128 MiB alone.
        refusal(qualified.copy(globalLimitBytes = 3 * gib)) shouldContain "exceeded: payload"
        // G_F of 8 GiB: still above H_F (so I-C and the cap pass) but below 30 x 256 MiB plus the envelope.
        val small = filesystem.copy(globalFootprintLimitBytes = 8 * gib)
        val found = violations(qualified.copy(filesystem = small))
        found.map { it.setting } shouldBe listOf(enforcement)
        found.single().problem shouldContain "exceeded: global_footprint"
        found.single().problem shouldContain "even before any legacy share"
    }

    @Test
    fun `missing declarations refuse production ALL on top of their own violations - the cohort proof is unproven`() {
        val found = violations(qualified.copy(declaredMaxIngestionProcesses = null))
        found.map { it.setting } shouldContain "testinbox.storage.declared-max-ingestion-processes"
        found.single { it.setting == enforcement }.problem shouldContain "cannot be evaluated from the declarations"
        val noNodes = violations(qualified.copy(expectedApiNodes = emptySet()))
        noNodes.single { it.setting == enforcement }.problem shouldContain "cohort binding cannot be computed"
    }

    @Test
    fun `node identity - an undeclared or mismatching node refuses production ALL`() {
        refusal(qualified.copy(node = null)) shouldContain "declares no storage node identity"
        val mismatch = violations(qualified.copy(node = DeclaredNode("ingest-9", NodeRole.INGESTION)))
        mismatch.map { it.setting } shouldBe listOf("testinbox.storage.activation.expected-ingestion-nodes")
        mismatch.single().problem shouldContain "does not include this node's own id 'ingest-9'"
        violations(qualified.copy(node = DeclaredNode("api-1", NodeRole.API))).shouldBeEmpty()
    }

    @Test
    fun `TENANT_LIMITS stays refused in production even with the fully qualified record`() {
        val found = violations(qualified.copy(enforcement = StorageEnforcement.TENANT_LIMITS))
        found.map { it.setting } shouldBe listOf(enforcement)
        found.single().problem shouldContain "dark staging qualification only"
    }

    @Test
    fun `OFF is unchanged in production - nothing about the record or the cohort is consulted`() {
        violations(qualified.copy(enforcement = StorageEnforcement.OFF)).shouldBeEmpty()
        violations(qualified.copy(enforcement = StorageEnforcement.OFF, qualificationRecords = emptyList(), node = null))
            .shouldBeEmpty()
        violations(qualified.copy(enforcement = StorageEnforcement.OFF, globalLimitBytes = 3 * gib)).shouldBeEmpty()
    }

    @Test
    fun `the refusal names settings and reasons, never a credential`() {
        val text =
            DeploymentSafety
                .describe(violations(qualified.copy(qualificationRecords = emptyList(), node = null)))
        text shouldNotContain "fixture-not-a-real-db-password--1"
        text shouldNotContain "fixture-not-a-real-s3-secret----1"
        text shouldContain enforcement
    }
}
