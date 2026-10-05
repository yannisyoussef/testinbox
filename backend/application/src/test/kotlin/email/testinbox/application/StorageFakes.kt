package email.testinbox.application

import email.testinbox.application.port.AmbiguityRecord
import email.testinbox.application.port.DatabaseClock
import email.testinbox.application.port.InboxStorageUsage
import email.testinbox.application.port.IncompleteUpload
import email.testinbox.application.port.LockedReservation
import email.testinbox.application.port.ReleasableReservation
import email.testinbox.application.port.ServerTime
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageAmbiguity
import email.testinbox.application.port.StorageCommitFence
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageLatch
import email.testinbox.application.port.StorageReservations
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.storage.GuardedStorage
import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageNode
import email.testinbox.application.storage.WriteSlots
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.storage.StorageUsage
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * In-memory ADR-035 protocol ports for application unit tests. The real
 * transactions, locks and SQL are proven against PostgreSQL and MinIO in the
 * persistence, storage and ingestion suites; these fakes keep the same
 * contracts so the orchestration can be driven without them.
 */
class InMemoryStorage(
    private val inboxes: InMemoryInboxRepository,
    private val messages: InMemoryMessageRepository,
    private val clock: Clock,
) {
    val blobs = InMemoryBlobStore()
    val ambiguity = InMemoryStorageAmbiguity(clock)
    val reservations = InMemoryStorageReservations(messages, ambiguity, clock)
    val inspection = InMemoryStorageInspection(blobs, clock)
    val breaker = StorageBreaker()
    val databaseClock = DatabaseClock { clock.instant() }
    val node = StorageNode("test-node", UUID.fromString("00000000-0000-0000-0000-000000000003"))

    /** Committed usage that the admission snapshot adds to reservations, per workspace (inboxes 0). */
    val committed = HashMap<WorkspaceId, Long>()

    val admissionStore =
        object : StorageAdmissionStore {
            override fun <R : Any> admit(
                scope: StorageAdmissionScope,
                decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
            ): R {
                val rows = reservations.rows.values
                val snapshot =
                    StorageUsageSnapshot(
                        t0 = clock.instant(),
                        global = StorageUsage(committed.values.sum(), rows.sumOf { it.bytes }),
                        workspaces =
                            scope.workspaceIds.associateWith { ws ->
                                StorageUsage(committed[ws] ?: 0, rows.filter { it.workspaceId == ws }.sumOf { it.bytes })
                            },
                        inboxes =
                            scope.inboxIds.associateWith { ib ->
                                InboxStorageUsage(
                                    inboxes.inboxes[ib]?.workspaceId,
                                    StorageUsage(0, rows.filter { it.inboxId == ib }.sumOf { it.bytes }),
                                )
                            },
                    )
                val plan = decide(snapshot)
                plan.reservations.forEach { draft ->
                    reservations.rows[draft.messageId] =
                        InMemoryStorageReservations.Row(
                            draft.messageId,
                            draft.workspaceId,
                            draft.inboxId,
                            draft.objectKeys,
                            draft.bytes,
                            "RESERVED",
                            draft.writeDeadlineAt,
                            null,
                            null,
                        )
                }
                return plan.outcome
            }
        }

    fun guarded(
        transactions: TransactionRunner,
        enforcement: StorageEnforcement = StorageEnforcement.OFF,
        policy: StorageCapacityPolicy = StorageCapacityPolicy.ADR_035_REFERENCE,
        slots: WriteSlots = WriteSlots(ambiguous = { ambiguity.unresolvedFor(node.nodeId) }),
        hook: IngestSyncHook = IngestSyncHook.NONE,
    ) = GuardedStorage(
        admission = StorageAdmission(admissionStore, policy, enforcement),
        reservations = reservations,
        ambiguity = ambiguity,
        latch = ambiguity,
        blobs = blobs,
        inspection = inspection,
        slots = slots,
        breaker = breaker,
        node = node,
        transactions = transactions,
        clock = databaseClock,
        hook = hook,
    )
}

