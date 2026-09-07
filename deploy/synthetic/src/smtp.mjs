import net from "node:net";

/**
 * A minimal SMTP client over a raw socket.
 *
 * Deliberately dependency-free rather than nodemailer: the synthetic suite is
 * a deployment gate, and every dependency it carries is one more thing that
 * can fail for a reason unrelated to the deployment. It also keeps the
 * *client* side honest — a library that transparently retries or reformats
 * would blur what the gateway actually received.
 */
export async function sendRawSmtp({ host, port, from, to, raw, timeoutMs = 30_000 }) {
  const socket = net.createConnection({ host, port });
  socket.setTimeout(timeoutMs);
  socket.setEncoding("latin1");

  let buffer = "";
  let pending = null;
  const transcript = [];

  const fail = (error) => {
    if (pending) {
      pending.reject(error);
      pending = null;
    }
  };

  // A reply ends at a line whose 4th character is a space (RFC 5321 §4.2);
  // earlier lines of a multiline reply use '-' in that position.
  const REPLY = /^(?:\d{3}-[^\n]*\n)*(\d{3}) [^\n]*\r?\n/;

  const tryResolve = () => {
    if (!pending) return;
    const match = buffer.match(REPLY);
    if (!match) return;
    buffer = buffer.slice(match[0].length);
    const { resolve } = pending;
    pending = null;
    transcript.push(match[0].trimEnd());
    resolve({ code: Number.parseInt(match[1], 10), text: match[0].trimEnd() });
  };

  socket.on("data", (chunk) => {
    buffer += chunk;
    tryResolve();
  });
  socket.on("error", fail);
  socket.on("timeout", () => fail(new Error(`SMTP timed out after ${timeoutMs}ms`)));
  socket.on("close", () => fail(new Error("SMTP connection closed by the server")));

  const readReply = () =>
    new Promise((resolve, reject) => {
      pending = { resolve, reject };
      // The reply may already be buffered from a previous chunk.
      tryResolve();
    });

  const send = async (line, expected) => {
    socket.write(`${line}\r\n`);
    const reply = await readReply();
    if (reply.code !== expected) {
      throw new Error(`SMTP ${line.split(":")[0]} -> ${reply.code} (expected ${expected}): ${reply.text}`);
    }
    return reply;
  };

  try {
    const greeting = await readReply();
    if (greeting.code !== 220) throw new Error(`SMTP greeting was ${greeting.code}: ${greeting.text}`);

    await send("EHLO synthetic.testinbox", 250);
    await send(`MAIL FROM:<${from}>`, 250);
    const rcpt = await send(`RCPT TO:<${to}>`, 250);
    await send("DATA", 354);

    // Dot-stuffing: a body line that is exactly "." would otherwise terminate DATA.
    const body = Buffer.from(raw).toString("latin1").replace(/\r?\n/g, "\r\n").replace(/^\./gm, "..");
    socket.write(body.endsWith("\r\n") ? body : `${body}\r\n`);
    const accepted = await send(".", 250);
    // The instant the gateway said 250, the delivery was persisted and
    // pg_notify had been issued in the same transaction (ADR-020). Callers
    // measuring wake-up latency must start from here, not from when they began
    // sending — everything before this point is connection setup, transfer,
    // MIME parsing and the blob write, none of which is notification latency.
    const acceptedAt = Date.now();

    await send("QUIT", 221);
    return { rcptReply: rcpt, dataReply: accepted, acceptedAt, transcript };
  } finally {
    socket.end();
    socket.destroy();
  }
}

/** Builds a multipart/alternative message with an inline text/plain attachment. */
export function buildMime({ from, to, subject, text, html, attachment }) {
  const boundary = `tb-${Math.random().toString(36).slice(2)}`;
  const inner = `tbalt-${Math.random().toString(36).slice(2)}`;
  const lines = [
    `From: TestInbox Synthetic <${from}>`,
    `To: <${to}>`,
    `Subject: ${subject}`,
    `Date: ${new Date().toUTCString()}`,
    "MIME-Version: 1.0",
    `Content-Type: multipart/mixed; boundary="${boundary}"`,
    "",
    `--${boundary}`,
    `Content-Type: multipart/alternative; boundary="${inner}"`,
    "",
    `--${inner}`,
    'Content-Type: text/plain; charset="utf-8"',
    "",
    text,
    "",
    `--${inner}`,
    'Content-Type: text/html; charset="utf-8"',
    "",
    html,
    "",
    `--${inner}--`,
    "",
  ];
  if (attachment) {
    lines.push(
      `--${boundary}`,
      `Content-Type: ${attachment.contentType}; name="${attachment.fileName}"`,
      `Content-Disposition: attachment; filename="${attachment.fileName}"`,
      "Content-Transfer-Encoding: base64",
      "",
      Buffer.from(attachment.content).toString("base64"),
      "",
    );
  }
  lines.push(`--${boundary}--`, "");
  return lines.join("\r\n");
}
