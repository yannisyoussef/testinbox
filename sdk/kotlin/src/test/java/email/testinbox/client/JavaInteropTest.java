package email.testinbox.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

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

    @Test
    void storageTypesAndConstantsAreReachableFromJava() {
        // ADR-035 §13 (TI-STORAGE-005): the storage surface has to be usable from plain Java.
        StorageUsage usage = new StorageUsage(512L, 100L, 20L, 392L, false);
        assertEquals(512L, usage.getLimitBytes());
        assertEquals(100L, usage.getStoredBytes());
        assertEquals(20L, usage.getReservedBytes());
        assertEquals(392L, usage.getAvailableBytes());
        assertEquals(false, usage.isOverLimit());

        StorageRefusalReason inbox = StorageRefusalReason.INBOX_LIMIT;
        StorageRefusalReason workspace = StorageRefusalReason.WORKSPACE_LIMIT;
        StorageRefusalReason service = StorageRefusalReason.SERVICE_CAPACITY;
        assertEquals("INBOX_LIMIT", inbox.getWire());
        assertEquals("WORKSPACE_LIMIT", workspace.toString());
        assertEquals(new StorageRefusalReason("SERVICE_CAPACITY"), service);
        // Forward compatibility from Java too: a reason this SDK predates is constructible and comparable.
        StorageRefusalReason future = new StorageRefusalReason("FUTURE_LIMIT_KIND");
        assertEquals("FUTURE_LIMIT_KIND", future.getWire());
    }

    @Test
    void theWaitAndStorageSignaturesCompileFromJava() {
        TestInboxClient client = new TestInboxClient("tk_java_interop", "http://localhost:1");
        MessageMatcher matcher = MessageMatcher.builder().subjectContains("Verify").build();

        // Referenced, never invoked: compiling is the assertion. Old API...
        Runnable oldSignature = () -> client.getInboxBlocking("id").awaitMessageBlocking(Duration.ofSeconds(30), matcher);
        Runnable oldDefault = () -> client.getInboxBlocking("id").awaitMessageBlocking(Duration.ofSeconds(30));
        // ...the explicit boundary (a boxed Long, so null means "none")...
        Runnable explicitBoundary = () -> client.getInboxBlocking("id").awaitMessageBlocking(Duration.ofSeconds(30), matcher, 7L);
        // ...the opt-out that restores the legacy wait...
        Runnable optOut = () -> client.getInboxBlocking("id").awaitMessageBlocking(Duration.ofSeconds(30), matcher, null, false);
        // ...and the workspace storage query.
        Runnable workspaceStorage = () -> {
            StorageUsage usage = client.getWorkspaceStorageBlocking();
            assertNotNull(usage);
        };
        assertNotNull(oldSignature);
        assertNotNull(oldDefault);
        assertNotNull(explicitBoundary);
        assertNotNull(optOut);
        assertNotNull(workspaceStorage);
    }

    @Test
    void inboxSnapshotsAndTheCursorAreReadableFromJava() {
        // The accessors a Java caller reads after createInbox/getInbox; nullable when the server predates them.
        Runnable read = () -> {
            Inbox inbox = new TestInboxClient("tk_java_interop", "http://localhost:1").getInboxBlocking("id");
            StorageUsage storage = inbox.getStorage();
            Long count = inbox.getStorageRefusalCount();
            Instant lastAt = inbox.getLastStorageRefusalAt();
            StorageRefusalReason reason = inbox.getLastStorageRefusalReason();
            Long cursor = inbox.getStorageRefusalCursor();
            assertNull(storage);
            assertNull(count);
            assertNull(lastAt);
            assertNull(reason);
            assertNull(cursor);
        };
        assertNotNull(read);
    }

    @Test
    void theTypedStorageExceptionExposesEveryFieldAsAGetter() {
        TestInboxStorageLimitExceededException e =
            new TestInboxStorageLimitExceededException(
                "refused",
                "corr",
                "inbox-id",
                StorageRefusalReason.INBOX_LIMIT,
                2L,
                3L,
                Instant.parse("2026-10-07T12:00:30Z"),
                "STORED_BYTES",
                512L,
                600L);
        assertEquals(409, e.getStatus());
        assertEquals("https://testinbox.email/problems/storage-limit-exceeded", e.getProblemType());
        assertEquals("inbox-id", e.getInboxId());
        assertEquals(StorageRefusalReason.INBOX_LIMIT, e.getRefusalReason());
        assertEquals(2L, e.getAfterStorageRefusalCount());
        assertEquals(3L, e.getStorageRefusalCount());
        assertEquals(Instant.parse("2026-10-07T12:00:30Z"), e.getLastStorageRefusalAt());
        assertEquals("STORED_BYTES", e.getQuota());
        assertEquals(512L, e.getLimit());
        assertEquals(600L, e.getCurrent());
        // SERVICE_CAPACITY: the reason only.
        TestInboxStorageLimitExceededException capacity =
            new TestInboxStorageLimitExceededException(
                "refused", null, "inbox-id", StorageRefusalReason.SERVICE_CAPACITY, 0L, 1L, Instant.EPOCH, null, null, null);
        assertNull(capacity.getQuota());
        assertNull(capacity.getLimit());
        assertNull(capacity.getCurrent());
        assertTrue(e instanceof TestInboxException);
    }
}
