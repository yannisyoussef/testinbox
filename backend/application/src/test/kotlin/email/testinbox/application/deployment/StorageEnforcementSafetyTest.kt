package email.testinbox.application.deployment

import email.testinbox.application.storage.DeclaredNode
import email.testinbox.application.storage.FilesystemDeclarations
import email.testinbox.application.storage.NodeRole
import email.testinbox.application.storage.QualificationFixtures
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * ADR-035 §17 test 48 and TI-STORAGE-006 §46/§47: `DeploymentSafety` refuses
 * a non-OFF deployment for EACH independent reason separately, and OFF stays
 * startable as the requalification and recovery mode.
 */
class StorageEnforcementSafetyTest {
    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    private val deployed =
        DeploymentSettings(
            environment = "staging",
            mailDomain = "staging.testinbox.email",
            databaseUrl = "jdbc:postgresql://db.staging.internal:5432/testinbox",
            databaseUsername = "testinbox_staging",
            databasePassword = "fixture-not-a-real-db-password--1",
            storageEndpoint = "https://objects.staging.internal",
            storageAccessKey = "fixture-not-a-real-s3-access-key",
            storageSecretKey = "fixture-not-a-real-s3-secret----1",
            publicBaseUrl = "https://api.staging.testinbox.email",
            bootstrapApiKey = null,
            waitWindowCap = Duration.ofSeconds(60),
            proxyReadTimeout = Duration.ofSeconds(120),
            edgeRequestCeiling = null,
            limitsEnabled = true,
            activeProfiles = setOf("staging"),
        )

    /**
     * The staging-shaped filesystem of the containment contract: 48 GiB at 4 KiB
     * blocks, one inode per block, G_F 20 GiB, D_budget 8 GiB, M 256 MiB, R_ops 3 GiB,
     * P_F 64 MiB (at least F(15 MiB, 1) + F(0, 1)), and the monitor role.
     */
    private val filesystem =
        FilesystemDeclarations(
            blockSizeBytes = 4096,
            objectOverheadMaxBytes = 24 * 1024,
            globalFootprintLimitBytes = 20 * gib,
            deletionDebtBudgetBytes = 8 * gib,
            metadataBudgetBytes = 256 * mib,
            operationalReserveBytes = 3 * gib,
            capacityBytes = 48 * gib,
            inodes = 48 * gib / 4096,
            observationMaxAge = Duration.ofMinutes(15),
            probeBudgetBytes = 64 * mib,
            monitorRole = "testinbox_monitor",
        )

    /** Every §18 declaration present and consistent, matched to an eligible (synthetic) record. */
    private val complete =
        StorageDeclarations(
            enforcement = StorageEnforcement.TENANT_LIMITS,
            globalLimitBytes = 40 * gib,
            declaredBucketQuotaBytes = 50 * gib,
            declaredMaxIngestionProcesses = 2,
            inboxShare = "0.25",
            measuredQuotaLagChurnBytes = 64 * mib,
            backendIdentity = QualificationFixtures.identity,
            qualificationRecords = listOf(QualificationFixtures.laptopLike, QualificationFixtures.eligible),
            expectedApiNodes = setOf("api-1"),
            expectedIngestionNodes = setOf("ingest-1", "ingest-2"),
            node = DeclaredNode("ingest-1", NodeRole.INGESTION),
            filesystem = filesystem,
        )

    private fun violations(storage: StorageDeclarations) = DeploymentSafety.validate(deployed.copy(storage = storage))

    private fun settingsOf(storage: StorageDeclarations) = violations(storage).map { it.setting }

    private fun only(
        storage: StorageDeclarations,
        setting: String,
        problem: String,
    ) {
        val found = violations(storage)
        found.map { it.setting } shouldBe listOf(setting)
        found.single().problem shouldContain problem
    }

    @Test
    fun `a complete, consistent, qualified TENANT_LIMITS declaration has no violations`() {
        violations(complete).shouldBeEmpty()
        violations(complete.copy(enforcement = StorageEnforcement.ALL)).shouldBeEmpty()
    }

    // --- the §46 startup negative matrix, one reason at a time ----------------------------------

    @Test
    fun `missing G`() = only(complete.copy(globalLimitBytes = null), "testinbox.storage.global-limit-bytes", "is not declared")

    @Test
    fun `invalid G`() = only(complete.copy(globalLimitBytes = -1), "testinbox.storage.global-limit-bytes", "must be positive")

