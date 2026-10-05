package email.testinbox.persistence

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.application.usecase.StorageAdmissionCandidate
import email.testinbox.application.usecase.StorageAdmissionDecision
import email.testinbox.application.usecase.StorageAdmissionRequest
import email.testinbox.application.usecase.StorageAdmissionResult
import email.testinbox.domain.AttachmentId
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.util.Collections
import java.util.UUID
import javax.sql.DataSource

/**
 * Fixtures for ADR-035 T1 over a [LedgerTestDatabase]: seeding base rows,
 * deltas and reservations directly, building candidates with their real key
 * layout, and reading back what T1 committed.
 */
class AdmissionFixture(
    val db: LedgerTestDatabase,
) {
    fun store(lockTimeout: Duration = JdbcStorageAdmission.DEFAULT_LOCK_TIMEOUT) =
        JdbcStorageAdmission(db.jdbc, db.transactions, lockTimeout)

    fun admission(
        policy: StorageCapacityPolicy,
        enforcement: StorageEnforcement = StorageEnforcement.ALL,
        store: StorageAdmissionStore = store(),
    ) = StorageAdmission(store, policy, enforcement)

    fun candidate(
        workspace: UUID,
        inbox: UUID,
        attachments: Int = 0,
        messageId: UUID = UUID.randomUUID(),
    ): StorageAdmissionCandidate {
        val ws = WorkspaceId(workspace)
        val ib = InboxId(inbox)
        val message = MessageId(messageId)
        return StorageAdmissionCandidate(
            messageId = message,
            workspaceId = ws,
            inboxId = ib,
            objectKeys =
                listOf(ObjectKeys.raw(ws, ib, message)) +
                    List(attachments) { ObjectKeys.attachment(ws, ib, message, AttachmentId(UUID.randomUUID())) },
        )
    }

    fun request(
        bytesPerCopy: Long,
        candidates: List<StorageAdmissionCandidate>,
    ) = StorageAdmissionRequest(bytesPerCopy, candidates, NODE, GENERATION)

    // --- seeding the three sums directly (each call ADDS to what is there) ---------

    fun workspaceBase(
        workspace: UUID,
        bytes: Long,
    ) {
        db.jdbc
            .sql(
                """
                INSERT INTO workspace_storage_account (workspace_id, base_bytes) VALUES (?, ?)
                ON CONFLICT (workspace_id) DO UPDATE SET base_bytes = workspace_storage_account.base_bytes + EXCLUDED.base_bytes
                """.trimIndent(),
            ).params(workspace, bytes)
            .update()
    }

    fun inboxBase(
        workspace: UUID,
        inbox: UUID,
        bytes: Long,
    ) {
        db.jdbc
            .sql(
                """
                INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes) VALUES (?, ?, ?)
                ON CONFLICT (inbox_id) DO UPDATE SET base_bytes = inbox_storage.base_bytes + EXCLUDED.base_bytes
                """.trimIndent(),
            ).params(inbox, workspace, bytes)
            .update()
    }

    /** Both bases at once: the usual shape after a compaction. */
    fun base(
        workspace: UUID,
        inbox: UUID,
        bytes: Long,
    ) {
        workspaceBase(workspace, bytes)
        inboxBase(workspace, inbox, bytes)
    }

    fun delta(
        workspace: UUID,
        inbox: UUID?,
        bytes: Long,
    ) {
        db.jdbc
            .sql("INSERT INTO storage_delta (workspace_id, inbox_id, bytes) VALUES (:w, :i, :b)")
            .param("w", workspace)
            .param("i", inbox)
            .param("b", bytes)
            .update()
    }

    /** A live reservation, as another event's T1 would have left it. */
    fun reservation(
        workspace: UUID,
        inbox: UUID,
        bytes: Long,
        state: String = "RESERVED",
        createdAgo: Duration = Duration.ZERO,
    ): UUID {
        val message = UUID.randomUUID()
        db.jdbc
            .sql(
                """
                INSERT INTO storage_reservation
                    (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at, write_deadline_at,
                     release_not_before, node_id, generation)
                VALUES (:m, :w, :i, ARRAY[:k]::text[], :b, :s, now() - make_interval(secs => :ago),
                        now() - make_interval(secs => :ago) + interval '120 seconds',
                        CASE WHEN :s = 'RELEASING' THEN now() + interval '17 minutes' END, 'seed', :g)
                """.trimIndent(),
            ).param("m", message)
            .param("w", workspace)
            .param("i", inbox)
            .param("k", listOf("$workspace/$inbox/$message/raw.eml"))
            .param("b", bytes)
            .param("s", state)
            .param("ago", createdAgo.seconds.toDouble())
            .param("g", UUID.randomUUID())
            .update()
        return message
    }

    // --- reading back -----------------------------------------------------------------

    fun reservationCount(): Long =
        db.jdbc
            .sql("SELECT count(*) FROM storage_reservation")
            .query(Long::class.java)
            .single()

    fun reservedBytes(): Long =
        db.jdbc
            .sql("SELECT coalesce(sum(bytes), 0) FROM storage_reservation")
            .query(Long::class.java)
            .single()

    fun reservedBytesIn(workspace: UUID): Long =
        db.jdbc
            .sql("SELECT coalesce(sum(bytes), 0) FROM storage_reservation WHERE workspace_id = ?")
            .param(workspace)
            .query(Long::class.java)
            .single()

    /** The snapshot T1 reads, taken through the real adapter with a decision that reserves nothing. */
    fun snapshot(
        workspaces: Set<UUID>,
        inboxes: Set<UUID>,
        store: StorageAdmissionStore = store(),
    ): StorageUsageSnapshot =
        store.admit(StorageAdmissionScope(workspaces.map(::WorkspaceId).toSet(), inboxes.map(::InboxId).toSet())) {
            StorageAdmissionPlan(emptyList(), it)
        }

    fun used(
        snapshot: StorageUsageSnapshot,
        workspace: UUID,
        inbox: UUID,
    ): Triple<Long, Long, Long> =
        Triple(
            snapshot.global.usedBytes,
            snapshot.workspaces.getValue(WorkspaceId(workspace)).usedBytes,
            snapshot.inboxes
                .getValue(InboxId(inbox))
                .usage.usedBytes,
        )

    companion object {
        const val NODE = "test-node"
        val GENERATION: UUID = UUID.fromString("00000000-0000-0000-0000-000000000035")

        /** A small policy: inbox limit = floor(workspace × share), cap = G − H. */
        fun policy(
            workspace: Long,
            share: String = "1",
            global: Long,
            h: Long = 0,
        ) = StorageCapacityPolicy(workspace, InboxShare.of(share), global, h)

        /** "admitted" or the refusal reason, per candidate, in envelope order. */
        fun StorageAdmissionResult.shape(): List<String> =
            decisions.map {
                when (it) {
                    is StorageAdmissionDecision.Admitted -> "admitted"
                    is StorageAdmissionDecision.Refused -> it.reason.name
                }
            }
    }
}

/**
 * A [DataSource] that records every SQL text prepared or executed on it, so a
 * test can count the statements one T1 issues.
 */
class RecordingDataSource(
    private val delegate: DataSource,
) {
    val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val dataSource: DataSource =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, method, args ->
            val result = invoke(delegate, method, args)
            if (method.name == "getConnection") recordingConnection(result as Connection) else result
        } as DataSource

    val jdbc: JdbcClient = JdbcClient.create(dataSource)
    val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))

    private fun recordingConnection(connection: Connection): Connection =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
            InvocationHandler { _, method, args ->
                if (method.name in setOf("prepareStatement", "prepareCall")) statements += args[0] as String
                val result = invoke(connection, method, args)
                if (method.name == "createStatement") recordingStatement(result as java.sql.Statement) else result
            },
        ) as Connection

    private fun recordingStatement(statement: java.sql.Statement): java.sql.Statement =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Statement::class.java)) { _, method, args ->
            val sql = args?.firstOrNull() as? String
            if (method.name.startsWith("execute") && sql != null) {
                statements += sql
            }
            invoke(statement, method, args)
        } as java.sql.Statement

    private fun invoke(
        target: Any,
        method: java.lang.reflect.Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
}
