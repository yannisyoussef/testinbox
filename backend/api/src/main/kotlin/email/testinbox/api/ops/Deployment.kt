package email.testinbox.api.ops

import email.testinbox.api.config.TestInboxProperties
import email.testinbox.application.deployment.DatabaseSessionPolicy
import email.testinbox.application.deployment.DeploymentSafety
import email.testinbox.application.deployment.DeploymentSettings
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.port.BlobStore
import org.slf4j.LoggerFactory
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.core.env.Environment
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
    springEnvironment: Environment,
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
                        edgeRequestCeiling = properties.deployment.edgeRequestCeiling,
                        limitsEnabled = properties.limits.enabled,
                        activeProfiles = springEnvironment.activeProfiles.toSet(),
                        publicSurface = true,
                        createBucket = properties.storage.createBucket,
                        requireDatabaseSessionTimeout = properties.deployment.requireDatabaseSessionTimeout,
                    ),
                )
            check(violations.isEmpty()) { DeploymentSafety.describe(violations) }
            log.info(
                "deployment configuration validated (environment={} profiles={} gitSha={} imageDigest={})",
                environment,
                springEnvironment.activeProfiles.joinToString(","),
                properties.deployment.gitSha,
                properties.deployment.imageDigest,
            )
        }
    }

    private companion object {
        val DEPLOYED_PROFILES = setOf("deployed", "staging", "production")
        val log = LoggerFactory.getLogger(DeploymentSafetyCheck::class.java)
    }
}

/**
 * ADR-033's one deployment requirement, made observable (ADR-034): whether the
 * database bounds a hung idempotency claim at all. Read on every probe, so a
 * setting Ops changes shows up without a restart.
 *
 * Enforcement is a profile decision, not a code one. In production an
 * unbounded claim is a liveness defect and the node is OUT_OF_SERVICE until
 * Ops sets the timeout. Elsewhere the fact is reported and the node stays UP:
 * a readiness check that removed every staging node the moment it shipped
 * would be the fragile-health-check failure ADR-030 already warns about.
 */
@Component("dbSession")
class DatabaseSessionHealthIndicator(
    private val policy: DatabaseSessionPolicy,
    private val properties: TestInboxProperties,
) : HealthIndicator {
    override fun health(): Health {
        val required = properties.deployment.requireDatabaseSessionTimeout
        val status =
            runCatching { policy.status() }
                .getOrElse {
                    // The database itself is unreachable; `db` reports that. Only the type is surfaced.
                    return Health
                        .down()
                        .withDetail("detail", it.javaClass.simpleName)
                        .withDetail("enforced", required)
                        .build()
                }
        val builder = if (status.bounded || !required) Health.up() else Health.outOfService()
        return builder
            .withDetail("idleInTransactionSessionTimeout", status.raw)
            .withDetail("bounded", status.bounded)
            .withDetail("enforced", required)
            .withDetail("detail", status.detail)
            .build()
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
