import { test } from "node:test";
import assert from "node:assert/strict";
import https from "node:https";
import crypto from "node:crypto";
import { accountsConfig } from "../src/env.mjs";
import {
  ACCOUNT_ROUTES,
  classifyAuthorizeRedirect,
  classifyClosed,
  classifyPrivate,
  classifySignInPage,
  parseMethods,
  refusalShape,
  sameRefusal,
} from "../src/accounts.mjs";

/**
 * The human account boundary (ADR-036), asserted from outside against the
 * state the environment DECLARES (TI-DELIVERY-002).
 *
 * Safe and repeatable in any deployed environment, production included. These
 * checks never:
 *   - send an email (every code request carries an address the API refuses
 *     before any budget or row: a malformed one, and one in the deployment's
 *     own receive domain);
 *   - create an account, a session, an admission, a waitlist entry, a
 *     participant workspace or a storage pool — so no seat is consumed and the
 *     TI-DELIVERY-001 rollback floor (`participant_workspace`,
 *     `storage_cohort_plan` empty) is never crossed;
 *   - complete a provider sign-in.
 * What they DO spend, only when accounts are enabled: one provider-start per
 * declared provider (a ten-minute `provider_login_state` row, purged within a
 * day, charged to the 200/hour global and 15/15-minute per-source start
 * budgets), and one logout with no session (no state).
 *
 * Run where the PUBLIC SITE is reachable; see README.md for the variables.
 */

const config = accountsConfig();
const enabled = config.expected === "enabled";
const declares = (method) => config.methods.includes(method);
const FOREIGN_ORIGIN = "https://attacker.invalid";

/**
 * One request with full control over its headers: `fetch` may add an Origin
 * of its own to a POST, and the CSRF checks below depend on sending none.
 * Never follows a redirect — a provider redirect is the thing under test.
 */
function call(method, path, { headers = {}, json } = {}) {
  const url = new URL(path, config.origin);
  const body = json === undefined ? undefined : Buffer.from(JSON.stringify(json));
  return new Promise((resolve) => {
    const request = https.request(
      {
        host: url.hostname,
        port: url.port || 443,
        servername: url.hostname,
        path: `${url.pathname}${url.search}`,
        method,
        headers: {
          accept: "application/json",
          ...(body ? { "content-type": "application/json", "content-length": body.length } : {}),
          ...headers,
        },
      },
      (response) => {
        let text = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          if (text.length < 262_144) text += chunk;
        });
        response.on("end", () =>
          resolve({
            status: response.statusCode,
            headers: response.headers,
            setCookies: response.headers["set-cookie"] ?? [],
            body: text,
          }),
        );
      },
    );
    request.on("error", (error) => resolve({ error: error.code ?? error.message }));
    request.setTimeout(config.timeoutMs, () => {
      request.destroy();
      resolve({ error: "ETIMEDOUT" });
    });
    request.end(body);
  });
}

const sameOrigin = { origin: config.origin };
const describe = (probe) =>
  probe.error ? `no response (${probe.error})` : `HTTP ${probe.status} ${probe.body.slice(0, 300)}`;

test(`GET /account/methods answers the declared state (${config.expected})`, async () => {
  const probe = await call("GET", "/account/methods");
  if (!enabled) {
    const verdict = classifyClosed(probe);
    assert.ok(verdict.closed, `accounts are declared disabled, but /account/methods is not closed: ${verdict.reason}`);
    return;
  }
  const parsed = parseMethods(probe);
  assert.ok(parsed.ok, `/account/methods on an enabled boundary: ${parsed.reason} — ${describe(probe)}`);
  // The declaration is what makes this a test: an incomplete provider or
  // email configuration does not stop the API, it silently drops the method.
  assert.deepEqual(
    parsed.methods,
    config.methods,
    `the deployment offers [${parsed.methods}] but declares [${config.methods}]; a missing method is a ` +
      `misconfiguration that fails closed (see testinbox_*_login_configured)`,
  );
  const caching = classifyPrivate(probe);
  assert.ok(caching.private, `/account/methods must not be cacheable: ${caching.reason}`);
  assert.deepEqual(probe.setCookies, [], "reading the methods must not set a cookie");
});

