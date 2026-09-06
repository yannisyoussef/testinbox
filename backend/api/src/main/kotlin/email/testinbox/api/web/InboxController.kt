package email.testinbox.api.web

import email.testinbox.api.auth.AuthAttributes
import email.testinbox.api.auth.requireScope
import email.testinbox.application.query.InboxQueries
import email.testinbox.application.query.MessageQueries
import email.testinbox.application.usecase.CreateInbox
import email.testinbox.application.usecase.DeleteInbox
import email.testinbox.application.usecase.WaitForMessage
import email.testinbox.domain.InboxId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.limits.QuotaExceeded
import email.testinbox.domain.message.HeaderMatcher
import email.testinbox.domain.message.MessageMatcher
import email.testinbox.domain.tenant.ApiScope
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val CONCURRENT_WAIT_RETRY_AFTER: java.time.Duration = java.time.Duration.ofSeconds(1)

@RestController
@RequestMapping("/v1/inboxes")
class InboxController(
    private val createInbox: CreateInbox,
    private val deleteInbox: DeleteInbox,
    private val waitForMessage: WaitForMessage,
    private val inboxQueries: InboxQueries,
    private val messageQueries: MessageQueries,
) {
    @PostMapping
    fun create(
        @RequestBody body: CreateInboxRequest,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.INBOXES_WRITE)
        val mode =
            when (body.addressMode) {
                null, "GENERATED" -> {
                    AddressMode.GENERATED
                }

                "EXACT" -> {
                    AddressMode.EXACT
                }

                else -> {
                    return Problems.respond(
                        Problems.of(
                            HttpStatus.BAD_REQUEST,
                            "invalid-request",
                            "Invalid request",
                            "Unknown addressMode '${body.addressMode}'",
                            request,
                        ),
                    )
                }
            }
        val result =
            createInbox.execute(
                CreateInbox.Command(
                    workspaceId = key.workspaceId,
                    projectId = key.projectId,
                    addressMode = mode,
                    ttlSeconds = body.ttlSeconds,
                    aliasHint = body.aliasHint,
                    localPart = body.localPart,
                ),
            )
        return when (result) {
            is CreateInbox.Result.Created -> {
                ResponseEntity.status(HttpStatus.CREATED).body(InboxDto.from(result.inbox))
            }

            is CreateInbox.Result.QuotaRejected -> {
                quotaProblem(result.exceeded, request)
            }

            is CreateInbox.Result.InvalidRequest -> {
                Problems.respond(
                    Problems.of(
                        HttpStatus.BAD_REQUEST,
                        "invalid-request",
                        "Invalid request",
                        result.reason,
                        request,
                    ),
                )
            }

            is CreateInbox.Result.AddressConflict -> {
                val problem =
                    Problems.of(
                        HttpStatus.CONFLICT,
                        "address-already-reserved",
                        "Address already reserved",
                        "localPart '${result.localPart}' is reserved or in cooldown",
                        request,
                    )
                result.availableAt?.let {
                    problem.setProperty(
                        "retryAfterSeconds",
                        maxOf(0, Duration.between(Instant.now(), it).seconds),
                    )
                }
                Problems.respond(problem)
            }
        }
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        val inbox =
            inboxQueries.get(key.workspaceId, InboxId(id))
                ?: return inboxNotFound(request)
        return ResponseEntity.ok(InboxDto.from(inbox))
    }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.INBOXES_WRITE)
        return when (deleteInbox.execute(key.workspaceId, InboxId(id))) {
            DeleteInbox.Result.Deleted -> ResponseEntity.noContent().build<Void>()
            DeleteInbox.Result.NotFound -> inboxNotFound(request)
        }
    }

    @GetMapping("/{id}/messages")
    fun listMessages(
        @PathVariable id: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        inboxQueries.get(key.workspaceId, InboxId(id)) ?: return inboxNotFound(request)
        val boundedLimit = limit.coerceIn(1, 200)
        val after =
            cursor?.let {
                Cursors.decode(it)
                    ?: return Problems.respond(
                        Problems.of(
                            HttpStatus.BAD_REQUEST,
                            "invalid-request",
                            "Invalid request",
                            "Malformed cursor",
                            request,
                        ),
                    )
            }
        val page = messageQueries.listPage(key.workspaceId, InboxId(id), after, boundedLimit)
        val nextCursor =
            page.lastOrNull()?.takeIf { page.size == boundedLimit }?.let {
                Cursors.encode(it.receivedAt, it.id.value)
            }
        return ResponseEntity.ok(MessagePageDto(page.map(MessageDto::from), nextCursor))
    }

    @PostMapping("/{id}/messages/wait")
    fun wait(
        @PathVariable id: UUID,
        @RequestBody body: WaitRequestDto,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        val timeoutSeconds =
            body.timeoutSeconds
                ?: return Problems.respond(
                    Problems.of(
                        HttpStatus.BAD_REQUEST,
                        "invalid-request",
                        "Invalid request",
                        "timeoutSeconds is required",
                        request,
                    ),
                )
        val matcherDto = body.matcher ?: MessageMatcherDto()
        val headerMatchers =
            matcherDto.headers.orEmpty().map {
                val name =
                    it.name
                        ?: return Problems.respond(
                            Problems.of(
                                HttpStatus.BAD_REQUEST,
                                "invalid-request",
                                "Invalid request",
                                "header matcher requires a name",
                                request,
                            ),
                        )
                HeaderMatcher(name, it.value)
            }
        val result =
            waitForMessage.execute(
                WaitForMessage.Command(
                    workspaceId = key.workspaceId,
                    inboxId = InboxId(id),
                    matcher =
                        MessageMatcher(
                            from = matcherDto.from,
                            subjectContains = matcherDto.subjectContains,
                            subjectEquals = matcherDto.subjectEquals,
                            headers = headerMatchers,
                        ),
                    timeoutSeconds = timeoutSeconds,
                ),
            )
        return when (result) {
            is WaitForMessage.Result.Matched -> {
                ResponseEntity.ok(
                    WaitResultDto(
                        status = "MATCHED",
                        message = MessageDto.from(result.message),
                        elapsedMs = result.elapsedMs,
                        arrivedButUnmatchedCount = null,
                        parseFailedCount = null,
                    ),
                )
            }

            is WaitForMessage.Result.Timeout -> {
                // ADR-020: window expiry is a successful query with a negative answer — never 408.
                ResponseEntity.ok(
                    WaitResultDto(
                        status = "TIMEOUT",
                        message = null,
                        elapsedMs = result.elapsedMs,
                        arrivedButUnmatchedCount = result.arrivedButUnmatchedCount,
                        parseFailedCount = result.parseFailedCount,
                    ),
                )
            }

            WaitForMessage.Result.InboxGone -> {
                Problems.respond(
                    Problems.of(
                        HttpStatus.GONE,
                        "inbox-gone",
                        "Inbox gone",
                        "The inbox is no longer active",
                        request,
                    ),
                )
            }

            WaitForMessage.Result.InboxNotFound -> {
                inboxNotFound(request)
            }

            is WaitForMessage.Result.ConcurrentWaitLimitExceeded -> {
                // Rate-shaped, not state-shaped: a slot frees with time (ADR-027 §8).
                Problems.respond(
                    Problems
                        .of(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "concurrent-wait-limit-exceeded",
                            "Concurrent wait limit exceeded",
                            "This workspace already holds all ${result.limit} concurrent wait slots",
                            request,
                        ).also { it.setProperty("limit", result.limit) },
                    retryAfter = CONCURRENT_WAIT_RETRY_AFTER,
                )
            }

            is WaitForMessage.Result.InvalidRequest -> {
                Problems.respond(
                    Problems.of(
                        HttpStatus.BAD_REQUEST,
                        "invalid-request",
                        "Invalid request",
                        result.reason,
                        request,
                    ),
                )
            }
        }
    }

    /**
     * Quota exhaustion is 409, never 429 (ADR-027 §8): waiting does not help,
     * so advertising a retry would send SDKs and CI scripts into a loop that
     * cannot succeed. The caller must free capacity.
     */
    private fun quotaProblem(
        exceeded: QuotaExceeded,
        request: HttpServletRequest,
    ): ResponseEntity<*> =
        Problems.respond(
            Problems
                .of(
                    HttpStatus.CONFLICT,
                    "quota-exceeded",
                    "Quota exceeded",
                    "Workspace quota ${exceeded.dimension.name} exhausted (${exceeded.current}/${exceeded.limit})",
                    request,
                ).also {
                    it.setProperty("quota", exceeded.dimension.name)
                    it.setProperty("limit", exceeded.limit)
                    it.setProperty("current", exceeded.current)
                },
        )

    private fun inboxNotFound(request: HttpServletRequest): ResponseEntity<*> =
        Problems.respond(
            Problems.of(
                HttpStatus.NOT_FOUND,
                "inbox-not-found",
                "Inbox not found",
                null,
                request,
            ),
        )
}
