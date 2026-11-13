# Le mie sedute a calendario — piano di lavoro

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In "Le mie sedute" (`/prenotazioni`) due viste a calendario delle proprie sedute, settimana (giorni × ore) e mese (griglia classica), accanto all'elenco di oggi. Disegno: `docs/superpowers/specs/2026-11-13-sedute-calendario-design.md`.

**Architecture:** Nessuna modifica al backend: le griglie leggono `GET /api/customer/bookings` (tutte le sedute) e le dispongono per giorno e ora di **Roma**. I conti di calendario sono funzioni pure in `src/lib/calendar.ts`, provate da sole con il runner di Playwright senza server; le griglie (`WeekGrid`, `MonthGrid`) solo disegnano; `BookingsClient` legge vista e periodo dalla query string (`?vista=settimana&da=…`), carica e filtra.

**Tech Stack:** Next.js 16 (App Router, `next/link`, `useSearchParams`), React 19, TypeScript 5.9 rigoroso, CSS grid con i token di `tokens.css`, `Intl.DateTimeFormat` con `timeZone: "Europe/Rome"`, Playwright 1.65 (unitari e end-to-end).

**Convenzioni del progetto da rispettare:** codice e commenti in inglese, interfaccia in italiano; nessuna regola di business nel frontend (D-005); solo colori da `tokens.css`; commit Conventional Commits con `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` dopo la suite verde. Tutti i comandi si lanciano da `frontend/`. Il messaggio di commit si scrive in un file e si passa con `git commit -F <file>` (le virgolette nel messaggio rompono `-m` in PowerShell).

---

## Mappa dei file

| File | Responsabilità |
|---|---|
| `frontend/playwright.unit.config.ts` (nuovo) | Runner per le funzioni pure: `testDir: "src"`, nessun server, processo nel fuso di New York |
| `frontend/package.json` | Script `test:unit` |
| `frontend/tsconfig.json` | Include `playwright.unit.config.ts` |
| `frontend/src/lib/calendar.ts` (nuovo) | Conti di calendario: giorni, settimane, mesi, orologio di Roma, corsie, titoli |
| `frontend/src/lib/calendar.test.ts` (nuovo) | Le prove dei conti |
| `frontend/src/lib/format.ts` | Due formati in più per i giorni di calendario: "gio" e "Giovedì 13 novembre" |
| `frontend/src/lib/labels.ts` | `SHOWN_IN_CALENDAR`: quali stati disegnano le griglie |
| `frontend/src/components/calendar/PeriodNav.tsx` (nuovo) | Riga `titolo · Oggi · ‹ ›` con i link |
| `frontend/src/components/calendar/WeekGrid.tsx` (nuovo) | La griglia settimanale |
| `frontend/src/components/calendar/MonthGrid.tsx` (nuovo) | La griglia mensile e l'elenco del giorno scelto |
| `frontend/src/app/prenotazioni/BookingsClient.tsx` | Le quattro schede, la query string, il caricamento, i filtri, le due viste |
| `frontend/src/app/globals.css` | Stili di schede-link, navigazione, settimana, mese |
| `frontend/e2e/support.ts` | `dayInRome(instant)` |
| `frontend/e2e/customer.spec.ts` | Il test nuovo; il test "ogni pagina" visita le due viste |
| `docs/decisioni.md`, `docs/passi/12-frontend-cliente.md`, `docs/stato.md` | D-087, la sezione "Dopo il passo", la riga di stato |

Fatti di calendario usati nelle prove (verificati): il 9 novembre 2026 è lunedì, il 13 venerdì, il 1° novembre 2026 domenica, il 1° giugno 2026 lunedì, il 29 dicembre 2025 lunedì, il 1° febbraio 2027 lunedì (febbraio 2027 = 4 settimane esatte), il 1° febbraio 2028 martedì (bisestile, 29 giorni), il 1° gennaio 2024 lunedì. L'ora legale 2026 inizia domenica 29 marzo alle 01:00 UTC e finisce domenica 25 ottobre alle 01:00 UTC.

---

### Task 1: il runner unitario e i giorni della settimana

**Files:**
- Create: `frontend/playwright.unit.config.ts`
- Modify: `frontend/package.json`, `frontend/tsconfig.json`
- Create: `frontend/src/lib/calendar.ts`
- Test: `frontend/src/lib/calendar.test.ts`

- [ ] **Step 1: la configurazione del runner**

`frontend/playwright.unit.config.ts`:

```ts
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
```

In `frontend/package.json`, dopo `"e2e": "playwright test",`:

```json
    "test:unit": "playwright test -c playwright.unit.config.ts",
```

In `frontend/tsconfig.json`, in `include`, dopo `"playwright.config.ts",`:

```json
    "playwright.unit.config.ts",
```

- [ ] **Step 2: il primo test, che fallisce**

`frontend/src/lib/calendar.test.ts`:

```ts
// The calendar maths (D-087). The process runs in New York's zone (playwright.unit.config.ts): nothing here may
// depend on the clock of the computer.
import { expect, test } from "@playwright/test";
import { isDay, mondayOf, weekOf, weekdayOf } from "./calendar";

test("a valid day has the shape and exists", () => {
  expect(isDay("2026-11-13")).toBe(true);
  expect(isDay("2028-02-29")).toBe(true);
  expect(isDay("2026-13-01")).toBe(false);
  expect(isDay("2026-02-30")).toBe(false);
  expect(isDay("2026-1-3")).toBe(false);
  expect(isDay("ieri")).toBe(false);
  expect(isDay("")).toBe(false);
  expect(isDay(null)).toBe(false);
  expect(isDay(undefined)).toBe(false);
});

test("Monday is 0 and Sunday is 6", () => {
  expect(weekdayOf("2026-11-09")).toBe(0);
  expect(weekdayOf("2026-11-13")).toBe(4);
  expect(weekdayOf("2026-11-15")).toBe(6);
});

test("every day of a week has the same Monday", () => {
  for (const day of ["2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13", "2026-11-14", "2026-11-15"]) {
    expect(mondayOf(day)).toBe("2026-11-09");
  }
  // across a month and across a year
  expect(mondayOf("2026-11-01")).toBe("2026-10-26");
  expect(mondayOf("2026-01-01")).toBe("2025-12-29");
});

test("the seven days of a week", () => {
  expect(weekOf("2026-10-26")).toEqual(["2026-10-26", "2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30", "2026-10-31", "2026-11-01"]);
});
```

- [ ] **Step 3: vedere il test fallire**

Run: `npm run test:unit`
Expected: errore di compilazione/import, `Cannot find module './calendar'` (o simile).

- [ ] **Step 4: il modulo, con le prime funzioni**

`frontend/src/lib/calendar.ts`:

```ts
// The calendar maths of the week and month views of "my sessions" (D-087). Pure functions, no React: the grids draw
// only what these compute. A day is a string "2026-11-13", a day of the calendar in Rome (format.ts, D-069), never a
// Date in the zone of the phone; a clock time is minutes since midnight on Rome's clock.
import { addDays } from "./format";

/** A day of the calendar, "2026-11-13". */
export type Day = string;

const DAY_SHAPE = /^\d{4}-\d{2}-\d{2}$/;

function partsOf(day: Day): [number, number, number] {
  return day.split("-").map(Number) as [number, number, number];
}

/** The day at midnight UTC: a Date used only to count days and weekdays, never to read a clock. */
function utcOf(day: Day): Date {
  const [year, month, date] = partsOf(day);
  return new Date(Date.UTC(year, month - 1, date));
}

/** A valid day: the shape, and a date that exists ("2026-13-01" and "2026-02-30" do not). */
export function isDay(text: string | null | undefined): text is Day {
  if (text == null || !DAY_SHAPE.test(text)) {
    return false;
  }
  const [year, month, date] = partsOf(text);
  const probe = utcOf(text);
  return probe.getUTCFullYear() === year && probe.getUTCMonth() === month - 1 && probe.getUTCDate() === date;
}

/** 0 for Monday … 6 for Sunday. */
export function weekdayOf(day: Day): number {
  return (utcOf(day).getUTCDay() + 6) % 7;
}

/** The Monday of the week of a day (the day itself on a Monday). */
export function mondayOf(day: Day): Day {
  return addDays(day, -weekdayOf(day));
}

/** The seven days from a Monday. */
export function weekOf(monday: Day): Day[] {
  return Array.from({ length: 7 }, (_, index) => addDays(monday, index));
}
```

