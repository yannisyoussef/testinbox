/**
 * Classification of a DIRECT-ORIGIN probe: a connection attempt to the
 * production host's own address, bypassing the approved ingress path.
 *
 * The invariant is the mirror image of `edge.mjs`. There, a request that
 * arrives through the edge with the wrong Host must be refused, and a
 * timeout proves nothing because a hung edge looks like a refusing one. Here
 * the origin must not be reachable AT ALL except through the approved path,
 * so the only acceptable outcome is that the connection never completes:
 * dropped (timeout), rejected (refused/reset) or unroutable. A firewall that
 * DROPs produces a timeout on purpose, and that is the proof.
 *
 * Anything that reaches the transport layer — a completed TCP connection, a
 * TLS handshake that fails on the certificate, an HTTP response of any status
 * — means the origin answered, and the invariant is violated whatever it
 * answered with. A "403 from the origin" is still the origin.
 *
 * Because "unreachable" is also what a runner with no network sees, this
 * verdict is only meaningful next to a positive control: the same runner must
 * reach the environment through its public hostname in the same run.
 */

const NOT_REACHED = new Set([
  "ETIMEDOUT",
  "ECONNREFUSED",
  "ECONNRESET",
  "EHOSTUNREACH",
  "ENETUNREACH",
  "EHOSTDOWN",
  "ENETDOWN",
]);

/** Failures that a runner with no route of that family produces on its own, whatever the origin does. */
const LOCAL_ROUTING = new Set(["EHOSTUNREACH", "ENETUNREACH", "EHOSTDOWN", "ENETDOWN"]);

/**
 * @param {{connected?: boolean, error?: string}} probe
 *   `connected: true` when the TCP connection completed, regardless of what
 *   happened after; `error` is the socket error code when it did not.
 * @param {{sameFamilyReachable?: boolean}} [control]
 *   Whether the positive control reached the environment over the same
 *   address family as the probe. A routing failure only proves isolation
 *   when the runner demonstrably CAN route that family — otherwise an IPv6
 *   probe from an IPv4-only runner would pass for free.
 * @returns {{isolated: boolean, reason: string}}
 */
export function classifyOriginProbe(probe, { sameFamilyReachable = true } = {}) {
  if (probe.connected === true) {
    return { isolated: false, reason: "the origin accepted a direct connection; it is reachable outside the approved ingress path" };
  }
  if (typeof probe.error !== "string" || probe.error === "") {
    // Fail closed: no connection and no reason is not evidence of isolation.
    return { isolated: false, reason: "the probe produced neither a connection nor a socket error; nothing was demonstrated" };
  }
  if (LOCAL_ROUTING.has(probe.error) && !sameFamilyReachable) {
    return { isolated: false, reason: `${probe.error} while the runner could not reach the environment over that address family; that is the runner's routing, not the origin's isolation` };
  }
  if (NOT_REACHED.has(probe.error)) {
    return { isolated: true, reason: `the origin did not answer a direct connection (${probe.error})` };
  }
  if (probe.error === "ENOTFOUND" || probe.error === "EAI_AGAIN") {
    // The probe address must be an address. A name that does not resolve
    // means the test was pointed at nothing, not that the origin is isolated.
    return { isolated: false, reason: `the probe target did not resolve (${probe.error}); the origin address must be given as an address` };
  }
  return { isolated: false, reason: `unexpected probe error ${probe.error}; treated as not demonstrated` };
}
