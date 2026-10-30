package email.testinbox.application.storage

/**
 * One machine-readable A_F qualification record (ADR-035 §9a), shipped inside
 * the artifact. It IS the compatibility contract: the exact combination the
 * qualification procedure ran against, plus what it covered and whether it
 * may enable enforcement.
 *
 * Framework-free: the storage adapter parses the JSON files and builds these;
 * `DeploymentSafety` only compares.
 */
data class QualificationRecord(
    val recordId: String,
    val qualifiedAt: String,
    val identity: StorageBackendIdentity,
    val uploadImplementationVersion: String,
    val platformClass: String,
    val evidenceReference: String,
    val scenarios: List<String>,
    val slowWExecuted: Boolean,
    /** As the record states it. [eligibility] re-derives it from the record's own content and never widens it. */
    val enablementEligible: Boolean,
    val ineligibilityReasons: List<String>,
    /** Where the record came from: shipped in the artifact, or the lab-only file (issue #102). */
    val source: QualificationRecordSource = QualificationRecordSource.SHIPPED,
    /**
     * The ADR-035 Amendment 2 filesystem elements (contract §9): the qualified
     * filesystem, the measured *O_max* and E7 metadata inodes, and the E1–E11
     * results. Optional in the format: records issued before Amendment 2 carry
     * none, and they are then never production evidence ([productionGaps]).
     */
    val filesystem: QualifiedFilesystem? = null,
) {
    /** The platform class a lab-only record must declare; a shipped record with it is never eligible. */
    val isLab: Boolean get() = platformClass.equals(LAB_PLATFORM_CLASS, ignoreCase = true)

    /**
     * Whether this record may back an enforcing deployment. The record's own
     * flag is necessary, not sufficient: a record that claims eligibility
     * while missing a contract element, or without `slow-W`, is corrected
     * here (§9a procedure step 5; §18 gate 7a), never trusted.
     */
    fun eligibility(): QualificationEligibility {
        val reasons = mutableListOf<String>()
        reasons += ineligibilityReasons
        if (!enablementEligible && reasons.isEmpty()) reasons += "the record declares itself not enablement-eligible"
        if (!slowWExecuted) reasons += "slow-W was not executed (ADR-035 §9a)"
        if (slowWExecuted &&
            scenarios.none { it.startsWith("slow-") }
        ) {
            reasons += "slow-W is claimed executed but no slow-* scenario is listed"
        }
        if (platformClass.equals("laptop", ignoreCase = true)) {
            reasons += "platformClass is laptop: ADR-035 §18 gate 7a requires the deployed host's own combination"
        }
        if (isLab) {
            reasons +=
                "platformClass is lab: a lab record is a startup gate for the isolated lab only, never enablement evidence (issue #102)"
        }
        if (identity.runtimeConfigHash.isNullOrBlank()) reasons += "the runtime admin-configuration hash is absent"
        identity
            .elements()
            .filterValues { it.isNullOrBlank() }
            .keys
            .filterNot { it == "minio.runtimeConfig.hash" }
            .forEach { reasons += "contract element '$it' is absent from the record" }
        if (uploadImplementationVersion.isBlank()) reasons += "the upload implementation version is absent from the record"
        return QualificationEligibility(enablementEligible && reasons.isEmpty(), reasons.distinct())
    }

    /**
     * Why this record, on top of [eligibility], cannot back `ALL` in production
     * (TI-DELIVERY-002b): ADR-035 §18 gate 7a requires the production host to be
     * qualified on ITS OWN combination, and Amendment 2 §A2.4/§A2.6 require the
     * record to carry the filesystem elements, the measured *O_max* and E7
     * inode figure, and E1–E11 as PASS. Empty means none of those reasons
     * applies; it never means "eligible" on its own.
     */
    fun productionGaps(): List<String> =
        buildList {
            if (source != QualificationRecordSource.SHIPPED) add("it is not shipped in the artifact")
            if (platformClass != PRODUCTION_PLATFORM_CLASS) {
                add(
                    "platformClass is '$platformClass', not '$PRODUCTION_PLATFORM_CLASS': ADR-035 §18 gate 7a requires the " +
                        "production host's own combination",
                )
            }
            val fs = filesystem
            if (fs == null) {
                add("it carries no filesystem elements (ADR-035 Amendment 2 §A2.4: re-issue it after E1–E11 on the production filesystem)")
                return@buildList
            }
            listOf("uuid" to fs.uuid, "type" to fs.type, "mountOptions" to fs.mountOptions, "mountSource" to fs.mountSource)
                .filter { it.second.isBlank() }
                .forEach { add("filesystem.${it.first} is absent") }
            if (fs.objectOverheadMaxBytes == null || fs.objectOverheadMaxBytes <= 0) {
                add("filesystem.objectOverheadMaxBytes (the measured O_max, E1–E3) is absent")
            }
            if (fs.metadataInodesMeasured == null || fs.metadataInodesMeasured <= 0) {
                add("filesystem.metadataInodesMeasured (E7) is absent")
            }
            val missing = REQUIRED_EXPERIMENTS.filter { fs.experiments[it] != PASS }
            if (missing.isNotEmpty()) add("experiments ${missing.joinToString(", ")} are not recorded as PASS")
            // The record states its filesystem twice: in the matched identity and in the
            // Amendment 2 elements. Two different answers are a contradiction, never a choice.
            if (fs.type.isNotBlank() && fs.type != identity.filesystemType) {
                add("filesystem.type '${fs.type}' contradicts host.filesystemType '${identity.filesystemType}'")
            }
            if (fs.mountOptions.isNotBlank() && fs.mountOptions != identity.mountOptions) {
                add("filesystem.mountOptions '${fs.mountOptions}' contradicts host.mountOptions '${identity.mountOptions}'")
            }
        }

    companion object {
        /** The platform class of a record qualified on the production host itself (ADR-035 §18 gate 7a). */
        const val PRODUCTION_PLATFORM_CLASS = "production-host"

        /** Amendment 2 §A2.6: every experiment of annex §10 must be recorded as PASS before `ALL`. */
        val REQUIRED_EXPERIMENTS: List<String> = (1..11).map { "E$it" }

        const val PASS = "PASS"
    }
}

