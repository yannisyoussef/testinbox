package email.testinbox.application.identity

import email.testinbox.application.Sha256
import email.testinbox.domain.identity.LoginProvider
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Protect PKCE/nonce material outside backups and independently of database access. */
class ProviderLoginCrypto(
    keyBytes: ByteArray,
) {
    private val key = keyBytes.copyOf().also { require(it.size == 32) { "provider state requires a 32-byte key" } }
    private val encryptionKey = SecretKeySpec(hmac("provider-state-encryption-v1"), "AES")
    private val random = SecureRandom()

    fun stateHash(state: String): String {
        require(validState(state))
        return Sha256.hex("provider-state-v1:$state")
    }

    fun browserHash(browser: SessionCredential): String = Sha256.hex("provider-browser-v1:${browser.value}")

    fun sourceHash(verifiedPeer: String): String {
        require(verifiedPeer.isNotBlank() && verifiedPeer.length <= 255)
        return hmac("provider-source-v1:$verifiedPeer").joinToString("") { "%02x".format(it) }
    }

    fun seal(
        provider: LoginProvider,
        stateHash: String,
        request: String,
    ): String {
        val plaintext = request.toByteArray(Charsets.UTF_8)
        require(plaintext.size in 1..8192) { "provider request exceeds its internal bound" }
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(provider, stateHash))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext))
    }

    fun open(
        provider: LoginProvider,
        stateHash: String,
        encoded: String,
    ): String? =
        try {
            if (encoded.length > 16_384) {
                null
            } else {
                val bytes = Base64.getDecoder().decode(encoded)
                if (bytes.size < 29) {
                    null
                } else {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                    cipher.updateAAD(aad(provider, stateHash))
                    cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
                }
            }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    fun validState(state: String): Boolean = state.matches(Regex("[A-Za-z0-9_=-]{16,256}"))

    override fun toString(): String = "ProviderLoginCrypto([REDACTED])"

    private fun aad(
        provider: LoginProvider,
        stateHash: String,
    ): ByteArray = "testinbox-provider-request-v1:${provider.name}:$stateHash".toByteArray(Charsets.US_ASCII)

    private fun hmac(value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
    }
}
