import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import {
  TestInboxApiError,
  TestInboxAuthError,
  TestInboxClient,
  TestInboxConflictError,
  TestInboxError,
  TestInboxForbiddenError,
  TestInboxInboxGoneError,
  TestInboxNotFoundError,
  TestInboxQuotaExceededError,
  TestInboxRateLimitError,
  TestInboxTimeoutError,
} from "./index";

const API_KEY = "tk_secret_unit_test_key";
const BASE_URL = "https://api.example.test";
const INBOX_ID = "11111111-1111-4111-8111-111111111111";
const MESSAGE_ID = "22222222-2222-4222-8222-222222222222";

let fetchMock: Mock;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

function client(): TestInboxClient {
  return new TestInboxClient({ apiKey: API_KEY, baseUrl: BASE_URL });
}

function jsonResponse(status: number, body: unknown, contentType = "application/json"): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": contentType },
  });
}

function problemResponse(status: number, extra: Record<string, unknown> = {}): Response {
  return jsonResponse(
    status,
    {
      type: "https://testinbox.email/problems/test-problem",
      title: "Test problem",
      status,
      detail: "Something specific went wrong",
      correlationId: "corr-123",
      ...extra,
    },
    "application/problem+json",
  );
}

function inboxDto(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: INBOX_ID,
    address: "qa-4f9x@testinbox.email",
    addressMode: "GENERATED",
    state: "ACTIVE",
    createdAt: "2026-08-29T12:00:00Z",
    expiresAt: "2026-08-29T12:10:00Z",
    ...extra,
  };
}

function messageDto(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: MESSAGE_ID,
    inboxId: INBOX_ID,
    receivedAt: "2026-08-29T12:01:02Z",
    envelopeTo: "qa-4f9x@testinbox.email",
    parseStatus: "OK",
    contentFingerprint: "deadbeef",
    rawSizeBytes: 1234,
    from: "noreply@example.com",
    fromHeader: "Example <noreply@example.com>",
    subject: "Welcome to Example",
    textBody: "Hello!",
    htmlBody: "<p>Hello!</p>",
    links: [{ href: "https://example.com/verify", text: "Verify" }],
    headers: [{ name: "X-Test", value: "1" }],
    attachments: [
      { id: "33333333-3333-4333-8333-333333333333", fileName: "a.pdf", contentType: "application/pdf", sizeBytes: 42 },
    ],
    ...extra,
  };
}

function requestBodyOf(call: unknown[]): Record<string, unknown> {
  const init = call[1] as RequestInit;
  return JSON.parse(init.body as string) as Record<string, unknown>;
}

function headersOf(call: unknown[]): Record<string, string> {
  const init = call[1] as RequestInit;
  return init.headers as Record<string, string>;
}

describe("TestInboxClient configuration", () => {
  it("throws a TestInboxError when no API key is available", () => {
    vi.stubEnv("TESTINBOX_API_KEY", "");
    expect(() => new TestInboxClient({ baseUrl: BASE_URL })).toThrow(TestInboxError);
  });

  it("falls back to TESTINBOX_API_KEY / TESTINBOX_BASE_URL env vars when options omit them", async () => {
    vi.stubEnv("TESTINBOX_API_KEY", "env-key");
    vi.stubEnv("TESTINBOX_BASE_URL", "https://env.example.test");
    fetchMock.mockResolvedValueOnce(jsonResponse(200, inboxDto()));

    await new TestInboxClient().getInbox(INBOX_ID);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`https://env.example.test/v1/inboxes/${INBOX_ID}`);
    expect((init.headers as Record<string, string>).authorization).toBe("Bearer env-key");
  });

  it("prefers explicit options over environment variables", async () => {
    vi.stubEnv("TESTINBOX_API_KEY", "env-key");
    vi.stubEnv("TESTINBOX_BASE_URL", "https://env.example.test");
    fetchMock.mockResolvedValueOnce(jsonResponse(200, inboxDto()));

    await client().getInbox(INBOX_ID);

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe(`${BASE_URL}/v1/inboxes/${INBOX_ID}`);
    expect(headersOf(fetchMock.mock.calls[0]!).authorization).toBe(`Bearer ${API_KEY}`);
  });

  it("never serializes the API key", () => {
    const c = client();
    expect(JSON.stringify(c)).not.toContain(API_KEY);
    expect(Object.values(c as unknown as Record<string, unknown>)).not.toContain(API_KEY);
  });
});