test("every account route is private, and closed while accounts are disabled", async () => {
  for (const route of ACCOUNT_ROUTES) {
    // On an open boundary a provider start records an attempt; the dedicated
    // provider tests below make exactly one each, so do not spend another here.
    if (enabled && route.startsSignIn) continue;
    const probe = await call(route.method, route.path, { headers: sameOrigin, json: route.json });
    assert.equal(probe.error, undefined, `${route.method} ${route.path}: ${describe(probe)}`);
    if (!enabled) {
      const verdict = classifyClosed(probe);
      assert.ok(verdict.closed, `${route.method} ${route.path} is not closed: ${verdict.reason} — ${describe(probe)}`);
      continue;
    }
    // Open boundary: whatever these unauthenticated, invalid requests are
    // answered with, it is private and never a server error.
    assert.ok(probe.status < 500 || probe.status === 503, `${route.method} ${route.path}: ${describe(probe)}`);
    // A provider start answers a 302 to the provider; everything else here is
    // a refusal or a public read, and none may be stored by a shared cache.
    const caching = classifyPrivate(probe);
    assert.ok(caching.private, `${route.method} ${route.path}: ${caching.reason}`);
  }
});

test("an unauthenticated session read is refused generically", async () => {
  const forged = `__Host-ti_session=${crypto.randomBytes(32).toString("base64url")}`;
  const reads = [
    ["no cookie", await call("GET", "/account/session")],
    ["a forged session cookie", await call("GET", "/account/session", { headers: { cookie: forged } })],
    ["the product alias, no cookie", await call("GET", "/account/product/workspace")],
    ["the waitlist, a forged cookie", await call("GET", "/account/waitlist", { headers: { cookie: forged } })],
  ];
  const shapes = [];
  for (const [name, probe] of reads) {
    if (!enabled) {
      const verdict = classifyClosed(probe);
      assert.ok(verdict.closed, `${name}: accounts are declared disabled, but ${verdict.reason}`);
      continue;
    }
    const refusal = refusalShape(probe);
    assert.ok(refusal.ok, `${name}: ${refusal.reason} — ${describe(probe)}`);
    assert.equal(refusal.shape.status, 401, `${name}: an unauthenticated read must be 401 — ${describe(probe)}`);
    shapes.push([name, refusal.shape]);
  }
  // No cookie and a forged one must be indistinguishable: a different answer
  // for "this credential exists but expired" would be an oracle.
  if (enabled) {
    const [[, none], [, forgedShape]] = shapes;
    assert.ok(
      sameRefusal(none, forgedShape),
      `a forged cookie is refused differently from no cookie: ${JSON.stringify(forgedShape)} vs ${JSON.stringify(none)}`,
    );
  }
});

test("a mutation without the exact public Origin is refused (CSRF)", async () => {
  const attempts = [
    ["logout, no Origin", await call("POST", "/account/logout")],
    ["logout, a foreign Origin", await call("POST", "/account/logout", { headers: { origin: FOREIGN_ORIGIN } })],
    [
      "email request, a foreign Origin",
      await call("POST", "/account/email/request", { headers: { origin: FOREIGN_ORIGIN }, json: { email: "not-an-address" } }),
    ],
    ["waitlist join, a foreign Origin", await call("POST", "/account/waitlist", { headers: { origin: FOREIGN_ORIGIN } })],
  ];
  for (const [name, probe] of attempts) {
    if (!enabled) {
      const verdict = classifyClosed(probe);
      assert.ok(verdict.closed, `${name}: accounts are declared disabled, but ${verdict.reason}`);
      continue;
    }
    const refusal = refusalShape(probe);
    assert.ok(refusal.ok, `${name}: ${refusal.reason} — ${describe(probe)}`);
    assert.equal(refusal.shape.status, 403, `${name} must be refused 403 — ${describe(probe)}`);
  }
  if (enabled) {
    // Positive control: the same logout from the public origin is accepted, so
    // the 403s above are the Origin check and not some other refusal. With no
    // session it changes nothing; it only clears an absent cookie.
    const control = await call("POST", "/account/logout", { headers: sameOrigin });
    assert.equal(control.status, 204, `logout from the exact public Origin must be accepted — ${describe(control)}`);
  }
});

