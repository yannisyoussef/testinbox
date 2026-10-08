import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import {
  TestInboxClient,
  TestInboxConflictError,
  TestInboxError,
  TestInboxProtocolError,
  TestInboxStorageLimitExceededError,
  TestInboxTimeoutError,
} from "./index";

/**
 * ADR-035 §13 SDK semantics (§17 test 45, TI-STORAGE-005): storage snapshots,
 * the per-Inbox monotonic observation cursor, refusal-aware chaining, the
 * typed storage-limit error, and compatibility with a server that predates
 * storage visibility. Every HTTP exchange is a stubbed `fetch`.
 */

const API_KEY = "tk_secret_unit_test_key";
const BASE_URL = "https://api.example.test";
const INBOX_ID = "11111111-1111-4111-8111-111111111111";
const STORAGE_LIMIT_TYPE = "https://testinbox.email/problems/storage-limit-exceeded";

let fetchMock: Mock;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function client(): TestInboxClient {
  return new TestInboxClient({ apiKey: API_KEY, baseUrl: BASE_URL });
}

function json(status: number, body: unknown, contentType = "application/json"): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": contentType } });
}

const usage = { limitBytes: 536870912, storedBytes: 402653184, reservedBytes: 15728640, availableBytes: 118489088, overLimit: false };

function inboxDto(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: INBOX_ID,
    address: "qa-4f9x@testinbox.email",
    addressMode: "GENERATED",
    state: "ACTIVE",
    createdAt: "2026-10-07T12:00:00Z",
    expiresAt: "2026-10-07T12:10:00Z",
    storage: usage,
    storageRefusalCount: 0,
    lastStorageRefusalAt: null,
    lastStorageRefusalReason: null,
    ...extra,
  };
}

function matched(count?: number): Response {
  return json(200, {
    status: "MATCHED",
    elapsedMs: 5,
    message: { id: "22222222-2222-4222-8222-222222222222", inboxId: INBOX_ID, receivedAt: "2026-10-07T12:01:00Z", parseStatus: "OK" },
    ...(count !== undefined && { storageRefusalCount: count, lastStorageRefusalAt: "2026-10-07T12:00:30Z" }),
  });
}

function timeout(count?: number): Response {
  return json(200, {
    status: "TIMEOUT",
    elapsedMs: 1000,
    arrivedButUnmatchedCount: 0,
    parseFailedCount: 0,
    ...(count !== undefined && { storageRefusalCount: count, lastStorageRefusalAt: null }),
  });
}

function refused(fields: Record<string, unknown> = {}): Response {
  return json(
    409,
    {
      type: STORAGE_LIMIT_TYPE,
      title: "Storage limit exceeded",
      status: 409,
      detail: "A storage ceiling (INBOX_LIMIT) refused a copy",
      correlationId: "corr-409",
      inboxId: INBOX_ID,
      refusalReason: "INBOX_LIMIT",
      afterStorageRefusalCount: 0,
      storageRefusalCount: 1,
      lastStorageRefusalAt: "2026-10-07T12:00:30Z",
      quota: "STORED_BYTES",
      limit: 536870912,
      current: 536870000,
      ...fields,
    },
    "application/problem+json",
  );
}

function waitBodies(): Array<Record<string, unknown>> {
  return fetchMock.mock.calls
    .filter((call) => String(call[0]).endsWith("/messages/wait"))
    .map((call) => JSON.parse((call[1] as RequestInit).body as string) as Record<string, unknown>);
}

/** Freezes the clock so a single TIMEOUT window exhausts the budget deterministically. */
function fixedClock(): void {
  let now = 1_000_000;
  vi.spyOn(Date, "now").mockImplementation(() => {
    now += 100_000; // every observation of the clock moves it well past any budget used here
    return now;
  });
}

