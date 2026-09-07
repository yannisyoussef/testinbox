package email.testinbox.application.deployment

import java.time.Duration

/**
 * The configuration a deployed process is about to run with, reduced to the
 * facts worth refusing to start over.
 *
 * Framework-free and shared by both deployables on purpose: the API and the
 * ingestion gateway are separate processes (ADR-001) reading overlapping
 * configuration, and a safety check written twice is a safety check that
 * drifts — the same reasoning that put limit enforcement in one use case
 * rather than in each adapter (ADR-027 §3).
 */
data class DeploymentSettings(
    /** The environment this process believes it is in, e.g. "staging". */
    val environment: String,
    val mailDomain: String,
    val databaseUrl: String,
    val databaseUsername: String,
    val databasePassword: String,
    val storageEndpoint: String,
    val storageAccessKey: String,
    val storageSecretKey: String,
    /** Public origin this environment is reached on, e.g. `https://api.staging.testinbox.email`. */
    val publicBaseUrl: String?,
    /** Bootstrap fixture key, if one is configured. Null/blank when absent. */
    val bootstrapApiKey: String?,
    val waitWindowCap: Duration,
    /**
     * Read/idle timeout of the reverse proxy in front of this process, as
     * configured for the environment. Declared rather than probed: the point
     * is to fail the deployment when the two are set inconsistently.
     */
    val proxyReadTimeout: Duration?,
    val limitsEnabled: Boolean,
)

data class DeploymentViolation(
    val setting: String,
    val problem: String,
)

/**
 * Fails a deployed process fast when its configuration is a local-development
 * default, a plaintext-HTTP public surface, or a proxy timeout that would
 * convert TestInbox's own `200 {status: TIMEOUT}` into a gateway error.
 *
 * The failure mode this exists for is silence: every one of these settings has
 * a working local default, so a missing environment variable does not produce
 * an error — it produces a staging environment running on `testinbox/testinbox`
 * against `localhost`, which looks healthy right up until it is reachable.
 */
object DeploymentSafety {
    /**
     * Values that ship in `application.yaml` and `docker-compose.yml` for local
     * development. Their presence in a deployed profile means an environment
     * variable was not supplied, not that someone chose them.
     */
    private val LOCAL_DEV_SECRETS = setOf("testinbox", "testinbox123", "changeme", "password", "postgres", "minioadmin")
    private const val LOCAL_MAIL_DOMAIN = "testinbox.local"
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1")

    /** Host of a `//host`, `//[v6]` or `@host` authority — bracketed IPv6 included. */
    private val AUTHORITY = Regex("""(?://|@)(\[[^\]]*]|[^/@\[\]:?#]+)(?::\d+)?(?:[/?#]|$)""")

    /** Minimum length for a bootstrap key in a deployed environment (ADR-010: it is a bearer credential). */
    const val MIN_BOOTSTRAP_KEY_LENGTH = 32

    /**
     * How much longer the proxy must wait than the server's own maximum wait
     * window (ADR-020). Covers request/response transit and the server's own
     * scheduling slack; anything tighter makes a legitimate long poll a race.
     */
    val PROXY_TIMEOUT_MARGIN: Duration = Duration.ofSeconds(30)

