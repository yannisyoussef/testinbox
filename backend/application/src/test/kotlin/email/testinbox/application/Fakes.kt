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
import email.testinbox.application.port.ReserveOutcome
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.port.WaitHandle
import email.testinbox.application.port.WakeOutcome
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.ExactReservation
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.inbox.ReservationStatus
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
    val messages = mutableListOf<Message>()
    val notifiedInboxes = mutableListOf<InboxId>()

    override fun appendVisible(message: Message): AppendOutcome {
        if (message.providerMessageId != null &&
            messages.any {
                it.provider == message.provider && it.providerMessageId == message.providerMessageId
            }
        ) {
            return AppendOutcome.DuplicateProviderEvent
        }
        messages += message
        notifiedInboxes += message.inboxId
        return AppendOutcome.Appended
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
