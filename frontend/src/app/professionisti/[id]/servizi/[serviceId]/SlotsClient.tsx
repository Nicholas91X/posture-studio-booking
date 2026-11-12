"use client";

import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { api, ApiError, unwrap, type Schemas } from "@/api/client";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { PractitionerFacts } from "@/components/PractitionerFacts";
import { loadCatalog, loadPractitioners, type PublicPractitioner } from "@/lib/directory";
import { addDays, dayParts, formatDateTime, formatLongDay, formatPrice, formatTime, fullName, romeDay, todayInRome } from "@/lib/format";
import { signInPath } from "@/lib/navigation";
import { isCustomer, useSession } from "@/lib/session";

type Slot = Schemas["PublicSlot"];
type Service = Schemas["ServiceItem"];

// How far the page asks: the backend cuts the answer to the booking horizon of the practitioner (D-038), so this is
// only an upper bound, never the rule
const DAYS_ASKED = 92;

export function SlotsClient() {
  const params = useParams<{ id: string; serviceId: string }>();
  const practitionerId = Number(params.id);
  const serviceId = Number(params.serviceId);
  const preselected = useSearchParams().get("inizio");
  const router = useRouter();
  const { session } = useSession();

  const [practitioner, setPractitioner] = useState<PublicPractitioner | null | undefined>(undefined);
  const [service, setService] = useState<Service | null | undefined>(undefined);
  const [slots, setSlots] = useState<Slot[] | null>(null);
  const [day, setDay] = useState<string | null>(null);
  const [chosen, setChosen] = useState<Slot | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [sending, setSending] = useState(false);

  useEffect(() => {
    Promise.all([loadPractitioners(), loadCatalog(practitionerId)]).then(([list, catalog]) => {
      setPractitioner(list.find((one) => one.id === practitionerId) ?? null);
      setService(catalog?.services.find((one) => one.id === serviceId) ?? null);
    }, setError);
  }, [practitionerId, serviceId]);

  const readSlots = useCallback(async () => {
    const from = todayInRome();
    const found = await unwrap(
      api.GET("/api/public/practitioners/{id}/services/{serviceId}/slots", {
        params: { path: { id: practitionerId, serviceId }, query: { from, to: addDays(from, DAYS_ASKED) } },
      }),
    );
    setSlots(found);
    return found;
  }, [practitionerId, serviceId]);

  useEffect(() => {
    readSlots().then((found) => {
      // back from the sign-in with the time chosen before: still free?
      const again = preselected === null ? undefined : found.find((slot) => slot.start === preselected);
      if (again !== undefined) {
        setChosen(again);
        setDay(romeDay(again.start));
      }
    }, setError);
  }, [readSlots, preselected]);

  // the free slots grouped by day in Rome, in the order the backend gives them
  const days = useMemo(() => {
    const grouped = new Map<string, Slot[]>();
    for (const slot of slots ?? []) {
      const key = romeDay(slot.start);
      grouped.set(key, [...(grouped.get(key) ?? []), slot]);
    }
    return grouped;
  }, [slots]);
  const shownDay = day !== null && days.has(day) ? day : (days.keys().next().value ?? null);

  async function book(slot: Slot) {
    if (!isCustomer(session)) {
      router.push(signInPath(`/professionisti/${practitionerId}/servizi/${serviceId}?inizio=${encodeURIComponent(slot.start)}`));
      return;
    }
    setSending(true);
    setError(null);
    try {
      const booking = await unwrap(
        api.POST("/api/customer/bookings", { body: { practitionerId, serviceId, startsAt: slot.start } }),
      );
      if (booking.paymentUrl !== null) {
        // card: to Stripe's page, where the amount is held (D-031); the slot is held meanwhile (D-061)
        window.location.assign(booking.paymentUrl);
        return;
      }
      // a credit: the booking is already waiting for approval, or confirmed (D-071)
      router.push(`/prenotazioni/${booking.id}?nuova=1`);
    } catch (failure) {
      setSending(false);
      setError(failure);
      if (failure instanceof ApiError && failure.is("SLOT_NOT_AVAILABLE")) {
        // taken meanwhile: the free slots of now, and another choice
        setChosen(null);
        void readSlots().catch(() => undefined);
      } else if (failure instanceof ApiError && failure.is("PROFILE_INCOMPLETE")) {
        router.push(signInPath(`/professionisti/${practitionerId}/servizi/${serviceId}`));
      }
    }
  }

  if (practitioner === undefined || service === undefined) {
    return error === null ? <Loading /> : <ErrorNotice error={error} />;
  }
  if (practitioner === null || service === null) {
    return (
      <Notice tone="warning">
        <p>Questo servizio non è prenotabile.</p>
        <p>
          <Link href="/">Torna all&apos;elenco</Link>
        </p>
      </Notice>
    );
  }

  const limitReached = error instanceof ApiError && error.is("BOOKING_LIMIT_REACHED");

  return (
    <>
      <Link href={`/professionisti/${practitionerId}`} className="back">
        ‹ {fullName(practitioner)}
      </Link>
      <h1>{service.name}</h1>
      <p className="muted">
        {service.durationMinutes} minuti · {formatPrice(service.priceCents)}
      </p>
      <PractitionerFacts practitioner={practitioner} />

      <ErrorNotice error={error} />
      {limitReached ? (
        <p>
          <Link href="/prenotazioni">Vedi le tue sedute</Link>
        </p>
      ) : null}

      {slots === null ? <Loading label="Cerco gli orari liberi…" /> : null}
      {slots !== null && slots.length === 0 ? (
        <Notice>
          <p>Non ci sono orari liberi nei prossimi giorni. Riprova più avanti.</p>
        </Notice>
      ) : null}

      {shownDay !== null ? (
        <>
          <h2>Scegli il giorno</h2>
          <div className="days" role="group" aria-label="Giorni con orari liberi">
            {[...days.keys()].map((key) => {
              const first = days.get(key)?.[0];
              if (first === undefined) {
                return null;
              }
              const parts = dayParts(first.start);
              return (
                <button
                  key={key}
                  type="button"
                  className="day"
                  aria-pressed={key === shownDay}
                  aria-label={formatLongDay(first.start)}
                  onClick={() => {
                    setDay(key);
                    setChosen(null);
                  }}
                >
                  <span className="small">{parts.weekday}</span>
                  <strong>{parts.day}</strong>
                  <span className="small">{parts.month}</span>
                </button>
              );
            })}
          </div>

          <h2>{formatLongDay(days.get(shownDay)?.[0]?.start ?? "")}</h2>
          <div className="slots" role="group" aria-label="Orari liberi">
            {(days.get(shownDay) ?? []).map((slot) => (
              <button
                key={slot.start}
                type="button"
                className="slot"
                aria-pressed={chosen?.start === slot.start}
                onClick={() => {
                  setChosen(slot);
                  setError(null);
                }}
              >
                {formatTime(slot.start)}
              </button>
            ))}
          </div>
        </>
      ) : null}

      {chosen !== null ? (
        <div
          className="card"
          style={{ marginTop: "1rem", scrollMarginBottom: "5rem" }}
          aria-live="polite"
          // on a phone the summary is below the slots: brought into view when a time is chosen
          ref={(card) => card?.scrollIntoView({ behavior: "smooth", block: "nearest" })}
        >
          <h2>Riepilogo</h2>
          <p>
            <strong>{service.name}</strong> con {fullName(practitioner)}
            <br />
            {formatDateTime(chosen.start)} – {formatTime(chosen.end)}
          </p>
          <p className="small">
            {practitioner.onlineBooking
              ? `Se hai un credito valido per questa seduta si usa quello e non paghi niente; altrimenti paghi ${formatPrice(service.priceCents)} con carta sulla pagina sicura di Stripe.`
              : "Si usa un tuo credito valido per questa seduta: non c'è niente da pagare."}
            {practitioner.bookingApproval === "MANUAL" && practitioner.onlineBooking
              ? " Con la carta l'importo è solo bloccato: lo addebitiamo quando il professionista approva."
              : ""}
          </p>
          <button type="button" className="button button-block" disabled={sending} onClick={() => void book(chosen)}>
            {sending ? "Invio…" : isCustomer(session) ? "Prenota" : "Accedi per prenotare"}
          </button>
        </div>
      ) : null}
    </>
  );
}
