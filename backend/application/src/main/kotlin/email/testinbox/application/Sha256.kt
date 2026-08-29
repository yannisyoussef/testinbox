package email.testinbox.application

import java.security.MessageDigest

object Sha256 {
    fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hex(value: String): String = hex(value.toByteArray(Charsets.UTF_8))
}
