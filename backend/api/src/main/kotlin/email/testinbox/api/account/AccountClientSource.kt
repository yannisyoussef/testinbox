package email.testinbox.api.account

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale

/**
 * The ONE place a sign-in source budget key is derived (ADR-036 §3: bound
 * requests by verified deployment source).
 *
 * The browser reaches `/account` only through the website's same-origin
 * proxy, so the TCP peer is the website for every visitor. When an operator
 * configures a shared secret, the website may name the client it derived from
 * its own trusted edge in [CLIENT_HEADER], authenticated by [SECRET_HEADER].
 * Anything else — no secret configured, header absent, secret missing or
 * wrong, or an address that is not one literal IP — falls back to the peer,
 * i.e. the shared budget. That is fail-safe: a request can never choose its
 * own source. `X-Forwarded-For`/`Forwarded` are never read here.
 *
 * IPv4 addresses are used as-is. IPv6 addresses are reduced to their /64,
 * the smallest prefix normally delegated to one host or LAN, so one host
 * cannot rotate through its own /64 to multiply its budget.
 *
 * Neither the secret nor any address is logged or used as a metric label.
 */
class AccountClientSource(
    configuredSecret: String,
    registry: MeterRegistry,
) {
    /** Closed metric outcomes; never an address. */
    enum class Outcome { TRUSTED, ABSENT, NOT_CONFIGURED, UNAUTHENTICATED, MALFORMED }

    private val secretDigest: ByteArray? = configuredSecret.takeIf { it.isNotEmpty() }?.let { digest(validate(it)) }
    private val counters: Map<Outcome, Counter> =
        Outcome.entries.associateWith { registry.counter(METRIC, "outcome", it.name.lowercase(Locale.ROOT)) }

    init {
        registry.gauge("testinbox_account_client_address_configured", this) { if (it.secretDigest == null) 0.0 else 1.0 }
    }

    /** The source string handed to the sign-in budget use cases (they hash it with their own key). */
    fun resolve(request: HttpServletRequest): String {
        val (outcome, client) = classify(request)
        counters.getValue(outcome).increment()
        return if (client != null) "client:$client" else "peer:${request.remoteAddr}"
    }

    private fun classify(request: HttpServletRequest): Pair<Outcome, String?> {
        val claimed = request.getHeaders(CLIENT_HEADER)?.toList().orEmpty()
        if (claimed.isEmpty()) return Outcome.ABSENT to null
        val expected = secretDigest ?: return Outcome.NOT_CONFIGURED to null
        val presented = request.getHeaders(SECRET_HEADER)?.toList().orEmpty()
        // Compare fixed-length digests so neither content nor length leaks through timing.
        if (presented.size != 1 || !MessageDigest.isEqual(digest(presented.single()), expected)) {
            return Outcome.UNAUTHENTICATED to null
        }
        val client = claimed.singleOrNull()?.let(::normalize) ?: return Outcome.MALFORMED to null
        return Outcome.TRUSTED to client
    }

    companion object {
        const val CLIENT_HEADER: String = "X-TestInbox-Client-Address"
        const val SECRET_HEADER: String = "X-TestInbox-Proxy-Secret"
        const val METRIC: String = "testinbox_account_client_address_total"
        const val MIN_SECRET_BYTES: Int = 32
        private const val MAX_SECRET_BYTES = 512
        private const val MIN_DISTINCT_CHARACTERS = 12
        private const val IPV6_PREFIX_BYTES = 8
        private const val MAX_LITERAL_LENGTH = 45
        private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")
        private val IPV6_CHARACTERS = Regex("""[0-9A-Fa-f:.]+""")

        /** Startup validation; the message never contains the value. */
        fun validate(secret: String): String {
            val bytes = secret.toByteArray(Charsets.UTF_8).size
            check(bytes in MIN_SECRET_BYTES..MAX_SECRET_BYTES) {
                "testinbox.accounts.client-address-secret must be $MIN_SECRET_BYTES to $MAX_SECRET_BYTES bytes"
            }
            check(secret.all { it in '!'..'~' }) {
                "testinbox.accounts.client-address-secret must be printable ASCII without whitespace"
            }
            check(secret.toSet().size >= MIN_DISTINCT_CHARACTERS) {
                "testinbox.accounts.client-address-secret is too weak; generate it randomly"
            }
            return secret
        }

        /**
         * One literal IPv4/IPv6 address, or null. Syntax is checked BEFORE any
         * InetAddress call, and [InetAddress.ofLiteral] never resolves names.
         * Lists, ports, brackets, zone ids, hostnames and legacy IPv4 forms
         * (`1`, `1.2.3`, leading zeros) are refused.
         */
        fun normalize(value: String): String? {
            if (value.isEmpty() || value.length > MAX_LITERAL_LENGTH) return null
            val address =
                when {
                    IPV4.matches(value) -> runCatching { InetAddress.ofLiteral(value) }.getOrNull()
                    ':' in value && IPV6_CHARACTERS.matches(value) -> runCatching { InetAddress.ofLiteral(value) }.getOrNull()
                    else -> null
                }
            return when (address) {
                // Includes IPv4-mapped IPv6, which Java returns as the IPv4 host it is.
                is Inet4Address -> {
                    address.hostAddress
                }

                is Inet6Address -> {
                    val prefix = address.address.copyOf()
                    prefix.fill(0, IPV6_PREFIX_BYTES, prefix.size)
                    InetAddress.getByAddress(prefix).hostAddress + "/64"
                }

                else -> {
                    null
                }
            }
        }

        private fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    }
}
