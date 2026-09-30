package email.testinbox.application.storage

import email.testinbox.domain.WorkspaceId
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ADR-035 §4/§5 write slots, per node.
 *
 * An event takes ONE slot before T1 and holds it through its uploads and T2,
 * so waiting for a slot never spends a reservation's deadline.
 *
 * - **Capacity.** At most [maxSlots] events write at once. Unresolved
 *   persisted ambiguity for this node ([ambiguous]) occupies slots too, so a
 *   slot whose upload ended ambiguously is not handed out again until that
 *   ambiguity is verified. Because this is read from the database, it
 *   survives breaker cycles and process restarts (§9: the H bound).
 * - **Fairness.** One workspace holds at most [perWorkspace] of them. A
 *   multi-workspace event counts against every workspace it involves. It
 *   takes all of its workspace permits at once under one lock, all or
 *   nothing, so two events can never deadlock by taking permits in different
 *   orders.
 * - **Bounded wait.** Waiting is bounded at [waitTimeout] (`W_slot`). Running
 *   out of time is infrastructure, not capacity.
 */
class WriteSlots(
    private val maxSlots: Int = StorageProtocol.MAX_CONCURRENT_WRITES,
    private val perWorkspace: Int = StorageProtocol.MAX_EVENTS_PER_WORKSPACE,
    private val waitTimeout: Duration = StorageProtocol.SLOT_WAIT,
    /** Unresolved ambiguity rows of this node. Read on every attempt, never cached. */
    private val ambiguous: () -> Int,
) {
    init {
        require(maxSlots > 0 && perWorkspace in 1..maxSlots) { "invalid slot configuration" }
    }

    private val lock = ReentrantLock()
    private val released = lock.newCondition()
    private var inUse = 0

    /** Slots lost to an ambiguity that could not be persisted: held until the process ends. */
    private var poisoned = 0
    private val perWorkspaceInUse = HashMap<WorkspaceId, Int>()

    inner class Slot internal constructor(
        val workspaces: Set<WorkspaceId>,
    ) : AutoCloseable {
        private var closed = false

        /** Returns the slot. Its ambiguity, if any, is already persisted and keeps occupying capacity. */
        override fun close() =
            lock.withLock {
                if (closed) return@withLock
                closed = true
                inUse--
                workspaces.forEach { ws -> perWorkspaceInUse.merge(ws, -1) { a, b -> (a + b).takeIf { it > 0 } } }
                released.signalAll()
            }

        /**
         * The upload ended ambiguously but its ambiguity could NOT be persisted.
         * The slot must not be reusable, so it stays occupied for the life of
         * this process. A restart then treats the generation as dead and
         * records keyless ambiguity for it (§9).
         */
        fun poison() =
            lock.withLock {
                if (closed) return@withLock
                closed = true
                poisoned++
                workspaces.forEach { ws -> perWorkspaceInUse.merge(ws, -1) { a, b -> (a + b).takeIf { it > 0 } } }
                released.signalAll()
            }
    }

    /** Throws [StorageUnavailableException] (`SLOT_WAIT`) when no slot frees up within `W_slot`. */
    fun acquire(workspaces: Set<WorkspaceId>): Slot {
        require(workspaces.isNotEmpty()) { "an event involves at least one workspace" }
        val deadline = System.nanoTime() + waitTimeout.toNanos()
        lock.withLock {
            while (true) {
                if (fits(workspaces)) {
                    inUse++
                    workspaces.forEach { perWorkspaceInUse.merge(it, 1, Int::plus) }
                    return Slot(workspaces)
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    throw StorageUnavailableException(
                        StorageUnavailableReason.SLOT_WAIT,
                        "no write slot within ${waitTimeout.toMillis()} ms",
                    )
                }
                // Ambiguity resolves outside this process, so re-check at least every POLL.
                released.await(minOf(remaining, POLL.toNanos()), TimeUnit.NANOSECONDS)
            }
        }
    }

    private fun fits(workspaces: Set<WorkspaceId>): Boolean =
        inUse + poisoned + ambiguous() < maxSlots && workspaces.all { (perWorkspaceInUse[it] ?: 0) < perWorkspace }

    /** Slots taken by live events, poisoned slots and persisted ambiguity (for metrics and tests). */
    fun occupied(): Int = lock.withLock { inUse + poisoned + ambiguous() }

    private companion object {
        val POLL: Duration = Duration.ofMillis(250)
    }
}
