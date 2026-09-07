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
    /**
     * Hard ceiling on how long the environment's ingress will hold ANY request,
     * when it has one. Cloudflare caps a request at ~100 s on the deployed
     * staging path (ADR-030), and no application setting can raise that — so a
     * wait window that needs more than the ceiling allows produces a 524 no
     * matter what `proxyReadTimeout` claims.
     *
     * Null where the ingress is ours and imposes no ceiling of its own, as in
     * the provider-neutral nginx topology.
     */
    val edgeRequestCeiling: Duration?,
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

    /**
     * Split into one function per concern rather than one long sequence: the
     * checks are independent, and a single method accumulating all of them is
     * both harder to read and — measurably — over the project's complexity
     * threshold.
     */
    fun validate(settings: DeploymentSettings): List<DeploymentViolation> =
        buildList {
            addAll(checkMailDomain(settings))
            addAll(checkDatabase(settings))
            addAll(checkObjectStorage(settings))
            addAll(checkPublicBaseUrl(settings))
            addAll(checkBootstrapKey(settings))
            addAll(checkProxyTimeout(settings))
            addAll(checkEdgeCeiling(settings))
            addAll(checkLimits(settings))
        }

    private fun checkMailDomain(settings: DeploymentSettings): List<DeploymentViolation> =
        if (settings.mailDomain.isBlank() || settings.mailDomain.equals(LOCAL_MAIL_DOMAIN, ignoreCase = true)) {
            listOf(
                DeploymentViolation(
                    "testinbox.mail-domain",
                    "is the local-development default '$LOCAL_MAIL_DOMAIN' — set the mail domain for ${settings.environment}",
                ),
            )
        } else {
            emptyList()
        }

    private fun checkDatabase(settings: DeploymentSettings): List<DeploymentViolation> =
        buildList {
            if (settings.databaseUrl.isBlank()) {
                add(DeploymentViolation("spring.datasource.url", "is not set"))
            } else if (containsLocalHost(settings.databaseUrl)) {
                add(
                    DeploymentViolation(
                        "spring.datasource.url",
                        "points at the local host — a deployed process needs the environment's database",
                    ),
                )
            }
            if (settings.databaseUsername.isBlank()) {
                add(DeploymentViolation("spring.datasource.username", "is not set"))
            }
            if (isLocalDevSecret(settings.databasePassword)) {
                add(
                    DeploymentViolation(
                        "spring.datasource.password",
                        "is a local-development default or empty — inject the environment's credential",
                    ),
                )
            }
        }

    private fun checkObjectStorage(settings: DeploymentSettings): List<DeploymentViolation> =
        buildList {
            if (settings.storageEndpoint.isBlank()) {
                add(DeploymentViolation("testinbox.storage.endpoint", "is not set"))
            } else if (containsLocalHost(settings.storageEndpoint)) {
                add(
                    DeploymentViolation(
                        "testinbox.storage.endpoint",
                        "points at the local host — a deployed process needs the environment's object store",
                    ),
                )
            }
            if (isLocalDevSecret(settings.storageAccessKey)) {
                add(DeploymentViolation("testinbox.storage.access-key", "is a local-development default or empty"))
            }
            if (isLocalDevSecret(settings.storageSecretKey)) {
                add(DeploymentViolation("testinbox.storage.secret-key", "is a local-development default or empty"))
            }
        }

    private fun checkPublicBaseUrl(settings: DeploymentSettings): List<DeploymentViolation> {
        // Absent is legitimate: the ingestion gateway terminates SMTP and has
        // no public HTTP origin of its own.
        val url = settings.publicBaseUrl ?: return emptyList()
        val problem =
            when {
                url.isBlank() -> "is blank"
                url.startsWith("http://", ignoreCase = true) -> "is plaintext HTTP — a public surface must be HTTPS"
                !url.startsWith("https://", ignoreCase = true) -> "is not an absolute https:// URL"
                else -> return emptyList()
            }
        return listOf(DeploymentViolation("testinbox.public-base-url", problem))
    }

    private fun checkBootstrapKey(settings: DeploymentSettings): List<DeploymentViolation> {
        val key = settings.bootstrapApiKey?.takeIf { it.isNotBlank() } ?: return emptyList()
        return buildList {
            if (key.length < MIN_BOOTSTRAP_KEY_LENGTH) {
                // Length only — never the value, never a hash of it (ADR-010).
                add(
                    DeploymentViolation(
                        "testinbox.bootstrap.api-key",
                        "is ${key.length} characters; a deployed bootstrap key must be at least $MIN_BOOTSTRAP_KEY_LENGTH",
                    ),
                )
            }
            if (LOCAL_DEV_SECRETS.any { key.equals(it, ignoreCase = true) } || key.startsWith("tk_e2e_")) {
                add(DeploymentViolation("testinbox.bootstrap.api-key", "is a known development/test fixture key"))
            }
        }
    }

    private fun checkProxyTimeout(settings: DeploymentSettings): List<DeploymentViolation> {
        val proxyTimeout =
            settings.proxyReadTimeout
                ?: return listOf(
                    DeploymentViolation(
                        "testinbox.proxy-read-timeout",
                        "is not declared — without it nothing checks that the ingress outlives a " +
                            "${settings.waitWindowCap.toSeconds()}s wait",
                    ),
                )
        val required = settings.waitWindowCap.plus(PROXY_TIMEOUT_MARGIN)
        if (proxyTimeout >= required) return emptyList()
        return listOf(
            DeploymentViolation(
                "testinbox.proxy-read-timeout",
                "is ${proxyTimeout.toSeconds()}s but the wait window cap is ${settings.waitWindowCap.toSeconds()}s; " +
                    "the proxy would abort a legitimate long poll before the server answers " +
                    "(need at least ${required.toSeconds()}s)",
            ),
        )
    }

    /**
     * The declared proxy timeout is what *we* configured; the ceiling is what
     * the environment will actually tolerate. Checking only the former lets a
     * deployment pass validation and then fail in production: raise the wait
     * window to 90 s, declare a 120 s proxy timeout, and every full-window wait
     * is cut at Cloudflare's 100 s and returns 524 — with every check green.
     */
    private fun checkEdgeCeiling(settings: DeploymentSettings): List<DeploymentViolation> {
        val ceiling = settings.edgeRequestCeiling ?: return emptyList()
        val violations = mutableListOf<DeploymentViolation>()
        val required = settings.waitWindowCap.plus(PROXY_TIMEOUT_MARGIN)
        if (required > ceiling) {
            violations +=
                DeploymentViolation(
                    "testinbox.wait-window-cap",
                    "is ${settings.waitWindowCap.toSeconds()}s, which needs ${required.toSeconds()}s of ingress " +
                        "patience, but this environment's ingress cuts a request at ${ceiling.toSeconds()}s; " +
                        "a full-window wait would fail there whatever the proxy timeout says. Raising this is " +
                        "an edge decision, not an application setting",
                )
        }
        settings.proxyReadTimeout?.let { declared ->
            if (declared > ceiling) {
                violations +=
                    DeploymentViolation(
                        "testinbox.proxy-read-timeout",
                        "is declared as ${declared.toSeconds()}s but the environment's ingress cuts a request at " +
                            "${ceiling.toSeconds()}s; the declared value is not achievable here",
                    )
            }
        }
        return violations
    }

    private fun checkLimits(settings: DeploymentSettings): List<DeploymentViolation> =
        if (settings.limitsEnabled) {
            emptyList()
        } else {
            listOf(
                DeploymentViolation(
                    "testinbox.limits.enabled",
                    "is false — a reachable deployment would be unprotected (ADR-027)",
                ),
            )
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
