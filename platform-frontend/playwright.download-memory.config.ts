import path from "node:path";
import { fileURLToPath } from "node:url";

import { defineConfig } from "@playwright/test";

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));
const testResults = path.resolve(
  frontendRoot,
  "output/playwright/download-memory",
);

export default defineConfig({
  testDir: path.resolve(frontendRoot, "e2e/download-memory"),
  testMatch: "**/*.spec.ts",
  outputDir: testResults,
  fullyParallel: false,
  workers: 1,
  timeout: 15 * 60 * 1000,
  expect: {
    timeout: 30 * 1000,
  },
  reporter: process.env.CI
    ? [["dot"], ["json", { outputFile: path.join(testResults, "report.json") }]]
    : [["list"]],
  use: {
    baseURL: "http://127.0.0.1:4174",
    serviceWorkers: "block",
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: {
        browserName: "chromium",
        launchOptions: {
          args: ["--js-flags=--expose-gc"],
        },
      },
    },
  ],
  webServer: {
    command: "pnpm exec vite --config e2e/download-memory/vite.config.ts",
    url: "http://127.0.0.1:4174/e2e/download-memory/",
    timeout: 120 * 1000,
    reuseExistingServer: !process.env.CI,
  },
});
