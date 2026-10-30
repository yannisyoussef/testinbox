import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ACCOUNT_ROUTES,
  classifyAttemptCookie,
  classifyAuthorizeRedirect,
  classifyClosed,
  classifySignInPage,
  parseMethods,
  refusalShape,
  sameRefusal,
} from "../src/accounts.mjs";
import { accountsConfig } from "../src/env.mjs";

/**
 * Self-tests for the account-boundary classifiers. The fixtures are the real
 * response shapes: the API's `Problems.of` bodies and boundary headers
 * (`HumanAccountBoundaryFilter`), and the site proxy's own problems
 * (testinbox-web `account-proxy.ts` / `product-proxy.ts`, `PRIVATE` headers).
 * Needs no deployed environment.
 */

const PRIVATE = { "cache-control": "no-store", pragma: "no-cache", "x-content-type-options": "nosniff" };
const problem = (status, type, title) =>
  JSON.stringify({ type: `https://testinbox.email/problems/${type}`, title, status, correlationId: "c0ffee" });
const ORIGIN = "https://testinbox.email";
const VALUE = "A".repeat(43);

const apiClosed = { status: 503, headers: { ...PRIVATE, "content-type": "application/problem+json" }, setCookies: [], body: problem(503, "accounts-unavailable", "Service Unavailable") };
const siteClosed = { status: 503, headers: PRIVATE, setCookies: [], body: JSON.stringify({ type: "about:blank", title: "Sign-in is unavailable", status: 503 }) };
const consoleClosed = { status: 503, headers: PRIVATE, setCookies: [], body: JSON.stringify({ type: "about:blank", title: "Console unavailable", status: 503 }) };

test("both layers' closed answers are the closed state", () => {
  for (const probe of [apiClosed, siteClosed, consoleClosed]) {
    assert.equal(classifyClosed(probe).closed, true, classifyClosed(probe).reason);
  }
});

test("a closed answer that is cacheable, sets a cookie or carries authority is not closed", () => {
  assert.equal(classifyClosed({ ...apiClosed, headers: { "cache-control": "max-age=60" } }).closed, false);
  assert.equal(classifyClosed({ ...apiClosed, headers: { "cache-control": "public, no-store" } }).closed, false);
  assert.equal(classifyClosed({ ...apiClosed, setCookies: [`__Host-ti_login=${VALUE}; Path=/`] }).closed, false);
  assert.equal(
    classifyClosed({ ...siteClosed, body: JSON.stringify({ title: "Sign-in is unavailable", methods: [] }) }).closed,
    false,
  );
});

test("a method-level 503, a 404, a 200 or no response is not the closed state", () => {
  // signin-unavailable means the boundary is OPEN with one method incomplete.
  assert.equal(classifyClosed({ ...apiClosed, body: problem(503, "signin-unavailable", "Service Unavailable") }).closed, false);
  assert.equal(classifyClosed({ ...apiClosed, status: 404 }).closed, false);
  assert.equal(classifyClosed({ status: 200, headers: PRIVATE, setCookies: [], body: '{"methods":[]}' }).closed, false);
  assert.equal(classifyClosed({ error: "ETIMEDOUT" }).closed, false);
  // A Next.js 404 page for a site without the proxy is not "closed" either.
  assert.equal(classifyClosed({ status: 503, headers: PRIVATE, setCookies: [], body: "<html>Service Unavailable</html>" }).closed, false);
});

test("methods: exactly {methods} with a unique known subset", () => {
  const ok = (body) => parseMethods({ status: 200, headers: PRIVATE, body: JSON.stringify(body) });
  assert.deepEqual(ok({ methods: ["github", "email"] }).methods, ["email", "github"]);
  assert.deepEqual(ok({ methods: [] }).methods, []);
  assert.equal(ok({ methods: ["email", "email"] }).ok, false);
  assert.equal(ok({ methods: ["password"] }).ok, false);
  assert.equal(ok({ methods: ["email"], capacity: 30 }).ok, false, "an extra member could leak cohort figures");
  assert.equal(ok({ methods: ["email"], remaining: 2 }).ok, false);
  assert.equal(parseMethods({ status: 503, body: "{}" }).ok, false);
});

test("refusals: comparable shape, no live cookie, no authority", () => {
  const unauthorized = { status: 401, headers: PRIVATE, setCookies: [], body: problem(401, "unauthorized", "Unauthorized") };
  const forged = { ...unauthorized, body: problem(401, "unauthorized", "Unauthorized").replace("c0ffee", "beef") };
  const a = refusalShape(unauthorized);
  const b = refusalShape(forged);
  assert.ok(a.ok && b.ok);
  assert.ok(sameRefusal(a.shape, b.shape), "only the correlation id differs");
  assert.equal(sameRefusal(a.shape, refusalShape({ ...unauthorized, body: problem(401, "session-expired", "Unauthorized") }).shape), false);
  assert.equal(refusalShape({ ...unauthorized, setCookies: [`__Host-ti_login=${VALUE}; Max-Age=600`] }).ok, false);
  // Clearing the session cookie (logout's answer) is not issuing one.
  assert.equal(refusalShape({ ...unauthorized, setCookies: ["__Host-ti_session=; Max-Age=0; Path=/"] }).ok, true);
  assert.equal(refusalShape({ ...unauthorized, body: JSON.stringify({ status: 401, challengeId: "x" }) }).ok, false);
  assert.equal(refusalShape({ error: "ECONNRESET" }).ok, false);
});

