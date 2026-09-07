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
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.ReceiveInboundDelivery
import email.testinbox.ingestion.mime.JakartaMimeParser
import email.testinbox.observability.BuildInfoMetric
import email.testinbox.observability.MicrometerBlobStoreMetrics
import email.testinbox.observability.MicrometerInboundMetrics
import email.testinbox.observability.MicrometerLimitMetrics
import email.testinbox.observability.MicrometerSmtpMetrics
import email.testinbox.persistence.BundledMigrations
import email.testinbox.persistence.JdbcRateLimiter
import email.testinbox.persistence.JdbcSchemaHistory
import email.testinbox.storage.S3BlobStore
import email.testinbox.storage.S3BlobStoreConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

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

    @Bean
    fun receiveInboundDelivery(
        inboxes: InboxRepository,
        messages: MessageRepository,
        blobs: BlobStore,
        parser: MimeParser,
        transactions: TransactionRunner,
        rateLimiter: RateLimiter,
        clock: Clock,
    ): ReceiveInboundDelivery =
        ReceiveInboundDelivery(
            inboxes,
            messages,
            blobs,
            parser,
            transactions,
            rateLimiter,
            clock,
            metrics = limitMetrics,
            inboundMetrics = inboundMetrics,
        )
}
