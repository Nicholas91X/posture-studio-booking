// What the end-to-end tests need around the browser (step 12):
// - the access codes, read from the emails in Mailpit, as a person would read them;
// - actors that call the backend's APIs with their own session and CSRF token, to prepare what the customer meets
//   (the admin creates a practitioner, the practitioner sets services, hours and credits). The pages of the panel
//   are step 13: here their APIs, with the types generated from the contract like the site.
import { expect, request, type APIRequestContext, type Page } from "@playwright/test";
import type { components } from "../src/api/schema";
import { BACKEND_URL, MAILPIT_URL } from "./urls";

export type Schemas = components["schemas"];

// a different address at every run: Mailpit keeps the emails of the runs before
export const RUN = Date.now().toString(36);
export const email = (who: string) => `${who}-${RUN}@e2e.local`;

type MailpitList = { messages: { ID: string }[] };
type MailpitMessage = { Text: string };

// The latest email with an access code for an address, if any. Only those emails: the same address may receive
// others at the same moment (a gift of credits is sent after its commit, in another thread, and can arrive after
// the code). Found by id, never by time: the clock of the Mailpit container may be seconds behind the computer's
// (seen on Windows with Docker: an email sent after the request looked older than it)
async function latestCodeEmail(address: string): Promise<string | undefined> {
  const mailpit = await request.newContext({ baseURL: MAILPIT_URL });
  try {
    const found = (await (
      await mailpit.get("/api/v1/search", { params: { query: `to:"${address}" subject:"codice di accesso"`, limit: "1" } })
    ).json()) as MailpitList;
    return found.messages[0]?.ID;
  } finally {
    await mailpit.dispose();
  }
}

/** Asks for a code with ask(), then waits for the new email and reads the code in it. */
export async function codeFor(address: string, ask: () => Promise<unknown>): Promise<string> {
  const before = await latestCodeEmail(address);
  await ask();
  let id: string | undefined;
  await expect
    .poll(async () => (id = await latestCodeEmail(address)), { timeout: 20_000, intervals: [300, 500, 1000] })
    .not.toBe(before);
  const mailpit = await request.newContext({ baseURL: MAILPIT_URL });
  try {
    const message = (await (await mailpit.get(`/api/v1/message/${id}`)).json()) as MailpitMessage;
    const code = /\b(\d{6})\b/.exec(message.Text)?.[1];
    expect(code, "a code of six digits in the email").toBeDefined();
    return code as string;
  } finally {
    await mailpit.dispose();
  }
}

/** Someone calling the backend directly, with their own cookies (session and CSRF token). */
export class Actor {
  private constructor(private readonly http: APIRequestContext) {}

  static async signIn(address: string): Promise<Actor> {
    const http = await request.newContext({ baseURL: BACKEND_URL });
    const actor = new Actor(http);
    const code = await codeFor(address, () => actor.call("POST", "/api/public/auth/login-code", { email: address }));
    await actor.call("POST", "/api/public/auth/session", { email: address, code });
    return actor;
  }

  async call<T = unknown>(method: "GET" | "POST" | "PUT" | "DELETE", path: string, body?: unknown): Promise<T> {
    if (method !== "GET") {
      await this.ensureToken();
    }
    const response = await this.http.fetch(path, {
      method,
      headers: method === "GET" ? {} : { "X-XSRF-TOKEN": await this.token() },
      ...(body === undefined ? {} : { data: body }),
    });
    if (!response.ok()) {
      throw new Error(`${method} ${path}: ${response.status()} ${await response.text()}`);
    }
    const text = await response.text();
    return (text === "" ? undefined : JSON.parse(text)) as T;
  }

  private async ensureToken() {
    if ((await this.token()) === "") {
      await this.http.get("/api/public/practitioners");
    }
  }

  private async token(): Promise<string> {
    const cookies = (await this.http.storageState()).cookies;
    return cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
  }

  dispose() {
    return this.http.dispose();
  }
}

/** The settings of a practitioner, the defaults of the studio with some changes (D-041, D-030). */
export function settings(changes: Partial<Schemas["PractitionerSettings"]> = {}): Schemas["PractitionerSettings"] {
  return {
    cancellationNoticeHours: 24,
    singleCreditValidityDays: 90,
    slotStepMinutes: 60,
    minBookingNoticeHours: 24,
    bookingHorizonDays: 21,
    maxFutureBookings: 4,
    bookingApproval: "AUTOMATIC",
    approvalWindowHours: 12,
    ...changes,
  };
}

/** Today in Rome, 2026-11-12. */
export function todayInRome(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Rome", year: "numeric", month: "2-digit", day: "2-digit" }).format(
    new Date(),
  );
}

/** Signs a customer in through the pages, as a person does: email, code from the email, and names the first time. */
export async function signInThroughThePages(page: Page, address: string, names?: { firstName: string; lastName: string }) {
  await page.getByLabel("La tua email").fill(address);
  const code = await codeFor(address, () => page.getByRole("button", { name: "Mandami il codice" }).click());
  await page.getByLabel("Codice").fill(code);
  await page.getByRole("button", { name: "Accedi", exact: true }).click();
  if (names !== undefined) {
    await page.getByLabel("Nome", { exact: true }).fill(names.firstName);
    await page.getByLabel("Cognome").fill(names.lastName);
    await page.getByRole("button", { name: "Continua" }).click();
  }
}

/** No horizontal scroll on a phone: nothing wider than the screen. */
export async function expectFitsTheScreen(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow, "page wider than the phone screen").toBeLessThanOrEqual(0);
}
