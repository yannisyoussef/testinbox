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
  /** Plaintext origin — proven to only ever redirect. */
  httpBaseUrl: required("TESTINBOX_HTTP_BASE_URL"),
  /**
   * True when the edge is this repository's own nginx (the CI rehearsal),
   * which lets the unknown-Host assertion demand the stronger `return 444`
   * behaviour instead of merely a refusal.
   */
  edgeIsReference: process.env.TESTINBOX_EDGE === "nginx-reference",
});

/**
 * Everything the mail-edge contract suite needs.
 *
 * Deliberately NOT part of `config`: the deployed staging estate has no Postfix
 * edge — the real one is a separate dormant Contabo host — so requiring these
 * of every synthetic run would break the deployment gate. The edge suite is a
 * separate target with its own minimum count, which is also why it must not be
 * expressed as skips: a suite that silently skips is a suite nobody notices has
 * stopped running.
 */
export function edgeConfig() {
  return Object.freeze({
    /** The Postfix edge's SMTP listener, reachable only inside the rehearsal. */
    host: required("TESTINBOX_EDGE_SMTP_HOST"),
    port: optionalInt("TESTINBOX_EDGE_SMTP_PORT", 2526),
    /** The tenant domain the edge relays for. */
    mailDomain: required("TESTINBOX_MAIL_DOMAIN"),
    /** The externally accepted ceiling, from deploy/mail-edge/contract.yaml. */
    messageSizeLimit: optionalInt("TESTINBOX_EDGE_MESSAGE_SIZE_LIMIT", 15728640),
    /** The reserved operational recipient (ADR-021 denylist, routed local:). */
    operationalRecipient: process.env.TESTINBOX_EDGE_POSTMASTER?.trim() || "postmaster",
  });
}

/**
 * The key-administration credential, resolved lazily.
 *
 * Deliberately NOT part of `config`: the deployment gate must not require it.
 * The gate's own credential stays least-privilege — `inboxes:write` and
 * `messages:read` — because it lives in an automated runner, and a credential
 * that can mint credentials is a much larger thing to leave there. The product
 * suite that exercises the lifecycle asks for this one only when it runs, and
 * fails loudly rather than skipping if it is absent: a suite that silently
 * skips is a suite nobody notices has stopped running.
 */
export function adminApiKey() {
  return required("TESTINBOX_ADMIN_API_KEY");
}

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
