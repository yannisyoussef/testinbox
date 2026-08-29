package email.testinbox.domain.inbox

/**
 * Validation and normalization of caller-supplied EXACT-mode local-parts
 * (ADR-021): RFC 5321 length <= 64, conservative character set,
 * lowercase-normalized before uniqueness checks, no quoted-string
 * local-parts, and a denylist of RFC 2142 role addresses plus
 * operationally sensitive names.
 */
object LocalPartPolicy {
    /** RFC 2142 role addresses and operationally sensitive names — never reservable by a tenant. */
    val DENYLIST: Set<String> =
        setOf(
            "postmaster", "abuse", "hostmaster", "webmaster", "security",
            "usenet", "news", "uucp", "ftp", "www", "noc",
            "admin", "administrator", "root", "support", "billing",
            "noreply", "no-reply", "info", "sales", "help", "contact",
            "legal", "privacy", "mailer-daemon", "marketing",
        )

    private val VALID = Regex("^[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?$")

    sealed interface Result {
        data class Valid(val normalized: String) : Result

        data class Invalid(val reason: String) : Result

        data class Denied(val localPart: String) : Result
    }

    fun validate(raw: String): Result {
        val normalized = raw.trim().lowercase()
        if (normalized.isEmpty()) return Result.Invalid("localPart must not be empty")
        if (normalized.length > 64) return Result.Invalid("localPart exceeds 64 characters (RFC 5321)")
        if (!VALID.matches(normalized)) {
            return Result.Invalid(
                "localPart must match [a-z0-9._-], start and end with a letter or digit",
            )
        }
        if (normalized.contains("..")) return Result.Invalid("localPart must not contain consecutive dots")
        if (normalized in DENYLIST) return Result.Denied(normalized)
        return Result.Valid(normalized)
    }
}
