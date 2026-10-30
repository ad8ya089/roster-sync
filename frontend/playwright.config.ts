import { defineConfig } from "@playwright/test";
import { existsSync } from "node:fs";
if (existsSync("../.env")) process.loadEnvFile("../.env");

export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 60_000,
  workers: 1,
  globalSetup: "./tests/e2e/prepare-checkpoint.ts",
  use: {
    baseURL: process.env.BASE_URL ?? "http://127.0.0.1:8080",
    locale: "en-GB",
    timezoneId: "Asia/Kolkata",
    trace: "off",
    screenshot: "off",
    video: "off",
  },
  reporter: [["list"]],
});
