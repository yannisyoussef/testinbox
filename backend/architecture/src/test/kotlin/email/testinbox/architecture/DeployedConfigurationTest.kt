package email.testinbox.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.io.File

/**
 * The deployed profiles and the compose topology are one contract split across
 * several files, and nothing else in the build reads every half.
 *
 * Failures that live here and nowhere else:
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
 *  3. **The staging/production split drifting** (ADR-034). Both environments
 *     are groups over ONE deployed layer; production is that layer plus a
 *     short overrides document. If someone re-creates a full staging profile,
 *     or puts a shared setting in the production overrides, the two start
 *     diverging by edit distance — which is the failure the layering exists
 *     to make impossible.
 *
 * Plain file parsing on purpose — no Spring, no Docker, no YAML dependency —
 * so it runs in the ordinary backend job.
 */
class DeployedConfigurationTest {
    private val repoRoot = File(System.getProperty("user.dir")).parentFile.parentFile
    private val baseProfiles =
        listOf(
            File(repoRoot, "backend/api/src/main/resources/application.yaml"),
            File(repoRoot, "backend/ingestion/src/main/resources/application.yaml"),
        )
    private val deployedProfiles =
        listOf(
            File(repoRoot, "backend/api/src/main/resources/application-deployed.yaml"),
            File(repoRoot, "backend/ingestion/src/main/resources/application-deployed.yaml"),
        )

    /**
     * The production overrides are the SECOND document of each deployed file,
     * activated on the `production` profile. A separate profile file would be
     * shadowed by the deployed layer under Spring's group ordering — see
     * `DeployedProfileLayeringTest` for the proof through Spring itself.
     */
    private fun productionDocument(deployed: File): String {
        val documents = deployed.readText().split(Regex("(?m)^---\\s*$"))
        assertEquals(
            2,
            documents.size,
            "${deployed.name} must contain exactly two documents: the deployed layer and the production overrides",
        )
        val production = documents[1]
        assertTrue(
            Regex("""activate:\s*\n\s*on-profile:\s*production""").containsMatchIn(production),
            "${deployed.name}: the second document must be activated on the production profile",
        )
        return production
    }

    private val composeFiles =
        listOf(
            File(repoRoot, "deploy/staging/compose.yaml"),
            File(repoRoot, "deploy/staging/compose.data.yaml"),
        )

    /** `${VAR}` with no `:default` — the deliberately fail-fast form. */
    private val required = Regex("""\$\{([A-Z0-9_]+)}""")

