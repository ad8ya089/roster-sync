import { defineConfig } from "@playwright/test";
const baseURL = process.env.LIVE_BASE_URL;
if (baseURL !== "https://rostersync-aditya.duckdns.org")
  throw new Error("Set LIVE_BASE_URL to the approved HTTPS assignment host");
if (!process.env.DEMO_PASSWORD)
  throw new Error("Provide the private demo password in the environment");
export default defineConfig({
  testDir: "./tests/live",
  timeout: 120_000,
  workers: 1,
  outputDir: "./test-results/live",
  reporter: [["list"]],
  use: {
    baseURL,
    locale: "en-GB",
    timezoneId: "Asia/Kolkata",
    trace: "off",
    screenshot: "off",
    video: "off",
  },
});
