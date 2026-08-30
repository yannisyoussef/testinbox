package email.testinbox.client

import email.testinbox.client.internal.transport.CreateInboxRequestDto
import email.testinbox.client.internal.transport.HeaderMatcherDto
import email.testinbox.client.internal.transport.InboxDto
import email.testinbox.client.internal.transport.MatcherDto
import email.testinbox.client.internal.transport.MessageDto
import email.testinbox.client.internal.transport.Transport
import email.testinbox.client.internal.transport.WaitRequestDto
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking

/** Addressing mode for [CreateInboxOptions] (ADR-021). */
enum class AddressMode { GENERATED, EXACT }

data class CreateInboxOptions
    @JvmOverloads
    constructor(
        val ttl: Duration? = null,
        val aliasHint: String? = null,
        val addressMode: AddressMode = AddressMode.GENERATED,
        val localPart: String? = null,
    )

/**
 * The matcher vocabulary, shared across SDKs (docs/sdk/principles.md #3).
 * Kotlin callers use the DSL on [Inbox.awaitMessage]; Java callers use
 * [MessageMatcher.builder].
 */
class MessageMatcher private constructor(
    internal val from: String?,
    internal val subjectContains: String?,
    internal val subjectEquals: String?,
    internal val headers: List<Pair<String, String?>>,
) {
    class Builder {
        private var from: String? = null
        private var subjectContains: String? = null
        private var subjectEquals: String? = null
        private val headers = mutableListOf<Pair<String, String?>>()

        fun from(address: String): Builder = apply { from = address }

        fun subjectContains(fragment: String): Builder = apply { subjectContains = fragment }

        fun subjectEquals(subject: String): Builder = apply { subjectEquals = subject }

        @JvmOverloads
        fun header(name: String, value: String? = null): Builder = apply { headers += name to value }

        fun build(): MessageMatcher = MessageMatcher(from, subjectContains, subjectEquals, headers.toList())
    }

    internal fun toDto(): MatcherDto =
        MatcherDto(
            from = from,
            subjectContains = subjectContains,
            subjectEquals = subjectEquals,
            headers = headers.map { HeaderMatcherDto(it.first, it.second) },
        )

    companion object {
        @JvmStatic fun builder(): Builder = Builder()

        @JvmStatic val ANY: MessageMatcher = Builder().build()
    }
}

data class EmailLink(val href: String, val text: String?)

data class EmailHeader(val name: String, val value: String)

data class AttachmentInfo(val id: String, val fileName: String?, val contentType: String?, val sizeBytes: Long)

/** A received email as observed by TestInbox. Plain data — bring your own assertions. */
class Message internal constructor(
    private val transport: Transport,
    private val dto: MessageDto,
) {
    val id: String get() = dto.id
    val inboxId: String get() = dto.inboxId
    val receivedAt: Instant? get() = dto.receivedAt?.let(Instant::parse)
    val parseStatus: String get() = dto.parseStatus
    val from: String? get() = dto.from
    val fromHeader: String? get() = dto.fromHeader
    val subject: String? get() = dto.subject
    val textBody: String? get() = dto.textBody

    /** Untrusted sender-controlled HTML — never render it on a trusted origin (ADR-011). */
    val htmlBody: String? get() = dto.htmlBody
    val headers: List<EmailHeader> get() = dto.headers.map { EmailHeader(it.name, it.value) }
    val links: List<EmailLink> get() = dto.links.map { EmailLink(it.href, it.text) }
    val attachments: List<AttachmentInfo>
        get() = dto.attachments.map { AttachmentInfo(it.id, it.fileName, it.contentType, it.sizeBytes) }

    suspend fun raw(): ByteArray = transport.rawMime(id)

    fun rawBlocking(): ByteArray = runBlocking { raw() }

    override fun toString(): String = "Message(id=$id, subject=$subject, from=$from)"
}

