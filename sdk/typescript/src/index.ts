/**
 * `@testinbox/client` — hand-designed TypeScript SDK for TestInbox (ADR-014).
 *
 * The internal transport (`src/internal/`) is deliberately NOT exported:
 * nothing under `internal/` is part of the public API surface.
 */

export { TestInboxClient, type ApiKeys } from "./client";
export {
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
  TestInboxTimeoutError,
  type ProblemDetails,
} from "./errors";
export type {
  AddressMode,
  ApiKeyMetadata,
  ApiKeyPage,
  ApiScope,
  CreateApiKeyOptions,
  CreatedApiKey,
  IdempotencyOptions,
  ListApiKeysOptions,
  AttachmentMeta,
  CreateInboxOptions,
  EmailHeader,
  EmailLink,
  HeaderMatcher,
  Inbox,
  InboxState,
  Message,
  ParseStatus,
  TestInboxClientOptions,
  WaitForMessageOptions,
} from "./types";
