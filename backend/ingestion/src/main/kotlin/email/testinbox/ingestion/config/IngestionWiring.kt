package email.testinbox.ingestion.config

import email.testinbox.application.LimitsConfig
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.BlobStoreMetrics
import email.testinbox.application.port.InboundMetrics
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.LimitMetrics
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.MimeParser
import email.testinbox.application.port.RateLimiter
import email.testinbox.application.port.SmtpMetrics
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.GuardedStorage
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.storage.StorageNode
import email.testinbox.application.storage.StorageNodeLifecycle
import email.testinbox.application.storage.WriteSlots
import email.testinbox.application.storage.activation.ActivationGuard
import email.testinbox.application.storage.activation.ActivationWatch
import email.testinbox.application.usecase.ReceiveInboundDelivery
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.ingestion.mime.JakartaMimeParser
import email.testinbox.ingestion.ops.StorageNodeRuntime
import email.testinbox.observability.BuildInfoMetric
import email.testinbox.observability.MicrometerBlobStoreMetrics
import email.testinbox.observability.MicrometerInboundMetrics
import email.testinbox.observability.MicrometerLimitMetrics
import email.testinbox.observability.MicrometerSmtpMetrics
import email.testinbox.observability.MicrometerStorageProtocolMetrics
import email.testinbox.persistence.BundledMigrations
import email.testinbox.persistence.JdbcActivationInventory
import email.testinbox.persistence.JdbcRateLimiter
import email.testinbox.persistence.JdbcSchemaHistory
import email.testinbox.persistence.JdbcStorageAdmission
import email.testinbox.persistence.JdbcStorageAmbiguity
import email.testinbox.persistence.JdbcStorageNodeClaims
import email.testinbox.persistence.JdbcStorageReservations
import email.testinbox.storage.QualificationRecords
import email.testinbox.storage.S3BlobStore
import email.testinbox.storage.S3BlobStoreConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * Metric adapters, separated from `IngestionWiring` only so that class can take
 * them as constructor properties rather than repeating them in the signature of
 * every bean that needs one.
 */
@Configuration
class IngestionMetricsWiring {
    @Bean
    fun limitMetrics(registry: io.micrometer.core.instrument.MeterRegistry): LimitMetrics = MicrometerLimitMetrics(registry)

    @Bean
    fun inboundMetrics(registry: io.micrometer.core.instrument.MeterRegistry): InboundMetrics = MicrometerInboundMetrics(registry)

    @Bean
    fun smtpMetrics(registry: io.micrometer.core.instrument.MeterRegistry): SmtpMetrics = MicrometerSmtpMetrics(registry)

    @Bean
    fun blobStoreMetrics(registry: io.micrometer.core.instrument.MeterRegistry): BlobStoreMetrics = MicrometerBlobStoreMetrics(registry)

    /** See the API's equivalent: build identity, never a fabricated digest. */
    @Bean
    fun buildInfoMetric(
        registry: io.micrometer.core.instrument.MeterRegistry,
        properties: IngestionProperties,
    ): BuildInfoMetric =
        BuildInfoMetric(
            registry,
            service = "testinbox-ingestion",
            gitSha = properties.deployment.gitSha,
            version = javaClass.`package`?.implementationVersion ?: "unknown",
        )
}