    @Test
    fun `every fail-fast placeholder in a deployed or production profile is supplied by the compose topology`() {
        val composeText = composeFiles.joinToString("\n") { it.readText() }
        val rehearsal = File(repoRoot, "scripts/staging-rehearsal.sh").readText()
        val example = File(repoRoot, "deploy/staging/.env.example").readText()

        assertAll(
            deployedProfiles.flatMap { profile ->
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
            deployedProfiles.map { profile ->
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
    fun `staging and production are profile groups over one deployed layer (ADR-034)`() {
        assertAll(
            baseProfiles.map { base ->
                {
                    val text = base.readText()
                    assertTrue(
                        Regex("""group:\s*\n\s*staging:\s*deployed\s*\n\s*production:\s*deployed""").containsMatchIn(text),
                        "${base.parentFile.parentFile.parentFile.parentFile.name}: application.yaml must define " +
                            "spring.profiles.group staging→deployed and production→deployed",
                    )
                }
            } +
                listOf("api", "ingestion").flatMap { module ->
                    listOf("staging", "production").map { profile ->
                        {
                            // A per-profile file is a trap either way: a staging one would
                            // silently duplicate the layer, and a production one would be
                            // silently SHADOWED by it (group order puts `deployed` last).
                            assertFalse(
                                File(repoRoot, "backend/$module/src/main/resources/application-$profile.yaml").exists(),
                                "backend/$module has an application-$profile.yaml; $profile is a group over the " +
                                    "deployed layer and its overrides belong in that file's second document",
                            )
                        }
                    }
                },
        )
    }

    @Test
    fun `the production overrides carry only what is stricter in production (ADR-034)`() {
        assertAll(
            deployedProfiles.map { profile ->
                {
                    val text = productionDocument(profile)
                    assertTrue(
                        Regex("""mail-domain:\s*inbox\.testinbox\.email""").containsMatchIn(text),
                        "${profile.name} must fix the tenant mail domain to inbox.testinbox.email (ADR-004)",
                    )
                    assertTrue(
                        Regex("""create-bucket:\s*false""").containsMatchIn(text),
                        "${profile.name} must turn bucket creation off; production credentials hold no CreateBucket",
                    )
                    // The ceiling is deliberately NOT redeclared without a default: the
                    // shared layer resolves an unset variable to null and DeploymentSafety
                    // refuses it in the aggregated report, where a bare placeholder
                    // exception would have pre-empted every other violation.
                    assertFalse(
                        text.contains("\${TESTINBOX_EDGE_REQUEST_CEILING}"),
                        "${profile.name}: do not redeclare the ceiling without a default in the production document",
                    )
                    // Shared settings belong in the deployed layer. Their presence
                    // here means production has started to diverge from staging.
                    for (shared in listOf("datasource:", "flyway:", "management:", "logging:", "server:")) {
                        assertFalse(
                            text.contains(shared),
                            "${profile.name} contains '$shared' — a shared setting in the production overrides " +
                                "is the start of two configurations that drift",
                        )
                    }
                }
            },
        )
    }

    @Test
    fun `the API production profile enforces the database session bound (ADR-033)`() {
        val api = productionDocument(deployedProfiles.first { it.path.contains("/api/") })
        assertTrue(
            Regex("""require-database-session-timeout:\s*true""").containsMatchIn(api),
            "the API's production overrides must make an unbounded idle_in_transaction_session_timeout a readiness failure",
        )
        val deployed = deployedProfiles.first { it.path.contains("/api/") }.readText()
        assertTrue(
            Regex("""readiness:\s*\n\s*#[^\n]*\n(?:\s*#[^\n]*\n)*\s*include:[^\n]*dbSession""").containsMatchIn(deployed),
            "the API readiness group must include dbSession, or the production enforcement is never consulted",
        )
    }

    @Test
    fun `the compose topology selects the profile group and defaults it to staging`() {
        val compose = File(repoRoot, "deploy/staging/compose.yaml").readText()
        assertTrue(
            compose.contains("SPRING_PROFILES_ACTIVE: \${TESTINBOX_SPRING_PROFILES:-staging}"),
            "compose must activate the profile group from TESTINBOX_SPRING_PROFILES, defaulting to staging",
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
        // enforce for itself. The reference topology sets it here; a deployed
        // database is checked live by the `dbSession` readiness indicator.
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

    /** The `  limits:` block of the deployed layer (first document), up to the next two-space key. */
    private fun limitsBlock(text: String): String? =
        Regex("""(?m)^  limits:\n((?:    .*\n|\s*\n)+)""").find(text.split(Regex("(?m)^---\\s*$")).first())?.groupValues?.get(1)

    @Test
    fun `both deployables carry the same closed-beta INGEST envelope in the shared deployed layer (ADR-035 amendment D)`() {
        val blocks = deployedProfiles.map { it to limitsBlock(it.readText()) }
        assertAll(
            blocks.map { (file, block) ->
                {
                    assertTrue(block != null, "${file.name}: the deployed layer must declare testinbox.limits (the beta envelope)")
                    val text = block.orEmpty()
                    for (expected in listOf(
                        "capacity: \${TESTINBOX_LIMITS_INGEST_CAPACITY:60}",
                        "refill-per-second: \${TESTINBOX_LIMITS_INGEST_REFILL_PER_SECOND:1.0}",
                        "capacity: \${TESTINBOX_LIMITS_INGEST_PER_INBOX_CAPACITY:30}",
                        "refill-per-second: \${TESTINBOX_LIMITS_INGEST_PER_INBOX_REFILL_PER_SECOND:0.5}",
                    )) {
                        assertTrue(text.contains(expected), "${file.name}: the beta envelope must contain '$expected'")
                    }
                    // Undeclared means refused under ALL: R_gated and L have no default anywhere.
                    val shared = file.readText()
                    assertTrue(shared.contains("gated-expected-rate: \${TESTINBOX_STORAGE_GATED_EXPECTED_RATE:}"), file.name)
                    assertTrue(shared.contains("declared-legacy-workspaces: \${TESTINBOX_STORAGE_DECLARED_LEGACY_WORKSPACES:}"), file.name)
                    assertFalse(
                        productionDocument(file).contains("limits:"),
                        "${file.name}: the envelope is shared by staging and production; a production-only copy would drift",
                    )
                }
            } +
                {
                    assertEquals(blocks[0].second, blocks[1].second, "the API and the gateway must carry the identical INGEST envelope")
                } +
                baseProfiles.map { base ->
                    {
                        // Development keeps the library defaults (300/10, 60/2).
                        assertTrue(limitsBlock(base.readText()) == null, "${base.path}: the base profile must not set the beta envelope")
                    }
                },
        )
    }

    @Test
    fun `the staging topology passes the INGEST envelope to both services with the deployed layer's own defaults`() {
        // A variable compose does not pass is inert in the topology; a compose default that
        // differs from the profile's would silently change staging's envelope.
        val compose = File(repoRoot, "deploy/staging/compose.yaml").readText()
        val deployed = deployedProfiles.first().readText()
        val placeholder = Regex("""\$\{(TESTINBOX_LIMITS_INGEST_[A-Z_]+):([^}]+)}""")
        val profileDefaults = placeholder.findAll(deployed).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(4, profileDefaults.size, "the deployed layer declares four INGEST envelope variables")
        assertAll(
            profileDefaults.map { (name, default) ->
                {
                    assertTrue(
                        compose.contains("$name: \${$name:-$default}"),
                        "deploy/staging/compose.yaml must pass $name with the deployed default $default",
                    )
                }
            } +
                listOf("TESTINBOX_STORAGE_GATED_EXPECTED_RATE", "TESTINBOX_STORAGE_DECLARED_LEGACY_WORKSPACES").map { name ->
                    { assertTrue(compose.contains("$name: \${$name:-}"), "compose must pass $name, with no default") }
                },
        )
    }

    @Test
    fun `both deployables and the staging topology read the same participant cap N, defaulting to thirty (M0)`() {
        // The API admits to N and both deployables compute the INGEST envelope r x (N + L) from it,
        // so it is one variable, passed to both services, with the same default everywhere.
        val compose = File(repoRoot, "deploy/staging/compose.yaml").readText()
        val shared = compose.substringAfter("environment: &jvm-env").substringBefore("\n  logging:")
        assertAll(
            deployedProfiles.map { file ->
                {
                    val first = file.readText().split(Regex("(?m)^---\\s*$")).first()
                    assertTrue(
                        first.contains("  beta:\n    max-participants: \${TESTINBOX_BETA_MAX_PARTICIPANTS:30}"),
                        "${file.name}: the shared deployed layer must bind testinbox.beta.max-participants from " +
                            "TESTINBOX_BETA_MAX_PARTICIPANTS, default 30",
                    )
                    assertFalse(productionDocument(file).contains("beta:"), "${file.name}: N must not have a production-only copy")
                }
            } +
                {
                    assertTrue(
                        shared.contains("TESTINBOX_BETA_MAX_PARTICIPANTS: \${TESTINBOX_BETA_MAX_PARTICIPANTS:-30}"),
                        "compose must pass TESTINBOX_BETA_MAX_PARTICIPANTS to both services (the shared jvm-env), default 30",
                    )
                } +
                baseProfiles.map { base ->
                    { assertFalse(base.readText().contains("max-participants"), "${base.path}: development keeps the code default") }
                },
        )
    }
}
