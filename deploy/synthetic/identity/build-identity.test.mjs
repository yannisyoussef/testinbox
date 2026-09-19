import { test } from "node:test";
import assert from "node:assert/strict";
import { identityConfig } from "../src/env.mjs";

/**
 * Deployment identity and readiness, read from the PRIVATE management ports
 * (ADR-028/029/033/034 §8). Run on the host by Ops after a
 * reconcile — the management ports are never routed by the ingress, which is
 * why this is not part of the public synthetic suite.
 *
 * What a dark production deployment must show before it is called one:
 *   * the running build is the approved source commit, on both deployables;
 *   * the environment is the one the handoff named;
 *   * readiness is UP with every component that ADR-020/029/033 put in it,
 *     including a live LISTEN connection and a bounded DB session timeout.
 */

const config = identityConfig();

async function getJson(url) {
  const response = await fetch(url);
  assert.equal(response.status, 200, `${url} answered ${response.status}`);
  return response.json();
}

for (const [name, base] of [
  ["api", config.apiManagementUrl],
  ["ingestion", config.ingestionManagementUrl],
]) {
  test(`${name}: the running build is the approved source commit`, async () => {
    const info = await getJson(`${base}/actuator/info`);
    assert.equal(info.testinbox?.gitSha, config.expectedGitSha, `${name} is not running ${config.expectedGitSha}`);
    assert.equal(info.testinbox?.environment, config.expectedEnvironment, `${name} does not believe it is ${config.expectedEnvironment}`);
  });

  test(`${name}: readiness is UP and every required component reports`, async () => {
    const readiness = await getJson(`${base}/actuator/health/readiness`);
    assert.equal(readiness.status, "UP", `${name} readiness is ${readiness.status}: ${JSON.stringify(readiness)}`);
    for (const component of ["db", "schema", "objectStorage"]) {
      assert.equal(readiness.components?.[component]?.status, "UP", `${name} ${component} is not UP`);
    }
  });
}

test("api: the wait notifier holds a live LISTEN connection (ADR-020)", async () => {
  const readiness = await getJson(`${config.apiManagementUrl}/actuator/health/readiness`);
  assert.equal(readiness.components?.waitNotifier?.details?.listening, true, "LISTEN is not live; waits would silently degrade");
});

test("api: the database session bound is reported, and in production enforced and bounded (ADR-033)", async () => {
  const readiness = await getJson(`${config.apiManagementUrl}/actuator/health/readiness`);
  const session = readiness.components?.dbSession;
  assert.ok(session, "dbSession is not in the readiness group");
  console.log(`dbSession: idle_in_transaction_session_timeout=${session.details?.idleInTransactionSessionTimeout} bounded=${session.details?.bounded} enforced=${session.details?.enforced}`);
  if (config.expectedEnvironment === "production") {
    // Elsewhere the value is reported; a staging estate that has not set it
    // stays in service. Production must both enforce and satisfy it.
    assert.equal(session.details?.enforced, true, "production must enforce the session bound, not merely report it");
    assert.equal(session.details?.bounded, true, `idle_in_transaction_session_timeout is ${session.details?.idleInTransactionSessionTimeout}`);
  }
});

test("ingestion: the SMTP listener is up", async () => {
  const readiness = await getJson(`${config.ingestionManagementUrl}/actuator/health/readiness`);
  assert.equal(readiness.components?.smtpListener?.status, "UP");
});

test("api: the scrape endpoint names the same build", async () => {
  const response = await fetch(`${config.apiManagementUrl}/actuator/prometheus`);
  assert.equal(response.status, 200);
  const text = await response.text();
  assert.match(text, new RegExp(`testinbox_build\\{[^}]*git_sha="${config.expectedGitSha}"`), "testinbox_build does not name the approved commit");
});
