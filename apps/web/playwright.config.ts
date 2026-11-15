import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end journey (ADR-0008 §1 검증). Expects the API on API_URL (default :8080, started by CI or by hand with
 * OPENRUN_PAYMENT_PG=fake) and the web server on WEB_BASE_URL (default :3000). No webServer: both are started outside.
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 90_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.WEB_BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
