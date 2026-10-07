import { after, test } from "node:test";
import assert from "node:assert/strict";
import { config } from "../src/env.mjs";

/**
 * ADR-035 §13 through the deployed environment (TI-STORAGE-004).
 *
 * The authenticated storage surface is raw REST state that the released SDKs
 * do not model yet (TI-STORAGE-005), so these requests go straight to the
 * deployed API over its real ingress with the synthetic credential. They prove
 * the deployed artifact serves the storage members, that every figure is the
 * caller's own, and that a wait which omits the cursor behaves exactly as it
 * did before ADR-035.
 *
 * They also prove, from outside, that live storage enforcement is OFF: an
 * opt-in wait on a fresh inbox ends `200 TIMEOUT` with a zero refusal count. A
 * `409` here would mean the deployment recorded a storage refusal against a
 * tenant inbox, which no deployable may do until the ADR-035 §18 enablement
 * gates have passed.
 *
 * Convergence signal: an artifact from before TI-STORAGE-004 answers
 * `GET /v1/workspace/storage` with `404`, so the first test also tells a
 * stale deployment from a reconciled one.
 */

const STORAGE_USAGE_MEMBERS = ["availableBytes", "limitBytes", "overLimit", "reservedBytes", "storedBytes"];
const REFUSAL_MEMBERS = ["storageRefusalCount", "lastStorageRefusalAt", "lastStorageRefusalReason"];

/** Members whose presence would mean a global or node figure leaked into a tenant response (ADR-035 §13d). */
const FORBIDDEN_MEMBER = /global|finalize|^[gh]$|node|generation|backlog|ambigu|reservation/i;

const headers = { authorization: `Bearer ${config.apiKey}`, "content-type": "application/json" };

async function call(method, path, body) {
  const response = await fetch(`${config.baseUrl}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  return { status: response.status, headers: response.headers, body: text ? JSON.parse(text) : undefined };
}

function memberNames(value, out = []) {
  if (Array.isArray(value)) value.forEach((v) => memberNames(v, out));
  else if (value && typeof value === "object") {
    for (const [name, child] of Object.entries(value)) {
      out.push(name);
      memberNames(child, out);
    }
  }
  return out;
}

function assertNoGlobalFigure(body, label) {
  const offenders = memberNames(body).filter((name) => name !== "reservedBytes" && FORBIDDEN_MEMBER.test(name));
  assert.deepEqual(offenders, [], `${label} must not carry a global, node or reservation-level member`);
}

function assertStorageUsage(usage, label) {
  assert.deepEqual(Object.keys(usage).sort(), STORAGE_USAGE_MEMBERS, `${label}: exactly the five StorageUsage members`);
  for (const name of ["limitBytes", "storedBytes", "reservedBytes", "availableBytes"]) {
    assert.equal(typeof usage[name], "number", `${label}.${name} is a number`);
    assert.ok(Number.isInteger(usage[name]), `${label}.${name} is an integer`);
  }
  assert.equal(typeof usage.overLimit, "boolean", `${label}.overLimit is a boolean`);
  assert.ok(usage.limitBytes > 0, `${label}.limitBytes is a real limit`);
  assert.ok(usage.availableBytes >= 0, `${label}.availableBytes is never negative`);
  assert.ok(
    usage.availableBytes <= Math.max(0, usage.limitBytes - usage.storedBytes - usage.reservedBytes),
    `${label}.availableBytes never exceeds the scope's own headroom`,
  );
  assert.equal(
    usage.overLimit,
    usage.storedBytes + usage.reservedBytes > usage.limitBytes,
    `${label}.overLimit is stored + reserved > limit`,
  );
}

function assertNoRefusal(inbox, label) {
  assert.equal(inbox.storageRefusalCount, 0, `${label}: a fresh inbox has no refusal`);
  assert.equal(inbox.lastStorageRefusalAt, null, `${label}: no last refusal time`);
  assert.equal(inbox.lastStorageRefusalReason, null, `${label}: no last refusal reason`);
}

