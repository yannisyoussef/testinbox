package email.testinbox.api.ops

import email.testinbox.api.config.TestInboxProperties
import email.testinbox.application.deployment.DeploymentSafety
import email.testinbox.application.deployment.DeploymentSettings
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.port.BlobStore
import org.slf4j.LoggerFactory
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Refuses to start a deployed API node whose configuration is a
 * local-development default, a plaintext public surface, or an ingress that
 * would cut a legitimate long poll short.
 *
 * Every one of those settings has a working local default, so a missing
 * environment variable produces no error at all — just a staging node running
 * on `testinbox/testinbox` against `localhost`. This turns that silence into a
 * startup failure. It is inert until `testinbox.deployment.environment` is
 * set, which is exactly what marks a process as deployed.
 */
@Component
class DeploymentSafetyCheck(
    properties: TestInboxProperties,
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
                        publicBaseUrl = properties.deployment.publicBaseUrl,
                        bootstrapApiKey = properties.bootstrap.apiKey,
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
        val log = LoggerFactory.getLogger(DeploymentSafetyCheck::class.java)
    }
}

/**
 * ADR-029 §4: a deployed node does not migrate, so it must not serve traffic
 * against a schema older than the one its artifact was built against.
 *
 * A schema *ahead* of this artifact is healthy — that is a rolled-back
 * artifact running against an already-migrated database, and treating it as an
 * outage would make artifact rollback impossible after any migration.
 */
@Component("schema")
class SchemaHealthIndicator(
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

/**
 * Object storage participates in readiness because raw MIME is written before
 * the DB row (ADR-005): a node that cannot reach the store cannot serve `/raw`
 * and cannot honour the raw-first ordering.
 *
 * The probe is a prefix listing that matches nothing — it exercises
 * connectivity, credentials and bucket existence without writing anything and
 * without adding a method to the `BlobStore` port for a health check's sake.
 */
@Component("objectStorage")
class ObjectStorageHealthIndicator(
    private val blobs: BlobStore,
) : HealthIndicator {
    override fun health(): Health =
        runCatching { blobs.listKeysOlderThan(PROBE_PREFIX, Instant.EPOCH) }
            .fold(
                onSuccess = { Health.up().build() },
                // Message only: an S3 exception can carry the endpoint, never credentials.
                onFailure = { Health.down().withDetail("detail", it.javaClass.simpleName).build() },
            )

    private companion object {
        /** Deliberately outside the message key space (docs/architecture/data-ownership.md). */
        const val PROBE_PREFIX = "_probe/readiness/"
    }
}
