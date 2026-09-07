import { test } from "node:test";
import assert from "node:assert/strict";
import { classifyUnknownHostProbe } from "../src/edge.mjs";

/**
 * Self-tests for the unknown-Host classifier.
 *
 * This is the one piece of synthetic logic with real branching, and it guards a
 * security invariant across two different edges. It also had to be *loosened*
 * from "connection reset only" to accept a 4xx — so the negative cases below
 * are what prove the loosening did not turn it into "any 4xx passes".
 *
 * Needs no deployed environment: it imports the classifier, never `env.mjs`.
 */

test("a dropped connection is the strongest form of refusal", () => {
  const verdict = classifyUnknownHostProbe({ error: "ECONNRESET" });
  assert.equal(verdict.served, false);
  assert.match(verdict.reason, /no HTTP response/);
});

test("a bare 4xx from the edge is a refusal", () => {
  // Cloudflare/Traefik answering 404 or 421 for an unrouted Host.
  for (const status of [400, 403, 404, 421, 451]) {
    const verdict = classifyUnknownHostProbe({ status, headers: {}, body: "404 page not found" });
    assert.equal(verdict.served, false, `status ${status} should be a refusal`);
  }
});

test("a 2xx is a failure — the request was routed somewhere", () => {
  assert.equal(classifyUnknownHostProbe({ status: 200, headers: {}, body: "" }).served, true);
});

test("a redirect is a failure — it is still an answer about a Host we do not serve", () => {
  for (const status of [301, 302, 307, 308]) {
    assert.equal(classifyUnknownHostProbe({ status, headers: {}, body: "" }).served, true, `status ${status}`);
  }
});

test("a 5xx is a failure, not an inconclusive result", () => {
  const verdict = classifyUnknownHostProbe({ status: 502, headers: {}, body: "" });
  assert.equal(verdict.served, true);
  assert.match(verdict.reason, /no justified 5xx behaviour/);
});

test("a 4xx produced BY TestInbox is a failure — this is what stops the test degrading to 'any 4xx'", () => {
  // The API sets X-Correlation-Id on every response before routing or auth, so
  // its presence means the request reached the application.
  const byCorrelationHeader = classifyUnknownHostProbe({
    status: 404,
    headers: { "X-Correlation-Id": "6f1b...", "content-type": "application/problem+json" },
    body: "{}",
  });
  assert.equal(byCorrelationHeader.served, true);
  assert.match(byCorrelationHeader.reason, /produced by TestInbox/);

  const byStabilityHeader = classifyUnknownHostProbe({
    status: 401,
    headers: { "x-api-stability": "experimental" },
    body: "",
  });
  assert.equal(byStabilityHeader.served, true);

  const byProblemBody = classifyUnknownHostProbe({
    status: 404,
    headers: {},
    body: '{"type":"https://testinbox.email/problems/inbox-not-found","title":"Not found"}',
  });
  assert.equal(byProblemBody.served, true);

  const byUiBody = classifyUnknownHostProbe({
    status: 404,
    headers: {},
    body: "<html><body><a href=\"/\">TestInbox Inspector</a></body></html>",
  });
  assert.equal(byUiBody.served, true);
});

test("header matching is case-insensitive, so a differently-cased edge cannot hide the app", () => {
  assert.equal(
    classifyUnknownHostProbe({ status: 404, headers: { "X-CORRELATION-ID": "x" }, body: "" }).served,
    true,
  );
});

test("a generic edge error page mentioning neither TestInbox nor a correlation id passes", () => {
  // False-positive resistance: refusals from real edges must not be misread as
  // application responses just because they are HTML.
  const cloudflare = classifyUnknownHostProbe({
    status: 403,
    headers: { server: "cloudflare", "cf-ray": "8a1..." },
    body: "<html><head><title>Error 1003</title></head><body>Direct IP access not allowed</body></html>",
  });
  assert.equal(cloudflare.served, false);

  const traefik = classifyUnknownHostProbe({ status: 404, headers: { server: "traefik" }, body: "404 page not found" });
  assert.equal(traefik.served, false);
});