function authorize(provider, overrides = {}, cookie = `__Host-ti_oauth=${VALUE}; Max-Age=600; Path=/; Secure; HttpOnly; SameSite=Lax`) {
  const google = provider === "google";
  const params = new URLSearchParams({
    response_type: "code",
    client_id: "client-123",
    scope: google ? "openid email" : "read:user user:email",
    state: "s".repeat(44),
    redirect_uri: `${ORIGIN}/account/oauth/callback/${provider}`,
    ...(google ? { nonce: "n".repeat(43) } : {}),
    code_challenge: "c".repeat(43),
    code_challenge_method: "S256",
    ...overrides,
  });
  for (const [key, value] of Object.entries(overrides)) if (value === undefined) params.delete(key);
  const base = google ? "https://accounts.google.com/o/oauth2/v2/auth" : "https://github.com/login/oauth/authorize";
  return { status: 302, headers: { ...PRIVATE, location: `${base}?${params}` }, setCookies: cookie ? [cookie] : [] };
}

test("a correct provider start passes for both providers", () => {
  for (const provider of ["google", "github"]) {
    const verdict = classifyAuthorizeRedirect(authorize(provider), { provider, publicOrigin: ORIGIN });
    assert.ok(verdict.ok, verdict.reason);
  }
});

test("a provider start fails on each broken protocol property", () => {
  const cases = [
    ["google", { redirect_uri: "https://app.testinbox.email/account/oauth/callback/google" }, /redirect_uri/],
    ["google", { redirect_uri: `${ORIGIN}/account/oauth/callback/google/` }, /redirect_uri/],
    ["github", { code_challenge_method: "plain" }, /S256/],
    ["github", { code_challenge: undefined, code_challenge_method: undefined }, /S256/],
    ["google", { nonce: undefined }, /nonce/],
    ["google", { state: undefined }, /state/],
    ["google", { state: "short" }, /state/],
    ["github", { scope: "read:user user:email repo" }, /scope/],
    ["google", { scope: "openid email https://www.googleapis.com/auth/gmail.readonly" }, /scope/],
    ["google", { response_type: "token" }, /response_type/],
    ["github", { client_id: "" }, /client_id/],
    ["github", { prompt: "none" }, /unexpected parameter/],
  ];
  for (const [provider, overrides, reason] of cases) {
    const verdict = classifyAuthorizeRedirect(authorize(provider, overrides), { provider, publicOrigin: ORIGIN });
    assert.equal(verdict.ok, false, `${JSON.stringify(overrides)} should fail`);
    assert.match(verdict.reason, reason);
  }
  const wrongHost = { ...authorize("github"), headers: { ...PRIVATE, location: "https://github.evil/login/oauth/authorize?x=1" } };
  assert.match(classifyAuthorizeRedirect(wrongHost, { provider: "github", publicOrigin: ORIGIN }).reason, /redirects to/);
  assert.equal(classifyAuthorizeRedirect({ ...authorize("google"), status: 200 }, { provider: "google", publicOrigin: ORIGIN }).ok, false);
  assert.equal(
    classifyAuthorizeRedirect({ ...authorize("google"), headers: { ...authorize("google").headers, "cache-control": "max-age=300" } }, { provider: "google", publicOrigin: ORIGIN }).ok,
    false,
  );
  assert.match(classifyAuthorizeRedirect(authorize("google", {}, null), { provider: "google", publicOrigin: ORIGIN }).reason, /exactly one attempt cookie/);
});

test("the attempt cookie must be a private host-only short-lived __Host- cookie", () => {
  const good = `__Host-ti_oauth=${VALUE}; Max-Age=600; Path=/; Secure; HttpOnly; SameSite=Lax`;
  assert.ok(classifyAttemptCookie(good).ok);
  for (const [bad, reason] of [
    [good.replace("; HttpOnly", ""), /HttpOnly/],
    [good.replace("; Secure", ""), /Secure/],
    [good.replace("SameSite=Lax", "SameSite=None"), /SameSite/],
    [`${good}; Domain=testinbox.email`, /Domain/],
    [good.replace("Max-Age=600", "Max-Age=86400"), /Max-Age/],
    [good.replace("__Host-ti_oauth", "ti_oauth"), /not __Host-ti_oauth/],
    [good.replace(VALUE, "short"), /opaque/],
  ]) {
    assert.match(classifyAttemptCookie(bad).reason, reason);
  }
});

