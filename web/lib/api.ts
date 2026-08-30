"use client";

/**
 * Client-side helper for talking to the backend through the local proxy
 * (/api/backend/...). The API key lives in sessionStorage only and is sent
 * via the x-testinbox-key request header — never in a URL.
 */

export const API_KEY_STORAGE = "testinbox.apiKey";
export const INBOX_ID_STORAGE = "testinbox.inboxId";

export function getApiKey(): string | null {
  try {
    return sessionStorage.getItem(API_KEY_STORAGE);
  } catch {
    return null;
  }
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/** GET a v1 path (e.g. "v1/inboxes/123") through the proxy; returns parsed JSON. */
export async function apiGet<T>(path: string): Promise<T> {
  const key = getApiKey();
  if (!key) {
    throw new ApiError(401, "No API key set. Go to the home page and enter one.");
  }
  const res = await fetch(`/api/backend/${path}`, {
    headers: { "x-testinbox-key": key },
    cache: "no-store",
  });
  if (!res.ok) {
    let detail = `${res.status}`;
    try {
      const problem = await res.json();
      detail = problem.detail ?? problem.title ?? detail;
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(res.status, `Request failed (${res.status}): ${detail}`);
  }
  return (await res.json()) as T;
}

/* Shapes from backend/api/contract/openapi.yaml (the authoritative contract). */

export interface Inbox {
  id: string;
  address: string;
  addressMode: string;
  state: string;
  createdAt: string;
  expiresAt: string;
}

export interface EmailHeader {
  name: string;
  value: string;
}

export interface EmailLink {
  href: string;
  text?: string;
}

export interface AttachmentMeta {
  id: string;
  fileName?: string;
  contentType?: string;
  sizeBytes: number;
}

export interface Message {
  id: string;
  inboxId: string;
  receivedAt: string;
  envelopeFrom?: string;
  envelopeTo: string;
  parseStatus: "OK" | "FAILED" | string;
  parseError?: string;
  from?: string;
  fromHeader?: string;
  toHeader?: string;
  subject?: string;
  textBody?: string;
  htmlBody?: string;
  headers?: EmailHeader[];
  links?: EmailLink[];
  attachments?: AttachmentMeta[];
  contentFingerprint: string;
  possibleDuplicateOfMessageId?: string;
  rawSizeBytes: number;
}

export interface MessagePage {
  items: Message[];
  nextCursor?: string;
}
