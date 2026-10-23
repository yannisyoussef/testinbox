package email.testinbox.domain.tenant

import java.security.SecureRandom
import java.util.zip.CRC32

/**
 * The wire format of a TestInbox API credential (ADR-032 §1).
 *
 * ```
 * ti_k1_<publicId>_<secret>_<check>
 * ```
 *
 * The alphabet is lowercase RFC 4648 base32, chosen because it contains
 * neither `_` nor `-`: the separator is therefore unambiguous and the whole
 * token survives a shell, a URL, a YAML value or a CI secret field without
 * escaping.
 *
 * Framework-free by construction (ADR-024) — this is the one description of
 * the credential's shape, and both the minting path and the authentication
 * path read it from here rather than re-deriving it.
 */
object ApiKeyFormat {
    const val PRODUCT_MARKER = "ti"

    /** Bump for a new shape; `k1` and a future `k2` authenticate side by side. */
    const val VERSION = "k1"

    const val PREFIX = "${PRODUCT_MARKER}_$VERSION"

    /** 16 base32 chars = 80 bits. Random, so it discloses no ordering or count. */
    const val PUBLIC_ID_LENGTH = 16

    /**
     * 52 base32 chars = 260 bits of CSPRNG output. Enforced on the way in
     * *and* on the way out, so no code path can mint or accept a credential
     * weaker than the entropy ADR-032 §2 reasons about.
     */
    const val SECRET_LENGTH = 52

    /** 20 bits of CRC-32. Integrity against truncation and typos — never security. */
    const val CHECK_LENGTH = 4

    /** `ti`, version, public id, secret, checksum. */
    const val SEGMENTS = 5

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private val ALPHABET_SET = ALPHABET.toSet()

    /**
     * True when [value] *looks like* a TestInbox credential. Used to route
     * parsing and — more importantly — to recognise the token in places where
     * it must never appear.
     */
    fun looksLikeCredential(value: String): Boolean = value.startsWith("${PRODUCT_MARKER}_")

    fun isBase32(value: String): Boolean = value.isNotEmpty() && value.all { it in ALPHABET_SET }

    /** CRC-32 of everything preceding the check segment, low 20 bits, base32-encoded. */
    fun checksumOf(
        publicId: String,
        secret: String,
    ): String {
        val crc = CRC32()
        crc.update("${PREFIX}_${publicId}_$secret".toByteArray(Charsets.US_ASCII))
        var remaining = crc.value and 0xFFFFF
        val out = CharArray(CHECK_LENGTH)
        for (i in CHECK_LENGTH - 1 downTo 0) {
            out[i] = ALPHABET[(remaining and 0x1F).toInt()]
            remaining = remaining shr 5
        }
        return String(out)
    }

    fun render(
        publicId: String,
        secret: String,
    ): String = "${PREFIX}_${publicId}_${secret}_${checksumOf(publicId, secret)}"

    fun randomBase32(
        length: Int,
        random: SecureRandom,
    ): String {
        val out = StringBuilder(length)
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        for (b in bytes) out.append(ALPHABET[(b.toInt() and 0x1F)])
        return out.toString()
    }

    /**
     * Mints a fresh credential: 260 bits of CSPRNG output in the secret, and
     * an independently random public id that encodes nothing.
     */
    fun generate(random: SecureRandom = SecureRandom()): ApiKeyCredential {
        val publicId = randomBase32(PUBLIC_ID_LENGTH, random)
        val secret = randomBase32(SECRET_LENGTH, random)
        return ApiKeyCredential(publicId, secret)
    }

    /**
     * Parses a presented token, rejecting anything that is not exactly a
     * well-formed `k1` credential.
     *
     * Rejection here is *cheap and local*: a truncated paste never reaches the
     * database and never produces a lookup that has to be explained. The
     * checksum's whole job is to make that failure legible instead of an
     * indistinguishable 401 in someone else's CI.
     */
    fun parse(presented: String): ParsedCredential {
        val parts = presented.split('_')
        if (parts.size != SEGMENTS) return ParsedCredential.Malformed
        val (marker, version, publicId) = parts
        val secret = parts[3]
        val check = parts[4]
        if (marker != PRODUCT_MARKER) return ParsedCredential.Malformed
        if (version != VERSION) return ParsedCredential.UnsupportedVersion
        if (publicId.length != PUBLIC_ID_LENGTH || !isBase32(publicId)) return ParsedCredential.Malformed
        if (secret.length != SECRET_LENGTH || !isBase32(secret)) return ParsedCredential.Malformed
        if (check.length != CHECK_LENGTH || !isBase32(check)) return ParsedCredential.Malformed
        if (check != checksumOf(publicId, secret)) return ParsedCredential.ChecksumMismatch
        return ParsedCredential.Valid(ApiKeyCredential(publicId, secret))
    }
}

/**
 * A complete credential — public handle plus secret. Exists only transiently:
 * at mint time on the way to the single `201` response, and per request on
 * the way to verification. It is never persisted, logged or serialised.
 */
data class ApiKeyCredential(
    val publicId: String,
    val secret: String,
) {
    init {
        require(publicId.length == ApiKeyFormat.PUBLIC_ID_LENGTH && ApiKeyFormat.isBase32(publicId)) {
            "public id must be ${ApiKeyFormat.PUBLIC_ID_LENGTH} base32 characters"
        }
        require(secret.length == ApiKeyFormat.SECRET_LENGTH && ApiKeyFormat.isBase32(secret)) {
            "secret must be ${ApiKeyFormat.SECRET_LENGTH} base32 characters"
        }
    }

    /** The full token. The only caller that may keep the result is the create response. */
    fun render(): String = ApiKeyFormat.render(publicId, secret)

    /**
     * Never let the secret reach a log, a stack trace or an error message
     * through the default data-class rendering.
     */
    override fun toString(): String = "ApiKeyCredential(publicId=$publicId, secret=<redacted>)"
}

sealed interface ParsedCredential {
    data class Valid(
        val credential: ApiKeyCredential,
    ) : ParsedCredential

    /** Not a `ti_`-shaped token at all, or structurally wrong. */
    data object Malformed : ParsedCredential

    /** A TestInbox credential of a format this build does not know. */
    data object UnsupportedVersion : ParsedCredential

    /** Well-shaped but self-inconsistent — almost always a truncated or mistyped paste. */
    data object ChecksumMismatch : ParsedCredential
}
