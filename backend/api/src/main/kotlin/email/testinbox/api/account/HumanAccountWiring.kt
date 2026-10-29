package email.testinbox.api.account

import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.application.usecase.AuthenticateHumanSession
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
) {
    /** Invalid account configuration disables this boundary, without taking down the Bearer API. */
    val canonicalOrigin: String? =
        runCatching { URI(publicOrigin) }
            .getOrNull()
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

    @Bean
    fun revokeHumanSession(
        sessions: HumanSessions,
        tx: TransactionRunner,
    ): RevokeHumanSession = RevokeHumanSession(sessions, tx)

    @Bean
    fun admitBetaParticipant(
        admissions: BetaAdmissions,
        tx: TransactionRunner,
        clock: Clock,
    ): AdmitBetaParticipant = AdmitBetaParticipant(admissions, tx, clock)
}
