/**
 * INTERNAL transport layer — thin, typed wrappers over the v1 REST contract
 * (`backend/api/contract/openapi.yaml`).
 *
 * Per ADR-014 this module is an implementation detail: it is NOT exported from
 * the package entry point and nothing here is part of the public API. It may
 * be swapped (e.g. for a generated client) without a public API break.
 *
 * Wire DTOs deliberately carry `[key: string]: unknown` index signatures and
 * optional enum-ish `string` fields: unknown fields and unknown enum values
 * from a newer server must never cause a failure (docs/sdk/principles.md #5).
 */

import {
  TestInboxApiError,
  TestInboxQuotaExceededError,
  TestInboxRateLimitError,
  TestInboxAuthError,
  TestInboxConflictError,
  TestInboxError,
  TestInboxForbiddenError,
  TestInboxInboxGoneError,
  TestInboxNotFoundError,
  type ProblemDetails,
} from "../errors";

// ---------------------------------------------------------------------------
// Wire DTOs (request/response shapes of the REST contract)
// ---------------------------------------------------------------------------

export interface CreateInboxRequestDto {
  addressMode?: string;
  ttlSeconds?: number;
  aliasHint?: string;
  localPart?: string;
}

export interface InboxDto {
  id: string;
  address: string;
  addressMode?: string;
  state?: string;
  createdAt?: string;
  expiresAt?: string;
  [key: string]: unknown;
}

export interface EmailHeaderDto {
  name: string;
  value: string;
  [key: string]: unknown;
}

export interface EmailLinkDto {
  href: string;
  text?: string;
  [key: string]: unknown;
}

export interface AttachmentMetaDto {
  id: string;
  fileName?: string;
  contentType?: string;
  sizeBytes?: number;
  [key: string]: unknown;
}

export interface MessageDto {
  id: string;
  inboxId?: string;
  receivedAt?: string;
  parseStatus?: string;
  from?: string;
  subject?: string;
  textBody?: string;
  htmlBody?: string;
  links?: EmailLinkDto[];
  headers?: EmailHeaderDto[];
  attachments?: AttachmentMetaDto[];
  [key: string]: unknown;
}

export interface MessagePageDto {
  items?: MessageDto[];
  nextCursor?: string | null;
  [key: string]: unknown;
}

export interface HeaderMatcherDto {
  name: string;
  value?: string;
}

export interface MessageMatcherDto {
  from?: string;
  subjectContains?: string;
  subjectEquals?: string;
  headers?: HeaderMatcherDto[];
}

export interface WaitRequestDto {
  matcher?: MessageMatcherDto;
  timeoutSeconds: number;
}

/**
 * Wait outcome. `status` is an open string: the contract requires clients to
 * tolerate unknown future status values (treated by the caller as "keep
 * chaining", ADR-020).
 */
