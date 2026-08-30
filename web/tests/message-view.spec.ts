import http from "node:http";
import { test, expect, Page } from "@playwright/test";

const INBOX_ID = "11111111-1111-1111-1111-111111111111";
const MESSAGE_ID = "22222222-2222-2222-2222-222222222222";
const API_KEY = "tik_test_walking_skeleton_key";
const PIXEL_PORT = 59999;

// Canary server on the tracking-pixel port: any hit means the sandboxed
// preview leaked a remote resource load.
let pixelHits = 0;
let pixelServer: http.Server;

test.beforeAll(async () => {
  pixelHits = 0;
  pixelServer = http.createServer((_req, res) => {
    pixelHits += 1;
    res.writeHead(200, { "content-type": "image/gif" });
    res.end();
  });
  await new Promise<void>((resolve) =>
    pixelServer.listen(PIXEL_PORT, "127.0.0.1", resolve),
  );
});

test.afterAll(async () => {
  await new Promise<void>((resolve, reject) =>
    pixelServer.close((err) => (err ? reject(err) : resolve())),
  );
});

async function seedSession(page: Page) {
  await page.addInitScript(
    ([key, inboxId]) => {
      sessionStorage.setItem("testinbox.apiKey", key);
      sessionStorage.setItem("testinbox.inboxId", inboxId);
    },
    [API_KEY, INBOX_ID],
  );
}

test("home form stores credentials and opens the inbox view", async ({ page }) => {
  await page.goto("/");
  await page.fill("#apiKey", API_KEY);
  await page.fill("#inboxId", INBOX_ID);
  await page.click("button[type=submit]");

  await expect(page).toHaveURL(new RegExp(`/inbox/${INBOX_ID}`));
  await expect(
    page.getByText("walking-skeleton.k7f3q@testinbox.email"),
  ).toBeVisible();
  await expect(page.getByText("ACTIVE")).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Confirm your account" }),
  ).toBeVisible();
});

test("message view renders subject, text body, headers, links and attachments", async ({
  page,
}) => {
  await seedSession(page);
  await page.goto(`/message/${MESSAGE_ID}`);

  await expect(page.getByTestId("message-subject")).toHaveText(
    "Confirm your account",
  );
  await expect(page.getByTestId("message-from")).toHaveText(
    "sender@example.com",
  );
  await expect(page.getByTestId("text-body")).toContainText(
    "https://app.example.com/verify?token=abc123",
  );
  await expect(page.getByTestId("headers-table")).toContainText("X-Custom");
  await expect(page.getByTestId("attachments-list")).toContainText(
    "invoice.pdf",
  );

  // Links section shows the extracted verification link as TEXT ONLY.
  const linksList = page.getByTestId("links-list");
  await expect(linksList).toContainText(
    "https://app.example.com/verify?token=abc123",
  );
  await expect(linksList.locator("a")).toHaveCount(0);
});

test("hostile HTML preview: no script execution, empty sandbox, no remote loads, no key in URLs", async ({
  page,
}) => {
  const requestedUrls: string[] = [];
  const pixelRequests: import("@playwright/test").Request[] = [];
  page.on("request", (req) => {
    requestedUrls.push(req.url());
    if (req.url().includes("127.0.0.1:59999")) pixelRequests.push(req);
  });
  page.on("dialog", () => {
    throw new Error("Unexpected dialog opened by untrusted HTML");
  });

  await seedSession(page);
  await page.goto(`/message/${MESSAGE_ID}`);

  const iframe = page.getByTestId("html-preview");
  await expect(iframe).toBeVisible();

  // Sandbox must be present and empty: no allow-scripts, no allow-same-origin.
  await expect(iframe).toHaveAttribute("sandbox", "");

  // The benign sanitized content is rendered inside the frame.
  await expect(iframe.contentFrame().getByText("Hello there")).toBeVisible();

  // Hostile srcdoc content must be stripped/neutralized before reaching the
  // iframe, and the frame's CSP meta must be present.
  const srcdoc = await iframe.getAttribute("srcdoc");
  expect(srcdoc).toContain("Content-Security-Policy");
  expect(srcdoc).toContain("default-src 'none'");
  expect(srcdoc).not.toContain("<script");
  expect(srcdoc).not.toContain("javascript:");
  expect(srcdoc).not.toContain("onmouseover");

  // Give any (unexpectedly) executed script / resource fetch time to happen.
  await page.waitForTimeout(500);

  // Script payloads set document.title to PWNED*; none may have run.
  await expect(page).toHaveTitle("TestInbox Inspector");

  // No request may reach the tracking-pixel host. Chromium reports even
  // CSP-blocked fetch attempts as `request` events (failing with
  // net::ERR_BLOCKED_BY_CSP before touching the network), so assert that
  // every attempt was CSP-blocked and got no response — and, authoritatively,
  // that the canary server on 127.0.0.1:59999 received zero hits.
  for (const req of pixelRequests) {
    // Chromium reports the block reason as "csp" or "net::ERR_BLOCKED_BY_CSP"
    // depending on version.
    expect(req.failure()?.errorText.toLowerCase()).toContain("csp");
    expect(await req.response()).toBeNull();
  }
  expect(pixelHits).toBe(0);

  // The API key must never appear in any request URL.
  expect(requestedUrls.filter((u) => u.includes(API_KEY))).toEqual([]);
});
