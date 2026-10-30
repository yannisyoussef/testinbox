package email.testinbox.api.account

import email.testinbox.application.port.HumanAuthenticationLock
import email.testinbox.application.port.HumanIdentities
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.port.ProviderLoginStates
import email.testinbox.application.port.TransactionRunner
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.util.Base64

class ProviderAccountWiringTest {
    private val store = Mockito.mock(ProviderLoginStates::class.java)
    private val registration = Mockito.mock(HumanAuthenticationLock::class.java)
    private val identities = Mockito.mock(HumanIdentities::class.java)
    private val sessions = Mockito.mock(HumanSessions::class.java)
    private val tx = Mockito.mock(TransactionRunner::class.java)
    private val registry = SimpleMeterRegistry()
    private val accountConfig = HumanAccountProperties(true, "https://console.example.invalid")
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val credential = ProviderClientProperties(true, "fixture-id", "fixture-credential")

    private fun runtime(
        properties: ProviderAccountProperties,
        accounts: HumanAccountProperties = accountConfig,
    ): ProviderAccountRuntime =
        ProviderAccountWiring(
            store,
            registration,
            identities,
            sessions,
            tx,
            email.testinbox.application.Clocks
                .utcMicros(),
        ).providerAccountRuntime(
            accounts,
            properties,
            registry,
            DefaultListableBeanFactory().getBeanProvider(ProviderProtocols::class.java),
            email.testinbox.application.TestInboxConfig(mailDomain = "inbox.testinbox.test"),
        )

    @Test
    fun `disabled or incomplete configuration never invents a provider or touches account storage`() {
        runtime(ProviderAccountProperties()).flows shouldBe null
        runtime(ProviderAccountProperties("not-base64", credential, credential)).flows shouldBe null
        runtime(ProviderAccountProperties(key, credential, credential), HumanAccountProperties()).flows shouldBe null
        runtime(
            ProviderAccountProperties(key, credential, credential),
            HumanAccountProperties(true, "http://unsafe.invalid"),
        ).flows shouldBe
            null
        Mockito.verifyNoInteractions(store, registration, identities, sessions, tx)
    }

    @Test
    fun `only configured providers become ready with fixed official endpoints and minimal scopes`() {
        val flows = requireNotNull(runtime(ProviderAccountProperties(key, credential)).flows)
        val google = requireNotNull(flows.protocols.registrations.findByRegistrationId("google"))
        google.redirectUri shouldBe "https://console.example.invalid/account/oauth/callback/google"
        google.scopes shouldBe setOf("openid", "email")
        google.providerDetails.issuerUri shouldBe "https://accounts.google.com"
        google.providerDetails.authorizationUri shouldBe "https://accounts.google.com/o/oauth2/v2/auth"
        google.providerDetails.tokenUri shouldBe "https://oauth2.googleapis.com/token"
        google.providerDetails.jwkSetUri shouldBe "https://www.googleapis.com/oauth2/v3/certs"
        flows.protocols.registrations.findByRegistrationId("github") shouldBe null
        registry
            .get("testinbox_provider_login_configured")
            .tag("provider", "google")
            .gauge()
            .value() shouldBe 1.0
        registry
            .get("testinbox_provider_login_configured")
            .tag("provider", "github")
            .gauge()
            .value() shouldBe 0.0
        Mockito.verifyNoInteractions(store, registration, identities, sessions, tx)
    }

    @Test
    fun `GitHub registration cannot request repositories or substitute arbitrary endpoints`() {
        val flows = requireNotNull(runtime(ProviderAccountProperties(key, github = credential)).flows)
        val github = requireNotNull(flows.protocols.registrations.findByRegistrationId("github"))
        github.scopes shouldBe setOf("read:user", "user:email")
        github.redirectUri shouldBe "https://console.example.invalid/account/oauth/callback/github"
        github.providerDetails.authorizationUri shouldBe "https://github.com/login/oauth/authorize"
        github.providerDetails.tokenUri shouldBe "https://github.com/login/oauth/access_token"
        github.providerDetails.userInfoEndpoint.uri shouldBe "https://api.github.com/user"
        flows.protocols.registrations.findByRegistrationId("google") shouldBe null
        Mockito.verifyNoInteractions(store, registration, identities, sessions, tx)
    }
}
