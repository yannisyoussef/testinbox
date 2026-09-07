package email.testinbox.api.config

import email.testinbox.application.LimitsConfig
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.port.ApiKeyRepository
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.BlobStoreMetrics
import email.testinbox.application.port.ExactAddressReservations
import email.testinbox.application.port.InboxMetrics
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.LimitMetrics
import email.testinbox.application.port.MessageNotifier
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.NotifierMetrics
import email.testinbox.application.port.RateLimiter
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.port.WaitMetrics
import email.testinbox.application.port.WaitSlots
import email.testinbox.application.port.WorkspaceQuotaState
import email.testinbox.application.query.InboxQueries
import email.testinbox.application.query.MessageQueries
import email.testinbox.application.usecase.AuthenticateApiKey
import email.testinbox.application.usecase.CreateInbox
import email.testinbox.application.usecase.DeleteInbox
import email.testinbox.application.usecase.ExpireInboxes
import email.testinbox.application.usecase.OrphanBlobSweep
import email.testinbox.application.usecase.WaitForMessage
import email.testinbox.notification.PgListenNotifier
import email.testinbox.notification.PgListenNotifierConfig
import email.testinbox.observability.BuildInfoMetric
import email.testinbox.observability.MicrometerBlobStoreMetrics
import email.testinbox.observability.MicrometerInboxMetrics
import email.testinbox.observability.MicrometerLimitMetrics
import email.testinbox.observability.MicrometerNotifierMetrics
import email.testinbox.observability.MicrometerWaitMetrics
import email.testinbox.persistence.BundledMigrations
import email.testinbox.persistence.JdbcRateLimiter
import email.testinbox.persistence.JdbcSchemaHistory
import email.testinbox.storage.S3BlobStore
import email.testinbox.storage.S3BlobStoreConfig
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

/**
 * Metric adapters, separated from `ApiWiring` only so that class can take them
 * as constructor properties rather than repeating them in the signature of
 * nearly every bean it defines.
 */
@Configuration
class ApiMetricsWiring {
    @Bean
    fun limitMetrics(registry: MeterRegistry): LimitMetrics = MicrometerLimitMetrics(registry)

    @Bean
    fun inboxMetrics(registry: MeterRegistry): InboxMetrics = MicrometerInboxMetrics(registry)

    @Bean
    fun waitMetrics(registry: MeterRegistry): WaitMetrics = MicrometerWaitMetrics(registry)

    @Bean
    fun blobStoreMetrics(registry: MeterRegistry): BlobStoreMetrics = MicrometerBlobStoreMetrics(registry)

    @Bean
    fun notifierMetrics(registry: MeterRegistry): NotifierMetrics = MicrometerNotifierMetrics(registry)

    /**
     * "Which build is this?" answered from the same place Ops reads everything
     * else. The image digest is deliberately not a label: an image cannot know
     * its own digest, and a value invented here would be a convincing lie in
     * the one metric whose whole job is identity.
     */
    @Bean
    fun buildInfoMetric(
        registry: MeterRegistry,
        properties: TestInboxProperties,
    ): BuildInfoMetric =
        BuildInfoMetric(
            registry,
            service = "testinbox-api",
            gitSha = properties.deployment.gitSha,
            version = javaClass.`package`?.implementationVersion ?: "unknown",
        )
}

/**
 * Collaborators shared by most beans are injected once here rather than
 * repeated in every factory method's parameter list — including the metric
 * ports, which nearly every use case now takes and none of which vary per
 * bean.
 */