- [ ] **Step 5: vedere il test passare**

Run: `npm run test:unit`
Expected: `4 passed`.

Run: `npm run typecheck`
Expected: nessun errore.

- [ ] **Step 6: commit**

Messaggio (file `commit-msg.txt` nello scratchpad, poi `git commit -F`):

```
test(frontend): a runner for the pure functions, and the days of a week

The calendar maths of the week and month views (D-087) start here: the
Playwright runner without server, in another zone on purpose.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

```powershell
git add frontend/playwright.unit.config.ts frontend/package.json frontend/tsconfig.json frontend/src/lib/calendar.ts frontend/src/lib/calendar.test.ts
git commit -q -F <file>
```

---

### Task 2: i mesi

**Files:**
- Modify: `frontend/src/lib/calendar.ts`
- Test: `frontend/src/lib/calendar.test.ts`

- [ ] **Step 1: i test, che falliscono**

Aggiungere a `calendar.test.ts` (e all'import: `addMonths, firstOfMonth, monthGridOf`):

```ts
test("the first of a month, and the months before and after", () => {
  expect(firstOfMonth("2026-11-13")).toBe("2026-11-01");
  expect(addMonths("2026-11-01", 1)).toBe("2026-12-01");
  expect(addMonths("2026-12-01", 1)).toBe("2027-01-01");
  expect(addMonths("2026-01-01", -1)).toBe("2025-12-01");
});

test("the grid of a month: whole weeks from Monday, as many as touch the month", () => {
  // November 2026 starts on a Sunday and ends on a Monday: six weeks
  const november = monthGridOf("2026-11-01");
  expect(november).toHaveLength(42);
  expect(november[0]).toBe("2026-10-26");
  expect(november[41]).toBe("2026-12-06");
  // June 2026 starts on a Monday: five weeks
  const june = monthGridOf("2026-06-01");
  expect(june).toHaveLength(35);
  expect(june[0]).toBe("2026-06-01");
  expect(june[34]).toBe("2026-07-05");
  // February 2027 starts on a Monday and has 28 days: four weeks exactly
  expect(monthGridOf("2027-02-01")).toHaveLength(28);
  // February 2028, leap year
  const leap = monthGridOf("2028-02-01");
  expect(leap).toHaveLength(35);
  expect(leap[0]).toBe("2028-01-31");
  expect(leap[34]).toBe("2028-03-05");
});
```

- [ ] **Step 2: vedere i test fallire**

Run: `npm run test:unit`
Expected: fallisce per import mancanti (`firstOfMonth` non esportato).

- [ ] **Step 3: le funzioni**

Aggiungere a `calendar.ts` dopo `weekOf`:

```ts
/** "2026-11-13" → "2026-11-01" */
export function firstOfMonth(day: Day): Day {
  return `${day.slice(0, 7)}-01`;
}

/** The first of the month n months after (n < 0: before) the first of a month. */
export function addMonths(first: Day, n: number): Day {
  const [year, month] = partsOf(first);
  return new Date(Date.UTC(year, month - 1 + n, 1)).toISOString().slice(0, 10);
}

/** The 28, 35 or 42 days of a month's grid: whole weeks from Monday, every week that touches the month. */
export function monthGridOf(first: Day): Day[] {
  const start = mondayOf(first);
  const last = addDays(addMonths(first, 1), -1);
  const end = addDays(mondayOf(last), 6);
  const count = Math.round((utcOf(end).getTime() - utcOf(start).getTime()) / 86_400_000) + 1;
  return Array.from({ length: count }, (_, index) => addDays(start, index));
}
```

- [ ] **Step 4: vedere i test passare**

Run: `npm run test:unit`
Expected: `6 passed`.

- [ ] **Step 5: commit**

```
feat(frontend): the grid of a month, whole weeks from Monday

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

### Task 3: l'orologio di Roma e le ore mostrate

**Files:**
- Modify: `frontend/src/lib/calendar.ts`
- Test: `frontend/src/lib/calendar.test.ts`

- [ ] **Step 1: i test, che falliscono**

Aggiungere (import: `hourRange, minutesBetween, romeClock, romeMinutes`):

```ts
test("the clock of Rome, whatever the zone of the process", () => {
  // the process is in New York (playwright.unit.config.ts): 10:00 in Rome is 04:00 there
  expect(romeClock("2026-11-16T10:00:00+01:00")).toEqual({ hour: 10, minute: 0 });
  expect(romeClock("2026-11-16T09:30:00Z")).toEqual({ hour: 10, minute: 30 });
  expect(romeMinutes("2026-11-16T10:30:00+01:00")).toBe(630);
  expect(romeMinutes("2026-11-16T23:00:00Z")).toBe(0); // midnight in Rome: 0, never 24:00
});

test("the clock of Rome across the changes of the hour", () => {
  // summer time begins on 2026-03-29 at 01:00 UTC: 09:00 UTC is 10:00 the day before, 11:00 from that day
  expect(romeClock("2026-03-28T09:00:00Z")).toEqual({ hour: 10, minute: 0 });
  expect(romeClock("2026-03-29T09:00:00Z")).toEqual({ hour: 11, minute: 0 });
  expect(romeClock("2026-03-29T01:30:00Z")).toEqual({ hour: 3, minute: 30 });
  // and ends on 2026-10-25 at 01:00 UTC
  expect(romeClock("2026-10-24T09:00:00Z")).toEqual({ hour: 11, minute: 0 });
  expect(romeClock("2026-10-25T09:00:00Z")).toEqual({ hour: 10, minute: 0 });
});

test("the minutes between two instants", () => {
  expect(minutesBetween("2026-11-16T10:00:00+01:00", "2026-11-16T11:00:00+01:00")).toBe(60);
  expect(minutesBetween("2026-11-16T10:00:00+01:00", "2026-11-16T10:30:00+01:00")).toBe(30);
});

test("the hours of the week grid: at least 9–19, wider when a session is outside", () => {
  expect(hourRange([])).toEqual({ from: 9, to: 19 });
  expect(hourRange([{ start: 600, end: 660 }])).toEqual({ from: 9, to: 19 });
  expect(hourRange([{ start: 510, end: 570 }])).toEqual({ from: 8, to: 19 }); // 8:30–9:30
  expect(hourRange([{ start: 1110, end: 1170 }])).toEqual({ from: 9, to: 20 }); // 18:30–19:30
  expect(hourRange([{ start: 1380, end: 1440 }])).toEqual({ from: 9, to: 24 }); // never past midnight
});
```

- [ ] **Step 2: vedere i test fallire**

Run: `npm run test:unit`
Expected: import mancanti.

- [ ] **Step 3: le funzioni**

In `calendar.ts` cambiare l'import in `import { STUDIO_ZONE, addDays } from "./format";` e aggiungere in fondo:

```ts
/** Something that occupies minutes of a day, from start to end (minutes since midnight). */
export type Interval = { start: number; end: number };

// en-GB with h23 writes "09:05" and never "24:00"
const clock = new Intl.DateTimeFormat("en-GB", { timeZone: STUDIO_ZONE, hour: "2-digit", minute: "2-digit", hourCycle: "h23" });

/** Hour and minute of an instant on Rome's clock, whatever the zone of the phone. */
export function romeClock(instant: string): { hour: number; minute: number } {
  const [hour, minute] = clock.format(new Date(instant)).split(":").map(Number) as [number, number];
  return { hour, minute };
}

/** Minutes since midnight on Rome's clock: 10:30 → 630. */
export function romeMinutes(instant: string): number {
  const { hour, minute } = romeClock(instant);
  return hour * 60 + minute;
}

/** The minutes from one instant to another (elapsed time). */
export function minutesBetween(start: string, end: string): number {
  return Math.round((new Date(end).getTime() - new Date(start).getTime()) / 60_000);
}

/** The hours the week grid shows: from the first session to the last, at least 9–19 (an extent of the drawing, not a rule). */
export function hourRange(intervals: readonly Interval[]): { from: number; to: number } {
  let from = 9;
  let to = 19;
  for (const interval of intervals) {
    from = Math.min(from, Math.floor(interval.start / 60));
    to = Math.max(to, Math.ceil(interval.end / 60));
  }
  return { from: Math.max(from, 0), to: Math.min(to, 24) };
}
```

