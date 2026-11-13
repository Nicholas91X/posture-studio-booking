// The states of the backend in plain words (step 12). Record<...>: when the contract gains a state, this file stops
// compiling until the new state has its words. The states are decided by the backend (D-005, D-074): here only names.
import type { Schemas } from "@/api/client";

export type BookingStatus = Schemas["CustomerBookingView"]["status"];
export type PaidWith = Schemas["CustomerBookingView"]["paidWith"];
export type LotStatus = Schemas["CreditLotSummary"]["status"];
export type LotSource = Schemas["CreditLotSummary"]["source"];
export type EntryKind = Schemas["CreditEntry"]["kind"];
export type Tone = "neutral" | "success" | "warning" | "danger";

export const BOOKING_STATUS: Record<BookingStatus, { text: string; tone: Tone }> = {
  PENDING_PAYMENT: { text: "In attesa di pagamento", tone: "warning" },
  PENDING_APPROVAL: { text: "In attesa di approvazione", tone: "warning" },
  CONFIRMED: { text: "Confermata", tone: "success" },
  COMPLETED: { text: "Svolta", tone: "neutral" },
  NO_SHOW: { text: "Non presentato", tone: "danger" },
  CANCELLED_IN_TIME: { text: "Cancellata da te", tone: "neutral" },
  CANCELLED_LATE: { text: "Cancellata da te in ritardo", tone: "danger" },
  CANCELLED_BY_STUDIO: { text: "Cancellata dallo studio", tone: "neutral" },
  EXPIRED: { text: "Pagamento non completato", tone: "neutral" },
  REJECTED: { text: "Non accettata", tone: "neutral" },
  APPROVAL_EXPIRED: { text: "Nessuna risposta in tempo", tone: "neutral" },
  WITHDRAWN: { text: "Ritirata da te", tone: "neutral" },
};

// Which states the week and month views draw (D-087): a session that is no longer an appointment is not an entry in a
// calendar. What a view shows, not a rule: the states are the backend's, and the list shows them all. Record<...>:
// a new state in the contract must be decided here
export const SHOWN_IN_CALENDAR: Record<BookingStatus, boolean> = {
  PENDING_PAYMENT: true,
  PENDING_APPROVAL: true,
  CONFIRMED: true,
  COMPLETED: true,
  NO_SHOW: true,
  CANCELLED_IN_TIME: false,
  CANCELLED_LATE: false,
  CANCELLED_BY_STUDIO: false,
  EXPIRED: false,
  REJECTED: false,
  APPROVAL_EXPIRED: false,
  WITHDRAWN: false,
};

export const PAID_WITH: Record<PaidWith, string> = {
  CARD: "Pagata con carta",
  CREDIT: "Pagata con un credito",
  IN_STUDIO: "Da pagare in studio",
};

export const LOT_STATUS: Record<LotStatus, { text: string; tone: Tone }> = {
  USABLE: { text: "Utilizzabile", tone: "success" },
  USED_UP: { text: "Esaurito", tone: "neutral" },
  EXPIRED: { text: "Scaduto", tone: "neutral" },
  PRACTITIONER_UNAVAILABLE: { text: "Non utilizzabile", tone: "danger" },
};

export const LOT_SOURCE: Record<LotSource, string> = {
  PACKAGE: "Pacchetto acquistato",
  GRANT: "Omaggio dello studio",
  CANCELLATION: "Da una seduta cancellata",
};

export const ENTRY_KIND: Record<EntryKind, string> = {
  LOT_CREATED: "Crediti ricevuti",
  BOOKING: "Usato per una prenotazione",
  BOOKING_RELEASE: "Tornato: la richiesta non è andata a buon fine",
  ADJUSTMENT: "Correzione dello studio",
  CANCELLATION_RESTORE: "Tornato per una seduta cancellata",
  EXTENSION: "Scadenza prorogata",
};

export function badgeClass(tone: Tone): string {
  return tone === "neutral" ? "badge" : `badge badge-${tone}`;
}
