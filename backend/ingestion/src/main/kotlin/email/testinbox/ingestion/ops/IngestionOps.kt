package email.testinbox.ingestion.ops

import email.testinbox.application.deployment.DeploymentSafety
import email.testinbox.application.deployment.DeploymentSettings
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.port.BlobStore
import email.testinbox.ingestion.config.IngestionProperties
import email.testinbox.ingestion.smtp.SmtpGateway
import org.slf4j.LoggerFactory
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.stereotype.Component
import java.time.Instant

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
) {
    init {
        val environment = properties.deployment.environment?.takeIf { it.isNotBlank() }
        if (environment != null) {
            val violations =
                DeploymentSafety.validate(
                    DeploymentSettings(
                        environment = environment,
                        mailDomain = properties.mailDomain,
                        databaseUrl = dataSourceProperties.determineUrl().orEmpty(),
                        databaseUsername = dataSourceProperties.determineUsername().orEmpty(),
                        databasePassword = dataSourceProperties.determinePassword().orEmpty(),
                        storageEndpoint = properties.storage.endpoint,
                        storageAccessKey = properties.storage.accessKey,
                        storageSecretKey = properties.storage.secretKey,
                        // The gateway terminates SMTP; it has no public HTTP origin.
                        publicBaseUrl = null,
                        bootstrapApiKey = null,
                        waitWindowCap = properties.waitWindowCap,
                        proxyReadTimeout = properties.deployment.proxyReadTimeout,
                        limitsEnabled = properties.limits.enabled,
                    ),
                )
            check(violations.isEmpty()) { DeploymentSafety.describe(violations) }
            log.info(
                "deployment configuration validated (environment={} gitSha={} imageDigest={})",
                environment,
                properties.deployment.gitSha,
                properties.deployment.imageDigest,
            )
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(IngestionDeploymentSafetyCheck::class.java)
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
            .withDetail("bundled", status.bundled?.raw ?: "none")
            .withDetail("applied", status.applied?.raw ?: "none")
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