class InMemoryStorageReservations(
    private val messages: InMemoryMessageRepository,
    private val ambiguity: InMemoryStorageAmbiguity,
    private val clock: Clock,
) : StorageCommitFence,
    StorageReservations {
    data class Row(
        val messageId: MessageId,
        val workspaceId: WorkspaceId,
        val inboxId: InboxId,
        val objectKeys: List<String>,
        val bytes: Long,
        var state: String,
        val writeDeadlineAt: Instant,
        var releaseNotBefore: Instant?,
        var firstUploadAt: Instant?,
    )

    val rows = LinkedHashMap<MessageId, Row>()
    val refusals = HashMap<InboxId, Int>()
    val lastRefusal = HashMap<InboxId, StorageRefusalReason>()

    override fun lockForCommit(ids: Collection<MessageId>) =
        ids.sortedBy { it.value }.mapNotNull { rows[it] }.map { LockedReservation(it.messageId, it.bytes, it.state) }

    override fun lockInboxes(ids: Collection<InboxId>) = Unit

    override fun recordRefusals(refusals: Map<InboxId, StorageRefusalReason>) {
        refusals.forEach { (inbox, reason) ->
            this.refusals.merge(inbox, 1, Int::plus)
            lastRefusal[inbox] = reason
        }
    }

    override fun consume(ids: Collection<MessageId>) {
        ids.forEach { check(rows[it]?.state == "RESERVED") { "fence did not hold" } }
        ids.forEach { rows.remove(it) }
    }

    override fun releaseDuplicates(ids: Collection<MessageId>) =
        ids.forEach { id ->
            rows[id]?.takeIf { it.state == "RESERVED" }?.apply {
                state = "RELEASING"
                releaseNotBefore = clock.instant()
            }
        }

    override fun markUploadStarted(id: MessageId): Boolean {
        val row = rows[id]?.takeIf { it.state == "RESERVED" } ?: return false
        row.firstUploadAt = row.firstUploadAt ?: clock.instant()
        return true
    }

    override fun releaseAbandoned(ids: Collection<MessageId>) = releaseDuplicates(ids)

    override fun expireOverdue(settle: Duration): Int {
        val due = rows.values.filter { it.state == "RESERVED" && it.writeDeadlineAt.isBefore(clock.instant()) }
        due.forEach {
            it.state = "RELEASING"
            it.releaseNotBefore = maxOf(it.releaseNotBefore ?: Instant.MIN, it.writeDeadlineAt.plus(settle))
        }
        return due.size
    }

    override fun releasable(
        horizon: Instant,
        limit: Int,
    ): List<MessageId> =
        rows.values
            .filter { it.state == "RELEASING" && !it.releaseNotBefore!!.isAfter(horizon) }
            .map { it.messageId }
            .sortedBy { it.value.toString() }
            .take(limit)

    override fun <T : Any> withReleasable(
        id: MessageId,
        horizon: Instant,
        work: (ReleasableReservation) -> T,
    ): T? =
        rows[id]
            ?.takeIf { it.state == "RELEASING" && !it.releaseNotBefore!!.isAfter(horizon) }
            ?.let { work(ReleasableReservation(it.messageId, it.workspaceId, it.objectKeys, it.releaseNotBefore!!)) }

    override fun release(id: MessageId) {
        rows[id]?.takeIf { it.state == "RELEASING" }?.let { rows.remove(id) }
    }

    override fun postpone(
        id: MessageId,
        until: Instant,
    ) {
        rows[id]?.let { it.releaseNotBefore = maxOf(it.releaseNotBefore ?: until, until) }
    }

    override fun holdForClockOffset(
        offset: Duration,
        settle: Duration,
    ): Int {
        var moved = 0
        rows.values.forEach {
            val hold = it.writeDeadlineAt.plus(settle).plus(offset.abs())
            if (it.releaseNotBefore == null || it.releaseNotBefore!!.isBefore(hold)) {
                it.releaseNotBefore = hold
                moved++
            }
        }
        return moved
    }

    var clockEpisode: Duration? = null

    override fun recordClockEpisode(offset: Duration) {
        clockEpisode = maxOf(clockEpisode ?: Duration.ZERO, offset.abs())
    }

    override fun applyClockEpisode(settle: Duration): Int? {
        val episode = clockEpisode ?: return null
        val held = holdForClockOffset(episode, settle)
        clockEpisode = null
        return held
    }

    override fun messageExists(id: MessageId) = messages.exists(id)

    override fun reservationExists(id: MessageId) = id in rows

    override fun isOrphan(
        id: MessageId,
        key: String,
    ) = !messages.exists(id) && id !in rows && ambiguity.records.none { it.objectKey == key && !it.resolved }

    override fun countsByState() =
        rows.values
            .groupingBy { it.state }
            .eachCount()
            .mapValues { it.value.toLong() }
}

