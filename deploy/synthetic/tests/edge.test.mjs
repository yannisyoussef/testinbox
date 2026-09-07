import { test } from "node:test";
import assert from "node:assert/strict";
import https from "node:https";
import { config } from "../src/env.mjs";

/**
 * The edge's own claims, asserted against the deployed ingress (§10/§12).
 *
 * Every one of these is a security or availability property that lives only in
 * nginx configuration, where nothing else in the repository can check it. They
 * are cheap — one request each — and they catch the class of mistake that is
 * invisible in review.
 *
 * The header test in particular exists because of a real one: `add_header`
 * does not accumulate across levels in nginx, so adding a single header to a
 * `location` silently drops every header inherited from the `server` block.
 * A CSP added to the UI route removed `nosniff` and HSTS from the entire UI —
 * the one origin that renders message-derived content (ADR-011).
 */

const SECURITY_HEADERS = ["strict-transport-security", "x-content-type-options", "referrer-policy"];

test("the actuator is not routed by the edge", async () => {
  // Actuator lives on a private management port. This is the second lock: if
  // an upstream change ever put it back on the traffic port, the edge must
  // still refuse to serve it.
  const response = await fetch(`${config.baseUrl}/actuator/health`);
  assert.equal(response.status, 404, "the edge must never proxy /actuator");
  const body = await response.text();
  assert.ok(!body.includes("status"), "no actuator payload may reach the public listener");
});

test("the UI origin keeps every security header, not just its own", async () => {
  const response = await fetch(`${config.baseUrl}/`);
  for (const header of [...SECURITY_HEADERS, "content-security-policy", "x-frame-options"]) {
    assert.ok(
      response.headers.get(header),
      `${header} is missing from the UI origin. If a header was recently added to this ` +
        `location, remember nginx add_header does not inherit — every header must be repeated.`,
    );
  }
});

test("the API origin keeps its security headers", async () => {
  // 401 is expected and fine: the headers must be present on the response
  // whatever the status, which is what `always` in the config is for.
  const response = await fetch(`${config.baseUrl}/v1/inboxes/00000000-0000-0000-0000-000000000000`);
  for (const header of SECURITY_HEADERS) {
    assert.ok(response.headers.get(header), `${header} is missing from the API origin`);
  }
});

test("an oversized request body is refused at the edge, not by the application", async () => {
  const response = await fetch(`${config.baseUrl}/v1/inboxes`, {
    method: "POST",
    headers: { authorization: `Bearer ${config.apiKey}`, "content-type": "application/json" },
    body: JSON.stringify({ aliasHint: "x".repeat(512 * 1024) }),
  });
  assert.equal(response.status, 413, "a body beyond client_max_body_size must be refused at the edge");
});

test("plaintext HTTP redirects to HTTPS and never serves content", async (t) => {
  if (!config.httpBaseUrl) {
    // Not skipped silently: a deployment that cannot tell us its plaintext
    // origin cannot have this proven, and says so.
    t.diagnostic("TESTINBOX_HTTP_BASE_URL not set — plaintext redirect not verified");
    return;
  }
  const response = await fetch(`${config.httpBaseUrl}/`, { redirect: "manual" });
  assert.equal(response.status, 308);
  assert.ok(
    response.headers.get("location")?.startsWith("https://"),
    `expected a redirect to https, got ${response.headers.get("location")}`,
  );
});

test("an unrecognised Host does not fall through into the TestInbox vhost", async () => {
  // fetch() forbids overriding Host, so this drops to the raw client. SNI stays
  // `localhost` (the certificate is valid for it) while the HTTP Host header is
  // something else — which is exactly how a misrouted vhost would be probed.
  const url = new URL(config.baseUrl);
  const result = await new Promise((resolve) => {
    const request = https.request(
      {
        host: url.hostname,
        port: url.port || 443,
        servername: url.hostname,
        path: "/",
        method: "GET",
        headers: { Host: "not-testinbox.example.com" },
      },
      (response) => {
        response.resume();
        resolve({ status: response.statusCode });
      },
    );
    request.on("error", (error) => resolve({ error: error.code ?? error.message }));
    request.end();
  });

  // nginx `return 444` closes without a response, which the client sees as a
  // reset. Anything with a status code means the request was served.
  assert.ok(
    result.error !== undefined,
    `an unmatched Host was served with status ${result.status}; it must hit the default_server and be dropped`,
  );
});
