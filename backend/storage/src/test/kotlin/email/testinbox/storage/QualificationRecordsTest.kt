package email.testinbox.storage

import email.testinbox.application.storage.StorageProtocol
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * ADR-035 §9a: the records shipped inside the artifact are the compatibility
 * contract. Loading is strict (a malformed record can never read as
 * qualified), and the committed laptop record is loaded, complete, and NOT
 * enablement-eligible — exactly as `QUALIFICATION.md` says.
 */
class QualificationRecordsTest {
    private val good =
        """
        {
          "schemaVersion": 1,
          "recordId": "fixture",
          "qualifiedAt": "2026-10-01T00:00:00Z",
          "minio": {
            "imageIndexDigest": "sha256:${"1".repeat(64)}",
            "platformMemberDigest": "sha256:${"2".repeat(64)}",
            "platform": "linux/amd64",
            "release": "RELEASE.2025-04-22T22-12-26Z",
            "commitId": "0d7408fc9969caf07de6a8c3a84f9fbb10a6739e",
            "binarySha256": "${"3".repeat(64)}",
            "mode": "single-node-single-drive",
            "driveCount": 1,
            "timeoutEnvironment": {"MINIO_IDLE_TIMEOUT": "30s"},
            "timeoutCliFlags": [],
            "runtimeConfig": {"normalized": "release-defaults", "hash": "sha256:${"4".repeat(64)}"},
            "storageClassInlineDefaults": "release-defaults"
          },
          "host": {"kernelRelease": "6.8.0", "architecture": "x86_64", "filesystemType": "xfs", "mountOptions": "rw,noatime", "device": "nvme"},
          "network": {"directPath": true, "proxy": "none", "tls": false},
          "uploadImplementationVersion": "${StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION}",
          "qualification": {
            "platformClass": "staging-host",
            "platform": "fixture",
            "evidenceReference": "fixture/",
            "procedure": "fixture",
            "scenarios": ["base-W", "slow-W"],
            "trials": 1,
            "lateCommits": 0,
            "slowWExecuted": true
          },
          "enablementEligible": true,
          "ineligibilityReasons": []
        }
        """.trimIndent()

    @Test
    fun `a complete record parses into the contract, element by element`() {
        val record = QualificationRecords.parse(good)
        record.recordId shouldBe "fixture"
        record.identity.imageIndexDigest shouldBe "sha256:" + "1".repeat(64)
        record.identity.timeoutEnvironment shouldBe mapOf("MINIO_IDLE_TIMEOUT" to "30s")
        record.identity.runtimeConfigHash shouldBe "sha256:" + "4".repeat(64)
        record.identity.directPath shouldBe true
        record.slowWExecuted shouldBe true
        record.eligibility().eligible shouldBe true
    }

    @Test
    fun `a malformed record fails to load rather than reading as qualified`() {
        fun broken(transform: (String) -> String) =
            assertThrows<QualificationRecordException> { QualificationRecords.parse(transform(good), "fixture.json") }
        broken { it.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2") }.message shouldContain "schemaVersion"
        broken { it.replace("\"driveCount\": 1", "\"driveCount\": \"one\"") }.message shouldContain "driveCount"
        broken { it.replace("\"directPath\": true", "\"directPath\": \"yes\"") }.message shouldContain "directPath"
        broken { it.replace("\"slowWExecuted\": true", "\"slowWExecuted\": 1") }.message shouldContain "slowWExecuted"
        broken { it.replace("\"kernelRelease\": \"6.8.0\",", "") }.message shouldContain "kernelRelease"
        broken { it.replace("\"timeoutCliFlags\": []", "\"timeoutCliFlags\": \"none\"") }.message shouldContain "timeoutCliFlags"
        broken { it.replace("\"mode\": \"single-node-single-drive\"", "\"mode\": \"\"") }.message shouldContain "mode"
        broken { "not json" }.message shouldContain "not valid JSON"
    }

    @Test
    fun `an absent runtime-configuration hash loads as null, and the record is then not eligible`() {
        val record = QualificationRecords.parse(good.replace("\"hash\": \"sha256:${"4".repeat(64)}\"", "\"hash\": null"))
        record.identity.runtimeConfigHash shouldBe null
        record.eligibility().eligible shouldBe false
        record.eligibility().reasons shouldContain "the runtime admin-configuration hash is absent"
    }

    @Test
    fun `the artifact ships the laptop record, and it is loaded, complete and NOT enablement-eligible`() {
        val records = QualificationRecords.load()
        records.shouldNotBeEmpty()
        val laptop = records.single { it.recordId == "laptop-arm64-reference-2026-09-29" }
        laptop.identity.imageIndexDigest shouldBe "sha256:bbac678936882e4033b6068efd0a19d103b219002d979087f7c5bd330172e00d"
        laptop.identity.mode shouldBe "single-node-single-drive"
        laptop.identity.kernelRelease shouldContain "linuxkit"
        laptop.identity.filesystemType shouldBe "ext4"
        laptop.slowWExecuted shouldBe false
        // Qualified with v1; the uploader is v2 since Ops E8, so the record no longer matches (re-qualify, §9a).
        laptop.uploadImplementationVersion shouldBe "adr035-presigned-put-v1"
        (laptop.uploadImplementationVersion == StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION) shouldBe false
        val eligibility = laptop.eligibility()
        eligibility.eligible shouldBe false
        eligibility.reasons.joinToString() shouldContain "slow-W"
    }

    @Test
    fun `an index naming a missing file fails to load`() {
        val loader =
            object : ClassLoader(QualificationRecordsTest::class.java.classLoader) {
                override fun getResource(name: String) =
                    if (name == QualificationRecords.INDEX) {
                        java.io.File
                            .createTempFile("index", ".txt")
                            .apply {
                                writeText("ghost.json\n")
                                deleteOnExit()
                            }.toURI()
                            .toURL()
                    } else {
                        null
                    }
            }
        assertThrows<QualificationRecordException> { QualificationRecords.load(loader) }.message shouldContain "ghost.json"
    }
}
