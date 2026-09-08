package email.testinbox.client

import email.testinbox.client.internal.transport.ApiKeyDto
import email.testinbox.client.internal.transport.CreateApiKeyRequestDto
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
        /**
         * An idempotency key the caller owns (ADR-033), sent verbatim.
         *
         * The SDK never generates or rewrites one, and that is the only
         * behaviour that makes the feature work: a key regenerated per attempt
         * defeats it entirely, and one generated inside a process that then
         * dies protects nothing — the retry comes from somewhere that never saw
         * it. Derive it from something your own retry boundary can reproduce: a
         * CI job id, a test name, a row in your own queue.
         */
        val idempotencyKey: String? = null,
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

/**
 * Permission carried by an API key. A wrapper over the wire string rather than
 * an enum, so a key granted a scope this SDK version predates still
 * round-trips instead of failing to deserialise.
 *
 * A plain class, deliberately **not** `@JvmInline value class`: the value-class
 * form compiles the constants to a private field plus a name-mangled accessor
 * (`getINBOXES_WRITE-XqM_LWM`) and the constructor to `box-impl`, none of
 * which is a legal Java identifier. That made `apiKeys.create` — whose blocking
 * facade exists precisely for plain-Java callers (ADR-023) — impossible to call
 * from Java at all. `JavaInteropTest` compiles real Java source against the
 * built classes so the claim is a gate rather than a comment.
 */
class ApiScope(
    val wire: String,
) {
    override fun toString(): String = wire

    override fun equals(other: Any?): Boolean = this === other || (other is ApiScope && other.wire == wire)

    override fun hashCode(): Int = wire.hashCode()

    companion object {
        @JvmField val INBOXES_WRITE: ApiScope = ApiScope("inboxes:write")

        @JvmField val MESSAGES_READ: ApiScope = ApiScope("messages:read")

        @JvmField val API_KEYS_MANAGE: ApiScope = ApiScope("api-keys:manage")
    }
}

/**
 * Metadata about an API key.
 *
 * There is no property here that could hold the credential — not an optional
 * one, not a nullable one. The secret exists only on [CreatedApiKey], and only
 * for the one call that mints it (ADR-032 §4). A caller who has an
 * [ApiKeyMetadata] cannot accidentally log, serialise or transmit a key,
 * because it does not have one.
 */
class ApiKeyMetadata internal constructor(dto: ApiKeyDto) {
    val id: String = dto.id

    /** Non-secret handle, safe to display and to quote in a support ticket. */
    val publicId: String = dto.publicId
    val name: String? = dto.name
    val scopes: List<ApiScope> = dto.scopes.map(::ApiScope)
    /**
     * Non-null: the contract marks it required, so a caller must not have to
     * null-check it — and an absent value is a contract violation worth
     * reporting rather than papering over with a fabricated instant.
     */
    val createdAt: Instant = parseRequiredInstant(dto.createdAt, "createdAt")
    val expiresAt: Instant? = dto.expiresAt?.let(Instant::parse)
    val revokedAt: Instant? = dto.revokedAt?.let(Instant::parse)

    /** Approximate — see ADR-032 §7. Never use it as an authorization input. */
    val lastUsedAt: Instant? = dto.lastUsedAt?.let(Instant::parse)
    val createdByApiKeyId: String? = dto.createdByApiKeyId

    val isRevoked: Boolean get() = revokedAt != null

    override fun toString(): String = "ApiKeyMetadata(id=$id, publicId=$publicId, name=$name, scopes=$scopes)"
}

private fun parseRequiredInstant(
    raw: String?,
    field: String,
): Instant {
    if (raw == null) throw TestInboxProtocolException("the server omitted required field '$field'")
    return runCatching { Instant.parse(raw) }
        .getOrElse { throw TestInboxProtocolException("the server sent an unparseable '$field'") }
}

