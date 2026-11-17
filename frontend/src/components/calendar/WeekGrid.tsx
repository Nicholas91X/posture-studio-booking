import Link from "next/link";
import type { CSSProperties, ReactNode } from "react";
import { hourRange, lanesOf, minutesBetween, romeMinutes, type Day, type Interval } from "@/lib/calendar";
import { formatDayWithWeekday, formatTime, formatWeekday, romeDay } from "@/lib/format";
import type { Tone } from "@/lib/labels";

/** One block of the grid: a session of the customer (step 12) or of the practitioner (step 13), or busy time (13b). */
export type GridEvent = {
  key: number | string;
  startsAt: string;
  endsAt: string;
  // a link to the session's page; none for time that is only "busy" (D-091)
  href?: string;
  tone: Tone;
  // what the block says, e.g. "Seduta con Anna Rossi" or "Mario Bianchi"
  what: string;
  // the state in words, for the screen reader
  status: string;
  // a mark on the block: the panel's flags (outside working hours, evaluation in the room, D-049)
  flag?: string;
  // the column it belongs to; by default its day in Rome (the week of "my sessions" and of the agenda)
  column?: string;
};

/** A background band: working hours, an absence, a window free together (D-091). Drawn under the blocks. */
export type GridShade = { key: string; column: string; startsAt: string; endsAt: string; kind: "work" | "away" | "together" };

/** A column of the grid: a day, or a practitioner on a day (the agendas compared). */
export type GridColumn = { key: string; day: Day; head: ReactNode; label: string };

const UNIT_MINUTES = 5; // one grid row
const ROW_PX = 4; // 48 px per hour
const HEAD_ROWS = 1; // the first grid row is the column names

/** The columns of a week, or of a single day: one per day, named by the day. */
export function dayColumns(days: Day[]): GridColumn[] {
  return days.map((day) => ({
    key: day,
    day,
    label: formatDayWithWeekday(day),
    head: (
      <>
        <span>{formatWeekday(day)}</span> <strong>{Number(day.slice(8))}</strong>
      </>
    ),
  }));
}

function minutesOf(startsAt: string, endsAt: string): Interval {
  const start = romeMinutes(startsAt);
  // never past the bottom of its column (a session over midnight does not exist in the studio's hours)
  return { start, end: Math.min(start + minutesBetween(startsAt, endsAt), 24 * 60) };
}

// The week (or a day, or the practitioners of a day) as the phone's calendar shows it (D-087): one column each, one
// grid row per five minutes, every block from its start to its end on Rome's clock. Only drawing: which blocks, of
// which columns, with which words, is decided by the page
export function WeekGrid({
  days,
  columns: given,
  today,
  events,
  shades = [],
}: {
  days?: Day[];
  columns?: GridColumn[];
  today: Day;
  events: GridEvent[];
  shades?: GridShade[];
}) {
  const columns = given ?? dayColumns(days ?? []);
  const columnOf = (event: GridEvent) => event.column ?? romeDay(event.startsAt);
  const placed = columns.map((column) =>
    lanesOf(
      events
        .filter((event) => columnOf(event) === column.key)
        .map((event) => ({ event, ...minutesOf(event.startsAt, event.endsAt) })),
    ),
  );
  const drawnShades = shades
    .map((shade) => ({ shade, index: columns.findIndex((column) => column.key === shade.column), ...minutesOf(shade.startsAt, shade.endsAt) }))
    .filter((entry) => entry.index !== -1);
  const range = hourRange([...placed.flat().map((entry) => entry.item), ...drawnShades]);
  const rows = ((range.to - range.from) * 60) / UNIT_MINUTES;
  const rowOf = (minutes: number) => HEAD_ROWS + 1 + Math.round((minutes - range.from * 60) / UNIT_MINUTES);
  const clamp = (minutes: number) => Math.min(Math.max(minutes, range.from * 60), range.to * 60);
  const hours = Array.from({ length: range.to - range.from }, (_, index) => range.from + index);
  const todayIndex = columns.findIndex((column) => column.day === today);
  const now = todayIndex === -1 ? null : romeMinutes(new Date().toISOString());
  const style: CSSProperties = {
    gridTemplateRows: `auto repeat(${rows}, ${ROW_PX}px)`,
    gridTemplateColumns: `2.6rem repeat(${columns.length}, minmax(0, 1fr))`,
  };
  const single = columns.length === 1;

  return (
    <div className={single ? "week week-single" : "week"} style={style}>
      <div className="week-corner" aria-hidden="true" />
      {columns.map((column, index) => (
        <div key={column.key} className={column.day === today && !given ? "week-head is-today" : "week-head"} style={{ gridColumn: index + 2 }}>
          {column.head}
        </div>
      ))}
      {columns.map((column, index) => (
        <div
          key={column.key}
          className={column.day === today && !given ? "week-col is-today" : "week-col"}
          style={{ gridColumn: index + 2 }}
          aria-hidden="true"
        />
      ))}
      {drawnShades.map(({ shade, index, start, end }) => {
        const from = clamp(start);
        const to = clamp(end);
        return to > from ? (
          <div
            key={shade.key}
            className={`shade shade-${shade.kind}`}
            style={{ gridColumn: index + 2, gridRow: `${rowOf(from)} / ${Math.max(rowOf(from) + 1, rowOf(to))}` }}
            aria-hidden="true"
          />
        ) : null;
      })}
      {hours.map((hour) => (
        <div key={hour} className="week-hour" style={{ gridRow: `${rowOf(hour * 60)} / span ${60 / UNIT_MINUTES}` }} aria-hidden="true">
          {hour}:00
        </div>
      ))}
      {columns.map((column, index) =>
        (placed[index] ?? []).map(({ item, lane, lanes }) => {
          const { event } = item;
          const startRow = rowOf(item.start);
          const endRow = Math.max(startRow + 1, rowOf(item.end));
          const flag = event.flag === undefined ? "" : `, ${event.flag}`;
          const className = `event event-${event.tone}${event.flag === undefined ? "" : " event-flagged"}`;
          const blockStyle: CSSProperties = {
            gridColumn: index + 2,
            gridRow: `${startRow} / ${endRow}`,
            left: `${(lane / lanes) * 100}%`,
            width: `calc(${100 / lanes}% - 2px)`,
          };
          const label = `${column.label}, ${formatTime(event.startsAt)}–${formatTime(event.endsAt)}, ${event.what}, ${event.status}${flag}`;
          const content = (
            <>
              <span className="event-time">
                {event.flag === undefined ? null : <span aria-hidden="true">⚠ </span>}
                {formatTime(event.startsAt)}
              </span>
              <span className="event-what">{event.what}</span>
            </>
          );
          return event.href === undefined ? (
            <div key={event.key} className={className} style={blockStyle} role="img" aria-label={label}>
              {content}
            </div>
          ) : (
            <Link key={event.key} href={event.href} className={className} style={blockStyle} aria-label={label}>
              {content}
            </Link>
          );
        }),
      )}
      {now !== null && now >= range.from * 60 && now < range.to * 60 ? (
        <div className="week-now" style={{ gridColumn: given ? `2 / span ${columns.length}` : todayIndex + 2, gridRow: rowOf(now) }} aria-hidden="true" />
      ) : null}
    </div>
  );
}
