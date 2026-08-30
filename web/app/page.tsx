"use client";

import { FormEvent, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { API_KEY_STORAGE, INBOX_ID_STORAGE } from "@/lib/api";

export default function HomePage() {
  const router = useRouter();
  const [apiKey, setApiKey] = useState("");
  const [inboxId, setInboxId] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    try {
      setApiKey(sessionStorage.getItem(API_KEY_STORAGE) ?? "");
      setInboxId(sessionStorage.getItem(INBOX_ID_STORAGE) ?? "");
    } catch {
      /* sessionStorage unavailable */
    }
  }, []);

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    const key = apiKey.trim();
    const id = inboxId.trim();
    if (!key || !id) {
      setError("Both the API key and the inbox ID are required.");
      return;
    }
    try {
      sessionStorage.setItem(API_KEY_STORAGE, key);
      sessionStorage.setItem(INBOX_ID_STORAGE, id);
    } catch {
      setError("sessionStorage is unavailable in this browser context.");
      return;
    }
    router.push(`/inbox/${encodeURIComponent(id)}`);
  }

  return (
    <div className="card">
      <h1>Open an inbox</h1>
      <p className="muted">
        The API key is kept in sessionStorage for this tab only and sent to the
        backend through a local proxy — it never appears in a URL.
      </p>
      <form onSubmit={onSubmit}>
        <label htmlFor="apiKey">API key</label>
        <input
          id="apiKey"
          type="password"
          autoComplete="off"
          value={apiKey}
          onChange={(e) => setApiKey(e.target.value)}
        />
        <label htmlFor="inboxId">Inbox ID</label>
        <input
          id="inboxId"
          type="text"
          autoComplete="off"
          value={inboxId}
          onChange={(e) => setInboxId(e.target.value)}
        />
        {error ? <p className="error">{error}</p> : null}
        <button type="submit">Open inbox</button>
      </form>
    </div>
  );
}