@Configuration
class ApiWiring(
    private val clock: Clock,
    private val properties: TestInboxProperties,
    private val limitMetrics: LimitMetrics,
    private val inboxMetrics: InboxMetrics,
    private val waitMetrics: WaitMetrics,
) {
    @Bean
    fun testInboxConfig(properties: TestInboxProperties): TestInboxConfig = properties.toConfig()

    @Bean
    fun limitsConfig(properties: TestInboxProperties): LimitsConfig =
        properties.limits.toConfig().also {
            if (!it.enabled) {
                LoggerFactory
                    .getLogger(ApiWiring::class.java)
                    .warn("testinbox.limits.enabled=false — rate limits and quotas are NOT enforced")
            }
        }

    @Bean
    fun rateLimiter(
        jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
        limits: LimitsConfig,
    ): RateLimiter = JdbcRateLimiter(jdbc, transactionManager) { category, perInbox -> limits.rateFor(category, perInbox) }

    @Bean(destroyMethod = "close")
    fun blobStore(
        properties: TestInboxProperties,
        metrics: BlobStoreMetrics,
    ): BlobStore =
        S3BlobStore(
            S3BlobStoreConfig(
                endpoint = properties.storage.endpoint,
                region = properties.storage.region,
                accessKey = properties.storage.accessKey,
                secretKey = properties.storage.secretKey,
                bucket = properties.storage.bucket,
                createBucket = properties.storage.createBucket,
            ),
            metrics,
        )

    /**
     * Backs the `schema` readiness indicator and the request-time gate
     * (ADR-029 §4). Wiring is the only place this adapter meets the
     * persistence adapter (ADR-024): the policy itself lives in the
     * application layer behind the `SchemaHistory` port.
     */
    @Bean
    fun schemaCompatibility(
        jdbc: JdbcClient,
        properties: TestInboxProperties,
    ): SchemaCompatibility {
        val bundled = BundledMigrations.highest()
        // An artifact that cannot see its own migrations reports "nothing to
        // require" and waves every schema through — the guard failing open,
        // silently, in exactly the packaging (a nested Boot jar) that only a
        // real deployment exercises.
        check(bundled != null || properties.deployment.environment.isNullOrBlank()) {
            "no migrations found on the classpath: this artifact cannot verify the schema it requires (ADR-029 §4)"
        }
        return SchemaCompatibility(JdbcSchemaHistory(jdbc), bundled)
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    fun messageNotifier(
        dataSourceProperties: DataSourceProperties,
        metrics: NotifierMetrics,
    ): PgListenNotifier =
        PgListenNotifier(
            PgListenNotifierConfig(
                jdbcUrl = dataSourceProperties.determineUrl(),
                username = dataSourceProperties.determineUsername().orEmpty(),
                password = dataSourceProperties.determinePassword().orEmpty(),
            ),
            metrics,
        )

    @Bean
    fun createInbox(
        inboxes: InboxRepository,
        reservations: ExactAddressReservations,
        tx: TransactionRunner,
        quotas: WorkspaceQuotaState,
        limits: LimitsConfig,
        config: TestInboxConfig,
    ): CreateInbox = CreateInbox(inboxes, reservations, tx, quotas, limits.quotas, clock, config, limitMetrics, inboxMetrics)

    @Bean
    fun deleteInbox(
        inboxes: InboxRepository,
        reservations: ExactAddressReservations,
        tx: TransactionRunner,
        config: TestInboxConfig,
    ): DeleteInbox = DeleteInbox(inboxes, reservations, tx, clock, config, inboxMetrics)

    @Bean
    fun waitForMessage(
        inboxes: InboxRepository,
        messages: MessageRepository,
        notifier: MessageNotifier,
        waitSlots: WaitSlots,
        limits: LimitsConfig,
        config: TestInboxConfig,
    ): WaitForMessage =
        WaitForMessage(
            inboxes,
            messages,
            notifier,
            waitSlots,
            limits.quotas.maxConcurrentWaits,
            clock,
            config,
            metrics = waitMetrics,
        )

    @Bean
    fun expireInboxes(
        inboxes: InboxRepository,
        reservations: ExactAddressReservations,
        blobs: BlobStore,
        tx: TransactionRunner,
        config: TestInboxConfig,
    ): ExpireInboxes = ExpireInboxes(inboxes, reservations, blobs, tx, clock, config, inboxMetrics)

    @Bean
    fun orphanBlobSweep(
        blobs: BlobStore,
        messages: MessageRepository,
    ): OrphanBlobSweep = OrphanBlobSweep(blobs, messages, clock, properties.orphanMinAge)

    @Bean
    fun authenticateApiKey(apiKeys: ApiKeyRepository): AuthenticateApiKey = AuthenticateApiKey(apiKeys)

    @Bean
    fun inboxQueries(inboxes: InboxRepository): InboxQueries = InboxQueries(inboxes)

    @Bean
    fun messageQueries(
        messages: MessageRepository,
        blobs: BlobStore,
    ): MessageQueries = MessageQueries(messages, blobs)
}
