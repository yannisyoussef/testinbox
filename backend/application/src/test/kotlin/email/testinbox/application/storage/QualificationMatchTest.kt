package email.testinbox.application.storage

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * ADR-035 §9a: a declared identity matches a record only when EVERY element
 * is equal, and a change to any one of them invalidates the match (the MinIO
 * change rule). The record's own eligibility is re-derived, never trusted.
 */
class QualificationMatchTest {
    private val record = QualificationFixtures.eligible
    private val identity = QualificationFixtures.identity

    private fun mismatches(declared: StorageBackendIdentity) =
        QualificationMatch.compare(declared, QualificationFixtures.UPLOAD, record).mismatchedElements

    @Test
    fun `an identical declaration matches with no differing element`() {
        mismatches(identity).shouldBeEmpty()
        QualificationMatch.find(identity, QualificationFixtures.UPLOAD, listOf(QualificationFixtures.laptopLike, record))?.recordId shouldBe
            "fixture-eligible"
    }

    @Test
    fun `every single element change invalidates the match and is named (ADR-035 §9a invalidation rule)`() {
        mapOf(
            "minio.imageIndexDigest" to identity.copy(imageIndexDigest = "sha256:" + "f".repeat(64)),
            "minio.platformMemberDigest" to identity.copy(platformMemberDigest = "sha256:" + "e".repeat(64)),
            "minio.release" to identity.copy(release = "RELEASE.2026-01-01T00-00-00Z"),
            "minio.commitId" to identity.copy(commitId = "deadbeef"),
            "minio.mode" to identity.copy(mode = "distributed-erasure"),
            "minio.driveCount" to identity.copy(driveCount = 4),
            "minio.timeoutEnvironment" to identity.copy(timeoutEnvironment = mapOf("MINIO_IDLE_TIMEOUT" to "0")),
            "minio.timeoutCliFlags" to identity.copy(timeoutCliFlags = listOf("--idle-timeout=0")),
            "minio.runtimeConfig.hash" to identity.copy(runtimeConfigHash = "sha256:" + "9".repeat(64)),
            "host.kernelRelease" to identity.copy(kernelRelease = "6.12.0"),
            "host.filesystemType" to identity.copy(filesystemType = "xfs"),
            "host.mountOptions" to identity.copy(mountOptions = "rw,noatime"),
            "minio.storageClassInlineDefaults" to identity.copy(storageClassInlineDefaults = "inline=0"),
            "network.directPath" to identity.copy(directPath = false),
            "network.proxy" to identity.copy(proxy = "haproxy"),
        ).forEach { (element, changed) ->
            mismatches(changed) shouldContainExactly listOf(element)
        }
    }

    @Test
    fun `the upload implementation version compared is the running binary's`() {
        QualificationMatch
            .compare(identity, "adr035-presigned-put-v2", record)
            .mismatchedElements shouldContainExactly listOf(QualificationMatch.UPLOAD_IMPLEMENTATION)
    }

    @Test
    fun `an absent runtime-configuration hash on either side never matches`() {
        mismatches(identity.copy(runtimeConfigHash = null)) shouldContain "minio.runtimeConfig.hash"
        QualificationMatch
            .compare(identity, QualificationFixtures.UPLOAD, record.copy(identity = identity.copy(runtimeConfigHash = null)))
            .mismatchedElements shouldContain "minio.runtimeConfig.hash"
    }

    @Test
    fun `eligibility is re-derived from the record's content and never widened`() {
        QualificationFixtures.eligible.eligibility().eligible shouldBe true
        val laptop = QualificationFixtures.laptopLike.eligibility()
        laptop.eligible shouldBe false
        laptop.reasons shouldContain "slow-W was not executed (ADR-035 §9a)"
        laptop.reasons shouldContain "the runtime admin-configuration hash is absent"
        // A record that CLAIMS eligibility without slow-W is corrected, not believed.
        QualificationFixtures.eligible
            .copy(slowWExecuted = false)
            .eligibility()
            .eligible shouldBe false
        QualificationFixtures.eligible
            .copy(identity = identity.copy(kernelRelease = ""))
            .eligibility()
            .reasons shouldContain "contract element 'host.kernelRelease' is absent from the record"
    }

    @Test
    fun `the environment map is compared canonically, whatever its key order`() {
        val a = identity.copy(timeoutEnvironment = mapOf("MINIO_CONN_USER_TIMEOUT" to "10m", "MINIO_IDLE_TIMEOUT" to "30s"))
        val b = identity.copy(timeoutEnvironment = mapOf("MINIO_IDLE_TIMEOUT" to "30s", "MINIO_CONN_USER_TIMEOUT" to "10m"))
        QualificationMatch.compare(a, QualificationFixtures.UPLOAD, record.copy(identity = b)).matches shouldBe true
    }
}
