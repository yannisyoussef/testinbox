package email.testinbox.api.web

import com.fasterxml.jackson.annotation.JsonInclude
import email.testinbox.application.port.ApiKeyCursor
import email.testinbox.application.port.InboxCursor
import email.testinbox.application.port.MessageCursor
import email.testinbox.application.port.StorageRefusalSnapshot
import email.testinbox.application.query.InboxStorageView
import email.testinbox.application.query.StorageUsageView
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.MessageId
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class CreateInboxRequest(
    val addressMode: String? = null,
    val ttlSeconds: Long? = null,
    val aliasHint: String? = null,
    val localPart: String? = null,
)

/**
 * ADR-035 §13's `StorageUsage`: one scope of the caller's own tenancy. The
 * wire name is `storedBytes`; the internal `committedBytes` never appears.
 * No global figure has a field here, so none can be rendered by accident.
 */
data class StorageUsageDto(
    val limitBytes: Long,
    val storedBytes: Long,
    val reservedBytes: Long,
    val availableBytes: Long,
    val overLimit: Boolean,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val footprint: email.testinbox.application.query.WorkspaceFootprintView? = null,
) {
    companion object {
        fun from(view: StorageUsageView): StorageUsageDto =
            StorageUsageDto(
                limitBytes = view.limitBytes,
                storedBytes = view.storedBytes,
                reservedBytes = view.reservedBytes,
                availableBytes = view.availableBytes,
                overLimit = view.overLimit,
                footprint = view.footprint,
            )
    }
}

/**
 * An inbox representation: the aggregate's own members plus its LIVE storage
 * state (ADR-035 §13b). The storage members are read at response time, on an
 * idempotent create replay too; they are not part of the `Inbox` aggregate
 * and never enter the idempotency snapshot.
 */
data class InboxDto(
    val id: UUID,
    val address: String,
    val addressMode: String,
    val state: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val storage: StorageUsageDto,
    val storageRefusalCount: Long,
    val lastStorageRefusalAt: Instant?,
    val lastStorageRefusalReason: String?,
) {
    companion object {
        fun from(
            inbox: Inbox,
            storage: InboxStorageView,
        ): InboxDto =
            InboxDto(
                id = inbox.id.value,
                address = inbox.address,
                addressMode = inbox.addressMode.name,
                state = inbox.state.name,
                createdAt = inbox.createdAt,
                expiresAt = inbox.expiresAt,
                storage = StorageUsageDto.from(storage.storage),
                storageRefusalCount = storage.refusals.count,
                lastStorageRefusalAt = storage.refusals.lastAt,
                lastStorageRefusalReason = storage.refusals.lastReason?.name,
            )
    }
}

data class EmailHeaderDto(
    val name: String,
    val value: String,
)

data class EmailLinkDto(
    val href: String,
    val text: String?,
)

data class AttachmentMetaDto(
    val id: UUID,
    val fileName: String?,
    val contentType: String?,
    val sizeBytes: Long,
)

data class MessageDto(
    val id: UUID,
    val inboxId: UUID,
    val receivedAt: Instant,
    val envelopeFrom: String?,
    val envelopeTo: String,
    val parseStatus: String,
    val parseError: String?,
    val from: String?,
    val fromHeader: String?,
    val toHeader: String?,
    val subject: String?,
    val textBody: String?,
    val htmlBody: String?,
    val headers: List<EmailHeaderDto>?,
    val links: List<EmailLinkDto>?,
    val attachments: List<AttachmentMetaDto>,
    val contentFingerprint: String,
    val possibleDuplicateOfMessageId: UUID?,
    val rawSizeBytes: Long,
) {
    companion object {
        fun from(message: Message): MessageDto =
            MessageDto(
                id = message.id.value,
                inboxId = message.inboxId.value,
                receivedAt = message.receivedAt,
                envelopeFrom = message.envelopeFrom,
                envelopeTo = message.envelopeTo,
                parseStatus = message.parseStatus.name,
                parseError = message.parseError,
                from = message.parsed?.fromAddress,
                fromHeader = message.parsed?.fromHeader,
                toHeader = message.parsed?.toHeader,
                subject = message.parsed?.subject,
                textBody = message.parsed?.textBody,
                htmlBody = message.parsed?.htmlBody,
                headers =
                    if (message.parseStatus == ParseStatus.OK) {
                        message.parsed
                            ?.headers
                            .orEmpty()
                            .map { EmailHeaderDto(it.name, it.value) }
                    } else {
                        null
                    },
                links =
                    if (message.parseStatus == ParseStatus.OK) {
                        message.parsed
                            ?.links
                            .orEmpty()
                            .map { EmailLinkDto(it.href, it.text) }
                    } else {
                        null
                    },
                attachments =
                    message.attachments.map {
                        AttachmentMetaDto(it.id.value, it.fileName, it.contentType, it.sizeBytes)
                    },
                contentFingerprint = message.contentFingerprint,
                possibleDuplicateOfMessageId = message.possibleDuplicateOfMessageId?.value,
                rawSizeBytes = message.rawSizeBytes,
            )
    }
}

