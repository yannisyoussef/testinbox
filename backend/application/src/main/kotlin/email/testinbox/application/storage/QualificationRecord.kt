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
) {
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
}

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
