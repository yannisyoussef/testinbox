package email.testinbox.application.port

import email.testinbox.domain.identity.LoginProvider
import email.testinbox.domain.identity.ProviderLoginState
import java.time.Instant

data class ProviderLoginVolume(
    val retained: Long,
    val lastHour: Long,
    val sourceQuarterHour: Long,
)

interface ProviderLoginStates {
    /** Erase expired protocol payloads; retain only bounded rate metadata until its window expires. */
    fun purge(
        before: Instant,
        now: Instant,
    )

    fun volume(
        sourceHash: String,
        now: Instant,
    ): ProviderLoginVolume

    fun replaceForBrowser(browserHash: String)

    fun insert(state: ProviderLoginState)

    /** Atomically return and erase a still-live, same-browser, same-provider encrypted request. */
    fun consume(
        stateHash: String,
        browserHash: String,
        provider: LoginProvider,
        now: Instant,
    ): String?
}