/**
 * The Amendment 2 filesystem elements of a qualification record, as
 * `scripts/check-storage-activation.sh` gate F reads them (`filesystem.*`).
 * Values are copied from Ops evidence; nothing here is a secret.
 */
data class QualifiedFilesystem(
    val uuid: String,
    val type: String,
    val mountOptions: String,
    val mountSource: String,
    /** The measured *O_max* (E1–E3); a deployment must declare at least this. */
    val objectOverheadMaxBytes: Long?,
    /** The E7 metadata inode measurement. */
    val metadataInodesMeasured: Long?,
    /** `E1` … `E11` → `PASS` (or whatever the run recorded). */
    val experiments: Map<String, String>,
)

/** Where a [QualificationRecord] was loaded from. */
enum class QualificationRecordSource {
    /** Bundled in the artifact, listed in `adr035-qualification/index.txt`. */
    SHIPPED,

    /**
     * Read from `testinbox.storage.qualification.lab-record-path` (issue
     * #102): accepted ONLY when the declared environment is `lab`, and only
     * as a startup gate — it proves nothing and is never enablement evidence.
     */
    LAB,
}

const val LAB_PLATFORM_CLASS = "lab"

data class QualificationEligibility(
    val eligible: Boolean,
    val reasons: List<String>,
)

/** How one record compares with a declared identity: the elements that differ, by name. */
data class QualificationMismatch(
    val recordId: String,
    val mismatchedElements: List<String>,
) {
    val matches: Boolean get() = mismatchedElements.isEmpty()
}

/**
 * Matches a declared backend identity against the shipped records
 * (ADR-035 §9a: "that declaration exactly equals a qualification record").
 * Every element is compared exactly; the runtime-configuration hash must be
 * present on both sides. The upload implementation version compared is the
 * RUNNING binary's, not a declared one.
 */
object QualificationMatch {
    const val UPLOAD_IMPLEMENTATION = "uploadImplementationVersion"

    fun compare(
        declared: StorageBackendIdentity,
        uploadImplementationVersion: String,
        record: QualificationRecord,
    ): QualificationMismatch {
        val mine = declared.elements()
        val theirs = record.identity.elements()
        val mismatched =
            mine.keys
                .filter { element ->
                    val a = mine[element]
                    val b = theirs[element]
                    a.isNullOrBlank() || b.isNullOrBlank() || a != b
                }.toMutableList()
        if (record.uploadImplementationVersion != uploadImplementationVersion) mismatched += UPLOAD_IMPLEMENTATION
        return QualificationMismatch(record.recordId, mismatched)
    }

    /** The first exactly matching record, or null. Mismatches of every record are available through [compareAll]. */
    fun find(
        declared: StorageBackendIdentity,
        uploadImplementationVersion: String,
        records: List<QualificationRecord>,
    ): QualificationRecord? = records.firstOrNull { compare(declared, uploadImplementationVersion, it).matches }

    fun compareAll(
        declared: StorageBackendIdentity,
        uploadImplementationVersion: String,
        records: List<QualificationRecord>,
    ): List<QualificationMismatch> = records.map { compare(declared, uploadImplementationVersion, it) }
}
