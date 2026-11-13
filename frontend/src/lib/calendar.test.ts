// The calendar maths (D-087). The process runs in New York's zone (playwright.unit.config.ts): nothing here may
// depend on the clock of the computer.
import { expect, test } from "@playwright/test";
import { addMonths, firstOfMonth, isDay, mondayOf, monthGridOf, weekOf, weekdayOf } from "./calendar";

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