- [ ] **Step 4: vedere i test passare**

Run: `npm run test:unit`
Expected: `10 passed`.

- [ ] **Step 5: commit**

```
feat(frontend): the clock of Rome for the calendar, and the hours a week shows

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

### Task 4: le corsie per le sovrapposizioni

**Files:**
- Modify: `frontend/src/lib/calendar.ts`
- Test: `frontend/src/lib/calendar.test.ts`

- [ ] **Step 1: i test, che falliscono**

Aggiungere (import: `lanesOf`):

```ts
test("lanes: sessions that overlap stand side by side, the others take the whole width", () => {
  expect(lanesOf([])).toEqual([]);

  const alone = lanesOf([{ start: 600, end: 660 }]);
  expect(alone).toEqual([{ item: { start: 600, end: 660 }, lane: 0, lanes: 1 }]);

  // one after the other, touching: no overlap
  const touching = lanesOf([{ start: 660, end: 720 }, { start: 600, end: 660 }]);
  expect(touching.map((entry) => [entry.item.start, entry.lane, entry.lanes])).toEqual([[600, 0, 1], [660, 0, 1]]);

  // two at the same time
  const twins = lanesOf([{ start: 600, end: 660, id: "a" }, { start: 600, end: 660, id: "b" }]);
  expect(twins.map((entry) => [entry.item.id, entry.lane, entry.lanes])).toEqual([["a", 0, 2], ["b", 1, 2]]);

  // a long one with two short ones in a row beside it: the two short ones share the second lane
  const chain = lanesOf([{ start: 600, end: 720, id: "long" }, { start: 630, end: 690, id: "first" }, { start: 690, end: 750, id: "second" }]);
  expect(chain.map((entry) => [entry.item.id, entry.lane, entry.lanes])).toEqual([["long", 0, 2], ["first", 1, 2], ["second", 1, 2]]);

  // a group of two, then one alone later in the day
  const groups = lanesOf([{ start: 600, end: 660 }, { start: 600, end: 660 }, { start: 900, end: 960 }]);
  expect(groups.map((entry) => entry.lanes)).toEqual([2, 2, 1]);
});
```

- [ ] **Step 2: vedere il test fallire**

Run: `npm run test:unit`
Expected: `lanesOf` non esportato.

- [ ] **Step 3: la funzione**

Aggiungere a `calendar.ts`:

```ts
/** An interval with its lane (the first free one) and how many lanes its group of overlapping intervals needs. */
export type Laned<T extends Interval> = { item: T; lane: number; lanes: number };

/** The intervals of one day, sorted, side by side when they overlap: the width of a column is shared by `lanes`. */
export function lanesOf<T extends Interval>(intervals: readonly T[]): Laned<T>[] {
  const sorted = [...intervals].sort((a, b) => a.start - b.start || a.end - b.end);
  const placed: Laned<T>[] = [];
  let group: Laned<T>[] = [];
  let laneEnds: number[] = []; // for each lane of the current group, when its last interval ends
  let groupEnd = -Infinity;
  const closeGroup = () => {
    for (const entry of group) {
      entry.lanes = laneEnds.length;
    }
    group = [];
    laneEnds = [];
  };
  for (const item of sorted) {
    if (item.start >= groupEnd) {
      // nothing placed so far reaches this one: a new group
      closeGroup();
    }
    let lane = laneEnds.findIndex((end) => end <= item.start);
    if (lane === -1) {
      lane = laneEnds.length;
      laneEnds.push(item.end);
    } else {
      laneEnds[lane] = item.end;
    }
    const entry: Laned<T> = { item, lane, lanes: 0 };
    group.push(entry);
    placed.push(entry);
    groupEnd = Math.max(groupEnd, item.end);
  }
  closeGroup();
  return placed;
}
```

- [ ] **Step 4: vedere il test passare**

Run: `npm run test:unit`
Expected: `11 passed`.

- [ ] **Step 5: commit**

```
feat(frontend): lanes for the sessions that overlap in a day

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

### Task 5: i titoli e i formati dei giorni di calendario

**Files:**
- Modify: `frontend/src/lib/calendar.ts`, `frontend/src/lib/format.ts`
- Test: `frontend/src/lib/calendar.test.ts`

- [ ] **Step 1: i test, che falliscono**

Aggiungere (import da `./calendar`: `monthTitle, weekTitle`; nuovo import `import { formatDayWithWeekday, formatWeekday } from "./format";`):

```ts
test("the titles of a week and of a month", () => {
  expect(weekTitle("2026-11-09")).toBe("9 – 15 novembre 2026");
  expect(weekTitle("2026-10-26")).toBe("26 ottobre – 1 novembre 2026");
  expect(weekTitle("2025-12-29")).toBe("29 dicembre 2025 – 4 gennaio 2026");
  expect(monthTitle("2026-11-01")).toBe("Novembre 2026");
  expect(monthTitle("2027-01-01")).toBe("Gennaio 2027");
});

test("a day of the calendar in words, whatever the zone of the process", () => {
  expect(formatWeekday("2026-11-09")).toBe("lun");
  expect(formatWeekday("2026-11-15")).toBe("dom");
  expect(formatDayWithWeekday("2026-11-13")).toBe("Venerdì 13 novembre");
  expect(formatDayWithWeekday("2026-11-01")).toBe("Domenica 1 novembre");
});
```

- [ ] **Step 2: vedere il test fallire**

Run: `npm run test:unit`
Expected: export mancanti.

- [ ] **Step 3: in `format.ts`**

Sostituire il blocco di `formatCalendarDay` (dalla riga `const calendarDay = …` alla fine della funzione) con:

```ts
const calendarDay = new Intl.DateTimeFormat("it-IT", { timeZone: "UTC", day: "numeric", month: "long", year: "numeric" });
const calendarWeekday = new Intl.DateTimeFormat("it-IT", { timeZone: "UTC", weekday: "short" });
const calendarDayLong = new Intl.DateTimeFormat("it-IT", { timeZone: "UTC", weekday: "long", day: "numeric", month: "long" });

/** A day of the calendar at midnight UTC, formatted in UTC: the words of that day, whatever the zone of the phone. */
function utcOf(day: string): Date {
  const [year, month, date] = day.split("-").map(Number) as [number, number, number];
  return new Date(Date.UTC(year, month - 1, date));
}

/** A day of the calendar as it is: "2027-02-09" → "9 febbraio 2027", whatever the zone of the phone. */
export function formatCalendarDay(day: string): string {
  return calendarDay.format(utcOf(day));
}

/** "2026-11-09" → "lun" */
export function formatWeekday(day: string): string {
  return calendarWeekday.format(utcOf(day));
}

/** "2026-11-13" → "Venerdì 13 novembre" */
export function formatDayWithWeekday(day: string): string {
  return capitalize(calendarDayLong.format(utcOf(day)));
}
```

- [ ] **Step 4: in `calendar.ts`**

Aggiungere in fondo:

```ts
const monthName = new Intl.DateTimeFormat("it-IT", { timeZone: "UTC", month: "long" });
const monthAndYear = new Intl.DateTimeFormat("it-IT", { timeZone: "UTC", month: "long", year: "numeric" });

/** "9 – 15 novembre 2026"; across months "26 ottobre – 1 novembre 2026"; across years "29 dicembre 2025 – 4 gennaio 2026". */
export function weekTitle(monday: Day): string {
  const sunday = addDays(monday, 6);
  const [fromYear, fromMonth, fromDate] = partsOf(monday);
  const [toYear, toMonth, toDate] = partsOf(sunday);
  const to = `${toDate} ${monthName.format(utcOf(sunday))} ${toYear}`;
  if (fromYear !== toYear) {
    return `${fromDate} ${monthName.format(utcOf(monday))} ${fromYear} – ${to}`;
  }
  if (fromMonth !== toMonth) {
    return `${fromDate} ${monthName.format(utcOf(monday))} – ${to}`;
  }
  return `${fromDate} – ${to}`;
}

/** "Novembre 2026" */
export function monthTitle(first: Day): string {
  const text = monthAndYear.format(utcOf(first));
  return text.charAt(0).toUpperCase() + text.slice(1);
}
```

