/**
 * Classification of responses from the human account boundary (ADR-036),
 * reached through the public site's same-origin `/account/*` proxy.
 *
 * Two layers can legitimately answer an account route: the site's proxy
 * (testinbox-web, `lib/auth/server/account-proxy.ts`) and the API behind it
 * (`HumanAccountBoundaryFilter` and the account controllers). The invariants
 * below hold whichever layer answers, which is what lets one suite run against
 * a site whose own switch is off as well as one that forwards to an API whose
 * switch is off. Like `edge.mjs`, these functions assert the property, not one
 * layer's mechanism, and they fail closed on anything they cannot interpret.
 *
 * Pure functions over plain probe objects — `{status, headers, setCookies,
 * body}` — so `unit/accounts-classifier.test.mjs` proves every branch without a
 * deployed environment.
 */

/**
 * Every route the site proxies and the API serves, in the shape a browser uses
 * it. `startsSignIn` marks the two that, on an OPEN boundary, record a provider
 * attempt and spend a start budget; the suite probes those once, on purpose.
 */
export const ACCOUNT_ROUTES = Object.freeze([
  { method: "GET", path: "/account/methods" },
  { method: "GET", path: "/account/session" },
  { method: "POST", path: "/account/logout" },
  { method: "POST", path: "/account/admission" },
  { method: "GET", path: "/account/waitlist" },
  { method: "POST", path: "/account/waitlist" },
  { method: "DELETE", path: "/account/waitlist" },
  { method: "POST", path: "/account/email/request", json: { email: "not-an-address" } },
  { method: "POST", path: "/account/email/verify", json: { challengeId: "00000000-0000-0000-0000-000000000000", code: "00000000" } },
  { method: "GET", path: "/account/oauth/authorize/google", startsSignIn: true },
  { method: "GET", path: "/account/oauth/authorize/github", startsSignIn: true },
  { method: "GET", path: "/account/oauth/callback/google" },
  { method: "GET", path: "/account/oauth/callback/github" },
  { method: "GET", path: "/account/product/workspace" },
]);

/** The session, login-attempt and provider-attempt cookies the API may set. */
const ACCOUNT_COOKIES = new Set(["__Host-ti_session", "__Host-ti_login", "__Host-ti_oauth"]);

/** Members that only an authenticated or admitted answer may carry. */
const AUTHORITY_MEMBERS = ["csrfToken", "contactEmail", "workspaceId", "projectId", "challengeId", "methods", "admission"];

function header(probe, name) {
  const headers = probe.headers ?? {};
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() === name) return Array.isArray(value) ? value.join(", ") : String(value);
  }
  return undefined;
}

