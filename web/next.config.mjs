/** @type {import('next').NextConfig} */
const nextConfig = {
  // Debugging tool only — no image optimization, no telemetry surprises.
  reactStrictMode: true,
  // Playwright drives the dev server via 127.0.0.1.
  allowedDevOrigins: ["127.0.0.1"],
  // Deployable as a self-contained Node server with only the files it uses
  // (ADR-028: small runtime images, no build tooling, no source tree).
  output: "standalone",
};

export default nextConfig;
