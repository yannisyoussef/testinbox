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

import { describe, expect, it } from "vitest";
import { TestInboxClient, TestInboxTimeoutError } from "../../src/index";

const baseUrl = process.env.TESTINBOX_BASE_URL;
const apiKey = process.env.TESTINBOX_API_KEY;

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
});
