import net from "node:net";

/**
 * A raw SMTP client for the mail-edge contract tests.
 *
 * `src/smtp.mjs` deliberately throws on any reply it did not expect, which is
 * right for a delivery helper and wrong here: most of these tests are about
 * what the edge says when it REFUSES, and about whether two recipients get
 * byte-identical answers. This client therefore never throws on a reply code —
 * it records every code, its full text, and how long it took, and lets the test
 * decide.
 *
 * It also drives multiple RCPTs in one transaction, which the ADR-026 atomicity
 * proof needs: the point is one DATA carrying several recipients, not several
 * deliveries that happen to succeed.
 *
 * Dependency-free for the same reason as the rest of the suite — a library that
 * transparently retried, reordered or rewrote would blur exactly what the edge
 * was asked and exactly what it answered.
 */
export async function edgeConversation({
  host,
  port,
  from,
  recipients,
  raw,
  helo = "sender.rehearsal.invalid",
  timeoutMs = 60_000,
  quit = true,
}) {
  const socket = net.createConnection({ host, port });
  socket.setTimeout(timeoutMs);
  socket.setEncoding("latin1");

  let buffer = "";
  let pending = null;
  const transcript = [];

  const REPLY = /^(?:\d{3}-[^\n]*\n)*(\d{3})([ ])([^\n]*)\r?\n/;

  const tryResolve = () => {
    if (!pending) return;
    const match = buffer.match(REPLY);
    if (!match) return;
    buffer = buffer.slice(match[0].length);
    const { resolve, startedAt } = pending;
    pending = null;
    const reply = {
      code: Number.parseInt(match[1], 10),
      // The full reply verbatim, because equivalence is asserted on text as
      // well as code — a differing enhanced status code would be an oracle.
      text: match[0].trimEnd(),
      ms: Number(process.hrtime.bigint() - startedAt) / 1e6,
    };
    transcript.push(reply.text);
    resolve(reply);
  };

  const fail = (error) => {
    if (pending) {
      pending.reject(error);
      pending = null;
    }
  };

  socket.on("data", (chunk) => {
    buffer += chunk;
    tryResolve();
  });
  socket.on("error", fail);
  socket.on("timeout", () => fail(new Error(`SMTP timeout after ${timeoutMs}ms`)));
  socket.on("close", () => fail(new Error("SMTP connection closed mid-reply")));

  const readReply = () =>
    new Promise((resolve, reject) => {
      pending = { resolve, reject, startedAt: process.hrtime.bigint() };
      tryResolve();
    });

  const command = async (line) => {
    socket.write(`${line}\r\n`);
    return readReply();
  };

  try {
    const greeting = await readReply();
    const ehlo = await command(`EHLO ${helo}`);
    const mail = await command(`MAIL FROM:<${from}>`);

    const rcptReplies = [];
    for (const recipient of recipients) {
      rcptReplies.push({ recipient, ...(await command(`RCPT TO:<${recipient}>`)) });
    }

    let dataReply = null;
    let bodyReply = null;
    // Only open DATA when at least one recipient was accepted: Postfix answers
    // 554 to DATA with no valid recipients, which would mask what the test is
    // actually asking about.
    if (raw !== undefined && rcptReplies.some((r) => r.code >= 200 && r.code < 300)) {
      dataReply = await command("DATA");
      if (dataReply.code === 354) {
        const body = Buffer.from(raw)
          .toString("latin1")
          .replace(/\r?\n/g, "\r\n")
          .replace(/^\./gm, "..");
        socket.write(body.endsWith("\r\n") ? body : `${body}\r\n`);
        const startedAt = process.hrtime.bigint();
        pending = null;
        socket.write(".\r\n");
        bodyReply = await new Promise((resolve, reject) => {
          pending = { resolve, reject, startedAt };
          tryResolve();
        });
      }
    }

    let quitReply = null;
    if (quit) quitReply = await command("QUIT");

    return { greeting, ehlo, mail, rcptReplies, dataReply, bodyReply, quitReply, transcript };
  } finally {
    socket.end();
    socket.destroy();
  }
}

/**
 * Builds a message of an exact byte length using realistic line lengths.
 *
 * The line length matters: a single multi-megabyte line is accounted for
 * differently by Postfix, so padding with one long line measures the generator
 * rather than the transport. This wraps at 76 columns like a real message.
 */
export function messageOfSize({ from, to, subject, bytes }) {
  const headers = `From: ${from}\r\nTo: ${to}\r\nSubject: ${subject}\r\n\r\n`;
  if (headers.length > bytes) throw new Error(`headers alone are ${headers.length} bytes`);
  const line = `${"x".repeat(76)}\r\n`;
  const parts = [headers];
  let have = headers.length;
  while (have + line.length <= bytes) {
    parts.push(line);
    have += line.length;
  }
  const remainder = bytes - have;
  if (remainder >= 2) parts.push(`${"x".repeat(remainder - 2)}\r\n`);
  const raw = parts.join("");
  if (Buffer.byteLength(raw, "latin1") !== bytes) {
    throw new Error(`built ${Buffer.byteLength(raw, "latin1")} bytes, wanted ${bytes}`);
  }
  return raw;
}
