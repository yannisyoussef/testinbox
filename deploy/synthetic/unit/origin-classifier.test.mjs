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
  for (const error of ["ETIMEDOUT", "ECONNREFUSED", "ECONNRESET", "EHOSTUNREACH", "ENETUNREACH", "EHOSTDOWN", "ENETDOWN"]) {
    const verdict = classifyOriginProbe({ error });
    assert.equal(verdict.isolated, true, `${error} should read as isolated`);
  }
});

test("a routing failure is not isolation when the runner cannot route that family at all", () => {
  // An IPv6 probe from an IPv4-only runner yields EHOSTUNREACH before any
  // packet reaches the origin. Only with a same-family positive control does
  // that count.
  for (const error of ["EHOSTUNREACH", "ENETUNREACH", "EHOSTDOWN", "ENETDOWN"]) {
    assert.equal(classifyOriginProbe({ error }, { sameFamilyReachable: false }).isolated, false, `${error} must not pass without the control`);
    assert.equal(classifyOriginProbe({ error }, { sameFamilyReachable: true }).isolated, true);
  }
  // A timeout or refusal is the origin's answer, not the runner's routing.
  for (const error of ["ETIMEDOUT", "ECONNREFUSED", "ECONNRESET"]) {
    assert.equal(classifyOriginProbe({ error }, { sameFamilyReachable: false }).isolated, true);
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
