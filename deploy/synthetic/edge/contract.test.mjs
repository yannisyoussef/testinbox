import { after, test } from "node:test";
import assert from "node:assert/strict";
import { TestInboxClient, TestInboxTimeoutError } from "@testinbox/client";
import { config, edgeConfig } from "../src/env.mjs";
import { edgeConversation, messageOfSize } from "../src/edge-smtp.mjs";

/**
 * TI-005 — the Postfix edge contract, proven end to end.
 *
 *   sender ──:25──> Postfix ──relay──> TestInbox ingestion ──> Postgres/MinIO
 *
 * Every test here sends through the EDGE, never directly to ingestion. Sending
 * straight to the gateway would prove the application and nothing about the hop
 * that will actually face the Internet — which is the hop TI-005 exists to make
 * executable before any public traffic is allowed. The direct-ingestion tests in
 * the backend suite answer a different question and are not replaced by these.
 *
 * Everything the application side asserts goes through the PUBLIC SDK built from
 * this commit, so a pass also means the public abstraction sees what the edge
 * delivered.
 */

const edge = edgeConfig();
const client = new TestInboxClient({ apiKey: config.apiKey, baseUrl: config.baseUrl });
const SENDER = "sut@example.net";
const created = [];

async function activeInbox() {
  const inbox = await client.createInbox({ ttlSeconds: 900 });
  created.push(inbox);
  return inbox;
}

/** A syntactically valid address in the tenant domain that belongs to no inbox. */
function unknownRecipient() {
  return `nobody-${Math.random().toString(36).slice(2, 10)}@${edge.mailDomain}`;
}

function mime({ to, subject = "edge contract", body = "hello from the edge", extraHeaders = "" }) {
  return `From: ${SENDER}\r\nTo: ${to}\r\nSubject: ${subject}\r\n${extraHeaders}\r\n${body}\r\n`;
}

/** Strips the queue id — the one part of a 250 that legitimately differs. */
function withoutQueueId(text) {
  return text.replace(/queued as [0-9A-Fa-f]+/g, "queued as <id>");
}

const send = (recipients, raw, timeoutMs = 60_000) =>
  edgeConversation({ host: edge.host, port: edge.port, from: SENDER, recipients, raw, timeoutMs });

/** waitForMessage throws on expiry; this turns that into a boolean for negatives. */
async function arrives(inbox, options) {
  try {
    return await inbox.waitForMessage(options);
  } catch (error) {
    if (error instanceof TestInboxTimeoutError) return null;
    throw error;
  }
}

after(async () => {
  for (const inbox of created) await inbox.delete().catch(() => {});
});

// ---------------------------------------------------------------------------
// Live tenant delivery — the path that must work.
// ---------------------------------------------------------------------------
test("a tenant message traverses Postfix and becomes visible in TestInbox", async () => {
  const inbox = await activeInbox();
  const subject = `live-${Date.now()}`;

  const convo = await send([inbox.address], mime({ to: inbox.address, subject }));
  assert.equal(convo.rcptReplies[0].code, 250, "RCPT must be accepted");
  assert.equal(convo.bodyReply.code, 250, `DATA must be accepted: ${convo.bodyReply?.text}`);

  const message = await inbox.waitForMessage({ subjectContains: subject, timeoutMs: 60_000 });
  assert.equal(message.subject, subject, "the relayed message is the one that arrived");

  // ADR-005: the stored raw is what INGESTION received, which legitimately
  // includes the trace header each hop added — not a byte copy of what the
  // sender wrote.
  const raw = Buffer.from(await message.raw()).toString("latin1");
  assert.match(raw, /^Received:/m, "the relay's trace header is present in the stored raw");
  assert.match(raw, new RegExp(`Subject: ${subject}`), "the sender's content survived the relay");
  assert.match(raw, /hello from the edge/, "the body survived the relay");
});

