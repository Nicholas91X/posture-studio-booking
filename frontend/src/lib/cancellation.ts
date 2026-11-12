// What the customer reads BEFORE confirming a cancellation (step 12, D-079). The sentence is built from the data of
// the backend, with no rule of its own (D-005):
// - freeCancellationUntil, the notice photographed when the booking was made: in time until that instant included;
// - paidWith, how the session was paid.
// The backend decides again at the click, with its clock: the answer (status and returnedCredit) is what happened,
// and the page shows that (cancellationResult), even when it differs from this preview by a second.
import type { Schemas } from "@/api/client";
import { formatCalendarDay, formatDateTime } from "./format";

type Booking = Schemas["CustomerBookingView"];

export type CancellationPreview = { inTime: boolean; title: string; text: string };

export function cancellationPreview(booking: Booking, now: Date = new Date()): CancellationPreview {
  const until = formatDateTime(booking.freeCancellationUntil);
  const inTime = now.getTime() <= new Date(booking.freeCancellationUntil).getTime();
  if (inTime) {
    switch (booking.paidWith) {
      case "CREDIT":
        return {
          inTime,
          title: "Sei in tempo",
          text: "Il credito che avevi usato ti torna disponibile, e potrai usarlo per un'altra seduta.",
        };
      case "CARD":
        return {
          inTime,
          title: "Sei in tempo",
          text: "Ricevi un credito per una seduta di questo servizio, da usare per una prossima prenotazione. Il pagamento non viene rimborsato.",
        };
      case "IN_STUDIO":
        return { inTime, title: "Sei in tempo", text: "Non avevi ancora pagato: la seduta si cancella senza conseguenze." };
    }
  }
  return {
    inTime,
    title: "Fuori tempo",
    text:
      booking.paidWith === "IN_STUDIO"
        ? `Si poteva cancellare senza conseguenze fino a ${until}. Ora la seduta risulterà cancellata in ritardo.`
        : `Si poteva cancellare senza perdere la seduta fino a ${until}. Se cancelli ora la seduta è persa e non ricevi nessun credito.`,
  };
}

/** What happened, from the answer of the backend. */
export function cancellationResult(booking: Booking): string {
  if (booking.status === "CANCELLED_IN_TIME") {
    return booking.returnedCredit === null
      ? "Seduta cancellata in tempo."
      : `Seduta cancellata in tempo. Hai un credito valido fino al ${formatCalendarDay(booking.returnedCredit.validUntil)}.`;
  }
  if (booking.status === "CANCELLED_LATE") {
    return "Seduta cancellata in ritardo: non ricevi nessun credito.";
  }
  return "La seduta è stata aggiornata.";
}
