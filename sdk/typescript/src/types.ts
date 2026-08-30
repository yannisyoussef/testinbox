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

/** Options for `TestInboxClient#createInbox`. */
export interface CreateInboxOptions {
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
