import { test } from "node:test";
import assert from "node:assert/strict";
import https from "node:https";
import { config } from "../src/env.mjs";
import { classifyUnknownHostProbe } from "../src/edge.mjs";

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

test("plaintext HTTP redirects to HTTPS and never serves content", async () => {
  // `TESTINBOX_HTTP_BASE_URL` is required, not optional. Returning early on a
  // missing variable recorded a PASS — evading both the count floor and the
  // zero-skips check in the rehearsal, which is precisely how a security
  // assertion goes quiet.
  const response = await fetch(`${config.httpBaseUrl}/`, { redirect: "manual" });

  // The property that matters is the same everywhere: a plaintext request is
  // answered with a PERMANENT redirect to https and no content. The status code
  // that carries it is an edge implementation detail.
  //
  // The reference nginx returns 308 for every method. Traefik — the edge in the
  // Infinity staging environment — returns 301 for GET, where rewriting the
  // method is harmless, and 308 for POST/HEAD, where preserving the method and
  // body actually matters. That is arguably the more careful behaviour, and it
  // is not something a deployment should have to give up to satisfy this suite.
  //
  // So demand the exact code only from the reference edge, and assert the
  // guarantee everywhere else — the same split `edgeIsReference` already draws
  // for the unknown-Host assertion below.
  if (config.edgeIsReference) {
    assert.equal(response.status, 308, "the reference nginx edge must answer 308");
  } else {
    assert.ok(
      [301, 308].includes(response.status),
      `expected a permanent redirect (301 or 308), got ${response.status}`,
    );
  }
  assert.ok(
    response.headers.get("location")?.startsWith("https://"),
    `expected a redirect to https, got ${response.headers.get("location")}`,
  );
  // Deliberately NOT asserting an empty body: both nginx and Traefik emit the
  // conventional short "Moved Permanently" payload with a redirect. "Never serves
  // content" means the application is never reached over plaintext, which the
  // 3xx + Location pair already establishes.
});

/** Probes one path with a Host TestInbox does not serve. */
async function probeUnknownHost(path) {
  const url = new URL(config.baseUrl);
  return new Promise((resolve) => {
    const request = https.request(
      {
        host: url.hostname,
        port: url.port || 443,
        servername: url.hostname,
        path,
        method: "GET",
        headers: { Host: "not-testinbox.example.com" },
      },
      (response) => {
        let body = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          // Bounded: an edge error page is small, and a large body would only
          // slow the probe. Enough to see any application fingerprint.
          if (body.length < 16_384) body += chunk;
        });
        response.on("end", () => resolve({ status: response.statusCode, headers: response.headers, body }));
      },
    );
    request.on("error", (error) => resolve({ error: error.code ?? error.message }));
    request.setTimeout(15_000, () => {
      request.destroy();
      resolve({ error: "ETIMEDOUT" });
    });
    request.end();
  });
}

test("an unrecognised Host is not served by TestInbox", async () => {
  // The invariant, not one edge's mechanism. nginx (`return 444`) drops the
  // connection; Cloudflare/Traefik answer a 4xx. Both refuse. What must never
  // happen is TestInbox answering — including with a 404 of its own, which
  // would mean the request was routed to the application after all. See
  // `src/edge.mjs`; its branches have their own self-tests.
  //
  // Two paths, because they reach different upstreams: `/` goes to the web
  // container, whose responses carry no API correlation header, and `/v1/...`
  // goes to the API, where that header is guaranteed if the request arrived.
  // A fall-through onto either must be caught.
  for (const path of ["/", "/v1/inboxes/00000000-0000-0000-0000-000000000000"]) {
    const probe = await probeUnknownHost(path);
    const verdict = classifyUnknownHostProbe(probe);
    assert.equal(
      verdict.served,
      false,
      `an unrecognised Host must not be served by TestInbox (${path}) — ${verdict.reason}`,
    );

    // Where we control the edge, hold it to the stronger property the
    // reference nginx actually provides: the connection is dropped, not
    // answered. Portable elsewhere, strict here — otherwise the rehearsal
    // silently loses the coverage it had before this test was made
    // mechanism-independent.
    if (config.edgeIsReference) {
      assert.ok(
        probe.error !== undefined,
        `the reference nginx edge must drop the connection for an unknown Host, got HTTP ${probe.status}`,
      );
    }
    console.log(`unknown Host ${path}: ${verdict.reason}`);
  }
});

test("the probe itself reaches a working environment", async () => {
  // Positive control for the test above: without it, a wholly unreachable
  // environment would make every "refusal" verdict meaningless.
  const response = await fetch(`${config.baseUrl}/v1/inboxes/00000000-0000-0000-0000-000000000000`, {
    headers: { authorization: `Bearer ${config.apiKey}` },
  });
  assert.ok(
    response.headers.get("x-correlation-id"),
    "the correct Host must reach the API — otherwise the unknown-Host result proves nothing",
  );
});
