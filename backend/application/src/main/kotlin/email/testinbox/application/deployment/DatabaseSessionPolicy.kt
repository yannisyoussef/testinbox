package email.testinbox.application.deployment

import java.time.Duration

/**
 * Outbound port: the value of `idle_in_transaction_session_timeout` as the
 * database reports it for *this process's* sessions (ADR-024).
 *
 * Read with `SHOW`, which needs no privilege and answers for the session that
 * asks — so a value set per role or per database by Ops is what comes back,
 * which is exactly the value that bounds our claims.
 */
fun interface DatabaseSessionSettings {
    /** The raw setting text, e.g. `30s`, `5min` or `0`. */
    fun idleInTransactionSessionTimeout(): String
}

/**
 * PostgreSQL renders a duration GUC in its own largest whole unit: `30s`,
 * `5min`, `1h`, `250ms`, or a bare number in the setting's base unit — which
 * for this setting is milliseconds. `0` means disabled.
 */
object SessionTimeout {
    private val VALUE = Regex("""^\s*(\d+)\s*(us|ms|s|min|h|d)?\s*$""")

    fun parse(raw: String): Duration? {
        val match = VALUE.find(raw) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        return when (match.groupValues[2]) {
            "us" -> Duration.ofNanos(amount * NANOS_PER_MICRO)
            "", "ms" -> Duration.ofMillis(amount)
            "s" -> Duration.ofSeconds(amount)
            "min" -> Duration.ofMinutes(amount)
            "h" -> Duration.ofHours(amount)
            "d" -> Duration.ofDays(amount)
            else -> null
        }
    }

    private const val NANOS_PER_MICRO = 1_000L
}

data class DatabaseSessionStatus(
    /** The setting as the database rendered it. */
    val raw: String,
    val timeout: Duration?,
    /** True when a hung claim is bounded at all: a positive, parseable timeout. */
    val bounded: Boolean,
    val detail: String,
)

/**
 * ADR-033 names one thing the application cannot enforce for itself: a node
 * that is partitioned mid-claim holds its idempotency row until the database
 * reaps the session, and PostgreSQL ships with that reaping disabled. The
 * bound is then the TCP keepalive interval — hours — during which one key is
 * unusable. No duplicate mutation is possible either way; this is liveness.
 *
 * This reads the effective value and says whether it bounds anything. Whether
 * an unbounded value takes the node out of readiness is the adapter's call,
 * because the answer differs by environment: production requires it, and a
 * staging estate that has not set it should be visible rather than removed
 * from service by the deploy that added this check.
 */
class DatabaseSessionPolicy(
    private val settings: DatabaseSessionSettings,
) {
    fun status(): DatabaseSessionStatus {
        val raw = settings.idleInTransactionSessionTimeout()
        val timeout = SessionTimeout.parse(raw)
        return when {
            timeout == null -> {
                DatabaseSessionStatus(raw, null, false, "idle_in_transaction_session_timeout is '$raw', which is not a duration")
            }

            timeout.isZero || timeout.isNegative -> {
                DatabaseSessionStatus(
                    raw,
                    timeout,
                    false,
                    "idle_in_transaction_session_timeout is disabled; a hung idempotency claim is unbounded (ADR-033)",
                )
            }

            timeout < RECOMMENDED_MINIMUM -> {
                DatabaseSessionStatus(
                    raw,
                    timeout,
                    true,
                    "idle_in_transaction_session_timeout is $raw, below the ${RECOMMENDED_MINIMUM.toSeconds()}s claim-wait " +
                        "ceiling; healthy transactions may be reaped",
                )
            }

            else -> {
                DatabaseSessionStatus(raw, timeout, true, "idle_in_transaction_session_timeout is $raw")
            }
        }
    }

    private companion object {
        /** `TestInboxProperties.Idempotency.MAX_CLAIM_WAIT`: the longest a legitimate claim may block. */
        val RECOMMENDED_MINIMUM: Duration = Duration.ofSeconds(30)
    }
}
