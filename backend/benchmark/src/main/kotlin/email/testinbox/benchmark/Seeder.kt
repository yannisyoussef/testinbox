package email.testinbox.benchmark

import email.testinbox.domain.storage.ParticipantStorageShare
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.random.Random

/** One seeded inbox. The list is ordered by workspace, so a run of consecutive inboxes spans few workspaces. */
class SeededInbox(
    val workspaceId: UUID,
    val inboxId: UUID,
    val address: String,
)

class SeededPopulation(
    val workspaceCount: Int,
    val inboxes: List<SeededInbox>,
    /** The cohort-share workspaces seeded beside a gate population ([Seeder.cohortShares]); empty otherwise. */
    val shareWorkspaces: Set<UUID> = emptySet(),
) {
    val workspaceIds: List<UUID> = inboxes.map { it.workspaceId }.distinct()

    /**
     * The gate population without its cohort shares: where the synthetic backlog
     * (deltas, reservations) and retention's expired inboxes go, so no share is filled
     * by bytes no T1 admitted (as [ClosedCohort] keeps its participants clean).
     */
    val unshared: SeededPopulation
        get() =
            if (shareWorkspaces.isEmpty()) {
                this
            } else {
                SeededPopulation(
                    workspaceCount,
                    inboxes.filter {
                        it.workspaceId !in
                            shareWorkspaces
                    },
                )
            }
}

/**
 * Bulk SQL seeding. Everything is one statement per table, or a few hundred
 * rows per statement: 10 000 workspaces one `INSERT` at a time would measure
 * the seeding, not T1.
 *
 * The standing ledger is synthetic: every inbox and workspace gets a base
 * row with a random committed figure, as after a compaction. The bases are
 * not derived from message rows, so this database would not reconcile; the
 * harness never runs reconciliation, and the admission cost does not depend
 * on the figures, only on the rows.
 */
