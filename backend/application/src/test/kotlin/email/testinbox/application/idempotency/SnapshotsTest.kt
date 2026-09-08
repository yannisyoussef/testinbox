package email.testinbox.application.idempotency

import email.testinbox.application.port.IdempotencyScope
import email.testinbox.application.port.IdempotencySnapshot
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.idempotency.IdempotencyKey
import email.testinbox.domain.idempotency.IdempotentOperation
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The decode side of the replay projection (ADR-033 §6).
 *
 * Everything here guards one branch: returning **null** rather than guessing.
 * Null is what makes the caller answer `idempotency-replay-unavailable`, and
 * that answer exists for a single reachable case — an ADR-028 artifact
 * rollback across a snapshot version bump, where a newer artifact wrote a
 * record this one cannot read. Re-executing would duplicate a mutation that
 * did commit, so a decoder that silently produced a *partly* correct object
 * would be worse than one that fails.
 *
 * It is also the case nobody exercises by hand, because it only happens during
 * a rollback.
 */
class SnapshotsTest {
    private val inbox =
        Inbox(
            id = InboxId(UUID.randomUUID()),
            workspaceId = WorkspaceId(UUID.randomUUID()),
            projectId = ProjectId(UUID.randomUUID()),
            address = "probe@testinbox.local",
            addressMode = AddressMode.GENERATED,
            state = InboxState.ACTIVE,
            createdAt = Instant.parse("2026-09-08T12:00:00Z"),
            expiresAt = Instant.parse("2026-09-08T12:10:00Z"),
        )

    @Test
    fun `an inbox survives the round trip unchanged`() {
        // Guard the guard: if this failed, every null assertion below would
        // still pass while proving only that the decoder is broken.
        InboxSnapshot.toInbox(InboxSnapshot.of(inbox)) shouldBe inbox
    }

    @Test
    fun `a version this artifact does not know decodes to null, never to a guess`() {
        val newer = IdempotencySnapshot(InboxSnapshot.VERSION + 1, InboxSnapshot.of(inbox).payload)
        // The payload is entirely readable — only the version differs. A
        // decoder that ignored the version would happily return an Inbox here,
        // which is exactly the rollback bug: a newer artifact's record read
        // under older rules.
        InboxSnapshot.toInbox(newer) shouldBe null

        val older = IdempotencySnapshot(InboxSnapshot.VERSION - 1, InboxSnapshot.of(inbox).payload)
        InboxSnapshot.toInbox(older) shouldBe null
    }

    @Test
    fun `a corrupt or truncated payload decodes to null rather than throwing`() {
        val valid = InboxSnapshot.of(inbox).payload
        listOf(
            valid - "inboxId",
            valid + ("inboxId" to "not-a-uuid"),
            valid + ("addressMode" to "NO_SUCH_MODE"),
            valid + ("state" to "NO_SUCH_STATE"),
            valid + ("expiresAt" to "not-a-timestamp"),
            valid + ("address" to null),
            emptyMap(),
        ).forEach { payload ->
            // Null, not an exception: this runs inside the claim transaction,
            // and a throw here would surface as a 500 on a request whose
            // mutation already committed.
            InboxSnapshot.toInbox(IdempotencySnapshot(InboxSnapshot.VERSION, payload)) shouldBe null
        }
    }

    @Test
    fun `a credential snapshot round trips and rejects an unknown version`() {
        val id = ApiKeyId(UUID.randomUUID())
        val publicId = "abcdefghijklmnop"
        ApiKeySnapshot.toCreated(ApiKeySnapshot.of(id, publicId)) shouldBe (id to publicId)

        val newer = IdempotencySnapshot(ApiKeySnapshot.VERSION + 1, ApiKeySnapshot.of(id, publicId).payload)
        ApiKeySnapshot.toCreated(newer) shouldBe null
        ApiKeySnapshot.toCreated(
            IdempotencySnapshot(ApiKeySnapshot.VERSION, mapOf("apiKeyId" to "not-a-uuid")),
        ) shouldBe null
    }

    @Test
    fun `a credential snapshot carries no secret material`() {
        // ADR-032 §4 is structural: the projection stores an identifier and a
        // non-secret handle, so there is nothing here to relax later. Asserted
        // on the keys rather than the values, so a field added in future has
        // to be named deliberately.
        ApiKeySnapshot.of(ApiKeyId(UUID.randomUUID()), "abcdefghijklmnop").payload.keys shouldBe
            setOf("apiKeyId", "publicId")
    }

    @Test
    fun `the key hash is salted by workspace and operation`() {
        // The same key value in two workspaces must be neither a collision nor
        // detectable as a match by anyone reading the table.
        val key = IdempotencyKey.of("shared-ci-job-1234")!!
        val workspace = WorkspaceId(UUID.randomUUID())
        val project = ProjectId(UUID.randomUUID())
        val other = WorkspaceId(UUID.randomUUID())

        val mine =
            IdempotencyKeys.hash(
                IdempotencyScope(workspace, project, IdempotentOperation.CREATE_INBOX),
                key,
            )
        val theirs =
            IdempotencyKeys.hash(
                IdempotencyScope(other, project, IdempotentOperation.CREATE_INBOX),
                key,
            )
        val otherOperation =
            IdempotencyKeys.hash(
                IdempotencyScope(workspace, project, IdempotentOperation.CREATE_API_KEY),
                key,
            )

        (mine == theirs) shouldBe false
        (mine == otherOperation) shouldBe false
        // And the raw key is not recoverable from, or present in, the digest.
        mine.contains("shared-ci-job-1234") shouldBe false
        mine.length shouldBe 64
    }
}
