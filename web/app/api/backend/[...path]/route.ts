import { NextRequest } from "next/server";

/**
 * Thin same-origin proxy to the TestInbox backend.
 *
 * - Backend origin comes from TESTINBOX_API_URL (default http://localhost:8080).
 * - Only paths starting with "v1/" are forwarded.
 * - The API key is taken from the "x-testinbox-key" request header supplied by
 *   client code and forwarded as "Authorization: Bearer <key>". The key never
 *   appears in a URL and is never logged here.
 */

const BACKEND_ORIGIN = (
  process.env.TESTINBOX_API_URL ?? "http://localhost:8080"
).replace(/\/+$/, "");

function problem(status: number, title: string): Response {
  return new Response(
    JSON.stringify({
      type: "about:blank",
      title,
      status,
    }),
    {
      status,
      headers: { "content-type": "application/problem+json" },
    },
  );
}

async function proxy(
  req: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
): Promise<Response> {
  const { path } = await ctx.params;
  const segments = path ?? [];

  // Only the versioned API surface, and no traversal tricks.
  if (
    segments.length < 2 ||
    segments[0] !== "v1" ||
    segments.some((s) => s === "" || s === "." || s === ".." || s.includes("/"))
  ) {
    return problem(404, "Only v1/ backend paths are proxied");
  }

  const key = req.headers.get("x-testinbox-key");
  if (!key) {
    return problem(401, "Missing x-testinbox-key header");
  }

  const target = `${BACKEND_ORIGIN}/${segments
    .map(encodeURIComponent)
    .join("/")}${req.nextUrl.search}`;

  const headers: Record<string, string> = {
    authorization: `Bearer ${key}`,
    accept: req.headers.get("accept") ?? "application/json",
  };
  const contentType = req.headers.get("content-type");
  if (contentType) headers["content-type"] = contentType;

  const hasBody = req.method !== "GET" && req.method !== "HEAD";

  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: req.method,
      headers,
      body: hasBody ? await req.arrayBuffer() : undefined,
      cache: "no-store",
      redirect: "manual",
    });
  } catch {
    return problem(502, "Backend unreachable");
  }

  const responseHeaders = new Headers();
  const upstreamContentType = upstream.headers.get("content-type");
  if (upstreamContentType) {
    responseHeaders.set("content-type", upstreamContentType);
  }
  responseHeaders.set("cache-control", "no-store");

  return new Response(upstream.body, {
    status: upstream.status,
    headers: responseHeaders,
  });
}

export {
  proxy as GET,
  proxy as POST,
  proxy as DELETE,
  proxy as PUT,
  proxy as PATCH,
};
