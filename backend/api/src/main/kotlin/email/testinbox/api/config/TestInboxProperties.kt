package email.testinbox.api.config

import email.testinbox.application.LimitsProperties
import email.testinbox.application.TestInboxConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.util.UUID

@ConfigurationProperties("testinbox")
data class TestInboxProperties(
    val mailDomain: String = "testinbox.local",
    val defaultTtl: Duration = Duration.ofMinutes(15),
    val maxTtl: Duration = Duration.ofHours(24),
    val expiryGrace: Duration = Duration.ofSeconds(30),
    val exactCooldown: Duration = Duration.ofHours(24),
    val waitWindowCap: Duration = Duration.ofSeconds(60),
    val maxRawSizeBytes: Long = 15L * 1024 * 1024,
    val sweepInterval: Duration = Duration.ofSeconds(5),
    val orphanSweepInterval: Duration = Duration.ofMinutes(30),
    val orphanMinAge: Duration = Duration.ofHours(1),
    val limits: LimitsProperties = LimitsProperties(),
    val storage: Storage = Storage(),
    val bootstrap: Bootstrap = Bootstrap(),
    val deployment: Deployment = Deployment(),
) {
    data class Storage(
        val endpoint: String = "http://localhost:9000",
        val region: String = "us-east-1",
        val accessKey: String = "testinbox",
        val secretKey: String = "testinbox123",
        val bucket: String = "testinbox-mime",
        /**
         * Create the bucket at startup when missing. Convenient against MinIO;
         * a managed object store where the deployment identity may not hold
         * `CreateBucket` should set this false and pre-create the bucket.
         */
        val createBucket: Boolean = true,
    )

    /**
     * Deployment identity and environment coupling (ADR-028/029). All of it is
     * absent locally: `environment` being set is what marks this process as
     * deployed and turns on the startup safety check.
     */
    data class Deployment(
        val environment: String? = null,
        /** Public origin this environment is served on; must be HTTPS when set. */
        val publicBaseUrl: String? = null,
        /**
         * Read/idle timeout configured on the reverse proxy in front of this
         * process. Declared here so a deployment whose ingress would cut a
         * legitimate long poll short fails to start instead of returning 504s.
         */
        val proxyReadTimeout: Duration? = null,
        val gitSha: String = "unknown",
        /** Digest of the running image — knowable only at deploy time (ADR-028). */
        val imageDigest: String = "unknown",
    )

    data class Bootstrap(
        /** Local/dev fixture API key. Hashed before storage; the plaintext never persists (ADR-010). */
        val apiKey: String? = null,
        val workspaceId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        val projectId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001"),
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
