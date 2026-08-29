package email.testinbox.domain

import java.util.UUID

@JvmInline
value class WorkspaceId(val value: UUID) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class ProjectId(val value: UUID) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class ApiKeyId(val value: UUID) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class InboxId(val value: UUID) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class MessageId(val value: UUID) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class AttachmentId(val value: UUID) {
    override fun toString(): String = value.toString()
}
