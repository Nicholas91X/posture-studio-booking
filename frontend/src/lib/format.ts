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

/** The offset of Rome's clock at an instant, in minutes: 60 in winter, 120 in summer. */
function romeOffsetMinutes(at: Date): number {
  const name = new Intl.DateTimeFormat("en-US", { timeZone: STUDIO_ZONE, timeZoneName: "longOffset" })
    .formatToParts(at)
    .find((part) => part.type === "timeZoneName")?.value;
  const match = /GMT([+-])(\d{2}):(\d{2})/.exec(name ?? "");
  if (match === null) {
    return 0;
  }
  const minutes = Number(match[2]) * 60 + Number(match[3]);
  return match[1] === "-" ? -minutes : minutes;
}

const two = (value: number) => String(value).padStart(2, "0");

/**
 * A day and a time on Rome's clock, as the practitioner types them, as the instant the backend wants:
 * ("2026-11-10", "09:30") → "2026-11-10T09:30:00+01:00", ("2026-07-10", "09:30") → "…+02:00". Only a conversion
 * (step 13): whether that time can be booked is the backend's answer.
 */
export function romeDateTime(day: string, clockTime: string): string {
  const [year, month, date] = day.split("-").map(Number) as [number, number, number];
  const [hour, minute] = clockTime.split(":").map(Number) as [number, number];
  const asIfUtc = Date.UTC(year, month - 1, date, hour, minute);
  // the offset at the guessed instant, then again at the corrected one: right also on the days the clock changes
  let offset = romeOffsetMinutes(new Date(asIfUtc));
  offset = romeOffsetMinutes(new Date(asIfUtc - offset * 60_000));
  const sign = offset < 0 ? "-" : "+";
  const absolute = Math.abs(offset);
  return `${day}T${two(hour)}:${two(minute)}:00${sign}${two(Math.floor(absolute / 60))}:${two(absolute % 60)}`;
}

/** An instant as Rome's clock time for an input, "09:30". */
export function romeClockTime(instant: string): string {
  return new Intl.DateTimeFormat("en-GB", { timeZone: STUDIO_ZONE, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(
    new Date(instant),
  );
}

/** "30,00" or "30" or "30.5" → 3000 or 3050 cents; null when it is not an amount. Typing only: the price is the backend's. */
export function parseEuro(text: string): number | null {
  const clean = text.trim().replace(/\s|€/g, "").replace(",", ".");
  if (!/^\d+(\.\d{1,2})?$/.test(clean)) {
    return null;
  }
  const [units, decimals = ""] = clean.split(".") as [string, string?];
  return Number(units) * 100 + Number(decimals.padEnd(2, "0"));
}

/** 3050 → "30,50", for an input to edit. */
export function euroInput(cents: number): string {
  return `${Math.floor(cents / 100)},${two(cents % 100)}`;
}
