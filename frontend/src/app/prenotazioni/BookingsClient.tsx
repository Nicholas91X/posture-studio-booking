"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { BookingCard } from "@/components/BookingCard";
import { PeriodNav } from "@/components/calendar/PeriodNav";
import { WeekGrid } from "@/components/calendar/WeekGrid";
import { ErrorNotice, Loading } from "@/components/Notice";
import { firstOfMonth, isDay, mondayOf, weekOf, weekTitle, type Day } from "@/lib/calendar";
import { useDirectory, type Directory } from "@/lib/directory";
import { addDays, romeDay, todayInRome } from "@/lib/format";
import { SHOWN_IN_CALENDAR } from "@/lib/labels";

type Booking = Schemas["CustomerBookingView"];
type Period = "upcoming" | "past";
type View = "prossime" | "passate" | "settimana" | "mese";

const VIEWS: Record<View, string> = { prossime: "Prossime", passate: "Passate", settimana: "Settimana", mese: "Mese" };

function viewOf(text: string | null): View {
  return text !== null && text in VIEWS ? (text as View) : "prossime";
}

// "My sessions": the list (not over yet, the soonest first; or over, the latest first: the split is the backend's,
// D-082) and the week and month views (D-087). The view and the period live in the query string: reloading, coming
// back from a session's page or saving the address keeps them
export function BookingsClient() {
  const query = useSearchParams();
  const view = viewOf(query.get("vista"));
  const today = todayInRome();
  const from = query.get("da");
  const anchor: Day = isDay(from) ? from : today;
  const isCalendar = view === "settimana" || view === "mese";

  // every session once, for the grids: read the first time a grid is shown, kept while the page is open
  const [all, setAll] = useState<Booking[] | null>(null);
  const [allError, setAllError] = useState<unknown>(null);
  useEffect(() => {
    if (!isCalendar || all !== null) {
      return undefined;
    }
    let current = true;
    unwrap(api.GET("/api/customer/bookings")).then(
      (found) => current && setAll(found),
      (failure: unknown) => current && setAllError(failure),
    );
    return () => {
      current = false;
    };
  }, [isCalendar, all]);
  const shown = useMemo(() => (all ?? []).filter((booking) => SHOWN_IN_CALENDAR[booking.status]), [all]);
  const directory = useDirectory(shown.map((booking) => booking.practitionerId));

  return (
    <>
      <h1>Le mie sedute</h1>
      <div className="tabs" role="tablist" aria-label="Vista">
        {(Object.keys(VIEWS) as View[]).map((one) => (
          <Link
            key={one}
            role="tab"
            aria-selected={one === view}
            className="tab"
            href={one === "prossime" ? "/prenotazioni" : `/prenotazioni?vista=${one}`}
          >
            {VIEWS[one]}
          </Link>
        ))}
      </div>

      {view === "prossime" || view === "passate" ? <BookingList period={view === "prossime" ? "upcoming" : "past"} /> : null}

      {isCalendar ? <ErrorNotice error={allError} /> : null}
      {isCalendar && allError === null && (all === null || !directory.ready) ? <Loading /> : null}
      {view === "settimana" && all !== null && directory.ready ? (
        <WeekView monday={mondayOf(anchor)} today={today} bookings={shown} directory={directory} />
      ) : null}
      {view === "mese" && all !== null && directory.ready ? (
        <p className="muted">
          Mese di {firstOfMonth(anchor)}: {shown.length} sedute
        </p>
      ) : null}
    </>
  );
}

// the list of before, unchanged (D-082)
function BookingList({ period }: { period: Period }) {
  const [bookings, setBookings] = useState<Booking[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const directory = useDirectory(bookings?.map((booking) => booking.practitionerId) ?? []);

  useEffect(() => {
    let current = true;
    setBookings(null);
    setError(null);
    unwrap(api.GET("/api/customer/bookings", { params: { query: { period } } })).then(
      (found) => current && setBookings(found),
      (failure: unknown) => current && setError(failure),
    );
    return () => {
      current = false;
    };
  }, [period]);

  const replace = (updated: Booking) =>
    setBookings((list) => list?.map((booking) => (booking.id === updated.id ? updated : booking)) ?? null);

  return (
    <>
      <ErrorNotice error={error} />
      {bookings === null && error === null ? <Loading /> : null}
      {bookings?.length === 0 ? (
        <p className="muted">
          {period === "upcoming" ? (
            <>
              Nessuna seduta in programma. <Link href="/">Prenotane una</Link>.
            </>
          ) : (
            "Nessuna seduta passata."
          )}
        </p>
      ) : null}
      {directory.ready || bookings?.length === 0
        ? bookings?.map((booking) => <BookingCard key={booking.id} booking={booking} directory={directory} onChange={replace} />)
        : bookings !== null && bookings.length > 0
          ? <Loading />
          : null}
    </>
  );
}

// the week of a Monday: the navigation, the grid with the sessions of those seven days, a word when there are none
function WeekView({ monday, today, bookings, directory }: { monday: Day; today: Day; bookings: Booking[]; directory: Directory }) {
  const days = weekOf(monday);
  const inWeek = bookings.filter((booking) => days.includes(romeDay(booking.startsAt)));
  return (
    <>
      <PeriodNav
        unit="settimana"
        title={weekTitle(monday)}
        previous={`/prenotazioni?vista=settimana&da=${addDays(monday, -7)}`}
        next={`/prenotazioni?vista=settimana&da=${addDays(monday, 7)}`}
        today={monday === mondayOf(today) ? null : "/prenotazioni?vista=settimana"}
      />
      <WeekGrid days={days} today={today} bookings={inWeek} directory={directory} />
      {inWeek.length === 0 ? (
        <p className="muted">
          Nessuna seduta questa settimana. <Link href="/">Prenotane una</Link>.
        </p>
      ) : null}
    </>
  );
}
