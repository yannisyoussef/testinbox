package email.testinbox.application.storage

/**
 * ADR-035 §17 seams: points in the guarded ingest path where a test can pause,
 * interleave or simulate a crash deterministically. Production passes
 * [NONE]. There is no endpoint, property or flag that reaches these.
 */
interface IngestSyncHook {
    fun afterSlot() {}

    fun afterAdmission() {}

    fun beforeUpload(key: String) {}

    fun afterUploads() {}

    fun beforeCommit() {}

    fun inCommitAfterReservationLock() {}

    companion object {
        val NONE: IngestSyncHook = object : IngestSyncHook {}
    }
}

/** ADR-035 §17 cleanup seams. Production passes [NONE]. */
interface CleanupSyncHook {
    fun afterClaim() {}

    fun afterDeleteBeforeList(key: String) {}

    companion object {
        val NONE: CleanupSyncHook = object : CleanupSyncHook {}
    }
}
