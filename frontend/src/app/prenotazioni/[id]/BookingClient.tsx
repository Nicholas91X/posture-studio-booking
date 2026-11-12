"use client";

import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { BookingCard } from "@/components/BookingCard";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { useDirectory } from "@/lib/directory";
import { formatDateTime } from "@/lib/format";
import { pollUntil } from "@/lib/poll";

type Booking = Schemas["CustomerBookingView"];
type Waiting = "no" | "yes" | "timed-out";

const POLL_EVERY_MS = 2000;
const POLL_FOR_MS = 60_000;

export function BookingClient() {
  const id = Number(useParams<{ id: string }>().id);
  const query = useSearchParams();
  const payment = query.get("pagamento");
  const isNew = query.get("nuova") === "1";

  const [booking, setBooking] = useState<Booking | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [waiting, setWaiting] = useState<Waiting>("no");
  const directory = useDirectory(booking === null ? [] : [booking.practitionerId]);

  const read = useCallback(
    () => unwrap(api.GET("/api/customer/bookings/{id}", { params: { path: { id } } })),
    [id],
  );

  // Back from Stripe after paying: the return proves nothing, the webhook confirms (D-007). While the booking
  // still waits for its payment, read it again, for a minute at most
  const waitForPayment = useCallback(
    async (signal?: AbortSignal) => {
      setWaiting("yes");
      const outcome = await pollUntil(read, (found) => found.status !== "PENDING_PAYMENT", {
        everyMs: POLL_EVERY_MS,
        forMs: POLL_FOR_MS,
        ...(signal === undefined ? {} : { signal }),
      });
      if (signal?.aborted) {
        return;
      }
      if (outcome.value !== undefined) {
        setBooking(outcome.value);
      }
      setWaiting(outcome.done ? "no" : "timed-out");
    },
    [read],
  );

  useEffect(() => {
    const abort = new AbortController();
    read().then((found) => {
      if (abort.signal.aborted) {
        return;
      }
      setBooking(found);
      if (payment === "completato" && found.status === "PENDING_PAYMENT") {
        void waitForPayment(abort.signal);
      }
    }, setError);
    return () => abort.abort();
  }, [read, payment, waitForPayment]);

  if (booking === null) {
    return error === null ? <Loading /> : <ErrorNotice error={error} />;
  }

  return (
    <>
      <Link href="/prenotazioni" className="back">
        ‹ Le mie sedute
      </Link>
      <h1>La tua seduta</h1>

      {waiting === "yes" ? (
        <Notice>
          <p>
            <span className="spinner" aria-hidden="true" /> Pagamento in verifica: aspettiamo la conferma di Stripe…
          </p>
        </Notice>
      ) : null}
      {waiting === "timed-out" ? (
        <Notice tone="warning">
          <p>
            La conferma del pagamento non è ancora arrivata. Se hai pagato, non pagare di nuovo: a volte serve qualche minuto,
            e quando arriva ti mandiamo un&apos;email.
          </p>
          <button type="button" className="button button-secondary" onClick={() => void waitForPayment()}>
            Controlla di nuovo
          </button>
        </Notice>
      ) : null}
      {waiting === "no" ? <ArrivalMessage booking={booking} payment={payment} isNew={isNew} /> : null}

      <BookingCard booking={booking} directory={directory} onChange={setBooking} linkToDetail={false} />
    </>
  );
}

// What the page says when the customer arrives: back from Stripe, or just booked with a credit
function ArrivalMessage({ booking, payment, isNew }: { booking: Booking; payment: string | null; isNew: boolean }) {
  if (payment === "annullato" && booking.status === "PENDING_PAYMENT") {
    return (
      <Notice tone="warning">
        <p>Il pagamento non è stato completato. Puoi riprenderlo finché l&apos;orario è tenuto per te.</p>
      </Notice>
    );
  }
  if (payment === null && !isNew) {
    return null;
  }
  const credit = booking.paidWith === "CREDIT";
  switch (booking.status) {
    case "PENDING_APPROVAL":
      return (
        <Notice tone="success">
          <p>
            {credit
              ? "Richiesta inviata: hai usato un tuo credito."
              : "Pagamento ricevuto: l'importo è bloccato sulla carta e viene addebitato solo se il professionista approva."}
            {booking.approvalDeadline !== null ? ` La risposta arriva entro ${formatDateTime(booking.approvalDeadline)}.` : ""}
          </p>
        </Notice>
      );
    case "CONFIRMED":
      return (
        <Notice tone="success">
          <p>{credit ? "Seduta confermata: hai usato un tuo credito." : "Pagamento ricevuto: la seduta è confermata."}</p>
        </Notice>
      );
    case "EXPIRED":
      return (
        <Notice tone="warning">
          <p>
            Il tempo per pagare è scaduto e l&apos;orario è tornato libero. Se il pagamento è partito lo stesso, l&apos;importo
            si sblocca da solo. <Link href="/">Prenota un altro orario</Link>.
          </p>
        </Notice>
      );
    default:
      return null;
  }
}
