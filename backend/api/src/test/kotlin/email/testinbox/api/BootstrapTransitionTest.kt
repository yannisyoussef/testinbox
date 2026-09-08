package email.testinbox.api

import email.testinbox.application.port.ApiKeyRepository
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * ADR-032 §8 through the real HTTP surface: the bootstrap credential can
 * initialise a fresh installation, closes its own window the moment a managed
 * administrator exists, and reopens only as break-glass.
 *
 * It runs in its own Spring context with its own bootstrap workspace, because
 * the shared suite base deliberately provisions a managed administrator into
 * the default workspace — which would have closed the window before the first
 * assertion.
 */
@TestPropertySource(
    properties = [
        "testinbox.bootstrap.api-key=bootstrap-transition-fixture-secret-value",
        "testinbox.bootstrap.workspace-id=00000000-0000-0000-0000-0000000000b7",
        "testinbox.bootstrap.project-id=00000000-0000-0000-0000-0000000000b7",
    ],
)
class BootstrapTransitionTest : ApiIntegrationTestBase() {
    companion object {
        const val OWN_BOOTSTRAP_KEY = "bootstrap-transition-fixture-secret-value"
        val OWN_WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b7")
    }

    @Autowired lateinit var apiKeys: ApiKeyRepository

    override val bootstrapWorkspaceId: WorkspaceId = WorkspaceId(OWN_WORKSPACE)

    /**
     * Deliberately provisions nothing. The base fixture mints a managed
     * administrator, and doing that here would close the window under test
     * before the first assertion ran.
     */
    override fun provisionFixtures() = Unit

    private val json = ObjectMapper()

    @Test
    fun `a fresh installation can initialise itself and then loses the bootstrap credential`() {
        // 1. Nothing managed exists yet: the bootstrap credential is how a new
        //    installation gets its first real key. Removing this would make a
        //    fresh install impossible to initialise.
        get("/v1/api-keys", OWN_BOOTSTRAP_KEY).statusCode shouldBe HttpStatus.OK

        // 2. It mints the first managed administrator.
        val created =
            post(
                "/v1/api-keys",
                """{"name":"first-admin","scopes":["api-keys:manage","inboxes:write","messages:read"]}""",
                OWN_BOOTSTRAP_KEY,
            )
        created.statusCode shouldBe HttpStatus.CREATED
        val managedKey = json.readTree(created.body!!)["key"].asString()
        val managedId = json.readTree(created.body!!)["apiKey"]["id"].asString()

        // 3. The window closes on the very next request — no restart, no
        //    configuration change, and therefore no way for an operator to
        //    forget to do it.
        get("/v1/api-keys", OWN_BOOTSTRAP_KEY).statusCode shouldBe HttpStatus.UNAUTHORIZED
        post("/v1/inboxes", "{}", OWN_BOOTSTRAP_KEY).statusCode shouldBe HttpStatus.UNAUTHORIZED
        get("/v1/api-keys", managedKey).statusCode shouldBe HttpStatus.OK

        // 4. Break-glass: revoking every managed administrator reopens it.
        //    This is why revoking the last admin key is allowed rather than
        //    refused — a compromised administrator must be revocable.
        delete("/v1/api-keys/$managedId", managedKey).statusCode shouldBe HttpStatus.NO_CONTENT
        get("/v1/api-keys", OWN_BOOTSTRAP_KEY).statusCode shouldBe HttpStatus.OK
    }

    @Test
    fun `the bootstrap credential is never visible to, or manageable through, the management API`() {
        val listed = get("/v1/api-keys", OWN_BOOTSTRAP_KEY)
        listed.statusCode shouldBe HttpStatus.OK

        val workspaceId = WorkspaceId(OWN_WORKSPACE)
        val stored =
            apiKeys.findBootstrapByHash(
                email.testinbox.application.Sha256
                    .hex(OWN_BOOTSTRAP_KEY),
            )!!
        stored.workspaceId shouldBe workspaceId
        stored.projectId shouldBe ProjectId(OWN_WORKSPACE)

        // Listing a credential the API cannot revoke would advertise a control
        // the caller does not have. Checked against the item ids rather than
        // by searching the body: the bootstrap key's id legitimately appears
        // as `createdByApiKeyId` on the first managed key it minted, and that
        // provenance is the point of recording it.
        json
            .readTree(listed.body!!)["items"]
            .toList()
            .none { it["id"].asString() == stored.id.value.toString() } shouldBe true
        get("/v1/api-keys/${stored.id.value}", OWN_BOOTSTRAP_KEY).statusCode shouldBe HttpStatus.NOT_FOUND
        delete("/v1/api-keys/${stored.id.value}", OWN_BOOTSTRAP_KEY).statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `the bootstrap credential cannot be presented in the managed credential format`() {
        // Its verifier hashes a different input and lives on a row the managed
        // lookup filters out, so there is no way to smuggle it through the
        // path that does not check the §8 window.
        apiKeys.findById(WorkspaceId(OWN_WORKSPACE), ApiKeyId(UUID.randomUUID())) shouldBe null
        get("/v1/api-keys", "ti_k1_${"a".repeat(16)}_${"b".repeat(52)}_cccc").statusCode shouldBe HttpStatus.UNAUTHORIZED
    }
}
