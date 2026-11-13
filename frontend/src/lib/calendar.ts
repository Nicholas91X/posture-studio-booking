// The calendar maths of the week and month views of "my sessions" (D-087). Pure functions, no React: the grids draw
// only what these compute. A day is a string "2026-11-13", a day of the calendar in Rome (format.ts, D-069), never a
// Date in the zone of the phone; a clock time is minutes since midnight on Rome's clock.
import { STUDIO_ZONE, addDays } from "./format";

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