describe("createInbox", () => {
  it("POSTs /v1/inboxes with the Authorization header and returns a mapped Inbox", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, inboxDto()));

    const inbox = await client().createInbox({ ttlSeconds: 600, aliasHint: "signup" });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`${BASE_URL}/v1/inboxes`);
    expect(init.method).toBe("POST");
    expect(headersOf(fetchMock.mock.calls[0]!).authorization).toBe(`Bearer ${API_KEY}`);
    expect(headersOf(fetchMock.mock.calls[0]!)["content-type"]).toBe("application/json");
    expect(requestBodyOf(fetchMock.mock.calls[0]!)).toEqual({ ttlSeconds: 600, aliasHint: "signup" });

    expect(inbox.id).toBe(INBOX_ID);
    expect(inbox.address).toBe("qa-4f9x@testinbox.email");
    expect(inbox.state).toBe("ACTIVE");
    expect(inbox.addressMode).toBe("GENERATED");
    expect(inbox.expiresAt).toBeInstanceOf(Date);
    expect(inbox.expiresAt.toISOString()).toBe("2026-08-29T12:10:00.000Z");
  });

  it("tolerates unknown extra fields and unknown enum values (forward compatibility)", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(
        201,
        inboxDto({
          state: "FROZEN",
          addressMode: "QUANTUM",
          someFutureField: { nested: true },
          anotherOne: [1, 2, 3],
        }),
      ),
    );

    const inbox = await client().createInbox();
    expect(inbox.state).toBe("FROZEN");
    expect(inbox.addressMode).toBe("QUANTUM");
    expect(requestBodyOf(fetchMock.mock.calls[0]!)).toEqual({});
  });
});

