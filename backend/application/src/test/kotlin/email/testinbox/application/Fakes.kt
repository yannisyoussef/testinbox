package email.testinbox.application

import email.testinbox.application.port.AppendOutcome
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.ExactAddressReservations
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.InsertInboxOutcome
import email.testinbox.application.port.MessageCursor
import email.testinbox.application.port.MessageNotifier
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.NotifierHealth
import email.testinbox.application.port.RateDecision
import email.testinbox.application.port.RateLimiter
import email.testinbox.application.port.ReserveOutcome
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.port.WaitHandle
import email.testinbox.application.port.WaitSlot
import email.testinbox.application.port.WaitSlots
import email.testinbox.application.port.WakeOutcome
import email.testinbox.application.port.WorkspaceQuotaState
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.ExactReservation
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.inbox.ReservationStatus
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.message.Message
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(
    var now: Instant,
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advanceSeconds(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

object NoopTx : TransactionRunner {
    override fun <T> required(block: () -> T): T = block()
}

/**
 * Transaction runner with in-memory rollback, so all-or-nothing behaviour of a
 * multi-recipient inbound event (ADR-026) is testable without a database.
 */
class RollbackTx(
    private val messages: InMemoryMessageRepository,
) : TransactionRunner {
    override fun <T> required(block: () -> T): T {
        val messageSnapshot = messages.messages.toList()
        val notifySnapshot = messages.notifiedInboxes.toList()
        return try {
            block()
        } catch (e: Throwable) {
            messages.messages.clear()
            messages.messages += messageSnapshot
            messages.notifiedInboxes.clear()
            messages.notifiedInboxes += notifySnapshot
            throw e
        }
    }
}

class InMemoryInboxRepository : InboxRepository {
    val inboxes = LinkedHashMap<InboxId, Inbox>()
    var forcedAddressTakenCount = 0

    override fun insert(inbox: Inbox): InsertInboxOutcome {
        if (forcedAddressTakenCount > 0) {
            forcedAddressTakenCount--
            return InsertInboxOutcome.AddressTaken
        }
        val routableConflict =
            inboxes.values.any {
                it.address == inbox.address && it.state in setOf(InboxState.ACTIVE, InboxState.EXPIRING)
            }
        if (routableConflict) return InsertInboxOutcome.AddressTaken
        inboxes[inbox.id] = inbox
        return InsertInboxOutcome.Inserted
    }

    override fun findById(
        workspaceId: WorkspaceId,
        id: InboxId,
    ): Inbox? = inboxes[id]?.takeIf { it.workspaceId == workspaceId }

    override fun findReceivableByAddress(address: String): Inbox? =
        inboxes.values.firstOrNull {
            it.address == address && it.state in setOf(InboxState.ACTIVE, InboxState.EXPIRING)
        }

    override fun markDeleted(
        workspaceId: WorkspaceId,
        id: InboxId,
        now: Instant,
    ): Inbox? {
        val existing = findById(workspaceId, id) ?: return null
        inboxes[id] = existing.copy(state = InboxState.DELETED)
        return existing
    }

    override fun findExpiredActive(
        now: Instant,
        limit: Int,
    ): List<Inbox> = inboxes.values.filter { it.state == InboxState.ACTIVE && !it.expiresAt.isAfter(now) }.take(limit)

    override fun transitionToExpiring(
        id: InboxId,
        graceUntil: Instant,
    ): Boolean {
        val inbox = inboxes[id]?.takeIf { it.state == InboxState.ACTIVE } ?: return false
        inboxes[id] = inbox.copy(state = InboxState.EXPIRING, graceUntil = graceUntil)
        return true
    }

    override fun findExpiringPastGrace(
        now: Instant,
        limit: Int,
    ): List<Inbox> =
        inboxes.values
            .filter { it.state == InboxState.EXPIRING && it.graceUntil?.isAfter(now) == false }
            .take(limit)

    override fun transitionToExpired(id: InboxId): Boolean {
        val inbox = inboxes[id]?.takeIf { it.state == InboxState.EXPIRING } ?: return false
        inboxes[id] = inbox.copy(state = InboxState.EXPIRED)
        return true
    }

    override fun findHardDeletable(limit: Int): List<Inbox> =
        inboxes.values
            .filter { it.state == InboxState.EXPIRED || it.state == InboxState.DELETED }
            .take(limit)

    override fun hardDelete(id: InboxId) {
        inboxes.remove(id)
    }
}

class InMemoryReservations : ExactAddressReservations {
    val byLocalPart = LinkedHashMap<String, ExactReservation>()

    override fun reserve(
        reservation: ExactReservation,
        now: Instant,
    ): ReserveOutcome {
        val existing = byLocalPart[reservation.localPart]
        if (existing != null &&
            existing.status == ReservationStatus.COOLDOWN &&
            existing.availableAt?.isAfter(now) == false
        ) {
            byLocalPart.remove(reservation.localPart)
        }
        val blocking = byLocalPart[reservation.localPart]
        if (blocking != null) return ReserveOutcome.Conflict(blocking.availableAt)
        byLocalPart[reservation.localPart] = reservation
        return ReserveOutcome.Reserved
    }

    override fun startCooldown(
        inboxId: InboxId,
        availableAt: Instant,
    ) {
        val entry =
            byLocalPart.values.firstOrNull {
                it.inboxId == inboxId && it.status == ReservationStatus.ACTIVE
            } ?: return
        byLocalPart[entry.localPart] =
            entry.copy(status = ReservationStatus.COOLDOWN, availableAt = availableAt)
    }
}

class InMemoryMessageRepository : MessageRepository {
    // Concurrency tests drive this from several threads at once (concurrent
    // waiters, multi-recipient events), so the backing lists must tolerate
    // concurrent append-while-iterating rather than throwing.
    val messages: MutableList<Message> = java.util.concurrent.CopyOnWriteArrayList()
    val notifiedInboxes: MutableList<InboxId> = java.util.concurrent.CopyOnWriteArrayList()

    /** Envelope recipient whose append throws, simulating a mid-event persistence failure. */
    var failAppendForRecipient: String? = null

    override fun appendVisible(message: Message): AppendOutcome {
        if (message.envelopeTo == failAppendForRecipient) {
            error("injected persistence failure for ${message.envelopeTo}")
        }
        if (isProviderEventReplay(message)) return AppendOutcome.DuplicateProviderEvent
        messages += message
        notifiedInboxes += message.inboxId
        return AppendOutcome.Appended
    }

    /** ADR-026: provider-event identity is recipient-scoped. */
    private fun isProviderEventReplay(message: Message): Boolean {
        val eventId = message.providerMessageId ?: return false
        return messages.any {
            it.provider == message.provider && it.providerMessageId == eventId && it.envelopeTo == message.envelopeTo
        }
    }

    override fun findEarliestIdByFingerprint(
        inboxId: InboxId,
        fingerprint: String,
    ): MessageId? =
        messages
            .filter { it.inboxId == inboxId && it.contentFingerprint == fingerprint }
            .minByOrNull { it.receivedAt }
            ?.id

    override fun listVisible(inboxId: InboxId): List<Message> = messages.filter { it.inboxId == inboxId }.sortedBy { it.receivedAt }

    override fun listPage(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
        after: MessageCursor?,
        limit: Int,
    ): List<Message> =
        messages
            .filter { it.workspaceId == workspaceId && it.inboxId == inboxId }
            .sortedBy { it.receivedAt }
            .filter { after == null || it.receivedAt.isAfter(after.receivedAt) }
            .take(limit)

    override fun findById(
        workspaceId: WorkspaceId,
        id: MessageId,
    ): Message? = messages.firstOrNull { it.id == id && it.workspaceId == workspaceId }

    override fun exists(id: MessageId): Boolean = messages.any { it.id == id }
}

class InMemoryBlobStore : BlobStore {
    data class Entry(
        val bytes: ByteArray,
        val contentType: String,
        val storedAt: Instant,
    )

    val blobs = LinkedHashMap<String, Entry>()
    var storedAtClock: Clock = Clock.systemUTC()
    val putOrder = mutableListOf<String>()

    override fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        blobs[key] = Entry(bytes, contentType, storedAtClock.instant())
        putOrder += key
    }

    override fun get(key: String): ByteArray? = blobs[key]?.bytes

    override fun delete(key: String) {
        blobs.remove(key)
    }

    override fun deletePrefix(prefix: String) {
        blobs.keys.removeAll { it.startsWith(prefix) }
    }

    override fun listKeysOlderThan(
        prefix: String,
        olderThan: Instant,
    ): List<String> = blobs.filter { it.key.startsWith(prefix) && it.value.storedAt.isBefore(olderThan) }.keys.toList()
}

/** Manually-driven notifier: tests signal wakes and observe subscriptions. */
class FakeNotifier : MessageNotifier {
    val handles = mutableListOf<FakeHandle>()
    var onAwait: (FakeHandle) -> WakeOutcome = { WakeOutcome.DEADLINE }

    inner class FakeHandle(
        val inboxId: InboxId,
    ) : WaitHandle {
        var closed = false
        var awaitCount = 0

        override fun awaitWake(deadline: Instant): WakeOutcome {
            awaitCount++
            return onAwait(this)
        }

        override fun close() {
            closed = true
        }
    }

    override fun subscribe(inboxId: InboxId): WaitHandle = FakeHandle(inboxId).also { handles += it }

    override fun health(): NotifierHealth = NotifierHealth(listening = true, epoch = 1, reconnectCount = 0)
}

/** In-memory quota state; counts derive from the fake repositories, as production derives from SQL. */
class InMemoryQuotaState(
    private val inboxes: InMemoryInboxRepository,
    private val messages: InMemoryMessageRepository,
) : WorkspaceQuotaState {
    var guardedWorkspaces = mutableListOf<WorkspaceId>()

    override fun guardAdmission(workspaceId: WorkspaceId) {
        guardedWorkspaces += workspaceId
    }

    override fun activeInboxCount(workspaceId: WorkspaceId): Long =
        inboxes.inboxes.values
            .count {
                it.workspaceId == workspaceId &&
                    it.state in setOf(InboxState.ACTIVE, InboxState.EXPIRING)
            }.toLong()

    override fun storedBytes(workspaceId: WorkspaceId): Long =
        messages.messages
            .filter { it.workspaceId == workspaceId }
            .sumOf { it.rawSizeBytes + it.attachments.sumOf { a -> a.sizeBytes } }
}

/** Rate limiter fake: allows everything until a category is explicitly exhausted. */
class FakeRateLimiter : RateLimiter {
    val exhausted = mutableSetOf<RateCategory>()
    val exhaustedInboxes = mutableSetOf<InboxId>()
    val calls = mutableListOf<Pair<RateCategory, InboxId?>>()

    override fun tryConsume(
        workspaceId: WorkspaceId,
        category: RateCategory,
        inboxId: InboxId?,
    ): RateDecision {
        calls += category to inboxId
        val allowed = category !in exhausted && (inboxId == null || inboxId !in exhaustedInboxes)
        return RateDecision(
            category = category,
            allowed = allowed,
            limit = 10,
            remaining = if (allowed) 9 else 0,
            retryAfter = if (allowed) null else java.time.Duration.ofSeconds(1),
        )
    }
}

/** Wait-slot fake with a fixed ceiling, mirroring the constraint-claimed production slots. */
class FakeWaitSlots : WaitSlots {
    var held = 0
    var peakHeld = 0

    override fun acquire(
        workspaceId: WorkspaceId,
        maxConcurrent: Long,
        leaseFor: java.time.Duration,
    ): WaitSlot? {
        if (held >= maxConcurrent) return null
        held++
        peakHeld = maxOf(peakHeld, held)
        return object : WaitSlot {
            override fun close() {
                held--
            }
        }
    }

    override fun reapExpired(): Int = 0
}

/**
 * In-memory [email.testinbox.application.port.ApiKeyRepository].
 *
 * It reproduces the *contract* the JDBC adapter is written to — managed-only
 * lookups, an atomic revoke that reports what it matched, and a guarded
 * `last_used_at` refresh — rather than being a permissive map. A fake that
 * ignored those restrictions would let the use-case tests pass against
 * behaviour the real adapter does not have.
 */
class InMemoryApiKeyRepository : email.testinbox.application.port.ApiKeyRepository {
    val keys = java.util.concurrent.ConcurrentHashMap<email.testinbox.domain.ApiKeyId, email.testinbox.domain.tenant.ApiKey>()

    /** Counts every statement the coalescing recorder actually issued. */
    val touchAttempts =
        java.util.concurrent.atomic
            .AtomicInteger()

    fun put(key: email.testinbox.domain.tenant.ApiKey): email.testinbox.domain.tenant.ApiKey {
        keys[key.id] = key
        return key
    }

    override fun findByPublicId(publicId: String): email.testinbox.domain.tenant.ApiKey? =
        keys.values.firstOrNull {
            it.publicId == publicId && it.kind == email.testinbox.domain.tenant.ApiKeyKind.MANAGED
        }

    override fun findBootstrapByHash(keyHash: String): email.testinbox.domain.tenant.ApiKey? =
        keys.values.firstOrNull {
            it.keyHash == keyHash && it.kind == email.testinbox.domain.tenant.ApiKeyKind.BOOTSTRAP
        }

    override fun hasUsableManagedAdmin(
        workspaceId: WorkspaceId,
        at: Instant,
    ): Boolean =
        keys.values.any {
            it.workspaceId == workspaceId &&
                it.kind == email.testinbox.domain.tenant.ApiKeyKind.MANAGED &&
                it.isUsableAt(at) &&
                it.hasScope(email.testinbox.domain.tenant.ApiScope.API_KEYS_MANAGE)
        }

    override fun insert(apiKey: email.testinbox.domain.tenant.ApiKey) {
        require(keys.putIfAbsent(apiKey.id, apiKey) == null) { "duplicate api key id" }
    }

    override fun findById(
        workspaceId: WorkspaceId,
        id: email.testinbox.domain.ApiKeyId,
    ): email.testinbox.domain.tenant.ApiKey? =
        keys[id]?.takeIf {
            it.workspaceId == workspaceId && it.kind == email.testinbox.domain.tenant.ApiKeyKind.MANAGED
        }

    override fun listPage(
        workspaceId: WorkspaceId,
        after: email.testinbox.application.port.ApiKeyCursor?,
        limit: Int,
    ): List<email.testinbox.domain.tenant.ApiKey> =
        keys.values
            .filter { it.workspaceId == workspaceId && it.kind == email.testinbox.domain.tenant.ApiKeyKind.MANAGED }
            .sortedWith(compareByDescending<email.testinbox.domain.tenant.ApiKey> { it.createdAt }.thenByDescending { it.id.value })
            .dropWhile {
                after != null &&
                    !(it.createdAt < after.createdAt || (it.createdAt == after.createdAt && it.id.value < after.id.value))
            }.take(limit)

    override fun revoke(
        workspaceId: WorkspaceId,
        id: email.testinbox.domain.ApiKeyId,
        at: Instant,
    ): email.testinbox.application.port.RevokeApiKeyOutcome {
        var outcome: email.testinbox.application.port.RevokeApiKeyOutcome =
            email.testinbox.application.port.RevokeApiKeyOutcome.NotFound
        // computeIfPresent is the fake's stand-in for the guarded UPDATE: the
        // decision and the mutation happen under one lock, so two concurrent
        // revocations cannot both report a fresh revocation.
        keys.computeIfPresent(id) { _, existing ->
            if (existing.workspaceId != workspaceId || existing.kind != email.testinbox.domain.tenant.ApiKeyKind.MANAGED) {
                existing
            } else if (existing.isRevoked()) {
                outcome = email.testinbox.application.port.RevokeApiKeyOutcome.AlreadyRevoked
                existing
            } else {
                outcome = email.testinbox.application.port.RevokeApiKeyOutcome.Revoked
                existing.copy(revokedAt = at)
            }
        }
        return outcome
    }

    override fun touchLastUsed(
        id: email.testinbox.domain.ApiKeyId,
        at: Instant,
        onlyIfOlderThan: Instant,
    ): Boolean {
        touchAttempts.incrementAndGet()
        var written = false
        keys.computeIfPresent(id) { _, existing ->
            val previous = existing.lastUsedAt
            if (previous == null || previous < onlyIfOlderThan) {
                written = true
                existing.copy(lastUsedAt = at)
            } else {
                existing
            }
        }
        return written
    }
}

/** Records what the application layer reported about credentials. */
class RecordingApiKeyMetrics : email.testinbox.application.port.ApiKeyMetrics {
    val auth = java.util.Collections.synchronizedList(mutableListOf<email.testinbox.application.port.AuthOutcome>())
    val lifecycle = java.util.Collections.synchronizedList(mutableListOf<email.testinbox.application.port.ApiKeyOperation>())
    val lastUsedWrites =
        java.util.concurrent.atomic
            .AtomicInteger()

    override fun authCompleted(outcome: email.testinbox.application.port.AuthOutcome) {
        auth += outcome
    }

    override fun lifecycle(operation: email.testinbox.application.port.ApiKeyOperation) {
        lifecycle += operation
    }

    override fun lastUsedPersisted() {
        lastUsedWrites.incrementAndGet()
    }
}

/** Captures audit events so tests can assert what they do — and do not — carry. */
class RecordingAuditLog : email.testinbox.application.port.AuditLog {
    val events = java.util.Collections.synchronizedList(mutableListOf<email.testinbox.application.port.AuditEvent>())

    override fun record(event: email.testinbox.application.port.AuditEvent) {
        events += event
    }
}

/**
 * An [email.testinbox.application.port.IdempotencyRecords] for call sites that
 * never present a key. Refuses loudly rather than pretending, so a test that
 * starts exercising idempotency cannot silently get a no-op.
 */
object NoIdempotencyRecords : email.testinbox.application.port.IdempotencyRecords {
    override fun claim(
        scope: email.testinbox.application.port.IdempotencyScope,
        keyHash: String,
        fingerprint: String,
        claimedByApiKeyId: email.testinbox.domain.ApiKeyId?,
        now: Instant,
        expiresAt: Instant,
        waitFor: java.time.Duration,
    ): email.testinbox.application.port.ClaimOutcome =
        error("this fixture has no idempotency records; construct Idempotency with a real one to test claims")

    override fun complete(
        scope: email.testinbox.application.port.IdempotencyScope,
        keyHash: String,
        snapshot: email.testinbox.application.port.IdempotencySnapshot,
    ) = error("this fixture has no idempotency records")

    override fun deleteExpired(
        now: Instant,
        batchSize: Int,
    ): Int = 0
}
