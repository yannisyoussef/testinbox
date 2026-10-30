package email.testinbox.api.account

import email.testinbox.application.port.AccountRequestBudgets
import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.application.usecase.AuthenticateHumanSession
import email.testinbox.application.usecase.ChargeAccountRequest
import email.testinbox.application.usecase.ResolveHumanWorkspace
import email.testinbox.application.usecase.RevokeHumanSession
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.time.Clock

@ConfigurationProperties("testinbox.accounts")
data class HumanAccountProperties(
    val enabled: Boolean = false,
    val publicOrigin: String = "",
    /** Shared with the website's /account proxy only; see AccountClientSource. Optional. */
    val clientAddressSecret: String = "",
) {
    override fun toString(): String = "HumanAccountProperties(enabled=$enabled, publicOrigin=$publicOrigin, clientAddressSecret=[REDACTED])"

    /** A configured client-address secret that fails [AccountClientSource.validate]; never the value. */
    val clientAddressSecretProblem: String? =
        clientAddressSecret.takeIf { it.isNotEmpty() }?.let { secret ->
            runCatching { AccountClientSource.validate(secret) }.exceptionOrNull()?.message
        }

    /**
     * Invalid account configuration disables this boundary, without taking down the Bearer API.
     * An invalid client-address secret counts: the operator meant every visitor to get its own
     * sign-in budget, and silently sharing one would let a single source lock everyone out.
     */
    val canonicalOrigin: String? =
        runCatching { URI(publicOrigin) }
            .getOrNull()
            ?.takeIf { clientAddressSecretProblem == null }
            ?.takeIf { origin ->
                origin.scheme == "https" && !origin.host.isNullOrBlank() &&
                    origin.host == origin.host.lowercase() && origin.rawUserInfo == null && origin.rawQuery == null &&
                    origin.rawFragment == null && origin.rawPath.isNullOrEmpty() && origin.port != 443 &&
                    publicOrigin == "https://${origin.rawAuthority}"
            }?.let { publicOrigin }
}

@Configuration
@EnableConfigurationProperties(HumanAccountProperties::class)
class HumanAccountWiring {
    @Bean
    fun authenticateHumanSession(
        sessions: HumanSessions,
        tx: TransactionRunner,
        clock: Clock,
    ): AuthenticateHumanSession = AuthenticateHumanSession(sessions, tx, clock)

    /** Absent means every sign-in shares the peer budget; an invalid one closes /account (see [HumanAccountProperties]). */
    @Bean
    fun accountClientSource(
        properties: HumanAccountProperties,
        registry: io.micrometer.core.instrument.MeterRegistry,
    ): AccountClientSource {
        properties.clientAddressSecretProblem?.let {
            org.slf4j.LoggerFactory
                .getLogger(AccountClientSource::class.java)
                .error("Account sign-in is disabled: {}", it)
        }
        return AccountClientSource(
            properties.clientAddressSecret.takeIf { properties.clientAddressSecretProblem == null }.orEmpty(),
            registry,
        )
    }

    @Bean
    fun chargeAccountRequest(
        authenticate: AuthenticateHumanSession,
        budgets: AccountRequestBudgets,
        tx: TransactionRunner,
    ): ChargeAccountRequest = ChargeAccountRequest(authenticate, budgets, tx)

    @Bean
    fun resolveHumanWorkspace(
        authenticate: AuthenticateHumanSession,
        admissions: BetaAdmissions,
        tx: TransactionRunner,
    ): ResolveHumanWorkspace = ResolveHumanWorkspace(authenticate, admissions, tx)

    @Bean
    fun revokeHumanSession(
        sessions: HumanSessions,
        tx: TransactionRunner,
    ): RevokeHumanSession = RevokeHumanSession(sessions, tx)

    @Bean
    fun cohortStorageReadiness(
        declarations: email.testinbox.application.storage.StorageDeclarations,
        jdbc: org.springframework.jdbc.core.simple.JdbcClient,
    ): email.testinbox.application.storage.CohortStorageReadiness {
        val policy =
            email.testinbox.application.storage.EffectiveStoragePolicy
                .footprint(declarations)
        val gate = email.testinbox.persistence.JdbcFootprintGate(jdbc)
        return email.testinbox.application.storage.CohortStorageReadiness {
            runCatching {
                val observed = gate.observe()
                policy != null && policy.unavailability(observed) == null && policy.cohort?.ready(observed?.cohort) == true
            }.getOrDefault(false)
        }
    }

    @Bean
    fun admitBetaParticipant(
        admissions: BetaAdmissions,
        tx: TransactionRunner,
        clock: Clock,
        capacity: email.testinbox.application.storage.CohortStorageReadiness,
    ): AdmitBetaParticipant = AdmitBetaParticipant(admissions, tx, clock, capacity)
}