@Configuration
class IngestionWiring(
    private val limitMetrics: LimitMetrics,
    private val inboundMetrics: InboundMetrics,
    private val blobStoreMetrics: BlobStoreMetrics,
) {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun testInboxConfig(properties: IngestionProperties): TestInboxConfig = properties.toConfig()

    @Bean(destroyMethod = "close")
    fun blobStore(properties: IngestionProperties): BlobStore =
        S3BlobStore(
            S3BlobStoreConfig(
                endpoint = properties.storage.endpoint,
                region = properties.storage.region,
                accessKey = properties.storage.accessKey,
                secretKey = properties.storage.secretKey,
                bucket = properties.storage.bucket,
                createBucket = properties.storage.createBucket,
            ),
            blobStoreMetrics,
        )

    /**
     * Backs the `schema` readiness indicator (ADR-029 §4). Wiring is the only
     * place this adapter meets the persistence adapter (ADR-024): the policy
     * itself lives in the application layer behind the `SchemaHistory` port.
     */
    @Bean
    fun schemaCompatibility(
        jdbc: JdbcClient,
        properties: IngestionProperties,
    ): SchemaCompatibility {
        val bundled = BundledMigrations.highest()
        // An artifact that cannot see its own migrations reports "nothing to
        // require" and waves every schema through — the guard failing open,
        // silently, in exactly the packaging (a nested Boot jar) that only a
        // real deployment exercises. Locally there is nothing to protect, so
        // this only bites where it matters.
        check(bundled != null || properties.deployment.environment.isNullOrBlank()) {
            "no migrations found on the classpath: this artifact cannot verify the schema it requires (ADR-029 §4)"
        }
        return SchemaCompatibility(JdbcSchemaHistory(jdbc), bundled)
    }

    @Bean
    fun limitsConfig(properties: IngestionProperties): LimitsConfig =
        properties.limits.toConfig().also {
            if (!it.enabled) {
                // Worth more here than on the API: an INGEST refusal is invisible
                // by design, so a silently disabled limiter looks identical to a
                // working one from every direction.
                org.slf4j.LoggerFactory
                    .getLogger(IngestionWiring::class.java)
                    .warn("testinbox.limits.enabled=false — inbound rate limits are NOT enforced")
            }
        }

    @Bean
    fun rateLimiter(
        jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
        limits: LimitsConfig,
    ): RateLimiter = JdbcRateLimiter(jdbc, transactionManager) { category, perInbox -> limits.rateFor(category, perInbox) }

    @Bean
    fun mimeParser(): MimeParser = JakartaMimeParser()

    // --- ADR-035 guarded ingest protocol (TI-STORAGE-003) ------------------------------------------

    @Bean
    fun storageInspection(blobs: BlobStore): StorageInspection = (blobs as S3BlobStore).inspection()

    /** Each adapter owns its transactions explicitly (READ COMMITTED is stated in the SQL). */
    private fun template(transactionManager: PlatformTransactionManager) = TransactionTemplate(transactionManager)

    @Bean
    fun storageReservations(
        jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
    ): JdbcStorageReservations = JdbcStorageReservations(jdbc, template(transactionManager))

    @Bean
    fun storageAmbiguity(
        jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
    ): JdbcStorageAmbiguity = JdbcStorageAmbiguity(jdbc, template(transactionManager))

    @Bean
    fun storageNode(properties: IngestionProperties): StorageNode = StorageNode(properties.storage.nodeId, UUID.randomUUID())

    @Bean
    fun storageBreaker(): StorageBreaker = StorageBreaker()

    @Bean
    fun writeSlots(
        ambiguity: JdbcStorageAmbiguity,
        node: StorageNode,
    ): WriteSlots = WriteSlots(ambiguous = { ambiguity.unresolvedFor(node.nodeId) })

    /**
     * ADR-035 §18: what this deployment declares, with the qualification
     * records shipped in this artifact. `IngestionDeploymentSafetyCheck` has
     * already refused a non-OFF value that is incomplete or unqualified by the
     * time this bean exists; nothing below re-checks, and nothing can widen it.
     */
    @Bean
    fun storageDeclarations(properties: IngestionProperties): StorageDeclarations =
        properties.storageDeclarations(QualificationRecords.load())

    /** The ONE effective policy (ADR-035 §3), the same factory the API uses for `limitBytes`. */
    @Bean
    fun storageCapacityPolicy(
        limits: LimitsConfig,
        declarations: StorageDeclarations,
    ): StorageCapacityPolicy = EffectiveStoragePolicy.of(limits, declarations)

    /** The EFFECTIVE G and H, and the effective mode, so Ops reads what admission really applies. */
    @Bean
    fun storageProtocolMetrics(
        registry: io.micrometer.core.instrument.MeterRegistry,
        policy: StorageCapacityPolicy,
        declarations: StorageDeclarations,
    ): StorageProtocolMetrics = MicrometerStorageProtocolMetrics(registry, policy, declarations.enforcement)

    /** TI-STORAGE-006 §22: the in-process fail-closed guard a broken activation invariant sets on a non-OFF node. */
    @Bean
    fun activationGuard(): ActivationGuard = ActivationGuard()

    /**
     * ADR-035 §14 Phase 4: this node re-runs the allowlist and inventory checks
     * on its heartbeat cadence. OFF observes; TENANT_LIMITS and ALL fail closed
     * through [activationGuard].
     */
    @Bean
    fun activationWatch(
        jdbc: JdbcClient,
        properties: IngestionProperties,
        declarations: StorageDeclarations,
        guard: ActivationGuard,
        storageMetrics: StorageProtocolMetrics,
    ): ActivationWatch =
        ActivationWatch(
            JdbcActivationInventory(jdbc),
            properties.storage.activation.toExpectedNodes(),
            declarations.enforcement,
            guard,
            storageMetrics,
        )

    /**
     * T1 with the deployment's enforcement mode (ADR-035 §14). OFF, the
     * default and the only committed value, observes every ceiling and refuses
     * nothing (Phase 2). A non-OFF value reaches here only after
     * `DeploymentSafety` proved every §18 declaration present and the backend
     * qualified (TI-STORAGE-006).
     *
     * The ceilings it decides against are [EffectiveStoragePolicy]'s: the same
     * formula the API applies for `limitBytes` (TI-STORAGE-004). Each deployable
     * reads its own `max-stored-bytes` and declarations, so the two agree
     * exactly when their configuration does; a split configuration is an
     * operations error, not a code path.
     */
    @Bean
    fun storageAdmission(
        jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
        policy: StorageCapacityPolicy,
        declarations: StorageDeclarations,
    ): StorageAdmission =
        StorageAdmission(
            JdbcStorageAdmission(jdbc, template(transactionManager)),
            policy,
            declarations.enforcement,
        )

    /** A @Bean method's parameters are its dependencies: one per protocol collaborator. */
    @Bean
    @Suppress("LongParameterList")
    fun guardedStorage(
        admission: StorageAdmission,
        reservations: JdbcStorageReservations,
        ambiguity: JdbcStorageAmbiguity,
        blobs: BlobStore,
        inspection: StorageInspection,
        slots: WriteSlots,
        breaker: StorageBreaker,
        node: StorageNode,
        transactions: TransactionRunner,
        storageMetrics: StorageProtocolMetrics,
        activation: ActivationGuard,
    ): GuardedStorage =
        GuardedStorage(
            admission = admission,
            reservations = reservations,
            ambiguity = ambiguity,
            latch = ambiguity,
            blobs = blobs,
            inspection = inspection,
            slots = slots,
            breaker = breaker,
            node = node,
            transactions = transactions,
            clock = reservations,
            metrics = storageMetrics,
            activation = activation,
        )

    /** A @Bean method's parameters are its dependencies: one per runtime collaborator. */
    @Bean
    @Suppress("LongParameterList")
    fun storageNodeRuntime(
        ambiguity: JdbcStorageAmbiguity,
        node: StorageNode,
        breaker: StorageBreaker,
        inspection: StorageInspection,
        reservations: JdbcStorageReservations,
        storageMetrics: StorageProtocolMetrics,
        slots: WriteSlots,
        dataSource: DataSource,
        activationWatch: ActivationWatch,
    ): StorageNodeRuntime =
        StorageNodeRuntime(
            StorageNodeLifecycle(ambiguity, node),
            breaker,
            inspection,
            reservations,
            storageMetrics,
            claims = JdbcStorageNodeClaims(dataSource),
            slots = slots,
            reservations = reservations,
            activation = activationWatch,
        )

    @Bean
    fun receiveInboundDelivery(
        inboxes: InboxRepository,
        messages: MessageRepository,
        storage: GuardedStorage,
        parser: MimeParser,
        rateLimiter: RateLimiter,
        clock: Clock,
    ): ReceiveInboundDelivery =
        ReceiveInboundDelivery(
            inboxes,
            messages,
            storage,
            parser,
            rateLimiter,
            clock,
            metrics = limitMetrics,
            inboundMetrics = inboundMetrics,
        )
}
