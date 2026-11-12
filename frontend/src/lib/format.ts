// How dates, times and prices are shown (step 12). Only showing: no rule is computed here (D-005).
// Instants come from the backend with their offset (e.g. 2026-11-16T10:00:00+01:00) and are always shown in the
// studio's zone, whatever the zone of the phone. Days of the calendar (a credit's last valid day, D-069) come as
// 2026-11-16 and are shown as they are, without any conversion.
export const STUDIO_ZONE = "Europe/Rome";

const price = new Intl.NumberFormat("it-IT", { style: "currency", currency: "EUR" });

/** 3000 → "30,00 €". Cents are integers (CLAUDE.md, "Soldi"): divided only to be shown. */
export function formatPrice(cents: number): string {
  return price.format(cents / 100);
}

const dateTime = new Intl.DateTimeFormat("it-IT", {
  timeZone: STUDIO_ZONE,
  weekday: "long",
  day: "numeric",
  month: "long",
  hour: "2-digit",
  minute: "2-digit",
});
const longDay = new Intl.DateTimeFormat("it-IT", { timeZone: STUDIO_ZONE, weekday: "long", day: "numeric", month: "long" });
const time = new Intl.DateTimeFormat("it-IT", { timeZone: STUDIO_ZONE, hour: "2-digit", minute: "2-digit" });
const weekdayShort = new Intl.DateTimeFormat("it-IT", { timeZone: STUDIO_ZONE, weekday: "short" });
const dayNumber = new Intl.DateTimeFormat("it-IT", { timeZone: STUDIO_ZONE, day: "numeric" });
const monthShort = new Intl.DateTimeFormat("it-IT", { timeZone: STUDIO_ZONE, month: "short" });
// en-CA writes 2026-11-16: the day of an instant in Rome, as the backend counts days (D-069)
const isoDay = new Intl.DateTimeFormat("en-CA", { timeZone: STUDIO_ZONE, year: "numeric", month: "2-digit", day: "2-digit" });

/** "Lunedì 16 novembre, ore 10:00" */
export function formatDateTime(instant: string): string {
  return capitalize(dateTime.format(new Date(instant)).replace(" alle ore ", ", ore "));
}

/** "lunedì 16 novembre" */
export function formatLongDay(instant: string): string {
  return capitalize(longDay.format(new Date(instant)));
}

/** "10:00" */
export function formatTime(instant: string): string {
  return time.format(new Date(instant));
}

/** the three parts of a day button: "lun", "16", "nov" */
export function dayParts(instant: string): { weekday: string; day: string; month: string } {
  const date = new Date(instant);
  return { weekday: weekdayShort.format(date), day: dayNumber.format(date), month: monthShort.format(date) };
}

/** The day in Rome of an instant, "2026-11-16": to group the free slots by day. */
export function romeDay(instant: string | Date): string {
  return isoDay.format(typeof instant === "string" ? new Date(instant) : instant);
}

/** Today in Rome, "2026-11-12". */
export function todayInRome(): string {
  return romeDay(new Date());
}

/** A day of the calendar plus n days, "2026-11-12" + 3 → "2026-11-15" (no zone: plain days). */
export function addDays(day: string, n: number): string {
  const [year, month, date] = day.split("-").map(Number) as [number, number, number];
  return new Date(Date.UTC(year, month - 1, date + n)).toISOString().slice(0, 10);
}

const calendarDay = new Intl.DateTimeFormat("it-IT", { timeZone: "UTC", day: "numeric", month: "long", year: "numeric" });

/** A day of the calendar as it is: "2027-02-09" → "9 febbraio 2027", whatever the zone of the phone. */
export function formatCalendarDay(day: string): string {
  const [year, month, date] = day.split("-").map(Number) as [number, number, number];
  return calendarDay.format(new Date(Date.UTC(year, month - 1, date)));
}

/** "Anna Rossi" */
export function fullName(person: { firstName: string; lastName: string }): string {
  return `${person.firstName} ${person.lastName}`;
}

/** 1 → "un'ora", 24 → "24 ore" */
export function hoursText(hours: number): string {
  return hours === 1 ? "un'ora" : `${hours} ore`;
}

/** 1 → "1 seduta", 5 → "5 sedute" */
export function sessionsText(count: number): string {
  return count === 1 ? "1 seduta" : `${count} sedute`;
}

/** 1 → "1 credito", 3 → "3 crediti" */
export function creditsText(count: number): string {
  return count === 1 ? "1 credito" : `${count} crediti`;
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}
