package email.testinbox.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-023 / docs/sdk/principles.md: the blocking facades exist for plain-Java
 * callers, so "usable from Java" has to be compiled, not asserted in prose.
 *
 * This file is written in Java on purpose. It caught a real break: `ApiScope`
 * was a `@JvmInline value class`, which compiles its constants to a private
 * field plus a name-mangled accessor (`getINBOXES_WRITE-XqM_LWM`) and its
 * constructor to `box-impl` — none of them legal Java identifiers. There was
 * no expression a Java caller could write to obtain an `ApiScope`, so
 * `apiKeys.createBlocking(List<ApiScope>, ...)` was uncallable. A Kotlin test
 * could never have noticed.
 */
class JavaInteropTest {

    @Test
    void scopeConstantsAreReachableFromJava() {
        ApiScope write = ApiScope.INBOXES_WRITE;
        ApiScope read = ApiScope.MESSAGES_READ;
        ApiScope manage = ApiScope.API_KEYS_MANAGE;

        assertEquals("inboxes:write", write.getWire());
        assertEquals("messages:read", read.getWire());
        assertEquals("api-keys:manage", manage.toString());
    }

    @Test
    void aScopeCanBeConstructedForAValueThisSdkPredates() {
        // Forward compatibility has to work from Java too: a server may grant
        // a scope newer than this artifact.
        ApiScope future = new ApiScope("inboxes:delete");
        assertEquals("inboxes:delete", future.getWire());
        assertEquals(new ApiScope("inboxes:delete"), future);
    }

    @Test
    void scopeListsCompileAgainstTheCreateSignature() {
        // The whole point: this is the expression a Java caller writes, and it
        // must type-check. It is never executed against a server — compiling
        // is the assertion.
        List<ApiScope> scopes = List.of(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ);
        assertEquals(2, scopes.size());
        assertTrue(scopes.contains(ApiScope.INBOXES_WRITE));

        TestInboxClient client = new TestInboxClient("tk_java_interop", "http://localhost:1");
        ApiKeys keys = client.getApiKeys();
        assertNotNull(keys);

        // Reference the blocking overloads without invoking them, so the
        // signatures stay Java-callable.
        Runnable createCall = () -> keys.createBlocking(scopes, "java-ci", Duration.ofHours(1));
        Runnable listCall = () -> keys.listBlocking(null, 10);
        Runnable getCall = () -> keys.getBlocking("id");
        Runnable revokeCall = () -> keys.revokeBlocking("id");
        assertNotNull(createCall);
        assertNotNull(listCall);
        assertNotNull(getCall);
        assertNotNull(revokeCall);
    }
}