/** Every inbox created here, deleted whatever happened. */
const created = [];

after(async () => {
  for (const id of created) {
    await call("DELETE", `/v1/inboxes/${id}`).catch(() => {});
  }
});

async function newInbox() {
  const response = await call("POST", "/v1/inboxes", { aliasHint: "synthetic-storage", ttlSeconds: 600 });
  assert.equal(response.status, 201, "inbox creation must succeed");
  created.push(response.body.id);
  return response.body;
}

test("GET /v1/workspace/storage answers the caller's own StorageUsage, and nothing global", async () => {
  const response = await call("GET", "/v1/workspace/storage");
  assert.equal(
    response.status,
    200,
    "a 404 here means the deployment still serves an artifact from before TI-STORAGE-004 (ADR-035 §13a)",
  );
  assertStorageUsage(response.body, "workspace");
  assertNoGlobalFigure(response.body, "workspace storage");
  // Charged as a metadata READ, so the budget headers of that category are present (ADR-027).
  assert.ok(response.headers.get("ratelimit-limit"), "the read is rate-governed, not a free endpoint");
});

test("POST /v1/inboxes carries the live storage and refusal members", async () => {
  const inbox = await newInbox();
  for (const name of ["storage", ...REFUSAL_MEMBERS]) {
    assert.ok(name in inbox, `created inbox carries ${name} (ADR-035 §13b)`);
  }
  assertStorageUsage(inbox.storage, "inbox.storage");
  assertNoRefusal(inbox, "create");
  assertNoGlobalFigure(inbox, "created inbox");
  const workspace = (await call("GET", "/v1/workspace/storage")).body;
  assert.ok(inbox.storage.limitBytes <= workspace.limitBytes, "the inbox limit is a share of the workspace limit, never above it");
});

test("GET /v1/inboxes/{id} returns the same live model as the create response", async () => {
  const createdInbox = await newInbox();
  const response = await call("GET", `/v1/inboxes/${createdInbox.id}`);
  assert.equal(response.status, 200);
  assert.deepEqual(Object.keys(response.body).sort(), Object.keys(createdInbox).sort(), "create and GET share one representation");
  assertStorageUsage(response.body.storage, "fetched inbox.storage");
  assert.equal(response.body.storage.limitBytes, createdInbox.storage.limitBytes, "the effective inbox limit is stable");
  assertNoRefusal(response.body, "get");
  assertNoGlobalFigure(response.body, "fetched inbox");
});

test("a wait that omits the cursor still ends MATCHED or TIMEOUT, carrying the count for diagnosis", async () => {
  const inbox = await newInbox();
  const response = await call("POST", `/v1/inboxes/${inbox.id}/messages/wait`, { timeoutSeconds: 2 });
  assert.equal(response.status, 200, "the legacy wait is never a 409 (ADR-035 §13c)");
  assert.equal(response.body.status, "TIMEOUT", "nothing was delivered, so the window expires as a successful negative answer");
  assert.equal(response.body.storageRefusalCount, 0, "the informational count rides on TIMEOUT");
  assert.equal(response.body.lastStorageRefusalAt, null);
  assertNoGlobalFigure(response.body, "legacy wait result");
});

test("an opt-in wait on a fresh inbox ends TIMEOUT: live storage enforcement is OFF, so no refusal is ever recorded", async () => {
  const inbox = await newInbox();
  const response = await call("POST", `/v1/inboxes/${inbox.id}/messages/wait`, {
    timeoutSeconds: 2,
    afterStorageRefusalCount: inbox.storageRefusalCount,
  });
  assert.equal(
    response.status,
    200,
    `expected 200 TIMEOUT; a ${response.status} means a storage refusal was recorded against a tenant inbox, ` +
      "which the deployed wiring (StorageEnforcement.OFF) must never do before the ADR-035 §18 enablement gates",
  );
  assert.equal(response.body.status, "TIMEOUT");
  assert.equal(response.body.storageRefusalCount, 0);
  assert.equal(response.headers.get("retry-after"), null);
});
