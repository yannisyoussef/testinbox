package email.testinbox.ingestion.config

import email.testinbox.application.LimitsConfig
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.LimitMetrics
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.MimeParser
import email.testinbox.application.port.RateLimiter
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.ReceiveInboundDelivery
import email.testinbox.ingestion.mime.JakartaMimeParser
import email.testinbox.observability.MicrometerLimitMetrics
import email.testinbox.persistence.JdbcRateLimiter
import email.testinbox.storage.S3BlobStore
import email.testinbox.storage.S3BlobStoreConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

@Configuration
class IngestionWiring {
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
            ),
        )

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
    fun limitMetrics(registry: io.micrometer.core.instrument.MeterRegistry): LimitMetrics = MicrometerLimitMetrics(registry)

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
    ): ReceiveInboundDelivery = ReceiveInboundDelivery(inboxes, messages, blobs, parser, transactions, rateLimiter, clock)
}
