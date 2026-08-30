package email.testinbox.client.internal.transport

import email.testinbox.client.TestInboxApiException
import email.testinbox.client.TestInboxAuthException
import email.testinbox.client.TestInboxConflictException
import email.testinbox.client.TestInboxForbiddenException
import email.testinbox.client.TestInboxInboxGoneException
import email.testinbox.client.TestInboxNotFoundException
import email.testinbox.client.TestInboxQuotaExceededException
import email.testinbox.client.TestInboxRateLimitException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * INTERNAL transport layer (ADR-014): a thin, hand-written client over the
 * committed OpenAPI contract. Nothing in this package is part of the public
 * SDK surface and it may change without notice.
 */
@Serializable
internal data class CreateInboxRequestDto(
    val addressMode: String? = null,
    val ttlSeconds: Long? = null,
    val aliasHint: String? = null,
    val localPart: String? = null,
)

@Serializable
internal data class InboxDto(
    val id: String,
    val address: String,
    val addressMode: String = "GENERATED",
    val state: String = "ACTIVE",
    val createdAt: String? = null,
    val expiresAt: String? = null,
)

@Serializable
internal data class HeaderDto(val name: String, val value: String = "")

@Serializable
internal data class LinkDto(val href: String, val text: String? = null)

@Serializable
internal data class AttachmentDto(
    val id: String,
    val fileName: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long = 0,
)

@Serializable
internal data class MessageDto(
    val id: String,
    val inboxId: String,
    val receivedAt: String? = null,
    val envelopeFrom: String? = null,
    val envelopeTo: String? = null,
    val parseStatus: String = "OK",
    val parseError: String? = null,
    val from: String? = null,
    val fromHeader: String? = null,
    val toHeader: String? = null,
    val subject: String? = null,
    val textBody: String? = null,
    val htmlBody: String? = null,
    val headers: List<HeaderDto> = emptyList(),
    val links: List<LinkDto> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
    val contentFingerprint: String? = null,
    val possibleDuplicateOfMessageId: String? = null,
    val rawSizeBytes: Long = 0,
)

@Serializable
internal data class MessagePageDto(val items: List<MessageDto> = emptyList(), val nextCursor: String? = null)

@Serializable
internal data class HeaderMatcherDto(val name: String, val value: String? = null)

@Serializable
internal data class MatcherDto(
    val from: String? = null,
    val subjectContains: String? = null,
    val subjectEquals: String? = null,
    val headers: List<HeaderMatcherDto> = emptyList(),
)

@Serializable
internal data class WaitRequestDto(val matcher: MatcherDto, val timeoutSeconds: Long)

@Serializable
internal data class WaitResultDto(
    val status: String = "TIMEOUT",
    val message: MessageDto? = null,
    val elapsedMs: Long = 0,
    val arrivedButUnmatchedCount: Int? = null,
    val parseFailedCount: Int? = null,
)

@Serializable
internal data class ProblemDto(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val correlationId: String? = null,
    val retryAfterSeconds: Long? = null,
    val category: String? = null,
    val quota: String? = null,
    val limit: Long? = null,
    val current: Long? = null,
)

internal class Transport(
    private val baseUrl: String,
    private val apiKey: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    // Forward compatibility: unknown fields and enum values must never break parsing.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun createInbox(request: CreateInboxRequestDto): InboxDto =
        json.decodeFromString(
            InboxDto.serializer(),
            execute("POST", "/v1/inboxes", json.encodeToString(CreateInboxRequestDto.serializer(), request))
                .let { String(it) },
        )

    suspend fun getInbox(id: String): InboxDto =
        json.decodeFromString(InboxDto.serializer(), String(execute("GET", "/v1/inboxes/$id")))

    suspend fun deleteInbox(id: String) {
        execute("DELETE", "/v1/inboxes/$id")
    }

    suspend fun listMessages(inboxId: String): MessagePageDto =
        json.decodeFromString(
            MessagePageDto.serializer(),
            String(execute("GET", "/v1/inboxes/$inboxId/messages")),
        )

    suspend fun wait(inboxId: String, request: WaitRequestDto): WaitResultDto =
        json.decodeFromString(
            WaitResultDto.serializer(),
            String(
                execute(
                    "POST",
                    "/v1/inboxes/$inboxId/messages/wait",
                    json.encodeToString(WaitRequestDto.serializer(), request),
                ),
            ),
        )

    suspend fun getMessage(id: String): MessageDto =
        json.decodeFromString(MessageDto.serializer(), String(execute("GET", "/v1/messages/$id")))

    suspend fun rawMime(messageId: String): ByteArray = execute("GET", "/v1/messages/$messageId/raw")

    private suspend fun execute(method: String, path: String, body: String? = null): ByteArray {
        val builder =
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json, message/rfc822, application/octet-stream")
        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        val response =
            httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray()).await()
        if (response.statusCode() in 200..299) return response.body()
        throw mapError(response.statusCode(), response.body(), response.headers())
    }

    private fun mapError(
        status: Int,
        body: ByteArray,
        headers: java.net.http.HttpHeaders,
    ): RuntimeException {
        val problem =
            runCatching { json.decodeFromString(ProblemDto.serializer(), String(body)) }
                .getOrElse { ProblemDto() }
        val detail = problem.detail ?: problem.title ?: "HTTP $status"
        val retryAfter =
            (problem.retryAfterSeconds ?: headers.firstValue("Retry-After").orElse(null)?.toLongOrNull())
                ?.let(java.time.Duration::ofSeconds)
        return when {
            status == 401 -> TestInboxAuthException(detail, problem.correlationId)
            status == 403 -> TestInboxForbiddenException(detail, problem.correlationId)
            status == 404 -> TestInboxNotFoundException(detail, problem.correlationId)
            // Two distinct 409s share this status (ADR-021 vs ADR-027), so the
            // problem type — not the status code — decides which error this is.
            status == 409 && problem.type?.endsWith("/quota-exceeded") == true ->
                TestInboxQuotaExceededException(
                    detail,
                    problem.correlationId,
                    quota = problem.quota,
                    limit = problem.limit,
                    current = problem.current,
                )
            status == 409 -> TestInboxConflictException(detail, problem.correlationId, problem.retryAfterSeconds)
            status == 410 -> TestInboxInboxGoneException(detail, problem.correlationId)
            status == 429 ->
                TestInboxRateLimitException(
                    detail,
                    problem.correlationId,
                    retryAfter = retryAfter,
                    category = problem.category,
                    limit = problem.limit ?: headers.firstValue("RateLimit-Limit").orElse(null)?.toLongOrNull(),
                    remaining = headers.firstValue("RateLimit-Remaining").orElse(null)?.toLongOrNull(),
                )
            else -> TestInboxApiException(status, problem.type, detail, problem.correlationId)
        }
    }
}