// ---------------------------------------------------------------------------
// ADR-025 — the enumeration invariant. The most important test in this file.
// ---------------------------------------------------------------------------
test("tenant, unknown and postmaster recipients are indistinguishable at SMTP", async () => {
  const inbox = await activeInbox();
  const recipients = [
    inbox.address,
    unknownRecipient(),
    `${edge.operationalRecipient}@${edge.mailDomain}`,
  ];

  const conversations = [];
  for (const recipient of recipients) {
    conversations.push(
      await send([recipient], mime({ to: recipient, subject: `equivalence-${Date.now()}` })),
    );
  }

  for (const [i, convo] of conversations.entries()) {
    assert.equal(convo.rcptReplies[0].code, 250, `RCPT for ${recipients[i]}`);
    assert.equal(convo.bodyReply.code, 250, `DATA for ${recipients[i]}`);
  }

  // Text equality too, queue id aside: a differing enhanced status code or
  // phrase would be an oracle just as surely as a differing numeric code.
  const rcptTexts = conversations.map((c) => withoutQueueId(c.rcptReplies[0].text));
  assert.equal(new Set(rcptTexts).size, 1, `RCPT replies differ: ${JSON.stringify(rcptTexts)}`);

  const dataTexts = conversations.map((c) => withoutQueueId(c.bodyReply.text));
  assert.equal(new Set(dataTexts).size, 1, `DATA replies differ: ${JSON.stringify(dataTexts)}`);

  // Ordering equivalence: the same commands produced the same reply sequence,
  // so a client cannot distinguish by shape either.
  const shapes = conversations.map((c) => c.transcript.map((t) => t.slice(0, 3)).join(","));
  assert.equal(new Set(shapes).size, 1, `reply ordering differs: ${JSON.stringify(shapes)}`);
});

test("an unknown recipient is relayed and then discarded by ingestion, not by the edge", async () => {
  // This only means anything because the edge RELAYS rather than discarding:
  // the dormant real edge discards at the edge itself, which is why its
  // evidence is necessary but not sufficient. The rehearsal renders the
  // production relay line instead. The storage-side negative proof is asserted
  // by the rehearsal step that can see Postgres and MinIO.
  const unknown = unknownRecipient();
  const marker = `UNKNOWN-${Math.random().toString(36).slice(2, 12).toUpperCase()}`;

  const convo = await send([unknown], mime({ to: unknown, subject: marker, body: marker }));
  assert.equal(convo.bodyReply.code, 250, "the edge answers the uniform 250");
  assert.match(convo.bodyReply.text, /queued as/i, "the edge queued it for relay rather than dropping it");
});

// ---------------------------------------------------------------------------
// ADR-026 — one DATA, several recipients.
// ---------------------------------------------------------------------------
test("one DATA transaction addressed to two tenants delivers to both", async () => {
  const a = await activeInbox();
  const b = await activeInbox();
  const subject = `multi-${Date.now()}`;

  const convo = await send([a.address, b.address], mime({ to: `${a.address}, ${b.address}`, subject }));
  assert.equal(convo.rcptReplies.length, 2, "both recipients were offered in ONE transaction");
  for (const reply of convo.rcptReplies) assert.equal(reply.code, 250, reply.text);
  assert.equal(convo.bodyReply.code, 250, "one DATA covered both recipients");

  const [first, second] = await Promise.all([
    a.waitForMessage({ subjectContains: subject, timeoutMs: 60_000 }),
    b.waitForMessage({ subjectContains: subject, timeoutMs: 60_000 }),
  ]);
  assert.equal(first.subject, subject, "recipient A received the message");
  assert.equal(second.subject, subject, "recipient B received the message");
});

test("one DATA addressed to a tenant and an unknown recipient delivers to the tenant only", async () => {
  const known = await activeInbox();
  const unknown = unknownRecipient();
  const subject = `mixed-${Date.now()}`;

  const convo = await send([known.address, unknown], mime({ to: known.address, subject }));
  // The mixed case must not become an oracle either — which is exactly the
  // failure a naive "reject unknown recipients early" would introduce.
  assert.equal(new Set(convo.rcptReplies.map((r) => r.code)).size, 1, "both RCPTs answered alike");
  assert.equal(
    new Set(convo.rcptReplies.map((r) => withoutQueueId(r.text))).size,
    1,
    "both RCPT texts are identical",
  );
  assert.equal(convo.bodyReply.code, 250);

  const delivered = await known.waitForMessage({ subjectContains: subject, timeoutMs: 60_000 });
  assert.equal(delivered.subject, subject, "the resolvable recipient still receives the message");
});

// ---------------------------------------------------------------------------
// Relay safety — the edge is not an open relay.
// ---------------------------------------------------------------------------
test("a foreign domain is refused from a client outside mynetworks", async () => {
  // An open-relay probe from inside mynetworks proves nothing: permit_mynetworks
  // is evaluated first and would accept it. mynetworks is pinned to loopback and
  // this sender is not loopback, so the path is genuinely untrusted.
  const convo = await send(["someone@example.net"], undefined);
  const reply = convo.rcptReplies[0];
  assert.equal(reply.code, 554, `a foreign domain must be refused, got: ${reply.text}`);
  assert.match(reply.text, /relay access denied/i, reply.text);
});