    // --- the legacy bucket-quota fuse is no longer load-bearing (Amendment 2; owner review c) ----

    @Test
    fun `the original 40 GiB staging quota does not trip the obsolete fuse under an otherwise valid Amendment 2 contract`() {
        // The old rule needed Q ≥ G + max(1 GiB, 10 % of G, H + churn) = 44 GiB for G = 40 GiB. The
        // staging bucket quota is 40 GiB: below it, and it must no longer block either mode.
        for (mode in listOf(StorageEnforcement.TENANT_LIMITS, StorageEnforcement.ALL)) {
            violations(complete.copy(enforcement = mode, declaredBucketQuotaBytes = 40 * gib)).shouldBeEmpty()
            violations(complete.copy(enforcement = mode, declaredBucketQuotaBytes = 40 * gib, measuredQuotaLagChurnBytes = null))
                .shouldBeEmpty()
        }
    }

    @Test
    fun `no bucket quota and no churn figure are required in any non-OFF mode - the quota is an optional secondary defence`() {
        for (mode in listOf(StorageEnforcement.TENANT_LIMITS, StorageEnforcement.ALL)) {
            violations(complete.copy(enforcement = mode, declaredBucketQuotaBytes = null, measuredQuotaLagChurnBytes = null))
                .shouldBeEmpty()
        }
    }

    @Test
    fun `a declared but malformed quota or churn figure is still refused - well-formedness, not the fuse`() {
        only(complete.copy(declaredBucketQuotaBytes = 0), "testinbox.storage.declared-bucket-quota-bytes", "must be positive")
        only(complete.copy(measuredQuotaLagChurnBytes = -1), "testinbox.storage.measured-quota-lag-churn-bytes", "must not be negative")
    }

    @Test
    fun `dropping the fuse drops nothing else - the filesystem containment inequality still refuses ALL and TENANT_LIMITS`() {
        for (mode in listOf(StorageEnforcement.TENANT_LIMITS, StorageEnforcement.ALL)) {
            val over =
                complete.copy(
                    enforcement = mode,
                    declaredBucketQuotaBytes = null,
                    filesystem =
                        filesystem.copy(
                            capacityBytes =
                                20 * gib,
                        ),
                )
            settingsOf(over) shouldContain "testinbox.storage.filesystem.capacity-bytes"
            settingsOf(complete.copy(enforcement = mode, declaredBucketQuotaBytes = 1000 * gib, backendIdentity = null)) shouldContain
                "testinbox.storage.backend-identity" // gate Q's match stays mandatory
        }
    }

    // --- the filesystem-containment declarations (TI-STORAGE-006E) ------------------------------

    @Test
    fun `each missing filesystem declaration refuses a non-OFF deployment, and is named`() {
        val keys =
            mapOf(
                "block-size-bytes" to filesystem.copy(blockSizeBytes = null),
                "object-overhead-max-bytes" to filesystem.copy(objectOverheadMaxBytes = null),
                "global-footprint-limit-bytes" to filesystem.copy(globalFootprintLimitBytes = null),
                "deletion-debt-budget-bytes" to filesystem.copy(deletionDebtBudgetBytes = null),
                "metadata-budget-bytes" to filesystem.copy(metadataBudgetBytes = null),
                "operational-reserve-bytes" to filesystem.copy(operationalReserveBytes = null),
                "capacity-bytes" to filesystem.copy(capacityBytes = null),
                "inodes" to filesystem.copy(inodes = null),
                "observation-max-age" to filesystem.copy(observationMaxAge = null),
                "probe-budget-bytes" to filesystem.copy(probeBudgetBytes = null),
                "monitor-role" to filesystem.copy(monitorRole = null),
            )
        keys.forEach { (key, fs) -> only(complete.copy(filesystem = fs), "testinbox.storage.filesystem.$key", "is not declared") }
    }

    @Test
    fun `the declared budgets must fit the declared filesystem - one byte over refuses`() {
        // 20 + 8 GiB + 64 MiB + 256 MiB + 3 GiB.
        val needed = 31 * gib + 256 * mib + 64 * mib
        violations(complete.copy(filesystem = filesystem.copy(capacityBytes = needed, operationalReserveBytes = 3 * gib)))
            .map { it.setting } shouldNotContain "testinbox.storage.filesystem.capacity-bytes"
        val found = violations(complete.copy(filesystem = filesystem.copy(capacityBytes = needed - 1, inodes = needed / 4096)))
        found.map { it.setting } shouldContain "testinbox.storage.filesystem.capacity-bytes"
        found.single { it.setting == "testinbox.storage.filesystem.capacity-bytes" }.problem shouldContain
            "G_F + D_budget + P_F + M + R_ops = $needed"
    }

