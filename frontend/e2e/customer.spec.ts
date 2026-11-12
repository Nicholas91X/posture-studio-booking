// The customer's site against the real backend (step 12), on a phone. One story in order:
// the admin creates Anna, Anna sets a service, her hours and three credits for Maria (through the APIs, the panel is
// step 13); then Maria uses the site, and a new customer signs in for the first time.
import { devices, expect, test, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { Actor, email, expectFitsTheScreen, settings, signInThroughThePages, todayInRome, type Schemas } from "./support";
import { FRONTEND_URL } from "./urls";

test.describe.configure({ mode: "serial" });

const DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"] as const;

let anna: Actor;
let practitionerId: number;
let serviceId: number;
let maria: { context: BrowserContext; page: Page };

async function phone(browser: Browser): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ ...devices["Pixel 7"], baseURL: FRONTEND_URL, locale: "it-IT", timezoneId: "Europe/Rome" });
  return { context, page: await context.newPage() };
}

test.beforeAll(async ({ browser }) => {
  const admin = await Actor.signIn("admin@e2e.local");
  const created = await admin.call<Schemas["AdminPractitionerView"]>("POST", "/api/admin/practitioners", {
    email: email("anna"),
    firstName: "Anna",
    lastName: "Rossi",
  });
  practitionerId = created.id;
  await admin.dispose();

  anna = await Actor.signIn(email("anna"));
  await anna.call("PUT", "/api/panel/settings", settings());
  const service = await anna.call<Schemas["ServiceOfferView"]>("POST", "/api/panel/services", {
    name: "Seduta posturale",
    durationMinutes: 60,
    priceCents: 3000,
    evaluation: false,
  });
  serviceId = service.id;
  // every day of the week, so the tests find free slots whatever day they run
  await anna.call("POST", "/api/panel/schedules", {
    validFrom: todayInRome(),
    bands: DAYS.map((dayOfWeek) => ({ dayOfWeek, startTime: "08:00", endTime: "20:00" })),
  });
  await anna.call("POST", "/api/panel/credit-grants", {
    customer: { email: email("maria"), firstName: "Maria", lastName: "Bianchi" },
    serviceId,
    credits: 3,
    note: "Prova end-to-end",
  });

  maria = await phone(browser);
  maria.page.on("console", (message) => console.log(`[browser ${message.type()}] ${message.text()}`));
  maria.page.on("pageerror", (error) => console.log(`[browser pageerror] ${error.message}`));
  await maria.page.goto("/accedi");
  await signInThroughThePages(maria.page, email("maria"));
  await expect(maria.page).toHaveURL(`${FRONTEND_URL}/`);
});

test.afterAll(async () => {
  await maria?.context.close();
  await anna?.dispose();
});

async function chooseTheFirstFreeSlot(page: Page) {
  await page.goto(`/professionisti/${practitionerId}/servizi/${serviceId}`);
  const slots = page.getByRole("group", { name: "Orari liberi", exact: true });
  await slots.getByRole("button").first().click();
  await expect(page.getByRole("heading", { name: "Riepilogo" })).toBeVisible();
}

test("un cliente nuovo accede con il codice, dà nome e cognome, torna dove era ed esce", async ({ page }) => {
  // a page for customers only sends to the sign-in, and back there afterwards
  await page.goto("/prenotazioni");
  await expect(page).toHaveURL(/\/accedi\?torna=%2Fprenotazioni/);
  await expect(page.getByText("Niente password")).toBeVisible();

  await signInThroughThePages(page, email("luca"), { firstName: "Luca", lastName: "Verdi" });

  await expect(page).toHaveURL(`${FRONTEND_URL}/prenotazioni`);
  await expect(page.getByRole("heading", { name: "Le mie sedute" })).toBeVisible();
  await expect(page.getByText("Nessuna seduta in programma")).toBeVisible();

  await page.getByRole("navigation").last().getByRole("link", { name: "Profilo" }).click();
  await expect(page.getByText(email("luca"))).toBeVisible();
  await expect(page.getByLabel("Nome", { exact: true })).toHaveValue("Luca");
  await page.getByRole("button", { name: "Esci" }).click();
  await expect(page).toHaveURL(`${FRONTEND_URL}/`);

  await page.goto("/crediti");
  await expect(page).toHaveURL(/\/accedi\?torna=%2Fcrediti/);
});

