import { test } from "node:test";
import assert from "node:assert/strict";
import { classifyOriginProbe } from "../src/origin.mjs";

/**
 * Self-tests for the direct-origin classifier. Deliberately the mirror of the
 * unknown-Host classifier's tests: there a timeout must NOT pass; here it
 * MUST, because a DROPping firewall is what a timeout looks like — and the
 * difference between the two is exactly what a reviewer would get wrong.
 */

test("a dropped, refused or unroutable connection proves isolation", () => {
  for (const error of ["ETIMEDOUT", "ECONNREFUSED", "ECONNRESET", "EHOSTUNREACH", "ENETUNREACH"]) {
    const verdict = classifyOriginProbe({ error });
    assert.equal(verdict.isolated, true, `${error} should read as isolated`);
  }
});

test("a completed connection is a violation whatever came after it", () => {
  // A TLS failure or a 403 still means the origin is listening to the world.
  assert.equal(classifyOriginProbe({ connected: true }).isolated, false);
  assert.equal(classifyOriginProbe({ connected: true, error: "ERR_TLS_CERT_ALTNAME_INVALID" }).isolated, false);
});

test("a name that does not resolve is not isolation — the probe hit nothing", () => {
  for (const error of ["ENOTFOUND", "EAI_AGAIN"]) {
    assert.equal(classifyOriginProbe({ error }).isolated, false, `${error} must not pass`);
  }
});

test("no evidence fails closed", () => {
  assert.equal(classifyOriginProbe({}).isolated, false);
  assert.equal(classifyOriginProbe({ error: "" }).isolated, false);
  assert.equal(classifyOriginProbe({ error: "ESOMETHINGELSE" }).isolated, false);
});