for (const provider of ["google", "github"]) {
  test(`the ${provider} sign-in start matches the declared configuration`, async () => {
    const probe = await call("GET", `/account/oauth/authorize/${provider}`);
    if (!enabled) {
      const verdict = classifyClosed(probe);
      assert.ok(verdict.closed, `accounts are declared disabled, but ${verdict.reason}`);
      return;
    }
    if (!declares(provider)) {
      // An unconfigured provider is unavailable — never a redirect to a
      // half-configured client, never a cookie.
      const refusal = refusalShape(probe);
      assert.ok(refusal.ok, `${provider} is not declared: ${refusal.reason} — ${describe(probe)}`);
      assert.equal(refusal.shape.status, 503, `${provider} is not declared, so it must answer 503 — ${describe(probe)}`);
      // The API's method-level answer, not a closed boundary: an enabled
      // environment whose whole boundary is closed must not pass here.
      assert.match(String(refusal.shape.type), /\/signin-unavailable$/, `${provider}: ${describe(probe)}`);
      assert.deepEqual(probe.setCookies, [], `an unavailable ${provider} start must not set a cookie`);
      return;
    }
    const verdict = classifyAuthorizeRedirect(probe, { provider, publicOrigin: config.origin });
    assert.ok(verdict.ok, `${provider} start: ${verdict.reason} — ${describe(probe)}`);
    console.log(verdict.reason);
  });
}

test("a code request for a non-address or a TestInbox address is refused identically and sends nothing", async () => {
  const malformed = await call("POST", "/account/email/request", { headers: sameOrigin, json: { email: "not-an-address" } });
  // Random, so a regression that did accept it could not hit a real inbox's
  // budget twice; it is in the deployment's own receive domain either way.
  const local = `synthetic-${crypto.randomBytes(6).toString("hex")}`;
  const own = await call("POST", "/account/email/request", {
    headers: sameOrigin,
    json: { email: `${local}@${config.mailDomain}` },
  });
  if (!enabled) {
    for (const [name, probe] of [["malformed", malformed], ["receive-domain", own]]) {
      const verdict = classifyClosed(probe);
      assert.ok(verdict.closed, `${name}: accounts are declared disabled, but ${verdict.reason}`);
    }
    return;
  }
  const a = refusalShape(malformed);
  const b = refusalShape(own);
  assert.ok(a.ok, `malformed address: ${a.reason} — ${describe(malformed)}`);
  assert.ok(b.ok, `receive-domain address: ${b.reason} — ${describe(own)}`);
  // With email sign-in ready both are 400 invalid-request; without it, both
  // 503. Either way the two must be indistinguishable, and neither may start
  // a challenge (no login cookie, no challengeId — refusalShape checks both).
  assert.equal(a.shape.status, declares("email") ? 400 : 503, `malformed address — ${describe(malformed)}`);
  assert.match(
    String(a.shape.type),
    declares("email") ? /\/invalid-request$/ : /\/signin-unavailable$/,
    `malformed address — ${describe(malformed)}`,
  );
  assert.ok(
    sameRefusal(a.shape, b.shape),
    `a TestInbox address must be refused exactly like a malformed one: ${JSON.stringify(b.shape)} vs ${JSON.stringify(a.shape)}`,
  );
  assert.deepEqual(own.setCookies, [], "a refused code request must not start a login attempt");
});

test("the sign-in pages match the declared state and advertise no places while disabled", async () => {
  for (const path of ["/login", "/signup"]) {
    const probe = await call("GET", path, { headers: { accept: "text/html" } });
    const verdict = classifySignInPage(probe);
    if (!enabled) {
      assert.ok(verdict.ok, `${path}: ${verdict.reason}`);
      continue;
    }
    // Enabled: the copy is the site's business; the page must exist.
    assert.equal(probe.status, 200, `${path} must be served when accounts are enabled — ${describe(probe)}`);
  }
});
