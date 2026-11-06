package email.testinbox.storage

import email.testinbox.application.storage.QualificationRecord
import email.testinbox.application.storage.StorageProtocol
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The record `scripts/build-qualification-record.py` writes from the Ops N1 evidence bundle (W4, R2) must load
 * through the REAL parser and be a complete production record. The input is the generator's output on a FAKE
 * bundle (plainly fake values); `scripts/build-qualification-record.test.sh` proves the generator still writes
 * exactly this file, byte for byte, so this test covers the generator's actual output, not a hand-written copy.
 * The file lives in test resources and is never listed in `adr035-qualification/index.txt`: nothing ships.
 */
class GeneratedQualificationRecordTest {
    private val json =
        checkNotNull(javaClass.classLoader.getResource("adr035-generated/fake-production-record.json")) {
            "the golden generated record is missing; run UPDATE_GOLDEN=1 scripts/build-qualification-record.test.sh"
        }.readText()

    @Test
    fun `the generated record parses, is eligible, and has no production gap`() {
        val record = QualificationRecords.parse(json, "fake-production-record.json")
        record.platformClass shouldBe QualificationRecord.PRODUCTION_PLATFORM_CLASS
        record.uploadImplementationVersion shouldBe StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION
        record.qualifiedAt shouldBe "2000-01-01T11:30:00Z"
        record.eligibility().reasons.shouldBeEmpty()
        record.eligibility().eligible shouldBe true
        record.productionGaps().shouldBeEmpty()
        val fs = checkNotNull(record.filesystem)
        fs.objectOverheadMaxBytes shouldBe 24576
        fs.metadataInodesMeasured shouldBe 157
        fs.experiments shouldBe QualificationRecord.REQUIRED_EXPERIMENTS.associateWith { "PASS" }
    }

    @Test
    fun `the generated fixture is plainly fake and is never shipped`() {
        val record = QualificationRecords.parse(json)
        record.recordId shouldContain "fake"
        record.identity.kernelRelease shouldContain "fake"
        QualificationRecords.load().none { it.recordId == record.recordId } shouldBe true
    }
}
