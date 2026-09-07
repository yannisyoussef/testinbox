import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { TestInboxClient, TestInboxInboxGoneError } from "@testinbox/client";
import { config } from "../src/env.mjs";
import { buildMime, sendRawSmtp } from "../src/smtp.mjs";

/**
 * The post-deployment synthetic scenario (TI-DEPLOY-001 §17).
 *
 * A deployment is NOT successful because `/health` returned 200. This runs a
 * real TestInbox workflow — create, deliver over SMTP, wait, assert, fetch raw,
 * delete — through the deployed API over its real HTTPS ingress, the deployed
 * gateway over its real SMTP listener, the environment's PostgreSQL and its
 * object store. It touches no application class directly; everything goes
 * through the PUBLIC TypeScript SDK built from this same commit (§26), so a
 * pass also means the public abstraction works against the deployment.
 */

const client = new TestInboxClient({ apiKey: config.apiKey, baseUrl: config.baseUrl });
const SENDER = "synthetic-sut@example.com";
const RUN = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

/** Every inbox created by this file, deleted in `after` whatever happened. */
const created = [];

async function newInbox(aliasHint) {
  const inbox = await client.createInbox({ aliasHint, ttlSeconds: 600 });
  created.push(inbox);
  return inbox;
}

after(async () => {
  // Cleanup runs even after an assertion failure: a failed synthetic run must
  // not leave inboxes and stored MIME behind on every deployment attempt.
  for (const inbox of created) {
    await inbox.delete().catch(() => {});
  }
});

before(() => {
  console.log(`synthetic run ${RUN} against ${config.baseUrl} (smtp ${config.smtpHost}:${config.smtpPort})`);
});

test("the full inbound workflow succeeds through the deployed environment", async () => {
  // 1-3. Authenticate and create a GENERATED inbox; the address is unique per
  // run by construction (ADR-021), so concurrent deployments cannot collide.
  const inbox = await newInbox("synthetic");
  assert.equal(inbox.addressMode, "GENERATED");
  assert.equal(inbox.state, "ACTIVE");
  assert.match(inbox.address, /@/);

  const subject = `TestInbox synthetic ${RUN}`;
  const text = `Verification token ${RUN}. Confirm at https://example.com/confirm/${RUN}`;
  const html = `<p>Verification token ${RUN}.</p><p><a href="https://example.com/confirm/${RUN}">Confirm</a></p>`;
  const attachment = {
    fileName: "receipt.txt",
    contentType: "text/plain",
    content: `synthetic attachment for run ${RUN}\n`,
  };

  // 4. Deliver a real MIME message through the deployed SMTP ingress.
  const delivery = await sendRawSmtp({
    host: config.smtpHost,
    port: config.smtpPort,
    from: SENDER,
    to: inbox.address,
    raw: buildMime({ from: SENDER, to: inbox.address, subject, text, html, attachment }),
  });
  assert.equal(delivery.dataReply.code, 250, "the gateway must accept the DATA transaction");

  // 5-6. Wait through the deployed HTTPS API and require a match.
  const message = await inbox.waitForMessage({ subjectContains: RUN, timeoutMs: 60_000 });

  // 7-10. The parsed content is what was actually sent.
  assert.equal(message.parseStatus, "OK");
  assert.equal(message.from, SENDER, "sender");
  assert.equal(message.subject, subject, "subject");
  assert.ok(message.textBody?.includes(`Verification token ${RUN}`), "plaintext body");
  assert.ok(message.htmlBody?.includes("<a href="), "html body");
  assert.ok(
    message.links.some((link) => link.href === `https://example.com/confirm/${RUN}`),
    `extracted links: ${JSON.stringify(message.links)}`,
  );

  // 12. Attachment metadata is parsed. Bytes are not fetched here — the point
  // is that a multipart/mixed message survived the deployed parser intact.
  const receipt = message.attachments.find((a) => a.fileName === "receipt.txt");
  assert.ok(receipt, `attachments: ${JSON.stringify(message.attachments)}`);
  assert.ok(receipt.sizeBytes > 0);

  // 11. Raw MIME round-trips from the deployed object store (ADR-005).
  const raw = Buffer.from(await message.raw()).toString("utf8");
  assert.ok(raw.includes(`Subject: ${subject}`), "raw MIME carries the original headers");
  assert.ok(raw.includes("Content-Disposition: attachment"), "raw MIME carries the attachment part");

  // 13-14. Explicit teardown, then the inbox is no longer usable.
  //
  // "Inaccessible as the contract allows" (ADR-009): delete is explicit early
  // teardown that marks the inbox DELETED; hard removal of rows and blobs
  // happens in the bounded async sweep. So the assertions are the ones the
  // contract actually makes — the inbox leaves ACTIVE, and it stops serving
  // traffic — rather than an immediate 404 the contract never promised.
  await inbox.delete();
  created.splice(created.indexOf(inbox), 1);

  const afterDelete = await client.getInbox(inbox.id);
  assert.equal(afterDelete.state, "DELETED", "a deleted inbox must not still be ACTIVE");

  await assert.rejects(
    () => afterDelete.waitForMessage({ timeoutMs: 5_000 }),
    (error) => error instanceof TestInboxInboxGoneError,
    "waiting on a deleted inbox must be refused with 410 (ADR-012), not time out",
  );
});

test("a wait that finds nothing times out cleanly instead of erroring", async () => {
  // A short, deliberate negative: `200 {status: TIMEOUT}` is a successful
  // answer (ADR-012/020). If the ingress mangled it we would see a transport
  // error here rather than the SDK's own timeout.
  const inbox = await newInbox("synthetic-empty");
  await assert.rejects(
    () => inbox.waitForMessage({ timeoutMs: 3_000 }),
    (error) => error.name === "TestInboxTimeoutError",
    "an empty inbox must produce the SDK timeout, not a transport failure",
  );
});

test("the deployment enforces authentication", async () => {
  const anonymous = new TestInboxClient({ apiKey: "tk_definitely_not_a_real_key", baseUrl: config.baseUrl });
  await assert.rejects(
    () => anonymous.createInbox(),
    (error) => error.name === "TestInboxAuthError",
    "an unknown key must be refused by the deployed API",
  );
});
