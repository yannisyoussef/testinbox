"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { apiGet, Message } from "@/lib/api";
import HtmlPreview from "@/components/HtmlPreview";

export default function MessagePage() {
  const params = useParams<{ id: string }>();
  const messageId = params.id;

  const [message, setMessage] = useState<Message | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setMessage(await apiGet<Message>(`v1/messages/${encodeURIComponent(messageId)}`));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [messageId]);

  useEffect(() => {
    void load();
  }, [load]);

  if (error) {
    return <p className="error">{error}</p>;
  }
  if (!message) {
    return <p className="muted">Loading…</p>;
  }

  return (
    <div>
      <h1 data-testid="message-subject">{message.subject ?? "(no subject)"}</h1>
      <div className="card">
        <dl className="meta">
          <dt>From</dt>
          <dd data-testid="message-from">
            {message.from ?? message.envelopeFrom ?? ""}
          </dd>
          <dt>Received</dt>
          <dd>{message.receivedAt}</dd>
          <dt>Parse status</dt>
          <dd>{message.parseStatus}</dd>
          {message.parseError ? (
            <>
              <dt>Parse error</dt>
              <dd>{message.parseError}</dd>
            </>
          ) : null}
          <dt>Inbox</dt>
          <dd>
            <Link href={`/inbox/${encodeURIComponent(message.inboxId)}`}>
              {message.inboxId}
            </Link>
          </dd>
        </dl>
      </div>

      <h2>Headers</h2>
      {message.headers && message.headers.length > 0 ? (
        <table data-testid="headers-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Value</th>
            </tr>
          </thead>
          <tbody>
            {message.headers.map((h, i) => (
              <tr key={`${h.name}-${i}`}>
                <td>{h.name}</td>
                <td>{h.value}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="muted">No headers.</p>
      )}

      <h2>Text body</h2>
      {message.textBody ? (
        <pre data-testid="text-body">{message.textBody}</pre>
      ) : (
        <p className="muted">No text body.</p>
      )}

      <h2>Links</h2>
      <p className="muted">
        Extracted links are shown as text only — they are never fetched and
        never rendered as clickable anchors.
      </p>
      {message.links && message.links.length > 0 ? (
        <ul className="links" data-testid="links-list">
          {message.links.map((l, i) => (
            <li key={`${l.href}-${i}`}>
              {l.text ? `${l.text} — ` : ""}
              {l.href}
            </li>
          ))}
        </ul>
      ) : (
        <p className="muted">No links extracted.</p>
      )}

      <h2>Attachments</h2>
      {message.attachments && message.attachments.length > 0 ? (
        <table data-testid="attachments-list">
          <thead>
            <tr>
              <th>File name</th>
              <th>Content type</th>
              <th>Size (bytes)</th>
            </tr>
          </thead>
          <tbody>
            {message.attachments.map((a) => (
              <tr key={a.id}>
                <td>{a.fileName ?? "(unnamed)"}</td>
                <td>{a.contentType ?? "unknown"}</td>
                <td>{a.sizeBytes}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="muted">No attachments.</p>
      )}

      <h2>HTML preview</h2>
      {message.htmlBody ? (
        <HtmlPreview html={message.htmlBody} />
      ) : (
        <p className="muted">No HTML body.</p>
      )}
    </div>
  );
}
