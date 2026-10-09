package email.testinbox.ingestion.guarded

import com.zaxxer.hikari.HikariDataSource
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.DatabaseClock
import email.testinbox.application.port.RateDecision
import email.testinbox.application.port.RateLimiter
import email.testinbox.application.port.ReservedUpload
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.port.UploadOutcome
import email.testinbox.application.storage.CleanupSyncHook
import email.testinbox.application.storage.GuardedStorage
import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.storage.ReleaseStaleReservations
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageNode
import email.testinbox.application.storage.StorageNodeLifecycle
import email.testinbox.application.storage.VerifyAmbiguousUploads
import email.testinbox.application.storage.WriteSlots
import email.testinbox.application.usecase.ReceiveInboundDelivery
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.ingestion.mime.JakartaMimeParser
import email.testinbox.persistence.JdbcInboxRepository
import email.testinbox.persistence.JdbcMessageRepository
import email.testinbox.persistence.JdbcStorageAdmission
import email.testinbox.persistence.JdbcStorageAmbiguity
import email.testinbox.persistence.JdbcStorageReservations
import email.testinbox.persistence.SpringTransactionRunner
import email.testinbox.storage.S3BlobStore
import email.testinbox.storage.S3BlobStoreConfig
import email.testinbox.storage.S3StorageInspection
import email.testinbox.storage.fenced.TcpFaultProxy
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ADR-035 guarded ingest path assembled by hand from the REAL adapters
 * (JDBC, the fenced S3 uploader, the pinned MinIO), around a fresh database
 * and a dedicated bucket. Every knob the §17 scenarios need is explicit:
 * enforcement mode, policy, sync hooks, `T_put`, slots, and a TCP fault proxy
 * in front of storage for uploads.
 *
 * Inspection, cleanup and verification always talk to MinIO directly, so a
 * fault injected into uploads never hides what is really in the bucket.
 */