test("the disabled sign-in pages are recognised; enabled place-advertising copy is caught", () => {
  const page = (text) => ({ status: 200, headers: { "content-type": "text/html; charset=utf-8" }, body: `<html><title>TestInbox</title>${text}</html>` });
  // Copy of the site's disabled (invitation-only) state.
  const disabled = page(
    "<p>Access is by invitation while TestInbox is in private beta.</p><span>private beta · invitation only</span>" +
      "<p>Access is by invitation only. Each admitted account has one private workspace.</p>",
  );
  assert.ok(classifySignInPage(disabled).ok, classifySignInPage(disabled).reason);
  // Copy of the site's enabled state, and of the earlier footer that advertised places while disabled.
  for (const text of [
    "<span>limited beta · 30 participant places</span>",
    "<p>Google, GitHub or a verified email. Up to 30 participant accounts.</p>",
    "<p>Registration does not guarantee a beta place.</p>",
    "<p>3 places left</p>",
  ]) {
    assert.equal(classifySignInPage(page(text)).advertises, true, text);
  }
  assert.equal(classifySignInPage({ status: 200, headers: { "content-type": "text/html" }, body: "<html>nginx</html>" }).ok, false, "positive control");
  assert.equal(classifySignInPage({ status: 404, headers: { "content-type": "text/html" }, body: "TestInbox" }).ok, false);
});

test("every proxied route is covered, and only the provider starts spend a budget", () => {
  const paths = new Set(ACCOUNT_ROUTES.map((r) => `${r.method} ${r.path}`));
  for (const required of [
    "GET /account/methods",
    "GET /account/session",
    "POST /account/logout",
    "POST /account/admission",
    "POST /account/email/request",
    "POST /account/email/verify",
    "GET /account/waitlist",
    "POST /account/waitlist",
    "DELETE /account/waitlist",
    "GET /account/oauth/authorize/google",
    "GET /account/oauth/callback/github",
    "GET /account/product/workspace",
  ]) {
    assert.ok(paths.has(required), required);
  }
  assert.deepEqual(
    ACCOUNT_ROUTES.filter((r) => r.startsSignIn).map((r) => r.path),
    ["/account/oauth/authorize/google", "/account/oauth/authorize/github"],
  );
});

function withEnv(values, fn) {
  const saved = {};
  for (const key of Object.keys(values)) {
    saved[key] = process.env[key];
    if (values[key] === undefined) delete process.env[key];
    else process.env[key] = values[key];
  }
  try {
    return fn();
  } finally {
    for (const [key, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
}

const BASE_ENV = {
  TESTINBOX_ACCOUNTS_ORIGIN: ORIGIN,
  TESTINBOX_ACCOUNTS_EXPECTED: "disabled",
  TESTINBOX_ACCOUNTS_EXPECTED_METHODS: undefined,
  TESTINBOX_MAIL_DOMAIN: "inbox.testinbox.email",
};

test("the account configuration is declared, never inferred, and refuses ambiguity loudly", () => {
  assert.equal(withEnv(BASE_ENV, () => accountsConfig()).expected, "disabled");
  assert.deepEqual(
    withEnv({ ...BASE_ENV, TESTINBOX_ACCOUNTS_EXPECTED: "enabled", TESTINBOX_ACCOUNTS_EXPECTED_METHODS: "github,email" }, () => accountsConfig()).methods,
    ["email", "github"],
  );
  assert.deepEqual(
    withEnv({ ...BASE_ENV, TESTINBOX_ACCOUNTS_EXPECTED: "enabled", TESTINBOX_ACCOUNTS_EXPECTED_METHODS: "none" }, () => accountsConfig()).methods,
    [],
  );
  for (const [override, message] of [
    [{ TESTINBOX_ACCOUNTS_EXPECTED: undefined }, /TESTINBOX_ACCOUNTS_EXPECTED is required/],
    [{ TESTINBOX_ACCOUNTS_EXPECTED: "auto" }, /"disabled" or "enabled"/],
    [{ TESTINBOX_ACCOUNTS_EXPECTED: "enabled" }, /TESTINBOX_ACCOUNTS_EXPECTED_METHODS is required/],
    [{ TESTINBOX_ACCOUNTS_EXPECTED: "enabled", TESTINBOX_ACCOUNTS_EXPECTED_METHODS: "email,password" }, /subset/],
    [{ TESTINBOX_ACCOUNTS_EXPECTED: "enabled", TESTINBOX_ACCOUNTS_EXPECTED_METHODS: "email,email" }, /subset/],
    [{ TESTINBOX_ACCOUNTS_ORIGIN: "http://testinbox.email" }, /https:\/\/ origin/],
    [{ TESTINBOX_ACCOUNTS_ORIGIN: "https://testinbox.email/console" }, /no path/],
    [{ TESTINBOX_ACCOUNTS_ORIGIN: undefined }, /TESTINBOX_ACCOUNTS_ORIGIN is required/],
    [{ TESTINBOX_MAIL_DOMAIN: undefined }, /TESTINBOX_MAIL_DOMAIN is required/],
  ]) {
    assert.throws(() => withEnv({ ...BASE_ENV, ...override }, () => accountsConfig()), message);
  }
  // A trailing slash is tolerated and normalised; the API compares origins exactly.
  assert.equal(withEnv({ ...BASE_ENV, TESTINBOX_ACCOUNTS_ORIGIN: `${ORIGIN}/` }, () => accountsConfig()).origin, ORIGIN);
});
