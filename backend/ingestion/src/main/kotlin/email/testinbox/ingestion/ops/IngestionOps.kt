package email.testinbox.ingestion.ops

import com.zaxxer.hikari.HikariConfigMXBean
import email.testinbox.application.deployment.DeploymentSafety
import email.testinbox.application.deployment.DeploymentSettings
import email.testinbox.application.deployment.IngestionPoolSize
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.port.BlobStore
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.ingestion.config.IngestionProperties
import email.testinbox.ingestion.smtp.SmtpGateway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.time.Instant
import javax.sql.DataSource

/**
 * The gateway's half of the deployed-configuration guard (see the API's
 * `DeploymentSafetyCheck`). The two deployables are separate processes with
 * separate environments, so each validates its own — a gateway pointed at a
 * local MinIO while the API is pointed at staging is a real and otherwise
 * silent misconfiguration.
 */
@Component
class IngestionDeploymentSafetyCheck(
    properties: IngestionProperties,
    dataSourceProperties: DataSourceProperties,
    springEnvironment: Environment,
    /** Built by the wiring from the properties and the records shipped in this artifact (ADR-035 §9a). */
    storage: StorageDeclarations,
    /** The pool T1 runs on; its effective size is checked against the approved INGEST envelope under ALL. */
    dataSource: ObjectProvider<DataSource>,
) {
    init {
        val environment = properties.deployment.environment?.takeIf { it.isNotBlank() }
        val deployedProfiles = springEnvironment.activeProfiles.toSet().intersect(DEPLOYED_PROFILES)
        // A deployed profile with no environment name would skip every check
        // below — a blank TESTINBOX_ENVIRONMENT must be a refusal, not a bypass.
        check(environment != null || deployedProfiles.isEmpty()) {
            "Refusing to start: deployed configuration is unsafe.\n  - testinbox.deployment.environment is not set " +
                "although profile '${deployedProfiles.sorted().joinToString(",")}' is active"
        }
        // ADR-035 §14: see the API's DeploymentSafetyCheck — a non-OFF value is
        // validated even without an environment name, and refused for it.
        val enforced = properties.storage.enforcement != email.testinbox.domain.storage.StorageEnforcement.OFF
        if (environment != null || enforced) {
            val violations =
                DeploymentSafety.validate(
                    DeploymentSettings(
                        environment = environment.orEmpty(),
                        mailDomain = properties.mailDomain,
                        // A URL Spring cannot determine (no value, no embedded database) is "not set".
                        databaseUrl = runCatching { dataSourceProperties.determineUrl() }.getOrNull().orEmpty(),
                        databaseUsername = runCatching { dataSourceProperties.determineUsername() }.getOrNull().orEmpty(),
                        databasePassword = runCatching { dataSourceProperties.determinePassword() }.getOrNull().orEmpty(),
                        storageEndpoint = properties.storage.endpoint,
                        storageAccessKey = properties.storage.accessKey,
                        storageSecretKey = properties.storage.secretKey,
                        // The gateway terminates SMTP; it has no public HTTP origin.
                        publicBaseUrl = null,
                        bootstrapApiKey = null,
                        waitWindowCap = properties.waitWindowCap,
                        proxyReadTimeout = properties.deployment.proxyReadTimeout,
                        edgeRequestCeiling = properties.deployment.edgeRequestCeiling,
                        limitsEnabled = properties.limits.enabled,
                        ingest = properties.limits.ingest,
                        ingestPerInbox = properties.limits.ingestPerInbox,
                        activeProfiles = springEnvironment.activeProfiles.toSet(),
                        // No public HTTP origin, so the production base-URL rule does not apply here.
                        publicSurface = false,
                        createBucket = properties.storage.createBucket,
                        storage = storage,
                        // The gateway runs T1, whose insert and COMMIT share one round trip (gate D A′):
                        // the driver properties Hikari passes to pgJDBC, read from the same keys it binds.
                        databaseDriverProperties =
                            Binder
                                .get(springEnvironment)
                                .bind(HIKARI_DRIVER_PROPERTIES, Bindable.mapOf(String::class.java, String::class.java))
                                .orElse(emptyMap()),
                        // ADR-035 amendment D: T1's latency under ALL is evidence for one pool size only.
                        ingestionPoolSize = poolSize(runCatching { dataSource.ifAvailable }.getOrNull()),
                    ),
                )
            check(violations.isEmpty()) { DeploymentSafety.describe(violations) }
            log.info(
                "deployment configuration validated (environment={} profiles={} gitSha={} imageDigest={} storageEnforcement={} " +
                    "admissionLocking={})",
                environment,
                springEnvironment.activeProfiles.joinToString(","),
                properties.deployment.gitSha,
                properties.deployment.imageDigest,
                properties.storage.enforcement,
                properties.storage.admissionLocking,
            )
        }
    }

    companion object {
        /**
         * The pool's effective `maximumPoolSize`, as Hikari reports it. Never
         * guessed: a pool that is not Hikari, or a size Hikari would replace with
         * its own default when it starts, is [IngestionPoolSize.Unreadable].
         */
        fun poolSize(dataSource: DataSource?): IngestionPoolSize {
            if (dataSource == null) return IngestionPoolSize.Unreadable("no DataSource")
            val hikari =
                runCatching {
                    dataSource.takeIf { it.isWrapperFor(HikariConfigMXBean::class.java) }?.unwrap(HikariConfigMXBean::class.java)
                }.getOrNull() ?: return IngestionPoolSize.Unreadable("the DataSource is not a Hikari pool")
            val size = hikari.maximumPoolSize
            return if (size >= 1) {
                IngestionPoolSize.Known(size)
            } else {
                IngestionPoolSize.Unreadable("Hikari maximumPoolSize $size, which Hikari replaces with its own default at start")
            }
        }

        private val DEPLOYED_PROFILES = setOf("deployed", "staging", "production")
        private const val HIKARI_DRIVER_PROPERTIES = "spring.datasource.hikari.data-source-properties"
        private val log = LoggerFactory.getLogger(IngestionDeploymentSafetyCheck::class.java)
    }
}

