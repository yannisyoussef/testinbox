package email.testinbox.storage

import email.testinbox.application.QualificationProperties
import email.testinbox.application.storage.QualificationRecordSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Issue #102: the lab-only qualification record is read from a file, strictly.
 * It must be a complete record of the shipped shape, declare `platformClass:
 * "lab"` and `enablementEligible: false`, or it does not load — a lab file can
 * never read as a qualified, eligible record. Whether it may be USED is
 * `DeploymentSafety`'s decision (environment `lab` only).
 */
class LabQualificationRecordTest {
    private fun shipped(): String =
        requireNotNull(javaClass.classLoader.getResource("adr035-qualification/staging-amd64-vmi2932906-2026-10-08.json")).readText()

    private fun labJson(
        platformClass: String = "lab",
        eligible: Boolean = false,
    ): String =
        shipped()
            .replace("\"recordId\": \"staging-amd64-vmi2932906-2026-10-08\"", "\"recordId\": \"lab-ti-applab-2026-10-19\"")
            .replace("\"platformClass\": \"staging-host\"", "\"platformClass\": \"$platformClass\"")
            .replace("\"enablementEligible\": true", "\"enablementEligible\": $eligible")

    @Test
    fun `a lab record loads from its file as a LAB-sourced record`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("lab.json").also { Files.writeString(it, labJson()) }

        val record = QualificationRecords.loadLab(file)

        record.source shouldBe QualificationRecordSource.LAB
        record.recordId shouldBe "lab-ti-applab-2026-10-19"
        record.isLab shouldBe true
        record.enablementEligible shouldBe false
        // Its content never makes it eligible as a shipped record would be judged.
        record.eligibility().eligible shouldBe false
        LabQualificationRecord.load(QualificationProperties(labRecordPath = file.toString())).map { it.recordId } shouldBe
            listOf("lab-ti-applab-2026-10-19")
    }

    @Test
    fun `a lab file that is not platformClass lab, or claims eligibility, does not load at all`(
        @TempDir dir: Path,
    ) {
        val notLab = dir.resolve("staging-shaped.json").also { Files.writeString(it, labJson(platformClass = "staging-host")) }
        assertThrows<QualificationRecordException> { QualificationRecords.loadLab(notLab) }.message shouldContain "platformClass"

        val eligible = dir.resolve("eligible.json").also { Files.writeString(it, labJson(eligible = true)) }
        assertThrows<QualificationRecordException> { QualificationRecords.loadLab(eligible) }.message shouldContain
            "enablementEligible: false"
    }

    @Test
    fun `an unreadable or malformed lab file fails loudly, never as an absent record`(
        @TempDir dir: Path,
    ) {
        assertThrows<QualificationRecordException> { QualificationRecords.loadLab(dir.resolve("missing.json")) }.message shouldContain
            "cannot be read"
        val malformed = dir.resolve("bad.json").also { Files.writeString(it, "{\"schemaVersion\": 1}") }
        assertThrows<QualificationRecordException> { QualificationRecords.loadLab(malformed) }
    }

    @Test
    fun `no path configured means no lab record, and the shipped records still carry none`() {
        LabQualificationRecord.load(QualificationProperties()).shouldBeEmpty()
        LabQualificationRecord.load(QualificationProperties(labRecordPath = "   ")).shouldBeEmpty()
        QualificationRecords.load().forEach { it.source shouldBe QualificationRecordSource.SHIPPED }
        QualificationRecords.load().none { it.isLab } shouldBe true
    }
}
