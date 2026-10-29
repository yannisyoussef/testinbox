package email.testinbox.api.web

import email.testinbox.api.auth.AuthAttributes
import email.testinbox.api.auth.requireScope
import email.testinbox.application.query.StorageQueries
import email.testinbox.application.query.WorkspaceQueries
import email.testinbox.application.usecase.StreamWorkspaceEvents
import email.testinbox.application.usecase.WorkspaceEvent
import email.testinbox.domain.tenant.ApiScope
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.Writer
import java.time.Clock
import java.time.Duration

private val CONCURRENT_STREAM_RETRY_AFTER: Duration = Duration.ofSeconds(1)

/**
 * The authenticated key's own workspace (ADR-027 keys everything on it).
 *
 * There is no workspace id in the path, the query, the body or a header. The
 * workspace is the one the credential belongs to, so nothing here can be
 * pointed at another tenant, and no response names a global figure: the
 * adapters' statements do not select one (ADR-035 §13d).
 */
@RestController
@RequestMapping("/v1/workspace", "/account/product/workspace")
class WorkspaceController(
    private val storageQueries: StorageQueries,
    private val workspaceQueries: WorkspaceQueries,
    private val streamWorkspaceEvents: StreamWorkspaceEvents,
    private val objectMapper: tools.jackson.databind.ObjectMapper,
    private val clock: Clock,
) {
    @GetMapping
    fun get(request: HttpServletRequest): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        // An authenticated key references its workspace row (NOT NULL, no
        // cascade), so a miss is a server fault, not a client outcome.
        val view =
            checkNotNull(workspaceQueries.workspace(key.workspaceId, key.projectId)) { "workspace of an authenticated key is missing" }
        return ResponseEntity.ok(WorkspaceDto.from(view))
    }

    @GetMapping("/storage")
    fun storage(request: HttpServletRequest): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        return ResponseEntity.ok(StorageUsageDto.from(storageQueries.workspace(key.workspaceId)))
    }

    /** The policy the use cases enforce and the consumption derived from rows, in one read (ADR-027). */
    @GetMapping("/usage")
    fun usage(request: HttpServletRequest): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        return ResponseEntity.ok(WorkspaceUsageDto.from(workspaceQueries.usage(key.workspaceId)))
    }

    /**
     * Server-sent events for the workspace, one window at a time.
     *
     * The handler writes the response itself and holds the request thread
     * for the window, exactly as the wait endpoint does: the stream is a
     * parked wait over the whole workspace and occupies one of the
     * workspace's concurrent-wait slots (ADR-027 §7). The window is capped
     * at `wait-window-cap`, under every edge's request ceiling, after which
     * an `end` event is sent and the client reconnects with the last event id
     * it saw (`Last-Event-ID`); the next window re-reads the visibility
     * horizon behind it, so nothing committed is missed and a few events may
     * repeat (at-least-once across windows; clients deduplicate on `id`).
     */
    @GetMapping("/events", produces = ["text/event-stream"])
    fun events(
        @RequestHeader(name = "Last-Event-ID", required = false) lastEventId: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<*>? {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        val after =
            lastEventId?.takeIf { it.isNotBlank() }?.let {
                Cursors.decode(it)
                    ?: return Problems.respond(
                        Problems.of(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", "Malformed Last-Event-ID", request),
                    )
            }
        val stream =
            when (val open = streamWorkspaceEvents.open(key.workspaceId, after)) {
                is StreamWorkspaceEvents.Open.ConcurrentWaitLimitExceeded -> {
                    return Problems.respond(
                        Problems
                            .of(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "concurrent-wait-limit-exceeded",
                                "Concurrent wait limit exceeded",
                                "This workspace already holds all ${open.limit} concurrent wait slots",
                                request,
                            ).also { it.setProperty("limit", open.limit) },
                        retryAfter = CONCURRENT_STREAM_RETRY_AFTER,
                    )
                }

                is StreamWorkspaceEvents.Open.Opened -> {
                    open.stream
                }
            }

        response.status = HttpStatus.OK.value()
        response.contentType = "text/event-stream;charset=UTF-8"
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        // Proxies that buffer responses (nginx) would hold every event until the
        // window ends; this header is the conventional opt-out.
        response.setHeader("X-Accel-Buffering", "no")
        response.setHeader("X-Content-Type-Options", "nosniff")
        val out = response.writer
        stream.use {
            out.write("retry: 1000\n\n")
            out.flush()
            var lastWrite = clock.instant()
            while (true) {
                val now = clock.instant()
                if (!now.isBefore(stream.deadline)) break
                val events = stream.next(until = minOf(lastWrite.plus(KEEP_ALIVE), stream.deadline))
                // Bytes go out only for this workspace's events, or on the
                // keep-alive clock anchored to the last write — never because
                // the stream woke: a wake with nothing to say writes nothing.
                val afterWake = clock.instant()
                if (events.isNotEmpty()) {
                    events.forEach { write(out, it) }
                } else if (!afterWake.isBefore(lastWrite.plus(KEEP_ALIVE))) {
                    out.write(": keep-alive\n\n")
                } else {
                    continue
                }
                lastWrite = afterWake
                out.flush()
                // A client that went away would otherwise hold the slot to the deadline.
                if (out.checkError()) break
            }
            // `id:` as well as `data.resume`: an EventSource reconnects with the
            // last id it saw, and a window that delivered no message has none.
            val resume = Cursors.encode(stream.position.receivedAt, stream.position.id.value)
            out.write("id: $resume\nevent: end\ndata: {\"reason\":\"window\",\"resume\":\"$resume\"}\n\n")
            out.flush()
        }
        return null
    }

    private fun write(
        out: Writer,
        event: WorkspaceEvent,
    ) {
        when (event) {
            is WorkspaceEvent.MessageReceived -> {
                val message = event.message
                out.write("id: ${Cursors.encode(message.receivedAt, message.id.value)}\n")
                out.write("event: message.received\n")
                out.write("data: ${objectMapper.writeValueAsString(MessageEventDto.from(message))}\n\n")
            }

            is WorkspaceEvent.InboxChanged -> {
                out.write("event: inbox.${event.change.name.lowercase()}\n")
                out.write("data: ${objectMapper.writeValueAsString(InboxEventDto.from(event.inbox))}\n\n")
            }
        }
    }

    private companion object {
        /** A comment line this often keeps idle connections open through intermediaries. */
        val KEEP_ALIVE: Duration = Duration.ofSeconds(15)
    }
}
