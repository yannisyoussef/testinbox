package email.testinbox.application.deployment

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Gate D A′: T1 sends its insert and COMMIT in one round trip, which was reasoned and tested only under
 * pgJDBC's default `autosave=never` and `preferQueryMode=extended`. A gateway configured otherwise, in
 * the URL or in Hikari's data-source properties, must refuse to start.
 */
class DriverProtocolSafetyTest {
    private val url = "jdbc:postgresql://db.staging.internal:5432/testinbox"

    /** A correctly configured staging gateway: no public origin, driver properties checked. */
    private val gateway =
        DeploymentSettings(
            environment = "staging",
            mailDomain = "staging.testinbox.email",
            databaseUrl = url,
            databaseUsername = "testinbox_staging",
            databasePassword = "fixture-not-a-real-db-password--1",
            storageEndpoint = "https://objects.staging.internal",
            storageAccessKey = "fixture-not-a-real-s3-access-key",
            storageSecretKey = "fixture-not-a-real-s3-secret----1",
            publicBaseUrl = null,
            bootstrapApiKey = null,
            waitWindowCap = Duration.ofSeconds(60),
            proxyReadTimeout = Duration.ofSeconds(120),
            edgeRequestCeiling = null,
            limitsEnabled = true,
            activeProfiles = setOf("staging"),
            publicSurface = false,
            databaseDriverProperties = mapOf("ApplicationName" to "testinbox-ingestion:node-1:storage-v1"),
        )

    private fun withUrl(query: String) = gateway.copy(databaseUrl = "$url?$query")

    private fun withProperty(
        key: String,
        value: String,
    ) = gateway.copy(databaseDriverProperties = gateway.databaseDriverProperties!! + (key to value))

    private fun refusedSettings(settings: DeploymentSettings) = DriverProtocolSafety.check(settings).map { it.setting }

    @Test
    fun `the driver defaults are accepted, whether left implicit or stated in any case`() {
        DeploymentSafety.validate(gateway).shouldBeEmpty()
        DriverProtocolSafety.check(withUrl("sslmode=require&ApplicationName=x")).shouldBeEmpty()
        DriverProtocolSafety.check(withUrl("autosave=never&preferQueryMode=extended")).shouldBeEmpty()
        DriverProtocolSafety.check(withUrl("AUTOSAVE=Never&preferquerymode=EXTENDED")).shouldBeEmpty()
        DriverProtocolSafety.check(withProperty("autosave", "never")).shouldBeEmpty()
        DriverProtocolSafety.check(withProperty("PreferQueryMode", "Extended")).shouldBeEmpty()
    }

    @Test
    fun `autosave other than never is refused in the URL, whatever its case`() {
        for (query in listOf("autosave=always", "autosave=conservative", "AutoSave=ALWAYS", "AUTOSAVE=Conservative")) {
            refusedSettings(withUrl("sslmode=require&$query")) shouldBe listOf("spring.datasource.url")
        }
    }

    @Test
    fun `autosave other than never is refused as a data-source property, whatever its case`() {
        for ((key, value) in listOf(
            "autosave" to "always",
            "autosave" to "conservative",
            "AutoSave" to "Always",
            "AUTOSAVE" to "CONSERVATIVE",
        )) {
            refusedSettings(withProperty(key, value)) shouldBe listOf("spring.datasource.hikari.data-source-properties.$key")
        }
    }

    @Test
    fun `preferQueryMode other than extended is refused in the URL and as a property, whatever its case`() {
        for (mode in listOf("simple", "SIMPLE", "extendedForPrepared", "extendedCacheEverything")) {
            refusedSettings(withUrl("preferQueryMode=$mode")) shouldBe listOf("spring.datasource.url")
            refusedSettings(withUrl("PREFERQUERYMODE=$mode")) shouldBe listOf("spring.datasource.url")
            refusedSettings(withProperty("preferQueryMode", mode)) shouldBe
                listOf("spring.datasource.hikari.data-source-properties.preferQueryMode")
            refusedSettings(withProperty("preferquerymode", mode)) shouldBe
                listOf("spring.datasource.hikari.data-source-properties.preferquerymode")
        }
    }

    @Test
    fun `a percent-encoded or repeated parameter cannot slip past the URL check`() {
        // pgJDBC decodes the query, and a later duplicate overrides an earlier one.
        refusedSettings(withUrl("autosave=%61lways")) shouldBe listOf("spring.datasource.url")
        refusedSettings(withUrl("pref%65rQueryMode=simple")) shouldBe listOf("spring.datasource.url")
        refusedSettings(withUrl("autosave=never&autosave=always")) shouldBe listOf("spring.datasource.url")
        refusedSettings(withUrl("autosave=")) shouldBe listOf("spring.datasource.url")
    }

    @Test
    fun `every offending setting is reported at once, in the URL and the properties`() {
        val both =
            withUrl("autosave=always&preferQueryMode=simple").copy(
                databaseDriverProperties = mapOf("autosave" to "conservative"),
            )
        refusedSettings(both) shouldBe
            listOf("spring.datasource.url", "spring.datasource.url", "spring.datasource.hikari.data-source-properties.autosave")
    }

    @Test
    fun `the refusal goes through the shared deployment check and names the default it requires, not the value`() {
        // Wired into DeploymentSafety.validate, which both deployables call: removing it fails here.
        val violations = DeploymentSafety.validate(withProperty("autosave", "always-fixture-value"))
        violations.map { it.setting } shouldBe listOf("spring.datasource.hikari.data-source-properties.autosave")
        val rendered = DeploymentSafety.describe(violations)
        rendered shouldContain "autosave to a value other than pgJDBC's default 'never'"
        rendered shouldNotContain "always-fixture-value"
        DeploymentSafety.validate(withUrl("preferQueryMode=simple")).map { it.setting } shouldBe listOf("spring.datasource.url")
    }

    @Test
    fun `a deployable that does not run T1 declares no driver properties and is not checked`() {
        // The API passes null: the one-round-trip commit is the gateway's.
        DriverProtocolSafety.check(withUrl("autosave=always").copy(databaseDriverProperties = null)).shouldBeEmpty()
    }
}
