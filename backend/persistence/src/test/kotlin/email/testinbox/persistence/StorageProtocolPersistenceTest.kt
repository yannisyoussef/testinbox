package email.testinbox.persistence

import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.storage.StorageRefusalReason
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.PGConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-035 §6, §6a, §7 and §9 on PostgreSQL: the reservation state machine,
 * refusal records, the orphan check, ambiguity and node generations, with
 * every interleaving that matters driven by held transactions, never sleeps.
 */
class StorageProtocolPersistenceTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase
    private lateinit var reservations: JdbcStorageReservations
    private lateinit var ambiguity: JdbcStorageAmbiguity

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
        reservations = JdbcStorageReservations(db.jdbc, db.transactions)
        ambiguity = JdbcStorageAmbiguity(db.jdbc, db.transactions)
    }

    @Suppress("LongParameterList") // one parameter per reservation column a scenario varies
    private fun reserve(
        ws: UUID,
        inbox: UUID,
        state: String = "RESERVED",
        deadlineIn: Duration = Duration.ofMinutes(2),
        node: String = "n1",
        generation: UUID = UUID.randomUUID(),
        started: Boolean = false,
        bytes: Long = 10,
    ): UUID {
        val id = UUID.randomUUID()
        db.jdbc
            .sql(
                """
                INSERT INTO storage_reservation (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at,
                                                 write_deadline_at, release_not_before, node_id, generation, first_upload_at)
                VALUES (:id, :ws, :inbox, ARRAY[:key]::text[], :bytes, :state, now(), now() + make_interval(secs => :in),
                        CASE WHEN :state = 'RELEASING' THEN now() END, :node, :gen, CASE WHEN :started THEN now() END)
                """.trimIndent(),
            ).param("id", id)
            .param("ws", ws)
            .param("inbox", inbox)
            .param("key", listOf("$ws/$inbox/$id/raw.eml"))
            .param("bytes", bytes)
            .param("state", state)
            .param("in", deadlineIn.seconds.toDouble())
            .param("node", node)
            .param("gen", generation)
            .param("started", started)
            .update()
        return id
    }

    private fun state(id: UUID): String? =
        db.jdbc
            .sql("SELECT state FROM storage_reservation WHERE message_id = ?")
            .param(id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    // --- T2 against cleanup, both orders (§17 test 20) ------------------------------------------

    @Test
    fun `T2 holding a reservation makes cleanup skip it, and T2 then consumes it`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val id = reserve(ws, inbox, deadlineIn = Duration.ofSeconds(-5)) // already past its deadline
        val t2Holds = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val t2 =
                pool.submit {
                    db.transactions.execute {
                        reservations.lockForCommit(listOf(MessageId(id))).single().state shouldBe "RESERVED"
                        t2Holds.countDown()
                        release.await(30, TimeUnit.SECONDS)
                        reservations.consume(listOf(MessageId(id)))
                    }
                }
            t2Holds.await(30, TimeUnit.SECONDS)
            // Cleanup does not wait on the row T2 holds, so it cannot deadlock with it.
            reservations.expireOverdue(Duration.ofMinutes(17)) shouldBe 0
            release.countDown()
            t2.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        state(id) shouldBe null // consumed exactly once, by T2
        reservations.expireOverdue(Duration.ofMinutes(17)) shouldBe 0
    }

    @Test
    fun `cleanup claiming first fences T2 out, which then sees the reservation RELEASING`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val id = reserve(ws, inbox, deadlineIn = Duration.ofSeconds(-5))

        reservations.expireOverdue(Duration.ofMinutes(17)) shouldBe 1

        db.transactions.execute {
            // T2's validation fails on this: GuardedStorage answers 451 (COMMIT_FENCED).
            reservations.lockForCommit(listOf(MessageId(id))).single().state shouldBe "RELEASING"
        }
        // And even a buggy consume cannot take a RELEASING row.
        db.transactions.execute { runCatching { reservations.consume(listOf(MessageId(id))) }.isFailure shouldBe true }
        state(id) shouldBe "RELEASING"
        db.jdbc
            .sql("SELECT extract(epoch FROM release_not_before - write_deadline_at) FROM storage_reservation WHERE message_id = ?")
            .param(id)
            .query(java.math.BigDecimal::class.java)
            .single()
            .toInt() shouldBe 17 * 60 // release_not_before = write_deadline_at + S
    }

    @Test
    fun `two cleaners never claim the same releasable row`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val ids = List(3) { reserve(ws, inbox, state = "RELEASING") }
        val horizon =
            java.time.Instant
                .now()
                .plusSeconds(60)
        reservations.releasable(horizon, 10).map { it.value } shouldContainExactlyInAnyOrder ids
        val firstHolds = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first =
                pool.submit<UUID?> {
                    reservations.withReleasable(MessageId(ids[0]), horizon) { row ->
                        firstHolds.countDown()
                        release.await(30, TimeUnit.SECONDS)
                        row.messageId.value
                    }
                }
            firstHolds.await(30, TimeUnit.SECONDS)
            // SKIP LOCKED: the held row is not handed out twice, and nothing waits for it.
            reservations.withReleasable(MessageId(ids[0]), horizon) { it.messageId.value } shouldBe null
            reservations.withReleasable(MessageId(ids[1]), horizon) { it.messageId.value } shouldBe ids[1]
            release.countDown()
            first.get(30, TimeUnit.SECONDS) shouldBe ids[0]
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `each releasable row commits alone, and a latch survives the rollback of the row that set it`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val (a, b) = List(2) { reserve(ws, inbox, state = "RELEASING") }
        val horizon =
            java.time.Instant
                .now()
                .plusSeconds(60)

        reservations.withReleasable(MessageId(a), horizon) { reservations.release(it.messageId) }
        runCatching {
            reservations.withReleasable(MessageId(b), horizon) {
                ambiguity.latch("late object")
                reservations.release(it.messageId)
                error("storage failed on this row")
            }
        }.isFailure shouldBe true

        state(a) shouldBe null // released, committed on its own
        state(b) shouldBe "RELEASING" // rolled back alone: still charged
        ambiguity.latched() shouldBe "late object" // committed before the row's rollback
    }

    @Test
    fun `a row no longer due or no longer RELEASING is not handed out`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val reserved = reserve(ws, inbox)
        val future = reserve(ws, inbox, state = "RELEASING")
        db.jdbc
            .sql(
                "UPDATE storage_reservation SET release_not_before = now() + interval '1 hour' WHERE message_id = ?",
            ).param(future)
            .update()
        val horizon =
            java.time.Instant
                .now()
                .plusSeconds(60)

        reservations.withReleasable(MessageId(reserved), horizon) { it } shouldBe null
        reservations.withReleasable(MessageId(future), horizon) { it } shouldBe null
    }

    @Test
    fun `T2 and retention cannot deadlock - retention waits for T2's inbox lock, then cascades`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val id = reserve(ws, inbox)
        val t2Holds = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val t2 =
                pool.submit {
                    db.transactions.execute {
                        reservations.lockForCommit(listOf(MessageId(id)))
                        reservations.lockInboxes(listOf(InboxId(inbox)))
                        t2Holds.countDown()
                        release.await(30, TimeUnit.SECONDS)
                        db.message(ws, inbox, rawBytes = 10)
                        reservations.consume(listOf(MessageId(id)))
                    }
                }
            t2Holds.await(30, TimeUnit.SECONDS)
            val retention = pool.submit { db.hardDeleteInbox(inbox) }
            db.awaitBlockedSessions() // retention waits on T2's FOR KEY SHARE, holding nothing T2 needs
            release.countDown()
            t2.get(30, TimeUnit.SECONDS)
            retention.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        db.jdbc
            .sql("SELECT count(*) FROM message")
            .query(Long::class.java)
            .single() shouldBe 0 // committed, then cascaded
        db.assertInvariant("after T2 then retention")
    }

    // --- refusal records (§6a) --------------------------------------------------------------------

    @Test
    fun `a refusal record counts per inbox, skips a vanished inbox, and wakes that inbox's waiters`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val gone = db.inbox(ws).also(db::hardDeleteInbox)
        val listener = db.dataSource.connection
        try {
            listener.createStatement().use { it.execute("LISTEN testinbox_messages") }

            db.transactions.execute {
                reservations.recordRefusals(
                    mapOf(
                        InboxId(inbox) to StorageRefusalReason.INBOX_LIMIT,
                        InboxId(gone) to StorageRefusalReason.WORKSPACE_LIMIT,
                    ),
                )
            }
            db.transactions.execute { reservations.recordRefusals(mapOf(InboxId(inbox) to StorageRefusalReason.SERVICE_CAPACITY)) }

            listener.createStatement().use { it.execute("SELECT 1") }
            val notifications =
                listener
                    .unwrap(PGConnection::class.java)
                    .getNotifications(5_000)
                    ?.toList()
                    .orEmpty()
            notifications.map { it.parameter } shouldBe listOf(inbox.toString(), inbox.toString())
        } finally {
            listener.close()
        }
        db.jdbc
            .sql("SELECT refusal_count, last_refusal_reason FROM inbox_storage WHERE inbox_id = ?")
            .param(inbox)
            .query { rs, _ -> rs.getLong(1) to rs.getString(2) }
            .single() shouldBe (2L to "SERVICE_CAPACITY")
        db.jdbc
            .sql("SELECT count(*) FROM inbox_storage WHERE inbox_id = ?")
            .param(gone)
            .query(Long::class.java)
            .single() shouldBe 0
    }

    @Test
    fun `refusal records roll back with the transaction that wrote them`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)

        runCatching {
            db.transactions.execute {
                reservations.recordRefusals(mapOf(InboxId(inbox) to StorageRefusalReason.INBOX_LIMIT))
                error("the event failed physically after admission")
            }
        }

        db.jdbc
            .sql("SELECT coalesce(sum(refusal_count), 0) FROM inbox_storage")
            .query(Long::class.java)
            .single() shouldBe 0
    }

    // --- the orphan check is one statement (§7) ------------------------------------------------------

    @Test
    fun `a key is an orphan only with no message, no reservation and no unresolved ambiguity`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val committed = db.message(ws, inbox, rawBytes = 1)!!
        val reserved = reserve(ws, inbox)
        val ambiguous = UUID.randomUUID()
        val ambiguousKey = "$ws/$inbox/$ambiguous/raw.eml"
        ambiguity.record("n1", ambiguousKey, 1, Duration.ofHours(1))
        val orphan = UUID.randomUUID()

        reservations.isOrphan(MessageId(committed), "$ws/$inbox/$committed/raw.eml") shouldBe false
        reservations.isOrphan(MessageId(reserved), "$ws/$inbox/$reserved/raw.eml") shouldBe false
        reservations.isOrphan(MessageId(ambiguous), ambiguousKey) shouldBe false
        reservations.isOrphan(MessageId(orphan), "$ws/$inbox/$orphan/raw.eml") shouldBe true
        // Resolved ambiguity no longer protects the key.
        db.jdbc.sql("UPDATE storage_ambiguity SET resolved_at = now()").update()
        reservations.isOrphan(MessageId(ambiguous), ambiguousKey) shouldBe true
    }

    // --- ambiguity, node generations, the latch (§9) ------------------------------------------------------

    @Test
    fun `an unclean earlier generation leaves keyless ambiguity for its started uploads, capped at the slots`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val dead = UUID.randomUUID()
        val clean = UUID.randomUUID()
        val current = UUID.randomUUID()
        ambiguity.registerGeneration("n1", dead, "storage-v1")
        ambiguity.registerGeneration("n1", clean, "storage-v1")
        ambiguity.markCleanShutdown("n1", clean)
        repeat(20) { reserve(ws, db.inbox(ws), node = "n1", generation = dead, started = true) }
        reserve(ws, inbox, node = "n1", generation = dead, started = false) // never started: nothing in flight
        reserve(ws, inbox, node = "n1", generation = clean, started = true)

        ambiguity.recoverDeadGenerations("n1", current, Duration.ZERO, slots = 16, verifyAfter = Duration.ofHours(1)) shouldBe 16

        ambiguity.unresolvedFor("n1") shouldBe 16 // at most the declared slots
        db.jdbc
            .sql("SELECT count(*) FROM storage_ambiguity WHERE node_id = 'n1' AND object_key IS NOT NULL")
            .query(Long::class.java)
            .single() shouldBe 0
        // Every started key of the dead generation gets its own per-key proof,
        // under a coverage node that takes none of n1's slots.
        db.jdbc
            .sql("SELECT count(*) FROM storage_ambiguity WHERE node_id = 'recovered:n1' AND object_key IS NOT NULL")
            .query(Long::class.java)
            .single() shouldBe 20
        ambiguity.unresolvedFor("recovered:n1") shouldBe 20
        db.jdbc
            .sql("SELECT count(*) FROM storage_node WHERE node_id = 'n1'")
            .query(Long::class.java)
            .single() shouldBe 0
        // Idempotent: the generation is forgotten once recovered.
        ambiguity.recoverDeadGenerations("n1", current, Duration.ZERO, 16, Duration.ofHours(1)) shouldBe 0
    }

    @Test
    fun `cleanup recovers another node only once its heartbeat is stale`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val generation = UUID.randomUUID()
        ambiguity.registerGeneration("n2", generation, "storage-v1")
        reserve(ws, inbox, node = "n2", generation = generation, started = true)

        ambiguity.recoverDeadGenerations(null, null, Duration.ofMinutes(5), 16, Duration.ofHours(1)) shouldBe 0 // alive
        db.jdbc.sql("UPDATE storage_node SET heartbeat_at = now() - interval '10 minutes'").update()
        ambiguity.recoverDeadGenerations(null, null, Duration.ofMinutes(5), 16, Duration.ofHours(1)) shouldBe 1
        ambiguity.unresolvedFor("n2") shouldBe 1
    }

    @Test
    fun `a live generation declared dead comes back at its next heartbeat, so a later crash is still recovered`() {
        val ws = db.workspace()
        val generation = UUID.randomUUID()
        ambiguity.registerGeneration("n3", generation, "storage-v1")
        ambiguity.heartbeat("n3", generation, "storage-v1") shouldBe true
        reserve(ws, db.inbox(ws), node = "n3", generation = generation, started = true)
        db.jdbc.sql("UPDATE storage_node SET heartbeat_at = now() - interval '10 minutes' WHERE node_id = 'n3'").update()
        ambiguity.recoverDeadGenerations(null, null, Duration.ofMinutes(5), 16, Duration.ofHours(1)) shouldBe 1

        ambiguity.heartbeat("n3", generation, "storage-v1") shouldBe false // it had been forgotten: re-registered

        db.jdbc
            .sql("SELECT count(*) FROM storage_node WHERE node_id = 'n3' AND NOT clean_shutdown")
            .query(Long::class.java)
            .single() shouldBe 1
        // A reservation started after the resurrection is recovered by a real crash.
        reserve(ws, db.inbox(ws), node = "n3", generation = generation, started = true)
        db.jdbc.sql("UPDATE storage_node SET heartbeat_at = now() - interval '10 minutes' WHERE node_id = 'n3'").update()
        ambiguity.recoverDeadGenerations(null, null, Duration.ofMinutes(5), 16, Duration.ofHours(1)) shouldBe 1
    }

    @Test
    fun `a key ambiguous within 24 hours is recognised, resolved or not`() {
        ambiguity.record("n4", "k-open", 1, Duration.ofHours(1))
        ambiguity.record("n4", "k-resolved", 1, Duration.ofHours(1))
        ambiguity.record("n4", "k-old", 1, Duration.ofHours(1))
        db.jdbc.sql("UPDATE storage_ambiguity SET resolved_at = now() WHERE object_key = 'k-resolved'").update()
        db.jdbc.sql("UPDATE storage_ambiguity SET resolved_at = now() - interval '25 hours' WHERE object_key = 'k-old'").update()

        ambiguity.wasAmbiguous("k-open", Duration.ofHours(24)) shouldBe true
        ambiguity.wasAmbiguous("k-resolved", Duration.ofHours(24)) shouldBe true
        ambiguity.wasAmbiguous("k-old", Duration.ofHours(24)) shouldBe false
        ambiguity.wasAmbiguous("k-never", Duration.ofHours(24)) shouldBe false
    }

    @Test
    fun `a clock-offset postponement moves RESERVED rows too, and their expiry keeps it`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val reserved = reserve(ws, inbox, deadlineIn = Duration.ofSeconds(-1))
        val releasing = reserve(ws, inbox, state = "RELEASING")

        fun rnbMinusDeadline(id: UUID) =
            db.jdbc
                .sql(
                    "SELECT extract(epoch FROM release_not_before - write_deadline_at)::bigint FROM storage_reservation WHERE message_id = ?",
                ).param(id)
                .query(Long::class.java)
                .single()
        val releasingBefore = rnbMinusDeadline(releasing)

        reservations.postponeAll(Duration.ofSeconds(45), Duration.ofMinutes(17)) shouldBe 2
        reservations.expireOverdue(Duration.ofMinutes(17)) shouldBe 1

        rnbMinusDeadline(reserved) shouldBe 17 * 60 + 45L // deadline + S + the offset, not deadline + S
        rnbMinusDeadline(releasing) shouldBe releasingBefore + 45
    }

    @Test
    fun `refusals are recorded in PostgreSQL uuid order, which is unsigned`() {
        // java.util.UUID orders f… before 1… (signed); PostgreSQL, the compactor
        // and drift repair order 1… first. Log the upsert order through the rows' xmin.
        val ws = db.workspace()
        val high = UUID.fromString("f0000000-0000-4000-8000-${UUID.randomUUID().toString().takeLast(12)}")
        val low = UUID.fromString("10000000-0000-4000-8000-${UUID.randomUUID().toString().takeLast(12)}")
        listOf(high, low).forEach { id ->
            db.jdbc
                .sql(
                    "INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at) " +
                        "VALUES (?, ?, ?, ?, 'GENERATED', 'ACTIVE', now(), now() + interval '1 hour')",
                ).params(id, ws, ws, "$id@ledger.test")
                .update()
        }
        (high < low) shouldBe true // the signed order this must not use

        db.transactions.execute {
            reservations.recordRefusals(
                mapOf(InboxId(high) to StorageRefusalReason.INBOX_LIMIT, InboxId(low) to StorageRefusalReason.INBOX_LIMIT),
            )
            // Written in the order upserted: the newest row version of each, by physical position.
            db.jdbc
                .sql("SELECT inbox_id FROM inbox_storage WHERE inbox_id IN (?, ?) ORDER BY ctid")
                .param(high)
                .param(low)
                .query(UUID::class.java)
                .list() shouldBe listOf(low, high)
        }
    }

    @Test
    fun `the latch is set once and only an operator clears it`() {
        ambiguity.latched() shouldBe null
        ambiguity.latch("late object")
        ambiguity.latch("second reason is ignored")
        ambiguity.latched() shouldBe "late object"
    }

    @Test
    fun `markUploadStarted commits on its own, before any upload, and never moves`() {
        val ws = db.workspace()
        val id = reserve(ws, db.inbox(ws))

        reservations.markUploadStarted(MessageId(id))
        val first =
            db.jdbc
                .sql(
                    "SELECT first_upload_at FROM storage_reservation WHERE message_id = ?",
                ).param(id)
                .query(OffsetDateTime::class.java)
                .single()
        reservations.markUploadStarted(MessageId(id))

        db.jdbc
            .sql(
                "SELECT first_upload_at FROM storage_reservation WHERE message_id = ?",
            ).param(id)
            .query(OffsetDateTime::class.java)
            .single() shouldBe
            first
    }

    @Test
    fun `abandoned and duplicate reservations are releasable at once, guarded on RESERVED`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val abandoned = reserve(ws, inbox)
        val duplicate = reserve(ws, inbox)
        val consumed = reserve(ws, inbox)
        db.transactions.execute { reservations.consume(listOf(MessageId(consumed))) }

        reservations.releaseAbandoned(listOf(MessageId(abandoned), MessageId(consumed)))
        db.transactions.execute { reservations.releaseDuplicates(listOf(MessageId(duplicate))) }

        state(abandoned) shouldBe "RELEASING"
        state(duplicate) shouldBe "RELEASING"
        state(consumed) shouldBe null
        reservations
            .releasable(
                java.time.Instant
                    .now()
                    .plusSeconds(5),
                10,
            ).map { it.value } shouldContainExactlyInAnyOrder
            listOf(abandoned, duplicate)
        reservations.countsByState() shouldBe mapOf("RELEASING" to 2L)
        reservations.lockForCommit(emptyList()).shouldBeEmpty()
    }
}
