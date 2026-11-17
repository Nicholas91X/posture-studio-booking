// The panel against the real backend (step 13), on a phone. One story in order, only the paths of the brief:
// Paola signs in; she approves Giulia's request; she books for a new customer and reads the warning; she gives Giulia
// two sessions; the admin tries to deactivate Sara, who still has a future session.
// What the pages meet is prepared through the APIs, as in customer.spec.ts.
import { devices, expect, test, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { romeDateTime } from "../src/lib/format";
import { Actor, email, expectFitsTheScreen, settings, signInThroughThePages, todayInRome, type Schemas } from "./support";
import { FRONTEND_URL } from "./urls";

test.describe.configure({ mode: "serial" });

const DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"] as const;

/** A day of the calendar plus n days (no zone: plain days). */
function plusDays(day: string, n: number): string {
  const [year, month, date] = day.split("-").map(Number) as [number, number, number];
  return new Date(Date.UTC(year, month - 1, date + n)).toISOString().slice(0, 10);
}

let paola: { context: BrowserContext; page: Page };
let paolaApi: Actor;
let giulia: Actor;
let saraId: number;
let paolaId: number;
let requestId: number;
// the admin signs in once, in the browser: the setup uses the same session for the APIs. A second code for the same
// address within minutes would hit the limit of D-050 (3 every 15 minutes) when the whole suite runs
let admin: { context: BrowserContext; page: Page };

async function phone(browser: Browser): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ ...devices["Pixel 7"], baseURL: FRONTEND_URL, locale: "it-IT", timezoneId: "Europe/Rome" });
  // the notifications popup is set to "later": headless Chromium answers "denied" anyway, and a headed run must not
  // have a popup over the pages. The popup and a real push are tried by hand (docs/passi/13-pannello.md)
  await context.addInitScript(() => localStorage.setItem("pannello.notifiche.piu-tardi", String(Date.now())));
  const page = await context.newPage();
  page.on("pageerror", (error) => console.log(`[browser pageerror] ${error.message}`));
  return { context, page };
}

/** A practitioner made by the admin, with settings, one service of an hour and every day 8–20 from today. */
async function practitioner(admin: Actor, who: string, firstName: string, lastName: string, approval: "MANUAL" | "AUTOMATIC") {
  const created = await admin.call<Schemas["AdminPractitionerView"]>("POST", "/api/admin/practitioners", { email: email(who), firstName, lastName });
  const actor = await Actor.signIn(email(who));
  await actor.call("PUT", "/api/panel/settings", settings({ bookingApproval: approval }));
  const service = await actor.call<Schemas["ServiceOfferView"]>("POST", "/api/panel/services", {
    name: "Seduta posturale",
    durationMinutes: 60,
    priceCents: 3000,
    evaluation: false,
  });
  await actor.call("POST", "/api/panel/schedules", {
    validFrom: todayInRome(),
    bands: DAYS.map((dayOfWeek) => ({ dayOfWeek, startTime: "08:00", endTime: "20:00" })),
  });
  return { id: created.id, actor, serviceId: service.id };
}

