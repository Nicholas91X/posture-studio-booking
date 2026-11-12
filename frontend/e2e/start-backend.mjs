// The backend of the end-to-end tests (playwright.config.ts): the real application, on port 8081, with a database of
// its own (posture_studio_e2e) dropped and created again at every run, in the PostgreSQL container of development.
// Everything else comes from the .env of the project (D-018), with a few values fixed for the tests:
// - ADMIN_EMAIL: an admin of the tests, never the developer's;
// - PAYMENT_ACCOUNTS empty: no test talks to Stripe (the payment by card is the manual test with the Stripe CLI);
// - SMS_TO_MAILPIT: the SMS codes as emails in Mailpit;
// - APP_URL: the links in the emails point to the frontend of the tests.
import { execFileSync, spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const env = Object.fromEntries(
  readFileSync(join(root, ".env"), "utf8")
    .split(/\r?\n/)
    .filter((line) => /^[A-Z0-9_]+=/.test(line))
    .map((line) => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)]),
);

const DATABASE = "posture_studio_e2e";
const container = process.env.E2E_POSTGRES_CONTAINER ?? "posture-studio-postgres-1";
const user = env.POSTGRES_USER ?? "posture_studio";

function psql(sql) {
  execFileSync("docker", ["exec", container, "psql", "-U", user, "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", sql], {
    stdio: "inherit",
  });
}

psql(`drop database if exists ${DATABASE} with (force)`);
psql(`create database ${DATABASE}`);

const backend = join(root, "backend");
const windows = process.platform === "win32";
// the full path: on Windows cmd does not always find mvnw.cmd by name (seen in step 10, D-072)
const child = spawn(join(backend, windows ? "mvnw.cmd" : "mvnw"), ["-q", "spring-boot:run"], {
  cwd: backend,
  shell: windows,
  stdio: "inherit",
  env: {
    ...process.env,
    POSTGRES_DB: DATABASE,
    SERVER_PORT: "8081",
    ADMIN_EMAIL: "admin@e2e.local",
    PAYMENT_ACCOUNTS: "",
    SMS_TO_MAILPIT: "true",
    APP_URL: "http://localhost:3001",
    OPENAPI_ENABLED: "false",
    BOOKING_JOBS_ENABLED: "true",
  },
});

const stop = () => child.kill();
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
child.on("exit", (code) => process.exit(code ?? 0));
