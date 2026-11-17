"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { api, unwrap } from "@/api/client";
import { PeriodNav } from "@/components/calendar/PeriodNav";
import { WeekGrid, type GridEvent } from "@/components/calendar/WeekGrid";
import { ErrorNotice, Loading } from "@/components/Notice";
import { RowCard } from "@/components/panel/RowCard";
import { isDay, mondayOf, weekOf, weekTitle, type Day } from "@/lib/calendar";
import { addDays, formatDayWithWeekday, todayInRome } from "@/lib/format";
import { SHOWN_IN_CALENDAR } from "@/lib/labels";
import { customerName, loadOwnServices, PANEL_BOOKING_STATUS, rowFlags, serviceNameIn, useApi, type PanelRow, type ServiceOffer } from "@/lib/panel";

type View = "giorno" | "settimana";

// The practitioner's agenda (step 13): a day or a week of their own sessions, with the flags the backend computes
// (D-049). As "my sessions" (D-087): the view and the day live in the address, so back, reload and a saved link work
export function AgendaClient() {
  const query = useSearchParams();
  const view: View = query.get("vista") === "settimana" ? "settimana" : "giorno";
  const today = todayInRome();
  const from = query.get("da");
  const day: Day = isDay(from) ? from : today;
  const monday = mondayOf(day);
  const days = view === "giorno" ? [day] : weekOf(monday);
  const first = days[0] as Day;
  const last = days[days.length - 1] as Day;

  const bookings = useApi(() => unwrap(api.GET("/api/panel/bookings", { params: { query: { from: first, to: last } } })), [first, last]);
  const requests = useApi(() => unwrap(api.GET("/api/panel/bookings/requests")), []);
  const services = useApi(() => loadOwnServices(), []);

  const link = (to: View, at: Day | null) => {
    const parts = to === "settimana" ? ["vista=settimana"] : [];
    if (at !== null) {
      parts.push(`da=${at}`);
    }
    return parts.length === 0 ? "/pannello" : `/pannello?${parts.join("&")}`;
  };

  return (
    <>
      <h1>Agenda</h1>
      {requests.data !== null && requests.data.length > 0 ? (
        <Link href="/pannello/richieste" className="notice notice-warning card-link">
          <strong>
            {requests.data.length === 1 ? "1 richiesta da approvare" : `${requests.data.length} richieste da approvare`}
          </strong>{" "}
          ›
        </Link>
      ) : null}

      <div className="tabs" role="tablist" aria-label="Vista">
        <Link role="tab" aria-selected={view === "giorno"} className="tab" href={link("giorno", day === today ? null : day)}>
          Giorno
        </Link>
        <Link role="tab" aria-selected={view === "settimana"} className="tab" href={link("settimana", monday === mondayOf(today) ? null : monday)}>
          Settimana
        </Link>
      </div>

      {view === "giorno" ? (
        <PeriodNav
          unit="giorno"
          title={formatDayWithWeekday(day)}
          previous={link("giorno", addDays(day, -1))}
          next={link("giorno", addDays(day, 1))}
          today={day === today ? null : "/pannello"}
        />
      ) : (
        <PeriodNav
          unit="settimana"
          title={weekTitle(monday)}
          previous={link("settimana", addDays(monday, -7))}
          next={link("settimana", addDays(monday, 7))}
          today={monday === mondayOf(today) ? null : link("settimana", null)}
        />
      )}

      <ErrorNotice error={bookings.error} />
      {bookings.loading ? <Loading /> : null}
      {bookings.data !== null ? (
        <Agenda view={view} days={days} today={today} rows={bookings.data} services={services.data} />
      ) : null}

      <div className="stack">
        <Link href={`/pannello/prenota${day === today ? "" : `?giorno=${day}`}`} className="button button-block">
          Prenota per un cliente
        </Link>
        <Link href={`/pannello/agende${day === today ? "" : `?giorno=${day}`}`} className="button button-secondary button-block">
          Agende a confronto
        </Link>
      </div>
    </>
  );
}

function Agenda({
  view,
  days,
  today,
  rows,
  services,
}: {
  view: View;
  days: Day[];
  today: Day;
  rows: PanelRow[];
  services: ServiceOffer[] | null;
}) {
  const events: GridEvent[] = rows
    .filter((row) => SHOWN_IN_CALENDAR[row.status])
    .map((row) => {
      const flags = rowFlags(row);
      return {
        key: row.id,
        startsAt: row.startsAt,
        endsAt: row.endsAt,
        href: `/pannello/sedute/${row.id}`,
        tone: PANEL_BOOKING_STATUS[row.status].tone,
        what: `${customerName(row)} · ${serviceNameIn(services, row.serviceId)}`,
        status: PANEL_BOOKING_STATUS[row.status].text,
        ...(flags.length > 0 ? { flag: flags.join(", ") } : {}),
      };
    });
  const empty = rows.length === 0;
  return (
    <>
      <WeekGrid days={days} today={today} events={events} />
      {empty ? <p className="muted">{view === "giorno" ? "Nessuna seduta in questo giorno." : "Nessuna seduta in questa settimana."}</p> : null}
      {/* the day as a list too: the names in full, the cancelled ones, the flags in words */}
      {view === "giorno" ? rows.map((row) => <RowCard key={row.id} row={row} services={services} />) : null}
    </>
  );
}
