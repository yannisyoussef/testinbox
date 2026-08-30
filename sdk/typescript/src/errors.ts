/**
 * Typed error taxonomy for `@testinbox/client`.
 *
 * RFC 7807 problem responses map to this hierarchy (docs/sdk/principles.md #6);
 * a generic "HTTP error" is never leaked to callers. The API key is never
 * included in error messages or fields.
 */

/** RFC 7807 problem details attached to an error, when the server sent them. */
export interface ProblemDetails {
  /** RFC 7807 `type` URI (e.g. `https://testinbox.email/problems/inbox-gone`). */
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  correlationId?: string;
  /**
   * Seconds until a retry may succeed. Present on rate-limit and
   * concurrent-wait refusals, and on address-already-reserved conflicts still
   * in cooldown (ADR-021). Absent on quota-exceeded, where waiting does not
   * help.
   */
  retryAfterSeconds?: number;
  /** Rate category that refused the request (ADR-027). */
  category?: string;
  /** Quota dimension that is exhausted, on quota-exceeded (ADR-027). */
  quota?: string;
  /** Configured allowance for the exceeded limit. */
  limit?: number;
  /** Current consumption of the exceeded quota dimension. */
  current?: number;
  /** Tokens left, when the server reports it. */
  remaining?: number;
}

/** Base class for every error thrown by the TestInbox SDK. */
export class TestInboxError extends Error {
  /** HTTP status code, when the error originates from an HTTP response. */
  readonly status?: number;
  /** RFC 7807 `type` URI. (Named `problemType` to avoid clashing with `Error`.) */
  readonly problemType?: string;
  readonly title?: string;
  readonly detail?: string;
  readonly correlationId?: string;

  constructor(message: string, problem?: ProblemDetails, options?: ErrorOptions) {
    super(message, options);
    this.name = "TestInboxError";
    this.status = problem?.status;
    this.problemType = problem?.type;
    this.title = problem?.title;
    this.detail = problem?.detail;
    this.correlationId = problem?.correlationId;
  }
}

/** 401 — missing or invalid API key. */
export class TestInboxAuthError extends TestInboxError {
  constructor(message: string, problem?: ProblemDetails, options?: ErrorOptions) {
    super(message, problem, options);
    this.name = "TestInboxAuthError";
  }
}

/** 403 — the API key is valid but not allowed to perform this operation. */
export class TestInboxForbiddenError extends TestInboxError {
  constructor(message: string, problem?: ProblemDetails, options?: ErrorOptions) {
    super(message, problem, options);
    this.name = "TestInboxForbiddenError";
  }
}

/**
 * 404 — resource not found. Also returned for resources belonging to another
 * workspace (the API never leaks cross-tenant existence).
 */
export class TestInboxNotFoundError extends TestInboxError {
  constructor(message: string, problem?: ProblemDetails, options?: ErrorOptions) {
    super(message, problem, options);
    this.name = "TestInboxNotFoundError";
  }
}

/**
 * 409 — conflict; e.g. an EXACT local-part already reserved or in cooldown
 * (ADR-021). `retryAfterSeconds` is set when the address is in cooldown.
 */
export class TestInboxConflictError extends TestInboxError {
  readonly retryAfterSeconds?: number;

  constructor(message: string, problem?: ProblemDetails, options?: ErrorOptions) {
    super(message, problem, options);
    this.name = "TestInboxConflictError";
    this.retryAfterSeconds = problem?.retryAfterSeconds;
  }
}

/** 410 — the inbox is no longer ACTIVE (expired or deleted). */
export class TestInboxInboxGoneError extends TestInboxError {
  constructor(message: string, problem?: ProblemDetails, options?: ErrorOptions) {
    super(message, problem, options);
    this.name = "TestInboxInboxGoneError";
  }
}

/**
 * 429 — the workspace's request budget for this operation is exhausted
 * (ADR-027). Waiting helps: `retryAfterSeconds` is the server's own estimate.
 *
 * Deliberately never retried automatically by the SDK: `POST /v1/inboxes`
 * creates a resource and `Idempotency-Key` is not implemented, so an
 * automatic retry could create duplicate inboxes. The caller decides.
 */
export class TestInboxRateLimitError extends TestInboxError {
  readonly retryAfterSeconds?: number;
  /** Rate category that refused the request, when the server names one. */
  readonly category?: string;
  readonly limit?: number;
  readonly remaining?: number;

  constructor(message: string, problem?: ProblemDetails, options?: ErrorOptions) {
    super(message, problem, options);
    this.name = "TestInboxRateLimitError";
    this.retryAfterSeconds = problem?.retryAfterSeconds;
    this.category = problem?.category;
    this.limit = problem?.limit;
    this.remaining = problem?.remaining;
  }
}

/**
 * 409 with problem type `quota-exceeded` — a workspace resource allowance is
 * exhausted (ADR-027). Distinct from `TestInboxRateLimitError` because
 * waiting does **not** help: the caller must free capacity, for example by
 * deleting an inbox.
 */
export class TestInboxQuotaExceededError extends TestInboxError {
  /** Quota dimension that is exhausted, e.g. `ACTIVE_INBOXES`. */
  readonly quota?: string;
  readonly limit?: number;
  readonly current?: number;

  constructor(message: string, problem?: ProblemDetails, options?: ErrorOptions) {
    super(message, problem, options);
    this.name = "TestInboxQuotaExceededError";
    this.quota = problem?.quota;
    this.limit = problem?.limit;
    this.current = problem?.current;
  }
}

/** Any other non-2xx HTTP response. */
export class TestInboxApiError extends TestInboxError {
  constructor(message: string, problem?: ProblemDetails, options?: ErrorOptions) {
    super(message, problem, options);
    this.name = "TestInboxApiError";
  }
}

/**
 * Thrown by `inbox.waitForMessage(...)` when the caller's overall timeout
 * budget expires without a match (ADR-020). A server-side wait-window expiry
 * (`status: "TIMEOUT"`) is NOT an error — the SDK chains further long-poll
 * calls while budget remains; only budget exhaustion raises this.
 */
export class TestInboxTimeoutError extends TestInboxError {
  /** Total time spent waiting on the client side, in milliseconds. */
  readonly elapsedMs: number;
  /**
   * From the last server poll: messages that became visible during the wait
   * but did not satisfy the matcher — the primary "why did my test time out"
   * diagnostic.
   */
  readonly arrivedButUnmatchedCount: number;
  /** From the last server poll: messages that arrived but failed MIME parsing. */
  readonly parseFailedCount: number;

  constructor(
    message: string,
    diagnostics: {
      elapsedMs: number;
      arrivedButUnmatchedCount: number;
      parseFailedCount: number;
    },
    options?: ErrorOptions,
  ) {
    super(message, undefined, options);
    this.name = "TestInboxTimeoutError";
    this.elapsedMs = diagnostics.elapsedMs;
    this.arrivedButUnmatchedCount = diagnostics.arrivedButUnmatchedCount;
    this.parseFailedCount = diagnostics.parseFailedCount;
  }
}
