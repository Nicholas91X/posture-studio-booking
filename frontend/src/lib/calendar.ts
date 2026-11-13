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