    @Test
    fun `ALL is refused in production until the envelope-order mitigation exists, and allowed elsewhere`() {
        // Contract §11.6 (TI-STORAGE-006E PR D): a code gate, not a procedure.
        val all = complete.copy(enforcement = StorageEnforcement.ALL)
        DeploymentSafety
            .validate(deployed.copy(environment = ProductionPolicy.ENVIRONMENT, storage = all))
            .map { it.setting } shouldContain "testinbox.storage.enforcement"
        violations(all).map { it.setting } shouldNotContain "testinbox.storage.enforcement"
    }

    @Test
    fun `the probe reserve must keep rule P admissible - F of the largest object plus a probe, one byte under refuses`() {
        val model =
            email.testinbox.domain.storage
                .FootprintModel(4096, 24 * 1024)
        val floor = model.bound(15 * mib, 1) + model.bound(0, 1)
        violations(complete.copy(filesystem = filesystem.copy(probeBudgetBytes = floor))).shouldBeEmpty()
        only(
            complete.copy(filesystem = filesystem.copy(probeBudgetBytes = floor - 1)),
            "testinbox.storage.filesystem.probe-budget-bytes",
            "P_F must be at least",
        )
    }

    @Test
    fun `the monitor role must be a plain PostgreSQL role name - written_by is compared to it`() {
        listOf("Ops Monitor", "monitor;drop", "\"quoted\"", "").forEach { role ->
            settingsOf(StorageDeclarations().copy(filesystem = FilesystemDeclarations(monitorRole = role))) shouldBe
                listOf("testinbox.storage.filesystem.monitor-role")
        }
        settingsOf(StorageDeclarations().copy(filesystem = FilesystemDeclarations(monitorRole = "testinbox_monitor"))).shouldBeEmpty()
    }

    @Test
    fun `the operational reserve exactly at its floor passes, one byte under refuses`() {
        val floor = 48 * gib / 20
        violations(complete.copy(filesystem = filesystem.copy(operationalReserveBytes = floor))).shouldBeEmpty()
        only(
            complete.copy(filesystem = filesystem.copy(operationalReserveBytes = floor - 1)),
            "testinbox.storage.filesystem.operational-reserve-bytes",
            "max(5 % of C_fs, 2 GiB)",
        )
    }

    @Test
    fun `exactly one inode per block passes, one fewer refuses`() {
        violations(complete.copy(filesystem = filesystem.copy(inodes = 48 * gib / 4096))).shouldBeEmpty()
        only(
            complete.copy(filesystem = filesystem.copy(inodes = 48 * gib / 4096 - 1)),
            "testinbox.storage.filesystem.inodes",
            "one inode per block",
        )
    }

    @Test
    fun `G_F equal to H_F leaves a zero cap and refuses, one byte above passes`() {
        val hF =
            email.testinbox.domain.storage
                .FootprintModel(4096, 24 * 1024)
                .finalizeBudgetBytes(2, 16, 15 * mib)
        only(
            complete.copy(filesystem = filesystem.copy(globalFootprintLimitBytes = hF)),
            "testinbox.storage.filesystem.global-footprint-limit-bytes",
            "G_F − H_F must be positive",
        )
        violations(complete.copy(filesystem = filesystem.copy(globalFootprintLimitBytes = hF + 1))).shouldBeEmpty()
    }

    @Test
    fun `an unsupported block size alone, or an observation age above an hour, is refused in every mode`() {
        val off = StorageDeclarations()
        settingsOf(off.copy(filesystem = FilesystemDeclarations(blockSizeBytes = 512))) shouldBe
            listOf("testinbox.storage.filesystem.block-size-bytes")
        settingsOf(off.copy(filesystem = FilesystemDeclarations(observationMaxAge = Duration.ofDays(1000)))) shouldBe
            listOf("testinbox.storage.filesystem.observation-max-age")
        settingsOf(off.copy(filesystem = FilesystemDeclarations(observationMaxAge = Duration.ofHours(1)))).shouldBeEmpty()
    }