test.beforeAll(async ({ browser }) => {
  admin = await phone(browser);
  await admin.page.goto("/pannello/professionisti");
  await expect(admin.page).toHaveURL(/\/accedi\?torna=%2Fpannello%2Fprofessionisti/);
  await signInThroughThePages(admin.page, "admin@e2e.local");
  await expect(admin.page).toHaveURL(`${FRONTEND_URL}/pannello/professionisti`);
  const adminApi = Actor.of(admin.context.request);
  const made = await practitioner(adminApi, "paola", "Paola", "Neri", "MANUAL");
  paolaApi = made.actor;
  paolaId = made.id;
  const sara = await practitioner(adminApi, "sara", "Sara", "Gialli", "AUTOMATIC");
  saraId = sara.id;

  // Giulia has a credit with Paola, and asks for a session in two days: with the manual approval it waits for Paola
  await paolaApi.call("POST", "/api/panel/credit-grants", {
    customer: { email: email("giulia"), firstName: "Giulia", lastName: "Verdi" },
    serviceId: made.serviceId,
    credits: 1,
    note: "Prova end-to-end",
  });
  giulia = await Actor.signIn(email("giulia"));
  const day = plusDays(todayInRome(), 3);
  const slots = await giulia.call<Schemas["PublicSlot"][]>(
    "GET",
    `/api/public/practitioners/${made.id}/services/${made.serviceId}/slots?from=${day}&to=${day}`,
  );
  const slot = slots[0];
  expect(slot, "a free slot for Giulia").toBeDefined();
  const request = await giulia.call<Schemas["CustomerBookingView"]>("POST", "/api/customer/bookings", {
    practitionerId: made.id,
    serviceId: made.serviceId,
    startsAt: slot?.start,
  });
  expect(request.status).toBe("PENDING_APPROVAL");
  requestId = request.id;

  // Sara has a future session, booked from her panel: it stops her deactivation (D-077)
  await sara.actor.call("POST", "/api/panel/bookings", {
    serviceId: sara.serviceId,
    startsAt: romeDateTime(plusDays(todayInRome(), 2), "10:00"),
    customer: { email: email("cliente-sara"), firstName: "Bruno", lastName: "Blu" },
  });
  await sara.actor.dispose();

  paola = await phone(browser);
});

test.afterAll(async () => {
  await admin?.context.close();
  await paola?.context.close();
  await paolaApi?.dispose();
  await giulia?.dispose();
});

