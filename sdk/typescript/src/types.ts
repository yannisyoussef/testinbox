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
 * Why a storage ceiling refused a copy of a message (ADR-035 §4): the inbox's
 * own limit, its workspace's limit, or service capacity. `SERVICE_CAPACITY` is
 * one bit — no global figure ever accompanies it. Unknown future values are
 * passed through as the exact wire string.
 */
export type StorageRefusalReason = "INBOX_LIMIT" | "WORKSPACE_LIMIT" | "SERVICE_CAPACITY" | (string & {});

/**
 * Physical storage accounting for ONE scope of your own tenancy (ADR-035
 * §13): your workspace, or one of your inboxes. Every figure describes the
 * caller's own scope only; nothing here describes another workspace or the
 * service as a whole.
 */
export interface StorageUsage {
  /** The effective limit of this scope — a policy value, not a constant. */
  readonly limitBytes: number;
  /** Committed bytes: raw MIME plus every extracted attachment. */
  readonly storedBytes: number;
  /** Bytes held by in-flight reservations, whatever their state or age. */
  readonly reservedBytes: number;
  /**
   * What this scope can still admit, never below zero. For an inbox it is the
   * minimum of the inbox's and its workspace's headroom.
   */
  readonly availableBytes: number;
  /** Whether `storedBytes + reservedBytes` exceeds `limitBytes`. Equality is not over. */
  readonly overLimit: boolean;
}

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
  /**
   * Observe storage refusals (ADR-035 §13c). Default `true`: every server
   * window carries the inbox's current `storageRefusalCursor`, and a copy
   * refused for this inbox after that boundary ends the wait with
   * `TestInboxStorageLimitExceededError` instead of letting it time out.
   *
   * `false` sends no boundary at all — the pre-ADR-035 behaviour, in which a
   * refusal is never surfaced and the wait ends with a match or a timeout.
   * Combining `false` with `afterStorageRefusalCount` is contradictory and is
   * rejected locally before any request is made.
   */
  observeStorageRefusals?: boolean;
  /**
   * An explicit observation boundary: the refusal count you have already
   * handled, for example one you persisted across a process restart. It
   * advances the inbox's cursor monotonically (`max(cursor, value)`) and that
   * resulting boundary is what the request carries, so a smaller value can
   * never make this `Inbox` object re-surface a refusal it already handled.
   * Must be a safe, non-negative integer. Raw REST remains the way to send an
   * arbitrary historical boundary.
   */
  afterStorageRefusalCount?: number;
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
   * This inbox's storage accounting as the server reported it when this
   * object was created or fetched (ADR-035 §13b). A snapshot: it never changes
   * on this object. Absent when the server predates storage visibility.
   */
  readonly storage?: StorageUsage;
  /**
   * How many copies a storage ceiling had refused for this inbox when this
   * representation was read. A snapshot, distinct from `storageRefusalCursor`:
   * it never changes on this object. Absent when the server predates storage
   * visibility.
   */
  readonly storageRefusalCount?: number;
  /** When the most recent refusal was recorded; absent while none has been, or on an older server. */
  readonly lastStorageRefusalAt?: Date;
  /** Why the most recent copy was refused; absent while none has been, or on an older server. */
  readonly lastStorageRefusalReason?: StorageRefusalReason;
  /**
   * This object's storage-refusal observation boundary (ADR-035 §13c): the
   * refusal count it has observed. Seeded from `storageRefusalCount`; it
   * advances in exactly two cases — when a wait surfaces a
   * `TestInboxStorageLimitExceededError` (to that error's count) and when you
   * pass an explicit `afterStorageRefusalCount` — always to the maximum of the
   * old and new value, never backwards. A `MATCHED` or `TIMEOUT` echo never
   * advances it. `undefined` when the server predates storage visibility, in
   * which case default waits send no boundary.
   */
  readonly storageRefusalCursor: number | undefined;

  /**
   * Deterministically wait for a matching message (long-poll; no client-side
   * busy polling). Non-consuming: the earliest matching message is returned
   * and remains listed. Throws `TestInboxTimeoutError` when the overall
   * `timeoutMs` budget expires, `TestInboxInboxGoneError` if the inbox is no
   * longer active, and — unless `observeStorageRefusals` is `false` —
   * `TestInboxStorageLimitExceededError` when a storage ceiling refused a copy
   * for this inbox after its observation boundary (ADR-035 §13c). That last
   * error is state-shaped: the SDK never retries it.
   */
  waitForMessage(options?: WaitForMessageOptions): Promise<Message>;

  /** List all messages in receipt order (pagination is followed internally). */
  listMessages(): Promise<Message[]>;

  /** Explicit early teardown; data removal completes asynchronously but boundedly. */
  delete(): Promise<void>;
}
