package email.testinbox.persistence

import email.testinbox.application.port.StorageAdmissionUnavailableException
import email.testinbox.application.storage.AdmissionLocking
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.PartitionFallbackReason
import email.testinbox.application.storage.StorageFootprintUnavailableException
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.AdmissionFixture.Companion.shape
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.dao.CannotAcquireLockException
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regression tests from the independent PostgreSQL-concurrency and architecture
 * reviews of the share-partitioned T1 (#136). Each named finding failed against
 * the code it was raised on and passes after its fix (see the commit message).
 */
class PartitionedAdmissionRegressionTest : PartitionedAdmissionFixture() {
    // --- P2: REPEATABLE READ write skew past the deferred bound check ----------------------------

    @Test
    fun `P2 - a REPEATABLE READ share writer is refused, so two writers cannot jointly overcommit on stale snapshots`() {
        val cohort = closedCohort()
        val (w1, w2) = cohort.workspaces
        val x = headroom() // alone, each raise fits exactly
        (x > 0) shouldBe true
        db.dataSource.connection.use { a ->
            a.autoCommit = false
            a.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
            a.createStatement().use { it.executeQuery("SELECT 1").close() } // the snapshot is taken here
            db.jdbc
                .sql("UPDATE storage_legacy_share SET payload_limit_bytes = payload_limit_bytes + ? WHERE workspace_id = ?")
                .params(x, w2)
                .update() shouldBe 1
            val refused =
                shouldThrow<SQLException> {
                    a
                        .prepareStatement(
                            "UPDATE storage_legacy_share SET payload_limit_bytes = payload_limit_bytes + ? WHERE workspace_id = ?",
                        ).use {
                            it.setLong(1, x)
                            it.setObject(2, w1)
                            it.executeUpdate()
                        }
                    a.commit()
                }
            refused.message.orEmpty() shouldContain "READ COMMITTED"
            a.rollback()
        }
        withinBound() shouldBe true
    }

    @Test
    fun `P2 control - the same interleaving under READ COMMITTED is refused at commit by the bound check`() {
        val cohort = closedCohort()
        val (w1, w2) = cohort.workspaces
        val x = headroom()
        db.dataSource.connection.use { a ->
            a.autoCommit = false
            a.createStatement().use { it.executeQuery("SELECT 1").close() }
            db.jdbc
                .sql("UPDATE storage_legacy_share SET payload_limit_bytes = payload_limit_bytes + ? WHERE workspace_id = ?")
                .params(x, w2)
                .update() shouldBe 1
            a.prepareStatement("UPDATE storage_legacy_share SET payload_limit_bytes = payload_limit_bytes + ? WHERE workspace_id = ?").use {
                it.setLong(1, x)
                it.setObject(2, w1)
                it.executeUpdate()
            }
            shouldThrow<SQLException> { a.commit() }
        }
        withinBound() shouldBe true
    }

    // --- C-1 (re-review): a SERIALIZABLE writer racing a READ COMMITTED one ---------------------

    @Test
    fun `C-1 R3 - a SERIALIZABLE share writer is refused, since SSI cannot see a READ COMMITTED writer`() {
        val cohort = closedCohort()
        val (w1, w2) = cohort.workspaces
        val x = headroom()
        db.dataSource.connection.use { a ->
            a.autoCommit = false
            a.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE") }
            a.createStatement().use { it.executeQuery("SELECT 1").close() } // the snapshot is taken here
            db.jdbc
                .sql("UPDATE storage_legacy_share SET payload_limit_bytes = payload_limit_bytes + ? WHERE workspace_id = ?")
                .params(x, w2)
                .update() shouldBe 1
            val refused =
                shouldThrow<SQLException> {
                    a
                        .prepareStatement(
                            "UPDATE storage_legacy_share SET payload_limit_bytes = payload_limit_bytes + ? WHERE workspace_id = ?",
                        ).use {
                            it.setLong(1, x)
                            it.setObject(2, w1)
                            it.executeUpdate()
                        }
                    a.commit()
                }
            refused.message.orEmpty() shouldContain "READ COMMITTED"
            a.rollback()
        }
        withinBound() shouldBe true
    }

    @Test
    fun `C-1 R5 - revoke, grow past the bound, re-approve by a plan UPDATE - the re-approval is refused`() {
        val cohort = closedCohort()
        val w1 = cohort.workspaces.first()
        db.jdbc.sql("UPDATE storage_cohort_plan SET approved = false").update()
        db.jdbc
            .sql("UPDATE storage_legacy_share SET payload_limit_bytes = payload_limit_bytes + ? WHERE workspace_id = ?")
            .params(headroom() + 1, w1)
            .update() shouldBe 1
        withinBound() shouldBe false
        shouldThrow<Exception> { db.jdbc.sql("UPDATE storage_cohort_plan SET approved = true").update() }
    }

    // --- S-REC: no cross-tenant convoy through the shared (35, 1) ----------------------------------

    @Test
    fun `S-REC - T1s queued on a hot workspace hold no (35, 1), so an exclusive waiter and a cold T1 do not wait for them`() {
        val cohort = closedCohort()
        val (hot, cold) = cohort.workspaces
        val key = JdbcStorageAdmission.workspaceLockKeys(listOf(hot)).single()
        val store = partitioned(lockTimeout = Duration.ofSeconds(20))
        val pool = Executors.newFixedThreadPool(3)
        db.dataSource.connection.use { holder ->
            holder.autoCommit = false
            // The head of the hot workspace's queue, holding its (38, k) for as long as the test wants.
            holder.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(38, $key)") }
            try {
                val queued = pool.submit<List<String>> { admit(store, hot to cohort.inboxes.getValue(hot)[0]).shape() }
                db.awaitBlockedSessions(1)
                // An exclusive (35, 1) asker (a heartbeat's node fence, a fallback, rule P): it must not wait
                // for the queued hot T1, which holds nothing but its place in the (38, k) queue.
                val exclusive =
                    pool.submit {
                        db.dataSource.connection.use { x ->
                            x.autoCommit = false
                            x.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(35, 1)") }
                            x.commit()
                        }
                    }
                exclusive.get(15, TimeUnit.SECONDS)
                val coldT1 = pool.submit<List<String>> { admit(store, cold to cohort.inboxes.getValue(cold)[0]).shape() }
                coldT1.get(15, TimeUnit.SECONDS) shouldBe listOf("admitted") // while the hot head still holds its key
                queued.isDone shouldBe false
                holder.rollback()
                queued.get(30, TimeUnit.SECONDS) shouldBe listOf("admitted")
            } finally {
                pool.shutdownNow()
            }
        }
    }

    // --- P1: debt racing the reserve that shares its workspace ------------------------------------

    @Test
    fun `P1 - a debt row that raced the reserve sharing its workspace, charged nowhere, sends T1 to mode a`() {
        val cohort = closedCohort()
        val newcomer = db.workspace()
        db.dataSource.connection.use { r ->
            r.autoCommit = false
            // Retention for the not-yet-shared workspace: the attribution trigger sees no share, so no charge row.
            r.prepareStatement("INSERT INTO storage_deletion_debt (bytes, objects, workspace_id) VALUES (?, 1, ?)").use {
                it.setLong(1, 5_000_000L)
                it.setObject(2, newcomer)
                it.executeUpdate() shouldBe 1
            }
            // Meanwhile the operator shares it; the reserve's back-fill cannot see the uncommitted debt.
            reserve((cohort.workspaces + newcomer).associate { WorkspaceId(it) to LegacyStorageShare(payloadShare, footprintShare) })
            r.commit()
        }
        db.jdbc
            .sql("SELECT count(*) FROM workspace_deletion_charge WHERE workspace_id = ?")
            .param(newcomer)
            .query(Long::class.java)
            .single() shouldBe 0
        val store = partitioned()
        admit(store, newcomer to db.inbox(newcomer)).shape() shouldBe listOf("admitted") // mode a: the debt is in its D
        store.partitionedAdmissions shouldBe 0
        store.partitionFallbacks shouldBe 1
        fallbackReasons shouldBe listOf(PartitionFallbackReason.UNCHARGED_DEBT)
    }

    // --- P3 / A4: a stale bound must not block an older artifact's legitimate reserve -------------

    @Test
    fun `P3 - a bound left by a newer artifact does not refuse a re-reserve that changed the plan without recording one`() {
        val cohort = closedCohort()
        val (payloadBound, _) = legacyBound()
        // An older artifact's reserve: lock, enlarge a share, re-approve the plan; it knows nothing of the bound.
        db.transactions.execute {
            db.jdbc
                .sql("SELECT pg_advisory_xact_lock(35, 1)")
                .query()
                .listOfRows()
            db.jdbc
                .sql("UPDATE storage_legacy_share SET payload_limit_bytes = ? WHERE workspace_id = ?")
                .params(payloadBound, cohort.workspaces.first())
                .update()
            db.jdbc.sql("UPDATE storage_cohort_plan SET fingerprint = repeat('a', 64)").update()
        }
        db.jdbc
            .sql("SELECT payload_limit_bytes FROM storage_legacy_share WHERE workspace_id = ?")
            .param(cohort.workspaces.first())
            .query(Long::class.java)
            .single() shouldBe payloadBound
        // The stale bound no longer matches the plan: no T1 partitions (and mode a's readiness decides).
        val store = partitioned()
        shouldThrow<StorageFootprintUnavailableException> {
            admit(store, cohort.workspaces.last() to cohort.inboxes.getValue(cohort.workspaces.last())[0])
        }
        store.partitionedAdmissions shouldBe 0
        store.partitionFallbacks shouldBe 1
        fallbackReasons shouldBe listOf(PartitionFallbackReason.NO_MATCHING_BOUND)
    }

    // --- A3: a per-workspace lock timeout is never a per-tenant 451 -------------------------------

    @Test
    fun `A3 - a workspace lock that times out falls back to mode a instead of answering 451`() {
        val cohort = closedCohort()
        val ws = cohort.workspaces.first()
        val key = JdbcStorageAdmission.workspaceLockKeys(listOf(ws)).single()
        db.dataSource.connection.use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(38, $key)") }
            val store = partitioned(lockTimeout = Duration.ofMillis(300))
            admit(store, ws to cohort.inboxes.getValue(ws)[0]).shape() shouldBe listOf("admitted")
            store.partitionFallbacks shouldBe 1
            store.partitionedAdmissions shouldBe 0
            fallbackReasons shouldBe listOf(PartitionFallbackReason.WORKSPACE_LOCK_TIMEOUT)
            holder.rollback()
        }
        // The shared (35, 1) is global, like mode a's exclusive one: its timeout stays a 451 for everyone.
        db.dataSource.connection.use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(35, 1)") }
            shouldThrow<StorageAdmissionUnavailableException> {
                admit(partitioned(lockTimeout = Duration.ofMillis(300)), ws to cohort.inboxes.getValue(ws)[1])
            }
            holder.rollback()
        }
    }

    @Test
    fun `A3 - a deadlock broken while taking the workspace locks falls back to mode a too`() {
        val cohort = closedCohort()
        val ws = cohort.workspaces.first()
        val deadlocking =
            object : JdbcStorageAdmission(
                db.jdbc,
                db.transactions,
                readsFootprint = true,
                locking = AdmissionLocking.COHORT_PARTITIONED,
                onFallback = { fallbackReasons += it },
            ) {
                override fun acquireWorkspaceLocks(keys: List<Int>): Unit =
                    throw CannotAcquireLockException("deadlock", SQLException("deadlock detected", "40P01"))
            }
        admit(deadlocking, ws to cohort.inboxes.getValue(ws)[0]).shape() shouldBe listOf("admitted")
        fallbackReasons shouldBe listOf(PartitionFallbackReason.WORKSPACE_LOCK_DEADLOCK)
        deadlocking.partitionedAdmissions shouldBe 0
    }

    // --- P4: keys are deduplicated and ordered by the key itself, collisions only serialize -------

    @Test
    fun `P4 - colliding workspace ids share one key, keys sort by value, and two colliding workspaces serialize safely`() {
        val m = 0x1234_5678_9abc_def0L
        val l = 0x0fed_cba9_8765_4321L
        val a = UUID(m, l)
        val b = UUID(l, m) // hi ^ lo is the same, so UUID.hashCode is the same
        (a == b) shouldBe false
        a.hashCode() shouldBe b.hashCode()
        JdbcStorageAdmission.workspaceLockKeys(listOf(a, b)) shouldBe listOf(a.hashCode())
        val keyed = listOf(UUID(0, 7), UUID(0, 0xFFFF_FFFDL), UUID(0, 5), UUID(0, 7)) // hash codes 7, -3, 5, 7
        JdbcStorageAdmission.workspaceLockKeys(keyed) shouldBe listOf(-3, 5, 7)

        val cohort = closedCohort(ids = listOf(a, b, UUID.randomUUID()))
        val store = partitioned()
        val race = race(store, listOf(a to cohort.inboxes.getValue(a)[0])) { admit(store, b to cohort.inboxes.getValue(b)[0]) }
        race.secondWasBlocked shouldBe true // one key: serialized, never wrong
        (race.first.shape() + race.second.shape()) shouldBe listOf("admitted", "admitted")
        store.partitionedAdmissions shouldBe 2
    }

    // --- A2: the database records each node's T1 protocol, for the inventory and gate L --------------

    @Test
    fun `A2 - a node row records its admission locking at registration and after every heartbeat, GLOBAL by default`() {
        fun locking(node: String) =
            db.jdbc
                .sql("SELECT admission_locking FROM storage_node WHERE node_id = ? ORDER BY heartbeat_at DESC LIMIT 1")
                .param(node)
                .query(String::class.java)
                .single()
        val partitionedNode =
            JdbcStorageAmbiguity(db.jdbc, db.transactions, StorageEnforcement.ALL, null, AdmissionLocking.COHORT_PARTITIONED)
        val generation = UUID.randomUUID()
        partitionedNode.registerGeneration("smtp-p", generation, "storage-v1")
        locking("smtp-p") shouldBe "COHORT_PARTITIONED"
        // A heartbeat that re-inserts a row cleanup removed must not come back as mode a.
        db.jdbc.sql("DELETE FROM storage_node WHERE node_id = 'smtp-p'").update()
        partitionedNode.heartbeat("smtp-p", generation, "storage-v1")
        locking("smtp-p") shouldBe "COHORT_PARTITIONED"
        JdbcStorageAmbiguity(db.jdbc, db.transactions, StorageEnforcement.ALL).registerGeneration("smtp-g", UUID.randomUUID(), "storage-v1")
        locking("smtp-g") shouldBe "GLOBAL"
        JdbcActivationInventory(db.jdbc).nodes().associate { it.nodeId to it.admissionLocking } shouldBe
            mapOf("smtp-p" to "COHORT_PARTITIONED", "smtp-g" to "GLOBAL")
        shouldThrow<Exception> { db.jdbc.sql("UPDATE storage_node SET admission_locking = 'cohort_partitioned'").update() }
    }

    // --- P6: G2 alone, rule P, and equivalence at the edge of the partition ----------------------

    @Test
    fun `P6 - an overcommitted bound alone (fingerprint and plan intact) sends T1 to mode a, and only G2 does`() {
        val cohort = closedCohort()
        val ws = cohort.workspaces.first()
        // Lower the bound under the shares without touching the plan or the inventory: R3 still holds.
        db.jdbc.sql("DROP TRIGGER storage_partition_bound_check ON storage_partition_bound").update()
        db.jdbc.sql("UPDATE storage_partition_bound SET payload_capacity_bytes = 1").update()
        withinBound() shouldBe false
        val store = partitioned()
        admit(store, ws to cohort.inboxes.getValue(ws)[0]).shape() shouldBe listOf("admitted") // mode a: the pool is ready
        fallbackReasons shouldBe listOf(PartitionFallbackReason.OVER_BOUND)
        store.partitionedAdmissions shouldBe 0
        // A mutant without G2 partitions the same state: G2 is what refused it.
        val withoutG2 =
            object : JdbcStorageAdmission(db.jdbc, db.transactions, readsFootprint = true, locking = AdmissionLocking.COHORT_PARTITIONED) {
                override fun partitionGuard() = PartitionedT1Sql.columns(bound = "true")
            }
        admit(withoutG2, ws to cohort.inboxes.getValue(ws)[1]).shape() shouldBe listOf("admitted")
        withoutG2.partitionedAdmissions shouldBe 1
    }

    @Test
    fun `P6 - rule P over-allocating a share closes admission for every T1, partitioned or not, as in mode a`() {
        val cohort = closedCohort()
        val (full, other) = cohort.workspaces
        val key = "$full/${cohort.inboxes.getValue(full)[0]}/${UUID.randomUUID()}/raw.eml"
        // An orphan sweep's pending debt: charged to the share, past its footprint ceiling.
        JdbcRowFreeDebtStore(db.jdbc, db.transactions).admit(key, 2 * footprintShare, 1, "orphan-sweep") { true } shouldBe true
        db.jdbc
            .sql("SELECT count(*) FROM workspace_deletion_charge WHERE workspace_id = ?")
            .param(full)
            .query(Long::class.java)
            .single() shouldBe 1
        val store = partitioned()
        shouldThrow<StorageFootprintUnavailableException> { admit(store, other to cohort.inboxes.getValue(other)[0]) }
        shouldThrow<StorageFootprintUnavailableException> { admit(modeA(), other to cohort.inboxes.getValue(other)[0]) }
        fallbackReasons shouldBe emptyList() // the guard held: the pool's readiness refused, in the same snapshot
        fx.reservedBytes() shouldBe 0
    }

    @Test
    fun `P6 - at the edge of the partition (usage at the bound, slack = the worst envelope) both modes decide alike`() {
        val partitionedRun = nearTheCeiling { partitioned() }
        val partitionedCount = lastStore.partitionedAdmissions
        val modeARun = nearTheCeiling { modeA() }
        partitionedRun shouldBe modeARun
        partitionedCount shouldBe partitionedRun.size.toLong()
        modeARun shouldBe
            listOf(
                listOf("admitted"),
                listOf("admitted"),
                listOf("admitted"),
                // Each workspace has one inbox holding all its usage, and the inbox share is 1:
                // the narrowest ceiling reached is the inbox's, in both modes.
                listOf("INBOX_LIMIT"),
                listOf("INBOX_LIMIT"),
                listOf("INBOX_LIMIT", "INBOX_LIMIT", "INBOX_LIMIT"),
                listOf("INBOX_LIMIT"),
            )
    }

    private lateinit var lastStore: JdbcStorageAdmission

    /**
     * A fresh database whose 30 participants, three small legacy shares and one
     * large legacy share exactly fill the recorded bound, with every share full
     * but three copies of the large one. Mode a's global payload slack is then
     * the worst envelope plus those three copies: the tightest state the
     * partition allows. Returns the shapes of seven events.
     */
    private fun nearTheCeiling(store: () -> JdbcStorageAdmission): List<List<String>> {
        db = LedgerTestDatabase.create(postgres, admin)
        fx = AdmissionFixture(db)
        val participants = List(30) { db.workspace() }
        participants.forEachIndexed { i, ws -> participant(ws, i + 1) }
        val small = List(3) { db.workspace() }
        val big = db.workspace()
        val inboxes = (participants + small + big).associateWith { db.inbox(it) }
        cohortInfrastructure()
        val runtime = checkNotNull(EffectiveStoragePolicy.footprint(declarations)?.cohort)
        val bound = checkNotNull(runtime.partitionCapacity(gib, gib))
        val room = bound.payloadBytes - 30L * 134217728 - 3 * payloadShare
        val roomFootprint = bound.footprintBytes - 30L * 268435456 - 3 * footprintShare
        reserve(
            small.associate { WorkspaceId(it) to LegacyStorageShare(payloadShare, footprintShare) } +
                (WorkspaceId(big) to LegacyStorageShare(room, roomFootprint)),
        )
        withinBound() shouldBe true
        headroom() shouldBe 0
        participants.forEach { fx.base(it, inboxes.getValue(it), 134217728) }
        small.forEach { fx.base(it, inboxes.getValue(it), payloadShare) }
        fx.base(big, inboxes.getValue(big), room - 3 * COPY)
        trust() // base rows written directly: re-mark trust the way only the owner can
        val h = EffectiveStoragePolicy.finalizeBudget(declarations).bytes
        val g = checkNotNull(declarations.globalLimitBytes)
        val worst = checkNotNull(EffectiveStoragePolicy.footprint(declarations)).worstCaseEvent.bytes
        val used =
            db.jdbc
                .sql("SELECT base_bytes FROM storage_global_base WHERE id = 1")
                .query(Long::class.java)
                .single()
        (g - h - used) shouldBe worst + 3 * COPY
        // The workspace limit above the large share, so that share is its own ceiling.
        val policy = StorageCapacityPolicy(16 * gib, InboxShare.of("1"), g, h)
        lastStore = store()
        val admission =
            StorageAdmission(lastStore, policy, StorageEnforcement.ALL, footprint = EffectiveStoragePolicy.footprint(declarations))

        fun event(vararg copies: Pair<UUID, UUID>) =
            admission.admit(fx.request(COPY, copies.map { fx.candidate(it.first, it.second) })).shape()
        val bigInbox = big to inboxes.getValue(big)
        return List(5) { event(bigInbox) } +
            listOf(
                event(*small.map { it to inboxes.getValue(it) }.toTypedArray()),
                event(participants.first() to inboxes.getValue(participants.first())),
            )
    }

    /** An ACTIVE participant, provisioned the way beta admission leaves one (account, admission, marker). */
    private fun participant(
        ws: UUID,
        place: Int,
    ) {
        val account = UUID.randomUUID()
        db.jdbc
            .sql(
                "INSERT INTO human_account (id, contact_email, contact_verified_at, state, created_at) VALUES (?, ?, now(), 'ACTIVE', now())",
            ).params(account, "$account@partitioned.test")
            .update()
        db.jdbc
            .sql(
                "INSERT INTO beta_admission (cohort_id, place, account_id, workspace_id, project_id, admitted_at) " +
                    "VALUES ('beta-30', ?, ?, ?, ?, now())",
            ).params(place, account, ws, ws)
            .update()
        db.jdbc
            .sql("INSERT INTO participant_workspace (workspace_id, cohort_id, place, allocated_at) VALUES (?, 'beta-30', ?, now())")
            .params(ws, place)
            .update()
    }

    private fun trust() {
        db.jdbc
            .sql(
                """
                DO ${'$'}t${'$'}
                BEGIN
                    PERFORM set_config('testinbox.trust_verifier', 'v10', true);
                    UPDATE storage_footprint_trust
                       SET trusted_epoch = distrust_epoch, trusted_seq = nextval('storage_debt_order_seq'), trusted_at = clock_timestamp()
                     WHERE id = 1;
                    PERFORM set_config('testinbox.trust_verifier', '', true);
                END
                ${'$'}t${'$'}
                """.trimIndent(),
            ).update()
        observation()
    }
}