function json(probe) {
  try {
    const value = JSON.parse(probe.body ?? "");
    return value !== null && typeof value === "object" && !Array.isArray(value) ? value : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Account answers are private: `no-store`, never `public`. A cached account
 * answer — a CSRF token, a session view, a provider redirect carrying state —
 * served to the next visitor is the failure this guards.
 */
export function classifyPrivate(probe) {
  const cacheControl = (header(probe, "cache-control") ?? "").toLowerCase();
  if (!/(^|[\s,])no-store($|[\s,])/.test(cacheControl)) {
    return { private: false, reason: `Cache-Control is "${cacheControl}", not no-store` };
  }
  if (/(^|[\s,])public($|[\s,])/.test(cacheControl)) {
    return { private: false, reason: `Cache-Control "${cacheControl}" allows shared caches` };
  }
  return { private: true, reason: "no-store" };
}

/**
 * The closed state: what every account route must answer while accounts are
 * disabled. Either layer may produce it — the site's own `503 Sign-in is
 * unavailable` (or `Console unavailable` for the product aliases), or the
 * API's `503 accounts-unavailable` passed through — but
 * in every case: 503, private, no cookie, a problem body, and nothing that
 * only an open boundary would return.
 */
export function classifyClosed(probe) {
  if (probe.error !== undefined) return { closed: false, reason: `no response (${probe.error})` };
  if (probe.status !== 503) return { closed: false, reason: `status ${probe.status}, not 503` };
  const caching = classifyPrivate(probe);
  if (!caching.private) return { closed: false, reason: caching.reason };
  if ((probe.setCookies ?? []).length > 0) {
    return { closed: false, reason: `a closed boundary set cookie(s): ${probe.setCookies.map((c) => c.split("=")[0]).join(", ")}` };
  }
  const body = json(probe);
  if (body === undefined) return { closed: false, reason: "the 503 carries no JSON problem body" };
  const leaked = AUTHORITY_MEMBERS.filter((member) => member in body);
  if (leaked.length > 0) return { closed: false, reason: `the 503 carries ${leaked.join(", ")}` };
  const type = typeof body.type === "string" ? body.type : "";
  if (type.endsWith("/accounts-unavailable")) return { closed: true, reason: "the API answers accounts-unavailable" };
  // The site's two proxies close with their own titles: account routes and the
  // console's product aliases (`/account/product/*`).
  if (body.title === "Sign-in is unavailable" || body.title === "Console unavailable") {
    return { closed: true, reason: `the site answers ${body.title}` };
  }
  // `signin-unavailable` is a configured boundary with an incomplete METHOD, not
  // a closed boundary; anything else is unrecognised. Both fail closed.
  return { closed: false, reason: `a 503 that is not the closed state (type ${type || "none"}, title ${body.title})` };
}

/**
 * `GET /account/methods` on an open boundary: exactly `{methods: [...]}`,
 * a unique subset of the three known names, and no count, capacity or account
 * information (OpenAPI: additionalProperties false).
 */
export function parseMethods(probe) {
  if (probe.status !== 200) return { ok: false, reason: `status ${probe.status}, not 200` };
  const body = json(probe);
  if (body === undefined) return { ok: false, reason: "no JSON object body" };
  const keys = Object.keys(body);
  if (keys.length !== 1 || keys[0] !== "methods" || !Array.isArray(body.methods)) {
    return { ok: false, reason: `body members are [${keys.join(", ")}], expected exactly [methods]` };
  }
  const methods = body.methods;
  if (methods.some((m) => !["email", "google", "github"].includes(m)) || new Set(methods).size !== methods.length) {
    return { ok: false, reason: `methods ${JSON.stringify(methods)} are not a unique subset of email, google, github` };
  }
  return { ok: true, methods: [...methods].sort(), reason: "well-formed" };
}

/**
 * A request the boundary must refuse without saying why beyond a generic
 * problem: unauthenticated reads, forged cookies, bad Origin. Returns the
 * comparable shape (status, type, title) so two refusals can be proven
 * identical, and refuses if the answer set a cookie or carried authority.
 */
export function refusalShape(probe) {
  if (probe.error !== undefined) return { ok: false, reason: `no response (${probe.error})` };
  if ((probe.setCookies ?? []).some((c) => ACCOUNT_COOKIES.has(c.split("=")[0]) && !/=;|=$|max-age=0/i.test(c))) {
    return { ok: false, reason: "a refusal issued a live account cookie" };
  }
  const body = json(probe);
  if (body === undefined) return { ok: false, reason: `status ${probe.status} without a JSON problem body` };
  const leaked = AUTHORITY_MEMBERS.filter((member) => member in body);
  if (leaked.length > 0) return { ok: false, reason: `the refusal carries ${leaked.join(", ")}` };
  return { ok: true, shape: { status: probe.status, type: body.type ?? null, title: body.title ?? null }, reason: "refused" };
}

/** True when two refusals cannot be told apart by status, problem type or title. */
export function sameRefusal(a, b) {
  return a.status === b.status && a.type === b.type && a.title === b.title;
}

const PROVIDERS = Object.freeze({
  google: {
    origin: "https://accounts.google.com",
    path: "/o/oauth2/v2/auth",
    scope: ["email", "openid"],
    nonce: true,
  },
  github: {
    origin: "https://github.com",
    path: "/login/oauth/authorize",
    scope: ["read:user", "user:email"],
    nonce: false,
  },
});

const AUTHORIZE_PARAMETERS = new Set([
  "response_type",
  "client_id",
  "redirect_uri",
  "scope",
  "state",
  "nonce",
  "code_challenge",
  "code_challenge_method",
]);

/**
 * The redirect that starts a provider sign-in (ADR-036 §3, PROVIDER-LOGIN.md
 * M3f): a 302 to the provider's fixed authorization endpoint, the code flow
 * with PKCE S256, opaque state, a Google nonce, the fixed scopes, and the
 * callback built from the configured public origin — exactly, because a
 * callback derived from a Host or forwarded header is the attack. Plus one
 * private, host-only, short-lived attempt cookie.
 */
export function classifyAuthorizeRedirect(probe, { provider, publicOrigin }) {
  const expected = PROVIDERS[provider];
  if (!expected) return { ok: false, reason: `unknown provider ${provider}` };
  if (probe.status !== 302) return { ok: false, reason: `status ${probe.status}, not 302` };
  const caching = classifyPrivate(probe);
  if (!caching.private) return { ok: false, reason: caching.reason };
  let location;
  try {
    location = new URL(header(probe, "location") ?? "");
  } catch {
    return { ok: false, reason: `no absolute Location (${header(probe, "location")})` };
  }
  if (location.origin !== expected.origin || location.pathname !== expected.path) {
    return { ok: false, reason: `redirects to ${location.origin}${location.pathname}, not ${expected.origin}${expected.path}` };
  }
  if (location.username || location.password || location.hash) {
    return { ok: false, reason: "the authorization URL carries userinfo or a fragment" };
  }
  const keys = [...location.searchParams.keys()];
  if (new Set(keys).size !== keys.length) return { ok: false, reason: "a repeated authorization parameter" };
  const unexpected = keys.filter((k) => !AUTHORIZE_PARAMETERS.has(k));
  if (unexpected.length > 0) return { ok: false, reason: `unexpected parameter(s) ${unexpected.join(", ")}` };
  const p = (name) => location.searchParams.get(name);
  const callback = `${publicOrigin}/account/oauth/callback/${provider}`;
  const checks = [
    [p("response_type") === "code", `response_type ${p("response_type")}, not code`],
    [(p("client_id") ?? "").length > 0, "no client_id"],
    [p("redirect_uri") === callback, `redirect_uri ${p("redirect_uri")}, not exactly ${callback}`],
    [
      (p("scope") ?? "").split(" ").sort().join(" ") === expected.scope.join(" "),
      `scope "${p("scope")}", not exactly "${expected.scope.join(" ")}"`,
    ],
    [/^[A-Za-z0-9_=-]{16,256}$/.test(p("state") ?? ""), "state missing or not an opaque 16–256 character value"],
    [p("code_challenge_method") === "S256", `code_challenge_method ${p("code_challenge_method")}, not S256`],
    [/^[A-Za-z0-9_-]{43}$/.test(p("code_challenge") ?? ""), "code_challenge is not a base64url SHA-256 (43 characters)"],
    [!expected.nonce || /^[A-Za-z0-9_=-]{16,}$/.test(p("nonce") ?? ""), "the Google request carries no nonce"],
  ];
  const failed = checks.find(([ok]) => !ok);
  if (failed) return { ok: false, reason: failed[1] };
  const cookies = probe.setCookies ?? [];
  if (cookies.length !== 1) return { ok: false, reason: `expected exactly one attempt cookie, got ${cookies.length}` };
  const cookie = classifyAttemptCookie(cookies[0]);
  if (!cookie.ok) return { ok: false, reason: cookie.reason };
  return { ok: true, reason: `${provider}: code flow, PKCE S256${expected.nonce ? ", nonce" : ""}, exact callback ${callback}` };
}

/** `__Host-ti_oauth`: Secure, HttpOnly, SameSite=Lax, Path=/, no Domain, at most ten minutes. */
export function classifyAttemptCookie(raw) {
  const [pair, ...parts] = raw.split(";").map((part) => part.trim());
  const separator = pair.indexOf("=");
  const name = pair.slice(0, separator);
  const value = pair.slice(separator + 1);
  if (name !== "__Host-ti_oauth") return { ok: false, reason: `cookie ${name}, not __Host-ti_oauth` };
  if (!/^[A-Za-z0-9_-]{43}$/.test(value)) return { ok: false, reason: "the attempt cookie is not an opaque 256-bit value" };
  const attributes = new Map(
    parts.map((part) => {
      const split = part.indexOf("=");
      return split < 0 ? [part.toLowerCase(), ""] : [part.slice(0, split).toLowerCase(), part.slice(split + 1)];
    }),
  );
  const maxAge = Number(attributes.get("max-age"));
  const checks = [
    [attributes.has("secure"), "not Secure"],
    [attributes.has("httponly"), "not HttpOnly"],
    [(attributes.get("samesite") ?? "").toLowerCase() === "lax", "not SameSite=Lax"],
    [attributes.get("path") === "/", "Path is not /"],
    [!attributes.has("domain"), "carries a Domain"],
    [Number.isInteger(maxAge) && maxAge > 0 && maxAge <= 600, `Max-Age ${attributes.get("max-age")} is not within 1–600 s`],
  ];
  const failed = checks.find(([ok]) => !ok);
  return failed ? { ok: false, reason: `the attempt cookie is ${failed[1]}` } : { ok: true, reason: "private attempt cookie" };
}

/**
 * Copy that would advertise beta places on a site whose accounts are off. The
 * disabled site must say access is restricted, not invite people to compete
 * for places nobody can take.
 */
const PLACE_ADVERTISING = [
  /\b\d+\s+participant\s+(?:places?|accounts?|seats?)\b/i,
  /\bparticipant\s+places?\b/i,
  /\b(?:places?|seats?)\s+(?:left|remaining|available)\b/i,
  /does not guarantee a beta place/i,
];

export function classifySignInPage(probe) {
  if (probe.error !== undefined) return { ok: false, reason: `no response (${probe.error})` };
  if (probe.status !== 200) return { ok: false, reason: `status ${probe.status}, not 200` };
  if (!/text\/html/i.test(header(probe, "content-type") ?? "")) return { ok: false, reason: "not an HTML page" };
  const body = probe.body ?? "";
  if (!/testinbox/i.test(body)) return { ok: false, reason: "the page does not look like the TestInbox site (positive control)" };
  const advertised = PLACE_ADVERTISING.map((pattern) => body.match(pattern)?.[0]).filter(Boolean);
  if (advertised.length > 0) return { ok: false, advertises: true, reason: `the page advertises places: "${advertised.join('", "')}"` };
  return { ok: true, advertises: false, reason: "no places advertised" };
}
