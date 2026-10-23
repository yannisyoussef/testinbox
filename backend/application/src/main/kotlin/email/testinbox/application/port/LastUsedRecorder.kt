package email.testinbox.application.port

import email.testinbox.domain.tenant.ApiKey
import java.time.Instant

/**
 * Records approximate credential usage (ADR-032 §7). A port rather than a
 * direct repository call so the authentication path stays independent of
 * *how* the coalescing is done, and so tests can observe it.
 */
interface LastUsedRecorder {
    fun record(
        key: ApiKey,
        at: Instant,
    )

    companion object {
        val NOOP: LastUsedRecorder =
            object : LastUsedRecorder {
                override fun record(
                    key: ApiKey,
                    at: Instant,
                ) = Unit
            }
    }
}
