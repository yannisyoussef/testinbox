package email.testinbox.client

/**
 * Base type for every TestInbox SDK failure (docs/sdk/principles.md #6).
 *
 * [problemType] carries the RFC 7807 `type` URI whenever the server sent one.
 * It is on the base rather than only on [TestInboxApiException] because one
 * status can carry two meanings: `403` is either `missing-scope` ("use a
 * different key") or `scope-escalation` ("ask for fewer scopes"), and without
 * this a caller would have to substring-match a human-readable `detail` to
 * tell them apart. The TypeScript SDK has always exposed it this way.
 */
open class TestInboxException(
    message: String,
    val correlationId: String? = null,
    /** Stable RFC 7807 `type` URI, when the server sent a problem body. */
    val problemType: String? = null,
    /** HTTP status, when the failure came from a response. */
    val status: Int? = null,
) : RuntimeException(message)

/**
 * The server's response did not match the committed contract — a required
 * field absent, or a timestamp that will not parse.
 *
 * Typed rather than an escaping `DateTimeParseException`: an untyped exception
 * from a dependency's internals is not something a caller can reasonably
 * handle, and it violates principle #6.
 */
class TestInboxProtocolException(message: String) : TestInboxException(message)

class TestInboxAuthException(
    message: String,
    correlationId: String? = null,
    problemType: String? = null,
    status: Int? = null,
) : TestInboxException(message, correlationId, problemType, status)

class TestInboxForbiddenException(
    message: String,
    correlationId: String? = null,
    problemType: String? = null,
    status: Int? = null,
) : TestInboxException(message, correlationId, problemType, status)

class TestInboxNotFoundException(
    message: String,
    correlationId: String? = null,
    problemType: String? = null,
    status: Int? = null,
) : TestInboxException(message, correlationId, problemType, status)

class TestInboxConflictException(
    message: String,
    correlationId: String? = null,
    /** Seconds until an EXACT local-part in cooldown becomes reservable, when known (ADR-021). */
    val retryAfterSeconds: Long? = null,
) : TestInboxException(message, correlationId)

class TestInboxInboxGoneException(
    message: String,
    correlationId: String? = null,
    problemType: String? = null,
    status: Int? = null,
) : TestInboxException(message, correlationId, problemType, status)

/**
 * The workspace's request budget for this operation is exhausted (HTTP 429,
 * ADR-027). Waiting helps: [retryAfter] is the server's own estimate.
 *
 * Deliberately *not* retried automatically by the SDK. `POST /v1/inboxes`
 * creates a resource and `Idempotency-Key` is not implemented, so an
 * automatic retry could create duplicate inboxes; the caller decides.
 */
class TestInboxRateLimitException(
    message: String,
    correlationId: String? = null,
    val retryAfter: java.time.Duration? = null,
    /** Rate category that refused the request, when the server names one. */
    val category: String? = null,
    val limit: Long? = null,
    val remaining: Long? = null,
) : TestInboxException(message, correlationId)

/**
 * A workspace resource allowance is exhausted (HTTP 409 with problem type
 * `quota-exceeded`, ADR-027). Distinct from [TestInboxRateLimitException]
 * because waiting does **not** help — the caller must free capacity, for
 * example by deleting an inbox.
 */
class TestInboxQuotaExceededException(
    message: String,
    correlationId: String? = null,
    /** Quota dimension that is exhausted, e.g. `ACTIVE_INBOXES`. */
    val quota: String? = null,
    val limit: Long? = null,
    val current: Long? = null,
) : TestInboxException(message, correlationId)

/**
 * Any failure the SDK does not model more specifically.
 *
 * [statusCode] is retained as a distinct name for source compatibility; it is
 * the same value as the inherited [status].
 */
class TestInboxApiException(
    val statusCode: Int,
    problemType: String?,
    message: String,
    correlationId: String? = null,
) : TestInboxException(message, correlationId, problemType, statusCode)

/**
 * The caller's overall wait timeout expired (distinct from a chainable
 * server wait-window TIMEOUT, ADR-020). Carries the last poll's diagnostics
 * — the primary "why did my test time out" signal.
 */
class TestInboxTimeoutException(
    val elapsedMs: Long,
    val arrivedButUnmatchedCount: Int,
    val parseFailedCount: Int,
) : TestInboxException(
        "No matching message within ${elapsedMs}ms " +
            "(arrivedButUnmatched=$arrivedButUnmatchedCount, parseFailed=$parseFailedCount)",
    )
