"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { BookingCard } from "@/components/BookingCard";
import { ErrorNotice, Loading } from "@/components/Notice";
import { useDirectory } from "@/lib/directory";

type Booking = Schemas["CustomerBookingView"];
type Period = "upcoming" | "past";

// "My sessions": not over yet, the soonest first; or over, the latest first (D-082). The split is the backend's
export function BookingsClient() {
  const [period, setPeriod] = useState<Period>("upcoming");
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
      <h1>Le mie sedute</h1>
      <div className="tabs" role="tablist" aria-label="Periodo">
        <button type="button" role="tab" className="tab" aria-selected={period === "upcoming"} onClick={() => setPeriod("upcoming")}>
          Prossime
        </button>
        <button type="button" role="tab" className="tab" aria-selected={period === "past"} onClick={() => setPeriod("past")}>
          Passate
        </button>
      </div>
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
