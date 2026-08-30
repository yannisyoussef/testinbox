/**
 * Tiny mock TestInbox backend for Playwright tests.
 *
 * Serves fixture JSON for one inbox and one message whose htmlBody contains
 * a <script> payload, a remote tracking pixel pointing at
 * http://127.0.0.1:59999/pixel.gif, and a verification link. The tests
 * assert that none of the hostile content executes or loads.
 *
 * GET /__requests returns every request seen (used by tests to verify the
 * proxy behavior, e.g. Authorization header present, key not in URLs).
 */
import http from "node:http";

const PORT = Number(process.env.MOCK_PORT ?? 4545);

export const INBOX_ID = "11111111-1111-1111-1111-111111111111";
export const MESSAGE_ID = "22222222-2222-2222-2222-222222222222";

const inbox = {
  id: INBOX_ID,
  address: "walking-skeleton.k7f3q@testinbox.email",
  addressMode: "GENERATED",
  state: "ACTIVE",
  createdAt: "2026-08-29T10:00:00Z",
  expiresAt: "2026-08-29T11:00:00Z",
};

const message = {
  id: MESSAGE_ID,
  inboxId: INBOX_ID,
  receivedAt: "2026-08-29T10:05:00Z",
  envelopeFrom: "sender@example.com",
  envelopeTo: inbox.address,
  parseStatus: "OK",
  from: "sender@example.com",
  fromHeader: "Evil Sender <sender@example.com>",
  toHeader: inbox.address,
  subject: "Confirm your account",
  textBody: "Hello!\nConfirm here: https://app.example.com/verify?token=abc123",
  htmlBody:
    "<html><body>" +
    "<script>document.title='PWNED'</script>" +
    "<img src=\"http://127.0.0.1:59999/pixel.gif\" alt=\"pixel\">" +
    "<p onmouseover=\"document.title='PWNED2'\">Hello <b>there</b></p>" +
    '<a href="https://app.example.com/verify?token=abc123">Confirm your account</a>' +
    '<a href="javascript:document.title=\'PWNED3\'">click</a>' +
    "</body></html>",
  headers: [
    { name: "From", value: "Evil Sender <sender@example.com>" },
    { name: "Subject", value: "Confirm your account" },
    { name: "X-Custom", value: "fixture" },
  ],
  links: [
    { href: "https://app.example.com/verify?token=abc123", text: "Confirm your account" },
  ],
  attachments: [
    {
      id: "33333333-3333-3333-3333-333333333333",
      fileName: "invoice.pdf",
      contentType: "application/pdf",
      sizeBytes: 12345,
    },
  ],
  contentFingerprint: "f".repeat(64),
  rawSizeBytes: 4096,
};

const seenRequests = [];

function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
}

function problem(res, status, title) {
  res.writeHead(status, { "content-type": "application/problem+json" });
  res.end(
    JSON.stringify({ type: "about:blank", title, status }),
  );
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);

  if (url.pathname === "/__requests") {
    return json(res, 200, seenRequests);
  }

  seenRequests.push({
    method: req.method,
    url: req.url,
    authorization: req.headers.authorization ?? null,
  });

  const auth = req.headers.authorization ?? "";
  if (!auth.startsWith("Bearer ")) {
    return problem(res, 401, "Missing bearer token");
  }

  if (req.method === "GET" && url.pathname === `/v1/inboxes/${INBOX_ID}`) {
    return json(res, 200, inbox);
  }
  if (req.method === "GET" && url.pathname === `/v1/inboxes/${INBOX_ID}/messages`) {
    return json(res, 200, { items: [message] });
  }
  if (req.method === "GET" && url.pathname === `/v1/messages/${MESSAGE_ID}`) {
    return json(res, 200, message);
  }

  return problem(res, 404, "Not found");
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`mock backend listening on http://127.0.0.1:${PORT}`);
});
