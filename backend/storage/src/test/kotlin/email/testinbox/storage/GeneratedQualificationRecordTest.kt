package email.testinbox.storage

import email.testinbox.application.storage.QualificationRecord
import email.testinbox.application.storage.StorageProtocol
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

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
        // E4 completes last in the fake bundle (as window N1-A2a does: A1 (iii), its (vi) census, then the (iv) ENOSPC
        // run), so its completedAt is qualifiedAt.
        record.qualifiedAt shouldBe "2000-01-01T12:40:00Z"
        record.eligibility().reasons.shouldBeEmpty()
        record.eligibility().eligible shouldBe true
        record.productionGaps().shouldBeEmpty()
        val fs = checkNotNull(record.filesystem)
        fs.objectOverheadMaxBytes shouldBe 24576
        fs.metadataInodesMeasured shouldBe 157
        fs.experiments shouldBe QualificationRecord.REQUIRED_EXPERIMENTS.associateWith { "PASS" }
    }

    /**
     * A1 (iii) is an additive member, like `inodeCensus`: the parser reads members by name, so the record above
     * parses although it carries it. This pins that the parsed file really does carry it.
     */
    @Test
    fun `the generated record carries the additive A1 (iii) bounded measurement the parser ignores`() {
        val a1 =
            checkNotNull(
                JsonMapper
                    .builder()
                    .build()
                    .readTree(json)
                    .get("filesystem")
                    ?.get("inodeBoundedMeasurement"),
            ) {
                "the golden generated record has no filesystem.inodeBoundedMeasurement"
            }
        a1.get("experiment").asString() shouldBe "A1iii"
        a1.get("objects").asLong() shouldBe 20446
        listOf("0B", "1B", "2-512B").map { a1.get("objectsPerPhase").get(it).asLong() } shouldBe listOf(2000L, 2000L, 16446L)
        a1.get("basis").asString() shouldContain "deferred raw.eml"
        QualificationRecords.parse(json).recordId shouldBe "production-amd64-fake-host-0-2000-01-01"
    }

    @Test
    fun `the generated fixture is plainly fake and is never shipped`() {
        val record = QualificationRecords.parse(json)
        record.recordId shouldContain "fake"
        record.identity.kernelRelease shouldContain "fake"
        QualificationRecords.load().none { it.recordId == record.recordId } shouldBe true
    }
}
