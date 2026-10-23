/**
 * Everything the synthetic suite needs to reach a DEPLOYED environment.
 *
 * Nothing here has a localhost default. The suite exists to prove a deployment
 * works; silently falling back to a local process would make it pass while
 * testing nothing, which is the exact failure mode it is meant to catch.
 */

import net from "node:net";

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

let deployment;

/**
 * The deployment gate's configuration, resolved on first use rather than at
 * import. The origin-isolation and build-identity suites import this module
 * for their own config and must not be forced to carry the synthetic API key
 * and the private SMTP host onto a machine that needs neither.
 */
export function deploymentConfig() {
  if (deployment) return deployment;
  const baseUrl = required("TESTINBOX_BASE_URL");
  // No opt-out. A plaintext run would send `TESTINBOX_API_KEY` in the clear while
  // proving nothing about the TLS termination it is supposed to be exercising —
  // and an escape hatch for that is the kind that ends up set in CI. Against a
  // private CA, trust the CA (`NODE_EXTRA_CA_CERTS`); never disable verification.
  if (!baseUrl.startsWith("https://")) {
    throw new Error(
      `TESTINBOX_BASE_URL must be https:// (got ${baseUrl.split("://")[0]}://…). ` +
        `For a private CA, set NODE_EXTRA_CA_CERTS instead.`,
    );
  }
  deployment = Object.freeze({
    /** HTTPS origin of the deployed API, through the real ingress. */
    baseUrl,
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
  return deployment;
}

/**
 * The same object, as a property-lazy view, so existing suites keep writing
 * `config.baseUrl` and pay for resolution on first access — which for them
 * is still module load, exactly as before.
 */
export const config = new Proxy(Object.freeze({}), {
  get: (_, key) => deploymentConfig()[key],
  has: (_, key) => key in deploymentConfig(),
  ownKeys: () => Reflect.ownKeys(deploymentConfig()),
  getOwnPropertyDescriptor: (_, key) => Object.getOwnPropertyDescriptor(deploymentConfig(), key),
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
 * Origin isolation (ADR-034 §6). Supplied by Ops at run time; the address is
 * never committed. Loud failure, never a skip.
 */
export function originConfig() {
  // Every port that is a trust boundary (docs/dev/production.md): HTTPS, HTTP,
  // both SMTP listeners, both management ports, PostgreSQL and the object
  // store. None may answer a direct connection from outside.
  const ports = (process.env.TESTINBOX_ORIGIN_PROBE_PORTS ?? "443,80,25,2525,9090,9091,5432,9000")
    .split(",")
    .map((p) => Number.parseInt(p.trim(), 10))
    .filter((p) => Number.isFinite(p));
  if (ports.length === 0) throw new Error("TESTINBOX_ORIGIN_PROBE_PORTS must list at least one port");
  const originAddress = required("TESTINBOX_ORIGIN_PROBE_ADDRESS");
  // An address, not a name: a hostname that resolves to nothing, or to the
  // wrong family, would "prove" isolation by never reaching the origin.
  const family = net.isIP(originAddress);
  if (family === 0) {
    throw new Error(`TESTINBOX_ORIGIN_PROBE_ADDRESS must be an IPv4 or IPv6 literal, got ${originAddress}`);
  }
  return Object.freeze({
    /** The approved path — the positive control. */
    baseUrl: required("TESTINBOX_BASE_URL"),
    originAddress,
    /** 4 or 6 — the positive control must reach the environment over the SAME family. */
    family,
    ports,
    timeoutMs: optionalInt("TESTINBOX_ORIGIN_PROBE_TIMEOUT_MS", 8_000),
  });
}

/**
 * Build identity and readiness through the PRIVATE management ports (ADR-034
 * §8). Run on the host; the ingress never routes these.
 */
export function identityConfig() {
  return Object.freeze({
    apiManagementUrl: required("TESTINBOX_API_MANAGEMENT_URL").replace(/\/$/, ""),
    ingestionManagementUrl: required("TESTINBOX_INGESTION_MANAGEMENT_URL").replace(/\/$/, ""),
    expectedGitSha: required("TESTINBOX_EXPECTED_GIT_SHA"),
    expectedEnvironment: required("TESTINBOX_EXPECTED_ENVIRONMENT"),
  });
}
