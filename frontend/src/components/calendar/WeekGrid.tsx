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
