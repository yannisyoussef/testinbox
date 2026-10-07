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
  TestInboxCredentialAlreadyCreatedError,
  TestInboxIdempotencyConflictError,
  TestInboxIdempotencyInProgressError,
  TestInboxError,
  TestInboxForbiddenError,
  TestInboxInboxGoneError,
  TestInboxNotFoundError,
  TestInboxProtocolError,
  TestInboxStorageLimitExceededError,
  type ProblemDetails,
} from "../errors";

/**
 * Identifies this SDK in the server's access logs.
 *
 * Until now no shipped SDK sent one, so requests were attributed to whatever
 * the runtime happened to default to. Staging Ops found the consequence: the
 * deployment gate's ability to reach its own API rested on an unexamined
 * interaction between Node's default `User-Agent` and a Cloudflare heuristic.
 * Nothing was broken, and nothing would have predicted or quickly diagnosed it
 * if it had been.
 *
 * Declaring it turns an accidental dependency into a stated one, and makes SDK
 * traffic separable from ad-hoc calls when debugging a customer's report.
 *
 * Kept in step with package.json by `client.test.ts`; a browser would ignore
 * this header (it is forbidden there), which is harmless — this SDK targets
 * Node (ADR-023).
 */
export const SDK_VERSION = "0.1.0";
const USER_AGENT = `testinbox-sdk-ts/${SDK_VERSION}`;

// ---------------------------------------------------------------------------
// Wire DTOs (request/response shapes of the REST contract)
// ---------------------------------------------------------------------------

export interface CreateInboxRequestDto {
  addressMode?: string;
  ttlSeconds?: number;
  aliasHint?: string;
  localPart?: string;
}

/** The wire shape of ADR-035 §13's `StorageUsage`. Validated, never spread, into the public type. */
export interface StorageUsageDto {
  limitBytes?: unknown;
  storedBytes?: unknown;
  reservedBytes?: unknown;
  availableBytes?: unknown;
  overLimit?: unknown;
  [key: string]: unknown;
}

export interface InboxDto {
  id: string;
  address: string;
  addressMode?: string;
  state?: string;
  createdAt?: string;
  expiresAt?: string;
  /** ADR-035 §13b members. Absent from a server that predates TI-STORAGE-004 (ADR-028). */
  storage?: unknown;
  storageRefusalCount?: unknown;
  lastStorageRefusalAt?: unknown;
  lastStorageRefusalReason?: unknown;
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

export interface ApiKeyDto {
  id: string;
  publicId: string;
  name?: string | null;
  scopes?: string[];
  createdAt?: string;
  expiresAt?: string | null;
  revokedAt?: string | null;
  lastUsedAt?: string | null;
  createdByApiKeyId?: string | null;
  [key: string]: unknown;
}

/**
 * The one wire shape that carries a credential. The secret sits beside the
 * metadata rather than inside it, which is what lets the public
 * `ApiKeyMetadata` type have no field for it at all (ADR-032 §4).
 */
export interface CreatedApiKeyDto {
  apiKey: ApiKeyDto;
  key: string;
  [key: string]: unknown;
}

export interface ApiKeyPageDto {
  items?: ApiKeyDto[];
  nextCursor?: string | null;
  [key: string]: unknown;
}

export interface CreateApiKeyRequestDto {
  name?: string;
  scopes: string[];
  expiresInSeconds?: number;
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
  /** ADR-035 §13c observation boundary. Omitted entirely for the legacy contract; never sent as 0 by default. */
  afterStorageRefusalCount?: number;
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
  /** Informational echoes from the deciding snapshot (ADR-035 §13c). The SDK never adopts them as a boundary. */
  storageRefusalCount?: number;
  lastStorageRefusalAt?: string | null;
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
    apiKeyId: typeof body.apiKeyId === "string" ? body.apiKeyId : undefined,
    publicId: typeof body.publicId === "string" ? body.publicId : undefined,
    inboxId: typeof body.inboxId === "string" ? body.inboxId : undefined,
    refusalReason: typeof body.refusalReason === "string" ? body.refusalReason : undefined,
    afterStorageRefusalCount:
      typeof body.afterStorageRefusalCount === "number" ? body.afterStorageRefusalCount : undefined,
    storageRefusalCount: typeof body.storageRefusalCount === "number" ? body.storageRefusalCount : undefined,
    lastStorageRefusalAt: typeof body.lastStorageRefusalAt === "string" ? body.lastStorageRefusalAt : undefined,
  };
}

export const STORAGE_LIMIT_EXCEEDED_TYPE = "https://testinbox.email/problems/storage-limit-exceeded";

/** A non-negative safe integer, or undefined. */
function countOrUndefined(value: unknown): number | undefined {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : undefined;
}

/**
 * Parses an RFC 3339 timestamp the contract marks `date-time`, or fails as a
 * protocol error. `new Date("garbage")` is an `Invalid Date` that would
 * otherwise escape silently (docs/sdk/principles.md #6).
 */
export function parseInstant(value: unknown, field: string): Date {
  if (typeof value !== "string") throw new TestInboxProtocolError(`the server omitted required field '${field}'`);
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) throw new TestInboxProtocolError(`the server sent an unparseable '${field}'`);
  return date;
}

/**
 * A `storage-limit-exceeded` problem carries five semantic members
 * (ADR-035 §13c). The problem TYPE is what claims the meaning, so a body that
 * claims it and omits one of them is a contract violation: it becomes a
 * protocol error, never a fabricated zero, a guessed reason or a generic
 * conflict. An unknown `refusalReason` STRING is not malformed — it passes
 * through as the exact wire value.
 */