data class MessagePageDto(
    val items: List<MessageDto>,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val nextCursor: String?,
)

data class InboxPageDto(
    val items: List<InboxDto>,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val nextCursor: String?,
)

data class ProjectDto(
    val id: UUID,
    val name: String,
    val createdAt: Instant,
)

/** The authenticated key's own workspace (ADR-027): never another's, never a global figure. */
data class WorkspaceDto(
    val id: UUID,
    val name: String,
    val createdAt: Instant,
    val mailDomain: String,
    val project: ProjectDto?,
) {
    companion object {
        fun from(view: email.testinbox.application.query.WorkspaceView): WorkspaceDto =
            WorkspaceDto(
                id = view.workspace.id.value,
                name = view.workspace.name,
                createdAt = view.workspace.createdAt,
                mailDomain = view.mailDomain,
                project = view.project?.let { ProjectDto(it.id.value, it.name, it.createdAt) },
            )
    }
}

data class QuotaDto(
    val dimension: String,
    val limit: Long,
    val current: Long,
)

data class RateDto(
    val category: String,
    val limit: Long,
    val refillPerSecond: Double,
    val remaining: Long?,
)

data class WorkspaceUsageDto(
    val enforced: Boolean,
    val quotas: List<QuotaDto>,
    val rates: List<RateDto>,
) {
    companion object {
        fun from(view: email.testinbox.application.query.WorkspaceUsageView): WorkspaceUsageDto =
            WorkspaceUsageDto(
                enforced = view.enforced,
                quotas = view.quotas.map { QuotaDto(it.dimension.name, it.limit, it.current) },
                rates = view.rates.map { RateDto(it.category.name, it.limit, it.refillPerSecond, it.remaining) },
            )
    }
}

/** One stream event's `data` for a message: the summary, never the bodies (fetch the message for those). */
data class MessageEventDto(
    val id: UUID,
    val inboxId: UUID,
    val receivedAt: Instant,
    val subject: String?,
    val from: String?,
    val parseStatus: String,
) {
    companion object {
        fun from(message: Message): MessageEventDto =
            MessageEventDto(
                message.id.value,
                message.inboxId.value,
                message.receivedAt,
                message.parsed?.subject,
                message.parsed?.fromAddress,
                message.parseStatus.name,
            )
    }
}

data class InboxEventDto(
    val id: UUID,
    val address: String,
    val state: String,
    val expiresAt: Instant,
) {
    companion object {
        fun from(inbox: Inbox): InboxEventDto = InboxEventDto(inbox.id.value, inbox.address, inbox.state.name, inbox.expiresAt)
    }
}

data class HeaderMatcherDto(
    val name: String? = null,
    val value: String? = null,
)

data class MessageMatcherDto(
    val from: String? = null,
    val subjectContains: String? = null,
    val subjectEquals: String? = null,
    val headers: List<HeaderMatcherDto>? = null,
)

data class WaitRequestDto(
    val matcher: MessageMatcherDto? = null,
    val timeoutSeconds: Long? = null,
    /**
     * ADR-035 §13c opt-in. Absent is the legacy contract and is NOT zero:
     * zero observes every refusal the inbox ever had, absence observes none.
     */
    val afterStorageRefusalCount: Long? = null,
)