- [ ] **Step 5: vedere i test passare**

Run: `npm run test:unit`
Expected: `13 passed`.

Run: `npm run typecheck`
Expected: nessun errore.

- [ ] **Step 6: commit**

```
feat(frontend): the titles of a week and a month, and a day of the calendar in words

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

### Task 6: le quattro schede e la query string in "Le mie sedute"

Le griglie non ci sono ancora: in questo passo la pagina legge `vista`, `da` e `giorno`, mostra le quattro schede come link e, per settimana e mese, carica tutte le sedute e mostra un testo provvisorio. L'elenco resta identico.

**Files:**
- Modify: `frontend/src/lib/labels.ts`
- Modify: `frontend/src/app/prenotazioni/BookingsClient.tsx`
- Modify: `frontend/src/app/globals.css` (`.tab`)

- [ ] **Step 1: gli stati che le griglie disegnano**

In `labels.ts`, dopo `BOOKING_STATUS`:

```ts
// Which states the week and month views draw (D-087): a session that is no longer an appointment is not an entry in a
// calendar. What a view shows, not a rule: the states are the backend's, and the list shows them all. Record<...>:
// a new state in the contract must be decided here
export const SHOWN_IN_CALENDAR: Record<BookingStatus, boolean> = {
  PENDING_PAYMENT: true,
  PENDING_APPROVAL: true,
  CONFIRMED: true,
  COMPLETED: true,
  NO_SHOW: true,
  CANCELLED_IN_TIME: false,
  CANCELLED_LATE: false,
  CANCELLED_BY_STUDIO: false,
  EXPIRED: false,
  REJECTED: false,
  APPROVAL_EXPIRED: false,
  WITHDRAWN: false,
};
```

- [ ] **Step 2: `BookingsClient.tsx` riscritto**

```tsx
"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { BookingCard } from "@/components/BookingCard";
import { ErrorNotice, Loading } from "@/components/Notice";
import { firstOfMonth, isDay, mondayOf, type Day } from "@/lib/calendar";
import { useDirectory } from "@/lib/directory";
import { todayInRome } from "@/lib/format";
import { SHOWN_IN_CALENDAR } from "@/lib/labels";

type Booking = Schemas["CustomerBookingView"];
type Period = "upcoming" | "past";
type View = "prossime" | "passate" | "settimana" | "mese";

const VIEWS: Record<View, string> = { prossime: "Prossime", passate: "Passate", settimana: "Settimana", mese: "Mese" };

function viewOf(text: string | null): View {
  return text !== null && text in VIEWS ? (text as View) : "prossime";
}

// "My sessions": the list (not over yet, the soonest first; or over, the latest first: the split is the backend's,
// D-082) and the week and month views (D-087). The view and the period live in the query string: reloading, coming
// back from a session's page or saving the address keeps them
export function BookingsClient() {
  const query = useSearchParams();
  const view = viewOf(query.get("vista"));
  const today = todayInRome();
  const from = query.get("da");
  const anchor: Day = isDay(from) ? from : today;
  const isCalendar = view === "settimana" || view === "mese";

  // every session once, for the grids: read the first time a grid is shown, kept while the page is open
  const [all, setAll] = useState<Booking[] | null>(null);
  const [allError, setAllError] = useState<unknown>(null);
  useEffect(() => {
    if (!isCalendar || all !== null) {
      return undefined;
    }
    let current = true;
    unwrap(api.GET("/api/customer/bookings")).then(
      (found) => current && setAll(found),
      (failure: unknown) => current && setAllError(failure),
    );
    return () => {
      current = false;
    };
  }, [isCalendar, all]);
  const shown = useMemo(() => (all ?? []).filter((booking) => SHOWN_IN_CALENDAR[booking.status]), [all]);
  const directory = useDirectory(shown.map((booking) => booking.practitionerId));

  return (
    <>
      <h1>Le mie sedute</h1>
      <div className="tabs" role="tablist" aria-label="Vista">
        {(Object.keys(VIEWS) as View[]).map((one) => (
          <Link
            key={one}
            role="tab"
            aria-selected={one === view}
            className="tab"
            href={one === "prossime" ? "/prenotazioni" : `/prenotazioni?vista=${one}`}
          >
            {VIEWS[one]}
          </Link>
        ))}
      </div>

      {view === "prossime" || view === "passate" ? <BookingList period={view === "prossime" ? "upcoming" : "past"} /> : null}

      {isCalendar ? <ErrorNotice error={allError} /> : null}
      {isCalendar && allError === null && (all === null || !directory.ready) ? <Loading /> : null}
      {view === "settimana" && all !== null && directory.ready ? <p className="muted">Settimana di {mondayOf(anchor)}: {shown.length} sedute</p> : null}
      {view === "mese" && all !== null && directory.ready ? <p className="muted">Mese di {firstOfMonth(anchor)}: {shown.length} sedute</p> : null}
    </>
  );
}

// the list of before, unchanged (D-082)
function BookingList({ period }: { period: Period }) {
  const [bookings, setBookings] = useState<Booking[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const directory = useDirectory(bookings?.map((booking) => booking.practitionerId) ?? []);

  useEffect(() => {
    let current = true;
    setBookings(null);
    setError(null);
    unwrap(api.GET("/api/customer/bookings", { params: { query: { period } } })).then(
      (found) => current && setBookings(found),
      (failure: unknown) => current && setError(failure),
    );
    return () => {
      current = false;
    };
  }, [period]);

  const replace = (updated: Booking) =>
    setBookings((list) => list?.map((booking) => (booking.id === updated.id ? updated : booking)) ?? null);

  return (
    <>
      <ErrorNotice error={error} />
      {bookings === null && error === null ? <Loading /> : null}
      {bookings?.length === 0 ? (
        <p className="muted">
          {period === "upcoming" ? (
            <>
              Nessuna seduta in programma. <Link href="/">Prenotane una</Link>.
            </>
          ) : (
            "Nessuna seduta passata."
          )}
        </p>
      ) : null}
      {directory.ready || bookings?.length === 0
        ? bookings?.map((booking) => <BookingCard key={booking.id} booking={booking} directory={directory} onChange={replace} />)
        : bookings !== null && bookings.length > 0
          ? <Loading />
          : null}
    </>
  );
}
```

- [ ] **Step 3: una scheda può essere un link**

In `globals.css`, `.tab` diventa:

```css
.tab {
  min-height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--color-text-muted);
  font: inherit;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
}
```

- [ ] **Step 4: controllo**

Run: `npm run typecheck`
Expected: nessun errore.

Nel browser (frontend di sviluppo su 3000, entrati come cliente): `/prenotazioni` mostra le quattro schede, "Prossime" attiva in indaco; "Settimana" porta a `?vista=settimana` e mostra il testo provvisorio; "Passate" l'elenco delle passate.

- [ ] **Step 5: commit**

```
feat(frontend): four tabs in "my sessions", the view and the period in the address

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

### Task 7: la navigazione del periodo e la griglia della settimana

**Files:**
- Create: `frontend/src/components/calendar/PeriodNav.tsx`
- Create: `frontend/src/components/calendar/WeekGrid.tsx`
- Modify: `frontend/src/app/prenotazioni/BookingsClient.tsx`
- Modify: `frontend/src/app/globals.css`

- [ ] **Step 1: `PeriodNav.tsx`**

```tsx
import Link from "next/link";

const LABELS = {
  settimana: { previous: "Settimana precedente", next: "Settimana successiva" },
  mese: { previous: "Mese precedente", next: "Mese successivo" },
} as const;

// The title of the period shown, with "Oggi" (hidden when already there) and the two arrows: links, so that the
// browser's back button and a saved address work (D-087). Indigo: it is wayfinding, not an action (D-086)
export function PeriodNav({
  unit,
  title,
  previous,
  next,
  today,
}: {
  unit: keyof typeof LABELS;
  title: string;
  previous: string;
  next: string;
  today: string | null;
}) {
  return (
    <div className="period-nav">
      <h2 className="period-title">{title}</h2>
      {today !== null ? (
        <Link href={today} className="period-today">
          Oggi
        </Link>
      ) : null}
      <Link href={previous} className="period-arrow" aria-label={LABELS[unit].previous}>
        ‹
      </Link>
      <Link href={next} className="period-arrow" aria-label={LABELS[unit].next}>
        ›
      </Link>
    </div>
  );
}
```

