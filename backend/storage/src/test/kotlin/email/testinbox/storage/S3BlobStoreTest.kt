package email.testinbox.storage

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MinIOContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3BlobStoreTest {
    private val minio =
        MinIOContainer("minio/minio:latest")
            .withUserName("testinbox")
            .withPassword("testinbox123")
            .also { it.start() }

    private val store =
        S3BlobStore(
            S3BlobStoreConfig(
                endpoint = minio.s3URL,
                accessKey = "testinbox",
                secretKey = "testinbox123",
                bucket = "testinbox-mime",
            ),
        )

    @AfterAll
    fun tearDown() {
        store.close()
        minio.stop()
    }

    @Test
    fun `put-get round trip preserves bytes`() {
        store.put("ws1/in1/m1/raw.eml", byteArrayOf(1, 2, 3), "message/rfc822")
        store.get("ws1/in1/m1/raw.eml")?.toList() shouldBe listOf<Byte>(1, 2, 3)
        store.get("ws1/in1/m1/missing").shouldBeNull()
    }

    @Test
    fun `deleting one inbox prefix never touches another inbox's blobs (data-ownership)`() {
        store.put("ws2/inboxA/m1/raw.eml", byteArrayOf(1), "x")
        store.put("ws2/inboxA/m1/attachments/a1", byteArrayOf(2), "x")
        store.put("ws2/inboxB/m2/raw.eml", byteArrayOf(3), "x")
        store.deletePrefix("ws2/inboxA/")
        store.get("ws2/inboxA/m1/raw.eml").shouldBeNull()
        store.get("ws2/inboxA/m1/attachments/a1").shouldBeNull()
        store.get("ws2/inboxB/m2/raw.eml")?.toList() shouldBe listOf<Byte>(3)
        // Idempotent re-delete.
        store.deletePrefix("ws2/inboxA/")
    }

    @Test
    fun `listKeysOlderThan supports the orphan sweep`() {
        store.put("ws3/in/m/raw.eml", byteArrayOf(1), "x")
        store.listKeysOlderThan("ws3/", Instant.now().plusSeconds(60))
            .shouldContainExactly("ws3/in/m/raw.eml")
        store.listKeysOlderThan("ws3/", Instant.now().minusSeconds(3600)) shouldBe emptyList()
    }
}
