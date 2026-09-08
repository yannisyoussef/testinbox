/**
 * Public, hand-designed types of `@testinbox/client` (ADR-014).
 *
 * These are NOT the wire DTOs — the internal transport translates between the
 * REST contract and these ergonomic types. Unknown server-side enum values are
 * passed through as plain strings rather than rejected (forward compatibility,
 * docs/sdk/principles.md #5), hence the `(string & {})` widening on the
 * enum-like unions: known values keep autocompletion, unknown values never throw.
 */

/** Inbox addressing mode (ADR-021). Unknown future values are passed through. */
export type AddressMode = "GENERATED" | "EXACT" | (string & {});

/** Inbox lifecycle state. Unknown future values are passed through. */
export type InboxState = "ACTIVE" | "EXPIRING" | "EXPIRED" | "DELETED" | (string & {});

/** MIME parse outcome for a message. Unknown future values are passed through. */
export type ParseStatus = "OK" | "FAILED" | (string & {});

/**
 * Permission carried by an API key (ADR-032 §9). Unknown future values are
 * passed through rather than rejected, so a key granted a scope this SDK
 * version predates still round-trips.
 */
export type ApiScope = "inboxes:write" | "messages:read" | "api-keys:manage" | (string & {});

/**
 * Metadata about an API key.
 *
 * There is deliberately **no** field here that could carry the credential —
 * not an optional one, not a nullable one. The secret exists only on
 * {@link CreatedApiKey}, and only for the single call that mints it
 * (ADR-032 §4). Code holding an `ApiKeyMetadata` cannot log, serialise or
 * transmit a key, because it does not have one.
 */
export interface ApiKeyMetadata {
  id: string;
  /** Non-secret handle embedded in the credential; safe to display and log. */
  publicId: string;
  name?: string;
  scopes: ApiScope[];
  createdAt: Date;
  /** Absent when the key does not expire. */
  expiresAt?: Date;
  /**
   * Absent while the key is usable. Revoked keys are **retained, never
   * deleted**, so `get` keeps returning them — "revoke then expect a 404" is
   * the wrong check; read this field instead.
   */
  revokedAt?: Date;
  /**
   * Approximate — refreshed at most once per coalescing interval and may lag
   * by that much after a burst (ADR-032 §7). For "is this still in use?",
   * never as an authorization input.
   */
  lastUsedAt?: Date;
  createdByApiKeyId?: string;
}

/**
 * The result of minting a key.
 *
 * `secret` is the only copy that will ever exist: the server does not store
 * it, so it cannot be shown again or recovered. Hand it to whatever needs it
 * and drop it — a lost key is replaced by minting a new one and revoking the
 * old.
 */
export interface CreatedApiKey {
  apiKey: ApiKeyMetadata;
  secret: string;
}

/** One page of key metadata. */
export interface ApiKeyPage {
  items: ApiKeyMetadata[];
  /** Opaque; absent when no further page exists. */
  nextCursor?: string;
}

/** Options for `TestInboxClient#apiKeys.list`. */
export interface ListApiKeysOptions {
  /** Opaque cursor from a previous page's `nextCursor`. */
  cursor?: string;
  /** Page size. Clamped to 1..200 by the server rather than refused. */
  limit?: number;
}

/** Options for `TestInboxClient#apiKeys.create`. */
export interface CreateApiKeyOptions extends IdempotencyOptions {
  /** Least privilege — request only what the holder needs. Immutable once created. */
  scopes: ApiScope[];
  /** Operator-chosen label, e.g. the CI system that will hold it. */
  name?: string;
  /** Optional lifetime; omit for a key that does not expire. Minimum 60 seconds. */
  expiresInSeconds?: number;
}

/** A single email header (headers can repeat; order preserved). */
export interface EmailHeader {
  name: string;
  value: string;
}

/** A link extracted from the message body. Data only — TestInbox never fetches links. */
export interface EmailLink {
  href: string;
  text?: string;
}

/** Attachment metadata. `fileName` is sender-supplied, unsanitized display data. */
export interface AttachmentMeta {
  id: string;
  fileName?: string;
  contentType?: string;
  sizeBytes: number;
}

/** Header matcher: omit `value` to match on header presence alone. */
export interface HeaderMatcher {
  name: string;
  value?: string;
}

