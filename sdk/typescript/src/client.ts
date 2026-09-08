/**
 * Hand-designed public API of `@testinbox/client` (ADR-014).
 *
 * Translates between the ergonomic public types (`Inbox`, `Message`) and the
 * internal transport's wire DTOs. Long-poll chaining for `waitForMessage`
 * lives here (docs/sdk/principles.md #4, ADR-020).
 */

import { TestInboxError, TestInboxTimeoutError } from "./errors";
import {
  Transport,
  type ApiKeyDto,
  type AttachmentMetaDto,
  type EmailHeaderDto,
  type EmailLinkDto,
  type InboxDto,
  type MessageDto,
  type MessageMatcherDto,
} from "./internal/transport";
import type {
  ApiKeyMetadata,
  ApiKeyPage,
  ApiScope,
  AttachmentMeta,
  CreateApiKeyOptions,
  CreatedApiKey,
  CreateInboxOptions,
  ListApiKeysOptions,
  EmailHeader,
  EmailLink,
  Inbox,
  Message,
  TestInboxClientOptions,
  WaitForMessageOptions,
} from "./types";

const DEFAULT_BASE_URL = "https://api.testinbox.email";
const DEFAULT_WAIT_TIMEOUT_MS = 30_000;
/** Server cap on a single wait call's window (docs/architecture/wait-semantics.md #7). */
const MAX_SERVER_WINDOW_SECONDS = 60;

function readEnv(name: string): string | undefined {
  // Documented opt-in fallback only — no other environment magic.
  const value = typeof process !== "undefined" ? process.env?.[name] : undefined;
  return value ? value : undefined;
}

/**
 * Entry point of the TestInbox SDK.
 *
 * ```ts
 * const client = new TestInboxClient({ apiKey: "…" });
 * const inbox = await client.createInbox();
 * const message = await inbox.waitForMessage({ subjectContains: "Verify" });
 * ```
 */
export class TestInboxClient {
  readonly #transport: Transport;
  #apiKeys?: ApiKeys;

  constructor(options: TestInboxClientOptions = {}) {
    const apiKey = options.apiKey ?? readEnv("TESTINBOX_API_KEY");
    if (!apiKey) {
      throw new TestInboxError(
        "A TestInbox API key is required: pass { apiKey } to TestInboxClient, " +
          "or set the TESTINBOX_API_KEY environment variable.",
      );
    }
    const baseUrl = options.baseUrl ?? readEnv("TESTINBOX_BASE_URL") ?? DEFAULT_BASE_URL;
    this.#transport = new Transport({ apiKey, baseUrl });
  }

  /** Create an ephemeral inbox (GENERATED or EXACT addressing, ADR-021). */
  async createInbox(options: CreateInboxOptions = {}): Promise<Inbox> {
    const dto = await this.#transport.createInbox(
      {
        ...(options.addressMode !== undefined && { addressMode: options.addressMode }),
        ...(options.ttlSeconds !== undefined && { ttlSeconds: options.ttlSeconds }),
        ...(options.aliasHint !== undefined && { aliasHint: options.aliasHint }),
        ...(options.localPart !== undefined && { localPart: options.localPart }),
      },
      options.idempotencyKey,
    );
    return new InboxImpl(this.#transport, dto);
  }

  /** Fetch an existing inbox by id. */
  async getInbox(id: string): Promise<Inbox> {
    return new InboxImpl(this.#transport, await this.#transport.getInbox(id));
  }

  /** Explicit early teardown of an inbox by id. */
  async deleteInbox(id: string): Promise<void> {
    await this.#transport.deleteInbox(id);
  }

  /**
   * Credential administration (ADR-032). Requires a key holding
   * `api-keys:manage`; an ordinary CI credential deliberately does not carry
   * it and receives a `TestInboxForbiddenError` here.
   *
   * There is no `rotate` call, and that is deliberate: rotation's hard part is
   * the interval before the client picks up the new credential, which no
   * server call can shorten. The correct sequence — create, deploy, verify,
   * revoke — is what this API expresses.
   */
  get apiKeys(): ApiKeys {
    // Memoised, so `client.apiKeys === client.apiKeys`. A fresh instance per
    // access silently defeats `vi.spyOn(client.apiKeys, "create")` — the spy
    // patches a throwaway, never fires, and the real fetch runs, which is a
    // false pass rather than an error.
    this.#apiKeys ??= new ApiKeysImpl(this.#transport);
    return this.#apiKeys;
  }
}

/** Key-management surface of {@link TestInboxClient}. */
export interface ApiKeys {
  /**
   * Mints a key. The returned `secret` is the only copy that will ever exist:
   * it is not stored and cannot be retrieved again (ADR-032 §4).
   */
  create(options: CreateApiKeyOptions): Promise<CreatedApiKey>;
  /** Newest first. Revoked keys are included; they are retained for audit. */
  list(options?: ListApiKeysOptions): Promise<ApiKeyPage>;
  /** Metadata only, and a revoked key is still returned — check `revokedAt`. */
  get(id: string): Promise<ApiKeyMetadata>;
  /** Idempotent: revoking an already-revoked key succeeds. */
  revoke(id: string): Promise<void>;
}

// ---------------------------------------------------------------------------
// Implementation classes (not exported; the public surface is the interfaces
// in ./types, so no internal transport type ever appears in the public API).
// ---------------------------------------------------------------------------

class ApiKeysImpl implements ApiKeys {
  readonly #transport: Transport;