describe("waitForMessage", () => {
  it("returns the message on an immediate MATCHED result", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, inboxDto()))
      .mockResolvedValueOnce(jsonResponse(200, { status: "MATCHED", elapsedMs: 120, message: messageDto() }));

    const inbox = await client().getInbox(INBOX_ID);
    const message = await inbox.waitForMessage({ subjectContains: "Welcome" });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const [url, init] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(url).toBe(`${BASE_URL}/v1/inboxes/${INBOX_ID}/messages/wait`);
    expect(init.method).toBe("POST");
    const body = requestBodyOf(fetchMock.mock.calls[1]!);
    expect(body.matcher).toEqual({ subjectContains: "Welcome" });
    expect(body.timeoutSeconds).toBe(30); // default 30 000 ms budget

    expect(message.id).toBe(MESSAGE_ID);
    expect(message.subject).toBe("Welcome to Example");
    expect(message.from).toBe("noreply@example.com");
    expect(message.textBody).toBe("Hello!");
    expect(message.htmlBody).toBe("<p>Hello!</p>");
    expect(message.links).toEqual([{ href: "https://example.com/verify", text: "Verify" }]);
    expect(message.headers).toEqual([{ name: "X-Test", value: "1" }]);
    expect(message.receivedAt.toISOString()).toBe("2026-08-29T12:01:02.000Z");
    expect(message.attachments).toEqual([
      { id: "33333333-3333-4333-8333-333333333333", fileName: "a.pdf", contentType: "application/pdf", sizeBytes: 42 },
    ]);
  });

  it("chains long-poll calls across a server TIMEOUT with correct timeoutSeconds values", async () => {
    let now = 1_000_000;
    vi.spyOn(Date, "now").mockImplementation(() => now);

    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, inboxDto()))
      // First poll: server window expires after 60s — a chainable outcome, not an error.
      .mockImplementationOnce(async () => {
        now += 60_000;
        return jsonResponse(200, {
          status: "TIMEOUT",
          elapsedMs: 60_000,
          arrivedButUnmatchedCount: 1,
          parseFailedCount: 0,
        });
      })
      .mockResolvedValueOnce(jsonResponse(200, { status: "MATCHED", elapsedMs: 250, message: messageDto() }));

    const inbox = await client().getInbox(INBOX_ID);
    const message = await inbox.waitForMessage({ timeoutMs: 90_000, from: "noreply@example.com" });

    // Exactly two wait POSTs were issued.
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const waitUrl = `${BASE_URL}/v1/inboxes/${INBOX_ID}/messages/wait`;
    expect(fetchMock.mock.calls[1]![0]).toBe(waitUrl);
    expect(fetchMock.mock.calls[2]![0]).toBe(waitUrl);

    // First window: min(90s remaining, 60s cap) = 60; second: min(30s remaining, 60) = 30.
    const firstBody = requestBodyOf(fetchMock.mock.calls[1]!);
    const secondBody = requestBodyOf(fetchMock.mock.calls[2]!);
    expect(firstBody.timeoutSeconds).toBe(60);
    expect(secondBody.timeoutSeconds).toBe(30);
    expect(firstBody.matcher).toEqual({ from: "noreply@example.com" });
    expect(secondBody.matcher).toEqual({ from: "noreply@example.com" });

    expect(message.id).toBe(MESSAGE_ID);
  });

  it("throws TestInboxTimeoutError with last-poll diagnostics when the overall budget expires", async () => {
    let now = 5_000_000;
    vi.spyOn(Date, "now").mockImplementation(() => now);

    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, inboxDto()))
      .mockImplementationOnce(async () => {
        now += 5_000;
        return jsonResponse(200, {
          status: "TIMEOUT",
          elapsedMs: 5_000,
          arrivedButUnmatchedCount: 2,
          parseFailedCount: 1,
        });
      });

    const inbox = await client().getInbox(INBOX_ID);
    const error = await inbox
      .waitForMessage({ timeoutMs: 5_000, subjectEquals: "exact subject" })
      .then(
        () => {
          throw new Error("expected waitForMessage to reject");
        },
        (e: unknown) => e,
      );

    expect(error).toBeInstanceOf(TestInboxTimeoutError);
    const timeout = error as TestInboxTimeoutError;
    expect(timeout.elapsedMs).toBe(5_000);
    expect(timeout.arrivedButUnmatchedCount).toBe(2);
    expect(timeout.parseFailedCount).toBe(1);
    // Only one wait poll fit in the budget.
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(requestBodyOf(fetchMock.mock.calls[1]!).timeoutSeconds).toBe(5);
  });

  it("treats an unknown wait status as 'keep chaining' and tolerates extra fields", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, inboxDto()))
      .mockResolvedValueOnce(
        jsonResponse(200, { status: "SNOOZED_FUTURE_STATUS", elapsedMs: 10, futureHint: "soon" }),
      )
      .mockResolvedValueOnce(
        jsonResponse(200, {
          status: "MATCHED",
          elapsedMs: 20,
          message: messageDto({ futureMessageField: true }),
          anotherFutureField: 7,
        }),
      );

    const inbox = await client().getInbox(INBOX_ID);
    const message = await inbox.waitForMessage();

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(message.id).toBe(MESSAGE_ID);
    // Empty matcher options => matcher omitted entirely (matches first parsed message).
    expect(requestBodyOf(fetchMock.mock.calls[1]!)).not.toHaveProperty("matcher");
  });

  it("sends the Authorization header on wait calls and never leaks the key in errors", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, inboxDto()))
      .mockResolvedValueOnce(problemResponse(410, { type: "https://testinbox.email/problems/inbox-gone" }));

    const inbox = await client().getInbox(INBOX_ID);
    const error = await inbox.waitForMessage().then(
      () => {
        throw new Error("expected rejection");
      },
      (e: unknown) => e,
    );

    expect(headersOf(fetchMock.mock.calls[1]!).authorization).toBe(`Bearer ${API_KEY}`);
    expect(error).toBeInstanceOf(TestInboxInboxGoneError);
    expect(JSON.stringify({ ...(error as Error) })).not.toContain(API_KEY);
    expect((error as Error).message).not.toContain(API_KEY);
  });
});