    @Test
    fun `the operational reserve must be at least max(5 percent of C_fs, 2 GiB)`() {
        // 5 % of 48 GiB = 2.4 GiB, above the 2 GiB floor.
        only(
            complete.copy(filesystem = filesystem.copy(operationalReserveBytes = 2 * gib)),
            "testinbox.storage.filesystem.operational-reserve-bytes",
            "max(5 % of C_fs, 2 GiB)",
        )
    }

    @Test
    fun `one inode per block - a filesystem made with the default inode ratio refuses`() {
        // mkfs.ext4's default -i 16384 gives a quarter of the inodes needed.
        only(
            complete.copy(filesystem = filesystem.copy(inodes = 48 * gib / 16384)),
            "testinbox.storage.filesystem.inodes",
            "mkfs -i 4096",
        )
    }

    @Test
    fun `H_F at or above G_F leaves no footprint admission cap`() {
        // 2 × 16 × φ(15 MiB) ≈ 483 MiB.
        only(
            complete.copy(filesystem = filesystem.copy(globalFootprintLimitBytes = 400 * mib)),
            "testinbox.storage.filesystem.global-footprint-limit-bytes",
            "G_F − H_F must be positive",
        )
    }

    @Test
    fun `a malformed filesystem figure is refused in every mode, OFF included`() {
        val off = StorageDeclarations()
        settingsOf(off.copy(filesystem = FilesystemDeclarations(capacityBytes = 0))) shouldBe
            listOf("testinbox.storage.filesystem.capacity-bytes")
        settingsOf(off.copy(filesystem = FilesystemDeclarations(observationMaxAge = Duration.ZERO))) shouldBe
            listOf("testinbox.storage.filesystem.observation-max-age")
        // O_max must cover at least six blocks, one per inode an object can take (contract §3.5).
        settingsOf(off.copy(filesystem = FilesystemDeclarations(blockSizeBytes = 4096, objectOverheadMaxBytes = 8192))) shouldBe
            listOf("testinbox.storage.filesystem.object-overhead-max-bytes")
        // And OFF starts with nothing declared at all.
        violations(off).shouldBeEmpty()
    }

    @Test
    fun `missing process count`() =
        only(
            complete.copy(declaredMaxIngestionProcesses = null),
            "testinbox.storage.declared-max-ingestion-processes",
            "INCLUDING deploy surge",
        )

    @Test
    fun `process count 0`() =
        only(complete.copy(declaredMaxIngestionProcesses = 0), "testinbox.storage.declared-max-ingestion-processes", "must be at least 1")

    @Test
    fun `H overflow is a configuration failure, never a wrapped value`() =
        only(
            complete.copy(declaredMaxIngestionProcesses = 2, maxObjectBytes = Long.MAX_VALUE / 2),
            "testinbox.storage.declared-max-ingestion-processes",
            "overflow",
        )

    @Test
    fun `H greater than or equal to G`() {
        // 2 × 16 × 15 MiB = 480 MiB; a G of 480 MiB leaves no admission cap.
        only(
            complete.copy(globalLimitBytes = 480 * mib, declaredBucketQuotaBytes = 10 * gib),
            "testinbox.storage.global-limit-bytes",
            "G − H must be positive",
        )
    }

    @Test
    fun `share at or below zero`() {
        only(complete.copy(inboxShare = "0"), "testinbox.storage.inbox-share", "(0, 1]")
        only(complete.copy(inboxShare = "-0.1"), "testinbox.storage.inbox-share", "(0, 1]")
    }

    @Test
    fun `share above one`() = only(complete.copy(inboxShare = "1.5"), "testinbox.storage.inbox-share", "(0, 1]")

    @Test
    fun `missing share`() = only(complete.copy(inboxShare = null), "testinbox.storage.inbox-share", "is not declared")

    @Test
    fun `missing backend identity`() = only(complete.copy(backendIdentity = null), "testinbox.storage.backend-identity", "is not declared")

    @Test
    fun `an artifact that ships no qualification record cannot enforce`() =
        only(complete.copy(qualificationRecords = emptyList()), "testinbox.storage.backend-identity", "ships no qualification record")

    @Test
    fun `unknown qualification - no record matches at all`() =
        only(
            complete.copy(
                backendIdentity = QualificationFixtures.identity.copy(imageIndexDigest = "sha256:" + "a".repeat(64), mode = "distributed"),
            ),
            "testinbox.storage.backend-identity",
            "matches no shipped qualification record",
        )