class InMemoryStorageAmbiguity(
    private val clock: Clock,
) : StorageAmbiguity,
    StorageLatch {
    data class Record(
        val id: Long,
        val nodeId: String,
        val objectKey: String?,
        val bytes: Long,
        val ambiguousAt: Instant,
        val verifyAt: Instant,
        var resolved: Boolean = false,
        var resolvedAt: Instant? = null,
    )

    val records = mutableListOf<Record>()
    val generations = LinkedHashMap<Pair<String, UUID>, Boolean>() // clean shutdown?
    var latchReason: String? = null
    var failRecording = false

    override fun record(
        nodeId: String,
        objectKey: String?,
        bytes: Long,
        verifyAfter: Duration,
    ) {
        check(!failRecording) { "database unavailable" }
        records += Record(records.size + 1L, nodeId, objectKey, bytes, clock.instant(), clock.instant().plus(verifyAfter))
    }

    override fun unresolvedFor(nodeId: String) = records.count { it.nodeId == nodeId && !it.resolved }

    override fun unresolvedTotal() = records.count { !it.resolved }

    override fun due(limit: Int) =
        records
            .filter { !it.resolved && !it.verifyAt.isAfter(clock.instant()) }
            .take(limit)
            .map { AmbiguityRecord(it.id, it.nodeId, it.objectKey, it.ambiguousAt) }

    override fun resolve(id: Long) {
        records.first { it.id == id }.let {
            it.resolved = true
            it.resolvedAt = clock.instant()
        }
    }

    override fun resolveCommitted(id: Long) {
        records.removeIf { it.id == id && !it.resolved }
    }

    override fun wasAmbiguous(
        key: String,
        within: Duration,
    ) = records.any { it.objectKey == key && (!it.resolved || it.resolvedAt!!.isAfter(clock.instant().minus(within))) }

    override fun oldestUnresolvedAge(): Duration? =
        records
            .filter {
                !it.resolved
            }.minOfOrNull { it.ambiguousAt }
            ?.let { Duration.between(it, clock.instant()) }

    override fun registerGeneration(
        nodeId: String,
        generation: UUID,
        capability: String,
    ) {
        generations[nodeId to generation] = false
    }

    override fun heartbeat(
        nodeId: String,
        generation: UUID,
        capability: String,
    ): Boolean = generations.putIfAbsent(nodeId to generation, false) != null

    override fun markCleanShutdown(
        nodeId: String,
        generation: UUID,
    ) {
        generations[nodeId to generation] = true
    }

    override fun recoverDeadGenerations(
        nodeId: String?,
        current: UUID?,
        staleAfter: Duration,
        slots: Int,
        verifyAfter: Duration,
    ): Int = 0

    override fun latched() = latchReason

    override fun latch(reason: String) {
        if (latchReason == null) latchReason = reason
    }
}

class InMemoryStorageInspection(
    private val blobs: InMemoryBlobStore,
    private val clock: Clock,
) : StorageInspection {
    var witnessSucceeds = true
    var offset: Duration = Duration.ZERO
    val incomplete = mutableListOf<IncompleteUpload>()

    override fun objectExists(key: String) = key in blobs.blobs

    override fun incompleteUploadExists(key: String) = incomplete.any { it.key == key }

    override fun deleteObject(key: String) {
        blobs.blobs.remove(key)
    }

    override fun incompleteUploads() = incomplete.toList()

    override fun abortIncompleteUpload(upload: IncompleteUpload) {
        incomplete.remove(upload)
    }

    override fun witness(probeKey: String) = witnessSucceeds

    override fun serverTime() = ServerTime(clock.instant().plus(offset), Duration.ZERO)

    override fun listedPayloadBytes() = blobs.blobs.values.sumOf { it.bytes.size.toLong() }
}
