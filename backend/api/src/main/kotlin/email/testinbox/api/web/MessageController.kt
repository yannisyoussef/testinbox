package email.testinbox.api.web

import email.testinbox.api.auth.AuthAttributes
import email.testinbox.api.auth.requireScope
import email.testinbox.application.query.MessageQueries
import email.testinbox.domain.AttachmentId
import email.testinbox.domain.MessageId
import email.testinbox.domain.tenant.ApiScope
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1/messages", "/account/product/messages")
class MessageController(
    private val messageQueries: MessageQueries,
) {
    /**
     * Messages across every serving inbox of the workspace. Newest first by
     * default (a feed); `order=asc` walks forward from a cursor, which is how
     * a client catches up after a gap.
     */
    @GetMapping
    fun listWorkspace(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        @RequestParam(required = false, defaultValue = "desc") order: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        val descending =
            when (order.lowercase()) {
                "desc" -> true

                "asc" -> false

                else -> return Problems.respond(
                    Problems.of(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", "order must be asc or desc", request),
                )
            }
        val boundedLimit = limit.coerceIn(1, 200)
        val position =
            cursor?.let {
                Cursors.decode(it)
                    ?: return Problems.respond(
                        Problems.of(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", "Malformed cursor", request),
                    )
            }
        val page = messageQueries.listWorkspacePage(key.workspaceId, position, boundedLimit, descending)
        val nextCursor =
            page.lastOrNull()?.takeIf { page.size == boundedLimit }?.let { Cursors.encode(it.receivedAt, it.id.value) }
        return ResponseEntity.ok(MessagePageDto(page.map(MessageDto::from), nextCursor))
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        val message =
            messageQueries.get(key.workspaceId, MessageId(id)) ?: return messageNotFound(request)
        return ResponseEntity.ok(MessageDto.from(message))
    }

    @GetMapping("/{id}/raw")
    fun raw(
        @PathVariable id: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        val bytes =
            messageQueries.rawMime(key.workspaceId, MessageId(id)) ?: return messageNotFound(request)
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("message/rfc822"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"raw.eml\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes)
    }

    @GetMapping("/{id}/attachments")
    fun listAttachments(
        @PathVariable id: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        val attachments =
            messageQueries.attachments(key.workspaceId, MessageId(id))
                ?: return messageNotFound(request)
        return ResponseEntity.ok(
            attachments.map { AttachmentMetaDto(it.id.value, it.fileName, it.contentType, it.sizeBytes) },
        )
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    fun attachment(
        @PathVariable id: UUID,
        @PathVariable attachmentId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        val (meta, bytes) =
            messageQueries.attachmentBytes(key.workspaceId, MessageId(id), AttachmentId(attachmentId))
                ?: return Problems.respond(
                    Problems.of(
                        HttpStatus.NOT_FOUND,
                        "attachment-not-found",
                        "Attachment not found",
                        null,
                        request,
                    ),
                )
        // Threat model: opaque download only — sanitized filename, nosniff,
        // restrictive CSP; never rendered inline (docs/security/threat-model.md).
        val safeName =
            (meta.fileName ?: "attachment")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .replace(Regex("\\.{2,}"), "_")
                .ifBlank { "attachment" }
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$safeName\"")
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; sandbox")
            .body(bytes)
    }

    private fun messageNotFound(request: HttpServletRequest): ResponseEntity<*> =
        Problems.respond(
            Problems.of(
                HttpStatus.NOT_FOUND,
                "message-not-found",
                "Message not found",
                null,
                request,
            ),
        )
}
