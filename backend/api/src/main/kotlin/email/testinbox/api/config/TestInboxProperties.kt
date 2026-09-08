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
    val idempotency: Idempotency = Idempotency(),
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
        /**
         * Hard ceiling the environment's ingress imposes on ANY request, when
         * it has one — Cloudflare's ~100s on the deployed staging path
         * (ADR-030). No application setting can raise it, so the wait window
         * has to fit underneath it.
         */
        val edgeRequestCeiling: Duration? = null,
        val gitSha: String = "unknown",
        /** Digest of the running image — knowable only at deploy time (ADR-028). */
        val imageDigest: String = "unknown",
    )

    /**
     * ADR-033. Both values are deployment policy rather than product
     * behaviour, so they are configurable: retention trades a privacy cost
     * that grows linearly against a benefit that saturates in minutes, and the
     * claim wait bounds how long a blocked duplicate holds a connection.
     */
    data class Idempotency(
        val retention: Duration = Duration.ofHours(6),
        val claimWait: Duration = Duration.ofSeconds(2),
        val sweepInterval: Duration = Duration.ofMinutes(5),
    ) {
        init {
            // `claimWait` becomes `SET LOCAL lock_timeout`, where Postgres
            // reads 0 as *disabled* rather than "give up immediately". A
            // configured zero would therefore invert ADR-033 §3 exactly: the
            // bounded wait becomes unbounded, and a blocked duplicate pins a
            // servlet thread, a pooled connection and an open transaction
            // until something else reaps it — which
            // `idle_in_transaction_session_timeout` will not do, because the
            // session is waiting on a lock rather than idle. A negative value
            // is a syntax error on every keyed request. Neither is reachable
            // by accident, and both are silent, so they are refused at startup.
            require(claimWait >= MIN_CLAIM_WAIT) {
                "testinbox.idempotency.claim-wait must be at least $MIN_CLAIM_WAIT (0 disables the timeout)"
            }
            require(claimWait <= MAX_CLAIM_WAIT) {
                "testinbox.idempotency.claim-wait must not exceed $MAX_CLAIM_WAIT"
            }
            require(!retention.isNegative && !retention.isZero) {
                "testinbox.idempotency.retention must be positive"
            }
            require(!sweepInterval.isNegative && !sweepInterval.isZero) {
                "testinbox.idempotency.sweep-interval must be positive"
            }
        }

        private companion object {
            val MIN_CLAIM_WAIT: Duration = Duration.ofMillis(1)

            /**
             * A claim that waits longer than this holds a connection for longer
             * than any client is still listening, so the refusal is cheaper.
             */
            val MAX_CLAIM_WAIT: Duration = Duration.ofSeconds(30)
        }
    }

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
