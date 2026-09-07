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

test("a full server wait window survives the ingress", { timeout: (config.waitWindowSeconds + 60) * 1000 }, async () => {
  const inbox = await newInbox("longpoll-window");
  const windowMs = config.waitWindowSeconds * 1000;

  const startedAt = Date.now();
  let failure;
  try {
    await inbox.waitForMessage({ timeoutMs: windowMs });
    failure = new Error("a wait on an empty inbox must not match");
  } catch (error) {
    failure = error;
  }
  const elapsed = Date.now() - startedAt;

  // The distinguishing assertion: a proxy that cut the request short produces
  // a transport/gateway error, never the SDK's own timeout type.
  assert.equal(
    failure.name,
    "TestInboxTimeoutError",
    `expected the server's own TIMEOUT answer after ${elapsed}ms, got ${failure.name}: ${failure.message}`,
  );
  // And it really was parked for the window, not answered immediately.
  assert.ok(
    elapsed >= windowMs * 0.9,
    `the wait returned after ${elapsed}ms, well short of the ${windowMs}ms window — ` +
      `the request was not actually parked for the full window`,
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
    // Wake-up is via LISTEN/NOTIFY, not the bounded degraded re-query. Anything
    // near or beyond the degraded interval would mean notifications are not
    // being delivered — the exact silent failure a transaction-mode pooler
    // causes (ADR-020, ADR-030 capability 2).
    const wakeMs = elapsed - parkMs;
    assert.ok(
      wakeMs < 5_000,
      `the parked wait took ${wakeMs}ms to resolve after delivery; LISTEN/NOTIFY is not ` +
        `waking waiters and the deployment is falling back to degraded re-query`,
    );
    console.log(`parked wait woke ${wakeMs}ms after SMTP delivery`);
  },
);
