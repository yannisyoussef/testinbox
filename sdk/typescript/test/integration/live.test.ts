/**
 * Live integration test against a real TestInbox deployment.
 *
 * Runs ONLY when both TESTINBOX_BASE_URL and TESTINBOX_API_KEY are set
 * (otherwise the suite is skipped). Execute with `npm run test:integration`.
 *
 * Exercises: createInbox -> waitForMessage with a short budget on an empty
 * inbox (expects TestInboxTimeoutError, per ADR-020 the server TIMEOUT status
 * chains and only budget exhaustion raises) -> delete.
 */

import { existsSync } from "node:fs";
import { writeFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { TestInboxClient, TestInboxStorageLimitExceededError, TestInboxTimeoutError } from "../../src/index";

const baseUrl = process.env.TESTINBOX_BASE_URL;
const apiKey = process.env.TESTINBOX_API_KEY;
/** Optional: an inbox the harness pre-loaded with storage refusals (ADR-035 §13c). */
const refusedInboxId = process.env.TESTINBOX_REFUSED_INBOX_ID;
/** Optional: the harness's file handshake for recording a refusal on demand (see TsSdkIntegrationTest). */
const refusalRequestFile = process.env.TESTINBOX_REFUSAL_REQUEST_FILE;
const refusalDoneFile = process.env.TESTINBOX_REFUSAL_DONE_FILE;

describe.skipIf(!baseUrl || !apiKey)("live TestInbox API", () => {
  it(
    "create -> wait (short timeout on empty inbox) -> delete",
    async () => {
      const client = new TestInboxClient({ apiKey: apiKey!, baseUrl: baseUrl! });

      const inbox = await client.createInbox({ ttlSeconds: 300, aliasHint: "sdk-it" });
      expect(inbox.id).toBeTruthy();
      expect(inbox.address).toContain("@");
      expect(inbox.state).toBe("ACTIVE");
      expect(inbox.expiresAt.getTime()).toBeGreaterThan(Date.now());

      try {
        // Nothing was sent to this inbox: a short overall budget must expire
        // with the typed timeout error carrying diagnostics.
        const error = await inbox
          .waitForMessage({ timeoutMs: 2_000, subjectContains: "will-never-match" })
          .then(
            () => {
              throw new Error("expected waitForMessage to time out on an empty inbox");
            },
            (e: unknown) => e,
          );
        expect(error).toBeInstanceOf(TestInboxTimeoutError);
        const timeout = error as TestInboxTimeoutError;
        expect(timeout.elapsedMs).toBeGreaterThanOrEqual(2_000);
        expect(timeout.arrivedButUnmatchedCount).toBe(0);
        expect(timeout.parseFailedCount).toBe(0);

        const messages = await inbox.listMessages();
        expect(messages).toEqual([]);
      } finally {
        await inbox.delete();
      }
    },
    60_000,
  );

  it.skipIf(!refusedInboxId)(
    "an inbox fetched with refusals: the seeded cursor observes only later ones, a lower explicit boundary never re-surfaces them, the opt-out is the legacy wait",
    async () => {
      const client = new TestInboxClient({ apiKey: apiKey!, baseUrl: baseUrl! });
      const inbox = await client.getInbox(refusedInboxId!);
      expect(inbox.storageRefusalCount).toBeGreaterThan(0);
      expect(inbox.storageRefusalCursor).toBe(inbox.storageRefusalCount);
      expect(inbox.lastStorageRefusalReason).toBe("INBOX_LIMIT");

      const expectTimeout = async (options: Parameters<typeof inbox.waitForMessage>[0]) => {
        const error = await inbox.waitForMessage({ timeoutMs: 2_000, subjectContains: "will-never-match", ...options }).then(
          () => {
            throw new Error("expected the wait to time out");
          },
          (e: unknown) => e,
        );
        expect(error).toBeInstanceOf(TestInboxTimeoutError);
      };
      // Seeded from the representation: the refusals already recorded are not re-surfaced.
      await expectTimeout({});
      // An explicit boundary BELOW the cursor is overridden by the cursor (monotonic): still no 409.
      await expectTimeout({ afterStorageRefusalCount: 0 });
      expect(inbox.storageRefusalCursor).toBe(inbox.storageRefusalCount);
      // The opt-out sends no boundary: the pre-ADR-035 contract.
      await expectTimeout({ observeStorageRefusals: false });
    },
    30_000,
  );

  it.skipIf(!refusalRequestFile || !refusalDoneFile)(
    "a refusal recorded while this object exists: the default wait surfaces the typed error, with the cursor already advanced (ADR-035 §13c)",
    async () => {
      const client = new TestInboxClient({ apiKey: apiKey!, baseUrl: baseUrl! });
      const inbox = await client.createInbox({ ttlSeconds: 300, aliasHint: "sdk-refused" });
      try {
        expect(inbox.storageRefusalCursor).toBe(0);
        // Ask the harness to record a real §6a refusal for THIS inbox, and wait until it has.
        await writeFile(refusalRequestFile!, inbox.id);
        const until = Date.now() + 60_000;
        while (!existsSync(refusalDoneFile!)) {
          if (Date.now() > until) throw new Error("the harness never recorded the refusal");
          await new Promise((r) => setTimeout(r, 50));
        }

        let cursorInHandler: number | undefined;
        const error = await inbox.waitForMessage({ timeoutMs: 5_000, subjectContains: "will-never-match" }).then(
          () => {
            throw new Error("expected the typed storage-limit error");
          },
          (e: unknown) => {
            cursorInHandler = inbox.storageRefusalCursor;
            return e;
          },
        );
        expect(error).toBeInstanceOf(TestInboxStorageLimitExceededError);
        const typed = error as TestInboxStorageLimitExceededError;
        expect(typed.status).toBe(409);
        expect(typed.problemType).toBe("https://testinbox.email/problems/storage-limit-exceeded");
        expect(typed.inboxId).toBe(inbox.id);
        expect(typed.refusalReason).toBe("INBOX_LIMIT");
        expect(typed.afterStorageRefusalCount).toBe(0);
        expect(typed.storageRefusalCount).toBe(1);
        expect(typed.quota).toBe("STORED_BYTES");
        expect(typed.limit).toBe(inbox.storage?.limitBytes);
        expect(cursorInHandler).toBe(1);
        expect(inbox.storageRefusalCount).toBe(0); // the snapshot stays what the create response said

        // The next default wait observes only later refusals: the window expires normally.
        const next = await inbox.waitForMessage({ timeoutMs: 2_000, subjectContains: "will-never-match" }).then(
          () => {
            throw new Error("expected the next wait to time out");
          },
          (e: unknown) => e,
        );
        expect(next).toBeInstanceOf(TestInboxTimeoutError);
        expect(inbox.storageRefusalCursor).toBe(1);
      } finally {
        await inbox.delete();
      }
    },
    90_000,
  );

  it(
    "getWorkspaceStorage returns the caller's own five-member StorageUsage",
    async () => {
      const client = new TestInboxClient({ apiKey: apiKey!, baseUrl: baseUrl! });
      const usage = await client.getWorkspaceStorage();
      expect(Object.keys(usage).sort()).toEqual(["availableBytes", "limitBytes", "overLimit", "reservedBytes", "storedBytes"]);
      expect(usage.limitBytes).toBeGreaterThan(0);
      expect(usage.availableBytes).toBeGreaterThanOrEqual(0);
      expect(usage.overLimit).toBe(usage.storedBytes + usage.reservedBytes > usage.limitBytes);
    },
    30_000,
  );
});