// found by Nicholas on 2026-11-12: the admin on the customer's site had no way out, "Esci" was only in the profile
test("l'admin sul sito dei clienti legge che non è la sua sezione, e può uscire", async ({ page }) => {
  await page.goto("/profilo");
  await signInThroughThePages(page, "admin@e2e.local");

  await expect(page).toHaveURL(`${FRONTEND_URL}/profilo`);
  await expect(page.getByText("Questa sezione è per i clienti")).toBeVisible();
  await page.getByRole("button", { name: "Esci" }).click();
  await expect(page).toHaveURL(`${FRONTEND_URL}/`);

  await page.goto("/profilo");
  await expect(page).toHaveURL(/\/accedi\?torna=%2Fprofilo/);
});

test("il sito dice prima che con Anna non si paga online, e mostra i crediti di Maria", async () => {
  const { page } = maria;
  await page.goto("/");
  await page.getByRole("link", { name: /Anna Rossi/ }).click();
  await expect(page.getByText("non si può pagare online").first()).toBeVisible();
  await expect(page.getByText("I tuoi crediti con Anna")).toBeVisible();
  await expect(page.getByText(/3 crediti per Seduta posturale/)).toBeVisible();
});

test("prenotazione con un credito, poi cancellazione in tempo: la frase prima e il credito che torna dopo", async () => {
  const { page } = maria;
  await chooseTheFirstFreeSlot(page);
  await page.getByRole("button", { name: "Prenota" }).click();

  await expect(page).toHaveURL(/\/prenotazioni\/\d+\?nuova=1/);
  await expect(page.getByText("Seduta confermata: hai usato un tuo credito.")).toBeVisible();
  await expect(page.getByText("Confermata", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Cancella la seduta" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("Sei in tempo.")).toBeVisible();
  await expect(dialog.getByText("Il credito che avevi usato ti torna disponibile")).toBeVisible();
  await dialog.getByRole("button", { name: "Sì, cancella" }).click();

  await expect(page.getByText(/Seduta cancellata in tempo\. Hai un credito valido fino al \d+ \w+ \d{4}\./)).toBeVisible();
  await expect(page.getByText("Cancellata da te", { exact: true })).toBeVisible();
});

test("cancellazione tardiva: prima di confermare Maria legge che la seduta è persa", async () => {
  // a notice longer than any booking ahead: every new booking is already past its free cancellation
  await anna.call("PUT", "/api/panel/settings", settings({ cancellationNoticeHours: 2000 }));
  try {
    const { page } = maria;
    await chooseTheFirstFreeSlot(page);
    await page.getByRole("button", { name: "Prenota" }).click();
    await expect(page.getByText("Seduta confermata: hai usato un tuo credito.")).toBeVisible();

    await page.getByRole("button", { name: "Cancella la seduta" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByText("Fuori tempo.")).toBeVisible();
    await expect(dialog.getByText(/Se cancelli ora la seduta è persa e non ricevi nessun credito/)).toBeVisible();
    await dialog.getByRole("button", { name: "Sì, cancella" }).click();

    await expect(page.getByText("Seduta cancellata in ritardo: non ricevi nessun credito.")).toBeVisible();
    await expect(page.getByText("Cancellata da te in ritardo")).toBeVisible();
  } finally {
    await anna.call("PUT", "/api/panel/settings", settings());
  }
});

test("con l'approvazione manuale la richiesta resta in attesa, e Maria la ritira", async () => {
  await anna.call("PUT", "/api/panel/settings", settings({ bookingApproval: "MANUAL" }));
  try {
    const { page } = maria;
    await chooseTheFirstFreeSlot(page);
    await expect(page.getByText(/approvata dal professionista entro 12 ore/)).toBeVisible();
    await page.getByRole("button", { name: "Prenota" }).click();

    await expect(page.getByText(/Richiesta inviata: hai usato un tuo credito\. La risposta arriva entro/)).toBeVisible();
    await expect(page.getByText("In attesa di approvazione")).toBeVisible();
    await page.getByRole("button", { name: "Ritira la richiesta" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Sì, ritira" }).click();

    await expect(page.getByText("Richiesta ritirata. Il credito che avevi usato è di nuovo disponibile.")).toBeVisible();
    await expect(page.getByText("Ritirata da te")).toBeVisible();
  } finally {
    await anna.call("PUT", "/api/panel/settings", settings());
  }
});

test("orario preso nel frattempo: la frase chiara, e un altro orario da scegliere", async () => {
  const { page } = maria;
  await chooseTheFirstFreeSlot(page);

  // meanwhile Anna gives that same time to another customer from the panel
  // the same question the page asked: the first free slot is the one Maria has chosen
  const to = new Date(Date.now() + 40 * 86_400_000).toISOString().slice(0, 10);
  const slots = await anna.call<Schemas["PublicSlot"][]>(
    "GET",
    `/api/public/practitioners/${practitionerId}/services/${serviceId}/slots?from=${todayInRome()}&to=${to}`,
  );
  const first = slots[0];
  expect(first).toBeDefined();
  await anna.call("POST", "/api/panel/bookings", {
    customer: { email: email("pietro"), firstName: "Pietro", lastName: "Neri" },
    serviceId,
    startsAt: first?.start,
  });

  await page.getByRole("button", { name: "Prenota" }).click();
  await expect(page.getByText("Questo orario non è più disponibile. Scegline un altro.")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Riepilogo" })).toHaveCount(0);
  await expect(page.getByRole("group", { name: "Orari liberi", exact: true }).getByRole("button").first()).toBeVisible();
});

test("un codice d'errore sconosciuto diventa una frase generica, mai un testo tecnico", async () => {
  const { page } = maria;
  await page.route("**/api/customer/bookings", async (route) => {
    if (route.request().method() !== "POST") {
      return route.fallback();
    }
    return route.fulfill({
      status: 409,
      contentType: "application/problem+json",
      body: JSON.stringify({ status: 409, code: "A_CODE_FROM_THE_FUTURE", detail: "Constraint booking_xyz violated" }),
    });
  });
  try {
    await chooseTheFirstFreeSlot(page);
    await page.getByRole("button", { name: "Prenota" }).click();
    await expect(page.getByText("Qualcosa non ha funzionato. Riprova tra poco.")).toBeVisible();
    await expect(page.getByText(/A_CODE_FROM_THE_FUTURE|Constraint|409/)).toHaveCount(0);
  } finally {
    await page.unroute("**/api/customer/bookings");
  }
});

test("ogni pagina sta nello schermo del telefono", async ({ browser }) => {
  const { page } = maria;
  const shots = test.info().outputPath();
  const visit = async (path: string, name: string, ready: string | RegExp) => {
    await page.goto(path);
    await expect(page.locator("main").getByText(ready).first()).toBeVisible();
    await expectFitsTheScreen(page);
    await page.screenshot({ path: `${shots}/${name}.png`, fullPage: true });
  };

  await visit("/", "01-professionisti", "Anna Rossi");
  await visit(`/professionisti/${practitionerId}`, "02-professionista", "Servizi");
  await visit(`/professionisti/${practitionerId}/servizi/${serviceId}`, "03-orari", "Scegli il giorno");
  await page.getByRole("group", { name: "Orari liberi", exact: true }).getByRole("button").first().click();
  await expectFitsTheScreen(page);
  await page.screenshot({ path: `${shots}/04-riepilogo.png`, fullPage: true });
  await visit("/prenotazioni", "05-sedute", "Le mie sedute");
  await page.getByRole("tab", { name: "Passate" }).click();
  await expectFitsTheScreen(page);
  // one of Maria's sessions, asked through the site with her own session
  const mine = (await (await page.request.get("/api/customer/bookings")).json()) as Schemas["CustomerBookingView"][];
  expect(mine.length).toBeGreaterThan(0);
  await visit(`/prenotazioni/${mine[0]?.id}`, "06-seduta", "La tua seduta");
  await visit("/crediti", "07-crediti", "I tuoi crediti");
  await page.getByRole("link", { name: /Seduta posturale/ }).first().click();
  await expect(page.getByRole("heading", { name: "Movimenti" })).toBeVisible();
  await expectFitsTheScreen(page);
  await page.screenshot({ path: `${shots}/08-storia-credito.png`, fullPage: true });
  await visit("/profilo", "09-profilo", "Accesso con SMS");

  // the pages of whoever is not signed in
  const anonymous = await phone(browser);
  await anonymous.page.goto("/accedi");
  await expect(anonymous.page.getByText("Niente password")).toBeVisible();
  await expectFitsTheScreen(anonymous.page);
  await anonymous.page.screenshot({ path: `${shots}/10-accedi.png`, fullPage: true });
  await anonymous.context.close();
});
