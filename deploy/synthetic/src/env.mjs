/**
 * Everything the synthetic suite needs to reach a DEPLOYED environment.
 *
 * Nothing here has a localhost default. The suite exists to prove a deployment
 * works; silently falling back to a local process would make it pass while
 * testing nothing, which is the exact failure mode it is meant to catch.
 */

function required(name) {
  const value = process.env[name];
  if (!value || value.trim() === "") {
    throw new Error(
      `${name} is required. The synthetic suite runs against a deployed environment; ` +
        `see docs/dev/staging.md for the full variable list.`,
    );
  }
  return value.trim();
}

function optionalInt(name, fallback) {
  const raw = process.env[name];
  if (!raw || raw.trim() === "") return fallback;
  const value = Number.parseInt(raw, 10);
  if (!Number.isFinite(value)) throw new Error(`${name} must be an integer, got ${raw}`);
  return value;
}

export const config = Object.freeze({
  /** HTTPS origin of the deployed API, through the real ingress. */
  baseUrl: required("TESTINBOX_BASE_URL"),
  /** Dedicated synthetic credential — never a personal or admin key. */
  apiKey: required("TESTINBOX_API_KEY"),
  /** Staging SMTP ingress. Private by design (§10/§22); reachable from the runner only. */
  smtpHost: required("TESTINBOX_SMTP_HOST"),
  smtpPort: optionalInt("TESTINBOX_SMTP_PORT", 2525),
  /** The deployment's server-side wait-window cap, in seconds. */
  waitWindowSeconds: optionalInt("TESTINBOX_WAIT_WINDOW_SECONDS", 60),
  /** How long a wait is left parked before the message is delivered (§18). */
  parkedWaitSeconds: optionalInt("TESTINBOX_PARKED_WAIT_SECONDS", 10),
  /** Plaintext origin, when the environment has one — used to prove it only redirects. */
  httpBaseUrl: process.env.TESTINBOX_HTTP_BASE_URL?.trim() || undefined,
});

/**
 * No opt-out. A plaintext run would send `TESTINBOX_API_KEY` in the clear while
 * proving nothing about the TLS termination it is supposed to be exercising —
 * and an escape hatch for that is the kind that ends up set in CI.
 * Against a private CA, trust the CA (`NODE_EXTRA_CA_CERTS`); do not drop to
 * plaintext and do not disable verification.
 */
if (!config.baseUrl.startsWith("https://")) {
  throw new Error(
    `TESTINBOX_BASE_URL must be https:// (got ${config.baseUrl.split("://")[0]}://…). ` +
      `For a private CA, set NODE_EXTRA_CA_CERTS instead.`,
  );
}