- [ ] **Step 2: `WeekGrid.tsx`**

```tsx
import Link from "next/link";
import type { CSSProperties } from "react";
import type { Schemas } from "@/api/client";
import { hourRange, lanesOf, minutesBetween, romeMinutes, type Day } from "@/lib/calendar";
import type { Directory } from "@/lib/directory";
import { formatDayWithWeekday, formatTime, formatWeekday, romeDay } from "@/lib/format";
import { BOOKING_STATUS } from "@/lib/labels";

type Booking = Schemas["CustomerBookingView"];

const UNIT_MINUTES = 5; // one grid row
const ROW_PX = 4; // 48 px per hour
const HEAD_ROWS = 1; // the first grid row is the day names

// The week as the phone's calendar shows it (D-087): seven columns, one grid row per five minutes, every session a
// block from its start to its end on Rome's clock. Only drawing: which sessions, and of which days, is decided before
export function WeekGrid({ days, today, bookings, directory }: { days: Day[]; today: Day; bookings: Booking[]; directory: Directory }) {
  const placed = days.map((day) =>
    lanesOf(
      bookings
        .filter((booking) => romeDay(booking.startsAt) === day)
        .map((booking) => {
          const start = romeMinutes(booking.startsAt);
          // never past the bottom of its column (a session over midnight does not exist in the studio's hours)
          return { booking, start, end: Math.min(start + minutesBetween(booking.startsAt, booking.endsAt), 24 * 60) };
        }),
    ),
  );
  const range = hourRange(placed.flat().map((entry) => entry.item));
  const rows = ((range.to - range.from) * 60) / UNIT_MINUTES;
  const rowOf = (minutes: number) => HEAD_ROWS + 1 + Math.round((minutes - range.from * 60) / UNIT_MINUTES);
  const hours = Array.from({ length: range.to - range.from }, (_, index) => range.from + index);
  const todayIndex = days.indexOf(today);
  const now = todayIndex === -1 ? null : romeMinutes(new Date().toISOString());
  const style: CSSProperties = { gridTemplateRows: `auto repeat(${rows}, ${ROW_PX}px)` };

  return (
    <div className="week" style={style}>
      <div className="week-corner" aria-hidden="true" />
      {days.map((day, index) => (
        <div key={day} className={day === today ? "week-head is-today" : "week-head"} style={{ gridColumn: index + 2 }}>
          <span>{formatWeekday(day)}</span> <strong>{Number(day.slice(8))}</strong>
        </div>
      ))}
      {days.map((day, index) => (
        <div key={day} className={day === today ? "week-col is-today" : "week-col"} style={{ gridColumn: index + 2 }} aria-hidden="true" />
      ))}
      {hours.map((hour) => (
        <div key={hour} className="week-hour" style={{ gridRow: `${rowOf(hour * 60)} / span ${60 / UNIT_MINUTES}` }} aria-hidden="true">
          {hour}:00
        </div>
      ))}
      {days.map((day, index) =>
        (placed[index] ?? []).map(({ item, lane, lanes }) => {
          const { booking } = item;
          const status = BOOKING_STATUS[booking.status];
          const what = `${directory.serviceName(booking.practitionerId, booking.serviceId)} con ${directory.practitionerName(booking.practitionerId)}`;
          const startRow = rowOf(item.start);
          const endRow = Math.max(startRow + 1, rowOf(item.end));
          return (
            <Link
              key={booking.id}
              href={`/prenotazioni/${booking.id}`}
              className={`event event-${status.tone}`}
              style={{ gridColumn: index + 2, gridRow: `${startRow} / ${endRow}`, left: `${(lane / lanes) * 100}%`, width: `calc(${100 / lanes}% - 2px)` }}
              aria-label={`${formatDayWithWeekday(day)}, ${formatTime(booking.startsAt)}–${formatTime(booking.endsAt)}, ${what}, ${status.text}`}
            >
              <span className="event-time">{formatTime(booking.startsAt)}</span>
              <span className="event-what">{what}</span>
            </Link>
          );
        }),
      )}
      {now !== null && now >= range.from * 60 && now < range.to * 60 ? (
        <div className="week-now" style={{ gridColumn: todayIndex + 2, gridRow: rowOf(now) }} aria-hidden="true" />
      ) : null}
    </div>
  );
}
```

- [ ] **Step 3: la vista settimana in `BookingsClient.tsx`**

Import da aggiungere:

```tsx
import { PeriodNav } from "@/components/calendar/PeriodNav";
import { WeekGrid } from "@/components/calendar/WeekGrid";
import { addDays, romeDay } from "@/lib/format";
import type { Directory } from "@/lib/directory";
```

e in quello da `@/lib/calendar`: `weekOf, weekTitle`.

Sostituire la riga provvisoria della settimana con:

```tsx
      {view === "settimana" && all !== null && directory.ready ? (
        <WeekView monday={mondayOf(anchor)} today={today} bookings={shown} directory={directory} />
      ) : null}
```

e aggiungere in fondo al file:

```tsx
// the week of a Monday: the navigation, the grid with the sessions of those seven days, a word when there are none
function WeekView({ monday, today, bookings, directory }: { monday: Day; today: Day; bookings: Booking[]; directory: Directory }) {
  const days = weekOf(monday);
  const inWeek = bookings.filter((booking) => days.includes(romeDay(booking.startsAt)));
  return (
    <>
      <PeriodNav
        unit="settimana"
        title={weekTitle(monday)}
        previous={`/prenotazioni?vista=settimana&da=${addDays(monday, -7)}`}
        next={`/prenotazioni?vista=settimana&da=${addDays(monday, 7)}`}
        today={monday === mondayOf(today) ? null : "/prenotazioni?vista=settimana"}
      />
      <WeekGrid days={days} today={today} bookings={inWeek} directory={directory} />
      {inWeek.length === 0 ? (
        <p className="muted">
          Nessuna seduta questa settimana. <Link href="/">Prenotane una</Link>.
        </p>
      ) : null}
    </>
  );
}
```

- [ ] **Step 4: gli stili**

Aggiungere a `globals.css`, prima di `/* ***** dialog ***** */`:

```css
/* ***** calendar: navigation, week, month (D-087) ***** */

.period-nav {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  margin: 0.25rem 0 0.75rem;
}

.period-title {
  flex: 1;
  margin: 0;
  font-size: 1.15rem;
}

/* moving in time is wayfinding: indigo, like where you are (D-086) */
.period-arrow {
  display: inline-flex;
  width: var(--tap);
  height: var(--tap);
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 1.7rem;
  line-height: 1;
  color: var(--color-indigo);
  text-decoration: none;
}

.period-today {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  margin-right: 0.25rem;
  padding: 0 0.8rem;
  border: 1px solid var(--color-periwinkle);
  border-radius: var(--radius-pill);
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--color-indigo);
  text-decoration: none;
}

.period-arrow:hover,
.period-today:hover {
  background: var(--color-periwinkle-tint);
}

/* the week: an hour column and seven day columns; the rows (one per five minutes) are set by the component */
.week {
  display: grid;
  grid-template-columns: 2.6rem repeat(7, minmax(0, 1fr));
  column-gap: 2px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 0.5rem 0.5rem 0.75rem 0;
  margin-bottom: 1rem;
}

.week-corner {
  grid-column: 1;
  grid-row: 1;
}

.week-head {
  grid-row: 1;
  padding-bottom: 0.4rem;
  text-align: center;
  font-size: 0.75rem;
  color: var(--color-text-muted);
}

.week-head strong {
  display: inline-flex;
  width: 1.6rem;
  height: 1.6rem;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 0.9rem;
  color: var(--color-text);
}

.week-head.is-today strong {
  background: var(--color-indigo);
  color: var(--color-on-indigo);
}

/* a line every hour: 12 rows of 4 px */
.week-col {
  grid-row: 2 / -1;
  border-bottom: 1px solid var(--color-border);
  background-image: repeating-linear-gradient(to bottom, var(--color-border) 0 1px, transparent 1px 48px);
}

.week-col.is-today {
  background-color: var(--color-periwinkle-tint);
}

.week-hour {
  grid-column: 1;
  align-self: start;
  transform: translateY(-50%);
  padding-right: 0.4rem;
  text-align: right;
  font-size: 0.7rem;
  line-height: 1;
  color: var(--color-text-muted);
  white-space: nowrap;
}

/* a session: from its start to its end, in the colours of its state (the badge's) */
.event {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  margin-top: 1px;
  padding: 1px 3px 1px 4px;
  border-left: 3px solid;
  border-radius: 5px;
  font-size: 0.72rem;
  line-height: 1.25;
  text-decoration: none;
  overflow: hidden;
}

.event-time {
  font-weight: 700;
}

.event-what {
  display: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.event-success {
  background: var(--color-success-bg);
  color: var(--color-success);
  border-color: var(--color-success);
}

.event-warning {
  background: var(--color-warning-bg);
  color: var(--color-warning);
  border-color: var(--color-warning);
}

.event-danger {
  background: var(--color-danger-bg);
  color: var(--color-danger);
  border-color: var(--color-danger);
}

.event-neutral {
  background: var(--color-surface-muted);
  color: var(--color-text-muted);
  border-color: var(--color-text-muted);
}

.week-now {
  z-index: 2;
  border-top: 2px solid var(--color-indigo);
  pointer-events: none;
}

@media (min-width: 48rem) {
  .event {
    font-size: 0.78rem;
  }

  .event-what {
    display: block;
  }
}
```

