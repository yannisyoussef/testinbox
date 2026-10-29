package email.testinbox.api.account

import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.port.HumanAuthenticationLock
import email.testinbox.application.port.ProviderLoginStates
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.PurgeProviderLoginState
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Configuration
class ProviderLoginMaintenanceWiring {
    @Bean
    fun purgeProviderLoginState(
        store: ProviderLoginStates,
        registration: HumanAuthenticationLock,
        tx: TransactionRunner,
        clock: Clock,
    ): PurgeProviderLoginState = PurgeProviderLoginState(store, registration, tx, clock)
}

@Component
class ProviderLoginMaintenance(
    private val schema: SchemaCompatibility,
    private val purge: PurgeProviderLoginState,
    registry: MeterRegistry,
) {
    private val success = registry.counter("testinbox_provider_state_cleanup_total", "outcome", "success")
    private val failure = registry.counter("testinbox_provider_state_cleanup_total", "outcome", "failure")

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun run() {
        if (!schema.status().compatible) return
        try {
            purge.execute()
            success.increment()
        } catch (problem: Exception) {
            failure.increment()
            throw problem
        }
    }
}
