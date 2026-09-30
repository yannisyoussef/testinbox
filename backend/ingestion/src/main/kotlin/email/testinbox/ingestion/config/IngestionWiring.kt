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
import email.testinbox.application.port.StorageAmbiguity
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.storage.GuardedStorage
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageNode
import email.testinbox.application.storage.StorageNodeLifecycle
import email.testinbox.application.storage.WriteSlots
import email.testinbox.application.usecase.ReceiveInboundDelivery
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.ingestion.mime.JakartaMimeParser
import email.testinbox.ingestion.ops.StorageNodeRuntime
import email.testinbox.observability.BuildInfoMetric
import email.testinbox.observability.MicrometerBlobStoreMetrics
import email.testinbox.observability.MicrometerInboundMetrics
import email.testinbox.observability.MicrometerLimitMetrics
import email.testinbox.observability.MicrometerSmtpMetrics
import email.testinbox.observability.MicrometerStorageProtocolMetrics
import email.testinbox.persistence.BundledMigrations
import email.testinbox.persistence.JdbcRateLimiter
import email.testinbox.persistence.JdbcSchemaHistory
import email.testinbox.persistence.JdbcStorageAdmission
import email.testinbox.persistence.JdbcStorageAmbiguity
import email.testinbox.persistence.JdbcStorageReservations
import email.testinbox.storage.S3BlobStore
import email.testinbox.storage.S3BlobStoreConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

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
    ): StorageAmbiguity = JdbcStorageAmbiguity(jdbc, template(transactionManager))

    @Bean
    fun storageNode(properties: IngestionProperties): StorageNode = StorageNode(properties.storage.nodeId, UUID.randomUUID())

    @Bean
    fun storageBreaker(): StorageBreaker = StorageBreaker()

    @Bean
    fun writeSlots(
        ambiguity: StorageAmbiguity,
        node: StorageNode,
    ): WriteSlots = WriteSlots(ambiguous = { ambiguity.unresolvedFor(node.nodeId) })

    @Bean
    fun storageProtocolMetrics(registry: io.micrometer.core.instrument.MeterRegistry): StorageProtocolMetrics =
        MicrometerStorageProtocolMetrics(registry, StorageCapacityPolicy.ADR_035_REFERENCE)

    /**
     * T1 with enforcement OFF, a literal. ADR-035 Phase 2: the whole protocol
     * runs and every ceiling is observed, but nothing is refused. No property,
     * environment variable or profile reaches this value (TI-STORAGE-003).
     */
    @Bean
    fun guardedStorage(
        jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
        limits: LimitsConfig,
        reservations: JdbcStorageReservations,
        ambiguity: StorageAmbiguity,
        blobs: BlobStore,
        inspection: StorageInspection,
        slots: WriteSlots,
        breaker: StorageBreaker,
        node: StorageNode,
        transactions: TransactionRunner,
        storageMetrics: StorageProtocolMetrics,
    ): GuardedStorage =
        GuardedStorage(
            admission =
                StorageAdmission(
                    JdbcStorageAdmission(jdbc, template(transactionManager)),
                    storagePolicy(limits),
                    StorageEnforcement.OFF,
                ),
            reservations = reservations,
            ambiguity = ambiguity,
            blobs = blobs,
            inspection = inspection,
            slots = slots,
            breaker = breaker,
            node = node,
            transactions = transactions,
            clock = reservations,
            metrics = storageMetrics,
        )

    @Bean
    fun storageNodeRuntime(
        ambiguity: StorageAmbiguity,
        node: StorageNode,
        breaker: StorageBreaker,
        inspection: StorageInspection,
        reservations: JdbcStorageReservations,
        storageMetrics: StorageProtocolMetrics,
    ): StorageNodeRuntime = StorageNodeRuntime(StorageNodeLifecycle(ambiguity, node), breaker, inspection, reservations, storageMetrics)

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

    companion object {
        /**
         * The observed ceilings: the workspace limit is the existing
         * `max-stored-bytes`, and the rest are the ADR-035 reference values. They
         * only feed observation while enforcement is OFF.
         */
        fun storagePolicy(limits: LimitsConfig): StorageCapacityPolicy {
            val reference = StorageCapacityPolicy.ADR_035_REFERENCE
            val workspace = limits.quotas.maxStoredBytes.coerceAtMost(reference.globalLimitBytes)
            return StorageCapacityPolicy(workspace, reference.inboxShare, reference.globalLimitBytes, reference.finalizeBudgetBytes)
        }
    }
}
