import { defineConfig, devices } from "@playwright/test";

const APP_PORT = 3311;
const MOCK_PORT = 4545;

export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: `http://127.0.0.1:${APP_PORT}`,
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: [
    {
      command: "node tests/mock-backend.mjs",
      url: `http://127.0.0.1:${MOCK_PORT}/__requests`,
      reuseExistingServer: !process.env.CI,
      env: { MOCK_PORT: String(MOCK_PORT) },
      timeout: 30_000,
    },
    {
      command: `npx next dev -p ${APP_PORT}`,
      url: `http://127.0.0.1:${APP_PORT}`,
      reuseExistingServer: !process.env.CI,
      env: { TESTINBOX_API_URL: `http://127.0.0.1:${MOCK_PORT}` },
      timeout: 120_000,
    },
  ],
});
