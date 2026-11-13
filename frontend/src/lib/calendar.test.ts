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
