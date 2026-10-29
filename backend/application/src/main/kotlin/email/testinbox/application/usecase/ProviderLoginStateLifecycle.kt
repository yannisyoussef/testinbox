package email.testinbox.application.usecase

import email.testinbox.application.identity.ProviderLoginCrypto
import email.testinbox.application.identity.SessionCredential
import email.testinbox.application.port.HumanAuthenticationLock
import email.testinbox.application.port.ProviderLoginStates
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.identity.LoginProvider
import email.testinbox.domain.identity.ProviderLoginState
import java.time.Clock
import java.time.Instant

/** The SDK creates the authorization request; this use case bounds its server-side state. */
class RecordProviderLoginState(
    private val store: ProviderLoginStates,
    private val registration: HumanAuthenticationLock,
    private val transactions: TransactionRunner,
    private val crypto: ProviderLoginCrypto,
    private val clock: Clock,
) {
    sealed interface Result {
        class Recorded(
            val browser: SessionCredential,
            val expiresAt: Instant,
        ) : Result {
            override fun toString(): String = "ProviderLoginRecorded(browser=[REDACTED], expiresAt=$expiresAt)"
        }

        data object Limited : Result
    }

    fun execute(
        provider: LoginProvider,
        state: String,
        request: String,
        verifiedPeer: String,
        browserToken: String? = null,
    ): Result {
        require(provider != LoginProvider.EMAIL)
        val browser = SessionCredential.parse(browserToken) ?: SessionCredential.generate()
        val stateHash = crypto.stateHash(state)
        val encrypted = crypto.seal(provider, stateHash, request)
        return transactions.required {
            registration.lock()
            val now = clock.instant()
            store.purge(now.minus(ProviderLoginState.RETENTION), now)
            val sourceHash = crypto.sourceHash(verifiedPeer)
            val volume = store.volume(sourceHash, now)
            if (volume.retained >= MAX_RETAINED || volume.lastHour >= MAX_PER_HOUR || volume.sourceQuarterHour >= MAX_PER_SOURCE) {
                return@required Result.Limited
            }
            val expires = now.plus(ProviderLoginState.LIFETIME)
            store.replaceForBrowser(crypto.browserHash(browser))
            store.insert(ProviderLoginState(stateHash, crypto.browserHash(browser), sourceHash, provider, encrypted, now, expires))
            Result.Recorded(browser, expires)
        }
    }

    companion object {
        /**
         * Every retained row, spent ones included. Above [MAX_PER_HOUR] times the 24-hour retention (4,800),
         * so spent states kept for replay detection never lock out sign-in; it fails closed only on an anomaly.
         */
        const val MAX_RETAINED: Int = 5_000
        const val MAX_PER_HOUR: Int = 200

        /** Per verified source, per quarter hour: its hourly equivalent (60) stays well under [MAX_PER_HOUR]. */
        const val MAX_PER_SOURCE: Int = 15
    }
}

class ConsumeProviderLoginState(
    private val store: ProviderLoginStates,
    private val transactions: TransactionRunner,
    private val crypto: ProviderLoginCrypto,
    private val clock: Clock,
) {
    fun execute(
        provider: LoginProvider,
        state: String,
        browserToken: String?,
    ): String? {
        if (provider == LoginProvider.EMAIL || !crypto.validState(state)) return null
        val browser = SessionCredential.parse(browserToken) ?: return null
        val hash = crypto.stateHash(state)
        val encrypted = transactions.required { store.consume(hash, crypto.browserHash(browser), provider, clock.instant()) } ?: return null
        // No DB lock remains over decryption or a later provider token request.
        return crypto.open(provider, hash, encrypted)
    }
}

class PurgeProviderLoginState(
    private val store: ProviderLoginStates,
    private val registration: HumanAuthenticationLock,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    fun execute() {
        transactions.required {
            registration.lock()
            val now = clock.instant()
            store.purge(now.minus(ProviderLoginState.RETENTION), now)
        }
    }
}
