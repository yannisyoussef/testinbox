/**
 * Classification of an "unknown Host" probe against whatever edge is actually
 * in front of TestInbox.
 *
 * The invariant is platform-independent: **a request carrying a Host TestInbox
 * does not serve must not be answered by TestInbox.** How an edge enforces
 * that is not: the repository's nginx reference uses `return 444` (close the
 * connection, no HTTP response at all), while the deployed Cloudflare/Traefik
 * path answers with a 4xx. Both satisfy the invariant. An earlier version of
 * this test asserted the connection reset specifically, so it encoded nginx's
 * mechanism and failed against a correct deployment.
 *
 * Widening it to "any 4xx passes" would have been the easy fix and a bad one —
 * a 404 rendered by the TestInbox API is also a 4xx, and it means the request
 * reached us. So a 4xx passes only when the response carries no evidence that
 * TestInbox produced it.
 */

/**
 * Fingerprints of a TestInbox-produced response. Any one of these means the
 * request reached the application, whatever status came back.
 *
 * The headers are set on EVERY API response by `CorrelationFilter`, before any
 * routing or authentication decision, so they survive error paths — which is
 * exactly what makes them a reliable tell.
 */
export const TESTINBOX_RESPONSE_MARKERS = Object.freeze({
  headers: ["x-correlation-id", "x-api-stability"],
  bodyPatterns: [
    /testinbox/i, // the UI shell ("TestInbox Inspector") and problem type URIs
    /"correlationId"/, // RFC 7807 bodies from the API
    /https:\/\/testinbox\.email\/problems\//i,
  ],
});

/**
 * @param {{error?: string, status?: number, headers?: Record<string,string>, body?: string}} probe
 * @returns {{served: boolean, reason: string}} `served` is the failure condition:
 *   true means TestInbox answered a Host it does not serve.
 */
export function classifyUnknownHostProbe(probe) {
  if (probe.error !== undefined) {
    // No HTTP response at all — nginx `return 444`, or any edge that drops the
    // connection. The strongest possible form of "not served".
    return { served: false, reason: `no HTTP response (${probe.error})` };
  }

  const status = probe.status;
  const headers = normalizeHeaders(probe.headers);
  const body = probe.body ?? "";
  const evidence = testInboxEvidence(headers, body);

  if (status >= 200 && status < 400) {
    return {
      served: true,
      reason: `the edge answered ${status} for a Host TestInbox does not serve; a success or redirect means the request was routed somewhere it should not have been`,
    };
  }

  if (status >= 500) {
    // Deliberately a failure rather than a shrug. A 5xx means the edge tried to
    // reach an upstream, or is itself broken; either way the invariant is not
    // demonstrated. If a specific edge legitimately answers 5xx here, that
    // belongs in this function with a stated reason — never in an environment
    // opt-out.
    return {
      served: true,
      reason: `the edge answered ${status}; an unknown Host must be refused, not attempted (no justified 5xx behaviour is recorded for this edge)`,
    };
  }

  if (evidence.length > 0) {
    return {
      served: true,
      reason: `the edge answered ${status}, but the response was produced by TestInbox (${evidence.join(", ")}) — a 4xx from the application still means the request reached it`,
    };
  }

  return { served: false, reason: `refused by the edge with ${status}, carrying no TestInbox content` };
}

function normalizeHeaders(headers) {
  const out = {};
  for (const [key, value] of Object.entries(headers ?? {})) {
    out[key.toLowerCase()] = String(value);
  }
  return out;
}

function testInboxEvidence(headers, body) {
  const found = [];
  for (const header of TESTINBOX_RESPONSE_MARKERS.headers) {
    if (headers[header] !== undefined) found.push(`${header} header`);
  }
  for (const pattern of TESTINBOX_RESPONSE_MARKERS.bodyPatterns) {
    if (pattern.test(body)) found.push(`body matches ${pattern}`);
  }
  return found;
}