describe("storage snapshots on the Inbox representation", () => {
  it("maps storage and the refusal members from create and from get, and freezes them", async () => {
    fetchMock.mockResolvedValueOnce(
      json(201, inboxDto({ storageRefusalCount: 2, lastStorageRefusalAt: "2026-10-07T11:59:00Z", lastStorageRefusalReason: "WORKSPACE_LIMIT" })),
    );
    const inbox = await client().createInbox();
    expect(inbox.storage).toEqual(usage);
    expect(Object.isFrozen(inbox.storage)).toBe(true);
    expect(inbox.storageRefusalCount).toBe(2);
    expect(inbox.lastStorageRefusalAt?.toISOString()).toBe("2026-10-07T11:59:00.000Z");
    expect(inbox.lastStorageRefusalReason).toBe("WORKSPACE_LIMIT");
    expect(inbox.storageRefusalCursor).toBe(2);
  });

  it("passes an unknown future refusal reason through as the exact wire value", async () => {
    fetchMock.mockResolvedValueOnce(
      json(200, inboxDto({ storageRefusalCount: 1, lastStorageRefusalAt: "2026-10-07T11:59:00Z", lastStorageRefusalReason: "SOME_FUTURE_REASON" })),
    );
    const inbox = await client().getInbox(INBOX_ID);
    expect(inbox.lastStorageRefusalReason).toBe("SOME_FUTURE_REASON");
  });

  it("treats an older server that omits every storage member as absent, not as zero", async () => {
    const legacy = inboxDto();
    for (const member of ["storage", "storageRefusalCount", "lastStorageRefusalAt", "lastStorageRefusalReason"]) delete legacy[member];
    fetchMock.mockResolvedValueOnce(json(200, legacy));
    const inbox = await client().getInbox(INBOX_ID);
    expect(inbox.address).toBe("qa-4f9x@testinbox.email");
    expect(inbox.storage).toBeUndefined();
    expect(inbox.storageRefusalCount).toBeUndefined();
    expect(inbox.lastStorageRefusalAt).toBeUndefined();
    expect(inbox.storageRefusalCursor).toBeUndefined();
  });

  it("refuses a present but malformed storage object or count as a protocol error, never a partial object", async () => {
    fetchMock.mockResolvedValueOnce(json(200, inboxDto({ storage: { limitBytes: 1, storedBytes: "lots" } })));
    await expect(client().getInbox(INBOX_ID)).rejects.toBeInstanceOf(TestInboxProtocolError);
    fetchMock.mockResolvedValueOnce(json(200, inboxDto({ storageRefusalCount: -1 })));
    await expect(client().getInbox(INBOX_ID)).rejects.toBeInstanceOf(TestInboxProtocolError);
    fetchMock.mockResolvedValueOnce(json(200, inboxDto({ storageRefusalCount: 1, lastStorageRefusalAt: "yesterday", lastStorageRefusalReason: "INBOX_LIMIT" })));
    await expect(client().getInbox(INBOX_ID)).rejects.toBeInstanceOf(TestInboxProtocolError);
  });
});

describe("getWorkspaceStorage", () => {
  it("GETs /v1/workspace/storage with the bearer token and maps exactly the five members", async () => {
    fetchMock.mockResolvedValueOnce(json(200, { ...usage, globalLimitBytes: 999 }));
    const result = await client().getWorkspaceStorage();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`${BASE_URL}/v1/workspace/storage`);
    expect(init.method).toBe("GET");
    expect((init.headers as Record<string, string>).authorization).toBe(`Bearer ${API_KEY}`);
    expect(result).toEqual(usage);
    expect("globalLimitBytes" in result).toBe(false); // mapped, never spread
    expect(Object.isFrozen(result)).toBe(true);
  });

  it("is a protocol error when a 200 omits or mistypes one of the five members, never a default of zero", async () => {
    const { availableBytes: _dropped, ...incomplete } = usage;
    fetchMock.mockResolvedValueOnce(json(200, incomplete));
    await expect(client().getWorkspaceStorage()).rejects.toBeInstanceOf(TestInboxProtocolError);
    fetchMock.mockResolvedValueOnce(json(200, { ...usage, overLimit: "no" }));
    await expect(client().getWorkspaceStorage()).rejects.toBeInstanceOf(TestInboxProtocolError);
  });
});