describe("error taxonomy (RFC 7807 mapping)", () => {
  it("maps 401 to TestInboxAuthError with problem details", async () => {
    fetchMock.mockResolvedValueOnce(problemResponse(401));
    const error = await client()
      .getInbox(INBOX_ID)
      .then(
        () => {
          throw new Error("expected rejection");
        },
        (e: unknown) => e,
      );

    expect(error).toBeInstanceOf(TestInboxAuthError);
    const authError = error as TestInboxAuthError;
    expect(authError.status).toBe(401);
    expect(authError.problemType).toBe("https://testinbox.email/problems/test-problem");
    expect(authError.title).toBe("Test problem");
    expect(authError.detail).toBe("Something specific went wrong");
    expect(authError.correlationId).toBe("corr-123");
  });

  it("maps 403 to TestInboxForbiddenError", async () => {
    fetchMock.mockResolvedValueOnce(problemResponse(403));
    await expect(client().deleteInbox(INBOX_ID)).rejects.toBeInstanceOf(TestInboxForbiddenError);
  });

  it("maps 404 to TestInboxNotFoundError", async () => {
    fetchMock.mockResolvedValueOnce(problemResponse(404));
    await expect(client().getInbox(INBOX_ID)).rejects.toBeInstanceOf(TestInboxNotFoundError);
  });

  it("maps 409 to TestInboxConflictError exposing retryAfterSeconds", async () => {
    fetchMock.mockResolvedValueOnce(
      problemResponse(409, {
        type: "https://testinbox.email/problems/address-already-reserved",
        retryAfterSeconds: 42,
      }),
    );
    const error = await client()
      .createInbox({ addressMode: "EXACT", localPart: "qa" })
      .then(
        () => {
          throw new Error("expected rejection");
        },
        (e: unknown) => e,
      );

    expect(error).toBeInstanceOf(TestInboxConflictError);
    expect((error as TestInboxConflictError).retryAfterSeconds).toBe(42);
    expect((error as TestInboxConflictError).problemType).toBe(
      "https://testinbox.email/problems/address-already-reserved",
    );
  });

  it("maps 410 to TestInboxInboxGoneError", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, inboxDto()))
      .mockResolvedValueOnce(problemResponse(410, { type: "https://testinbox.email/problems/inbox-gone" }));

    const inbox = await client().getInbox(INBOX_ID);
    await expect(inbox.waitForMessage({ timeoutMs: 1_000 })).rejects.toBeInstanceOf(TestInboxInboxGoneError);
  });

  it("maps other non-2xx statuses to TestInboxApiError, even without a parseable body", async () => {
    fetchMock.mockResolvedValueOnce(new Response("gateway exploded", { status: 502 }));
    const error = await client()
      .getInbox(INBOX_ID)
      .then(
        () => {
          throw new Error("expected rejection");
        },
        (e: unknown) => e,
      );

    expect(error).toBeInstanceOf(TestInboxApiError);
    expect((error as TestInboxApiError).status).toBe(502);
  });

  it("every error subclass is a TestInboxError", () => {
    for (const ErrClass of [
      TestInboxAuthError,
      TestInboxForbiddenError,
      TestInboxNotFoundError,
      TestInboxConflictError,
      TestInboxInboxGoneError,
      TestInboxApiError,
    ]) {
      expect(new ErrClass("x")).toBeInstanceOf(TestInboxError);
    }
    expect(
      new TestInboxTimeoutError("x", { elapsedMs: 1, arrivedButUnmatchedCount: 0, parseFailedCount: 0 }),
    ).toBeInstanceOf(TestInboxError);
  });
});

