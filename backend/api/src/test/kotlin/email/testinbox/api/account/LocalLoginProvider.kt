package email.testinbox.api.account

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/** Local wire protocol fixture, with real RSA signatures and actual token/user-info HTTP. */
class LocalLoginProvider : AutoCloseable {
    private val json = jacksonObjectMapper()
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("fixture").generate()
    private val wrongKey: RSAKey = RSAKeyGenerator(2048).keyID("fixture").generate()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: String get() = "http://127.0.0.1:${server.address.port}"
    val exchanges = AtomicInteger()
    val forbiddenRedirects = AtomicInteger()
    var mode = "valid"
    var nonce = ""
    var challenge = ""
    var email = "private@example.invalid"
    var githubId = 123456L
    var pkceValid = false

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    fun protocols(): ProviderProtocols {
        fun client(id: String) =
            ClientRegistration
                .withRegistrationId(id)
                .clientId("fixture-client")
                .clientSecret("fixture-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .redirectUri("https://console.example.invalid/account/oauth/callback/$id")
                .authorizationUri("$base/$id/authorize")
                .tokenUri("$base/$id/token")
        val google =
            client("google")
                .scope("openid", "email")
                .issuerUri("https://accounts.google.com")
                .jwkSetUri("$base/jwks")
                .userNameAttributeName("sub")
                .build()
        val github =
            client("github")
                .scope("read:user", "user:email")
                .userInfoUri("$base/github/user")
                .userNameAttributeName("id")
                .build()
        return ProviderProtocols(InMemoryClientRegistrationRepository(google, github))
    }

    private fun handle(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/jwks" -> {
                respond(exchange, JWKSet(key.toPublicJWK()).toString())
            }

            "/google/token", "/github/token" -> {
                token(exchange)
            }

            "/github/user" -> {
                check(exchange.requestHeaders.getFirst("Authorization") == "Bearer fixture-access")
                val id: Any = if (mode == "invalid-id") 12.5 else githubId
                val profile = mapOf("id" to id, "login" to "mutable-name", "email" to "untrusted-profile@example.invalid")
                respond(exchange, json.writeValueAsString(profile))
            }

            "/github/user/emails" -> {
                check(exchange.requestHeaders.getFirst("Authorization") == "Bearer fixture-access")
                val contact = mapOf("email" to email, "primary" to true, "verified" to (mode != "unverified"))
                val result = if (mode == "missing-email") emptyList() else listOf(contact)
                respond(exchange, json.writeValueAsString(result))
            }

            "/forbidden" -> {
                forbiddenRedirects.incrementAndGet()
                respond(exchange, "{}")
            }

            else -> {
                respond(exchange, "{}", 404)
            }
        }
    }

    private fun token(exchange: HttpExchange) {
        exchanges.incrementAndGet()
        val form = parameters(exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8))
        val verifier = form["code_verifier"] ?: ""
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        pkceValid = verifier.length >= 43 && hash == challenge
        check(form["client_id"] == "fixture-client" && form["client_secret"] == "fixture-secret")
        val provider = if (exchange.requestURI.path.startsWith("/google")) "google" else "github"
        check(form["redirect_uri"] == "https://console.example.invalid/account/oauth/callback/$provider")
        if (!pkceValid || form["code"] != "fixture-code" || mode == "token-error") {
            respond(exchange, """{"error":"invalid_grant"}""", 400)
        } else if (mode == "redirect") {
            exchange.responseHeaders.set("Location", "$base/forbidden")
            respond(exchange, "{}", 302)
        } else {
            val response =
                mutableMapOf<String, Any>(
                    "access_token" to "fixture-access",
                    "token_type" to "Bearer",
                    "expires_in" to 300,
                )
            if (exchange.requestURI.path.startsWith("/google")) response["id_token"] = idToken()
            respond(exchange, json.writeValueAsString(response))
        }
    }

    private fun idToken(): String {
        val now = Instant.now()
        val issuer = if (mode == "issuer") "https://wrong.example.invalid" else "https://accounts.google.com"
        val expiry = now.plusSeconds(if (mode == "expired") -3600 else 300)
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .subject("google-subject")
                .audience(if (mode == "audience") "another-client" else "fixture-client")
                .issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(expiry))
                .claim(
                    "nonce",
                    if (mode == "missing-nonce") {
                        null
                    } else if (mode == "nonce") {
                        "wrong"
                    } else {
                        nonce
                    },
                ).claim("email", if (mode in setOf("external", "hosted")) "user@example.invalid" else "fixture@gmail.com")
                .claim("email_verified", mode != "unverified")
        if (mode == "hosted") claims.claim("hd", "example.invalid")
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture").build(), claims.build())
        jwt.sign(RSASSASigner(if (mode == "signature") wrongKey else key))
        return jwt.serialize()
    }

    private fun respond(
        exchange: HttpExchange,
        body: String,
        status: Int = 200,
    ) {
        exchange.responseHeaders.set("Content-Type", "application/json")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        fun parameters(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate { entry ->
                val fields = entry.split('=', limit = 2)
                val name = URLDecoder.decode(fields[0], StandardCharsets.UTF_8)
                val value = URLDecoder.decode(fields.getOrElse(1) { "" }, StandardCharsets.UTF_8)
                name to value
            }
    }
}
