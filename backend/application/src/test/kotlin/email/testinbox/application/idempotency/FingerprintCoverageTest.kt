package email.testinbox.application.idempotency

import email.testinbox.application.usecase.CreateApiKey
import email.testinbox.application.usecase.CreateInbox
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The fingerprint enumerates fields, and a hand-maintained enumeration rots.
 *
 * The failure it rots into is quiet and expensive: the next field added to a
 * command would simply be left out, so a retry that changed only that field
 * would replay the old result. The client would not get what it asked for, and
 * nothing anywhere would report an error.
 *
 * This fails the build the day a field is added, and the only ways to satisfy
 * it are to fingerprint the field or to state in
 * [RequestFingerprint.DELIBERATELY_EXCLUDED] why it does not belong.
 */
class FingerprintCoverageTest {
    /**
     * Java reflection, not kotlin-reflect: the application module does not
     * depend on it, and adding a dependency for one test would be the wrong
     * trade. A data class's constructor properties are its declared fields.
     */
    private fun uncovered(
        type: Class<*>,
        label: String,
        fingerprinted: Set<String>,
    ): List<String> {
        val fields = type.declaredFields.filterNot { it.isSynthetic }.map { it.name }
        // Guard the guard: reflection that found nothing would pass silently.
        check(fields.isNotEmpty()) { "no fields found on $label — this test would prove nothing" }
        return fields
            .filterNot { it in fingerprinted }
            .filterNot { "$label.$it" in RequestFingerprint.DELIBERATELY_EXCLUDED }
            .map { "$label.$it" }
    }

    @Test
    fun `every CreateInbox command field is fingerprinted or explicitly excluded`() {
        uncovered(
            CreateInbox.Command::class.java,
            "CreateInbox.Command",
            setOf("projectId", "addressMode", "ttlSeconds", "aliasHint", "localPart"),
        ).shouldBeEmpty()
    }

    @Test
    fun `every CreateApiKey command field is fingerprinted or explicitly excluded`() {
        uncovered(
            CreateApiKey.Command::class.java,
            "CreateApiKey.Command",
            setOf("name", "scopes", "expiresIn"),
        ).shouldBeEmpty()
    }

    @Test
    fun `the exclusions carry a reason, so dropping a field is a visible act`() {
        RequestFingerprint.DELIBERATELY_EXCLUDED.values.forEach { reason -> (reason.length > 20) shouldBe true }
        // Guard the guard: an empty map would make the filters above vacuous.
        RequestFingerprint.DELIBERATELY_EXCLUDED.isNotEmpty() shouldBe true
    }
}
