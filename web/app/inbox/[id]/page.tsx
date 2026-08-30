"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { apiGet, Inbox, MessagePage } from "@/lib/api";

export default function InboxPage() {
  const params = useParams<{ id: string }>();
  const inboxId = params.id;

  const [inbox, setInbox] = useState<Inbox | null>(null);
  const [page, setPage] = useState<MessagePage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [inboxRes, messagesRes] = await Promise.all([
        apiGet<Inbox>(`v1/inboxes/${encodeURIComponent(inboxId)}`),
        apiGet<MessagePage>(`v1/inboxes/${encodeURIComponent(inboxId)}/messages`),
      ]);
      setInbox(inboxRes);
      setPage(messagesRes);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [inboxId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return (
    <div>
      <div className="row">
        <h1>Inbox</h1>
        <button className="secondary" onClick={() => void refresh()} disabled={loading}>
          {loading ? "Refreshing…" : "Refresh"}
        </button>
      </div>

      {error ? <p className="error">{error}</p> : null}

      {inbox ? (
        <div className="card">
          <dl className="meta">
            <dt>Address</dt>
            <dd>{inbox.address}</dd>
            <dt>State</dt>
            <dd>{inbox.state}</dd>
            <dt>Expires</dt>
            <dd>{inbox.expiresAt}</dd>
            <dt>Created</dt>
            <dd>{inbox.createdAt}</dd>
            <dt>Mode</dt>
            <dd>{inbox.addressMode}</dd>
          </dl>
        </div>
      ) : null}

      <h2>Messages</h2>
      {page ? (
        page.items.length === 0 ? (
          <p className="muted">No messages yet.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Subject</th>
                <th>From</th>
                <th>Received</th>
                <th>Parse</th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((m) => (
                <tr key={m.id}>
                  <td>
                    <Link href={`/message/${encodeURIComponent(m.id)}`}>
                      {m.subject ?? "(no subject)"}
                    </Link>
                  </td>
                  <td>{m.from ?? m.envelopeFrom ?? ""}</td>
                  <td>{m.receivedAt}</td>
                  <td>{m.parseStatus}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )
      ) : null}
    </div>
  );
}
