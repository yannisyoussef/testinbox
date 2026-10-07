package email.testinbox.api.web

import email.testinbox.api.auth.AuthAttributes
import email.testinbox.api.auth.requireScope
import email.testinbox.application.query.StorageQueries
import email.testinbox.domain.tenant.ApiScope
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ADR-035 §13a: the authenticated key's own workspace storage.
 *
 * There is no workspace id in the path, the query, the body or a header. The
 * workspace is the one the credential belongs to, so nothing here can be
 * pointed at another tenant, and the response names no global figure: the
 * adapter's statement does not select one (§13d).
 */
@RestController
@RequestMapping("/v1/workspace")
class WorkspaceController(
    private val storageQueries: StorageQueries,
) {
    @GetMapping("/storage")
    fun storage(request: HttpServletRequest): ResponseEntity<*> {
        val key = AuthAttributes.principal(request)
        key.requireScope(ApiScope.MESSAGES_READ)
        return ResponseEntity.ok(StorageUsageDto.from(storageQueries.workspace(key.workspaceId)))
    }
}