@Suppress("LongParameterList") // every knob a §17 scenario needs, each with a default
class GuardedIngestHarness(
    val enforcement: StorageEnforcement = StorageEnforcement.OFF,
    val policy: StorageCapacityPolicy = GENEROUS,
    hook: IngestSyncHook = IngestSyncHook.NONE,
    cleanupHook: CleanupSyncHook = CleanupSyncHook.NONE,
    tPut: Duration = Duration.ofSeconds(3),
    viaProxy: Boolean = false,
    maxSlots: Int = 16,
    perWorkspace: Int = 4,
    slotWait: Duration = Duration.ofSeconds(2),
    val nodeId: String = "ingest-test",
    val breaker: StorageBreaker = StorageBreaker(initialBackoff = Duration.ofMillis(300), maxBackoff = Duration.ofSeconds(1)),
    /** Share an existing database and bucket: a "restart" of the same node. */
    shared: GuardedIngestHarness? = null,
    val metrics: RecordingProtocolMetrics = RecordingProtocolMetrics(),
    inspectionOverride: ((StorageInspection) -> StorageInspection)? = null,
    /** Runs after an upload reached storage, before its outcome returns: a throw here loses the outcome. */
    private val afterUpload: (ReservedUpload) -> Unit = {},
    /** Runs before the guarded path persists an ambiguity: a throw here is a failed persist. */
    private val beforeAmbiguityRecord: () -> Unit = {},
    /** TI-STORAGE-006 §22: the activation guard the guarded path consults before the slot and T1. */
    val activation: email.testinbox.application.storage.activation.ActivationGuard =
        email.testinbox.application.storage.activation
            .ActivationGuard(),
    /** TI-STORAGE-006E PR D: the global footprint rules (T1 and the pre-resolution check), or none. */
    val footprint: email.testinbox.application.storage.FootprintPolicy? = null,
) : AutoCloseable {
    val dbName: String = shared?.dbName ?: "guarded_${UUID.randomUUID().toString().replace("-", "")}"
    val bucket: String = shared?.bucket ?: "g-${UUID.randomUUID().toString().take(12)}"

    val dataSource =
        HikariDataSource().apply {
            if (shared == null) createDatabase(dbName)
            jdbcUrl = postgres.jdbcUrl.substringBeforeLast('/') + "/" + dbName
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 12
        }
    val jdbc: JdbcClient = JdbcClient.create(dataSource)
    private val transactionManager = DataSourceTransactionManager(dataSource)
    private val template = TransactionTemplate(transactionManager)
    val transactions = SpringTransactionRunner(transactionManager)

    val s3: S3Client = s3Client()
    val proxy: TcpFaultProxy? = if (viaProxy) TcpFaultProxy(InetSocketAddress(minioHost(), minioPort())) else null

    init {
        if (shared == null) {
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        }
    }

    private val store =
        S3BlobStore(
            S3BlobStoreConfig(
                endpoint = if (proxy != null) "http://${minioHost()}:${proxy.port}" else minio.s3URL,
                accessKey = ACCESS,
                secretKey = SECRET,
                bucket = bucket,
                createBucket = false,
                uploadTimeout = tPut,
            ),
        )

    /** Every payload write goes through this counter: the fenced path, and nothing else. */
    val fencedWrites = Collections.synchronizedList(mutableListOf<String>())
    val blobs: BlobStore =
        object : BlobStore by store {
            override fun putReserved(upload: ReservedUpload): UploadOutcome {
                fencedWrites += upload.key
                return store.putReserved(upload).also { afterUpload(upload) }
            }
        }

    private val directInspection: StorageInspection = S3StorageInspection(s3, bucket)
    val inspection: StorageInspection = inspectionOverride?.invoke(directInspection) ?: directInspection
    val reservations = JdbcStorageReservations(jdbc, template)
    val ambiguity = JdbcStorageAmbiguity(jdbc, template)
    val clock: DatabaseClock = reservations
    val node = StorageNode(nodeId, UUID.randomUUID())
    val lifecycle = StorageNodeLifecycle(ambiguity, node, maxSlots).also { it.start() }
    val slots = WriteSlots(maxSlots, perWorkspace, slotWait) { ambiguity.unresolvedFor(nodeId) }

    val guarded =
        GuardedStorage(
            admission =
                StorageAdmission(
                    JdbcStorageAdmission(jdbc, template, readsFootprint = footprint != null),
                    policy,
                    enforcement,
                    footprint = footprint,
                ),
            reservations = reservations,
            ambiguity =
                object : email.testinbox.application.port.StorageAmbiguity by ambiguity {
                    override fun record(
                        nodeId: String,
                        objectKey: String?,
                        bytes: Long,
                        verifyAfter: Duration,
                    ) {
                        beforeAmbiguityRecord()
                        ambiguity.record(nodeId, objectKey, bytes, verifyAfter)
                    }
                },
            latch = ambiguity,
            blobs = blobs,
            inspection = inspection,
            slots = slots,
            breaker = breaker,
            node = node,
            transactions = transactions,
            clock = clock,
            metrics = metrics,
            hook = hook,
            activation = activation,
            footprint =
                email.testinbox.application.storage.FootprintPrecheck(
                    footprint,
                    enforcement,
                    email.testinbox.persistence.JdbcFootprintGate(jdbc),
                ),
        )

    val messages = JdbcMessageRepository(jdbc)
    val receive =
        ReceiveInboundDelivery(
            JdbcInboxRepository(jdbc),
            messages,
            guarded,
            JakartaMimeParser(),
            ALLOW_ALL,
            Clock.systemUTC(),
        )

    /**
     * Cleanup's clock: the database clock plus a manual advance. `C_drain`
     * (1 s here) is then decided by [tick], never by how many milliseconds
     * happened to pass between two statements. The advance stays far inside
     * `ε_max`, so the clock-offset check is unaffected.
     */
    @Volatile var advance: Duration = Duration.ZERO
    private val cleanupClock = DatabaseClock { reservations.now().plus(advance) }

    fun cleanup(
        settle: Duration = Duration.ofMinutes(17),
        drain: Duration = Duration.ofSeconds(1),
        reservations: email.testinbox.application.port.StorageReservations = this.reservations,
    ) = ReleaseStaleReservations(
        reservations,
        ambiguity,
        ambiguity,
        inspection,
        cleanupClock,
        "api-test",
        metrics,
        cleanupHookFor,
        drain = drain,
        settle = settle,
    )

    /** Moves cleanup's clock past `C_drain`: a witness completed before this now counts. */
    fun tick() {
        advance = advance.plusSeconds(2)
    }

    /**
     * One full release cycle: a pass that expires and witnesses (it can never
     * release, since `C_drain` has not passed), [tick], then a pass that
     * releases. Returns the second pass's report.
     */
    fun releaseCycle(cleanup: ReleaseStaleReservations = cleanup()): ReleaseStaleReservations.Report {
        cleanup.run()
        tick()
        return cleanup.run()
    }

    private val cleanupHookFor: CleanupSyncHook = cleanupHook

    fun verification() = VerifyAmbiguousUploads(ambiguity, ambiguity, reservations, inspection, metrics)

    // --- fixtures -------------------------------------------------------------------------------------

    fun workspace(): WorkspaceId {
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO workspace (id, name, created_at) VALUES (?, 'w', now())").param(id).update()
        jdbc.sql("INSERT INTO project (id, workspace_id, name, created_at) VALUES (?, ?, 'p', now())").params(id, id).update()
        return WorkspaceId(id)
    }

    /** Returns the inbox id and its address. */
    fun inbox(workspace: WorkspaceId): Pair<InboxId, String> {
        val id = UUID.randomUUID()
        val address = "i${id.toString().take(8)}@testinbox.local"
        jdbc
            .sql(
                """
                INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at)
                VALUES (?, ?, ?, ?, 'GENERATED', 'ACTIVE', now(), now() + interval '1 hour')
                """.trimIndent(),
            ).params(id, workspace.value, workspace.value, address)
            .update()
        return InboxId(id) to address
    }

    fun deliver(
        recipients: List<String>,
        raw: ByteArray = mime(),
        providerMessageId: String? = null,
    ) = receive.execute(
        ReceiveInboundDelivery.Command(
            envelopeFrom = "sender@example.com",
            recipients = recipients,
            raw = raw,
            provider = if (providerMessageId != null) "ses" else "smtp",
            providerMessageId = providerMessageId,
        ),
    )

    // --- observations ----------------------------------------------------------------------------------

    fun count(sql: String): Long = jdbc.sql(sql).query(Long::class.java).single()

    fun messageCount() = count("SELECT count(*) FROM message")

    fun reservationStates(): Map<String, Long> = reservations.countsByState()

    fun unresolvedAmbiguity() = ambiguity.unresolvedTotal()

    fun latched() = ambiguity.latched()

    fun refusalCount(): Long = count("SELECT coalesce(sum(refusal_count), 0) FROM inbox_storage")

    fun listedBytes(): Long = inspection.listedPayloadBytes()

    fun committedBytes(): Long =
        count(
            "SELECT (SELECT coalesce(sum(base_bytes), 0) FROM workspace_storage_account) + (SELECT coalesce(sum(bytes), 0) FROM storage_delta)",
        )

    fun reservedBytes(): Long = count("SELECT coalesce(sum(bytes), 0) FROM storage_reservation")

    fun reservationKeys(): List<String> =
        jdbc
            .sql("SELECT unnest(object_keys) FROM storage_reservation")
            .query(String::class.java)
            .list()
            .filterNotNull()

    /** ADR-035 §17 seam: SQL back-dating instead of waiting E, S or T_verify. */
    fun backdate(by: Duration) {
        jdbc
            .sql(
                """
                UPDATE storage_reservation
                   SET created_at = created_at - make_interval(secs => :s),
                       write_deadline_at = write_deadline_at - make_interval(secs => :s),
                       release_not_before = release_not_before - make_interval(secs => :s)
                """.trimIndent(),
            ).param("s", by.seconds.toDouble())
            .update()
        jdbc
            .sql(
                "UPDATE storage_ambiguity SET ambiguous_at = ambiguous_at - make_interval(secs => :s), " +
                    "verify_at = verify_at - make_interval(secs => :s)",
            ).param("s", by.seconds.toDouble())
            .update()
    }

    /** A restart of this node: same node id, same database and bucket, a new generation. The old one is NOT marked clean. */
    fun restart(
        hook: IngestSyncHook = IngestSyncHook.NONE,
        maxSlots: Int = 16,
        slotWait: Duration = Duration.ofSeconds(2),
    ) = GuardedIngestHarness(
        enforcement,
        policy,
        hook,
        tPut = Duration.ofSeconds(3),
        maxSlots = maxSlots,
        slotWait = slotWait,
        nodeId = nodeId,
        shared = this,
    )

    override fun close() {
        proxy?.close()
        store.close()
        s3.close()
        dataSource.close()
    }

    companion object {
        const val ACCESS = "testinbox"
        const val SECRET = "testinbox123"

        val GENEROUS = StorageCapacityPolicy(1L shl 40, InboxShare.of("1"), 1L shl 50, 0)

        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        @JvmStatic
        val minio: MinIOContainer =
            MinIOContainer(
                DockerImageName
                    .parse(
                        "ghcr.io/yannisyoussef/testinbox-mirror/minio@sha256:bbac678936882e4033b6068efd0a19d103b219002d979087f7c5bd330172e00d",
                    ).asCompatibleSubstituteFor("minio/minio"),
            ).withUserName(ACCESS)
                .withPassword(SECRET)
                .also { it.start() }

        fun minioHost(): String = URI.create(minio.s3URL).host

        fun minioPort(): Int = URI.create(minio.s3URL).port

        fun s3Client(): S3Client =
            S3Client
                .builder()
                .endpointOverride(URI.create(minio.s3URL))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET)))
                .forcePathStyle(true)
                .build()

        private fun createDatabase(name: String) {
            java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
                c.createStatement().use { it.execute("CREATE DATABASE $name") }
            }
        }

        val ALLOW_ALL =
            object : RateLimiter {
                override fun tryConsume(
                    workspaceId: WorkspaceId,
                    category: RateCategory,
                    inboxId: InboxId?,
                ) = RateDecision(category, allowed = true, limit = Long.MAX_VALUE, remaining = Long.MAX_VALUE, retryAfter = null)
            }

        /** A two-part message: raw MIME plus one extracted attachment (counted twice, ADR-035 §2). */
        fun mime(
            subject: String = "hello",
            attachment: String = "attachment-bytes-".repeat(20),
        ): ByteArray =
            (
                "From: sender@example.com\r\nTo: someone@testinbox.local\r\nSubject: $subject\r\nMIME-Version: 1.0\r\n" +
                    "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n" +
                    "--b\r\nContent-Type: text/plain\r\n\r\nbody\r\n" +
                    "--b\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"a.bin\"\r\n\r\n" +
                    "$attachment\r\n--b--\r\n"
            ).toByteArray()
    }
}

