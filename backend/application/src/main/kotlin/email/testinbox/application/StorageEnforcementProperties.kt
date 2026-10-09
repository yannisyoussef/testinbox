package email.testinbox.application

import email.testinbox.application.storage.DeclaredNode
import email.testinbox.application.storage.NodeRole
import email.testinbox.application.storage.QualificationRecord
import email.testinbox.application.storage.StorageBackendIdentity
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.storage.activation.ExpectedNodes
import email.testinbox.domain.storage.StorageEnforcement

/**
 * The declared storage backend combination, as bound from
 * `testinbox.storage.backend-identity.*` (ADR-035 §9a). Plain data, no
 * framework: both deployables bind it and hand it to `DeploymentSafety`.
 * Every member is nullable so an undeclared identity is `null` as a whole
 * rather than a half-filled one that happens to match nothing.
 */
data class BackendIdentityProperties(
    val imageIndexDigest: String? = null,
    val platformMemberDigest: String? = null,
    val release: String? = null,
    val commitId: String? = null,
    val mode: String? = null,
    val driveCount: Int? = null,
    val timeoutEnvironment: Map<String, String> = emptyMap(),
    val timeoutCliFlags: List<String> = emptyList(),
    val runtimeConfigHash: String? = null,
    val kernelRelease: String? = null,
    val filesystemType: String? = null,
    val mountOptions: String? = null,
    val storageClassInlineDefaults: String? = null,
    val directPath: Boolean? = null,
    val proxy: String? = null,
) {
    /** Declared at all? A single member is enough to count as a (possibly incomplete) declaration. */
    val declared: Boolean
        get() =
            listOf(
                imageIndexDigest,
                platformMemberDigest,
                release,
                commitId,
                mode,
                driveCount,
                runtimeConfigHash,
                kernelRelease,
                filesystemType,
                mountOptions,
                storageClassInlineDefaults,
                directPath,
                proxy,
            ).any { it != null } ||
                timeoutEnvironment.isNotEmpty() ||
                timeoutCliFlags.isNotEmpty()

    /**
     * The identity, with every absent member rendered as an empty string so
     * the match reports it as a differing element instead of failing to build.
     */
    fun toIdentity(): StorageBackendIdentity =
        StorageBackendIdentity(
            imageIndexDigest = imageIndexDigest.orEmpty(),
            platformMemberDigest = platformMemberDigest.orEmpty(),
            release = release.orEmpty(),
            commitId = commitId.orEmpty(),
            mode = mode.orEmpty(),
            driveCount = driveCount ?: 0,
            timeoutEnvironment = timeoutEnvironment,
            timeoutCliFlags = timeoutCliFlags,
            runtimeConfigHash = runtimeConfigHash,
            kernelRelease = kernelRelease.orEmpty(),
            filesystemType = filesystemType.orEmpty(),
            mountOptions = mountOptions.orEmpty(),
            storageClassInlineDefaults = storageClassInlineDefaults.orEmpty(),
            directPath = directPath ?: false,
            proxy = proxy.orEmpty(),
        )
}

/**
 * `testinbox.storage.activation.*`: the node inventory a deployment DECLARES
 * (TI-STORAGE-006 §20). Exact node ids per role; never inferred from what is
 * heartbeating.
 */
data class ActivationProperties(
    val expectedApiNodes: List<String> = emptyList(),
    val expectedIngestionNodes: List<String> = emptyList(),
) {
    fun toExpectedNodes(): ExpectedNodes =
        ExpectedNodes(
            api = expectedApiNodes.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            ingestion = expectedIngestionNodes.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
}

/**
 * `testinbox.storage.filesystem.*`: the dedicated filesystem's declared
 * budgets (filesystem-containment contract §1; TI-STORAGE-006E). Declarations
 * only: the Ops checker verifies the filesystem (gate F).
 */
data class FilesystemProperties(
    val blockSizeBytes: Long? = null,
    val objectOverheadMaxBytes: Long? = null,
    val globalFootprintLimitBytes: Long? = null,
    val deletionDebtBudgetBytes: Long? = null,
    val metadataBudgetBytes: Long? = null,
    val operationalReserveBytes: Long? = null,
    val capacityBytes: Long? = null,
    val inodes: Long? = null,
    val observationMaxAge: java.time.Duration? = null,
) {
    fun toDeclarations(): email.testinbox.application.storage.FilesystemDeclarations =
        email.testinbox.application.storage.FilesystemDeclarations(
            blockSizeBytes = blockSizeBytes,
            objectOverheadMaxBytes = objectOverheadMaxBytes,
            globalFootprintLimitBytes = globalFootprintLimitBytes,
            deletionDebtBudgetBytes = deletionDebtBudgetBytes,
            metadataBudgetBytes = metadataBudgetBytes,
            operationalReserveBytes = operationalReserveBytes,
            capacityBytes = capacityBytes,
            inodes = inodes,
            observationMaxAge = observationMaxAge,
        )
}

/**
 * Builds the framework-free [StorageDeclarations] from the values both
 * deployables bind under `testinbox.storage.*`. One function, so the API and
 * the gateway cannot interpret the same keys differently.
 */
object StorageDeclarationsFactory {
    @Suppress("LongParameterList") // one parameter per declared key; a wrapper would hide which key is which
    fun from(
        enforcement: StorageEnforcement,
        globalLimitBytes: Long?,
        declaredBucketQuotaBytes: Long?,
        declaredMaxIngestionProcesses: Int?,
        inboxShare: String?,
        measuredQuotaLagChurnBytes: Long?,
        backendIdentity: BackendIdentityProperties?,
        maxObjectBytes: Long,
        qualificationRecords: List<QualificationRecord>,
        activation: ActivationProperties = ActivationProperties(),
        nodeId: String? = null,
        nodeRole: NodeRole? = null,
        filesystem: FilesystemProperties = FilesystemProperties(),
    ): StorageDeclarations =
        StorageDeclarations(
            enforcement = enforcement,
            globalLimitBytes = globalLimitBytes,
            declaredBucketQuotaBytes = declaredBucketQuotaBytes,
            declaredMaxIngestionProcesses = declaredMaxIngestionProcesses,
            inboxShare = inboxShare?.takeIf { it.isNotBlank() },
            measuredQuotaLagChurnBytes = measuredQuotaLagChurnBytes,
            maxObjectBytes = maxObjectBytes,
            backendIdentity = backendIdentity?.takeIf { it.declared }?.toIdentity(),
            qualificationRecords = qualificationRecords,
            expectedIngestionNodes = activation.toExpectedNodes().ingestion,
            expectedApiNodes = activation.toExpectedNodes().api,
            node = if (nodeId != null && nodeRole != null) DeclaredNode(nodeId, nodeRole) else null,
            filesystem = filesystem.toDeclarations(),
        )
}
