import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "TestInbox Inspector",
  description: "Minimal debugging UI for TestInbox (walking skeleton).",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>
        <header className="topbar">
          <a href="/">TestInbox Inspector</a>
          <span className="muted">debugging tool — not a dashboard</span>
        </header>
        <main className="container">{children}</main>
      </body>
    </html>
  );
}
