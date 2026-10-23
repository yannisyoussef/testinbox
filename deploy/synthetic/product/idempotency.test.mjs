import { after, test } from "node:test";
import assert from "node:assert/strict";
import {
  TestInboxClient,
  TestInboxIdempotencyConflictError,
} from "@testinbox/client";
import { config } from "../src/env.mjs";

/**
 * Product synthetic for idempotent mutations (TI-003 §30, ADR-033).
 *
 * Small on purpose. The deployment gate proves a deployment works; this proves
 * one product behaviour on top of it, and a failure here is a different verdict
 * — "idempotency is broken on an otherwise healthy deployment" — from "do not
 * ship this artifact".
 *
 * It needs only the ordinary least-privilege synthetic credential: inbox
 * creation is the operation under test, and the key-creation half is covered by
 * ephemeral e2e rather than by minting credentials against staging on every
 * deployment.
 */

const client = new TestInboxClient({ apiKey: config.apiKey, baseUrl: config.baseUrl });
const RUN = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

/** Every inbox this file created, deleted in `after` whatever happened. */
const created = [];

after(async () => {
  for (const inbox of created) await inbox.delete().catch(() => {});
});

test("a retried create returns the same inbox through the deployed edge", async () => {
  const idempotencyKey = `synthetic-idem-${RUN}`;
  const options = { ttlSeconds: 600, idempotencyKey };

  const first = await client.createInbox(options);
  created.push(first);
  const retry = await client.createInbox(options);

  // One resource, however many times the client asked — proven end to end,
  // through the real ingress, not against a local process.
  assert.equal(retry.id, first.id);
  assert.equal(retry.address, first.address);
  assert.equal(retry.expiresAt.toISOString(), first.expiresAt.toISOString());
});

test("the same key with a changed request is refused, and creates nothing", async () => {
  const idempotencyKey = `synthetic-conflict-${RUN}`;
  const first = await client.createInbox({ ttlSeconds: 600, idempotencyKey });
  created.push(first);

  await assert.rejects(
    () => client.createInbox({ ttlSeconds: 900, idempotencyKey }),
    (error) => error instanceof TestInboxIdempotencyConflictError,
  );

  // The refusal is terminal and inert: the original is untouched.
  const fetched = await client.getInbox(first.id);
  assert.equal(fetched.address, first.address);
});

test("a refused request leaves its key free for a corrected retry", async () => {
  const idempotencyKey = `synthetic-refused-${RUN}`;
  // Beyond the deployment's maximum TTL, so the server refuses it.
  await assert.rejects(() => client.createInbox({ ttlSeconds: 99_999_999, idempotencyKey }));

  // Recording that refusal would freeze it into the key for the whole
  // retention window with no way to clear it (ADR-033 §4).
  const recovered = await client.createInbox({ ttlSeconds: 600, idempotencyKey });
  created.push(recovered);
  assert.ok(recovered.id);
});
