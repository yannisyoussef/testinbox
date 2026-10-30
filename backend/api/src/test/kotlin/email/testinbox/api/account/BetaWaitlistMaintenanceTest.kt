package email.testinbox.api.account

import email.testinbox.application.deployment.AppliedSchema
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.deployment.SchemaHistory
import email.testinbox.application.deployment.SchemaVersion
import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.port.BetaWaitlist
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.ManageBetaWaitlist
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BetaWaitlistMaintenanceTest {
    private val store = Mockito.mock(BetaWaitlist::class.java)
    private val admissions = Mockito.mock(BetaAdmissions::class.java)
    private val registry = SimpleMeterRegistry()
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    private fun maintenance(applied: List<String>) =
        BetaWaitlistMaintenance(
            SchemaCompatibility(SchemaHistory { AppliedSchema(applied, emptyList(), true) }, SchemaVersion("16")),
            ManageBetaWaitlist(
                admissions,
                store,
                object : TransactionRunner {
                    override fun <T> required(block: () -> T): T = block()
                },
                Clock.fixed(now, ZoneOffset.UTC),
            ),
            registry,
        )

    @Test fun `cleanup does not query a schema before the waitlist migration`() {
        maintenance(listOf("15")).run()
        Mockito.verifyNoInteractions(admissions, store)
    }

    @Test fun `cleanup runs without signup traffic and reports its successful run`() {
        maintenance(listOf("16")).run()
        Mockito.verify(admissions).lockPolicy()
        Mockito.verify(store).purge(now)
        registry
            .get("testinbox_waitlist_cleanup_total")
            .tag("outcome", "success")
            .counter()
            .count() shouldBe 1.0
    }

    @Test fun `cleanup failure remains visible and never masquerades as success`() {
        Mockito.doThrow(IllegalStateException("fixture cleanup failure")).`when`(store).purge(now)
        shouldThrow<IllegalStateException> { maintenance(listOf("16")).run() }
        registry
            .get("testinbox_waitlist_cleanup_total")
            .tag("outcome", "failure")
            .counter()
            .count() shouldBe 1.0
        registry
            .get("testinbox_waitlist_cleanup_total")
            .tag("outcome", "success")
            .counter()
            .count() shouldBe 0.0
    }

    @Test fun `invalid policy never silently becomes a valid enrollment budget`() {
        BetaWaitlistProperties(maximum = 1001).policy shouldBe null
        BetaWaitlistProperties(retention = java.time.Duration.ZERO).policy shouldBe null
        BetaWaitlistProperties().policy?.maximum shouldBe 1000
    }
}
