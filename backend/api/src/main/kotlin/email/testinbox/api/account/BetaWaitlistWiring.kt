package email.testinbox.api.account

import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.port.BetaWaitlist
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.ManageBetaWaitlist
import email.testinbox.domain.identity.BetaWaitlistPolicy
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("testinbox.accounts.waitlist")
class BetaWaitlistProperties(
    val retention: Duration = Duration.ofDays(30),
    val maximum: Int = 1000,
) {
    val policy: BetaWaitlistPolicy? = runCatching { BetaWaitlistPolicy(retention, maximum) }.getOrNull()
}

@Configuration
@EnableConfigurationProperties(BetaWaitlistProperties::class)
class BetaWaitlistWiring {
    @Bean
    fun manageBetaWaitlist(
        admissions: BetaAdmissions,
        store: BetaWaitlist,
        tx: TransactionRunner,
        clock: Clock,
    ): ManageBetaWaitlist = ManageBetaWaitlist(admissions, store, tx, clock)
}

@Component
class BetaWaitlistMaintenance(
    private val schema: SchemaCompatibility,
    private val manage: ManageBetaWaitlist,
    registry: MeterRegistry,
) {
    private val success = registry.counter("testinbox_waitlist_cleanup_total", "outcome", "success")
    private val failure = registry.counter("testinbox_waitlist_cleanup_total", "outcome", "failure")

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun run() {
        if (!schema.status().compatible) return
        try {
            manage.purge()
            success.increment()
        } catch (problem: Exception) {
            failure.increment()
            throw problem
        }
    }
}
