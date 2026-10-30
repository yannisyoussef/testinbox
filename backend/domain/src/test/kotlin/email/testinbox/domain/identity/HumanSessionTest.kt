package email.testinbox.domain.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class HumanSessionTest {
    private val start = Instant.parse("2026-10-29T12:00:00Z")
    private val account = HumanAccountId(UUID.randomUUID())

    private fun session() = HumanSession("a".repeat(64), account, start, start.plus(HumanSession.ABSOLUTE_LIFETIME), start)

    @Test
    fun `idle boundary is exclusive and backwards time is refused`() {
        val session = session()
        session.usableAt(start) shouldBe true
        session.usableAt(start.minusNanos(1)) shouldBe false
        session.usableAt(start.plus(HumanSession.IDLE_LIFETIME).minusNanos(1)) shouldBe true
        session.usableAt(start.plus(HumanSession.IDLE_LIFETIME)) shouldBe false
    }

    @Test
    fun `absolute boundary is exclusive even after recent activity`() {
        val session = session()
        val recentlyUsed = session.copy(lastSeenAt = session.expiresAt.minusSeconds(1))
        recentlyUsed.usableAt(session.expiresAt.minusNanos(1)) shouldBe true
        recentlyUsed.usableAt(session.expiresAt) shouldBe false
    }

    @Test
    fun `invalid verifier or inconsistent lifetimes cannot form a session`() {
        shouldThrow<IllegalArgumentException> { session().copy(verifier = "plaintext") }
        shouldThrow<IllegalArgumentException> { session().copy(expiresAt = start) }
        shouldThrow<IllegalArgumentException> { session().copy(expiresAt = start.plus(HumanSession.ABSOLUTE_LIFETIME).plusSeconds(1)) }
        shouldThrow<IllegalArgumentException> { session().copy(lastSeenAt = start.minusSeconds(1)) }
        shouldThrow<IllegalArgumentException> { session().copy(lastSeenAt = session().expiresAt) }
    }

    @Test
    fun `only active verified accounts are eligible`() {
        HumanAccount(account, "owner@example.invalid", HumanAccountState.ACTIVE, true).eligible() shouldBe true
        HumanAccountState.entries.forEach { state ->
            HumanAccount(account, "owner@example.invalid", state, false).eligible() shouldBe false
        }
        listOf(HumanAccountState.SUSPENDED, HumanAccountState.DELETION_PENDING).forEach { state ->
            HumanAccount(account, "owner@example.invalid", state, true).eligible() shouldBe false
        }
    }
}
