package email.testinbox.domain.message

/**
 * The wait/filter matcher vocabulary (docs/architecture/wait-semantics.md,
 * docs/sdk/principles.md). A message satisfies a matcher iff ALL specified
 * fields match. Only parsed messages (`parseStatus == OK`) can satisfy a
 * matcher; ParseFailed messages never match (ADR-012).
 */
data class HeaderMatcher(val name: String, val value: String? = null)

data class MessageMatcher(
    /** Case-insensitive exact match against the parsed From address (or raw From header fallback). */
    val from: String? = null,
    val subjectContains: String? = null,
    val subjectEquals: String? = null,
    val headers: List<HeaderMatcher> = emptyList(),
) {
    fun matches(message: Message): Boolean {
        if (message.parseStatus != ParseStatus.OK) return false
        val parsed = message.parsed ?: return false
        if (from != null) {
            val address = parsed.fromAddress
            val header = parsed.fromHeader
            val addressMatches = address != null && address.equals(from, ignoreCase = true)
            val headerMatches = header != null && header.equals(from, ignoreCase = true)
            if (!addressMatches && !headerMatches) return false
        }
        if (subjectEquals != null && parsed.subject != subjectEquals) return false
        if (subjectContains != null && (parsed.subject == null || !parsed.subject.contains(subjectContains))) {
            return false
        }
        for (headerMatcher in headers) {
            val candidates = parsed.headers.filter { it.name.equals(headerMatcher.name, ignoreCase = true) }
            if (candidates.isEmpty()) return false
            if (headerMatcher.value != null && candidates.none { it.value == headerMatcher.value }) return false
        }
        return true
    }
}
