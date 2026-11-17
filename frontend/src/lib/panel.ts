// What the pages of the panel share (step 13): reading data, the names of the practitioner's own services, the states
// as the practitioner reads them. Only showing and sending: every rule is the backend's (D-005).
import { useCallback, useEffect, useRef, useState } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import type { BookingStatus, Tone } from "./labels";

export type PanelRow = Schemas["PanelBookingRow"];
export type PanelBooking = Schemas["PanelBookingView"];
export type ServiceOffer = Schemas["ServiceOfferView"];

/** Data read when the page opens (and again on reload()), with its error; set() replaces it after an action. */
export function useApi<T>(load: () => Promise<T>, deps: readonly unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [round, setRound] = useState(0);
  const loader = useRef(load);
  loader.current = load;

  useEffect(() => {
    let current = true;
    setError(null);
    loader.current().then(
      (found) => current && setData(found),
      (failure: unknown) => current && setError(failure),
    );
    return () => {
      current = false;
    };
    // the caller lists what the call depends on, as for a useEffect
  }, [...deps, round]);

  const reload = useCallback(() => setRound((value) => value + 1), []);
  return { data, error, loading: data === null && error === null, set: setData, reload };
}

/** An action of a button: running, its error, and the call. */
export function useAction() {
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const run = useCallback(async <T,>(action: () => Promise<T>): Promise<T | undefined> => {
    setRunning(true);
    setError(null);
    try {
      return await action();
    } catch (failure) {
      setError(failure);
      return undefined;
    } finally {
      setRunning(false);
    }
  }, []);
  return { running, error, setError, run };
}

let services: Promise<ServiceOffer[]> | null = null;

/** The practitioner's own services, active or not: read once per visit, shared by the pages. */
export function loadOwnServices(refresh = false): Promise<ServiceOffer[]> {
  if (refresh) {
    services = null;
  }
  services ??= unwrap(api.GET("/api/panel/services")).catch((error: unknown) => {
    services = null;
    throw error;
  });
  return services;
}

/** The name of an own service; "Seduta" if the list is not there. */
export function serviceNameIn(list: readonly ServiceOffer[] | null, serviceId: number): string {
  return list?.find((service) => service.id === serviceId)?.name ?? "Seduta";
}

/** The customer's name on a row; "Cliente" when the customer has none yet (D-050: a profile not finished). */
export function customerName(row: { customerFirstName: string | null; customerLastName: string | null }): string {
  const name = [row.customerFirstName, row.customerLastName].filter((part) => part !== null && part !== "").join(" ");
  return name === "" ? "Cliente" : name;
}

// The states as the practitioner reads them: "cancellata dal cliente" where the customer reads "da te".
// Record<...>: a new state in the contract does not compile until it has its words
export const PANEL_BOOKING_STATUS: Record<BookingStatus, { text: string; tone: Tone }> = {
  PENDING_PAYMENT: { text: "In attesa di pagamento", tone: "warning" },
  PENDING_APPROVAL: { text: "Da approvare", tone: "warning" },
  CONFIRMED: { text: "Confermata", tone: "success" },
  COMPLETED: { text: "Svolta", tone: "neutral" },
  NO_SHOW: { text: "Non presentato", tone: "danger" },
  CANCELLED_IN_TIME: { text: "Cancellata dal cliente", tone: "neutral" },
  CANCELLED_LATE: { text: "Cancellata dal cliente in ritardo", tone: "danger" },
  CANCELLED_BY_STUDIO: { text: "Cancellata dallo studio", tone: "neutral" },
  EXPIRED: { text: "Pagamento non completato", tone: "neutral" },
  REJECTED: { text: "Rifiutata", tone: "neutral" },
  APPROVAL_EXPIRED: { text: "Scaduta senza risposta", tone: "neutral" },
  WITHDRAWN: { text: "Ritirata dal cliente", tone: "neutral" },
};

/** The flags of a row in words (D-049): computed by the backend, here only said. */
export function rowFlags(row: PanelRow): string[] {
  const flags: string[] = [];
  if (row.outsideWorkingHours) {
    flags.push("Fuori orario");
  }
  if (row.evaluationOverlaps.length > 0) {
    flags.push("Valutazione nella stanza");
  }
  return flags;
}