describe("the observation cursor and the wait request", () => {
  it("seeds the cursor from the representation and sends it by default, with no caller ceremony", async () => {
    fetchMock.mockResolvedValueOnce(json(200, inboxDto({ storageRefusalCount: 7, lastStorageRefusalAt: "2026-10-07T11:59:00Z", lastStorageRefusalReason: "INBOX_LIMIT" })));
    fetchMock.mockResolvedValueOnce(matched(7));
    const inbox = await client().getInbox(INBOX_ID);
    await inbox.waitForMessage({ subjectContains: "x" });
    expect(waitBodies()[0]!.afterStorageRefusalCount).toBe(7);
  });

  it("a freshly created inbox sends 0 by default", async () => {
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    fetchMock.mockResolvedValueOnce(matched(0));
    const inbox = await client().createInbox();
    await inbox.waitForMessage();
    expect(waitBodies()[0]!.afterStorageRefusalCount).toBe(0);
  });

  it("an older-server representation sends NO boundary by default — absence is not zero", async () => {
    const legacy = inboxDto();
    for (const member of ["storage", "storageRefusalCount", "lastStorageRefusalAt", "lastStorageRefusalReason"]) delete legacy[member];
    fetchMock.mockResolvedValueOnce(json(200, legacy));
    fetchMock.mockResolvedValueOnce(matched());
    const inbox = await client().getInbox(INBOX_ID);
    await inbox.waitForMessage();
    expect("afterStorageRefusalCount" in waitBodies()[0]!).toBe(false);
    expect(inbox.storageRefusalCursor).toBeUndefined();
  });

  it("an explicit boundary on an older-server representation is still sent, and seeds the cursor", async () => {
    const legacy = inboxDto();
    for (const member of ["storage", "storageRefusalCount", "lastStorageRefusalAt", "lastStorageRefusalReason"]) delete legacy[member];
    fetchMock.mockResolvedValueOnce(json(200, legacy));
    fetchMock.mockResolvedValueOnce(matched());
    const inbox = await client().getInbox(INBOX_ID);
    await inbox.waitForMessage({ afterStorageRefusalCount: 4 });
    expect(waitBodies()[0]!.afterStorageRefusalCount).toBe(4);
    expect(inbox.storageRefusalCursor).toBe(4);
  });

  it("explicit above the cursor advances it and is sent; explicit below is ignored in favour of the cursor", async () => {
    fetchMock.mockResolvedValueOnce(json(200, inboxDto({ storageRefusalCount: 2, lastStorageRefusalAt: "2026-10-07T11:59:00Z", lastStorageRefusalReason: "INBOX_LIMIT" })));
    fetchMock.mockResolvedValueOnce(matched(2)).mockResolvedValueOnce(matched(2));
    const inbox = await client().getInbox(INBOX_ID);
    await inbox.waitForMessage({ afterStorageRefusalCount: 5 });
    expect(waitBodies()[0]!.afterStorageRefusalCount).toBe(5);
    expect(inbox.storageRefusalCursor).toBe(5);
    await inbox.waitForMessage({ afterStorageRefusalCount: 2 });
    expect(waitBodies()[1]!.afterStorageRefusalCount).toBe(5);
    expect(inbox.storageRefusalCursor).toBe(5);
  });

  it("rejects an invalid explicit boundary locally, with zero HTTP calls", async () => {
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    const inbox = await client().createInbox();
    fetchMock.mockClear();
    for (const bad of [-1, 1.5, Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1]) {
      await expect(inbox.waitForMessage({ afterStorageRefusalCount: bad })).rejects.toBeInstanceOf(RangeError);
    }
    expect(fetchMock).not.toHaveBeenCalled();
    expect(inbox.storageRefusalCursor).toBe(0);
  });

  it("rejects the contradictory combination of an explicit boundary and observeStorageRefusals: false, with zero HTTP calls", async () => {
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    const inbox = await client().createInbox();
    fetchMock.mockClear();
    await expect(inbox.waitForMessage({ observeStorageRefusals: false, afterStorageRefusalCount: 3 })).rejects.toBeInstanceOf(TypeError);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(inbox.storageRefusalCursor).toBe(0);
  });

  it("observeStorageRefusals: false sends no boundary on any window and never surfaces a refusal", async () => {
    fixedClock();
    fetchMock.mockResolvedValueOnce(json(200, inboxDto({ storageRefusalCount: 3, lastStorageRefusalAt: "2026-10-07T11:59:00Z", lastStorageRefusalReason: "INBOX_LIMIT" })));
    fetchMock.mockResolvedValueOnce(timeout(4)).mockResolvedValueOnce(timeout(5));
    const inbox = await client().getInbox(INBOX_ID);
    await expect(inbox.waitForMessage({ observeStorageRefusals: false, timeoutMs: 150_000 })).rejects.toBeInstanceOf(TestInboxTimeoutError);
    const bodies = waitBodies();
    expect(bodies.length).toBeGreaterThanOrEqual(1);
    for (const body of bodies) expect("afterStorageRefusalCount" in body).toBe(false);
    expect(inbox.storageRefusalCursor).toBe(3);
  });

  it("a MATCHED echo above the cursor is NOT adopted, and the snapshot is untouched", async () => {
    fetchMock.mockResolvedValueOnce(json(200, inboxDto({ storageRefusalCount: 2, lastStorageRefusalAt: "2026-10-07T11:59:00Z", lastStorageRefusalReason: "INBOX_LIMIT" })));
    fetchMock.mockResolvedValueOnce(matched(3)).mockResolvedValueOnce(matched(3));
    const inbox = await client().getInbox(INBOX_ID);
    await inbox.waitForMessage();
    expect(inbox.storageRefusalCursor).toBe(2);
    expect(inbox.storageRefusalCount).toBe(2);
    await inbox.waitForMessage();
    expect(waitBodies()[1]!.afterStorageRefusalCount).toBe(2); // the next wait may therefore surface refusal 3
  });

  it("TIMEOUT echoes above the cursor are NOT adopted across chained windows, and the final timeout keeps the cursor", async () => {
    fixedClock();
    fetchMock.mockResolvedValueOnce(json(200, inboxDto({ storageRefusalCount: 4, lastStorageRefusalAt: "2026-10-07T11:59:00Z", lastStorageRefusalReason: "INBOX_LIMIT" })));
    fetchMock.mockResolvedValueOnce(timeout(6)).mockResolvedValueOnce(timeout(6)).mockResolvedValueOnce(timeout(6));
    const inbox = await client().getInbox(INBOX_ID);
    await expect(inbox.waitForMessage({ timeoutMs: 250_000 })).rejects.toBeInstanceOf(TestInboxTimeoutError);
    for (const body of waitBodies()) expect(body.afterStorageRefusalCount).toBe(4);
    expect(inbox.storageRefusalCursor).toBe(4);
  });
});