/** Records every protocol signal, for assertions. */
class RecordingProtocolMetrics : StorageProtocolMetrics {
    val events = Collections.synchronizedList(mutableListOf<String>())
    val admitted = AtomicInteger()

    override fun admission(outcome: email.testinbox.application.port.StorageAdmissionOutcome) {
        events += "admission:$outcome"
        if (outcome == email.testinbox.application.port.StorageAdmissionOutcome.ADMITTED) admitted.incrementAndGet()
    }

    override fun unenforcedLimit(scope: email.testinbox.domain.storage.StorageScope) {
        events += "unenforced:$scope"
    }

    /** When set, thrown from [physicalFailure]: a failure inside the abandon path, after the ambiguity was recorded. */
    @Volatile var physicalFailureError: Error? = null

    override fun physicalFailure(kind: email.testinbox.application.port.PhysicalFailureKind) {
        events += "failure:$kind"
        physicalFailureError?.let { throw it }
    }

    override fun commitFenced() {
        events += "fenced"
    }

    override fun footprintUnavailable(reason: email.testinbox.application.storage.FootprintUnavailability) {
        events += "footprint:$reason"
    }

    override fun released(path: email.testinbox.application.port.ReleasePath) {
        events += "released:$path"
    }

    override fun lateObject() {
        events += "late"
    }
}
