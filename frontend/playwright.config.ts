import { defineConfig, devices } from "@playwright/test";
import { BACKEND_URL, FRONTEND_URL } from "./e2e/urls";

// End-to-end tests against the real backend (step 12), on a phone (Pixel 7, Chromium): the site is phone first.
// They start their own backend and their own frontend, next to the developer's ones and never touching them:
// - backend on 8081 with a database of its own, emptied at every run (e2e/start-backend.mjs);
// - frontend on 3001, with /api sent to that backend, built in .next-e2e.
// Needed before: Docker with PostgreSQL and Mailpit up (infra/docker-compose.dev.yml), the .env of the project.
export default defineConfig({
  testDir: "e2e",
  // one story, in order: the tests share the practitioner they prepare
  workers: 1,
  fullyParallel: false,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [["list"]],
  use: {
    baseURL: FRONTEND_URL,
    locale: "it-IT",
    timezoneId: "Europe/Rome",
    trace: "retain-on-failure",
  },
  projects: [{ name: "telefono", use: { ...devices["Pixel 7"] } }],
  webServer: [
    {
      command: "node e2e/start-backend.mjs",
      url: `${BACKEND_URL}/api/public/practitioners`,
      timeout: 300_000,
      reuseExistingServer: false,
      stdout: "pipe",
    },
    {
      command: "npx next dev --port 3001",
      url: FRONTEND_URL,
      timeout: 180_000,
      reuseExistingServer: false,
      env: { BACKEND_URL, NEXT_DIST_DIR: ".next-e2e" },
    },
  ],
});
