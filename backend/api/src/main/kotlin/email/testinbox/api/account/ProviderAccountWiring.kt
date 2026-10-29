package email.testinbox.api.account

import email.testinbox.application.identity.ProviderLoginCrypto
import email.testinbox.application.port.HumanAuthenticationLock
import email.testinbox.application.port.HumanIdentities
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.port.ProviderLoginStates
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.ConsumeProviderLoginState
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.application.usecase.RecordProviderLoginState
import email.testinbox.application.usecase.ResolveVerifiedIdentity
import email.testinbox.application.usecase.SignInWithVerifiedProvider
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import java.time.Clock
import java.util.Base64

enum class ProviderAccountEvent { STARTED, LIMITED, REJECTED, SIGNED_IN, DIFFERENT_METHOD, UNAVAILABLE }

@ConfigurationProperties("testinbox.accounts.oauth")
class ProviderAccountProperties(
    val stateKeyBase64: String = "",
    val google: ProviderClientProperties = ProviderClientProperties(),
    val github: ProviderClientProperties = ProviderClientProperties(),
) {
    override fun toString(): String = "ProviderAccountProperties(credentials=[REDACTED])"
}

class ProviderClientProperties(
    val enabled: Boolean = false,
    val clientId: String = "",
    val clientSecret: String = "",
) {
    fun configured(): Boolean = enabled && clientId.isNotBlank() && clientSecret.isNotBlank()

    override fun toString(): String = "ProviderClientProperties(enabled=$enabled, credentials=[REDACTED])"
}

class ProviderAccountFlows(
    val protocols: ProviderProtocols,
    val record: RecordProviderLoginState,
    val consume: ConsumeProviderLoginState,
    val signIn: SignInWithVerifiedProvider,
)

class ProviderAccountRuntime(
    val flows: ProviderAccountFlows?,
    private val registry: MeterRegistry,
) {
    fun observe(event: ProviderAccountEvent) {
        registry.counter("testinbox_provider_login_total", "outcome", event.name.lowercase(java.util.Locale.ROOT)).increment()
    }
}

@Configuration
@EnableConfigurationProperties(ProviderAccountProperties::class)
class ProviderAccountWiring(
    private val store: ProviderLoginStates,
    private val registration: HumanAuthenticationLock,
    private val identities: HumanIdentities,
    private val sessions: HumanSessions,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    @Bean
    fun providerAccountRuntime(
        accounts: HumanAccountProperties,
        properties: ProviderAccountProperties,
        registry: MeterRegistry,
        override: ObjectProvider<ProviderProtocols>,
    ): ProviderAccountRuntime {
        val flows =
            if (!accounts.enabled || accounts.canonicalOrigin == null) {
                null
            } else {
                val crypto = runCatching { ProviderLoginCrypto(Base64.getDecoder().decode(properties.stateKeyBase64)) }.getOrNull()
                val protocols = override.ifAvailable ?: registrations(accounts.canonicalOrigin, properties)?.let(::ProviderProtocols)
                if (crypto == null || protocols == null) {
                    null
                } else {
                    ProviderAccountFlows(
                        protocols,
                        RecordProviderLoginState(store, registration, tx, crypto, clock),
                        ConsumeProviderLoginState(store, tx, crypto, clock),
                        SignInWithVerifiedProvider(
                            ResolveVerifiedIdentity(identities, registration, tx, clock),
                            IssueHumanSession(sessions, tx, clock),
                            tx,
                        ),
                    )
                }
            }
        for (provider in listOf("google", "github")) {
            val ready = flows?.protocols?.registrations?.findByRegistrationId(provider) != null
            registry.gauge(
                "testinbox_provider_login_configured",
                listOf(
                    io.micrometer.core.instrument.Tag
                        .of("provider", provider),
                ),
                if (ready) 1 else 0,
            )
        }
        return ProviderAccountRuntime(flows, registry)
    }

    private fun registrations(
        origin: String,
        properties: ProviderAccountProperties,
    ): InMemoryClientRegistrationRepository? {
        val registrations = mutableListOf<ClientRegistration>()
        if (properties.google.configured()) {
            registrations +=
                client("google", origin, properties.google)
                    .scope("openid", "email")
                    .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("https://oauth2.googleapis.com/token")
                    .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                    .issuerUri("https://accounts.google.com")
                    .userNameAttributeName("sub")
                    .build()
        }
        if (properties.github.configured()) {
            registrations +=
                client("github", origin, properties.github)
                    .scope("read:user", "user:email")
                    .authorizationUri("https://github.com/login/oauth/authorize")
                    .tokenUri("https://github.com/login/oauth/access_token")
                    .userInfoUri("https://api.github.com/user")
                    .userNameAttributeName("id")
                    .build()
        }
        return registrations.takeIf { it.isNotEmpty() }?.let(::InMemoryClientRegistrationRepository)
    }

    private fun client(
        name: String,
        origin: String,
        properties: ProviderClientProperties,
    ): ClientRegistration.Builder =
        ClientRegistration
            .withRegistrationId(name)
            .clientId(properties.clientId)
            .clientSecret(properties.clientSecret)
            .clientAuthenticationMethod(
                ClientAuthenticationMethod.CLIENT_SECRET_POST,
            ).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("$origin/account/oauth/callback/$name")
            .clientName(name)
}
