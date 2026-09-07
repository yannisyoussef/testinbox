import { after, test } from "node:test";
import assert from "node:assert/strict";
import { TestInboxClient } from "@testinbox/client";
import { config } from "../src/env.mjs";
import { buildMime, sendRawSmtp } from "../src/smtp.mjs";

/**
 * Long-poll correctness through the REAL ingress (TI-DEPLOY-001 §13/§18).
 *
 * This is the environment test most likely to be missing and most likely to
 * matter. `waitForMessage` parks an HTTP request for up to the server's
 * wait-window cap. nginx's DEFAULT `proxy_read_timeout` is 60s — the same as
 * the cap — so a stock reverse proxy races every full-window wait and converts
 * a legitimate `200 {status: TIMEOUT}` into a 504. Nothing in the application
 * can detect that; only a request through the deployed proxy can.
 *
 * Two independent properties are proven here:
 *
 *   1. A request parked for the FULL server window survives the ingress and
 *      comes back as TestInbox's own timeout answer, not a gateway error.
 *   2. A request already parked before the message exists is woken by a later
 *      SMTP delivery — which exercises the ingress, the API, the session-scoped
 *      LISTEN connection, PostgreSQL, the gateway and the SDK together.
 */

const client = new TestInboxClient({ apiKey: config.apiKey, baseUrl: config.baseUrl });
const SENDER = "synthetic-longpoll@example.com";
const created = [];

after(async () => {
  for (const inbox of created) await inbox.delete().catch(() => {});
});

async function newInbox(aliasHint) {
  const inbox = await client.createInbox({ aliasHint, ttlSeconds: 900 });
  created.push(inbox);
  return inbox;
}

test(
  "ONE request parked for the full server window survives the ingress",
  { timeout: (config.waitWindowSeconds + 60) * 1000 },
  async () => {
    // Deliberately a raw fetch rather than the SDK. The SDK chains long polls
    // (client.ts caps each call at the server window and loops until the
    // caller's budget runs out), so an SDK-level assertion cannot tell one
    // 60s request from several shorter ones — and it is the single long
    // request that the proxy read timeout applies to. This makes it one
    // request by construction.
    const inbox = await newInbox("longpoll-window");
    const windowMs = config.waitWindowSeconds * 1000;

    const startedAt = Date.now();
    const response = await fetch(`${config.baseUrl}/v1/inboxes/${inbox.id}/messages/wait`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${config.apiKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({ timeoutSeconds: config.waitWindowSeconds }),
    });
    const body = await response.json();
    const elapsed = Date.now() - startedAt;

    // A proxy whose read timeout is at or below the wait window answers 502 or
    // 504 here, or resets the connection. TestInbox's own answer is a 200.
    assert.equal(
      response.status,
      200,
      `expected 200 {status: TIMEOUT} after ${elapsed}ms; the ingress returned ${response.status}. ` +
        `Its read timeout must exceed the ${config.waitWindowSeconds}s wait window.`,
    );
    assert.equal(body.status, "TIMEOUT", `unexpected wait result: ${JSON.stringify(body)}`);
    assert.ok(
      elapsed >= windowMs * 0.9,
      `the request returned after ${elapsed}ms, short of the ${windowMs}ms window — it was not parked for the full window`,
    );
  },
);

test("the SDK surfaces a window timeout as its own error type, not a transport failure", async () => {
  // The ergonomics half of the test above: whatever the ingress did, a caller
  // sees TestInboxTimeoutError. A 502/504 would surface as TestInboxApiError
  // and an aborted socket as a plain TestInboxError, so neither can pass here.
  const inbox = await newInbox("longpoll-sdk");
  await assert.rejects(
    () => inbox.waitForMessage({ timeoutMs: 3_000 }),
    (error) => error.name === "TestInboxTimeoutError",
  );
});

test(
  "a request already parked is woken by a later SMTP delivery",
  { timeout: (config.parkedWaitSeconds + 120) * 1000 },
  async () => {
    const inbox = await newInbox("longpoll-parked");
    const token = Math.random().toString(36).slice(2, 10);
    const parkMs = config.parkedWaitSeconds * 1000;

    // Start the wait BEFORE the message exists. The budget is generous; what
    // is being measured is that the answer arrives only after the delivery.
    const startedAt = Date.now();
    const waiting = inbox.waitForMessage({
      subjectContains: token,
      timeoutMs: (config.waitWindowSeconds + 60) * 1000,
    });

    // A bounded delay rather than a synchronisation primitive: the server-side
    // signal that a request is parked (`wait_requests_active`) is on the
    // private management port and is deliberately not reachable from here. The
    // delay is load-bearing — the elapsed-time assertion below fails if the
    // request was answered from the fast path instead of being parked.
    await new Promise((resolve) => setTimeout(resolve, parkMs));

    const subject = `Parked wait ${token}`;
    await sendRawSmtp({
      host: config.smtpHost,
      port: config.smtpPort,
      from: SENDER,
      to: inbox.address,
      raw: buildMime({
        from: SENDER,
        to: inbox.address,
        subject,
        text: `parked ${token}`,
        html: `<p>parked ${token}</p>`,
      }),
    });

    const message = await waiting;
    const elapsed = Date.now() - startedAt;

    assert.equal(message.subject, subject);
    assert.ok(
      elapsed >= parkMs,
      `the wait resolved after ${elapsed}ms but the message was only sent at ${parkMs}ms — ` +
        `it cannot have been a parked request`,
    );
    // Wake-up must be via LISTEN/NOTIFY, not the bounded degraded re-query.
    // The threshold is BELOW the degraded ticker (PgListenNotifier's
    // degradedInterval, 1s) on purpose: a database behind a transaction-mode
    // pooler accepts LISTEN and never delivers, and the waiter then still
    // resolves in ~1-2s off the fallback. A 5s threshold would pass for
    // exactly the deployment ADR-030 capability 2 exists to rule out.
    const wakeMs = elapsed - parkMs;
    assert.ok(
      wakeMs < 750,
      `the parked wait took ${wakeMs}ms to resolve after delivery. That is at or beyond the ` +
        `degraded re-query interval, so notifications are NOT reaching waiters — the signature ` +
        `of a transaction-mode pooler in front of PostgreSQL (ADR-020, ADR-030 capability 2).`,
    );
    console.log(`parked wait woke ${wakeMs}ms after SMTP delivery`);
  },
);
