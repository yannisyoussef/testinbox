package email.testinbox.api.account

import email.testinbox.application.identity.EmailLoginCrypto
import email.testinbox.application.port.EmailLoginDelivery
import email.testinbox.application.port.EmailLoginEvent
import email.testinbox.application.port.EmailLoginObservations
import email.testinbox.application.port.EmailLogins
import email.testinbox.application.port.HumanAuthenticationLock
import email.testinbox.application.port.HumanIdentities
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.application.usecase.RequestEmailLogin
import email.testinbox.application.usecase.ResolveVerifiedIdentity
import email.testinbox.application.usecase.VerifyEmailLogin
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.Locale

@ConfigurationProperties("testinbox.accounts.email")
class EmailLoginProperties(
    val enabled: Boolean = false,
    val verificationKeyBase64: String = "",
    val smtp: LoginSmtpProperties = LoginSmtpProperties(),
) {
    override fun toString(): String = "EmailLoginProperties(enabled=$enabled, credentials=[REDACTED])"
}

class LoginSmtpProperties(
    val host: String = "",
    val port: Int = 587,
    val username: String = "",
    val password: String = "",
    val from: String = "",
) {
    override fun toString(): String = "LoginSmtpProperties([REDACTED])"
}

class EmailAccountFlows(
    val request: RequestEmailLogin,
    val verify: VerifyEmailLogin,
)

enum class EmailAccountAvailability { DISABLED, INCOMPLETE, READY }

class EmailAccountRuntime(
    val availability: EmailAccountAvailability,
    val flows: EmailAccountFlows? = null,
)

@Configuration
@EnableConfigurationProperties(EmailLoginProperties::class)
class EmailAccountWiring(
    private val store: EmailLogins,
    private val registration: HumanAuthenticationLock,
    private val identities: HumanIdentities,
    private val sessions: HumanSessions,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    @Bean
    fun emailAccountRuntime(
        accounts: HumanAccountProperties,
        properties: EmailLoginProperties,
        delivery: ObjectProvider<EmailLoginDelivery>,
        registry: MeterRegistry,
    ): EmailAccountRuntime {
        val runtime =
            when {
                !accounts.enabled || !properties.enabled -> EmailAccountRuntime(EmailAccountAvailability.DISABLED)
                accounts.canonicalOrigin == null -> EmailAccountRuntime(EmailAccountAvailability.INCOMPLETE)
                else -> configured(properties, delivery, registry)
            }
        registry.gauge("testinbox_email_login_configured", runtime) { if (it.availability == EmailAccountAvailability.READY) 1.0 else 0.0 }
        return runtime
    }

    private fun configured(
        properties: EmailLoginProperties,
        delivery: ObjectProvider<EmailLoginDelivery>,
        registry: MeterRegistry,
    ): EmailAccountRuntime {
        val crypto =
            try {
                SmtpLoginDelivery.validate(properties.smtp)
                EmailLoginCrypto.fromBase64(properties.verificationKeyBase64)
            } catch (_: IllegalArgumentException) {
                return EmailAccountRuntime(EmailAccountAvailability.INCOMPLETE)
            }
        val counters =
            EmailLoginEvent.entries.associateWith { event ->
                registry.counter("testinbox_email_login_total", "outcome", event.name.lowercase(Locale.ROOT))
            }
        val observations = EmailLoginObservations { counters.getValue(it).increment() }
        val sender = delivery.ifAvailable ?: SmtpLoginDelivery(properties.smtp)
        val resolver = ResolveVerifiedIdentity(identities, registration, transactions, clock)
        val issuer = IssueHumanSession(sessions, transactions, clock)
        return EmailAccountRuntime(
            EmailAccountAvailability.READY,
            EmailAccountFlows(
                RequestEmailLogin(store, registration, transactions, crypto, sender, clock, observations),
                VerifyEmailLogin(store, registration, transactions, crypto, resolver, issuer, clock, observations),
            ),
        )
    }
}
