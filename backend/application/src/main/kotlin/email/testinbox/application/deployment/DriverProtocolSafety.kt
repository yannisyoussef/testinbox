package email.testinbox.application.deployment

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * The pgJDBC protocol settings T1's one-round-trip commit depends on (gate D A′).
 *
 * T1 sends its reservation insert and its `COMMIT` as one multi-statement
 * query, so the global admission lock is released without a client round trip
 * (`JdbcStorageAdmission.insert`). That is correct under pgJDBC's defaults:
 *
 * - `autosave=never`. With `always` or `conservative` the driver sends its own
 *   `SAVEPOINT` before a query and `RELEASE` / `ROLLBACK TO` after it, all of
 *   which presume the query leaves the transaction open. A batch ending in
 *   `COMMIT` does not; whether the driver then skips its follow-up depends on
 *   its own transaction-state tracking (42.7.13 skips the `RELEASE`), which is
 *   a driver detail, not a property T1 was reasoned or tested under.
 * - `preferQueryMode=extended`. The batch was designed and is tested for the
 *   extended protocol only; the other modes change how the multi-statement
 *   query and its parameters reach the server.
 *
 * Nothing in TestInbox sets either, which is exactly why a later
 * `TESTINBOX_DB_URL` or data-source property that did would go unnoticed. So
 * any value other than the default, in the URL or in Hikari's
 * `data-source-properties`, is refused, key and value compared
 * case-insensitively (stricter than the driver, which is the safe direction).
 *
 * Checked only where [DeploymentSettings.databaseDriverProperties] is not
 * null, i.e. on the deployable that runs T1 (the ingestion gateway). Not
 * covered: properties pgJDBC reads from a service file or a classpath
 * `driverconfig.properties`, and a `spring.datasource.hikari.jdbc-url` that
 * overrides `spring.datasource.url`; none is used by any deployment.
 */
object DriverProtocolSafety {
    private val REQUIRED = mapOf("autosave" to "never", "preferquerymode" to "extended")

    fun check(settings: DeploymentSettings): List<DeploymentViolation> {
        val properties = settings.databaseDriverProperties ?: return emptyList()
        return buildList {
            for ((key, value) in urlParameters(settings.databaseUrl)) {
                refusal(key, value)?.let { add(DeploymentViolation("spring.datasource.url", "sets $it")) }
            }
            for ((key, value) in properties) {
                refusal(key, value)?.let {
                    add(DeploymentViolation("spring.datasource.hikari.data-source-properties.$key", "sets $it"))
                }
            }
        }
    }

    /**
     * The problem with one driver property, or null when it is not one this
     * check governs or holds the default. Never echoes the value: the
     * violation names the property and the value it requires.
     */
    private fun refusal(
        key: String,
        value: String,
    ): String? {
        val name = key.trim().lowercase()
        val required = REQUIRED[name] ?: return null
        if (value.trim().equals(required, ignoreCase = true)) return null
        val property = if (name == "autosave") "autosave" else "preferQueryMode"
        return "$property to a value other than pgJDBC's default '$required'; T1 sends its insert and COMMIT in one " +
            "round trip (gate D A′), which is correct only under that default"
    }

    /** Every `key=value` of the URL's query, percent-decoded as pgJDBC decodes them; duplicates kept. */
    private fun urlParameters(url: String): List<Pair<String, String>> =
        url
            .substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .map { parameter ->
                val key = parameter.substringBefore('=')
                val value = parameter.substringAfter('=', "")
                decode(key) to decode(value)
            }

    private fun decode(text: String): String = runCatching { URLDecoder.decode(text, StandardCharsets.UTF_8) }.getOrDefault(text)
}
