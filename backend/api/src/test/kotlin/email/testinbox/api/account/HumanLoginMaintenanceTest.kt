package email.testinbox.api.account

import email.testinbox.application.deployment.AppliedSchema
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.deployment.SchemaHistory
import email.testinbox.application.deployment.SchemaVersion
import email.testinbox.application.port.EmailLogins
import email.testinbox.application.port.HumanAuthenticationLock
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.PurgeEmailLoginState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HumanLoginMaintenanceTest {
    private val store = Mockito.mock(EmailLogins::class.java)
    private val registry = SimpleMeterRegistry()
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    private fun maintenance(applied: List<String>): HumanLoginMaintenance =
        HumanLoginMaintenance(
            SchemaCompatibility(SchemaHistory { AppliedSchema(applied, emptyList(), true) }, SchemaVersion("14")),
            PurgeEmailLoginState(
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
    fun `a node awaiting its migration never touches the challenge table`() {
        maintenance(listOf("13")).run()
        Mockito.verifyNoInteractions(store)
        registry
            .get("testinbox_account_cleanup_total")
            .tag("outcome", "success")
            .counter()
            .count() shouldBe 0.0
    }

    @Test
    fun `cleanup runs independently of account activation and records success`() {
        HumanAccountProperties().enabled shouldBe false
        maintenance(listOf("14")).run()
        Mockito.verify(store).purge(now.minusSeconds(24 * 3600))
        registry
            .get("testinbox_account_cleanup_total")
            .tag("outcome", "success")
            .counter()
            .count() shouldBe 1.0
    }

    @Test
    fun `cleanup failure remains visible rather than being counted as success`() {
        Mockito.doThrow(IllegalStateException("cleanup failed")).`when`(store).purge(now.minusSeconds(24 * 3600))
        shouldThrow<IllegalStateException> { maintenance(listOf("14")).run() }
        registry
            .get("testinbox_account_cleanup_total")
            .tag("outcome", "failure")
            .counter()
            .count() shouldBe 1.0
        registry
            .get("testinbox_account_cleanup_total")
            .tag("outcome", "success")
            .counter()
            .count() shouldBe 0.0
    }
}
