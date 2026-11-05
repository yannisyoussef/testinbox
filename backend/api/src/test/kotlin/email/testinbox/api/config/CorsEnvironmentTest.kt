package email.testinbox.api.config

import email.testinbox.api.web.ApiCors
import email.testinbox.api.web.ApiCorsFilter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource

/** The shipped YAML, the documented variable, and the startup refusal — without a database. */
class CorsEnvironmentTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TestInboxProperties::class)
    class PropertiesOnly

    private fun context(values: Map<String, Any> = emptyMap()): ApplicationContextRunner =
        ApplicationContextRunner().withUserConfiguration(PropertiesOnly::class.java).withInitializer { context ->
            val sources = context.environment.propertySources
            sources.addFirst(SystemEnvironmentPropertySource("cors-test-env", values))
            YamlPropertySourceLoader().load("shipped-api-yaml", ClassPathResource("application.yaml")).forEach(sources::addLast)
        }

    @Test
    fun `the shipped default is no origins, which is CORS off`() {
        context().run { context ->
            val properties = context.getBean(TestInboxProperties::class.java)
            properties.cors.allowedOrigins.shouldBeEmpty()
            ApiCors.configuration(properties.cors.allowedOrigins).shouldBeNull()
        }
    }

    @Test
    fun `the documented variable binds a comma-separated list`() {
        context(mapOf("TESTINBOX_CORS_ALLOWED_ORIGINS" to "https://docs.testinbox.email,http://localhost:3000")).run { context ->
            context.getBean(TestInboxProperties::class.java).cors.allowedOrigins shouldBe
                listOf("https://docs.testinbox.email", "http://localhost:3000")
        }
    }

    @Test
    fun `a bad origin refuses startup, naming the property`() {
        for (bad in listOf("*", "https://docs.testinbox.email/", "https://Docs.testinbox.email")) {
            val properties = TestInboxProperties(cors = TestInboxProperties.Cors(allowedOrigins = listOf(bad)))
            val failure =
                shouldThrow<IllegalStateException> {
                    ApiCorsFilter(
                        properties,
                        tools.jackson.databind.json
                            .JsonMapper(),
                    )
                }
            failure.message!! shouldContain "testinbox.cors.allowed-origins"
        }
    }

    @Test
    fun `credentials are never allowed and the lists are the contract's`() {
        val config = ApiCors.configuration(listOf("https://docs.testinbox.email"))!!
        config.allowCredentials shouldBe false
        config.allowedOrigins shouldBe listOf("https://docs.testinbox.email")
        config.allowedMethods shouldBe listOf("GET", "POST", "DELETE")
        config.maxAge shouldBe 600L
    }
}