data class WaitResultDto(
    val status: String,
    val message: MessageDto?,
    val elapsedMs: Long,
    val arrivedButUnmatchedCount: Int?,
    val parseFailedCount: Int?,
    /** Informational, from the deciding snapshot (ADR-035 §13c). */
    val storageRefusalCount: Long,
    val lastStorageRefusalAt: Instant?,
) {
    companion object {
        fun matched(
            message: MessageDto,
            elapsedMs: Long,
            refusals: StorageRefusalSnapshot,
        ): WaitResultDto =
            WaitResultDto(
                status = "MATCHED",
                message = message,
                elapsedMs = elapsedMs,
                arrivedButUnmatchedCount = null,
                parseFailedCount = null,
                storageRefusalCount = refusals.count,
                lastStorageRefusalAt = refusals.lastAt,
            )

        fun timeout(
            elapsedMs: Long,
            arrivedButUnmatchedCount: Int,
            parseFailedCount: Int,
            refusals: StorageRefusalSnapshot,
        ): WaitResultDto =
            WaitResultDto(
                status = "TIMEOUT",
                message = null,
                elapsedMs = elapsedMs,
                arrivedButUnmatchedCount = arrivedButUnmatchedCount,
                parseFailedCount = parseFailedCount,
                storageRefusalCount = refusals.count,
                lastStorageRefusalAt = refusals.lastAt,
            )
    }
}

/** Opaque cursor: base64url of `<epochMicros>:<id>`. */
object Cursors {
    fun encode(
        at: Instant,
        id: UUID,
    ): String {
        val micros = at.epochSecond * 1_000_000 + at.nano / 1_000
        return Base64.getUrlEncoder().withoutPadding().encodeToString("$micros:$id".toByteArray())
    }

    /** A day of clock skew is tolerated; anything before the epoch or further ahead is not a position this API ever issued. */
    private val FURTHEST_FUTURE: java.time.Duration = java.time.Duration.ofDays(1)

    private fun decodePosition(cursor: String): Pair<Instant, UUID>? =
        runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(cursor))
            val (micros, id) = decoded.split(':', limit = 2)
            val microsLong = micros.toLong()
            if (microsLong < 0) return null
            val at = Instant.ofEpochSecond(microsLong / 1_000_000, (microsLong % 1_000_000) * 1_000)
            if (at.isAfter(Instant.now().plus(FURTHEST_FUTURE))) return null
            at to UUID.fromString(id)
        }.getOrNull()

    fun decode(cursor: String): MessageCursor? =
        decodePosition(cursor)?.let { (at, id) -> MessageCursor(receivedAt = at, id = MessageId(id)) }

    fun decodeApiKey(cursor: String): ApiKeyCursor? =
        decodePosition(cursor)?.let { (at, id) -> ApiKeyCursor(createdAt = at, id = ApiKeyId(id)) }

    fun decodeInbox(cursor: String): InboxCursor? =
        decodePosition(cursor)?.let { (at, id) -> InboxCursor(createdAt = at, id = email.testinbox.domain.InboxId(id)) }
}

data class CreateApiKeyRequest(
    val name: String? = null,
    val scopes: List<String>? = null,
    val expiresInSeconds: Long? = null,
)

/**
 * API-key metadata. There is deliberately **no** secret-bearing field on this
 * type — not an optional one, not a nullable one. `ApiKeySerializationTest`
 * asserts that property by reflection, so a field added later that happens to
 * be named like a credential fails the build rather than shipping.
 */
data class ApiKeyDto(
    val id: UUID,
    val publicId: String,
    val name: String?,
    val scopes: List<String>,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
    val lastUsedAt: Instant?,
    val createdByApiKeyId: UUID?,
) {
    companion object {
        fun from(key: email.testinbox.domain.tenant.ApiKey): ApiKeyDto =
            ApiKeyDto(
                id = key.id.value,
                // Managed keys always carry one; the management API never
                // returns a bootstrap row (ADR-032 §8).
                publicId = key.publicId.orEmpty(),
                name = key.name,
                scopes = key.scopes.map { it.wire }.sorted(),
                createdAt = key.createdAt,
                expiresAt = key.expiresAt,
                revokedAt = key.revokedAt,
                lastUsedAt = key.lastUsedAt,
                createdByApiKeyId = key.createdByApiKeyId?.value,
            )
    }
}

/**
 * The one and only representation that carries a credential (ADR-032 §4).
 * The secret sits in its own field beside the metadata rather than inside it,
 * so "does this response contain a key?" is answerable by looking at the type.
 */
data class CreatedApiKeyDto(
    val apiKey: ApiKeyDto,
    val key: String,
)

data class ApiKeyPageDto(
    val items: List<ApiKeyDto>,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val nextCursor: String?,
)
