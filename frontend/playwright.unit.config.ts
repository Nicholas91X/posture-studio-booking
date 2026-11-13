import { defineConfig } from "@playwright/test";

// The pure functions (src/**/*.test.ts), with the same runner as the end-to-end tests but no browser and no server:
// a few seconds. The process is put in another zone on purpose: a computation that read the clock of the computer
// instead of Rome's would fail here (D-087).
process.env.TZ = "America/New_York";

export default defineConfig({
  testDir: "src",
  testMatch: /.*\.test\.ts$/,
  reporter: [["list"]],
});