export interface WaitResultDto {
  status?: string;
  message?: MessageDto;
  elapsedMs?: number;
  arrivedButUnmatchedCount?: number;
  parseFailedCount?: number;
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// Error mapping (RFC 7807 -> typed hierarchy)
// ---------------------------------------------------------------------------

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asProblemDetails(status: number, body: unknown): ProblemDetails {
  if (!isRecord(body)) return { status };
  return {
    status,
    type: typeof body.type === "string" ? body.type : undefined,
    title: typeof body.title === "string" ? body.title : undefined,
    detail: typeof body.detail === "string" ? body.detail : undefined,
    instance: typeof body.instance === "string" ? body.instance : undefined,
    correlationId: typeof body.correlationId === "string" ? body.correlationId : undefined,
    retryAfterSeconds:
      typeof body.retryAfterSeconds === "number" ? body.retryAfterSeconds : undefined,
    category: typeof body.category === "string" ? body.category : undefined,
    quota: typeof body.quota === "string" ? body.quota : undefined,
    limit: typeof body.limit === "number" ? body.limit : undefined,
    current: typeof body.current === "number" ? body.current : undefined,
  };
}

function errorForStatus(status: number, problem: ProblemDetails): TestInboxError {
  const summary = problem.title
    ? `${problem.title}${problem.detail ? `: ${problem.detail}` : ""}`
    : `request failed`;
  const message = `TestInbox API error (HTTP ${status}): ${summary}`;
  switch (status) {
    case 401:
      return new TestInboxAuthError(message, problem);
    case 403:
      return new TestInboxForbiddenError(message, problem);
    case 404:
      return new TestInboxNotFoundError(message, problem);
    case 409:
      // Two distinct 409 meanings share this status (ADR-021 vs ADR-027), so
      // the problem type decides which error this is — never the status code.
      return problem.type?.endsWith("/quota-exceeded")
        ? new TestInboxQuotaExceededError(message, problem)
        : new TestInboxConflictError(message, problem);
    case 410:
      return new TestInboxInboxGoneError(message, problem);
    case 429:
      return new TestInboxRateLimitError(message, problem);
    default:
      return new TestInboxApiError(message, problem);
  }
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

export interface TransportOptions {
  apiKey: string;
  baseUrl: string;
}

interface RequestOptions {
  body?: unknown;
  accept?: string;
  signal?: AbortSignal;
}

/**
 * Minimal HTTP client over the global `fetch` (Node >= 20; zero runtime
 * dependencies). Holds the API key in a private field so it can never be
 * enumerated, logged, or JSON-serialized.
 */
export class Transport {
  readonly #apiKey: string;
  readonly #baseUrl: string;

  constructor(options: TransportOptions) {
    this.#apiKey = options.apiKey;
    this.#baseUrl = options.baseUrl.replace(/\/+$/, "");
  }

  async createInbox(request: CreateInboxRequestDto): Promise<InboxDto> {
    const res = await this.#request("POST", "/v1/inboxes", { body: request });
    return (await res.json()) as InboxDto;
  }

  async getInbox(inboxId: string): Promise<InboxDto> {
    const res = await this.#request("GET", `/v1/inboxes/${encodeURIComponent(inboxId)}`, {});
    return (await res.json()) as InboxDto;
  }

  async deleteInbox(inboxId: string): Promise<void> {
    await this.#request("DELETE", `/v1/inboxes/${encodeURIComponent(inboxId)}`, {});
  }

  async listMessages(inboxId: string, cursor?: string): Promise<MessagePageDto> {
    const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : "";
    const res = await this.#request(
      "GET",
      `/v1/inboxes/${encodeURIComponent(inboxId)}/messages${query}`,
      {},
    );
    return (await res.json()) as MessagePageDto;
  }

  async wait(inboxId: string, request: WaitRequestDto): Promise<WaitResultDto> {
    // Safety net: abort only well past the server's own window, so a hung
    // connection cannot stall the caller indefinitely. Normal wait-window
    // expiry is a 200 {status: TIMEOUT} response, never an abort.
    const signal = AbortSignal.timeout((request.timeoutSeconds + 15) * 1000);
    const res = await this.#request(
      "POST",
      `/v1/inboxes/${encodeURIComponent(inboxId)}/messages/wait`,
      { body: request, signal },
    );
    return (await res.json()) as WaitResultDto;
  }

  async getRawMime(messageId: string): Promise<Uint8Array> {
    const res = await this.#request("GET", `/v1/messages/${encodeURIComponent(messageId)}/raw`, {
      accept: "message/rfc822",
    });
    return new Uint8Array(await res.arrayBuffer());
  }

  async #request(method: string, path: string, options: RequestOptions): Promise<Response> {
    const headers: Record<string, string> = {
      authorization: `Bearer ${this.#apiKey}`,
      accept: options.accept ?? "application/json, application/problem+json",
    };
    let body: string | undefined;
    if (options.body !== undefined) {
      headers["content-type"] = "application/json";
      body = JSON.stringify(options.body);
    }

    let response: Response;
    try {
      response = await fetch(this.#baseUrl + path, {
        method,
        headers,
        body,
        signal: options.signal,
      });
    } catch (cause) {
      const reason = cause instanceof Error ? cause.message : String(cause);
      throw new TestInboxError(`TestInbox API request failed: ${reason}`, undefined, { cause });
    }

    if (!response.ok) {
      throw errorForStatus(response.status, asProblemDetails(response.status, await safeJson(response)));
    }
    return response;
  }
}

async function safeJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return undefined;
  }
}