function storageLimitExceeded(message: string, problem: ProblemDetails): TestInboxError {
  const afterStorageRefusalCount = countOrUndefined(problem.afterStorageRefusalCount);
  const storageRefusalCount = countOrUndefined(problem.storageRefusalCount);
  const missing = [
    problem.inboxId === undefined && "inboxId",
    problem.refusalReason === undefined && "refusalReason",
    afterStorageRefusalCount === undefined && "afterStorageRefusalCount",
    storageRefusalCount === undefined && "storageRefusalCount",
    problem.lastStorageRefusalAt === undefined && "lastStorageRefusalAt",
  ].filter((name): name is string => typeof name === "string");
  if (missing.length > 0) {
    return new TestInboxProtocolError(
      `the server sent a storage-limit-exceeded problem without ${missing.join(", ")}`,
      problem,
    );
  }
  const lastStorageRefusalAt = new Date(problem.lastStorageRefusalAt!);
  if (Number.isNaN(lastStorageRefusalAt.getTime())) {
    return new TestInboxProtocolError("the server sent an unparseable 'lastStorageRefusalAt'", problem);
  }
  return new TestInboxStorageLimitExceededError(message, problem, {
    inboxId: problem.inboxId!,
    refusalReason: problem.refusalReason!,
    afterStorageRefusalCount: afterStorageRefusalCount!,
    storageRefusalCount: storageRefusalCount!,
    lastStorageRefusalAt,
    ...(problem.quota !== undefined && { quota: problem.quota }),
    ...(problem.limit !== undefined && { limit: problem.limit }),
    ...(problem.current !== undefined && { current: problem.current }),
  });
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
    case 409: {
      // Several distinct meanings share this status (ADR-021, ADR-027,
      // ADR-033), and their correct client actions differ — free capacity,
      // wait for a cooldown, retry with the same key, never reuse the key, or
      // revoke and re-mint. The problem type decides, never the status code.
      const type = problem.type ?? "";
      // Exact match on the full URI: this is the one 409 a wait may answer with,
      // and mapping it by suffix alone would let an unrelated future type share it.
      if (type === STORAGE_LIMIT_EXCEEDED_TYPE) return storageLimitExceeded(message, problem);
      if (type.endsWith("/quota-exceeded")) return new TestInboxQuotaExceededError(message, problem);
      if (type.endsWith("/idempotency-request-in-progress")) {
        return new TestInboxIdempotencyInProgressError(message, problem);
      }
      if (type.endsWith("/idempotency-secret-not-replayable")) {
        return new TestInboxCredentialAlreadyCreatedError(message, problem);
      }
      if (type.endsWith("/idempotency-key-reused") || type.endsWith("/idempotency-replay-unavailable")) {
        return new TestInboxIdempotencyConflictError(message, problem);
      }
      return new TestInboxConflictError(message, problem);
    }
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
  /** Sent verbatim as `Idempotency-Key`; never generated or rewritten here. */
  idempotencyKey?: string;
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

  async createInbox(request: CreateInboxRequestDto, idempotencyKey?: string): Promise<InboxDto> {
    const res = await this.#request("POST", "/v1/inboxes", {
      body: request,
      ...(idempotencyKey !== undefined && { idempotencyKey }),
    });
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

  /** ADR-035 §13a: the authenticated key's own workspace. */
  async getWorkspaceStorage(): Promise<StorageUsageDto> {
    const res = await this.#request("GET", "/v1/workspace/storage", {});
    return (await res.json()) as StorageUsageDto;
  }

  async getRawMime(messageId: string): Promise<Uint8Array> {
    const res = await this.#request("GET", `/v1/messages/${encodeURIComponent(messageId)}/raw`, {
      accept: "message/rfc822",
    });
    return new Uint8Array(await res.arrayBuffer());
  }

  async createApiKey(
    request: CreateApiKeyRequestDto,
    idempotencyKey?: string,
  ): Promise<CreatedApiKeyDto> {
    const res = await this.#request("POST", "/v1/api-keys", {
      body: request,
      ...(idempotencyKey !== undefined && { idempotencyKey }),
    });
    return (await res.json()) as CreatedApiKeyDto;
  }

  async listApiKeys(cursor?: string, limit?: number): Promise<ApiKeyPageDto> {
    const params = new URLSearchParams();
    if (cursor !== undefined) params.set("cursor", cursor);
    if (limit !== undefined) params.set("limit", String(limit));
    const query = params.size > 0 ? `?${params.toString()}` : "";
    const res = await this.#request("GET", `/v1/api-keys${query}`, {});
    return (await res.json()) as ApiKeyPageDto;
  }

  async getApiKey(id: string): Promise<ApiKeyDto> {
    const res = await this.#request("GET", `/v1/api-keys/${encodeURIComponent(id)}`, {});
    return (await res.json()) as ApiKeyDto;
  }

  async revokeApiKey(id: string): Promise<void> {
    await this.#request("DELETE", `/v1/api-keys/${encodeURIComponent(id)}`, {});
  }

  async #request(method: string, path: string, options: RequestOptions): Promise<Response> {
    const headers: Record<string, string> = {
      authorization: `Bearer ${this.#apiKey}`,
      accept: options.accept ?? "application/json, application/problem+json",
      "user-agent": USER_AGENT,
    };
    // Sent verbatim; never generated or rewritten here (ADR-033).
    if (options.idempotencyKey !== undefined) {
      headers["idempotency-key"] = options.idempotencyKey;
    }
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