- [ ] **Step 5: controllo**

Run: `npm run typecheck` → nessun errore.

Nel browser, `/prenotazioni?vista=settimana`: la griglia 9–19 con le righe delle ore, il giorno di oggi col cerchio indaco e il velo pervinca, la riga indaco all'ora attuale; se il cliente ha una seduta nella settimana, il blocco al posto giusto. Frecce: la settimana prima e dopo; "Oggi" compare solo fuori dalla settimana corrente. Con il browser MCP a 412 × 915: screenshot in chiaro e in scuro.

- [ ] **Step 6: commit**

```
feat(frontend): the week of "my sessions" as a grid of days and hours

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

### Task 8: la griglia del mese e il giorno scelto

**Files:**
- Create: `frontend/src/components/calendar/MonthGrid.tsx`
- Modify: `frontend/src/app/prenotazioni/BookingsClient.tsx`
- Modify: `frontend/src/app/globals.css`

- [ ] **Step 1: `MonthGrid.tsx`**

```tsx
import Link from "next/link";
import type { Schemas } from "@/api/client";
import { weekOf, type Day } from "@/lib/calendar";
import type { Directory } from "@/lib/directory";
import { formatDayWithWeekday, formatTime, formatWeekday, romeDay, sessionsText } from "@/lib/format";
import { BOOKING_STATUS, badgeClass } from "@/lib/labels";

type Booking = Schemas["CustomerBookingView"];

const MAX_DOTS = 4;
// "lun … dom", from a week known to start on a Monday
const WEEKDAYS = weekOf("2024-01-01").map(formatWeekday);

