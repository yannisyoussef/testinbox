package email.testinbox.migrator

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

/**
 * The single logical schema-migration executor (ADR-029).
 *
 * A one-shot deployable: it applies every pending Flyway migration bundled in
 * its own artifact, reports the resulting schema version, and exits. The
 * deployed API and ingestion processes never migrate — they refuse readiness
 * until the schema they were built against is present.
 *
 * It is built from the same commit as the API/ingestion images and carries the
 * identical `db/migration` resources (contributed by the `persistence` module
 * at runtime), so "the migrations that ran" and "the migrations the deployed
 * artifacts expect" cannot drift.
 *
 * Exit codes: `0` when the schema is at the bundled version, non-zero when
 * Flyway fails — which aborts the deployment before any service is started or
 * updated.
 */
@SpringBootApplication
class MigratorApplication

/**
 * Reports the outcome: `0` only when every versioned migration this artifact
 * bundles is applied and none failed.
 *
 * Deliberately logs versions and states only — never the JDBC URL,
 * credentials or connection properties (ADR-029 §7), because deployment logs
 * are far more widely readable than the secret store.
 */
internal fun reportOutcome(flyway: Flyway): Int {
    val log = LoggerFactory.getLogger(MigratorApplication::class.java)
    val info = flyway.info()
    val versioned = info.all().filter { it.version != null }
    val failed = versioned.filter { it.state.isFailed }
    // `pending()` honours `target`, so a run configured to stop short reports
    // nothing pending while leaving the schema behind the artifact. Asking
    // which versioned migrations are *not applied* catches that, and IGNORED
    // and ABOVE_TARGET with it.
    val notApplied = versioned.filterNot { it.state.isApplied }

    log.info(
        "schema migration complete: version={} applied={} notApplied={}",
        info.current()?.version?.version ?: "<empty>",
        versioned.count { it.state.isApplied },
        notApplied.size,
    )
    if (failed.isNotEmpty()) {
        log.error("failed migrations present in flyway_schema_history: {}", failed.map { it.version?.version })
        return EXIT_FAILED_HISTORY
    }
    if (notApplied.isNotEmpty()) {
        log.error(
            "migrations bundled in this artifact were not applied: {}",
            notApplied.map { "${it.version?.version}=${it.state}" },
        )
        return EXIT_STILL_PENDING
    }
    return 0
}

internal const val EXIT_FAILED_HISTORY = 3
internal const val EXIT_STILL_PENDING = 4

fun main(args: Array<String>) {
    // A Flyway failure fails context startup, and Spring Boot exits non-zero;
    // the checks below catch the states Flyway itself treats as success.
    val context = runApplication<MigratorApplication>(*args)
    val code =
        context.use {
            reportOutcome(it.getBean(Flyway::class.java))
        }
    exitProcess(code)
}