/**
 * The result of minting a key: the metadata, plus the credential — which the
 * server will never return again and does not store.
 *
 * [secret] is deliberately not part of [toString], so a log line, a test
 * failure message or a debugger dump of this object cannot leak it. Read it
 * once, hand it to whatever needs it, and drop it.
 */
class CreatedApiKey internal constructor(
    val apiKey: ApiKeyMetadata,
    val secret: String,
) {
    override fun toString(): String = "CreatedApiKey(apiKey=$apiKey, secret=<redacted>)"
}

/** One page of key metadata. */
class ApiKeyPage internal constructor(
    val items: List<ApiKeyMetadata>,
    val nextCursor: String?,
)

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
                    options.idempotencyKey,
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

        /**
         * Credential administration. Requires a key holding
         * `api-keys:manage`; an ordinary CI credential deliberately does not
         * carry it and receives [TestInboxForbiddenException] here.
         */
        val apiKeys: ApiKeys = ApiKeys(transport)

        companion object {
            const val DEFAULT_BASE_URL: String = "https://api.testinbox.email"
        }
    }

/**
 * The key-management surface (ADR-032).
 *
 * There is no `rotate` call, and that is a design decision rather than an
 * omission: rotation's hard part is the interval during which the client has
 * not yet picked up the new credential, and no server call can shorten it. The
 * correct sequence is [create] → deploy → verify → [revoke], which this API
 * expresses directly.
 */
class ApiKeys internal constructor(
    private val transport: email.testinbox.client.internal.transport.Transport,
) {
    /**
     * Mints a key. The returned [CreatedApiKey.secret] is the only copy that
     * will ever exist — it is not stored and cannot be retrieved again.
     */
    /**
     * [idempotencyKey] is sent verbatim and is strongly recommended here
     * (ADR-033 §8): a lost response otherwise leaves an **orphan credential** —
     * one that exists, has real authority, and that nobody holds and nobody
     * knows to revoke. With a key, the retry is refused by
     * [TestInboxConflictException] naming the credential already created,
     * rather than minting a second.
     */
    @JvmOverloads
    suspend fun create(
        scopes: List<ApiScope>,
        name: String? = null,
        expiresIn: Duration? = null,
        idempotencyKey: String? = null,
    ): CreatedApiKey {
        val dto =
            transport.createApiKey(
                CreateApiKeyRequestDto(
                    name = name,
                    scopes = scopes.map { it.wire },
                    expiresInSeconds = expiresIn?.seconds,
                ),
                idempotencyKey,
            )
        return CreatedApiKey(ApiKeyMetadata(dto.apiKey), dto.key)
    }

    @JvmOverloads
    fun createBlocking(
        scopes: List<ApiScope>,
        name: String? = null,
        expiresIn: Duration? = null,
        idempotencyKey: String? = null,
    ): CreatedApiKey = runBlocking { create(scopes, name, expiresIn, idempotencyKey) }

    /** Newest first. Revoked keys are included; they are retained for audit. */
    @JvmOverloads
    suspend fun list(cursor: String? = null, limit: Int? = null): ApiKeyPage {
        val page = transport.listApiKeys(cursor, limit)
        return ApiKeyPage(page.items.map(::ApiKeyMetadata), page.nextCursor)
    }

    @JvmOverloads
    fun listBlocking(cursor: String? = null, limit: Int? = null): ApiKeyPage = runBlocking { list(cursor, limit) }

    /**
     * Metadata only, and a revoked key is still returned — revocation is a
     * state change, not a deletion. "Revoke then expect a not-found" is the
     * wrong check; read [ApiKeyMetadata.isRevoked].
     */
    suspend fun get(id: String): ApiKeyMetadata = ApiKeyMetadata(transport.getApiKey(id))

    fun getBlocking(id: String): ApiKeyMetadata = runBlocking { get(id) }

    /** Idempotent: revoking an already-revoked key succeeds. */
    suspend fun revoke(id: String) {
        transport.revokeApiKey(id)
    }

    fun revokeBlocking(id: String): Unit = runBlocking { revoke(id) }
}
