"use client";

import { useMemo } from "react";
import DOMPurify from "dompurify";

/**
 * ADR-011: untrusted htmlBody is NEVER injected into the page DOM.
 *
 * Rendering path:
 *  1. Sanitize with DOMPurify (client-side): strips <script>, event handler
 *     attributes, javascript: URLs, etc.
 *  2. Prefix a strict CSP meta tag (default-src 'none'; style-src
 *     'unsafe-inline') so ALL remote resource loads (images, fonts, media)
 *     are blocked inside the frame — no tracking pixels, no SSRF-adjacent
 *     fetches. Remote images simply do not load.
 *  3. Render only via <iframe sandbox=""> srcdoc — no allow-scripts, no
 *     allow-same-origin, so even a sanitizer bypass cannot run script or
 *     touch the parent origin.
 *
 * There is deliberately no image proxy and no server-side fetching of any
 * resource referenced by the HTML.
 */

const CSP_META =
  `<meta http-equiv="Content-Security-Policy" ` +
  `content="default-src 'none'; style-src 'unsafe-inline'">`;

export default function HtmlPreview({ html }: { html: string }) {
  const srcDoc = useMemo(() => {
    // DOMPurify only runs in the browser; this component is client-only.
    const sanitized = DOMPurify.sanitize(html);
    return CSP_META + sanitized;
  }, [html]);

  return (
    <iframe
      className="html-preview"
      title="Sanitized HTML preview (sandboxed, remote loads blocked)"
      sandbox=""
      srcDoc={srcDoc}
      data-testid="html-preview"
    />
  );
}
