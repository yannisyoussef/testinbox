import { after, test } from "node:test";
import assert from "node:assert/strict";
import { TestInboxClient, TestInboxAuthError } from "@testinbox/client";
import { adminApiKey, config } from "../src/env.mjs";

/**
 * Product synthetic for the credential lifecycle (TI-002 §19, ADR-032).
 *
 * Separate from the deployment gate on purpose, and for two reasons:
 *
 * - The gate proves a *deployment* works and must run with a least-privilege
 *   credential. This suite needs one that can mint credentials, which is a far
 *   larger thing to leave sitting in an automated runner.
 * - A failure here means the credential lifecycle is broken on a deployment
 *   that is otherwise healthy. That is a different verdict from "do not ship
 *   this artifact", and conflating them would make the gate noisier without
 *   making it stronger.
 *
 * Every credential it mints is short-lived AND revoked in cleanup, so a
 * deployment does not accumulate live credentials. The rows themselves are
 * retained — revoked keys always are (ADR-032 §5) — which is two inert rows
 * per run.
 */

const KEY_TTL_SECONDS = 600;
const RUN = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

const admin = new TestInboxClient({ apiKey: adminApiKey(), baseUrl: config.baseUrl });

/** Everything minted here, revoked in `after` whatever happened. */
const minted = [];
const inboxes = [];

async function mint(name) {
  const created = await admin.apiKeys.create({
    name: `synthetic-${name}-${RUN}`,
    scopes: ["inboxes:write", "messages:read"],
    // Belt and braces with the revocation below: if cleanup never runs — the
    // process is killed, the network drops — the credential still stops
    // working on its own.
    expiresInSeconds: KEY_TTL_SECONDS,
  });
  minted.push(created);
  return created;
}

after(async () => {
  for (const inbox of inboxes) await inbox.delete().catch(() => {});
  for (const created of minted) await admin.apiKeys.revoke(created.apiKey.id).catch(() => {});
});

test("a managed credential authenticates against the deployed environment", async () => {
  const ci = await mint("primary");

  // The credential came back exactly once, in the shape the SDK models.
  assert.match(ci.secret, /^ti_k1_[a-z2-7]{16}_[a-z2-7]{52}_[a-z2-7]{4}$/);
  assert.equal(ci.apiKey.publicId, ci.secret.split("_")[2]);
  assert.equal(ci.apiKey.revokedAt, undefined);

  const client = new TestInboxClient({ apiKey: ci.secret, baseUrl: config.baseUrl });
  const inbox = await client.createInbox({ aliasHint: "keylifecycle", ttlSeconds: 600 });
  inboxes.push(inbox);
  assert.equal(inbox.state, "ACTIVE");
});

test("reading a credential back never returns its secret", async () => {
  const ci = minted[0];
  const metadata = await admin.apiKeys.get(ci.apiKey.id);
  const rendered = JSON.stringify(metadata);
  // The deployed environment must not have a path that reproduces it — the
  // plaintext is not stored, so there is nothing to return (ADR-032 §4).
  assert.ok(!rendered.includes(ci.secret));
  assert.ok(!rendered.includes(ci.secret.split("_")[3]));
  assert.equal(metadata.publicId, ci.apiKey.publicId);
});

test("a revoked credential stops working while another valid one keeps working", async () => {
  const doomed = await mint("doomed");
  const survivor = await mint("survivor");

  const doomedClient = new TestInboxClient({ apiKey: doomed.secret, baseUrl: config.baseUrl });
  const survivorClient = new TestInboxClient({ apiKey: survivor.secret, baseUrl: config.baseUrl });

  // Both live: this is the window that makes rotation zero-downtime.
  inboxes.push(await doomedClient.createInbox({ ttlSeconds: 300 }));
  inboxes.push(await survivorClient.createInbox({ ttlSeconds: 300 }));

  await admin.apiKeys.revoke(doomed.apiKey.id);

  // Immediate, across whatever node answers next: no authorization state is
  // cached anywhere, so there is no staleness window to wait out (ADR-032 §5).
  await assert.rejects(
    () => doomedClient.createInbox({ ttlSeconds: 300 }),
    (error) => error instanceof TestInboxAuthError,
  );

  // And revocation was surgical — the other credential is untouched.
  inboxes.push(await survivorClient.createInbox({ ttlSeconds: 300 }));

  const after = await admin.apiKeys.get(doomed.apiKey.id);
  assert.ok(after.revokedAt instanceof Date, "the revoked key is retained with its revocation time");
});

test("the deployment is running on managed credentials, not the bootstrap one", async () => {
  // "Staging must not accidentally continue relying forever on the bootstrap
  // key" was a statement in the docs and nothing else. This makes it a check:
  // a usable managed administrator existing is exactly the condition that
  // closes the ADR-032 §8 window, so asserting it proves the handover happened
  // — and does so without this suite needing to know the bootstrap secret.
  const page = await admin.apiKeys.list({ limit: 200 });
  const administrators = page.items.filter(
    (key) => !key.revokedAt && key.scopes.includes("api-keys:manage"),
  );
  assert.ok(
    administrators.length > 0,
    "no usable managed administrator: this environment is still authenticating with its bootstrap credential",
  );
});

test("revocation is idempotent, so a retried cleanup is not an error", async () => {
  const throwaway = await mint("idempotent");
  await admin.apiKeys.revoke(throwaway.apiKey.id);
  // A client retrying after a network failure must not be told its own
  // successful call failed.
  await admin.apiKeys.revoke(throwaway.apiKey.id);
});
