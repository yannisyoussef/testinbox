package email.testinbox.application

import java.time.Duration

/**
 * Deployment configuration consumed by use cases. All values are
 * configuration-driven (ADR-021 requires the exact-address cooldown to be;
 * TTLs/caps come from ADR-009, the wait cap from wait-semantics.md).
 */
data class TestInboxConfig(
    /** Mail domain routable to this deployment, e.g. `testinbox.local`. */
    val mailDomain: String,
    val defaultTtl: Duration = Duration.ofMinutes(15),
    val maxTtl: Duration = Duration.ofHours(24),
    /** EXPIRING grace window honoring in-flight deliveries (ADR-009). */
    val expiryGrace: Duration = Duration.ofSeconds(30),
    /** Cooldown before an EXACT local-part may be re-reserved (ADR-021 default 24h). */
    val exactCooldown: Duration = Duration.ofHours(24),
    /** Cap on a single server-side wait window; SDKs chain longer timeouts (ADR-012/020). */
    val waitWindowCap: Duration = Duration.ofSeconds(60),
    val maxRawSizeBytes: Long = 15L * 1024 * 1024,
    val sweepBatchSize: Int = 100,
)
