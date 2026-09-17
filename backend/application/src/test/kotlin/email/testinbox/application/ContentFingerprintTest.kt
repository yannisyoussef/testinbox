package email.testinbox.application

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * ADR-019 §4 (as amended): the fingerprint identifies content, not transport.
 *
 * These cases are written as raw bytes rather than through the SMTP gateway so
 * the exact trace headers are chosen, not observed — the guarantee has to hold
 * for timestamps and queue identifiers we will never see locally, including
 * those a TI-005 Postfix relay will add.
 */
class ContentFingerprintTest {
    private val body = "From: sut@example.com\r\nSubject: Verify your email\r\n\r\nClick the link.\r\n"

    private fun fp(vararg parts: String) = ContentFingerprint.of(parts.joinToString("").toByteArray(Charsets.UTF_8))

    @Test
    fun `the same content stamped a second apart fingerprints identically`() {
        // The original defect: one digit of the gateway's own timestamp.
        val first = fp("Received: from a (b [10.0.0.1]) by c; Wed, 16 Sep 2026 22:40:19 -0400 (EDT)\r\n", body)
        val second = fp("Received: from a (b [10.0.0.1]) by c; Wed, 16 Sep 2026 22:40:20 -0400 (EDT)\r\n", body)
        first shouldBe second
    }

    @Test
    fun `queue identifiers do not change the fingerprint`() {
        val first = fp("Received: from a by b with SMTP id MU4X9LA3; Wed, 16 Sep 2026 22:40:19 -0400\r\n", body)
        val second = fp("Received: from a by b with SMTP id ZZ1Q0000; Wed, 16 Sep 2026 22:40:19 -0400\r\n", body)
        first shouldBe second
    }

    @Test
    fun `hop count does not change the fingerprint`() {
        // Direct to the gateway vs. through a relay that added its own hop —
        // the TI-005 Postfix shape. Deliberately not keyed on host or hop count.
        val direct = fp("Received: from gateway by testinbox; Wed, 16 Sep 2026 22:40:19 -0400\r\n", body)
        val relayed =
            fp(
                "Received: from gateway by testinbox; Wed, 16 Sep 2026 22:40:20 -0400\r\n",
                "Received: from sut.example.com by relay.example.net; Wed, 16 Sep 2026 22:40:18 -0400\r\n",
                body,
            )
        direct shouldBe relayed
        direct shouldBe fp(body)
    }

    @Test
    fun `a folded Received field is removed with all of its continuation lines`() {
        // Real trace headers fold across several lines; leaving a continuation
        // behind would reintroduce the timestamp the folding carries.
        val folded =
            fp(
                "Received: from test-client (localhost [127.0.0.1])\r\n" +
                    "\tby localhost\r\n" +
                    "        with SMTP (TestInbox) id MU4X9LA3\r\n" +
                    "        for it-3365cdda@testinbox.local;\r\n" +
                    "        Wed, 16 Sep 2026 22:40:19 -0400 (EDT)\r\n",
                body,
            )
        folded shouldBe fp(body)
    }

    @Test
    fun `Received matching is case-insensitive`() {
        val stamp = " from a by b; Wed, 16 Sep 2026 22:40:19 -0400\r\n"
        fp("received:$stamp", body) shouldBe fp(body)
        fp("RECEIVED:$stamp", body) shouldBe fp(body)
        fp("ReCeIvEd:$stamp", body) shouldBe fp(body)
    }

    @Test
    fun `obsolete whitespace before the colon is still a Received field`() {
        fp("Received : from a by b; Wed, 16 Sep 2026 22:40:19 -0400\r\n", body) shouldBe fp(body)
    }

    @Test
    fun `fields that merely start with Received are content and are preserved`() {
        // Received-SPF and X-Received carry authentication and provider content.
        // Dropping them would discard real differences between two messages.
        fp("Received-SPF: pass (sender is authorized)\r\n", body) shouldNotBe fp(body)
        fp("X-Received: by 10.0.0.1 with SMTP id abc\r\n", body) shouldNotBe fp(body)
        fp("Received-SPF: pass\r\n", body) shouldNotBe fp("Received-SPF: fail\r\n", body)
    }

    @Test
    fun `a Received line in the body is content, not trace`() {
        val withBodyLine = fp("Subject: s\r\n\r\nReceived: from evil by evil; whenever\r\n")
        val withoutIt = fp("Subject: s\r\n\r\n")
        withBodyLine shouldNotBe withoutIt
        // ...and stripping the header trace above it does not reach into it.
        fp("Received: from a by b; now\r\nSubject: s\r\n\r\nReceived: x\r\n") shouldBe
            fp("Subject: s\r\n\r\nReceived: x\r\n")
    }

    @Test
    fun `everything that is not transport trace still changes the fingerprint`() {
        val base = fp("Subject: a\r\n\r\nbody\r\n")
        base shouldNotBe fp("Subject: b\r\n\r\nbody\r\n")
        base shouldNotBe fp("Subject: a\r\n\r\nbody!\r\n")
        base shouldNotBe fp("Subject: a\r\nTo: x@y.z\r\n\r\nbody\r\n")
    }

    @Test
    fun `bare LF line endings are normalized too`() {
        fp("Received: from a by b; Wed, 16 Sep 2026 22:40:19 -0400\nSubject: s\n\nbody\n") shouldBe
            fp("Subject: s\n\nbody\n")
    }

    @Test
    fun `the header and body separator is preserved, so CRLF and LF stay distinct`() {
        // Preserving the separator byte-for-byte is part of the rule: a message
        // that arrived with bare LF is not the same bytes as one with CRLF.
        fp("Subject: s\r\n\r\nbody") shouldNotBe fp("Subject: s\n\nbody")
    }

    @Test
    fun `a header-only message with no separator is handled`() {
        fp("Received: from a by b; now\r\nSubject: s\r\n") shouldBe fp("Subject: s\r\n")
        fp("Received: from a by b; now\r\n") shouldBe fp("")
    }

    @Test
    fun `degenerate input does not throw`() {
        ContentFingerprint.of(ByteArray(0)) shouldBe Sha256.hex(ByteArray(0))
        ContentFingerprint.of("\r\n".toByteArray()) shouldBe Sha256.hex("\r\n".toByteArray())
        // A leading continuation line has no field to continue; it is kept as-is
        // rather than guessed at.
        ContentFingerprint.normalize("\tstray\r\nSubject: s\r\n".toByteArray()) shouldBe
            "\tstray\r\nSubject: s\r\n".toByteArray()
    }

    @Test
    fun `body bytes are preserved exactly, including binary`() {
        val binary = byteArrayOf(0x00, 0x7F, -0x01, -0x80, 0x0D, 0x0A, 0x00)
        val raw = "Received: from a by b; now\r\nSubject: s\r\n\r\n".toByteArray() + binary
        ContentFingerprint.normalize(raw) shouldBe "Subject: s\r\n\r\n".toByteArray() + binary
    }

    @Test
    fun `normalization never lengthens the message`() {
        val raw = ("Received: from a by b; now\r\n" + body).toByteArray()
        (ContentFingerprint.normalize(raw).size <= raw.size) shouldBe true
    }
}
