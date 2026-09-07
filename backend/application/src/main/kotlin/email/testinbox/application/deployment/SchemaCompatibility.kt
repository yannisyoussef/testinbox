package email.testinbox.application.deployment

/**
 * A Flyway version, ordered the way Flyway orders them: element-wise on the
 * numeric parts, so `10` is above `9` and `1.10` above `1.9`. String ordering
 * would get both wrong, and would do so silently — as a readiness answer of
 * "compatible" on a schema that is actually behind.
 */
data class SchemaVersion(
    val raw: String,
) : Comparable<SchemaVersion> {
    private val parts: List<Long> =
        raw.split('.', '_').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toLongOrNull() }

    override fun compareTo(other: SchemaVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0L }.compareTo(other.parts.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        return 0
    }

    override fun toString(): String = raw
}

/** What `flyway_schema_history` currently says. */
data class AppliedSchema(
    val successfulVersions: List<String>,
    val failedVersions: List<String>,
    /** False when the history table does not exist — migrations have never run here. */
    val historyPresent: Boolean,
) {
    companion object {
        val ABSENT = AppliedSchema(emptyList(), emptyList(), historyPresent = false)
    }
}

/** Outbound port: reads the migration history from the system of record (ADR-024). */
fun interface SchemaHistory {
    fun read(): AppliedSchema
}

/**
 * The rendered answer, not the raw materials.
 *
 * Versions come back as display strings on purpose: an adapter that needs
 * `SchemaVersion` is an adapter that is about to re-derive the comparison
 * itself, and `DependencyRuleTest` fails the build for exactly that.
 */
data class SchemaStatus(
    val bundled: String?,
    val applied: String?,
    val compatible: Boolean,
    val detail: String,
)

/**
 * ADR-029 §4: a deployed process does not migrate, so before it serves traffic
 * it must confirm the schema its artifact was built against is actually
 * present.
 *
 * The asymmetry is deliberate and load-bearing. Applied *behind* bundled means
 * this artifact would run queries against columns that do not exist — refuse.
 * Applied *ahead* of bundled is the normal state of a rolled-back artifact
 * running against an already-migrated database, and treating it as an outage
 * would make artifact rollback impossible after any migration at all, which is
 * the entire rollback story of ADR-028.
 */
class SchemaCompatibility(
    private val history: SchemaHistory,
    private val bundled: SchemaVersion?,
) {
    fun status(): SchemaStatus {
        val applied = history.read()
        val highest = applied.successfulVersions.map(::SchemaVersion).maxOrNull()
        return when {
            applied.failedVersions.isNotEmpty() -> {
                status(highest, false, "failed migrations in history: ${applied.failedVersions.joinToString(",")}")
            }

            bundled == null -> {
                status(highest, true, "this artifact bundles no migrations")
            }

            !applied.historyPresent -> {
                status(null, false, "flyway_schema_history is absent — migrations have not run")
            }

            highest == null -> {
                status(null, false, "no successful migration recorded; this artifact requires $bundled")
            }

            highest < bundled -> {
                status(highest, false, "schema is at $highest, this artifact requires $bundled")
            }

            highest > bundled -> {
                status(highest, true, "schema is at $highest, ahead of this artifact's $bundled (rolled-back artifact)")
            }

            else -> {
                status(highest, true, "schema is at $highest")
            }
        }
    }

    private fun status(
        applied: SchemaVersion?,
        compatible: Boolean,
        detail: String,
    ) = SchemaStatus(bundled = bundled?.raw, applied = applied?.raw, compatible = compatible, detail = detail)
}