  constructor(transport: Transport) {
    this.#transport = transport;
  }

  async create(options: CreateApiKeyOptions): Promise<CreatedApiKey> {
    const dto = await this.#transport.createApiKey(
      {
        scopes: options.scopes.map((scope) => String(scope)),
        ...(options.name !== undefined && { name: options.name }),
        ...(options.expiresInSeconds !== undefined && { expiresInSeconds: options.expiresInSeconds }),
      },
      options.idempotencyKey,
    );
    return { apiKey: toApiKeyMetadata(dto.apiKey), secret: dto.key };
  }

  async list(options: ListApiKeysOptions = {}): Promise<ApiKeyPage> {
    const page = await this.#transport.listApiKeys(options.cursor, options.limit);
    return {
      items: (page.items ?? []).map(toApiKeyMetadata),
      ...(page.nextCursor != null && { nextCursor: page.nextCursor }),
    };
  }

  async get(id: string): Promise<ApiKeyMetadata> {
    return toApiKeyMetadata(await this.#transport.getApiKey(id));
  }

  async revoke(id: string): Promise<void> {
    await this.#transport.revokeApiKey(id);
  }
}

/**
 * Maps the wire shape to the public metadata type, field by field.
 *
 * Explicit rather than a spread: a spread would copy whatever the server sent,
 * so a future response that carried credential material would silently land on
 * a type documented as never holding any.
 */
function toApiKeyMetadata(dto: ApiKeyDto): ApiKeyMetadata {
  return {
    id: dto.id,
    publicId: dto.publicId,
    ...(dto.name != null && { name: dto.name }),
    scopes: (dto.scopes ?? []) as ApiScope[],
    createdAt: new Date(dto.createdAt ?? NaN),
    ...(dto.expiresAt != null && { expiresAt: new Date(dto.expiresAt) }),
    ...(dto.revokedAt != null && { revokedAt: new Date(dto.revokedAt) }),
    ...(dto.lastUsedAt != null && { lastUsedAt: new Date(dto.lastUsedAt) }),
    ...(dto.createdByApiKeyId != null && { createdByApiKeyId: dto.createdByApiKeyId }),
  };
}

class InboxImpl implements Inbox {
  readonly id: string;
  readonly address: string;
  readonly addressMode: string;
  readonly state: string;
  readonly createdAt: Date;
  readonly expiresAt: Date;
  readonly #transport: Transport;

  constructor(transport: Transport, dto: InboxDto) {
    this.#transport = transport;
    this.id = dto.id;
    this.address = dto.address;
    this.addressMode = dto.addressMode ?? "GENERATED";
    this.state = dto.state ?? "ACTIVE";
    this.createdAt = new Date(dto.createdAt ?? NaN);
    this.expiresAt = new Date(dto.expiresAt ?? NaN);
  }

