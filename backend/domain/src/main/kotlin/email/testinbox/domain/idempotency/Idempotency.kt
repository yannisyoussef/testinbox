package email.testinbox.domain.idempotency

/**
 * The mutations that accept an `Idempotency-Key` (ADR-033 §1).
 *
 * A closed enum rather than a string, for two reasons: it is a metric label,
 * and the rule for membership is a real precondition — an operation qualifies
 * only if its entire mutation is a single Postgres transaction. Object storage
 * does not roll back (ADR-005 writes raw MIME before the row), so no inbound
 * path can ever be added here.
 */
enum class IdempotentOperation(
    val wire: String,
) {
    CREATE_INBOX("create-inbox"),
    CREATE_API_KEY("create-api-key"),
}

/**
 * A client-chosen key making a retry of one logical request safe.
 *
 * Opaque by contract: the server infers nothing from the content, so a UUID, a
 * ULID and a CI job id are all equally valid. The bounds exist for reasons
 * that are not aesthetic — too short and a tenant's own jobs collide by
 * accident, too long and it becomes a storage and logging burden — and the
 * character class keeps a value that will be stored and audited from carrying
 * control characters or newlines.
 */
@JvmInline
value class IdempotencyKey private constructor(
    val value: String,
) {
    override fun toString(): String = "IdempotencyKey(<redacted>)"

    companion object {
        const val MIN_LENGTH = 16
        const val MAX_LENGTH = 255

        /** Printable ASCII, so an audit line or a header cannot be split by one. */
        private val ALLOWED = Regex("^[\\x21-\\x7e]+$")

        /**
         * Returns null rather than throwing with the value attached. The
         * rejected key is customer-generated and would otherwise land in a
         * problem body, our access log and the client's CI log — see ADR-033
         * §10; the caller reports the *constraint*, never the input.
         */
        fun of(raw: String): IdempotencyKey? {
            if (raw.length < MIN_LENGTH || raw.length > MAX_LENGTH) return null
            if (!ALLOWED.matches(raw)) return null
            return IdempotencyKey(raw)
        }
    }
}

/**
 * Canonical encoding of the semantically relevant request, hashed into a
 * fingerprint.
 *
 * Length-prefixed with an explicit absent marker, so no two distinct inputs
 * can share an encoding: without the prefix `["a", "b"]` and `["ab", ""]`
 * collide, and without the marker an absent field and an empty one do.
 */
object CanonicalRequest {
    private const val ABSENT = "~"
    private const val PRESENT = "="

    fun encode(fields: List<Pair<String, String?>>): String =
        buildString {
            for ((name, value) in fields) {
                append(name.length).append(':').append(name)
                if (value == null) {
                    append(ABSENT)
                } else {
                    append(PRESENT).append(value.length).append(':').append(value)
                }
                append(';')
            }
        }
}