    fun validate(settings: DeploymentSettings): List<DeploymentViolation> {
        val violations = mutableListOf<DeploymentViolation>()

        fun reject(
            setting: String,
            problem: String,
        ) = violations.add(DeploymentViolation(setting, problem))

        if (settings.mailDomain.isBlank() || settings.mailDomain.equals(LOCAL_MAIL_DOMAIN, ignoreCase = true)) {
            reject(
                "testinbox.mail-domain",
                "is the local-development default '$LOCAL_MAIL_DOMAIN' — set the mail domain for ${settings.environment}",
            )
        }

        if (containsLocalHost(settings.databaseUrl)) {
            reject("spring.datasource.url", "points at the local host — a deployed process needs the environment's database")
        }
        if (settings.databaseUrl.isBlank()) {
            reject("spring.datasource.url", "is not set")
        }
        if (settings.databaseUsername.isBlank()) {
            reject("spring.datasource.username", "is not set")
        }
        if (isLocalDevSecret(settings.databasePassword)) {
            reject("spring.datasource.password", "is a local-development default or empty — inject the environment's credential")
        }

        if (settings.storageEndpoint.isBlank()) {
            reject("testinbox.storage.endpoint", "is not set")
        } else if (containsLocalHost(settings.storageEndpoint)) {
            reject("testinbox.storage.endpoint", "points at the local host — a deployed process needs the environment's object store")
        }
        if (isLocalDevSecret(settings.storageAccessKey)) {
            reject("testinbox.storage.access-key", "is a local-development default or empty")
        }
        if (isLocalDevSecret(settings.storageSecretKey)) {
            reject("testinbox.storage.secret-key", "is a local-development default or empty")
        }

        settings.publicBaseUrl?.let { url ->
            when {
                url.isBlank() -> {
                    reject("testinbox.public-base-url", "is blank")
                }

                url.startsWith("http://", ignoreCase = true) -> {
                    reject("testinbox.public-base-url", "is plaintext HTTP — a public surface must be HTTPS")
                }

                !url.startsWith("https://", ignoreCase = true) -> {
                    reject("testinbox.public-base-url", "is not an absolute https:// URL")
                }
            }
        }

        val key = settings.bootstrapApiKey?.takeIf { it.isNotBlank() }
        if (key != null) {
            if (key.length < MIN_BOOTSTRAP_KEY_LENGTH) {
                // Length only — never the value, never a hash of it (ADR-010).
                reject(
                    "testinbox.bootstrap.api-key",
                    "is ${key.length} characters; a deployed bootstrap key must be at least $MIN_BOOTSTRAP_KEY_LENGTH",
                )
            }
            if (LOCAL_DEV_SECRETS.any { key.equals(it, ignoreCase = true) } || key.startsWith("tk_e2e_")) {
                reject("testinbox.bootstrap.api-key", "is a known development/test fixture key")
            }
        }

        val proxyTimeout = settings.proxyReadTimeout
        if (proxyTimeout == null) {
            reject(
                "testinbox.proxy-read-timeout",
                "is not declared — without it nothing checks that the ingress outlives a ${settings.waitWindowCap.toSeconds()}s wait",
            )
        } else {
            val required = settings.waitWindowCap.plus(PROXY_TIMEOUT_MARGIN)
            if (proxyTimeout < required) {
                reject(
                    "testinbox.proxy-read-timeout",
                    "is ${proxyTimeout.toSeconds()}s but the wait window cap is ${settings.waitWindowCap.toSeconds()}s; " +
                        "the proxy would abort a legitimate long poll before the server answers " +
                        "(need at least ${required.toSeconds()}s)",
                )
            }
        }

        if (!settings.limitsEnabled) {
            reject("testinbox.limits.enabled", "is false — a reachable deployment would be unprotected (ADR-027)")
        }

        return violations
    }

    /** Renders violations for a startup failure. Setting *names* and problems only — no values. */
    fun describe(violations: List<DeploymentViolation>): String =
        violations.joinToString(
            prefix = "Refusing to start: deployed configuration is unsafe.\n",
            separator = "\n",
        ) { "  - ${it.setting} ${it.problem}" }

    private fun isLocalDevSecret(value: String): Boolean = value.isBlank() || LOCAL_DEV_SECRETS.any { value.equals(it, ignoreCase = true) }

    /**
     * Matches a loopback authority, not a substring: `//localhost.example.com`
     * is a real remote host and must not be rejected.
     */
    private fun containsLocalHost(value: String): Boolean =
        AUTHORITY
            .findAll(value.lowercase())
            .map { it.groupValues[1].removeSurrounding("[", "]") }
            .any { it in LOCAL_HOSTS }
}
