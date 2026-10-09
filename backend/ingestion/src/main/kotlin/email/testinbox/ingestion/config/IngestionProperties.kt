package email.testinbox.ingestion.config

import email.testinbox.application.ActivationProperties
import email.testinbox.application.BackendIdentityProperties
import email.testinbox.application.LimitsProperties
import email.testinbox.application.StorageDeclarationsFactory
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.storage.QualificationRecord
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.storage.StorageEnforcement
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("testinbox")
data class IngestionProperties(
    val mailDomain: String = "testinbox.local",
    val defaultTtl: Duration = Duration.ofMinutes(15),
    val maxTtl: Duration = Duration.ofHours(24),
    val expiryGrace: Duration = Duration.ofSeconds(30),
    val exactCooldown: Duration = Duration.ofHours(24),
    val waitWindowCap: Duration = Duration.ofSeconds(60),
    val maxRawSizeBytes: Long = 15L * 1024 * 1024,
    val limits: LimitsProperties = LimitsProperties(),
    val smtp: Smtp = Smtp(),
    val storage: Storage = Storage(),
    val deployment: Deployment = Deployment(),
) {
    data class Smtp(
        val port: Int = 2525,
    )

    data class Storage(
        val endpoint: String = "http://localhost:9000",
        val region: String = "us-east-1",
        val accessKey: String = "testinbox",
        val secretKey: String = "testinbox123",
        val bucket: String = "testinbox-mime",
        /** See TestInboxProperties.Storage.createBucket. */
        val createBucket: Boolean = true,
        /**
         * ADR-035 §9: this gateway's stable node id. Its persisted ambiguity
         * occupies its write slots across restarts, so it must NOT change when
         * the process restarts. It is not a secret, and never a metric label.
         */
        val nodeId: String = "testinbox-ingestion",
        /**
         * ADR-035 §14: OFF | TENANT_LIMITS | ALL, exactly those three states.
         * OFF is the default and the only value any committed environment may
         * carry (`scripts/check-storage-enforcement-off.sh`). A non-OFF value
         * is refused at startup unless every §18 declaration below is present
         * and consistent (`DeploymentSafety`).
         */
        val enforcement: StorageEnforcement = StorageEnforcement.OFF,
        /** *G*, the global application ceiling in bytes (ADR-035 §3). Required when enforcement is not OFF. */
        val globalLimitBytes: Long? = null,
        /** OPTIONAL: the MinIO bucket quota, a secondary defence only (ADR-035 Amendment 2); never required, only checked to be positive. */
        val declaredBucketQuotaBytes: Long? = null,
        /** Ingestion processes that can run at once, deploy surge INCLUDED (§9: a rolling deploy that overlaps two must declare 2). */
        val declaredMaxIngestionProcesses: Int? = null,
        /** The inbox share of the workspace limit, decimal text in (0, 1] (§3). */
        val inboxShare: String? = null,
        /** OPTIONAL and informational since ADR-035 Amendment 2: the bytes MinIO accepted during one usage-refresh lag; if set, never negative. */
        val measuredQuotaLagChurnBytes: Long? = null,
        /** The declared storage combination, matched against the shipped qualification records (§9a). */
        val backendIdentity: BackendIdentityProperties = BackendIdentityProperties(),
        /** The node inventory the activation barrier expects (TI-STORAGE-006 §20). */
        val activation: ActivationProperties = ActivationProperties(),
        /** The dedicated filesystem's declared budgets (filesystem-containment contract, TI-STORAGE-006E). */
        val filesystem: email.testinbox.application.FilesystemProperties = email.testinbox.application.FilesystemProperties(),
    )

    /** The framework-free declarations `DeploymentSafety` and `EffectiveStoragePolicy` read (ADR-035 §18). */
    fun storageDeclarations(records: List<QualificationRecord>): StorageDeclarations =
        StorageDeclarationsFactory.from(
            enforcement = storage.enforcement,
            globalLimitBytes = storage.globalLimitBytes,
            declaredBucketQuotaBytes = storage.declaredBucketQuotaBytes,
            declaredMaxIngestionProcesses = storage.declaredMaxIngestionProcesses,
            inboxShare = storage.inboxShare,
            measuredQuotaLagChurnBytes = storage.measuredQuotaLagChurnBytes,
            backendIdentity = storage.backendIdentity,
            maxObjectBytes = maxRawSizeBytes,
            qualificationRecords = records,
            activation = storage.activation,
            nodeId = storage.nodeId,
            nodeRole = email.testinbox.application.storage.NodeRole.INGESTION,
            filesystem = storage.filesystem,
        )

    /**
     * Deployment identity (ADR-028/029). `environment` being set is what marks
     * this process as deployed and turns on the startup safety check.
     *
     * The gateway has no public HTTP surface of its own — it terminates SMTP —
     * so `publicBaseUrl` stays null here and the proxy-timeout coupling is
     * still declared, because the two deployables share a wait-window cap and
     * a misconfiguration on either side is worth catching on either side.
     */
    data class Deployment(
        val environment: String? = null,
        val proxyReadTimeout: Duration? = null,
        /**
         * Hard ceiling the environment's ingress imposes on ANY request, when
         * it has one — Cloudflare's ~100s on the deployed staging path
         * (ADR-030). No application setting can raise it, so the wait window
         * has to fit underneath it.
         */
        val edgeRequestCeiling: Duration? = null,
        val gitSha: String = "unknown",
        val imageDigest: String = "unknown",
    )

    fun toConfig(): TestInboxConfig =
        TestInboxConfig(
            mailDomain = mailDomain,
            defaultTtl = defaultTtl,
            maxTtl = maxTtl,
            expiryGrace = expiryGrace,
            exactCooldown = exactCooldown,
            waitWindowCap = waitWindowCap,
            maxRawSizeBytes = maxRawSizeBytes,
        )
}