describe("the typed storage-limit error", () => {
  it("maps ONLY the storage-limit-exceeded type to the typed error, with every field, status and problemType", async () => {
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    fetchMock.mockResolvedValueOnce(
      refused({ refusalReason: "WORKSPACE_LIMIT", afterStorageRefusalCount: 2, storageRefusalCount: 3, limit: 2147483648, current: 2147480000 }),
    );
    const inbox = await client().createInbox();
    const error = await inbox.waitForMessage().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(TestInboxStorageLimitExceededError);
    expect(error).not.toBeInstanceOf(TestInboxConflictError);
    const typed = error as TestInboxStorageLimitExceededError;
    expect(typed.status).toBe(409);
    expect(typed.problemType).toBe(STORAGE_LIMIT_TYPE);
    expect(typed.correlationId).toBe("corr-409");
    expect(typed.inboxId).toBe(INBOX_ID);
    expect(typed.refusalReason).toBe("WORKSPACE_LIMIT");
    expect(typed.afterStorageRefusalCount).toBe(2);
    expect(typed.storageRefusalCount).toBe(3);
    expect(typed.lastStorageRefusalAt.toISOString()).toBe("2026-10-07T12:00:30.000Z");
    expect(typed.quota).toBe("STORED_BYTES");
    expect(typed.limit).toBe(2147483648);
    expect(typed.current).toBe(2147480000);
    expect(typed.message).not.toContain(API_KEY);
    expect(JSON.stringify(typed)).not.toContain(API_KEY);
    // Exactly one request: a storage refusal is never retried.
    expect(waitBodies().length).toBe(1);
  });

  it("advances the cursor to the 409's count BEFORE the caller's handler runs", async () => {
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    fetchMock.mockResolvedValueOnce(refused({ storageRefusalCount: 3 }));
    const inbox = await client().createInbox();
    let cursorInHandler: number | undefined;
    try {
      await inbox.waitForMessage();
    } catch (error) {
      expect(error).toBeInstanceOf(TestInboxStorageLimitExceededError);
      cursorInHandler = inbox.storageRefusalCursor;
    }
    expect(cursorInHandler).toBe(3);
    expect(inbox.storageRefusalCount).toBe(0); // the snapshot is a snapshot
    // The next default wait carries the advanced boundary.
    fetchMock.mockResolvedValueOnce(matched(3));
    await inbox.waitForMessage();
    expect(waitBodies()[1]!.afterStorageRefusalCount).toBe(3);
  });

  it("SERVICE_CAPACITY carries the reason and no quota, limit or current — absent, not zero", async () => {
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    const problem = refused({ refusalReason: "SERVICE_CAPACITY" });
    const body = (await problem.json()) as Record<string, unknown>;
    delete body.quota;
    delete body.limit;
    delete body.current;
    fetchMock.mockResolvedValueOnce(json(409, body, "application/problem+json"));
    const inbox = await client().createInbox();
    const error = (await inbox.waitForMessage().catch((e: unknown) => e)) as TestInboxStorageLimitExceededError;
    expect(error).toBeInstanceOf(TestInboxStorageLimitExceededError);
    expect(error.refusalReason).toBe("SERVICE_CAPACITY");
    expect("quota" in error).toBe(false);
    expect("limit" in error).toBe(false);
    expect("current" in error).toBe(false);
    // Structural, as an allowlist: exactly these own members, nothing else.
    expect(Object.getOwnPropertyNames(error).sort()).toEqual(
      ["afterStorageRefusalCount", "correlationId", "detail", "inboxId", "lastStorageRefusalAt", "message", "name", "problemType", "refusalReason", "stack", "status", "storageRefusalCount", "title"].sort(),
    );
  });

  it("an unknown future refusal reason is still the typed error and still advances the cursor", async () => {
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    fetchMock.mockResolvedValueOnce(refused({ refusalReason: "FUTURE_LIMIT_KIND", storageRefusalCount: 2 }));
    const inbox = await client().createInbox();
    const error = (await inbox.waitForMessage().catch((e: unknown) => e)) as TestInboxStorageLimitExceededError;
    expect(error).toBeInstanceOf(TestInboxStorageLimitExceededError);
    expect(error.refusalReason).toBe("FUTURE_LIMIT_KIND");
    expect(inbox.storageRefusalCursor).toBe(2);
  });

  it("a storage-limit problem missing a required member is a protocol error, and the cursor does not move", async () => {
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    const problem = refused();
    const body = (await problem.json()) as Record<string, unknown>;
    delete body.storageRefusalCount;
    fetchMock.mockResolvedValueOnce(json(409, body, "application/problem+json"));
    const inbox = await client().createInbox();
    const error = await inbox.waitForMessage().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(TestInboxProtocolError);
    expect(error).not.toBeInstanceOf(TestInboxConflictError);
    expect((error as TestInboxError).status).toBe(409);
    expect(inbox.storageRefusalCursor).toBe(0);
    // An unparseable timestamp is malformed too.
    fetchMock.mockResolvedValueOnce(refused({ lastStorageRefusalAt: "not-a-date" }));
    await expect(inbox.waitForMessage()).rejects.toBeInstanceOf(TestInboxProtocolError);
    expect(inbox.storageRefusalCursor).toBe(0);
  });

  it("an ordinary 409 is still the generic conflict, not the storage error", async () => {
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    fetchMock.mockResolvedValueOnce(
      json(409, { type: "https://testinbox.email/problems/address-already-reserved", title: "Reserved", status: 409 }, "application/problem+json"),
    );
    const inbox = await client().createInbox();
    await expect(inbox.waitForMessage()).rejects.toBeInstanceOf(TestInboxConflictError);
    expect(inbox.storageRefusalCursor).toBe(0);
  });
});

