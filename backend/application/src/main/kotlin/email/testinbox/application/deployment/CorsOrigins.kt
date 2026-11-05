package email.testinbox.application.deployment

import java.net.URI
import java.net.URISyntaxException

/**
 * The browser origins allowed to call the public `/v1` API cross-origin
 * (docs/api/principles.md #11) — today only the documentation site's
 * "Try it" playground.
 *
 * An entry is an **exact origin** as a browser serializes it in the `Origin`
 * header: `scheme://host[:port]`, lowercase, nothing else. Anything looser is
 * refused at startup rather than normalized, because an allow-list that
 * silently rewrites its entries is an allow-list nobody can read:
 *
 * - `*` (or any wildcard) is never accepted. With bearer keys and no
 *   credentials a wildcard would still be "safe" in the cookie sense, but it
 *   would turn every web page on the internet into a client of the API with
 *   whatever key a visitor pasted, which is not a decision a config typo
 *   should be able to make.
 * - a path, query, fragment, trailing slash or userinfo never appears in a
 *   real `Origin`, so such an entry would never match and only looks allowed;
 * - an explicit default port (`:443` on https, `:80` on http) is omitted by
 *   browsers, so it would never match either;
 * - an upper-case host would match (origin comparison ignores case) but says
 *   the value was hand-typed from somewhere other than a browser; refused so
 *   the configured value is the one in the access log.
 *
 * Framework-free and in the application layer so [DeploymentSafety] can apply
 * the production rules (https only, no loopback/staging/rehearsal host) with
 * the same host classification it applies to every other production value.
 */
object CorsOrigins {
    /** The property, and the environment variable that feeds it (application.yaml). */
    const val PROPERTY = "testinbox.cors.allowed-origins"
    const val ENVIRONMENT_VARIABLE = "TESTINBOX_CORS_ALLOWED_ORIGINS"

    private val SCHEMES = mapOf("https" to 443, "http" to 80)

    /** A DNS name or dotted IPv4 literal: lowercase labels, no empty label, no edge hyphen. */
    private val HOST = Regex("^(?!-)[a-z0-9-]{1,63}(?<!-)(\\.(?!-)[a-z0-9-]{1,63}(?<!-))*$")
    private const val MAX_PORT = 65535

    /**
     * Every syntactic problem with [origins], in order. Empty means each entry
     * is an exact origin. Applies in every environment, local included.
     */
    fun violations(origins: List<String>): List<DeploymentViolation> =
        origins.mapNotNull { problem(it) }.map { DeploymentViolation(PROPERTY, it) }

    /** Fails startup with one message naming the property and every bad entry. */
    fun requireValid(origins: List<String>) {
        val violations = violations(origins)
        check(violations.isEmpty()) {
            violations.joinToString(
                prefix =
                    "Refusing to start: $PROPERTY ($ENVIRONMENT_VARIABLE) must be a comma-separated list of exact " +
                        "origins (scheme://host[:port], lowercase, no path, no wildcard).\n",
                separator = "\n",
            ) { "  - ${it.setting} ${it.problem}" }
        }
    }

    /** The scheme and host of a syntactically valid entry, for the production rules; null for an invalid one. */
    internal fun parse(origin: String): Pair<String, String>? {
        if (problem(origin) != null) return null
        val uri = URI(origin)
        return uri.scheme to uri.host
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun problem(entry: String): String? {
        if (entry.isBlank()) return "contains an empty entry (a stray comma?)"
        if (entry.contains('*')) return "entry '$entry' is a wildcard; only exact origins are allowed and '*' is always refused"
        if (entry != entry.trim()) return "entry '$entry' has surrounding whitespace"
        val uri =
            try {
                URI(entry)
            } catch (_: URISyntaxException) {
                return "entry '$entry' is not an origin"
            }
        val scheme = uri.scheme ?: return "entry '$entry' has no scheme; write it as https://host"
        val defaultPort = SCHEMES[scheme] ?: return "entry '$entry' must use http or https (lowercase)"
        return when {
            uri.rawUserInfo != null -> {
                "entry '$entry' carries userinfo"
            }

            uri.isOpaque || uri.rawAuthority == null -> {
                "entry '$entry' has no host"
            }

            !uri.rawPath.isNullOrEmpty() -> {
                "entry '$entry' has a path (an origin has none, not even a trailing slash)"
            }

            uri.rawQuery != null -> {
                "entry '$entry' has a query"
            }

            uri.rawFragment != null -> {
                "entry '$entry' has a fragment"
            }

            uri.host == null || !HOST.matches(uri.host) -> {
                "entry '$entry' must name a lowercase DNS host or IPv4 address"
            }

            uri.port == defaultPort -> {
                "entry '$entry' names the default port explicitly; browsers omit it, so it would never match"
            }

            uri.port != -1 && uri.port !in 1..MAX_PORT -> {
                "entry '$entry' has an invalid port"
            }

            // Catches an empty (`host:`) or zero-padded (`:0800`) port: the browser never writes either.
            uri.rawAuthority != uri.host + (if (uri.port == -1) "" else ":${uri.port}") -> {
                "entry '$entry' is not in exact origin form"
            }

            else -> {
                null
            }
        }
    }
}
