// The calendar maths (D-087). The process runs in New York's zone (playwright.unit.config.ts): nothing here may
// depend on the clock of the computer.
import { expect, test } from "@playwright/test";
import {
  addMonths,
  firstOfMonth,
  hourRange,
  isDay,
  lanesOf,
  minutesBetween,
  mondayOf,
  monthGridOf,
  monthTitle,
  romeClock,
  romeMinutes,
  weekOf,
  weekTitle,
  weekdayOf,
} from "./calendar";
import { formatDayWithWeekday, formatWeekday } from "./format";

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