class Seeder(
    private val jdbc: JdbcClient,
    private val random: Random,
) {
    fun population(
        workspaces: Int,
        inboxesPerWorkspace: Int,
    ): SeededPopulation {
        val workspaceIds =
            jdbc
                .sql(
                    """
                    WITH w AS (INSERT INTO workspace (id, name, created_at)
                               SELECT gen_random_uuid(), 'bench-workspace', now() FROM generate_series(1, :n) RETURNING id),
                         p AS (INSERT INTO project (id, workspace_id, name, created_at) SELECT id, id, 'bench-project', now() FROM w RETURNING id)
                    INSERT INTO workspace_storage_account (workspace_id, base_bytes)
                    SELECT id, 0 FROM p RETURNING workspace_id
                    """.trimIndent(),
                ).param("n", workspaces)
                .query(UUID::class.java)
                .list()
                .filterNotNull()
        val inboxes =
            jdbc
                .sql(
                    """
                    WITH i AS (
                        INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at)
                        SELECT gen_random_uuid(), w, w, 'i' || gen_random_uuid() || '@bench.test', 'GENERATED', 'ACTIVE', now(), now() + interval '1 day'
                          FROM unnest(ARRAY[:ws]::uuid[]) AS w, generate_series(1, :perWorkspace)
                        RETURNING id, workspace_id, address
                    ),
                    s AS (
                        INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes)
                        SELECT id, workspace_id, 1000 + (random() * 1000000)::bigint FROM i RETURNING workspace_id, base_bytes
                    ),
                    acct AS (
                        UPDATE workspace_storage_account a SET base_bytes = a.base_bytes + t.bytes
                          FROM (SELECT workspace_id, sum(base_bytes) AS bytes FROM s GROUP BY workspace_id) t
                         WHERE a.workspace_id = t.workspace_id
                        RETURNING 1
                    )
                    SELECT id, workspace_id, address FROM i
                    """.trimIndent(),
                ).param("ws", workspaceIds)
                .param("perWorkspace", inboxesPerWorkspace)
                .query { rs, _ ->
                    SeededInbox(
                        rs.getObject("workspace_id", UUID::class.java),
                        rs.getObject("id", UUID::class.java),
                        rs.getString("address"),
                    )
                }.list()
                .sortedWith(compareBy({ it.workspaceId }, { it.inboxId }))
        return SeededPopulation(workspaces, inboxes)
    }

    /** One compaction interval's worth of unfolded deltas, spread at random over the population. */
    fun deltas(
        population: SeededPopulation,
        count: Int,
    ) {
        val rows = List(count) { population.inboxes.random(random) to random.nextLong(1, 100_000) }
        rows.chunked(BATCH).forEach { chunk ->
            var statement =
                jdbc.sql(
                    "INSERT INTO storage_delta (workspace_id, inbox_id, bytes) VALUES " +
                        chunk.indices.joinToString(",") { "(:w$it, :i$it, :b$it)" },
                )
            chunk.forEachIndexed { i, (inbox, bytes) ->
                statement = statement.param("w$i", inbox.workspaceId).param("i$i", inbox.inboxId).param("b$i", bytes)
            }
            statement.update()
        }
    }

    /**
     * The same number of unfolded rows as [deltas], usage-neutral: `+x` and `−x`
     * on one inbox, so a closed cohort's shares are not filled by bytes no T1
     * admitted ([ClosedCohort]). The rows T1 scans are what the backlog is for.
     */
    fun neutralDeltas(
        population: SeededPopulation,
        count: Int,
    ) {
        val rows =
            List((count + 1) / 2) { population.inboxes.random(random) to random.nextLong(1, 100_000) }
                .flatMap { (inbox, bytes) -> listOf(inbox to bytes, inbox to -bytes) }
                .take(count)
        rows.chunked(BATCH).forEach { chunk ->
            var statement =
                jdbc.sql(
                    "INSERT INTO storage_delta (workspace_id, inbox_id, bytes) VALUES " +
                        chunk.indices.joinToString(",") { "(:w$it, :i$it, :b$it)" },
                )
            chunk.forEachIndexed { i, (inbox, bytes) ->
                statement = statement.param("w$i", inbox.workspaceId).param("i$i", inbox.inboxId).param("b$i", bytes)
            }
            statement.update()
        }
    }

    /**
     * The live reservation backlog another node's events would have left:
     * `RESERVED` with a deadline ahead, and one in five `RELEASING` with a
     * release time far ahead, so no cleanup could remove them during a run.
     */
    fun reservations(
        population: SeededPopulation,
        count: Int,
    ) {
        val rows =
            List(count) { i ->
                Triple(
                    population.inboxes.random(random),
                    UUID.randomUUID(),
                    if (i % 5 ==
                        0
                    ) {
                        "RELEASING"
                    } else {
                        "RESERVED"
                    },
                )
            }
        rows.chunked(BATCH).forEach { chunk ->
            var statement =
                jdbc.sql(
                    """
                    INSERT INTO storage_reservation
                        (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at, write_deadline_at, release_not_before, node_id, generation)
                    VALUES
                    """.trimIndent() +
                        chunk.indices.joinToString(",") {
                            "(:m$it, :w$it, :i$it, ARRAY[:k$it]::text[], 10000, :s$it, now(), now() + interval '2 hours', " +
                                "CASE WHEN :s$it = 'RELEASING' THEN now() + interval '2 hours' END, '$SEED_NODE', :g$it)"
                        },
                )
            chunk.forEachIndexed { i, (inbox, message, state) ->
                statement =
                    statement
                        .param("m$i", message)
                        .param("w$i", inbox.workspaceId)
                        .param("i$i", inbox.inboxId)
                        .param("k$i", listOf("${inbox.workspaceId}/${inbox.inboxId}/$message/raw.eml"))
                        .param("s$i", state)
                        .param("g$i", UUID.randomUUID())
            }
            statement.update()
        }
    }

    /**
     * Inboxes already `EXPIRED`, each holding [messagesEach] messages with one
     * attachment, for the retention sweep to hard-delete. Their rows are
     * inserted in bulk, so the ledger triggers fire once per statement; the
     * caller compacts afterwards so the seeding leaves no delta backlog.
     */
    fun retentionTargets(
        population: SeededPopulation,
        count: Int,
        messagesEach: Int,
        rawBytes: Long,
        attachmentBytes: Long,
    ): Int {
        if (count == 0) return 0
        val owners = List(count) { population.workspaceIds.random(random) }
        val inboxIds =
            jdbc
                .sql(
                    """
                    WITH i AS (
                        INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at, grace_until)
                        SELECT gen_random_uuid(), w, w, 'r' || gen_random_uuid() || '@bench.test', 'GENERATED', 'EXPIRED',
                               now() - interval '2 hours', now() - interval '1 hour', now() - interval '30 minutes'
                          FROM unnest(ARRAY[:ws]::uuid[]) AS w
                        RETURNING id, workspace_id
                    ),
                    s AS (INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes) SELECT id, workspace_id, 0 FROM i RETURNING 1)
                    SELECT id FROM i
                    """.trimIndent(),
                ).param("ws", owners)
                .query(UUID::class.java)
                .list()
                .filterNotNull()
        jdbc
            .sql(
                """
                WITH m AS (
                    INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_from, envelope_to,
                                         raw_object_key, raw_size_bytes, content_fingerprint, parse_status, subject)
                    SELECT gen_random_uuid(), i.workspace_id, i.id, now() - make_interval(secs => g), 'smtp', 'sender@example.com', i.address,
                           i.workspace_id || '/' || i.id || '/' || gen_random_uuid() || '/raw.eml', :raw, 'retention-seed', 'OK', 'retention'
                      FROM inbox i, generate_series(1, :n) AS g
                     WHERE i.id = ANY(ARRAY[:ids]::uuid[])
                    RETURNING id, workspace_id, raw_object_key
                )
                INSERT INTO attachment (id, workspace_id, message_id, file_name, content_type, size_bytes, object_key)
                SELECT gen_random_uuid(), workspace_id, id, 'a.bin', 'application/octet-stream', :att,
                       replace(raw_object_key, 'raw.eml', 'attachments/' || gen_random_uuid())
                  FROM m
                """.trimIndent(),
            ).param("raw", rawBytes)
            .param("n", messagesEach)
            .param("ids", inboxIds)
            .param("att", attachmentBytes)
            .update()
        return inboxIds.size
    }

    /**
     * The W1 cohort ([ClosedCohort]), at its maximum by default: [participants] ACTIVE,
     * verified participants (the ADR-037 fixed 128/256 MiB shares, by V19's defaults,
     * whatever their number) and one legacy share, each with [inboxesPerWorkspace]
     * inboxes, provisioned the way `AdmitBetaParticipant` leaves them (account,
     * admission, marker). The legacy workspace is `workspaceIds[participants]`.
     * Inserting the markers needs the operator authority: the harness is the owner of
     * its own benchmark database.
     */
    fun cohort(
        inboxesPerWorkspace: Int,
        participants: Int = PARTICIPANTS,
    ): SeededPopulation {
        require(participants in 1..PARTICIPANTS) { "the cohort has 1..$PARTICIPANTS participants" }
        val cohort = population(participants + 1, inboxesPerWorkspace)
        cohort.workspaceIds.take(participants).forEachIndexed { index, ws ->
            val account = UUID.randomUUID()
            jdbc
                .sql(
                    "INSERT INTO human_account (id, contact_email, contact_verified_at, state, created_at) " +
                        "VALUES (:a, :email, now(), 'ACTIVE', now())",
                ).param("a", account)
                .param("email", "$account@bench.invalid")
                .update()
            jdbc
                .sql(
                    "INSERT INTO beta_admission (cohort_id, place, account_id, workspace_id, project_id, admitted_at) " +
                        "VALUES ('beta-30', :place, :a, :ws, :ws, now())",
                ).param("place", index + 1)
                .param("a", account)
                .param("ws", ws)
                .update()
            jdbc
                .sql(
                    "INSERT INTO participant_workspace (workspace_id, cohort_id, place, allocated_at) VALUES (:ws, 'beta-30', :place, now())",
                ).param("ws", ws)
                .param("place", index + 1)
                .update()
        }
        jdbc
            .sql("INSERT INTO storage_legacy_share (workspace_id, payload_limit_bytes, footprint_limit_bytes) VALUES (:ws, :p, :f)")
            .param("ws", cohort.workspaceIds[participants])
            .param("p", LEGACY_PAYLOAD_BYTES)
            .param("f", LEGACY_FOOTPRINT_BYTES)
            .update()
        return cohort
    }

    /**
     * The closed beta's cohort shares beside a gate population (owner decision
     * 2026-11-06, #124 6684712608: "include the beta's 31 cohort shares"): [PARTICIPANTS]
     * workspaces with the ADR-037 participant share (128 MiB payload, 256 MiB footprint)
     * and one legacy share, each with [inboxesPerWorkspace] inboxes. T1's one statement
     * then reads 31 shares, their usage and their budgets, as it does in the beta.
     *
     * **Seeded as `storage_legacy_share` rows, not `participant_workspace` rows.** A
     * participant row makes the cohort pool REQUIRED, and a required pool is approved
     * only when no workspace lacks a share (`unknown = 0`, CohortStorageRuntime). Beside
     * 10 000 unshared workspaces that never holds, so every CHOSEN T1 would be a
     * COHORT_UNAPPROVED `451` and the run would measure nothing. The 30 participant-sized
     * rows are therefore legacy rows with the participant figures: the statement reads
     * the same 31 share rows, their deltas, reservations and charges, and prices every
     * copy to a share against its budget, as in the beta; it does not run the required
     * pool's approval (the plan, node and fingerprint checks), which no population of
     * 10 000 unshared workspaces can pass.
     *
     * Returns the gate population with the share workspaces' inboxes added; its
     * workspace count stays the nominal one, so scenario names and §11 coverage are
     * unchanged.
     */
    fun cohortShares(
        population: SeededPopulation,
        inboxesPerWorkspace: Int,
    ): SeededPopulation {
        val shares = population(PARTICIPANTS + 1, inboxesPerWorkspace)
        shares.workspaceIds.forEachIndexed { index, ws ->
            val participant = index < PARTICIPANTS
            jdbc
                .sql("INSERT INTO storage_legacy_share (workspace_id, payload_limit_bytes, footprint_limit_bytes) VALUES (:ws, :p, :f)")
                .param("ws", ws)
                .param("p", if (participant) ParticipantStorageShare.PAYLOAD_BYTES else LEGACY_PAYLOAD_BYTES)
                .param("f", if (participant) ParticipantStorageShare.FOOTPRINT_BYTES else LEGACY_FOOTPRINT_BYTES)
                .update()
        }
        return SeededPopulation(population.workspaceCount, population.inboxes + shares.inboxes, shares.workspaceIds.toSet())
    }

    /**
     * Between PARTITIONED scenarios, untimed: the cohort's messages go (their
     * ledger deltas fold in the next compaction), and the deletion debt and
     * cleanup charges they leave are dropped, so every scenario starts the
     * cohort from the same usage, far inside its shares.
     */
    fun resetCohortUsage(cohort: SeededPopulation) {
        val ids = cohort.workspaceIds
        jdbc.sql("DELETE FROM message WHERE workspace_id = ANY(ARRAY[:ws]::uuid[])").param("ws", ids).update()
        jdbc.sql("DELETE FROM workspace_deletion_charge WHERE workspace_id = ANY(ARRAY[:ws]::uuid[])").param("ws", ids).update()
        jdbc.sql("DELETE FROM storage_deletion_debt WHERE workspace_id = ANY(ARRAY[:ws]::uuid[])").param("ws", ids).update()
    }

    companion object {
        /** ADR-037: the cohort's thirty places (`BetaCohortPolicy.MAX_PARTICIPANTS`). */
        const val PARTICIPANTS = 30

        /** Sized for retention's expired inboxes, which the closed cohort puts in the legacy workspace. */
        const val LEGACY_PAYLOAD_BYTES = 64L shl 30
        const val LEGACY_FOOTPRINT_BYTES = 128L shl 30
        const val SEED_NODE = "bench-seed"
        private const val BATCH = 200
    }
}
