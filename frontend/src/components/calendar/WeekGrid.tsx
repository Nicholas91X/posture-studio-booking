import Link from "next/link";
import type { CSSProperties } from "react";
import { hourRange, lanesOf, minutesBetween, romeMinutes, type Day } from "@/lib/calendar";
import { formatDayWithWeekday, formatTime, formatWeekday, romeDay } from "@/lib/format";
import type { Tone } from "@/lib/labels";

/** One block of the grid: a session of the customer (step 12) or of the practitioner (step 13). */
export type GridEvent = {
  key: number;
  startsAt: string;
  endsAt: string;
  href: string;
  tone: Tone;
  // what the block says, e.g. "Seduta con Anna Rossi" or "Mario Bianchi"
  what: string;
  // the state in words, for the screen reader
  status: string;
  // a mark on the block: the panel's flags (outside working hours, evaluation in the room, D-049)
  flag?: string;
};

const UNIT_MINUTES = 5; // one grid row
const ROW_PX = 4; // 48 px per hour
const HEAD_ROWS = 1; // the first grid row is the day names

// The week (or a single day) as the phone's calendar shows it (D-087): one column per day, one grid row per five
// minutes, every session a block from its start to its end on Rome's clock. Only drawing: which sessions, of which
// days, with which words, is decided by the page
export function WeekGrid({ days, today, events }: { days: Day[]; today: Day; events: GridEvent[] }) {
  const placed = days.map((day) =>
    lanesOf(
      events
        .filter((event) => romeDay(event.startsAt) === day)
        .map((event) => {
          const start = romeMinutes(event.startsAt);
          // never past the bottom of its column (a session over midnight does not exist in the studio's hours)
          return { event, start, end: Math.min(start + minutesBetween(event.startsAt, event.endsAt), 24 * 60) };
        }),
    ),
  );
  const range = hourRange(placed.flat().map((entry) => entry.item));
  const rows = ((range.to - range.from) * 60) / UNIT_MINUTES;
  const rowOf = (minutes: number) => HEAD_ROWS + 1 + Math.round((minutes - range.from * 60) / UNIT_MINUTES);
  const hours = Array.from({ length: range.to - range.from }, (_, index) => range.from + index);
  const todayIndex = days.indexOf(today);
  const now = todayIndex === -1 ? null : romeMinutes(new Date().toISOString());
  const style: CSSProperties = {
    gridTemplateRows: `auto repeat(${rows}, ${ROW_PX}px)`,
    gridTemplateColumns: `2.6rem repeat(${days.length}, minmax(0, 1fr))`,
  };

  return (
    <div className={days.length === 1 ? "week week-single" : "week"} style={style}>
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
          const { event } = item;
          const startRow = rowOf(item.start);
          const endRow = Math.max(startRow + 1, rowOf(item.end));
          const flag = event.flag === undefined ? "" : `, ${event.flag}`;
          return (
            <Link
              key={event.key}
              href={event.href}
              className={`event event-${event.tone}${event.flag === undefined ? "" : " event-flagged"}`}
              style={{ gridColumn: index + 2, gridRow: `${startRow} / ${endRow}`, left: `${(lane / lanes) * 100}%`, width: `calc(${100 / lanes}% - 2px)` }}
              aria-label={`${formatDayWithWeekday(day)}, ${formatTime(event.startsAt)}–${formatTime(event.endsAt)}, ${event.what}, ${event.status}${flag}`}
            >
              <span className="event-time">
                {event.flag === undefined ? null : <span aria-hidden="true">⚠ </span>}
                {formatTime(event.startsAt)}
              </span>
              <span className="event-what">{event.what}</span>
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
