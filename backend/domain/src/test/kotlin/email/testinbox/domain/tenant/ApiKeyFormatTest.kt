package email.testinbox.domain.tenant

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class ApiKeyFormatTest {
    private val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1234L) }

    private fun valid(): ApiKeyCredential = ApiKeyFormat.generate(random)

    @Test
    fun `a generated credential round-trips through parse`() {
        val credential = valid()
        val parsed = ApiKeyFormat.parse(credential.render()).shouldBeInstanceOf<ParsedCredential.Valid>()
        parsed.credential shouldBe credential
    }

    @Test
    fun `the format is recognisable, versioned, and carries the specified entropy`() {
        val rendered = valid().render()
        rendered shouldStartWith "ti_k1_"
        val parts = rendered.split('_')
        parts shouldHaveSize 5
        parts[2].length shouldBe ApiKeyFormat.PUBLIC_ID_LENGTH
        // 52 base32 characters = 260 bits. The length is the entropy claim
        // ADR-032 §2 rests on, so it is asserted rather than assumed.
        parts[3].length shouldBe ApiKeyFormat.SECRET_LENGTH
        parts[4].length shouldBe ApiKeyFormat.CHECK_LENGTH
    }

    @Test
    fun `the separator can never be ambiguous — no component may contain one`() {
        // base64url would have been shorter and would have put `-` and `_`
        // inside the components, silently breaking the split for some keys.
        repeat(200) {
            val credential = ApiKeyFormat.generate(random)
            (credential.publicId + credential.secret).forEach { c ->
                (c == '_' || c == '-') shouldBe false
            }
        }
    }

    @Test
    fun `generated credentials do not repeat`() {
        val rendered = (1..500).map { ApiKeyFormat.generate(random).render() }
        rendered.toSet() shouldHaveSize rendered.size
    }

    @Test
    fun `a truncated credential is rejected locally, before any lookup`() {
        val rendered = valid().render()
        // The exact failure a checksum exists to catch: a copy-paste that lost
        // its tail. Without it this is an unexplained 401 in someone's CI.
        ApiKeyFormat.parse(rendered.dropLast(1)) shouldBe ParsedCredential.Malformed
        ApiKeyFormat.parse(rendered.dropLast(6)) shouldBe ParsedCredential.Malformed
    }

    @Test
    fun `a single transposed character is caught by the checksum`() {
        val credential = valid()
        val secret = credential.secret
        val swapped = secret.substring(0, 3) + secret[4] + secret[3] + secret.substring(5)
        val mistyped =
            "${ApiKeyFormat.PREFIX}_${credential.publicId}_${swapped}_" +
                ApiKeyFormat.checksumOf(credential.publicId, secret)
        ApiKeyFormat.parse(mistyped) shouldBe ParsedCredential.ChecksumMismatch
    }

    @Test
    fun `an unknown format version is distinguished from garbage`() {
        val credential = valid()
        val future =
            "ti_k9_${credential.publicId}_${credential.secret}_" +
                ApiKeyFormat.checksumOf(credential.publicId, credential.secret)
        // Reported separately so a k1-only build meeting a k2 credential is
        // diagnosable, rather than looking like a malformed paste.
        ApiKeyFormat.parse(future) shouldBe ParsedCredential.UnsupportedVersion
    }

    @Test
    fun `structurally wrong tokens are rejected without exceptions`() {
        val good = valid()
        listOf(
            "",
            "ti",
            "ti_k1",
            "ti_k1__",
            "ti_k1_short_secret_abcd",
            "xx_k1_${good.publicId}_${good.secret}_abcd",
            // 16 chars, so it is rejected for the alphabet rather than for
            // length — the earlier fixture was 17 and never reached isBase32.
            "ti_k1_UPPERCASENOTBAS_${good.secret}_abcd",
            // '0', '1' and '8' are outside the base32 alphabet.
            "ti_k1_0000000000000000_${good.secret}_abcd",
            "ti_k1_${good.publicId}_${good.secret}_ab_cd",
        ).forEach { token ->
            // The `shouldBeInstanceOf<ParsedCredential>` that used to be here
            // was a tautology on the declared return type.
            withClue(token) { (ApiKeyFormat.parse(token) is ParsedCredential.Valid) shouldBe false }
        }
    }

    @Test
    fun `a credential never renders its secret through toString`() {
        val credential = valid()
        val rendered = credential.toString()
        rendered.contains(credential.secret) shouldBe false
        rendered.contains("redacted") shouldBe true
        // The public id is safe to show and is what makes the log line useful.
        rendered.contains(credential.publicId) shouldBe true
    }

    @Test
    fun `the type refuses to hold a weaker secret than the format promises`() {
        // Belt and braces around ADR-032 §2: the entropy argument is only
        // sound if no code path can construct a short secret at all.
        runCatching { ApiKeyCredential("short", "a".repeat(ApiKeyFormat.SECRET_LENGTH)) }.isFailure shouldBe true
        runCatching { ApiKeyCredential(valid().publicId, "aaaa") }.isFailure shouldBe true
    }

    @Test
    fun `looksLikeCredential recognises our tokens and not a bootstrap secret`() {
        ApiKeyFormat.looksLikeCredential(valid().render()) shouldBe true
        ApiKeyFormat.looksLikeCredential("ti_k9_whatever") shouldBe true
        ApiKeyFormat.looksLikeCredential("a-configured-bootstrap-secret") shouldBe false
    }
}
