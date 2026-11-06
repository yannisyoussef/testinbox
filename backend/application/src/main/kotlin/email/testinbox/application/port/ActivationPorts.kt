package email.testinbox.application.port

import java.time.Instant

/** One client session of the database, as `pg_stat_activity` shows it to the application role. */
data class DatabaseSession(
    val applicationName: String,
    val role: String,
)

/** One `storage_node` row (ADR-035 §9, §14). */
data class StorageNodeRow(
    val nodeId: String,
    val capability: String,
    val heartbeatAt: Instant,
    val cleanShutdown: Boolean,
    /** V25: the T1 lock protocol the node declared (`GLOBAL` for rows of artifacts that predate it). */
    val admissionLocking: String = "GLOBAL",
)

/**
 * What the activation barrier reads (ADR-035 §14 Phase 3 (a)): the client
 * sessions of the current database and the node inventory. Reads only, under
 * the application role's own privileges. On PostgreSQL 16 a role without
 * `pg_read_all_stats` sees the `application_name` of its OWN role's sessions,
 * and other roles' sessions only with their identifying columns hidden, so
 * this query yields exactly the application role's sessions — which is the
 * set §14 (a) allowlists (an old binary can only connect as that role). No
 * broader statistics privilege is needed, and none is taken (TI-STORAGE-006
 * §17). Other roles and other databases are Ops's part of the inventory, read
 * by the Ops-run checker with its own privileges.
 */
interface ActivationInventory {
    fun sessions(): List<DatabaseSession>

    /** The database role this process connects as: the "application DB role" whose sessions §14 (a) allowlists. */
    fun applicationRole(): String

    fun nodes(): List<StorageNodeRow>

    /** The database clock the heartbeats are measured on. */
    fun now(): Instant
}

/**
 * ADR-035 amendment D, the window before any cohort plan exists: whether one
 * exists, and how many `workspace` rows there are, counted only up to a cap.
 */
data class WorkspacePopulation(
    val cohortPlanExists: Boolean,
    /** Counted up to the cap the reader was given, never beyond: a bound check, not a population scan. */
    val workspaces: Long,
)

/** Reads [WorkspacePopulation] under the deployable's own privileges (`workspace(id)` and `storage_cohort_plan`, which T1 already reads). */
fun interface WorkspacePopulationReader {
    fun read(cap: Int): WorkspacePopulation
}