describe("concurrency and independence", () => {
  it("two concurrent waits whose 409s settle out of order leave the maximum count, not the last completion", async () => {
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    const inbox = await client().createInbox();
    let releaseFirst!: (r: Response) => void;
    let releaseSecond!: (r: Response) => void;
    fetchMock
      .mockImplementationOnce(() => new Promise<Response>((resolve) => (releaseFirst = resolve)))
      .mockImplementationOnce(() => new Promise<Response>((resolve) => (releaseSecond = resolve)));
    const a = inbox.waitForMessage().catch((e: unknown) => e);
    const b = inbox.waitForMessage().catch((e: unknown) => e);
    await Promise.resolve(); // both requests are in flight
    // Count 5 settles first, then the late, smaller 3.
    releaseFirst(refused({ storageRefusalCount: 5 }));
    await a;
    expect(inbox.storageRefusalCursor).toBe(5);
    releaseSecond(refused({ storageRefusalCount: 3 }));
    const late = await b;
    expect(late).toBeInstanceOf(TestInboxStorageLimitExceededError);
    expect(inbox.storageRefusalCursor).toBe(5);
  });

  it("…and the same in the other order", async () => {
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    const inbox = await client().createInbox();
    let releaseFirst!: (r: Response) => void;
    let releaseSecond!: (r: Response) => void;
    fetchMock
      .mockImplementationOnce(() => new Promise<Response>((resolve) => (releaseFirst = resolve)))
      .mockImplementationOnce(() => new Promise<Response>((resolve) => (releaseSecond = resolve)));
    const a = inbox.waitForMessage().catch((e: unknown) => e);
    const b = inbox.waitForMessage().catch((e: unknown) => e);
    await Promise.resolve();
    releaseFirst(refused({ storageRefusalCount: 3 }));
    await a;
    releaseSecond(refused({ storageRefusalCount: 5 }));
    await b;
    expect(inbox.storageRefusalCursor).toBe(5);
  });

  it("each window of one chained wait carries the current cursor, so a concurrent 409 moves the next window's boundary", async () => {
    let now = 1_000_000;
    vi.spyOn(Date, "now").mockImplementation(() => now);
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    const inbox = await client().createInbox();
    let releaseWindow!: (r: Response) => void;
    fetchMock
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            releaseWindow = (r) => {
              now += 60_000;
              resolve(r);
            };
          }),
      )
      .mockResolvedValueOnce(refused({ storageRefusalCount: 2 })) // the concurrent wait's 409
      .mockResolvedValueOnce(matched(2)); // the chained second window
    const chained = inbox.waitForMessage({ timeoutMs: 90_000 });
    await Promise.resolve();
    await inbox.waitForMessage().catch(() => undefined); // concurrent wait, refused at count 2
    expect(inbox.storageRefusalCursor).toBe(2);
    releaseWindow(timeout(0));
    await chained;
    const bodies = waitBodies();
    expect(bodies[0]!.afterStorageRefusalCount).toBe(0); // first window of the chained wait
    expect(bodies[2]!.afterStorageRefusalCount).toBe(2); // its second window carries the advanced cursor
  });

  it("two Inbox objects for the same server inbox keep independent cursors", async () => {
    fetchMock.mockResolvedValueOnce(json(200, inboxDto({ storageRefusalCount: 2, lastStorageRefusalAt: "2026-10-07T11:59:00Z", lastStorageRefusalReason: "INBOX_LIMIT" })));
    fetchMock.mockResolvedValueOnce(json(200, inboxDto({ storageRefusalCount: 2, lastStorageRefusalAt: "2026-10-07T11:59:00Z", lastStorageRefusalReason: "INBOX_LIMIT" })));
    const a = await client().getInbox(INBOX_ID);
    const b = await client().getInbox(INBOX_ID);
    fetchMock.mockResolvedValueOnce(refused({ afterStorageRefusalCount: 2, storageRefusalCount: 3 }));
    await a.waitForMessage().catch(() => undefined);
    expect(a.storageRefusalCursor).toBe(3);
    expect(b.storageRefusalCursor).toBe(2);
  });

  it("a fresh object initialises from the server's current count, and a persisted boundary is resumed explicitly", async () => {
    fetchMock.mockResolvedValueOnce(json(200, inboxDto({ storageRefusalCount: 1, lastStorageRefusalAt: "2026-10-07T11:59:00Z", lastStorageRefusalReason: "INBOX_LIMIT" })));
    fetchMock.mockResolvedValueOnce(matched(1));
    const fresh = await client().getInbox(INBOX_ID);
    expect(fresh.storageRefusalCursor).toBe(1);
    await fresh.waitForMessage({ afterStorageRefusalCount: 6 }); // a boundary persisted by a previous process
    expect(waitBodies()[0]!.afterStorageRefusalCount).toBe(6);
    expect(fresh.storageRefusalCursor).toBe(6);
  });
});

describe("source compatibility", () => {
  it("the documented calls compile and run with no storage option mentioned", async () => {
    fetchMock.mockResolvedValueOnce(json(201, inboxDto()));
    fetchMock.mockResolvedValueOnce(matched(0));
    const inbox = await client().createInbox();
    const message = await inbox.waitForMessage({ subjectContains: "Verify" });
    expect(message.id).toBeTruthy();
  });
});
