package email.testinbox.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.io.File

/**
 * The staging profiles and the compose topology are one contract split across
 * four files, and nothing else in the build reads both halves.
 *
 * Two failures live here and neither produces an error anywhere else:
 *
 *  1. **A placeholder with no default that compose does not supply.** The
 *     no-default form is deliberate — an unset variable must fail startup
 *     rather than silently fall back to the local-development value. But that
 *     only helps if the deployment actually provides it, and the first person
 *     to find out otherwise is whoever enables the environment.
 *  2. **Startup migration being re-enabled in a deployed profile.** ADR-029 §1
 *     names racing executors as the failure it exists to prevent, and it is
 *     one deleted line away. It would not even be visible in a deployment: the
 *     migration job has already applied everything, so the application's own
 *     Flyway run finds nothing pending and looks identical to a correct one.
 *
 * Plain file parsing on purpose — no Spring, no Docker, no YAML dependency —
 * so it runs in the ordinary backend job.
 */
class StagingConfigurationTest {
    private val repoRoot = File(System.getProperty("user.dir")).parentFile.parentFile
    private val stagingProfiles =
        listOf(
            File(repoRoot, "backend/api/src/main/resources/application-staging.yaml"),
            File(repoRoot, "backend/ingestion/src/main/resources/application-staging.yaml"),
        )
    private val composeFiles =
        listOf(
            File(repoRoot, "deploy/staging/compose.yaml"),
            File(repoRoot, "deploy/staging/compose.data.yaml"),
        )

    /** `${VAR}` with no `:default` — the deliberately fail-fast form. */
    private val required = Regex("""\$\{([A-Z0-9_]+)}""")

    @Test
    fun `every fail-fast placeholder in a staging profile is supplied by the compose topology`() {
        val composeText = composeFiles.joinToString("\n") { it.readText() }
        val rehearsal = File(repoRoot, "scripts/staging-rehearsal.sh").readText()
        val example = File(repoRoot, "deploy/staging/.env.example").readText()

        assertAll(
            stagingProfiles.flatMap { profile ->
                required.findAll(profile.readText()).map { it.groupValues[1] }.distinct().map { name ->
                    {
                        assertTrue(
                            composeText.contains("$name:") || composeText.contains("\${$name"),
                            "${profile.name} requires $name, which deploy/staging/compose*.yaml never supplies — " +
                                "a deployment would fail to start on a missing placeholder",
                        )
                        assertTrue(
                            rehearsal.contains(name) || example.contains(name) || composeText.contains("$name:-"),
                            "$name has no value in .env.example or the rehearsal, so nothing ever proves it can be set",
                        )
                    }
                }
            },
        )
    }

    @Test
    fun `no deployed profile migrates at startup (ADR-029)`() {
        assertAll(
            stagingProfiles.map { profile ->
                {
                    val flyway = Regex("""flyway:\s*\n\s*enabled:\s*(\S+)""").find(profile.readText())
                    assertTrue(flyway != null, "${profile.name} must state spring.flyway.enabled explicitly")
                    assertEquals(
                        "false",
                        flyway!!.groupValues[1],
                        "${profile.name} enables startup migration; ADR-029 §2 requires exactly one migration " +
                            "executor, and a racing one is invisible — it finds nothing pending and looks correct",
                    )
                }
            },
        )
    }

    @Test
    fun `the ingress read timeout the compose topology configures clears the wait window it also configures`() {
        // DeploymentSafety enforces this at startup from the values a process
        // is given; this asserts the values the topology actually gives it, so
        // a bad default is caught in the ordinary build rather than by a
        // container that refuses to boot.
        val compose = File(repoRoot, "deploy/staging/compose.yaml").readText()
        val window =
            Regex("""TESTINBOX_WAIT_WINDOW_CAP:-(\d+)s""")
                .find(compose)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        val proxy =
            Regex("""TESTINBOX_PROXY_READ_TIMEOUT_SECONDS:-(\d+)""")
                .find(compose)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        assertTrue(window != null && proxy != null, "compose must default both the wait window and the proxy timeout")
        assertTrue(
            proxy!! >= window!! + 30,
            "proxy read timeout ${proxy}s does not clear the ${window}s wait window by the required 30s margin; " +
                "a full-window long poll would intermittently become a 504",
        )
    }

    @Test
    fun `the data topology bounds how long a hung node can hold an idempotency claim`() {
        // ADR-033 Consequences names this a deployment requirement, and it is
        // the one part of the guarantee the application genuinely cannot
        // enforce for itself: a node that is partitioned mid-claim holds the
        // row until something server-side reaps it, and Postgres disables that
        // reaping by default — which makes the real bound the TCP keepalive
        // interval, hours, during which that one key is unusable.
        //
        // Asserted here because nothing else would notice its removal: no
        // request fails, no test goes red, and the symptom is a single wedged
        // key long after the deploy that dropped the flag.
        val data = File(repoRoot, "deploy/staging/compose.data.yaml").readText()
        val timeout =
            Regex("""idle_in_transaction_session_timeout=(\d+)s""")
                .find(data)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        assertTrue(
            timeout != null,
            "deploy/staging/compose.data.yaml no longer sets idle_in_transaction_session_timeout; " +
                "ADR-033 requires it, and Postgres defaults it to disabled",
        )
        // Long enough to clear any legitimate claim wait (capped at 30s by
        // TestInboxProperties.Idempotency), short enough to be a bound at all.
        assertTrue(
            timeout!! in 30..300,
            "idle_in_transaction_session_timeout is ${timeout}s; below the 30s claim-wait ceiling it would " +
                "reap healthy transactions, and far above it stops bounding a partitioned node usefully",
        )
    }
}
