package email.testinbox.application.usecase

import email.testinbox.application.port.ApiKeyMetrics
import email.testinbox.application.port.ApiKeyRepository
import email.testinbox.application.port.LastUsedRecorder
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.tenant.ApiKey
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps `last_used_at` approximately current without putting a write on the
 * hot path of every authenticated request (ADR-032 §7).
 *
 * Two layers, and both are needed:
 *
 * - **The in-memory map** removes the database round trip entirely in the
 *   steady state. Without it, every request would still issue an `UPDATE`
 *   that usually matches nothing — cheaper than a write, but not free, and
 *   paid on every call.
 * - **The `WHERE` guard inside the repository** is what makes it *correct*
 *   across nodes and restarts. The map is a per-process optimisation that may
 *   be empty, stale or cleared at any moment; the database decides.
 *
 * Losing the map costs at most one extra write per key per interval, which is
 * why it can be bounded by simply discarding it when it grows too large. A
 * cache that must be evicted precisely would be a worse trade for a field
 * whose documented accuracy is "within five minutes".
 */
class CoalescingLastUsedRecorder(
    private val apiKeys: ApiKeyRepository,
    private val interval: Duration = DEFAULT_INTERVAL,
    private val maxTrackedKeys: Int = DEFAULT_MAX_TRACKED_KEYS,
    private val metrics: ApiKeyMetrics = ApiKeyMetrics.NOOP,
) {
    private val lastWrittenByKey = ConcurrentHashMap<ApiKeyId, Instant>()

    fun asRecorder(): LastUsedRecorder =
        object : LastUsedRecorder {
            override fun record(
                key: ApiKey,
                at: Instant,
            ) = this@CoalescingLastUsedRecorder.record(key, at)
        }

    fun record(
        key: ApiKey,
        at: Instant,
    ) {
        val threshold = at.minus(interval)
        val locallyKnown = lastWrittenByKey[key.id] ?: key.lastUsedAt
        if (locallyKnown != null && locallyKnown.isAfter(threshold)) return

        // Bounded by discarding wholesale. The entries are timestamps, so the
        // worst consequence of dropping them is a few extra guarded UPDATEs.
        if (lastWrittenByKey.size >= maxTrackedKeys) lastWrittenByKey.clear()
        // Recorded before the write, not after: if the write fails we must not
        // retry it on every subsequent request for the next five minutes.
        lastWrittenByKey[key.id] = at

        if (apiKeys.touchLastUsed(key.id, at, threshold)) metrics.lastUsedPersisted()
    }

    companion object {
        val DEFAULT_INTERVAL: Duration = Duration.ofMinutes(5)

        /**
         * A ceiling on memory, not a working-set estimate. A workspace holds
         * a handful of credentials; this bounds what a pathological tenant
         * (or an attacker minting keys) can make a node retain.
         */
        const val DEFAULT_MAX_TRACKED_KEYS = 10_000
    }
}
