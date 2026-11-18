package email.testinbox.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.time.Instant

/**
 * The application's S3 permission contract: every call the API and the ingestion
 * gateway make, run as a SCOPED user whose MinIO policy grants exactly
 * [REQUIRED_POLICY] and nothing else. Every other storage test runs as the root
 * user, which is why a missing permission went unseen until production: the
 * orphan sweep, reservation release, the ambiguity verifier and rule (P)'s
 * `resolveStale` all call `ListMultipartUploads`, which the deployed
 * `testinbox-bucket` policy did not grant (infinity-core #12 2154-2156: 219 of
 * 219 sweeps failed with 403 since Deploy 1).
 *
 * [REQUIRED_POLICY] is the policy Ops must provision for the runtime credential.
 * A change here is a change to that contract and must reach Ops' bootstrap
 * (infinity-core `infra/testinbox/docker-compose*.yml`) in the same step.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3PermissionContractTest {
    private val minio =
        MinIOContainer(
            // The same private mirror, pinned by index digest, as S3BlobStoreTest
            // (docs/dev/third-party-mirrors.md). Its image also carries `mc`, used
            // here only for the provisioning step Ops performs in production.
            DockerImageName
                .parse(
                    "ghcr.io/yannisyoussef/testinbox-mirror/minio@sha256:bbac678936882e4033b6068efd0a19d103b219002d979087f7c5bd330172e00d",
                ).asCompatibleSubstituteFor("minio/minio"),
        ).withUserName(ROOT)
            .withPassword(ROOT_SECRET)
            .also { it.start() }

    private val stores = mutableListOf<S3BlobStore>()

    @AfterAll
    fun tearDown() {
        stores.forEach(S3BlobStore::close)
        minio.stop()
    }

    @Test
    fun `every storage call succeeds as a user holding exactly the required policy`() {
        val bucket = provision("contract", REQUIRED_POLICY)
        val store = scoped(bucket)
        val inspection = store.inspection()

        // Payload writes, reads, listings and deletes (ingest, raw reads, retention, the sweep's listing).
        store.fencedPut("ws/in/m1/raw.eml", byteArrayOf(1, 2, 3))
        store.get("ws/in/m1/raw.eml")?.toList() shouldBe listOf<Byte>(1, 2, 3)
        store.listKeysOlderThan("", Instant.now().plusSeconds(60)) shouldContainExactly listOf("ws/in/m1/raw.eml")
        inspection.objectSize("ws/in/m1/raw.eml") shouldBe 3L
        inspection.objectExists("ws/in/m1/raw.eml") shouldBe true
        inspection.listedPayloadBytes() shouldBe 3L
        inspection.serverTime()

        // The witness probe (zero-byte PUT under _probe/, listed, deleted).
        inspection.witness("${S3StorageInspection.PROBE_PREFIX}contract/${java.util.UUID.randomUUID()}") shouldBe true

        // Multipart: listed by the sweep, the release proof and the verifier; aborted by the sweep.
        // TestInbox never starts one, so the root client stands in for a stray client here.
        root().createMultipartUpload(
            CreateMultipartUploadRequest
                .builder()
                .bucket(bucket)
                .key("ws/in/m2/raw.eml")
                .build(),
        )
        inspection.incompleteUploadExists("ws/in/m2/raw.eml") shouldBe true
        val incomplete = inspection.incompleteUploads()
        incomplete.map { it.key } shouldContainExactly listOf("ws/in/m2/raw.eml")
        inspection.abortIncompleteUpload(incomplete.single())
        inspection.incompleteUploads().shouldBeEmpty()

        inspection.deleteObject("ws/in/m1/raw.eml")
        inspection.objectExists("ws/in/m1/raw.eml") shouldBe false
        store.fencedPut("ws/in/m3/raw.eml", byteArrayOf(4))
        store.deletePrefix("ws/in/")
        store.listKeysOlderThan("", Instant.now().plusSeconds(60)).shouldBeEmpty()
    }

    @Test
    fun `the policy deployed before the fix denies ListMultipartUploads, the call every full sweep needs`() {
        // Regression evidence for #12 2154: the old policy lacks s3:ListBucketMultipartUploads.
        val bucket = provision("previous", PREVIOUS_POLICY)
        val inspection = scoped(bucket).inspection()
        shouldThrow<S3Exception> { inspection.incompleteUploads() }.statusCode() shouldBe 403
        shouldThrow<S3Exception> { inspection.incompleteUploadExists("ws/in/m/raw.eml") }.statusCode() shouldBe 403
    }

    /** Ops' provisioning step: a bucket, a policy and a user holding only that policy (read back). */
    private fun provision(
        name: String,
        policy: String,
    ): String {
        val bucket = "tb-$name-${System.nanoTime()}"
        val user = "app-$name"
        mc("mc alias set local http://127.0.0.1:9000 $ROOT $ROOT_SECRET")
        mc("mc mb local/$bucket")
        mc("cat > /tmp/$name.json <<'POLICY'\n${policy.replace("\$BUCKET", bucket)}\nPOLICY")
        mc("mc admin policy create local pol-$name /tmp/$name.json")
        mc("mc admin user add local $user $APP_SECRET")
        mc("mc admin policy attach local pol-$name --user $user")
        mc("mc admin user info local $user --json").let { check(it.contains("\"policyName\":\"pol-$name\"")) { it } }
        return bucket
    }

    private fun mc(command: String): String {
        val result = minio.execInContainer("sh", "-c", command)
        check(result.exitCode == 0) { "provisioning step failed: $command\n${result.stdout}\n${result.stderr}" }
        return result.stdout
    }

    private fun scoped(bucket: String): S3BlobStore =
        S3BlobStore(
            S3BlobStoreConfig(
                endpoint = minio.s3URL,
                accessKey = "app-${bucket.substringAfter("tb-").substringBefore('-')}",
                secretKey = APP_SECRET,
                bucket = bucket,
                // Production runtime credentials hold no CreateBucket (ADR-034).
                createBucket = false,
            ),
        ).also(stores::add)

    private fun root(): S3Client =
        S3Client
            .builder()
            .endpointOverride(URI.create(minio.s3URL))
            .region(Region.of("us-east-1"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ROOT, ROOT_SECRET)))
            .forcePathStyle(true)
            .build()

    /** The port has no unfenced write: the ingest path's presigned, fenced PUT (ADR-035 §5). */
    private fun S3BlobStore.fencedPut(
        key: String,
        bytes: ByteArray,
    ) {
        putReserved(
            email.testinbox.application.port
                .ReservedUpload(key, bytes, Instant.now(), java.time.Duration.ofSeconds(120)),
        ) shouldBe email.testinbox.application.port.UploadOutcome.Stored
    }

    companion object {
        private const val ROOT = "rootadmin"
        private const val ROOT_SECRET = "rootadmin-secret-1"
        private const val APP_SECRET = "app-secret-123456"

        /** The runtime credential's policy: the application's S3 permission contract. */
        const val REQUIRED_POLICY =
            """{"Version":"2012-10-17","Statement":[
  {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload"],
   "Resource":["arn:aws:s3:::${'$'}BUCKET/*"]},
  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation","s3:ListBucketMultipartUploads"],
   "Resource":["arn:aws:s3:::${'$'}BUCKET"]}]}"""

        /** infinity-core `testinbox-bucket` as deployed up to the fix (#12 2154). */
        private const val PREVIOUS_POLICY =
            """{"Version":"2012-10-17","Statement":[
  {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],
   "Resource":["arn:aws:s3:::${'$'}BUCKET/*"]},
  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation"],
   "Resource":["arn:aws:s3:::${'$'}BUCKET"]}]}"""
    }
}
