package email.testinbox.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import java.net.URI
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3BlobStoreTest {
    private val minio =
        MinIOContainer(
            // minio/minio is no longer anonymously pullable from Docker Hub;
            // quay.io is MinIO's public mirror. Pinned to the release staging
            // runs (deploy/staging/compose.data.yaml) so tests prove that version.
            DockerImageName
                .parse("quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z")
                .asCompatibleSubstituteFor("minio/minio"),
        ).withUserName("testinbox")
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
        store
            .listKeysOlderThan("ws3/", Instant.now().plusSeconds(60))
            .shouldContainExactly("ws3/in/m/raw.eml")
        store.listKeysOlderThan("ws3/", Instant.now().minusSeconds(3600)) shouldBe emptyList()
    }

    @Test
    fun `two deployables starting at once both come up, whichever creates the bucket`() {
        // The API and ingestion containers start simultaneously and both run
        // `ensureBucket`. Before this was tolerated, the loser of that race got
        // a 409 and refused to start — an outage caused entirely by the
        // convenience flag, reproducible only under concurrent startup.
        val bucket = "race-${System.nanoTime()}"
        val racers = 6
        val gate = java.util.concurrent.CountDownLatch(1)
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(racers)
        try {
            val futures =
                (1..racers).map {
                    pool.submit<S3BlobStore> {
                        gate.await()
                        S3BlobStore(
                            S3BlobStoreConfig(
                                endpoint = minio.s3URL,
                                accessKey = "testinbox",
                                secretKey = "testinbox123",
                                bucket = bucket,
                            ),
                        )
                    }
                }
            gate.countDown()
            // Every one of them must construct: none may fail because another
            // won.
            val stores = futures.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            stores.forEach { store ->
                store.put("probe/raw.eml", byteArrayOf(7), "message/rfc822")
                store.get("probe/raw.eml")?.toList() shouldBe listOf<Byte>(7)
                store.close()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** An administrative client, standing in for the Ops provisioning step. */
    private fun admin(): S3Client =
        S3Client
            .builder()
            .endpointOverride(URI.create(minio.s3URL))
            .region(Region.of("us-east-1"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("testinbox", "testinbox123")))
            .forcePathStyle(true)
            .build()

    @Test
    fun `with bucket creation off, an absent bucket stays absent and every operation reports it (ADR-034)`() {
        // Production runtime credentials hold no CreateBucket. The adapter must
        // not try — a try under scoped credentials would fail anyway, but a try
        // under over-broad ones would silently paper over a provisioning gap.
        val bucket = "preprovisioned-${System.nanoTime()}"
        val store =
            S3BlobStore(
                S3BlobStoreConfig(
                    endpoint = minio.s3URL,
                    accessKey = "testinbox",
                    secretKey = "testinbox123",
                    bucket = bucket,
                    createBucket = false,
                ),
            )
        try {
            // The readiness probe's call, and the raw-first write's call: both must fail loudly.
            shouldThrow<NoSuchBucketException> { store.listKeysOlderThan("_probe/readiness/", Instant.EPOCH) }
            shouldThrow<NoSuchBucketException> { store.put("ws/in/m/raw.eml", byteArrayOf(1), "message/rfc822") }
            admin().use { s3 ->
                shouldThrow<NoSuchBucketException> { s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build()) }
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun `with bucket creation off, a pre-provisioned bucket works without any creation privilege`() {
        val bucket = "preprovisioned-${System.nanoTime()}"
        admin().use { s3 -> s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build()) }
        val store =
            S3BlobStore(
                S3BlobStoreConfig(
                    endpoint = minio.s3URL,
                    accessKey = "testinbox",
                    secretKey = "testinbox123",
                    bucket = bucket,
                    createBucket = false,
                ),
            )
        try {
            store.put("ws/in/m/raw.eml", byteArrayOf(9), "message/rfc822")
            store.get("ws/in/m/raw.eml")?.toList() shouldBe listOf<Byte>(9)
            store.listKeysOlderThan("_probe/readiness/", Instant.EPOCH) shouldBe emptyList()
        } finally {
            store.close()
        }
    }
}