/** ADR-029 §4, gateway side: do not accept mail against a schema this artifact predates. */
@Component("schema")
class IngestionSchemaHealthIndicator(
    private val schema: SchemaCompatibility,
) : HealthIndicator {
    override fun health(): Health {
        val status = schema.status()
        val builder = if (status.compatible) Health.up() else Health.outOfService()
        return builder
            .withDetail("bundled", status.bundled ?: "none")
            .withDetail("applied", status.applied ?: "none")
            .withDetail("detail", status.detail)
            .build()
    }
}

/** Raw MIME is written to object storage before the DB row (ADR-005) — no store, no ingestion. */
@Component("objectStorage")
class IngestionObjectStorageHealthIndicator(
    private val blobs: BlobStore,
) : HealthIndicator {
    override fun health(): Health =
        runCatching { blobs.listKeysOlderThan(PROBE_PREFIX, Instant.EPOCH) }
            .fold(
                onSuccess = { Health.up().build() },
                onFailure = { Health.down().withDetail("detail", it.javaClass.simpleName).build() },
            )

    private companion object {
        const val PROBE_PREFIX = "_probe/readiness/"
    }
}

/**
 * The listener socket is the gateway's entire reason to exist, and it is bound
 * by a `SmartLifecycle` rather than by the web server — so a context that came
 * up with a failed or stopped SMTP listener would otherwise report a perfectly
 * healthy actuator on a process accepting no mail.
 */
@Component("smtpListener")
class SmtpListenerHealthIndicator(
    private val gateway: SmtpGateway,
    private val properties: IngestionProperties,
) : HealthIndicator {
    override fun health(): Health {
        val builder = if (gateway.isRunning) Health.up() else Health.down()
        return builder.withDetail("port", properties.smtp.port).build()
    }
}