    @Test
    fun `each contract element mismatch refuses startup and is named (ADR-035 §9a)`() {
        val id = QualificationFixtures.identity
        mapOf(
            "minio.imageIndexDigest" to id.copy(imageIndexDigest = "sha256:" + "b".repeat(64)),
            "minio.mode" to id.copy(mode = "distributed-erasure"),
            "minio.driveCount" to id.copy(driveCount = 4),
            "minio.timeoutEnvironment" to id.copy(timeoutEnvironment = mapOf("MINIO_IDLE_TIMEOUT" to "0")),
            "minio.timeoutCliFlags" to id.copy(timeoutCliFlags = listOf("--idle-timeout=0")),
            "minio.runtimeConfig.hash" to id.copy(runtimeConfigHash = "sha256:" + "c".repeat(64)),
            "host.kernelRelease" to id.copy(kernelRelease = "6.12.1"),
            "host.filesystemType" to id.copy(filesystemType = "xfs"),
            "host.mountOptions" to id.copy(mountOptions = "rw,noatime"),
            "network.directPath" to id.copy(directPath = false),
            "network.proxy" to id.copy(proxy = "nginx"),
        ).forEach { (element, declared) ->
            val found = violations(complete.copy(backendIdentity = declared))
            found.map { it.setting } shouldBe listOf("testinbox.storage.backend-identity")
            found.single().problem shouldContain element
        }
    }

    @Test
    fun `upload implementation version mismatch`() =
        only(
            complete.copy(uploadImplementationVersion = "adr035-presigned-put-v2"),
            "testinbox.storage.backend-identity.upload-implementation-version",
            "differing in uploadImplementationVersion",
        )

    @Test
    fun `a matching record that is not enablement-eligible refuses, naming why`() {
        val found =
            violations(
                complete.copy(
                    backendIdentity = QualificationFixtures.identity.copy(runtimeConfigHash = null),
                    qualificationRecords = listOf(QualificationFixtures.laptopLike),
                ),
            )
        // The laptop-like record has no hash either, so the hash element differs on BOTH sides and never matches.
        found.single().problem shouldContain "minio.runtimeConfig.hash"
        val eligibleShapeButIneligible =
            QualificationFixtures.eligible.copy(
                slowWExecuted = false,
                enablementEligible = false,
                ineligibilityReasons = listOf("slow-W was not executed"),
            )
        val viaFlag = violations(complete.copy(qualificationRecords = listOf(eligibleShapeButIneligible)))
        viaFlag.single().setting shouldBe "testinbox.storage.backend-identity"
        viaFlag.single().problem shouldContain "not enablement-eligible"
        viaFlag.single().problem shouldContain "slow-W"
    }

    @Test
    fun `a non-OFF value outside a declared deployed environment is refused for that reason`() {
        val found = DeploymentSafety.validate(deployed.copy(environment = "", storage = complete))
        found.map { it.setting } shouldContain "testinbox.deployment.environment"
        found.first { it.setting == "testinbox.deployment.environment" }.problem shouldContain "TENANT_LIMITS"
    }

    @Test
    fun `every missing declaration is reported at once, not one restart at a time`() {
        val found = settingsOf(StorageDeclarations(enforcement = StorageEnforcement.ALL))
        found shouldContain "testinbox.storage.global-limit-bytes"
        found shouldContain "testinbox.storage.declared-max-ingestion-processes"
        found shouldContain "testinbox.storage.inbox-share"
        // The legacy fuse inputs are optional now (Amendment 2): never named as missing.
        found shouldNotContain "testinbox.storage.declared-bucket-quota-bytes"
        found shouldNotContain "testinbox.storage.measured-quota-lag-churn-bytes"
        found shouldContain "testinbox.storage.filesystem.capacity-bytes"
        found shouldContain "testinbox.storage.filesystem.inodes"
        found shouldContain "testinbox.storage.backend-identity"
        found shouldContain "testinbox.storage.activation.expected-api-nodes"
        found shouldContain "testinbox.storage.activation.expected-ingestion-nodes"
    }

    @Test
    fun `an undeclared node inventory refuses non-OFF - nothing is inferred from heartbeats`() {
        only(complete.copy(expectedApiNodes = emptySet()), "testinbox.storage.activation.expected-api-nodes", "is not declared")
        only(complete.copy(expectedIngestionNodes = emptySet()), "testinbox.storage.activation.expected-ingestion-nodes", "is not declared")
    }

