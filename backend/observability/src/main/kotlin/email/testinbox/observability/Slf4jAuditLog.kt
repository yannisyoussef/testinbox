package email.testinbox.observability

import email.testinbox.application.port.AuditEvent
import email.testinbox.application.port.AuditLog
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Writes audit events to a dedicated logger, `testinbox.audit`, so a
 * deployment can route them somewhere with different retention and access
 * control from application logs without filtering on message text.
 *
 * The correlation id is read from the MDC rather than threaded through the
 * application layer: it is a transport concern, and putting it in the event
 * type would mean every use case had to carry an HTTP notion inward.
 *
 * Fields are emitted as key=value pairs on one line. There is no formatting
 * path that could interpolate a secret, because [AuditEvent] has no field that
 * holds one (see the port's contract).
 */
class Slf4jAuditLog : AuditLog {
    override fun record(event: AuditEvent) {
        val correlationId = MDC.get(CORRELATION_KEY)
        val common =
            "at=${event.at} workspaceId=${event.workspaceId.value} " +
                "actorApiKeyId=${event.actorApiKeyId?.value ?: "-"} correlationId=${correlationId ?: "-"}"
        when (event) {
            is AuditEvent.ApiKeyCreated -> {
                log.info(
                    "event=api_key.created {} apiKeyId={} publicId={} name={} scopes={} expiresAt={}",
                    common,
                    event.apiKeyId.value,
                    event.publicId,
                    event.name ?: "-",
                    event.scopes
                        .map { it.wire }
                        .sorted()
                        .joinToString(","),
                    event.expiresAt ?: "-",
                )
            }

            is AuditEvent.ApiKeyRevoked -> {
                log.info(
                    "event=api_key.revoked {} apiKeyId={} alreadyRevoked={}",
                    common,
                    event.apiKeyId.value,
                    event.alreadyRevoked,
                )
            }

            is AuditEvent.BootstrapSuperseded -> {
                // WARN, not INFO: the credential still being presented after a
                // managed administrator exists means something in an operator's
                // estate is still shipping the bootstrap secret.
                log.warn("event=bootstrap.superseded {}", common)
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger("testinbox.audit")
        const val CORRELATION_KEY = "correlationId"
    }
}
