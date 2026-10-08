package email.testinbox.ingestion.ops

import email.testinbox.application.port.ActivationInventory
import email.testinbox.application.port.DatabaseClock
import email.testinbox.application.port.DatabaseSession
import email.testinbox.application.port.StorageAmbiguity
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageNodeRow
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageNode
import email.testinbox.application.storage.StorageNodeLifecycle
import email.testinbox.application.storage.activation.ActivationGuard
import email.testinbox.application.storage.activation.ActivationWatch
import email.testinbox.application.storage.activation.ExpectedNodes
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * ADR-035 §14 Phase 4 / TI-STORAGE-006 §21–§23: the gateway's node runtime
 * runs the activation check BEFORE it accepts anything and then on every
 * heartbeat, and a non-OFF node whose barrier is broken sets the guard that
 * `GuardedStorage` consults. Deleting the `activation.run()` calls would make
 * this test fail; nothing sleeps — the heartbeat is invoked directly.
 */
class StorageNodeRuntimeActivationTest {
    private val now = Instant.parse("2026-10-08T10:00:00Z")

    private class Inventory(
        var sessions: List<DatabaseSession>,
    ) : ActivationInventory {
        override fun sessions() = sessions

        override fun applicationRole() = "testinbox_app"

        override fun nodes() = listOf(StorageNodeRow("ingest-1", "storage-v1", Instant.parse("2026-10-08T09:59:55Z"), false))

        override fun now() = Instant.parse("2026-10-08T10:00:00Z")
    }

    private val healthy = listOf(DatabaseSession("testinbox-ingestion:ingest-1:storage-v1", "testinbox_app"))
    private val old = healthy + DatabaseSession("PostgreSQL JDBC Driver", "testinbox_app")

    private fun runtime(
        inventory: Inventory,
        guard: ActivationGuard,
        enforcement: StorageEnforcement,
    ): StorageNodeRuntime {
        val ambiguity = mock(StorageAmbiguity::class.java)
        val lifecycle = StorageNodeLifecycle(ambiguity, StorageNode("ingest-1", UUID.randomUUID()))
        val watch =
            ActivationWatch(inventory, ExpectedNodes(emptySet(), setOf("ingest-1")), enforcement, guard, StorageProtocolMetrics.NOOP)
        return StorageNodeRuntime(
            lifecycle,
            StorageBreaker(),
            mock(StorageInspection::class.java),
            DatabaseClock { now },
            StorageProtocolMetrics.NOOP,
            heartbeatEvery = Duration.ofHours(1), // never fires on its own in this test
            offsetEvery = Duration.ofHours(1),
            activation = watch,
        )
    }

    private fun StorageNodeRuntime.heartbeatNow() {
        val method = StorageNodeRuntime::class.java.getDeclaredMethod("heartbeat").apply { isAccessible = true }
        method.invoke(this)
    }

    @Test
    fun `the first check runs at start - a non-OFF node next to an old binary fails closed before its first DATA`() {
        val guard = ActivationGuard()
        val runtime = runtime(Inventory(old), guard, StorageEnforcement.TENANT_LIMITS)
        try {
            runtime.start()
            checkNotNull(guard.violated()) shouldContain "PostgreSQL JDBC Driver"
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `every heartbeat re-checks, so the guard clears when the old binary is gone and sets when one appears`() {
        val inventory = Inventory(healthy)
        val guard = ActivationGuard()
        val runtime = runtime(inventory, guard, StorageEnforcement.ALL)
        try {
            runtime.start()
            guard.violated() shouldBe null
            inventory.sessions = old
            runtime.heartbeatNow()
            checkNotNull(guard.violated()) shouldContain "PostgreSQL JDBC Driver"
            inventory.sessions = healthy
            runtime.heartbeatNow()
            guard.violated() shouldBe null
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `under OFF the same old binary is observed and the guard stays clear (Phase 2 overlap)`() {
        val guard = ActivationGuard()
        val runtime = runtime(Inventory(old), guard, StorageEnforcement.OFF)
        try {
            runtime.start()
            runtime.heartbeatNow()
            guard.violated() shouldBe null
        } finally {
            runtime.stop()
        }
    }
}