    @Test
    fun `a node whose own id is not in the declared set refuses - it would start as an undeclared node`() {
        only(
            complete.copy(node = DeclaredNode("ingest-9", NodeRole.INGESTION)),
            "testinbox.storage.activation.expected-ingestion-nodes",
            "'ingest-9'",
        )
        only(complete.copy(node = DeclaredNode("api-9", NodeRole.API)), "testinbox.storage.activation.expected-api-nodes", "'api-9'")
        violations(complete.copy(node = DeclaredNode("api-1", NodeRole.API))).shouldBeEmpty()
    }

    @Test
    fun `a node id with a colon is refused in every mode - the session name could not carry it`() {
        settingsOf(StorageDeclarations(node = DeclaredNode("api:1", NodeRole.API))) shouldBe listOf("testinbox.storage.node-id")
        settingsOf(StorageDeclarations(node = DeclaredNode(" ", NodeRole.API))) shouldBe listOf("testinbox.storage.node-id")
        violations(StorageDeclarations(node = DeclaredNode("api-1", NodeRole.API))).shouldBeEmpty()
    }

    @Test
    fun `more declared ingestion nodes than declared processes refuses - H would under-count them`() {
        only(
            complete.copy(declaredMaxIngestionProcesses = 1),
            "testinbox.storage.declared-max-ingestion-processes",
            "2 ingestion nodes are declared (ingest-1,ingest-2)",
        )
        // Surge above the declared set is Ops's word: three processes for two nodes is accepted.
        violations(complete.copy(declaredMaxIngestionProcesses = 3)).shouldBeEmpty()
    }

    // --- the §47 OFF compatibility matrix ----------------------------------------------------

    @Test
    fun `OFF starts with nothing declared - Phase 2 as it ships`() {
        violations(StorageDeclarations.OFF).shouldBeEmpty()
    }

    @Test
    fun `OFF starts with an absent, stale or mismatching qualification and a changed backend (the MinIO change rule)`() {
        val changed = QualificationFixtures.identity.copy(imageIndexDigest = "sha256:" + "d".repeat(64))
        violations(complete.copy(enforcement = StorageEnforcement.OFF, backendIdentity = changed)).shouldBeEmpty()
        violations(complete.copy(enforcement = StorageEnforcement.OFF, qualificationRecords = emptyList())).shouldBeEmpty()
        violations(complete.copy(enforcement = StorageEnforcement.OFF, backendIdentity = null)).shouldBeEmpty()
    }

    @Test
    fun `OFF starts without the global benchmark and without Q, and even with a Q below the fuse`() {
        violations(complete.copy(enforcement = StorageEnforcement.OFF, declaredBucketQuotaBytes = null)).shouldBeEmpty()
        violations(complete.copy(enforcement = StorageEnforcement.OFF, declaredBucketQuotaBytes = 1)).shouldBeEmpty()
    }

    @Test
    fun `OFF still refuses a declared value that is malformed - that is a mistake in any mode`() {
        settingsOf(StorageDeclarations(globalLimitBytes = -5)) shouldBe listOf("testinbox.storage.global-limit-bytes")
        settingsOf(StorageDeclarations(inboxShare = "2")) shouldBe listOf("testinbox.storage.inbox-share")
        settingsOf(StorageDeclarations(declaredMaxIngestionProcesses = 0)) shouldBe
            listOf("testinbox.storage.declared-max-ingestion-processes")
        settingsOf(StorageDeclarations(globalLimitBytes = 40 * gib)) shouldNotContain "testinbox.storage.global-limit-bytes"
        // H ≥ G is named in OFF too, instead of surfacing as an exception at wiring.
        settingsOf(StorageDeclarations(globalLimitBytes = 100 * mib)) shouldBe listOf("testinbox.storage.global-limit-bytes")
    }

    @Test
    fun `the rendered refusal names settings and problems, never a credential`() {
        val text = DeploymentSafety.describe(violations(StorageDeclarations(enforcement = StorageEnforcement.TENANT_LIMITS)))
        text shouldContain "Refusing to start"
        text shouldContain "global-limit-bytes"
        text.shouldNotContainSecret()
    }

    private fun String.shouldNotContainSecret() {
        this.contains(deployed.databasePassword) shouldBe false
        this.contains(deployed.storageSecretKey) shouldBe false
    }
}