test("il professionista accede al pannello con il codice e arriva all'agenda", async () => {
  const { page } = paola;
  await page.goto("/pannello");
  await expect(page).toHaveURL(/\/accedi\?torna=%2Fpannello/);
  await signInThroughThePages(page, email("paola"));

  await expect(page).toHaveURL(`${FRONTEND_URL}/pannello`);
  await expect(page.getByRole("heading", { name: "Agenda" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Sezioni" }).last().getByRole("link", { name: "Richieste" })).toBeVisible();
  await expectFitsTheScreen(page);

  // the home page of the site sends a practitioner to the panel (the link of a push is the site's address)
  await page.goto("/");
  await expect(page).toHaveURL(`${FRONTEND_URL}/pannello`);
});

test("il professionista approva una richiesta prima della scadenza", async () => {
  const { page } = paola;
  await page.goto("/pannello");
  await page.getByRole("link", { name: /1 richiesta da approvare/ }).click();
  await expect(page).toHaveURL(`${FRONTEND_URL}/pannello/richieste`);
  await expect(page.getByText("Giulia Verdi")).toBeVisible();
  await expect(page.getByText(/Rispondi entro/)).toBeVisible();
  await expectFitsTheScreen(page);

  await page.getByRole("button", { name: "Approva" }).click();
  await expect(page.getByText(/Approvata: la seduta di .* è confermata/)).toBeVisible();
  await expect(page.getByText("Nessuna richiesta in attesa.")).toBeVisible();

  const booking = await giulia.call<Schemas["CustomerBookingView"]>("GET", `/api/customer/bookings/${requestId}`);
  expect(booking.status).toBe("CONFIRMED");
});

test("prenotazione per un cliente nuovo: prima la conferma, poi l'avviso di controllare l'email", async () => {
  const { page } = paola;
  await page.goto("/pannello/prenota");
  await page.getByLabel("Giorno").fill(plusDays(todayInRome(), 1));
  await page.getByLabel("Ora").fill("10:00");
  await page.getByLabel("Email del cliente").fill(email("nuovo"));
  await page.getByLabel("Nome", { exact: true }).fill("Nadia");
  await page.getByLabel("Cognome").fill("Nuovi");
  await expectFitsTheScreen(page);
  await page.getByRole("button", { name: "Continua" }).click();

  const confirm = page.getByRole("dialog", { name: "Confermi la prenotazione?" });
  await expect(confirm.getByText("Nadia Nuovi")).toBeVisible();
  await expect(confirm.getByText("Valutazione nella stanza")).toBeHidden();
  await confirm.getByRole("button", { name: "Prenota" }).click();

  await expect(page.getByRole("heading", { name: "Seduta prenotata" })).toBeVisible();
  await expect(page.getByText("Da pagare in studio.")).toBeVisible();
  await expect(page.getByText("Cliente nuovo: controlla l'email.")).toBeVisible();
  await expect(page.getByText(email("nuovo"))).toBeVisible();

  // the session is in the agenda of that day, with the customer's name
  await page.getByRole("link", { name: "Vedi la seduta" }).click();
  await expect(page.getByText("Nadia Nuovi")).toBeVisible();
  await expect(page.getByText("Confermata", { exact: true })).toBeVisible();
});

test("una concessione: due sedute regalate a Giulia, con la nota, poi nei suoi crediti", async () => {
  const { page } = paola;
  await page.goto("/pannello/clienti");
  await page.getByRole("button", { name: "Regala sedute a un cliente" }).click();
  const gift = page.getByRole("dialog", { name: "Regala sedute" });
  await gift.getByLabel("Email del cliente").fill(email("giulia"));
  await gift.getByLabel("Nome", { exact: true }).fill("Giulia");
  await gift.getByLabel("Cognome").fill("Verdi");
  await gift.getByLabel("Sedute").fill("2");
  await gift.getByLabel(/Nota/).fill("Per la seduta spostata dallo studio");
  await gift.getByRole("button", { name: "Regala" }).click();

  await expect(page.getByText(/Regalo registrato: 2 crediti per Seduta posturale/)).toBeVisible();
  await page.getByRole("link", { name: /Giulia Verdi/ }).click();
  await expect(page.getByRole("heading", { name: "Giulia Verdi" })).toBeVisible();
  const lot = page.locator(".card", { hasText: "Omaggio dello studio" }).filter({ hasText: "2 crediti" });
  await lot.getByRole("button", { name: "Storia e note" }).click();
  await expect(lot.getByText("Nota: Per la seduta spostata dallo studio")).toBeVisible();
  await expectFitsTheScreen(page);
});

// D-091: Paola compares her agenda with Sara's. In two days Sara is busy 10-11, Paola is free all day, both work 8-20:
// free together 8-10 and 11-20. Of Sara's session Paola sees only "Occupato"
test("le agende a confronto: solo occupato e libero, e gli orari liberi insieme", async () => {
  const { page } = paola;
  const day = plusDays(todayInRome(), 2);
  await page.goto(`/pannello/agende?giorno=${day}&chi=${paolaId},${saraId}`);

  await expect(page.getByRole("heading", { name: "Agende a confronto" })).toBeVisible();
  await expect(page.getByRole("img", { name: /Sara Gialli.*10:00–11:00, Occupato/ })).toBeVisible();
  await expect(page.getByText("Liberi insieme: 08:00–10:00, 11:00–20:00")).toBeVisible();
  // nothing of Sara's customer anywhere on the page
  await expect(page.getByText("Bruno")).toHaveCount(0);
  await expectFitsTheScreen(page);
});

test("l'admin prova a disattivare un professionista con sedute future: rifiutato, e vede quali sono", async () => {
  const { page } = admin;
  await page.goto("/pannello/professionisti");
  await page.getByRole("link", { name: /Sara Gialli/ }).click();
  await expect(page).toHaveURL(`${FRONTEND_URL}/pannello/professionisti/${saraId}`);
  await expect(page.getByRole("button", { name: "Cancella per conto dello studio" })).toBeVisible();
  await expectFitsTheScreen(page);

  await page.getByRole("button", { name: "Disattiva il professionista" }).click();
  const ask = page.getByRole("dialog", { name: "Disattivare il professionista?" });
  await ask.getByRole("button", { name: "Sì, disattiva" }).click();
  await expect(ask.getByText(/Il professionista ha ancora sedute future o richieste in attesa/)).toBeVisible();
  await ask.getByRole("button", { name: "No" }).click();
  await expect(page.getByText("Attivo", { exact: true })).toBeVisible();
});
