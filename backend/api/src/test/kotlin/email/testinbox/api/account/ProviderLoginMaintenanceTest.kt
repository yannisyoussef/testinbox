package email.testinbox.api.account

import email.testinbox.application.deployment.AppliedSchema
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.deployment.SchemaHistory
import email.testinbox.application.deployment.SchemaVersion
import email.testinbox.application.port.HumanAuthenticationLock
import email.testinbox.application.port.ProviderLoginStates
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.PurgeProviderLoginState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ProviderLoginMaintenanceTest {
    private val store = Mockito.mock(ProviderLoginStates::class.java)
    private val registry = SimpleMeterRegistry()
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    private fun maintenance(applied: List<String>): ProviderLoginMaintenance =
        ProviderLoginMaintenance(
            SchemaCompatibility(SchemaHistory { AppliedSchema(applied, emptyList(), true) }, SchemaVersion("15")),
            PurgeProviderLoginState(
                store,
                HumanAuthenticationLock { },
                object : TransactionRunner {
                    override fun <T> required(block: () -> T): T = block()
                },
                Clock.fixed(now, ZoneOffset.UTC),
            ),
            registry,
        )

    @Test
    fun `the state table is untouched before its migration is applied`() {
        maintenance(listOf("14")).run()
        Mockito.verifyNoInteractions(store)
    }

    @Test
    fun `cleanup erases expired payloads with the current instant even while login is disabled`() {
        maintenance(listOf("15")).run()
        Mockito.verify(store).purge(now.minusSeconds(24 * 3600), now)
        registry
            .get("testinbox_provider_state_cleanup_total")
            .tag("outcome", "success")
            .counter()
            .count() shouldBe 1.0
    }

    @Test
    fun `a failed state cleanup stays visible`() {
        Mockito.doThrow(IllegalStateException("cleanup failed")).`when`(store).purge(now.minusSeconds(24 * 3600), now)
        shouldThrow<IllegalStateException> { maintenance(listOf("15")).run() }
        registry
            .get("testinbox_provider_state_cleanup_total")
            .tag("outcome", "failure")
            .counter()
            .count() shouldBe 1.0
    }
}
