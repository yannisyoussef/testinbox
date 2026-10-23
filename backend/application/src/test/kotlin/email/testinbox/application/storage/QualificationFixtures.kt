package email.testinbox.application.storage

/** A qualification record and a matching declared identity that WOULD enable enforcement: synthetic, test-only. */
object QualificationFixtures {
    const val UPLOAD = StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION

    val identity =
        StorageBackendIdentity(
            imageIndexDigest = "sha256:" + "1".repeat(64),
            platformMemberDigest = "sha256:" + "2".repeat(64),
            release = "RELEASE.2025-04-22T22-12-26Z",
            commitId = "0d7408fc9969caf07de6a8c3a84f9fbb10a6739e",
            mode = "single-node-single-drive",
            driveCount = 1,
            timeoutEnvironment = emptyMap(),
            timeoutCliFlags = emptyList(),
            runtimeConfigHash = "sha256:" + "3".repeat(64),
            kernelRelease = "6.8.0-45-generic",
            filesystemType = "ext4",
            mountOptions = "rw,relatime",
            storageClassInlineDefaults = "release-defaults",
            directPath = true,
            proxy = "none",
        )

    val eligible =
        QualificationRecord(
            recordId = "fixture-eligible",
            qualifiedAt = "2026-10-01T00:00:00Z",
            identity = identity,
            uploadImplementationVersion = UPLOAD,
            platformClass = "staging-host",
            evidenceReference = "fixture",
            scenarios = listOf("base-W", "base-R100ms", "freeze45-W", "freeze45-R2s", "phase1ms-R2s", "slow-W"),
            slowWExecuted = true,
            enablementEligible = true,
            ineligibilityReasons = emptyList(),
        )

    /** The shape of the committed laptop record: a match that must still refuse enablement. */
    val laptopLike =
        eligible.copy(
            recordId = "fixture-laptop",
            identity = identity.copy(runtimeConfigHash = null),
            platformClass = "laptop",
            slowWExecuted = false,
            enablementEligible = false,
            ineligibilityReasons = listOf("platformClass is laptop", "slow-W was not executed"),
        )
}
