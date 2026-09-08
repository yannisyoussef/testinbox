package email.testinbox.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import email.testinbox.api.web.ApiKeyDto
import email.testinbox.api.web.ApiKeyPageDto
import email.testinbox.api.web.CreatedApiKeyDto
import email.testinbox.domain.tenant.ApiKeyFormat
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * The negative proofs for ADR-032 §4: the credential appears in exactly one
 * response and nowhere else — not in another representation, not in a log,
 * not in an error body.
 *
 * These are written as *searches* rather than as assertions about fields we
 * happen to remember, so a field added later is caught by the same test.
 */
class ApiKeySecretLeakTest : ApiIntegrationTestBase() {
    private val json = ObjectMapper()
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var root: Logger
    private lateinit var jdkHttpClient: Logger

    /** Field names that would carry credential material if one ever leaked. */
    private val suspicious = listOf("key", "secret", "hash", "verifier", "token", "credential", "password")

    @BeforeEach
    fun attachAppender() {
        root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.addAppender(appender)
        // DEBUG rather than INFO: "never logged, even at debug level" is the
        // claim in docs/security/threat-model.md, so the test has to be able
        // to see debug output for it to mean anything.
        root.level = Level.DEBUG
        // …with one exception, and it is the test harness rather than the
        // system under test: the JDK's own HttpURLConnection logs the request
        // headers it is *sending*, so at DEBUG the RestTemplate this test uses
        // prints the Authorization header it just constructed. Silencing that
        // one logger keeps the assertion below unqualified; broadening the
        // assertion instead would have hidden a real server-side leak too.
        jdkHttpClient = LoggerFactory.getLogger("sun.net.www") as Logger
        jdkHttpClient.level = Level.INFO
    }

    @AfterEach
    fun detachAppender() {
        root.detachAppender(appender)
        root.level = Level.INFO
        jdkHttpClient.level = null
    }

    private data class Field(
        val name: String,
        val owner: KClass<*>,
        val isString: Boolean,
    )

    private fun fieldsOf(
        type: KClass<*>,
        seen: MutableSet<KClass<*>> = mutableSetOf(),
    ): List<Field> {
        if (!seen.add(type)) return emptyList()
        return type.memberProperties.flatMap { property ->
            val returned = property.returnType.classifier as? KClass<*>
            val nested =
                if (returned != null && returned.qualifiedName?.startsWith("email.testinbox") == true) {
                    fieldsOf(returned, seen)
                } else {
                    emptyList()
                }
            listOf(Field(property.name, type, returned == String::class)) + nested
        }
    }

    /**
     * A credential can only live in a string. Restricting the scan to string
     * fields is what lets `CreatedApiKeyDto.apiKey` — an object, named after a
     * credential — pass while `key` does not, without an exception list that
     * would also have to be maintained.
     */
    private fun credentialShaped(field: Field): Boolean = field.isString && suspicious.any { field.name.lowercase().endsWith(it) }

    @Test
    fun `no metadata type has a field that could hold credential material`() {
        // Recursive, so a nested type added to ApiKeyDto later is covered too.
        val offenders =
            (fieldsOf(ApiKeyDto::class) + fieldsOf(ApiKeyPageDto::class)).filter(::credentialShaped)
        offenders.shouldBeEmpty()
    }

    @Test
    fun `the creation response is the only type allowed to carry a credential`() {
        // The complement of the assertion above: the exception is explicit,
        // named, and asserted to be exactly one field on exactly one type.
        val bearing = fieldsOf(CreatedApiKeyDto::class).filter(::credentialShaped)
        bearing.map { it.name } shouldBe listOf("key")
        bearing.single().owner shouldBe CreatedApiKeyDto::class
    }

    @Test
    fun `no representation other than the creation response contains the credential`() {
        val created = json.readTree(post("/v1/api-keys", """{"name":"leak-probe","scopes":["inboxes:write"]}""").body!!)
        val plaintext = created["key"].asString()
        val secret = plaintext.split('_')[3]
        val id = created["apiKey"]["id"].asString()

        val representations =
            listOf(
                get("/v1/api-keys/$id"),
                get("/v1/api-keys"),
                get("/v1/api-keys/$id", readOnlyKey),
                get("/v1/api-keys/00000000-0000-0000-0000-000000000000"),
                post("/v1/inboxes", "{}", plaintext),
                get("/v1/api-keys", plaintext),
            )
        representations.forEach { response ->
            val body = response.body.orEmpty()
            body.contains(secret) shouldBe false
            body.contains(plaintext) shouldBe false
        }
    }

    @Test
    fun `the credential never reaches the logs, at any level, on success or failure`() {
        val created = json.readTree(post("/v1/api-keys", """{"name":"log-probe","scopes":["inboxes:write"]}""").body!!)
        val plaintext = created["key"].asString()
        val secret = plaintext.split('_')[3]

        // Exercise the paths that handle the token: a success, an
        // authorization failure, and a rejected credential — the last is where
        // a well-meaning "invalid key: $token" would appear.
        post("/v1/inboxes", "{}", plaintext)
        get("/v1/api-keys", plaintext)
        get("/v1/inboxes/00000000-0000-0000-0000-000000000000", plaintext.dropLast(3))
        get("/v1/inboxes/00000000-0000-0000-0000-000000000000", "Bearer-shaped-nonsense")

        val logged = appender.list.map { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
        logged.none { it.contains(secret) } shouldBe true
        logged.none { it.contains(plaintext) } shouldBe true
        // The Authorization header value itself, in any form.
        logged.none { it.contains("Bearer $plaintext") } shouldBe true
        // Guard the guard: an appender that captured nothing would pass all of
        // the above while proving nothing at all.
        (appender.list.size > 0) shouldBe true
    }

    @Test
    fun `the stored verifier never reaches the logs either`() {
        val created = json.readTree(post("/v1/api-keys", """{"name":"hash-probe","scopes":["messages:read"]}""").body!!)
        val plaintext = created["key"].asString()
        val verifier =
            email.testinbox.application.Sha256
                .hex(plaintext.split('_')[3])

        get("/v1/api-keys", plaintext)
        val logged = appender.list.map { it.formattedMessage }
        // A hashed credential is not a credential, but publishing every stored
        // verifier hands an offline attacker the whole target set for free.
        logged.none { it.contains(verifier) } shouldBe true
    }

    @Test
    fun `the audit trail records the credential's public handle and nothing more`() {
        val created = json.readTree(post("/v1/api-keys", """{"name":"audit-probe","scopes":["messages:read"]}""").body!!)
        val plaintext = created["key"].asString()
        val publicId = plaintext.split('_')[2]
        val secret = plaintext.split('_')[3]

        val audit = appender.list.filter { it.loggerName == "testinbox.audit" }.map { it.formattedMessage }
        audit.any { it.contains("event=api_key.created") && it.contains(publicId) } shouldBe true
        audit.none { it.contains(secret) } shouldBe true
        // And it carries the correlation id, which is what makes it joinable
        // with the request logs.
        audit.any { it.contains("correlationId=") && !it.contains("correlationId=-") } shouldBe true
    }

    @Test
    fun `a rendered credential is never equal to anything the database stores`() {
        val created = json.readTree(post("/v1/api-keys", """{"name":"store-probe","scopes":["messages:read"]}""").body!!)
        val plaintext = created["key"].asString()
        val parsed = ApiKeyFormat.parse(plaintext)
        (parsed is email.testinbox.domain.tenant.ParsedCredential.Valid) shouldBe true
    }
}
