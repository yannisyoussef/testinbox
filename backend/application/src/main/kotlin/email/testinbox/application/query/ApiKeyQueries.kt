package email.testinbox.application.query

import email.testinbox.application.port.ApiKeyCursor
import email.testinbox.application.port.ApiKeyRepository
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.tenant.ApiKey

/**
 * Read side of the management API. Every query is workspace-scoped from the
 * authenticated key, so a key belonging to another tenant is simply absent —
 * indistinguishable from one that never existed (anti-enumeration).
 */
class ApiKeyQueries(
    private val apiKeys: ApiKeyRepository,
) {
    fun get(
        actor: ApiKey,
        id: ApiKeyId,
    ): ApiKey? = apiKeys.findById(actor.workspaceId, id)

    fun list(
        actor: ApiKey,
        after: ApiKeyCursor?,
        limit: Int,
    ): List<ApiKey> = apiKeys.listPage(actor.workspaceId, after, limit)
}

/**
 * Page-size bounds for the management list endpoint. The default lives here
 * rather than as a literal on the controller parameter, so the contract has
 * one definition and `ApiKeyPagingTest` can hold it to the published one.
 */
object ApiKeyPaging {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200

    fun bound(limit: Int): Int = limit.coerceIn(1, MAX_LIMIT)

    fun cursorOf(key: ApiKey): ApiKeyCursor = ApiKeyCursor(key.createdAt, key.id)
}