  async waitForMessage(options: WaitForMessageOptions = {}): Promise<Message> {
    const budgetMs = options.timeoutMs ?? DEFAULT_WAIT_TIMEOUT_MS;
    const startedAt = Date.now();
    const deadline = startedAt + budgetMs;
    const matcher = buildMatcher(options);

    let arrivedButUnmatchedCount = 0;
    let parseFailedCount = 0;
    let polled = false;

    // Long-poll chaining (ADR-012/020): each server call's window is
    // min(remaining budget, server cap), at least 1s. A server response of
    // {status: "TIMEOUT"} — or any unknown future status — is a successful
    // negative answer, never an error: chain again while budget remains.
    for (;;) {
      const remainingMs = deadline - Date.now();
      if (polled && remainingMs <= 0) {
        throw new TestInboxTimeoutError(
          `waitForMessage timed out after ${Date.now() - startedAt}ms ` +
            `(arrived but unmatched: ${arrivedButUnmatchedCount}, ` +
            `parse failed: ${parseFailedCount})`,
          {
            elapsedMs: Date.now() - startedAt,
            arrivedButUnmatchedCount,
            parseFailedCount,
          },
        );
      }

      const timeoutSeconds = Math.max(
        1,
        Math.min(MAX_SERVER_WINDOW_SECONDS, Math.ceil(Math.max(remainingMs, 0) / 1000)),
      );
      const result = await this.#transport.wait(this.id, {
        ...(matcher !== undefined && { matcher }),
        timeoutSeconds,
      });
      polled = true;

      if (result.status === "MATCHED" && result.message !== undefined) {
        return new MessageImpl(this.#transport, result.message);
      }
      // Keep the freshest diagnostics for the eventual timeout error.
      if (typeof result.arrivedButUnmatchedCount === "number") {
        arrivedButUnmatchedCount = result.arrivedButUnmatchedCount;
      }
      if (typeof result.parseFailedCount === "number") {
        parseFailedCount = result.parseFailedCount;
      }
    }
  }

  async listMessages(): Promise<Message[]> {
    const messages: Message[] = [];
    let cursor: string | undefined;
    do {
      const page = await this.#transport.listMessages(this.id, cursor);
      for (const dto of page.items ?? []) {
        messages.push(new MessageImpl(this.#transport, dto));
      }
      // The server may send an explicit `"nextCursor": null` — both null and
      // undefined mean "no further page".
      cursor = page.nextCursor ?? undefined;
    } while (cursor !== undefined);
    return messages;
  }

  async delete(): Promise<void> {
    await this.#transport.deleteInbox(this.id);
  }
}

function buildMatcher(options: WaitForMessageOptions): MessageMatcherDto | undefined {
  const matcher: MessageMatcherDto = {
    ...(options.from !== undefined && { from: options.from }),
    ...(options.subjectContains !== undefined && { subjectContains: options.subjectContains }),
    ...(options.subjectEquals !== undefined && { subjectEquals: options.subjectEquals }),
    ...(options.headers !== undefined && {
      headers: options.headers.map((h) => ({
        name: h.name,
        ...(h.value !== undefined && { value: h.value }),
      })),
    }),
  };
  return Object.keys(matcher).length > 0 ? matcher : undefined;
}

class MessageImpl implements Message {
  readonly id: string;
  readonly inboxId: string;
  readonly subject?: string;
  readonly from?: string;
  readonly textBody?: string;
  readonly htmlBody?: string;
  readonly links: readonly EmailLink[];
  readonly headers: readonly EmailHeader[];
  readonly receivedAt: Date;
  readonly attachments: readonly AttachmentMeta[];
  readonly parseStatus: string;
  readonly #transport: Transport;

  constructor(transport: Transport, dto: MessageDto) {
    this.#transport = transport;
    this.id = dto.id;
    this.inboxId = dto.inboxId ?? "";
    this.subject = dto.subject;
    this.from = dto.from;
    this.textBody = dto.textBody;
    this.htmlBody = dto.htmlBody;
    this.links = (dto.links ?? []).map(toLink);
    this.headers = (dto.headers ?? []).map(toHeader);
    this.receivedAt = new Date(dto.receivedAt ?? NaN);
    this.attachments = (dto.attachments ?? []).map(toAttachment);
    this.parseStatus = dto.parseStatus ?? "OK";
  }

  raw(): Promise<Uint8Array> {
    return this.#transport.getRawMime(this.id);
  }
}

function toLink(dto: EmailLinkDto): EmailLink {
  return { href: dto.href, ...(dto.text !== undefined && { text: dto.text }) };
}

function toHeader(dto: EmailHeaderDto): EmailHeader {
  return { name: dto.name, value: dto.value };
}

function toAttachment(dto: AttachmentMetaDto): AttachmentMeta {
  return {
    id: dto.id,
    ...(dto.fileName !== undefined && { fileName: dto.fileName }),
    ...(dto.contentType !== undefined && { contentType: dto.contentType }),
    sizeBytes: dto.sizeBytes ?? 0,
  };
}
