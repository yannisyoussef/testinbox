package email.testinbox.application.storage

import email.testinbox.domain.WorkspaceId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** ADR-035 §4/§5 write slots: capacity, fairness, all-or-nothing, poison, and the ambiguity read. */
class WriteSlotsTest {
    private val persisted = AtomicInteger()
    private val a = WorkspaceId(UUID.randomUUID())
    private val b = WorkspaceId(UUID.randomUUID())

    private fun slots(
        max: Int = 4,
        perWorkspace: Int = 2,
        wait: Duration = Duration.ofMillis(100),
        ambiguous: () -> Int = { persisted.get() },
    ) = WriteSlots(max, perWorkspace, wait, ambiguous = ambiguous)

    @Test
    fun `rows no live node answers for count against every node's slots, and exhaust them before resolution`() {
        // Contract Lemma 3 (TI-STORAGE-006E): 2 live nodes with 16 slots each and 10 rows left by a
        // replaced node id may hold at most 2 x 16 late objects in all, not 2 x 16 + 10.
        val orphaned = AtomicInteger(0)
        val node = WriteSlots(4, 4, Duration.ofMillis(100), orphaned = { orphaned.get() }) { 1 }
        orphaned.set(2)
        val held = node.acquire(setOf(a)) // 1 own + 2 orphaned + 1 in use = 4
        shouldThrow<StorageUnavailableException> { node.acquire(setOf(b)) }.reason shouldBe StorageUnavailableReason.SLOT_WAIT
        held.close()
        node.exhaustedBeforeResolution() shouldBe false
        orphaned.set(3)
        node.exhaustedBeforeResolution() shouldBe true // 1 own + 3 orphaned: no slot left for anyone
        // Without the rule (OFF, TENANT_LIMITS) nothing is counted and nothing is checked early.
        val off = WriteSlots(4, 4, Duration.ofMillis(100)) { 1 }
        off.exhaustedBeforeResolution() shouldBe false
        off.acquire(setOf(a)).close()
    }

    @Test
    fun `persisted ambiguity occupies slots, and a full node is a 451, never a capacity refusal`() {
        val slots = slots()
        persisted.set(3)
        val held = slots.acquire(setOf(a))
        slots.occupied() shouldBe 4

        shouldThrow<StorageUnavailableException> { slots.acquire(setOf(b)) }.reason shouldBe StorageUnavailableReason.SLOT_WAIT
        held.close()
    }

    @Test
    fun `one workspace holds at most its share, and others still get slots`() {
        val slots = slots()
        val first = slots.acquire(setOf(a))
        val second = slots.acquire(setOf(a))
        shouldThrow<StorageUnavailableException> { slots.acquire(setOf(a)) }
        slots.acquire(setOf(b)).close()
        first.close()
        slots.acquire(setOf(a)).close()
        second.close()
    }

    @Test
    fun `a multi-workspace event takes every workspace permit or none`() {
        val slots = slots()
        val a1 = slots.acquire(setOf(a))
        val a2 = slots.acquire(setOf(a))
        // b has room, a does not: nothing is taken for b either.
        shouldThrow<StorageUnavailableException> { slots.acquire(setOf(a, b)) }
        slots.acquire(setOf(b)).close()
        slots.acquire(setOf(b)).close()
        slots.occupied() shouldBe 2
        a1.close()
        a2.close()
    }

    @Test
    fun `a poisoned slot stays occupied for the life of the process, counted once`() {
        val slots = slots()
        slots.acquire(setOf(a)).poison()
        slots.occupied() shouldBe 1
        slots.poisoned() shouldBe 1
        val other = slots.acquire(setOf(a)) // the workspace share is returned; the slot is not
        other.close()
        slots.occupied() shouldBe 1
    }

    @Test
    fun `every in-flight slot has its own index, a returned index is reused, and a poisoned one never is (escrow lanes)`() {
        val slots = slots(max = 4, perWorkspace = 4)
        val first = List(3) { slots.acquire(setOf(a)) }
        first.map { it.index } shouldBe listOf(0, 1, 2)
        first[1].close()
        val again = slots.acquire(setOf(b))
        again.index shouldBe 1
        first[0].poison()
        first[2].close()
        again.close()
        // Index 0 stays with the poisoned slot for the life of the process.
        val later = List(3) { slots.acquire(setOf(a)) }
        later.map { it.index }.toSet() shouldBe setOf(1, 2, 3)
    }

    @Test
    fun `a waiter gets a slot as soon as one is returned`() {
        val slots = slots(max = 1, perWorkspace = 1, wait = Duration.ofSeconds(10))
        val held = slots.acquire(setOf(a))
        val waiting = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val waiter =
                pool.submit<Unit> {
                    waiting.countDown()
                    slots.acquire(setOf(b)).close()
                }
            waiting.await(5, TimeUnit.SECONDS)
            held.close()
            waiter.get(5, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `an ambiguity recorded while a count was being read is not missed when its slot comes back`() {
        // The count is read OUTSIDE the lock. An ambiguity persisted, then its slot
        // returned, while a stale count is in flight must not free that slot twice.
        val slots = slots(max = 1, perWorkspace = 1, wait = Duration.ofMillis(300))
        val held = slots.acquire(setOf(a))
        val reading = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val stale =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val racing =
            WriteSlots(1, 1, Duration.ofMillis(300)) {
                if (stale.compareAndSet(true, false)) {
                    reading.countDown()
                    proceed.await(5, TimeUnit.SECONDS)
                    0 // stale: taken before the ambiguity below was persisted
                } else {
                    persisted.get()
                }
            }
        val racingHeld = racing.acquire(setOf(a))
        stale.set(true) // the next count read is the stale one
        val pool = Executors.newSingleThreadExecutor()
        try {
            val attempt = pool.submit<Result<WriteSlots.Slot>> { runCatching { racing.acquire(setOf(b)) } }
            reading.await(5, TimeUnit.SECONDS)
            persisted.set(1) // the upload ended ambiguously: persisted first...
            racingHeld.close() // ...then its slot returns
            proceed.countDown()

            attempt.get(5, TimeUnit.SECONDS).isFailure shouldBe true // re-read: still occupied by the ambiguity
        } finally {
            pool.shutdownNow()
            held.close()
        }
    }
}