test("the apex domain is not treated as the tenant domain", async () => {
  // The tenant domain is a subdomain; the apex stays on an ordinary mailbox
  // provider and must never be relayed here.
  const apex = edge.mailDomain.split(".").slice(1).join(".");
  const convo = await send([`someone@${apex}`], undefined);
  assert.equal(convo.rcptReplies[0].code, 554, `the apex ${apex} must not be relayed`);
});

// ---------------------------------------------------------------------------
// ADR-019 — no Message-ID dedup, and the edge must not invent one.
// ---------------------------------------------------------------------------
test("two sends reusing one Message-ID remain two deliveries", async () => {
  const inbox = await activeInbox();
  const subject = `msgid-${Date.now()}`;
  const raw = mime({
    to: inbox.address,
    subject,
    extraHeaders: `Message-ID: <fixed-${Date.now()}@example.net>\r\n`,
  });

  for (const _ of [1, 2]) {
    const convo = await send([inbox.address], raw);
    assert.equal(convo.bodyReply.code, 250);
  }

  await inbox.waitForMessage({ subjectContains: subject, timeoutMs: 60_000 });
  let matching = [];
  for (let attempt = 0; attempt < 30; attempt++) {
    matching = (await inbox.listMessages()).filter((m) => m.subject === subject);
    if (matching.length >= 2) break;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  assert.equal(matching.length, 2, "a reused Message-ID must never suppress a delivery (ADR-019)");
});

test("the edge does not stamp a Message-ID onto a message that lacks one", async () => {
  const inbox = await activeInbox();
  const subject = `nomsgid-${Date.now()}`;

  await send([inbox.address], mime({ to: inbox.address, subject }));
  const message = await inbox.waitForMessage({ subjectContains: subject, timeoutMs: 60_000 });
  const raw = Buffer.from(await message.raw()).toString("latin1");

  // The behavioural half only. local_header_rewrite_clients is the hard gate,
  // because a remote sender does not match its default and this assertion would
  // pass whether or not the setting is correct.
  assert.ok(!/^Message-ID:/im.test(raw), "the relay must not add a Message-ID");
  assert.ok(!/^Date:/im.test(raw), "the relay must not add a Date either");
});

// ---------------------------------------------------------------------------
// The size contract — the boundary proof behind the equal ceilings.
// ---------------------------------------------------------------------------
test("a message near the edge ceiling is accepted and still fits ingestion", async () => {
  // The contract is a property, not a byte count: everything the public edge
  // accepts must remain acceptable to ingestion after TestInbox-owned transport
  // headers are added. Postfix applies its limit to the message INCLUDING the
  // trace header it is about to add, so it refuses some way below its own
  // limit — this walks down from the ceiling to find what it will actually
  // take, rather than hard-coding an observed reserve that would turn a
  // measurement into a false invariant.
  const inbox = await activeInbox();
  const subject = `size-ok-${Date.now()}`;

  let accepted = null;
  for (const reserve of [1024, 2048, 4096, 8192]) {
    const bytes = edge.messageSizeLimit - reserve;
    const convo = await send(
      [inbox.address],
      messageOfSize({ from: SENDER, to: inbox.address, subject, bytes }),
      180_000,
    );
    if (convo.bodyReply?.code === 250) {
      accepted = bytes;
      break;
    }
  }
  assert.ok(accepted, "the edge must accept a message near, but under, its ceiling");

  const message = await inbox.waitForMessage({ subjectContains: subject, timeoutMs: 180_000 });
  const storedBytes = (await message.raw()).length;

  assert.ok(storedBytes > accepted, "each hop adds trace headers, so the stored message is larger");
  assert.ok(
    storedBytes <= edge.messageSizeLimit,
    `an edge-accepted message stored as ${storedBytes} bytes must stay within the ingestion ` +
      `ceiling ${edge.messageSizeLimit}: this is the equal-ceilings property, and if it ever ` +
      `fails the answer is a separated internal transport allowance, never a higher public ceiling`,
  );
});

test("a message above the edge ceiling is refused and never becomes a row", async () => {
  const inbox = await activeInbox();
  const subject = `size-too-big-${Date.now()}`;

  const convo = await send(
    [inbox.address],
    messageOfSize({
      from: SENDER,
      to: inbox.address,
      subject,
      bytes: edge.messageSizeLimit + 8192,
    }),
    180_000,
  );
  assert.equal(convo.bodyReply.code, 552, `oversized mail must be refused: ${convo.bodyReply?.text}`);

  const arrived = await arrives(inbox, { subjectContains: subject, timeoutMs: 8_000 });
  assert.equal(arrived, null, "a refused message must not become a row");
});