/** A reserved inbox with wait/list/delete operations. */
class Inbox internal constructor(
    private val transport: Transport,
    private val serverWaitCap: Duration,
    dto: InboxDto,
) {
    val id: String = dto.id
    val address: String = dto.address
    val addressMode: String = dto.addressMode
    val state: String = dto.state
    val expiresAt: Instant? = dto.expiresAt?.let(Instant::parse)

    /**
     * Waits for the earliest visible message matching [matcher], chaining
     * server long-poll windows until [timeout] is exhausted (ADR-020 /
     * docs/sdk/principles.md #4). Non-consuming. Throws
     * [TestInboxTimeoutException] with diagnostics when the overall budget
     * expires.
     */
    @JvmOverloads
    suspend fun awaitMessage(
        timeout: Duration = Duration.ofSeconds(30),
        matcher: MessageMatcher = MessageMatcher.ANY,
    ): Message {
        val deadline = Instant.now().plus(timeout)
        val started = Instant.now()
        var lastUnmatched = 0
        var lastParseFailed = 0
        while (true) {
            val remaining = Duration.between(Instant.now(), deadline)
            if (remaining.isNegative || remaining.isZero) break
            // Ceil to whole seconds so the final window covers the full budget
            // instead of leaving a sub-second tail poll.
            val remainingSeconds = (remaining.toMillis() + 999) / 1000
            val window = minOf(remainingSeconds.coerceAtLeast(1), serverWaitCap.seconds)
            val result = transport.wait(id, WaitRequestDto(matcher.toDto(), window))
            when (result.status) {
                "MATCHED" -> result.message?.let { return Message(transport, it) }
                // TIMEOUT — a successful negative answer; chain the next poll.
                // Unknown future statuses are treated the same (forward compat).
                else -> {
                    lastUnmatched = result.arrivedButUnmatchedCount ?: lastUnmatched
                    lastParseFailed = result.parseFailedCount ?: lastParseFailed
                }
            }
        }
        throw TestInboxTimeoutException(
            elapsedMs = Duration.between(started, Instant.now()).toMillis(),
            arrivedButUnmatchedCount = lastUnmatched,
            parseFailedCount = lastParseFailed,
        )
    }

    /** Kotlin DSL form: `inbox.awaitMessage(30.seconds) { from("x"); subjectContains("y") }`. */
    suspend fun awaitMessage(timeout: Duration, configure: MessageMatcher.Builder.() -> Unit): Message =
        awaitMessage(timeout, MessageMatcher.builder().apply(configure).build())

    /** Blocking facade for plain Java/JUnit callers (docs/sdk/architecture.md). */
    @JvmOverloads
    fun awaitMessageBlocking(
        timeout: Duration = Duration.ofSeconds(30),
        matcher: MessageMatcher = MessageMatcher.ANY,
    ): Message = runBlocking { awaitMessage(timeout, matcher) }

    suspend fun messages(): List<Message> =
        transport.listMessages(id).items.map { Message(transport, it) }

    fun messagesBlocking(): List<Message> = runBlocking { messages() }

    suspend fun delete() {
        transport.deleteInbox(id)
    }

    fun deleteBlocking(): Unit = runBlocking { delete() }
}

/**
 * Entry point for the TestInbox JVM SDK.
 *
 * ```kotlin
 * val testInbox = TestInboxClient(apiKey = "tk_...", baseUrl = "http://localhost:8080")
 * val inbox = testInbox.createInbox()
 * sut.register(inbox.address)
 * val message = inbox.awaitMessage(Duration.ofSeconds(30)) { subjectContains("Verify") }
 * val link = message.links.first().href
 * inbox.delete()
 * ```
 */
class TestInboxClient
    @JvmOverloads
    constructor(
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        private val serverWaitCap: Duration = Duration.ofSeconds(60),
    ) {
        private val transport = Transport(baseUrl, apiKey)

        @JvmOverloads
        suspend fun createInbox(options: CreateInboxOptions = CreateInboxOptions()): Inbox {
            val dto =
                transport.createInbox(
                    CreateInboxRequestDto(
                        addressMode = options.addressMode.name,
                        ttlSeconds = options.ttl?.seconds,
                        aliasHint = options.aliasHint,
                        localPart = options.localPart,
                    ),
                )
            return Inbox(transport, serverWaitCap, dto)
        }

        @JvmOverloads
        fun createInboxBlocking(options: CreateInboxOptions = CreateInboxOptions()): Inbox =
            runBlocking { createInbox(options) }

        suspend fun getInbox(id: String): Inbox = Inbox(transport, serverWaitCap, transport.getInbox(id))

        fun getInboxBlocking(id: String): Inbox = runBlocking { getInbox(id) }

        suspend fun getMessage(id: String): Message = Message(transport, transport.getMessage(id))

        fun getMessageBlocking(id: String): Message = runBlocking { getMessage(id) }

        companion object {
            const val DEFAULT_BASE_URL: String = "https://api.testinbox.email"
        }
    }
