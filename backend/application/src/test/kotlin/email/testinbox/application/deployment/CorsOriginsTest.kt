package email.testinbox.application.deployment

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

class CorsOriginsTest {
    @Test
    fun `exact origins are accepted, with or without a non-default port`() {
        CorsOrigins
            .violations(
                listOf(
                    "https://docs.testinbox.email",
                    "http://localhost:3000",
                    "https://docs.example.test:8443",
                    "http://127.0.0.1:5173",
                    "https://xn--bcher-kva.example",
                ),
            ).shouldBeEmpty()
    }

    @Test
    fun `an empty list is valid - it is the default and means CORS off`() {
        CorsOrigins.violations(emptyList()).shouldBeEmpty()
        CorsOrigins.requireValid(emptyList())
    }

    @Test
    fun `a wildcard is always refused, in any position`() {
        for (entry in listOf("*", "https://*.testinbox.email", "https://docs.testinbox.email*")) {
            CorsOrigins.violations(listOf(entry)).single().problem shouldContain "wildcard"
        }
    }

    @Test
    fun `anything that is not exactly an origin is refused`() {
        val refused =
            listOf(
                "",
                " ",
                "docs.testinbox.email",
                "//docs.testinbox.email",
                "ftp://docs.testinbox.email",
                "HTTPS://docs.testinbox.email",
                "https://Docs.TestInbox.email",
                "https://docs.testinbox.email/",
                "https://docs.testinbox.email/try-it",
                "https://docs.testinbox.email?x=1",
                "https://docs.testinbox.email#top",
                "https://user@docs.testinbox.email",
                "https://user:pass@docs.testinbox.email",
                "https://docs.testinbox.email:443",
                "http://localhost:80",
                "https://docs.testinbox.email:",
                "https://docs.testinbox.email:0",
                "https://docs.testinbox.email:08443",
                "https://docs.testinbox.email:70000",
                "https://-docs.testinbox.email",
                "https://docs_testinbox.email",
                "https://[::1]:3000",
                " https://docs.testinbox.email",
                "https:docs.testinbox.email",
                "null",
            )
        for (entry in refused) {
            CorsOrigins.violations(listOf(entry)) shouldHaveSize 1
        }
    }

    @Test
    fun `every bad entry is reported, under the property's name`() {
        val violations = CorsOrigins.violations(listOf("https://ok.example", "*", "https://bad.example/"))
        violations shouldHaveSize 2
        violations.map { it.setting }.toSet() shouldBe setOf(CorsOrigins.PROPERTY)
    }

    @Test
    fun `startup failure names the property and the environment variable`() {
        val failure = shouldThrow<IllegalStateException> { CorsOrigins.requireValid(listOf("*")) }
        failure.message!! shouldContain "testinbox.cors.allowed-origins"
        failure.message!! shouldContain "TESTINBOX_CORS_ALLOWED_ORIGINS"
    }

    // --- production (DeploymentSafety) -------------------------------------------

    private val production =
        DeploymentSettings(
            environment = "production",
            activeProfiles = setOf("production", "deployed"),
            mailDomain = ProductionPolicy.MAIL_DOMAIN,
            databaseUrl = "jdbc:postgresql://db.prod.internal:5432/testinbox",
            databaseUsername = "testinbox_production",
            databasePassword = "fixture-not-a-real-db-password--1",
            storageEndpoint = "https://objects.prod.internal",
            storageAccessKey = "fixture-not-a-real-s3-access-key",
            storageSecretKey = "fixture-not-a-real-s3-secret----1",
            publicBaseUrl = "https://api.testinbox.email",
            bootstrapApiKey = null,
            waitWindowCap = Duration.ofSeconds(60),
            proxyReadTimeout = Duration.ofSeconds(100),
            edgeRequestCeiling = Duration.ofSeconds(100),
            limitsEnabled = true,
            createBucket = false,
            requireDatabaseSessionTimeout = true,
            eventsVisibilityLag = Duration.ofSeconds(90),
        )

    private fun productionCors(vararg origins: String) =
        DeploymentSafety.validate(production.copy(corsAllowedOrigins = origins.toList())).filter {
            it.setting == CorsOrigins.PROPERTY
        }

    @Test
    fun `production accepts the docs site, and no CORS at all`() {
        DeploymentSafety.validate(production).shouldBeEmpty()
        DeploymentSafety.validate(production.copy(corsAllowedOrigins = listOf("https://docs.testinbox.email"))).shouldBeEmpty()
    }

    @Test
    fun `production refuses a plaintext origin`() {
        productionCors("http://docs.testinbox.email").single().problem shouldContain "https"
    }

    @Test
    fun `production refuses a loopback origin in every spelling`() {
        for (origin in listOf(
            "https://localhost",
            "https://localhost:3000",
            "https://127.0.0.1",
            "https://127.0.0.2:8443",
            "https://docs.localhost",
        )) {
            productionCors(origin) shouldHaveSize 1
        }
    }

    @Test
    fun `production refuses a staging or rehearsal origin`() {
        productionCors("https://docs.staging.testinbox.email").single().problem shouldContain "staging"
        productionCors("https://rehearsal.example").single().problem shouldContain "rehearsal"
    }

    @Test
    fun `production reports every bad origin at once`() {
        productionCors("http://docs.testinbox.email", "https://localhost:3000", "https://docs.testinbox.email") shouldHaveSize 2
    }

    @Test
    fun `staging may name a local or plaintext docs build - only production is strict`() {
        val staging =
            production.copy(
                environment = "staging",
                activeProfiles = setOf("staging", "deployed"),
                mailDomain = "staging.testinbox.email",
                publicBaseUrl = "https://api.staging.testinbox.email",
                corsAllowedOrigins = listOf("http://localhost:3000", "https://docs.staging.testinbox.email"),
            )
        DeploymentSafety.validate(staging).filter { it.setting == CorsOrigins.PROPERTY }.shouldBeEmpty()
    }
}
