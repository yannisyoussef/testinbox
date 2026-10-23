import { before, test } from "node:test";
import assert from "node:assert/strict";
import dns from "node:dns/promises";
import net from "node:net";
import { classifyOriginProbe } from "../src/origin.mjs";
import { originConfig } from "../src/env.mjs";

/**
 * Origin isolation, proven from OUTSIDE the host (ADR-034 §6).
 *
 *     approved ingress path (public hostname)  → TestInbox answers
 *     direct connection to the origin address  → nothing answers
 *
 * The origin address is supplied by Ops at run time and never committed; the
 * repository knows the invariant, not the IP. The positive control is
 * mandatory: without it a runner with no network would "prove" isolation.
 *
 * Runs as its own target (`npm run test:origin`), never as part of the
 * deployment gate: the gate runs ON the host, where the origin is reachable
 * by construction and this test would be meaningless.
 */

const config = originConfig();

function probe(host, port, timeoutMs) {
  return new Promise((resolve) => {
    const socket = net.connect({ host, port });
    socket.setTimeout(timeoutMs);
    socket.once("connect", () => {
      socket.destroy();
      resolve({ connected: true });
    });
    socket.once("timeout", () => {
      socket.destroy();
      resolve({ error: "ETIMEDOUT" });
    });
    socket.once("error", (error) => resolve({ error: error.code ?? String(error) }));
  });
}

/** Set by the positive control: the runner reached the environment over the probe's own address family. */
let sameFamilyReachable = false;

before(async () => {
  // Resolve the public hostname to an address of the SAME family as the probe
  // and connect to it. This is what makes "unreachable" mean the origin's
  // firewall rather than the runner's routing table.
  const host = new URL(config.baseUrl).hostname;
  const { address } = await dns.lookup(host, { family: config.family });
  const control = await probe(address, 443, config.timeoutMs);
  sameFamilyReachable = control.connected === true;
});

test("positive control: the environment is reachable through its public hostname, over the probe's address family", async () => {
  assert.equal(sameFamilyReachable, true, `the runner cannot reach ${config.baseUrl} over IPv${config.family}; every isolation verdict below would be vacuous`);
  const response = await fetch(`${config.baseUrl}/v1/inboxes/00000000-0000-0000-0000-000000000000`);
  assert.ok(
    response.headers.get("x-correlation-id"),
    "the approved path must reach the API, or the isolation verdicts below prove nothing",
  );
});

for (const port of config.ports) {
  test(`a direct connection to the origin address on :${port} is not answered`, async () => {
    const result = await probe(config.originAddress, port, config.timeoutMs);
    const verdict = classifyOriginProbe(result, { sameFamilyReachable });
    assert.equal(
      verdict.isolated,
      true,
      `the origin answered a direct connection on :${port} — DOCKER-USER/origin isolation is not enforced (${verdict.reason})`,
    );
    console.log(`origin :${port}: ${verdict.reason}`);
  });
}
