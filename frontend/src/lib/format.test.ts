// The conversions of the panel's forms (step 13): run with the process in New York's zone, as calendar.test.ts
import { expect, test } from "@playwright/test";
import { euroInput, parseEuro, romeClockTime, romeDateTime } from "./format";

test("a time on Rome's clock is +01:00 in winter and +02:00 in summer, whatever the zone of the computer", () => {
  expect(romeDateTime("2026-11-10", "09:30")).toBe("2026-11-10T09:30:00+01:00");
  expect(romeDateTime("2026-07-10", "09:30")).toBe("2026-07-10T09:30:00+02:00");
  // the day the clock goes forward (29 March 2026, 2:00 → 3:00): after the change it is summer time
  expect(romeDateTime("2026-03-29", "10:00")).toBe("2026-03-29T10:00:00+02:00");
  expect(romeDateTime("2026-10-25", "10:00")).toBe("2026-10-25T10:00:00+01:00");
});

test("an instant read back as Rome's clock time", () => {
  expect(romeClockTime("2026-11-10T08:30:00Z")).toBe("09:30");
  expect(romeClockTime("2026-07-10T07:30:00Z")).toBe("09:30");
});

test("an amount typed in euro becomes integer cents, never a fraction", () => {
  expect(parseEuro("30")).toBe(3000);
  expect(parseEuro("30,5")).toBe(3050);
  expect(parseEuro(" 45,90 € ")).toBe(4590);
  expect(parseEuro("0.10")).toBe(10);
  expect(parseEuro("30,555")).toBeNull();
  expect(parseEuro("trenta")).toBeNull();
  expect(parseEuro("")).toBeNull();
  expect(euroInput(3050)).toBe("30,50");
  expect(euroInput(5)).toBe("0,05");
});
