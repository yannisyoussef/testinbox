/** @type {import('next').NextConfig} */
const nextConfig = {
  // Debugging tool only — no image optimization, no telemetry surprises.
  reactStrictMode: true,
  // Playwright drives the dev server via 127.0.0.1.
  allowedDevOrigins: ["127.0.0.1"],
};

export default nextConfig;
