package email.testinbox.application

import java.io.ByteArrayOutputStream

/**
 * Transport-insensitive content fingerprint (ADR-019 §4, as amended).
 *
 * The fingerprint answers "is this the same message content?", so it must not
 * change when the same content traverses a different number of transport hops
 * or is stamped a second later. Every hop — an upstream Postfix relay, then our
 * own SMTP gateway — prepends a `Received:` trace field carrying a timestamp
 * (second resolution) and a queue identifier. Hashing the stored bytes directly
 * made two byte-identical sends fingerprint differently unless they happened to
 * land inside the same wall-clock second, which silently reduced the ADR-019 §4
 * duplicate annotation to the one-second heuristic that ADR explicitly rejected.
 *
 * Normalization therefore removes complete `Received:` fields from the
 * top-level header block and hashes what remains. It is deliberately
 * transport-shaped, not topology-shaped: it keys on nothing but the field name,
 * so it is insensitive to our hostnames, our hop count and our current Postfix
 * layout, and needs no change when those do.
 *
 * The stored raw MIME is never touched (ADR-005): `/raw` keeps every trace
 * header, so the distinct transport evidence behind two matching fingerprints
 * stays visible. The accepted trade-off is that messages differing *only* in
 * their `Received:` fields fingerprint alike and may be annotated as possible
 * duplicates.
 *
 * This value remains informational metadata. It never suppresses a message,
 * never becomes a provider deduplication key, and never uses `Message-ID` as
 * identity: two distinct SMTP `DATA` transactions remain two visible rows even
 * when their fingerprints match.
 */
object ContentFingerprint {
    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A
    private const val SP: Byte = 0x20
    private const val HTAB: Byte = 0x09
    private const val COLON: Byte = 0x3A

    private val RECEIVED = "received".toByteArray(Charsets.US_ASCII)

    /** SHA-256 over [raw] with top-level `Received:` fields removed. */
    fun of(raw: ByteArray): String = Sha256.hex(normalize(raw))

    /**
     * Returns [raw] with complete `Received:` fields removed from the top-level
     * header block. Every other byte — remaining header fields, the header/body
     * separator, and the body — is preserved exactly, so the result stays
     * byte-faithful for everything that is not transport trace.
     */
    internal fun normalize(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size)
        var cursor = 0
        while (cursor < raw.size) {
            val lineEnd = lineEnd(raw, cursor)
            if (contentEnd(raw, cursor, lineEnd) == cursor) {
                // The blank line separating headers from body. It and everything
                // after it are body bytes as far as this normalization cares.
                break
            }
            // A field runs from its first line through any folded continuation
            // lines, which RFC 5322 marks by a leading space or tab. Dropping a
            // Received field means dropping its continuations with it.
            var fieldEnd = lineEnd
            while (fieldEnd < raw.size && (raw[fieldEnd] == SP || raw[fieldEnd] == HTAB)) {
                fieldEnd = lineEnd(raw, fieldEnd)
            }
            if (!isReceivedField(raw, cursor, contentEnd(raw, cursor, lineEnd))) {
                out.write(raw, cursor, fieldEnd - cursor)
            }
            cursor = fieldEnd
        }
        // Separator and body, verbatim.
        if (cursor < raw.size) {
            out.write(raw, cursor, raw.size - cursor)
        }
        return out.toByteArray()
    }

    /** Index just past this line's terminator, or [raw].size when the line is unterminated. */
    private fun lineEnd(
        raw: ByteArray,
        start: Int,
    ): Int {
        var i = start
        while (i < raw.size) {
            if (raw[i] == LF) return i + 1
            i++
        }
        return raw.size
    }

    /** Index where this line's terminator begins — i.e. the end of its content. */
    private fun contentEnd(
        raw: ByteArray,
        start: Int,
        lineEnd: Int,
    ): Int {
        if (lineEnd > start && raw[lineEnd - 1] == LF) {
            // Tolerates bare LF as well as CRLF: a hostile or hand-rolled sender
            // may use either, and the header block must still be found.
            val beforeLf = lineEnd - 1
            return if (beforeLf > start && raw[beforeLf - 1] == CR) beforeLf - 1 else beforeLf
        }
        return lineEnd
    }

    /**
     * True when the line at [start] begins a field whose name is exactly
     * `Received`, compared case-insensitively.
     *
     * The name must match in full: `Received-SPF:` and `X-Received:` are
     * different fields carrying content, not transport trace, and are kept.
     * A line starting with space or tab is a folded continuation and never
     * starts a field, so it is never treated as one here.
     */
    private fun isReceivedField(
        raw: ByteArray,
        start: Int,
        contentEnd: Int,
    ): Boolean {
        if (start >= contentEnd) return false
        if (raw[start] == SP || raw[start] == HTAB) return false
        var colon = -1
        var i = start
        while (i < contentEnd) {
            if (raw[i] == COLON) {
                colon = i
                break
            }
            i++
        }
        if (colon < 0) return false
        // Obsolete RFC 5322 syntax permits whitespace between the field name
        // and its colon; a sender using it still wrote a Received field.
        var nameEnd = colon
        while (nameEnd > start && (raw[nameEnd - 1] == SP || raw[nameEnd - 1] == HTAB)) {
            nameEnd--
        }
        if (nameEnd - start != RECEIVED.size) return false
        for (offset in RECEIVED.indices) {
            if (lowerAscii(raw[start + offset]) != RECEIVED[offset]) return false
        }
        return true
    }

    private fun lowerAscii(b: Byte): Byte = if (b >= 'A'.code.toByte() && b <= 'Z'.code.toByte()) (b + 0x20).toByte() else b
}