// The month as a classic grid (D-087): a dot per session in the colour of its state, the chosen day listed below.
// Every cell is a link that puts its day in the address, so that coming back keeps it
export function MonthGrid({
  first,
  cells,
  chosen,
  today,
  bookings,
  directory,
}: {
  first: Day;
  cells: Day[];
  chosen: Day | null;
  today: Day;
  bookings: Booking[];
  directory: Directory;
}) {
  const byDay = new Map<Day, Booking[]>();
  for (const booking of [...bookings].sort((a, b) => a.startsAt.localeCompare(b.startsAt))) {
    const day = romeDay(booking.startsAt);
    byDay.set(day, [...(byDay.get(day) ?? []), booking]);
  }
  const month = first.slice(0, 7);
  const ofChosen = chosen === null ? [] : (byDay.get(chosen) ?? []);

  return (
    <>
      <div className="month">
        {WEEKDAYS.map((name) => (
          <div key={name} className="month-weekday" aria-hidden="true">
            {name}
          </div>
        ))}
        {cells.map((day) => {
          const ofDay = byDay.get(day) ?? [];
          const classes = ["month-day", day.slice(0, 7) === month ? "" : "is-outside", day === today ? "is-today" : ""].filter(Boolean).join(" ");
          const count = ofDay.length === 0 ? "nessuna seduta" : sessionsText(ofDay.length);
          return (
            <Link
              key={day}
              href={`/prenotazioni?vista=mese&da=${first}&giorno=${day}`}
              className={classes}
              aria-current={day === chosen ? "true" : undefined}
              aria-label={`${day === today ? "Oggi, " : ""}${formatDayWithWeekday(day)}, ${count}`}
            >
              <span className="month-number">{Number(day.slice(8))}</span>
              <span className="month-dots" aria-hidden="true">
                {ofDay.slice(0, MAX_DOTS).map((booking) => (
                  <i key={booking.id} className={`dot dot-${BOOKING_STATUS[booking.status].tone}`} />
                ))}
                {ofDay.length > MAX_DOTS ? <span className="month-more">+{ofDay.length - MAX_DOTS}</span> : null}
              </span>
            </Link>
          );
        })}
      </div>

      {chosen === null ? (
        <p className="muted">Tocca un giorno per vedere le sue sedute.</p>
      ) : (
        <section>
          <h2>{formatDayWithWeekday(chosen)}</h2>
          {ofChosen.length === 0 ? (
            <p className="muted">Nessuna seduta.</p>
          ) : (
            <ul className="day-list">
              {ofChosen.map((booking) => {
                const status = BOOKING_STATUS[booking.status];
                return (
                  <li key={booking.id}>
                    <Link href={`/prenotazioni/${booking.id}`} className="day-row">
                      <span className="day-row-time">
                        {formatTime(booking.startsAt)}–{formatTime(booking.endsAt)}
                      </span>
                      <span className="day-row-what">
                        {directory.serviceName(booking.practitionerId, booking.serviceId)} con {directory.practitionerName(booking.practitionerId)}
                      </span>
                      <span className={badgeClass(status.tone)}>{status.text}</span>
                    </Link>
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      )}
    </>
  );
}
```

- [ ] **Step 2: la vista mese in `BookingsClient.tsx`**

Import: `import { MonthGrid } from "@/components/calendar/MonthGrid";` e da `@/lib/calendar` anche `addMonths, monthGridOf, monthTitle`.

Dopo `const anchor…`, leggere il giorno scelto:

```tsx
  const chosenParam = query.get("giorno");
```

Sostituire la riga provvisoria del mese con:

```tsx
      {view === "mese" && all !== null && directory.ready ? (
        <MonthView first={firstOfMonth(anchor)} chosenParam={chosenParam} today={today} bookings={shown} directory={directory} />
      ) : null}
```

e aggiungere in fondo al file:

```tsx
// the month of a first day: the navigation, the grid, the chosen day (today in the current month, else none) listed
function MonthView({
  first,
  chosenParam,
  today,
  bookings,
  directory,
}: {
  first: Day;
  chosenParam: string | null;
  today: Day;
  bookings: Booking[];
  directory: Directory;
}) {
  const cells = monthGridOf(first);
  const chosen = isDay(chosenParam) && cells.includes(chosenParam) ? chosenParam : first === firstOfMonth(today) ? today : null;
  const inGrid = bookings.filter((booking) => cells.includes(romeDay(booking.startsAt)));
  const inMonth = inGrid.some((booking) => romeDay(booking.startsAt).slice(0, 7) === first.slice(0, 7));
  return (
    <>
      <PeriodNav
        unit="mese"
        title={monthTitle(first)}
        previous={`/prenotazioni?vista=mese&da=${addMonths(first, -1)}`}
        next={`/prenotazioni?vista=mese&da=${addMonths(first, 1)}`}
        today={first === firstOfMonth(today) ? null : "/prenotazioni?vista=mese"}
      />
      <MonthGrid first={first} cells={cells} chosen={chosen} today={today} bookings={inGrid} directory={directory} />
      {!inMonth ? (
        <p className="muted">
          Nessuna seduta in questo mese. <Link href="/">Prenotane una</Link>.
        </p>
      ) : null}
    </>
  );
}
```

- [ ] **Step 3: gli stili**

Aggiungere a `globals.css`, dopo il blocco `@media (min-width: 48rem)` della settimana:

```css
/* the month: seven columns, a cell per day with a dot per session */
.month {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 2px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 0.5rem;
  margin-bottom: 1rem;
}

.month-weekday {
  padding-bottom: 0.25rem;
  text-align: center;
  font-size: 0.75rem;
  color: var(--color-text-muted);
}

.month-day {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.2rem;
  min-height: 52px;
  padding: 0.3rem 0;
  border-radius: var(--radius-small);
  color: var(--color-text);
  text-decoration: none;
}

.month-day:hover {
  background: var(--color-periwinkle-tint);
}

.month-day.is-outside {
  color: var(--color-text-muted);
  opacity: 0.6;
}

.month-number {
  display: inline-flex;
  width: 1.7rem;
  height: 1.7rem;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-weight: 600;
}

.month-day.is-today .month-number {
  background: var(--color-indigo);
  color: var(--color-on-indigo);
}

/* the chosen day: the whole cell in indigo, like the chosen day of the booking page */
.month-day[aria-current="true"] {
  background: var(--color-indigo);
  color: var(--color-on-indigo);
  opacity: 1;
}

.month-day[aria-current="true"] .month-number {
  background: transparent;
  color: inherit;
}

.month-dots {
  display: flex;
  align-items: center;
  gap: 3px;
  min-height: 6px;
  font-size: 0.65rem;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-text-muted);
}

.dot-success {
  background: var(--color-success);
}

.dot-warning {
  background: var(--color-warning);
}

.dot-danger {
  background: var(--color-danger);
}

.month-day[aria-current="true"] .dot {
  background: var(--color-on-indigo);
}

/* the sessions of the chosen day */
.day-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.day-row {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 0.6rem;
  align-items: center;
  min-height: var(--tap);
  padding: 0.5rem 0;
  border-top: 1px solid var(--color-border);
  color: var(--color-text);
  text-decoration: none;
}

.day-row-time {
  font-weight: 600;
  white-space: nowrap;
}

.day-row-what {
  min-width: 0;
}
```

- [ ] **Step 4: controllo**

Run: `npm run typecheck` → nessun errore.

Nel browser, `/prenotazioni?vista=mese`: la griglia del mese corrente, oggi col cerchio e scelto (casella indaco), sotto "Oggi…" con le sue sedute o "Nessuna seduta."; toccando un altro giorno la query cambia e la casella si sposta; nel mese prossimo nessun giorno scelto e la frase "Tocca un giorno…"; "Oggi" torna al mese corrente. Screenshot chiaro e scuro a 412 × 915.

- [ ] **Step 5: commit**

```
feat(frontend): the month of "my sessions" as a grid, with the chosen day listed

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

### Task 9: le prove end-to-end

**Files:**
- Modify: `frontend/e2e/support.ts`
- Modify: `frontend/e2e/customer.spec.ts`

- [ ] **Step 1: il giorno di Roma di un istante, in `support.ts`**

Sostituire `todayInRome` con:

```ts
const romeDay = new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Rome", year: "numeric", month: "2-digit", day: "2-digit" });

/** The day in Rome of an instant, "2026-11-16". */
export function dayInRome(instant: string): string {
  return romeDay.format(new Date(instant));
}

/** Today in Rome, 2026-11-12. */
export function todayInRome(): string {
  return romeDay.format(new Date());
}
```

- [ ] **Step 2: il test nuovo in `customer.spec.ts`**

Aggiungere `dayInRome` all'import da `./support`. Inserire **prima** di `test("ogni pagina sta nello schermo del telefono"…`:

```ts
test("la settimana e il mese mostrano la seduta confermata, non quelle annullate, e portano alla sua pagina", async () => {
  const { page } = maria;
  await chooseTheFirstFreeSlot(page);
  await page.getByRole("button", { name: "Prenota" }).click();
  await expect(page.getByText("Seduta confermata: hai usato un tuo credito.")).toBeVisible();
  const id = /\/prenotazioni\/(\d+)/.exec(page.url())?.[1];
  expect(id).toBeDefined();
  const upcoming = (await (await page.request.get("/api/customer/bookings?period=upcoming")).json()) as Schemas["CustomerBookingView"][];
  const confirmed = upcoming.find((booking) => booking.id === Number(id));
  expect(confirmed).toBeDefined();
  const day = dayInRome(confirmed?.startsAt ?? "");

  // the week: the confirmed session is a block; the ones cancelled and withdrawn before are not drawn
  await page.goto(`/prenotazioni?vista=settimana&da=${day}`);
  const block = page.getByRole("link", { name: /Seduta posturale con Anna Rossi, Confermata$/ });
  await expect(block).toHaveCount(1);
  await expect(page.getByRole("link", { name: /Cancellata|Ritirata/ })).toHaveCount(0);
  await expectFitsTheScreen(page);
  await block.click();
  await expect(page).toHaveURL(`${FRONTEND_URL}/prenotazioni/${id}`);

  // the month: a dot on its day, and the row below the grid
  await page.goto(`/prenotazioni?vista=mese&da=${day}&giorno=${day}`);
  await expect(page.getByRole("link", { name: /, 1 seduta$/ })).toHaveCount(1);
  await expect(page.getByRole("link", { name: /Seduta posturale con Anna Rossi/ })).toBeVisible();
  await expectFitsTheScreen(page);
});
```

- [ ] **Step 3: le due viste nel test "ogni pagina"**

Dopo le righe

```ts
  await page.getByRole("tab", { name: "Passate" }).click();
  await expectFitsTheScreen(page);
```

aggiungere:

```ts
  await visit("/prenotazioni?vista=settimana", "05b-settimana", /^\d+ .* \d{4}$/);
  await visit("/prenotazioni?vista=mese", "05c-mese", /^[A-Z][a-zù]+ \d{4}$/);
```

- [ ] **Step 4: la suite completa**

Prerequisiti: Docker con PostgreSQL e Mailpit su, `.env` del progetto.

Run: `npm run typecheck` → nessun errore.
Run: `npm run test:unit` → `13 passed`.
Run: `npm run e2e` → `10 passed` (circa 45 secondi di test più l'avvio del backend).

- [ ] **Step 5: commit**

```
test(frontend): the week and the month views in the end-to-end story

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

### Task 10: la documentazione

**Files:**
- Modify: `docs/decisioni.md`, `docs/passi/12-frontend-cliente.md`, `docs/stato.md`

- [ ] **Step 1: D-087 in `docs/decisioni.md`** (in fondo, dopo D-086, separata da `---`)

```markdown
## D-087 — "Le mie sedute" anche a calendario: settimana a griglia di ore e mese classico, solo le sedute vere, nessuna modifica al backend
**Stato:** accettata · 2026-11-13 · richiesta di Nicholas · disegno: `docs/superpowers/specs/2026-11-13-sedute-calendario-design.md` · completa D-082 (l'elenco diviso dal backend resta)

**Contesto.** "Le mie sedute" era solo un elenco, prossime e passate. Nicholas voleva vedere le proprie sedute anche in una griglia di calendario, settimanale e mensile, sul telefono.

**Decisione.**
- **Dove**: nella stessa pagina, con una barra sola di quattro schede, **Prossime · Passate · Settimana · Mese**. Vista e periodo stanno nell'indirizzo (`?vista=settimana&da=2026-11-09`, `?vista=mese&da=2026-11-01&giorno=2026-11-13`); schede, frecce, "Oggi" e caselle del mese sono link: ricaricare, tornare indietro e salvare il link funzionano. Nessuna preferenza ricordata sul telefono.
- **Settimana**: griglia CSS, una colonna per giorno e una riga ogni cinque minuti (48 px l'ora); una seduta è un blocco dal suo inizio alla sua fine, nei colori del suo stato (quelli dei badge), link alla sua pagina. Ore mostrate: dalla prima all'ultima seduta, almeno 9–19 (ampiezza di disegno, non regola). Sedute sovrapposte si dividono la colonna in corsie. Oggi: cerchio indaco sul numero, velo pervinca sulla colonna, riga indaco all'ora attuale.
- **Mese**: griglia classica lun–dom, un puntino per seduta (massimo quattro, poi "+n"); il giorno scelto in indaco pieno e, sotto, le sue sedute come righe-link. Di default è scelto oggi nel mese corrente, nessuno negli altri.
- **Quali sedute**: solo gli stati "veri" (in attesa di pagamento, in attesa di approvazione, confermata, svolta, non presentato); cancellate, ritirate, non accettate e scadute restano nell'elenco. È una scelta di **presentazione** (`SHOWN_IN_CALENDAR`, un `Record` sugli stati: uno stato nuovo nel contratto obbliga a decidere), non una regola: gli stati li decide il backend.
- **Dati**: `GET /api/customer/bookings` senza `period` (già esistente), una volta per visita. Giorno e ora sempre quelli di **Roma** (`Intl` con `Europe/Rome`), mai dell'orologio del telefono; i conti di calendario sono funzioni pure in `src/lib/calendar.ts`, provate da sole con il runner di Playwright senza server (`npm run test:unit`), con il processo messo apposta nel fuso di New York.
- **Nessuna modifica al backend**: se la lista di un cliente diventasse lunga (centinaia di sedute), si aggiungerà `from/to`.

**Alternative scartate.** *Una libreria di calendario* (FullCalendar, react-big-calendar): centinaia di KB sul telefono, stili propri, e disegna nel fuso del telefono. *Pagine separate* `/prenotazioni/settimana` e `/mese`: tre pagine con gli stessi dati, niente in più. *La settimana a righe* (un giorno per riga): più leggibile sul telefono, ma non è una griglia; Nicholas ha scelto la griglia giorni × ore come nel calendario del telefono. *Tutte le sedute, le annullate in grigio*: un appuntamento che non c'è più confonde in un calendario.

**Conseguenze.** Un test end-to-end in più (10 su 10): la seduta confermata appare nella settimana e nel mese e porta alla sua pagina; quelle annullate no. Nuovi file: `src/lib/calendar.ts` (+ test), `src/components/calendar/{PeriodNav,WeekGrid,MonthGrid}.tsx`, `playwright.unit.config.ts`.
```

- [ ] **Step 2: la sezione in `docs/passi/12-frontend-cliente.md`** (in fondo)

```markdown
## Dopo il passo: le viste a calendario (2026-11-13, D-087)

### Cosa è stato costruito
| File | Cosa fa |
|---|---|
| `src/lib/calendar.ts` + `calendar.test.ts` | I conti di calendario, funzioni pure: lunedì di un giorno, settimane, griglia del mese (28, 35 o 42 giorni), orologio di Roma di un istante, corsie per le sovrapposizioni, titoli. 13 prove senza server (`npm run test:unit`) |
| `playwright.unit.config.ts` | Il runner di Playwright usato per le funzioni pure: `testDir: "src"`, nessun browser, processo nel fuso di New York |
| `src/components/calendar/WeekGrid.tsx` | La settimana: griglia CSS con una riga ogni 5 minuti, blocchi-link nei colori dello stato, corsie, oggi e l'ora attuale |
| `src/components/calendar/MonthGrid.tsx` | Il mese: caselle-link con i puntini, il giorno scelto elencato sotto |
| `src/components/calendar/PeriodNav.tsx` | Titolo del periodo, "Oggi", frecce: tutti link |
| `src/app/prenotazioni/BookingsClient.tsx` | Le quattro schede, `vista`/`da`/`giorno` dalla query string, il caricamento di tutte le sedute, i filtri per stato e periodo |
| `src/lib/labels.ts` | `SHOWN_IN_CALENDAR`: quali stati le griglie disegnano |

### Concetti chiave
**Lo stato nell'indirizzo.** Vista e periodo stanno nella query string e si cambiano con link, non con uno stato nascosto nel componente: così il tasto indietro, il ricaricamento e un link salvato fanno la cosa giusta senza codice in più. In React si legge con `useSearchParams`, e la pagina si ricalcola da sola quando l'indirizzo cambia.

**Griglia CSS con righe di 5 minuti.** `display: grid` con 8 colonne (ore + 7 giorni) e una riga per ogni 5 minuti (4 px): un blocco dichiara `grid-row: inizio / fine` e il browser lo mette e lo dimensiona da solo, per qualsiasi durata. Le righe delle ore sono un `repeating-linear-gradient` sulle colonne, non 70 elementi. Elementi diversi possono occupare le stesse celle: le colonne sotto, i blocchi sopra (`z-index`).

**Giorni di calendario come stringhe.** Un giorno è `"2026-11-13"`, e per contare giorni e settimane si usa `Date.UTC` a mezzanotte UTC: nessun fuso, nessun cambio dell'ora. Ora e minuto di un istante si leggono con `Intl.DateTimeFormat` e `timeZone: "Europe/Rome"`: la stessa seduta è alle 10:00 anche per un cliente all'estero. I test girano con il processo nel fuso di New York per provarlo.

**Funzioni pure provate da sole.** I conti stanno in un modulo senza React e senza rete; il runner di Playwright, che il progetto già ha, li esegue in pochi secondi con una seconda configurazione senza `webServer`. Nessuna dipendenza nuova.

**Partizione degli intervalli (corsie).** Per sedute sovrapposte: ordinate per inizio, a ognuna la prima corsia il cui ultimo intervallo è già finito; quando nessuna seduta già messa arriva alla prossima, il gruppo si chiude e tutti i suoi membri sanno quante corsie dividono.

### Decisioni
- D-087: le viste a calendario di "le mie sedute".

### Domande da colloquio
### Domanda 1: Perché la vista e il periodo stanno nella query string e non in uno `useState`?
**Risposta modello.** Perché sono stato di navigazione, non di interazione: l'utente si aspetta che indietro, ricarica e link salvato lo riportino alla stessa settimana. Con lo stato nell'indirizzo lo fa il browser; con uno `useState` dovrei ricostruirlo a mano, e perderei il link condivisibile.

### Domanda 2: Come disegni un blocco dalle 10:30 alle 11:15 in una griglia CSS?
**Risposta modello.** Scelgo un'unità di riga (5 minuti, 4 px) e dichiaro `grid-row: r(630) / r(675)`, dove `r` converte i minuti dalla prima ora mostrata in un indice di riga. Il browser calcola posizione e altezza; io non faccio aritmetica di pixel. Due sedute sovrapposte ottengono una corsia e una larghezza in percentuale.

### Domanda 3: Perché i conti di calendario non usano `new Date(anno, mese, giorno)`?
**Risposta modello.** Perché quel costruttore lavora nel fuso del dispositivo: a mezzanotte di un cambio dell'ora, o su un telefono all'estero, "aggiungi un giorno" può sbagliare. Uso `Date.UTC` solo per contare, e `Intl` con `Europe/Rome` solo per leggere ore e minuti di un istante: due strumenti, ciascuno per il suo compito.
```

- [ ] **Step 3: la riga in `docs/stato.md`**

Nel paragrafo del passo 12, dopo la frase sull'identità visiva, aggiungere:

```markdown
**"Le mie sedute" anche a calendario** (D-087, 2026-11-13, richiesta di Nicholas): settimana a griglia giorni × ore e mese classico, solo le sedute vere, vista e periodo nell'indirizzo, nessuna modifica al backend; 13 prove unitarie (`npm run test:unit`) e un test end-to-end in più (10 su 10). Disegno e piano in `docs/superpowers/`.
```

E in testa (riga "Ultimo aggiornamento") la data 2026-11-13 e "D-083–D-087".

- [ ] **Step 4: commit**

```
docs: the week and month views of "my sessions" (D-087), and what they taught

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Verifica finale (prima di dire "fatto")

- [ ] `npm run typecheck` → nessun errore
- [ ] `npm run test:unit` → `13 passed`
- [ ] `npm run e2e` → `10 passed`
- [ ] Screenshot a 412 × 915 in chiaro e scuro di settimana e mese, guardati: niente scorrimento orizzontale, blocchi leggibili, oggi e il giorno scelto in indaco
- [ ] `git status` pulito, `git log` con i commit del piano
