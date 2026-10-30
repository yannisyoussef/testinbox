import { after, test } from "node:test";
import assert from "node:assert/strict";
import { TestInboxClient } from "@testinbox/client";
import { config } from "../src/env.mjs";
import { buildMime, sendRawSmtp } from "../src/smtp.mjs";

/**
 * The workspace event stream through the REAL ingress.
 *
 * `GET /v1/workspace/events` is a response that stays open for a whole
 * wait window and carries each event the moment it happens. An edge that
 * buffers responses (nginx's default without `X-Accel-Buffering`, or a
 * Cloudflare/Traefik hop configured to) still passes every in-process test
 * — embedded Tomcat does not buffer — and still returns a 200 here: the
 * events simply arrive together when the window ends. The invariant proven
 * is therefore *delivery before the window ends*, measured from the SMTP
 * `250`, whichever edge is in front (CLAUDE.md: assert the invariant, not an
 * edge's mechanism).
 */

const client = new TestInboxClient({ apiKey: config.apiKey, baseUrl: config.baseUrl });
const SENDER = "synthetic-events@example.com";
const created = [];

after(async () => {
  for (const inbox of created) await inbox.delete().catch(() => {});
});

/** Reads SSE frames from a fetch body; resolves `events` as they complete. */
function sseReader(body, onEvent) {
  const decoder = new TextDecoder();
  let buffer = "";
  let current = {};
  const reader = body.getReader();
  (async () => {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      buffer += decoder.decode(value, { stream: true });
      let nl;
      while ((nl = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, nl).replace(/\r$/, "");
        buffer = buffer.slice(nl + 1);
        if (line === "") {
          if (current.event || current.data) onEvent(current);
          current = {};
        } else if (line.startsWith("event: ")) current.event = line.slice(7);
        else if (line.startsWith("data: ")) current.data = (current.data ?? "") + line.slice(6);
        else if (line.startsWith("id: ")) current.id = line.slice(4);
        else if (line.startsWith("retry: ")) current.retry = Number(line.slice(7));
      }
    }
  })().catch(() => {});
  return reader;
}

test(
  "a message delivered while a stream is open reaches the client before the window ends",
  { timeout: (config.waitWindowSeconds + 60) * 1000 },
  async () => {
    const inbox = await client.createInbox({ aliasHint: "events-stream", ttlSeconds: 900 });
    created.push(inbox);
    const token = Math.random().toString(36).slice(2, 10);

    const controller = new AbortController();
    const response = await fetch(`${config.baseUrl}/v1/workspace/events`, {
      headers: { authorization: `Bearer ${config.apiKey}`, accept: "text/event-stream" },
      signal: controller.signal,
    });
    assert.equal(
      response.status,
      200,
      `the ingress answered ${response.status} to the stream; a 404 means the deployment still serves an artifact ` +
        `from before the console reads (GET /v1/workspace/events)`,
    );
    assert.match(response.headers.get("content-type") ?? "", /^text\/event-stream/);

    let resolveReceived;
    const received = new Promise((resolve) => (resolveReceived = resolve));
    let ended = false;
    sseReader(response.body, (event) => {
      if (event.event === "end") ended = true;
      if (event.event === "message.received" && event.data?.includes(inbox.id)) {
        resolveReceived({ at: Date.now(), ended, data: JSON.parse(event.data) });
      }
    });

    try {
      // Let the stream open and subscribe before the delivery, so the event is
      // a wake, not a replay.
      await new Promise((resolve) => setTimeout(resolve, 2_000));
      const subject = `Streamed ${token}`;
      const delivery = await sendRawSmtp({
        host: config.smtpHost,
        port: config.smtpPort,
        from: SENDER,
        to: inbox.address,
        raw: buildMime({ from: SENDER, to: inbox.address, subject, text: `streamed ${token}` }),
      });

      const windowMs = config.waitWindowSeconds * 1000;
      const outcome = await Promise.race([
        received,
        new Promise((_, reject) =>
          setTimeout(() => reject(new Error(`no message.received within the ${config.waitWindowSeconds}s window`)), windowMs + 15_000),
        ),
      ]);
      assert.equal(outcome.data.subject, subject);
      assert.equal(outcome.ended, false, "the event arrived only after the window's end frame: the edge buffered the stream");
      const deliveryMs = outcome.at - delivery.acceptedAt;
      // Well inside the window and inside one keep-alive interval: a buffering
      // hop releases nothing until the window closes (60 s) or its own buffer
      // fills, so a bound at a third of the window separates the two cleanly.
      assert.ok(
        deliveryMs < windowMs / 3,
        `message.received reached the client ${deliveryMs}ms after the gateway accepted the delivery; ` +
          `an edge in front of the API is buffering the event stream`,
      );
      console.log(`stream delivered the event ${deliveryMs}ms after the gateway accepted the delivery`);
    } finally {
      controller.abort();
    }
  },
);
