package email.testinbox.api.config

import email.testinbox.application.LimitsConfig
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.MessageNotifier
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.RateLimiter
import email.testinbox.application.port.TenantReader
import email.testinbox.application.port.WaitMetrics
import email.testinbox.application.port.WaitSlots
import email.testinbox.application.port.WorkspaceQuotaState
import email.testinbox.application.query.WorkspaceQueries
import email.testinbox.application.usecase.StreamWorkspaceEvents
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The console API's reads of the authenticated key's own workspace: the
 * tenancy and usage queries, and the event stream. Its own configuration so
 * [ApiWiring] stays the wiring of the core product.
 */
@Configuration
class ConsoleReadsWiring {
    /** Tenancy and usage reads of the key's own workspace; one `LimitsConfig`, the one the use cases enforce. */
    @Bean
    fun workspaceQueries(
        tenants: TenantReader,
        config: TestInboxConfig,
        limits: LimitsConfig,
        quotas: WorkspaceQuotaState,
        rateLimiter: RateLimiter,
        waitSlots: WaitSlots,
    ): WorkspaceQueries = WorkspaceQueries(tenants, config.mailDomain, limits, quotas, rateLimiter, waitSlots)

    /** The workspace event stream: a parked wait over the whole workspace, one slot per stream (ADR-027 §7). */
    @Bean
    @Suppress("LongParameterList") // a @Bean method's parameters are its dependencies: one per stream collaborator
    fun streamWorkspaceEvents(
        inboxes: InboxRepository,
        messages: MessageRepository,
        notifier: MessageNotifier,
        waitSlots: WaitSlots,
        limits: LimitsConfig,
        config: TestInboxConfig,
        clock: Clock,
        metrics: WaitMetrics,
    ): StreamWorkspaceEvents =
        StreamWorkspaceEvents(
            inboxes,
            messages,
            notifier,
            waitSlots,
            limits.quotas.maxConcurrentWaits,
            clock,
            config,
            metrics,
            visibilityLag = config.eventsVisibilityLag,
        )
}