describe("rate limits and quotas (ADR-027)", () => {
  it("maps 429 to a typed rate-limit error carrying the server's retry hint", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(
        429,
        {
          type: "https://testinbox.email/problems/rate-limit-exceeded",
          title: "Rate limit exceeded",
          status: 429,
          detail: "Too many INBOX_CREATE requests",
          category: "INBOX_CREATE",
          retryAfterSeconds: 7,
          correlationId: "c-429",
        },
        { "content-type": "application/problem+json" },
      ),
    );

    const error = await client()
      .createInbox()
      .then(
        () => {
          throw new Error("expected a rate-limit error");
        },
        (e: unknown) => e,
      );
    expect(error).toBeInstanceOf(TestInboxRateLimitError);
    const rateLimited = error as TestInboxRateLimitError;
    expect(rateLimited.retryAfterSeconds).toBe(7);
    expect(rateLimited.category).toBe("INBOX_CREATE");
    expect(rateLimited.correlationId).toBe("c-429");
    // No hidden retry: creating a resource is not automatically repeated while
    // Idempotency-Key is unimplemented.
    expect(fetchMock.mock.calls).toHaveLength(1);
  });

  it("discriminates the two 409 meanings on problem type, not status", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(409, {
        type: "https://testinbox.email/problems/quota-exceeded",
        title: "Quota exceeded",
        status: 409,
        quota: "ACTIVE_INBOXES",
        limit: 2,
        current: 2,
      }),
    );
    const quota = await client()
      .createInbox()
      .then(
        () => {
          throw new Error("expected a quota error");
        },
        (e: unknown) => e,
      );
    expect(quota).toBeInstanceOf(TestInboxQuotaExceededError);
    expect((quota as TestInboxQuotaExceededError).quota).toBe("ACTIVE_INBOXES");
    expect((quota as TestInboxQuotaExceededError).current).toBe(2);

    // The same status with the ADR-021 type stays a plain conflict.
    fetchMock.mockResolvedValueOnce(
      jsonResponse(409, {
        type: "https://testinbox.email/problems/address-already-reserved",
        title: "Conflict",
        status: 409,
        retryAfterSeconds: 3600,
      }),
    );
    const conflict = await client()
      .createInbox({ addressMode: "EXACT", localPart: "qa" })
      .then(
        () => {
          throw new Error("expected a conflict error");
        },
        (e: unknown) => e,
      );
    expect(conflict).toBeInstanceOf(TestInboxConflictError);
    expect(conflict).not.toBeInstanceOf(TestInboxQuotaExceededError);
  });

  it("does not retry a rate-limited wait automatically", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, inboxDto()))
      .mockResolvedValueOnce(
        jsonResponse(429, {
          type: "https://testinbox.email/problems/concurrent-wait-limit-exceeded",
          status: 429,
          retryAfterSeconds: 1,
        }),
      );
    const inbox = await client().getInbox(INBOX_ID);
    await expect(inbox.waitForMessage({ timeoutMs: 5_000 })).rejects.toBeInstanceOf(
      TestInboxRateLimitError,
    );
    // One wait POST, not a chained retry loop.
    expect(fetchMock.mock.calls).toHaveLength(2);
  });
});

describe("messages", () => {
  it("listMessages follows pagination cursors and maps every message", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, inboxDto()))
      .mockResolvedValueOnce(jsonResponse(200, { items: [messageDto()], nextCursor: "c2" }))
      .mockResolvedValueOnce(
        jsonResponse(200, { items: [messageDto({ id: "44444444-4444-4444-8444-444444444444" })] }),
      );

    const inbox = await client().getInbox(INBOX_ID);
    const messages = await inbox.listMessages();

    expect(fetchMock.mock.calls[1]![0]).toBe(`${BASE_URL}/v1/inboxes/${INBOX_ID}/messages`);
    expect(fetchMock.mock.calls[2]![0]).toBe(`${BASE_URL}/v1/inboxes/${INBOX_ID}/messages?cursor=c2`);
    expect(messages.map((m) => m.id)).toEqual([MESSAGE_ID, "44444444-4444-4444-8444-444444444444"]);
  });

  it('listMessages stops on an explicit `"nextCursor": null` (server sends null, not absent)', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, inboxDto()))
      .mockResolvedValueOnce(jsonResponse(200, { items: [messageDto()], nextCursor: null }));

    const inbox = await client().getInbox(INBOX_ID);
    const messages = await inbox.listMessages();

    expect(messages).toHaveLength(1);
    expect(fetchMock.mock.calls).toHaveLength(2);
  });

  it("message.raw() fetches the raw MIME bytes from /v1/messages/{id}/raw", async () => {
    const rawBytes = new Uint8Array([82, 101, 99, 101, 105, 118, 101, 100, 58]);
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, inboxDto()))
      .mockResolvedValueOnce(jsonResponse(200, { status: "MATCHED", elapsedMs: 5, message: messageDto() }))
      .mockResolvedValueOnce(
        new Response(rawBytes, { status: 200, headers: { "content-type": "message/rfc822" } }),
      );

    const inbox = await client().getInbox(INBOX_ID);
    const message = await inbox.waitForMessage();
    const raw = await message.raw();

    expect(fetchMock.mock.calls[2]![0]).toBe(`${BASE_URL}/v1/messages/${MESSAGE_ID}/raw`);
    expect(raw).toBeInstanceOf(Uint8Array);
    expect(Array.from(raw)).toEqual(Array.from(rawBytes));
  });

  it("inbox.delete() issues DELETE /v1/inboxes/{id}", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, inboxDto()))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    const inbox = await client().getInbox(INBOX_ID);
    await inbox.delete();

    const [url, init] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(url).toBe(`${BASE_URL}/v1/inboxes/${INBOX_ID}`);
    expect(init.method).toBe("DELETE");
  });
});
