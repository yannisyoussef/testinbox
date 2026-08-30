package email.testinbox.client

/** Base type for every TestInbox SDK failure (docs/sdk/principles.md #6). */
open class TestInboxException(
    message: String,
    val correlationId: String? = null,
) : RuntimeException(message)

class TestInboxAuthException(message: String, correlationId: String? = null) :
    TestInboxException(message, correlationId)

class TestInboxForbiddenException(message: String, correlationId: String? = null) :
    TestInboxException(message, correlationId)

class TestInboxNotFoundException(message: String, correlationId: String? = null) :
    TestInboxException(message, correlationId)

class TestInboxConflictException(
    message: String,
    correlationId: String? = null,
    /** Seconds until an EXACT local-part in cooldown becomes reservable, when known (ADR-021). */
    val retryAfterSeconds: Long? = null,
) : TestInboxException(message, correlationId)

class TestInboxInboxGoneException(message: String, correlationId: String? = null) :
    TestInboxException(message, correlationId)

class TestInboxApiException(
    val statusCode: Int,
    val problemType: String?,
    message: String,
    correlationId: String? = null,
) : TestInboxException(message, correlationId)

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
