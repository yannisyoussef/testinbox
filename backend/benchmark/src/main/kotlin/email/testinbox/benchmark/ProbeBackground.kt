package email.testinbox.benchmark

import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Who plays which part in a timing-probe scenario (security review S-C1, #136),
 * over the closed cohort: the hot workspace (flooded), the observer and the cold
 * workspace (probe targets), the legacy workspace (retention load), and every
 * OTHER workspace, which carries the background traffic. The run fails when a
 * role would collide with the legacy workspace or receive background traffic.
 */
class ProbeRoles(
    population: SeededPopulation,
    legacyWorkspace: UUID,
    reservedHotInboxes: Int,
) {
    private val byWorkspace: List<List<SeededInbox>> =
        population.inboxes
            .groupBy { it.workspaceId }
            .values
            .toList()

    init {
        check(byWorkspace.size >= MIN_WORKSPACES) { "the timing probes need at least $MIN_WORKSPACES workspaces" }
    }

    val hot: List<SeededInbox> = byWorkspace[0]
    val observer: List<SeededInbox> = byWorkspace[1]
    val cold: List<SeededInbox> = byWorkspace[2]

    /** Neither probe target is the legacy workspace: its retention load would bias it. */
    private val roleWorkspaces = setOf(hot.first().workspaceId, observer.first().workspaceId, cold.first().workspaceId)

    init {
        check(legacyWorkspace !in roleWorkspaces) { "a probe role is the legacy workspace, which carries retention load" }
    }

    /** Every workspace but the hot, observer, cold and legacy ones. */
    val background: List<SeededInbox> =
        population.inboxes.filter { it.workspaceId !in roleWorkspaces && it.workspaceId != legacyWorkspace }

    init {
        check(background.isNotEmpty()) { "no workspace is left for the background traffic" }
        check(background.none { it.workspaceId in roleWorkspaces || it.workspaceId == legacyWorkspace }) {
            "background traffic would reach a probe role or the legacy workspace"
        }
    }

    /** `inside`: each reserved hot inbox (the target) with the observer's own inbox, in rotation. */
    val inside: List<List<SeededInbox>> = hot.takeLast(reservedHotInboxes).map { listOf(it, observer.first()) }

    /** `cold`: the cold workspace's inboxes in rotation, as many as `inside` uses, so neither class hits a per-inbox refill. */
    val coldTarget: List<List<SeededInbox>> = cold.take(reservedHotInboxes).map { listOf(it) }

    private companion object {
        /** Hot, observer, cold, at least one background workspace. */
        const val MIN_WORKSPACES = 4
    }
}

/** One background event: the copies INGEST let through, and when its T1 ended (null when none ran). */
data class BackgroundResult(
    val admittedCopies: Int,
    val t1EndNanos: Long?,
    val unavailable: Boolean,
)

/** What the background traffic of one probe run achieved: evidence that the system was loaded. */
data class BackgroundSummary(
    val offeredRate: Double,
    val events: Int,
    val admittedCopies: Long,
    val admittedCopiesPerSecond: Double,
    val eventsReachingT1: Int,
    /** Due time to T1 commit, over the events whose T1 ran. */
    val t1: LatencySummary?,
    val unavailableRate: Double,
)

/**
 * The probe scenario's background (security review S-C1, must-fix 1): ordinary
 * traffic at the matrix point's offered rate, spread over the background
 * workspaces ([ProbeRoles.background]) with the recipient mix, through production
 * INGEST (in [send]) - so T1, the write slots and mode a's global lock are busy
 * while the probes run, as in production.
 */
class BackgroundLoad(
    private val inboxes: List<SeededInbox>,
    private val ratePerSecond: Double,
    private val durationSeconds: Int,
    private val workers: Int,
    private val mix: RecipientMix,
    private val seed: Long,
    private val send: (List<SeededInbox>) -> BackgroundResult,
) {
    private val events = AtomicInteger()
    private val admitted = AtomicLong()
    private val reached = AtomicInteger()
    private val unavailable = AtomicInteger()
    private val t1: MutableList<Long> = Collections.synchronizedList(ArrayList())
    private val elapsed = AtomicLong()
    private val thread = Thread(::drive, "bench-background").apply { isDaemon = true }

    fun start() = thread.start()

    fun join() = thread.join()

    private fun drive() {
        val count = Math.ceil(ratePerSecond * durationSeconds).toInt()
        val randoms = ThreadLocal.withInitial { Random(seed + Thread.currentThread().threadId()) }
        val report =
            OpenLoopScheduler(ratePerSecond, Arrivals.POISSON, seed).run(count, workers) { due ->
                val random = randoms.get()
                val recipients = minOf(mix.draw(random.nextDouble()), inboxes.size)
                val start = random.nextInt(inboxes.size - recipients + 1)
                val result = send(inboxes.subList(start, start + recipients))
                events.incrementAndGet()
                admitted.addAndGet(result.admittedCopies.toLong())
                result.t1EndNanos?.let {
                    reached.incrementAndGet()
                    t1 += it - due.scheduledNanos
                }
                if (result.unavailable) unavailable.incrementAndGet()
                !result.unavailable
            }
        elapsed.set(report.elapsedNanos)
    }

    fun summary(): BackgroundSummary {
        val seconds = elapsed.get() / NANOS_PER_SECOND
        val sent = events.get()
        return BackgroundSummary(
            offeredRate = ratePerSecond,
            events = sent,
            admittedCopies = admitted.get(),
            admittedCopiesPerSecond = if (seconds > 0) admitted.get() / seconds else 0.0,
            eventsReachingT1 = reached.get(),
            t1 = LatencySummary.ofNanos(t1),
            unavailableRate = if (sent == 0) 0.0 else unavailable.get().toDouble() / sent,
        )
    }

    private companion object {
        const val NANOS_PER_SECOND = 1e9
    }
}