/**
 * An idempotency key the caller owns (ADR-033).
 *
 * The SDK sends it verbatim and never generates or rewrites one, which is the
 * only behaviour that makes the feature work: a key regenerated per attempt
 * defeats it entirely, and a key generated inside a process that then dies
 * provides no protection at all — the retry comes from somewhere that never
 * saw it. Derive it from something your own retry boundary can reproduce: a CI
 * job id, a test name, a row in your own queue.
 */
export interface IdempotencyOptions {
  idempotencyKey?: string;
}

/** Options for `TestInboxClient#createInbox`. */
export interface CreateInboxOptions extends IdempotencyOptions {
  /** Inbox time-to-live; defaults to the deployment default, capped at the deployment maximum. */
  ttlSeconds?: number;
  /** GENERATED mode only — human-readable prefix; the address always carries a random token. */
  aliasHint?: string;
  /** Defaults to GENERATED. */
  addressMode?: AddressMode;
  /** EXACT mode only — the exact local-part to reserve (conflict raises `TestInboxConflictError`). */
  localPart?: string;
}

/**
 * Options for `Inbox#waitForMessage`. A message matches iff ALL specified
 * matcher fields match; with no matcher fields, the first parsed message
 * matches. Parse-failed messages never match.
 */
export interface WaitForMessageOptions {
  /**
   * Overall caller budget in milliseconds (default 30 000). May exceed the
   * server's single-call wait cap — the SDK chains long-poll calls internally
   * (ADR-012/020). On expiry a `TestInboxTimeoutError` is thrown.
   */
  timeoutMs?: number;
  /** Case-insensitive exact match on the parsed From address. */
  from?: string;
  subjectContains?: string;
  subjectEquals?: string;
  headers?: HeaderMatcher[];
}

/** Constructor options for `TestInboxClient`. */
export interface TestInboxClientOptions {
  /**
   * Workspace/project-scoped API key. Falls back to the `TESTINBOX_API_KEY`
   * environment variable (documented opt-in convention) when omitted.
   * The key is never logged or serialized by the SDK.
   */
  apiKey?: string;
  /**
   * API origin, default `https://api.testinbox.email`. Falls back to the
   * `TESTINBOX_BASE_URL` environment variable when omitted (for self-hosted
   * or staging deployments).
   */
  baseUrl?: string;
}

/**
 * A received email message. Parsed fields (`subject`, `from`, bodies, …) are
 * absent when `parseStatus` is `"FAILED"`; the raw MIME is always available
 * via `raw()` (ADR-005).
 */
export interface Message {
  readonly id: string;
  readonly inboxId: string;
  readonly subject?: string;
  /** Parsed From address (address part only). */
  readonly from?: string;
  readonly textBody?: string;
  /**
   * Untrusted, sender-controlled HTML. Never render it on a trusted origin
   * (ADR-011) and never auto-fetch URLs found inside it.
   */
  readonly htmlBody?: string;
  /** Extracted links (empty array when none). */
  readonly links: readonly EmailLink[];
  /** All email headers in order (empty array when parsing failed). */
  readonly headers: readonly EmailHeader[];
  readonly receivedAt: Date;
  /** Attachment metadata only — bytes are fetched separately. */
  readonly attachments: readonly AttachmentMeta[];
  readonly parseStatus: ParseStatus;
  /** Fetch the raw MIME bytes exactly as accepted on the wire (ADR-005). */
  raw(): Promise<Uint8Array>;
}

/** An ephemeral inbox. Obtained from `TestInboxClient` — not constructed directly. */
export interface Inbox {
  readonly id: string;
  /** The full email address to send test mail to. */
  readonly address: string;
  readonly addressMode: AddressMode;
  readonly state: InboxState;
  readonly createdAt: Date;
  readonly expiresAt: Date;

  /**
   * Deterministically wait for a matching message (long-poll; no client-side
   * busy polling). Non-consuming: the earliest matching message is returned
   * and remains listed. Throws `TestInboxTimeoutError` when the overall
   * `timeoutMs` budget expires, and `TestInboxInboxGoneError` if the inbox is
   * no longer active.
   */
  waitForMessage(options?: WaitForMessageOptions): Promise<Message>;

  /** List all messages in receipt order (pagination is followed internally). */
  listMessages(): Promise<Message[]>;

  /** Explicit early teardown; data removal completes asynchronously but boundedly. */
  delete(): Promise<void>;
}
