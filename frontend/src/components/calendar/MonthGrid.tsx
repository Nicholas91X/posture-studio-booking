import Link from "next/link";
import type { Schemas } from "@/api/client";
import { weekOf, type Day } from "@/lib/calendar";
import type { Directory } from "@/lib/directory";
import { formatDayWithWeekday, formatTime, formatWeekday, romeDay, sessionsText } from "@/lib/format";
import { BOOKING_STATUS, badgeClass } from "@/lib/labels";

type Booking = Schemas["CustomerBookingView"];

const MAX_DOTS = 4;
// "lun … dom", from a week known to start on a Monday
const WEEKDAYS = weekOf("2024-01-01").map(formatWeekday);

// The month as a classic grid (D-087): a dot per session in the colour of its state, the chosen day listed below.
// Every cell is a link that puts its day in the address, so that coming back keeps it
export function MonthGrid({
  first,
  cells,
  chosen,
  today,
  bookings,
  directory,
}: {
  first: Day;
  cells: Day[];
  chosen: Day | null;
  today: Day;
  bookings: Booking[];
  directory: Directory;
}) {
  const byDay = new Map<Day, Booking[]>();
  for (const booking of [...bookings].sort((a, b) => a.startsAt.localeCompare(b.startsAt))) {
    const day = romeDay(booking.startsAt);
    byDay.set(day, [...(byDay.get(day) ?? []), booking]);
  }
  const month = first.slice(0, 7);
  const ofChosen = chosen === null ? [] : (byDay.get(chosen) ?? []);

  return (
    <>
      <div className="month">
        {WEEKDAYS.map((name) => (
          <div key={name} className="month-weekday" aria-hidden="true">
            {name}
          </div>
        ))}
        {cells.map((day) => {
          const ofDay = byDay.get(day) ?? [];
          const classes = ["month-day", day.slice(0, 7) === month ? "" : "is-outside", day === today ? "is-today" : ""].filter(Boolean).join(" ");
          const count = ofDay.length === 0 ? "nessuna seduta" : sessionsText(ofDay.length);
          return (
            <Link
              key={day}
              href={`/prenotazioni?vista=mese&da=${first}&giorno=${day}`}
              className={classes}
              aria-current={day === chosen ? "true" : undefined}
              aria-label={`${day === today ? "Oggi, " : ""}${formatDayWithWeekday(day)}, ${count}`}
            >
              <span className="month-number">{Number(day.slice(8))}</span>
              <span className="month-dots" aria-hidden="true">
                {ofDay.slice(0, MAX_DOTS).map((booking) => (
                  <i key={booking.id} className={`dot dot-${BOOKING_STATUS[booking.status].tone}`} />
                ))}
                {ofDay.length > MAX_DOTS ? <span className="month-more">+{ofDay.length - MAX_DOTS}</span> : null}
              </span>
            </Link>
          );
        })}
      </div>

      {chosen === null ? (
        <p className="muted">Tocca un giorno per vedere le sue sedute.</p>
      ) : (
        <section>
          <h2>{formatDayWithWeekday(chosen)}</h2>
          {ofChosen.length === 0 ? (
            <p className="muted">Nessuna seduta.</p>
          ) : (
            <ul className="day-list">
              {ofChosen.map((booking) => {
                const status = BOOKING_STATUS[booking.status];
                return (
                  <li key={booking.id}>
                    <Link href={`/prenotazioni/${booking.id}`} className="day-row">
                      <span className="day-row-time">
                        {formatTime(booking.startsAt)}–{formatTime(booking.endsAt)}
                      </span>
                      <span className="day-row-what">
                        {directory.serviceName(booking.practitionerId, booking.serviceId)} con {directory.practitionerName(booking.practitionerId)}
                      </span>
                      <span className={badgeClass(status.tone)}>{status.text}</span>
                    </Link>
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      )}
    </>
  );
}
