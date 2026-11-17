"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { PANEL_WORDING } from "@/api/errors";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { isDay } from "@/lib/calendar";
import { formatCalendarDay, formatDateTime, formatDayWithWeekday, formatTime, romeDateTime, todayInRome } from "@/lib/format";
import { loadOwnServices, serviceNameIn, useAction, useApi } from "@/lib/panel";

type Overlap = Schemas["RoomOverlap"];
type Created = Schemas["PanelBookingCreated"];
type Check = { startsAt: string; overlaps: Overlap[]; credit: Schemas["PanelCreditCheck"] };

// A session booked by the practitioner for a customer (D-024, D-048): found or created by email, confirmed at once,
// with a credit of the customer if one covers it, otherwise paid in the studio. Working hours and absences apply, the
// grid does not: the backend decides. Before confirming, the room (D-043, D-088): only times, never who; and whether
// a credit of the customer with this practitioner would pay it, so that the practitioner chooses (D-090)
export function BookForCustomerClient() {
  const query = useSearchParams();
  const services = useApi(() => loadOwnServices(), []);
  const active = services.data?.filter((service) => service.active) ?? [];

  const [serviceId, setServiceId] = useState<number | null>(null);
  const [day, setDay] = useState(isDay(query.get("giorno")) ? (query.get("giorno") as string) : todayInRome());
  const [time, setTime] = useState("09:00");
  const [email, setEmail] = useState(query.get("email") ?? "");
  const [firstName, setFirstName] = useState(query.get("nome") ?? "");
  const [lastName, setLastName] = useState(query.get("cognome") ?? "");
  const [check, setCheck] = useState<Check | null>(null);
  const [pay, setPay] = useState<"credit" | "studio">("credit");
  const [created, setCreated] = useState<Created | null>(null);
  const checking = useAction();
  const booking = useAction();

  const chosen = serviceId ?? active[0]?.id ?? null;

  if (services.loading) {
    return <Loading />;
  }
  if (created !== null) {
    return <Booked created={created} email={email} serviceName={serviceNameIn(services.data, created.serviceId)} onAgain={() => setCreated(null)} />;
  }

  const askRoom = (event: FormEvent) => {
    event.preventDefault();
    if (chosen === null) {
      return;
    }
    const startsAt = romeDateTime(day, time);
    void checking.run(async () => {
      const [overlaps, credit] = await Promise.all([
        unwrap(api.GET("/api/panel/bookings/room-overlaps", { params: { query: { serviceId: chosen, startsAt } } })),
        unwrap(api.GET("/api/panel/bookings/credit-check", { params: { query: { serviceId: chosen, startsAt, email: email.trim() } } })),
      ]);
      setPay("credit");
      setCheck({ startsAt, overlaps, credit });
    });
  };

  const confirm = () => {
    if (chosen === null || check === null) {
      return;
    }
    void booking.run(async () => {
      const answer = await unwrap(
        api.POST("/api/panel/bookings", {
          body: {
            serviceId: chosen,
            startsAt: check.startsAt,
            customer: { email: email.trim(), firstName: firstName.trim(), lastName: lastName.trim() },
            // the choice only exists with a credit (D-090); without one the backend books it in the studio anyway
            inStudio: check.credit.usableCredit && pay === "studio",
          },
        }),
      );
      setCheck(null);
      setCreated(answer);
    });
  };

  return (
    <>
      <h1>Prenota per un cliente</h1>
      <ErrorNotice error={services.error} />
      {services.data !== null && active.length === 0 ? (
        <Notice tone="warning">
          <p>
            Non hai servizi attivi. <Link href="/pannello/catalogo">Aggiungine uno</Link> prima di prenotare.
          </p>
        </Notice>
      ) : null}
      <form className="card stack" onSubmit={askRoom}>
        <div className="field">
          <label htmlFor="service">Servizio</label>
          <select id="service" className="input" value={chosen ?? ""} onChange={(event) => setServiceId(Number(event.target.value))} required>
            {active.map((service) => (
              <option key={service.id} value={service.id}>
                {service.name} · {service.durationMinutes} min{service.evaluation ? " · valutazione" : ""}
              </option>
            ))}
          </select>
        </div>
        <div className="form-pair">
          <div className="field">
            <label htmlFor="day">Giorno</label>
            <input id="day" type="date" className="input" required min={todayInRome()} value={day} onChange={(event) => setDay(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="time">Ora</label>
            <input id="time" type="time" className="input" required step={300} value={time} onChange={(event) => setTime(event.target.value)} />
          </div>
        </div>
        <h2>Cliente</h2>
        <div className="field">
          <label htmlFor="email">Email del cliente</label>
          <input id="email" type="email" className="input" required autoComplete="off" value={email} onChange={(event) => setEmail(event.target.value)} />
          <p className="hint">Se il cliente esiste già lo ritroviamo da qui, con il nome che ha. Se non esiste, lo creiamo.</p>
        </div>
        <div className="form-pair">
          <div className="field">
            <label htmlFor="firstName">Nome</label>
            <input id="firstName" className="input" required maxLength={100} autoComplete="off" value={firstName} onChange={(event) => setFirstName(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="lastName">Cognome</label>
            <input id="lastName" className="input" required maxLength={100} autoComplete="off" value={lastName} onChange={(event) => setLastName(event.target.value)} />
          </div>
        </div>
        <ErrorNotice error={checking.error} wording={PANEL_WORDING} />
        <button type="submit" className="button button-block" disabled={checking.running || chosen === null}>
          {checking.running ? "Controllo…" : "Continua"}
        </button>
      </form>

      {check !== null && chosen !== null ? (
        <Dialog title="Confermi la prenotazione?" onClose={() => setCheck(null)}>
          <p>
            <strong>{formatDayWithWeekday(day)}</strong>, ore {time}
            <br />
            {serviceNameIn(services.data, chosen)}
            <br />
            {firstName} {lastName} · {email}
          </p>
          {check.overlaps.length > 0 ? (
            <Notice tone="warning">
              <p>
                <strong>Valutazione nella stanza.</strong> In questo orario c&apos;è già una seduta dell&apos;altro professionista,
                e una delle due è una valutazione:{" "}
                {check.overlaps.map((overlap) => `${formatTime(overlap.start)}–${formatTime(overlap.end)}`).join(", ")}. Puoi
                prenotare lo stesso.
              </p>
            </Notice>
          ) : null}
          {check.credit.usableCredit ? (
            <fieldset className="stack">
              <legend>Come si paga</legend>
              <label className="check">
                <input type="radio" name="pay" checked={pay === "credit"} onChange={() => setPay("credit")} />
                <span>
                  Usa un credito del cliente
                  {check.credit.validUntil !== null ? ` (valido fino al ${formatCalendarDay(check.credit.validUntil)})` : ""}
                </span>
              </label>
              <label className="check">
                <input type="radio" name="pay" checked={pay === "studio"} onChange={() => setPay("studio")} />
                <span>Pagamento in studio: il credito resta al cliente</span>
              </label>
            </fieldset>
          ) : (
            <p className="small muted">Il cliente non ha crediti con te per questa seduta: si paga in studio.</p>
          )}
          <ErrorNotice error={booking.error} wording={PANEL_WORDING} />
          <div className="dialog-actions">
            <button type="button" className="button" disabled={booking.running} onClick={confirm}>
              {booking.running ? "Invio…" : "Prenota"}
            </button>
            <button type="button" className="button button-secondary" onClick={() => setCheck(null)}>
              Modifica
            </button>
          </div>
        </Dialog>
      ) : null}
    </>
  );
}

// After the booking: what the backend answered, and the warning for a customer created now (D-088)
function Booked({ created, email, serviceName, onAgain }: { created: Created; email: string; serviceName: string; onAgain: () => void }) {
  return (
    <>
      <h1>Seduta prenotata</h1>
      <Notice tone="success">
        <p>
          {serviceName}, {formatDateTime(created.startsAt)}: confermata.{" "}
          {created.paidWith === "CREDIT" ? "È stato usato un credito del cliente." : "Da pagare in studio."}
        </p>
      </Notice>
      {created.customerCreated ? (
        <Notice tone="warning">
          <p>
            <strong>Cliente nuovo: controlla l&apos;email.</strong> Nessun cliente dello studio aveva l&apos;indirizzo{" "}
            <strong>{email.trim()}</strong>: è stato creato adesso, e la conferma della seduta arriva lì. Se c&apos;è un errore di
            battitura, cancella la seduta e prenotala di nuovo con l&apos;indirizzo giusto.
          </p>
        </Notice>
      ) : (
        <p className="small muted">Il cliente era già registrato: nel pannello vedrai il nome e il cognome che ha già.</p>
      )}
      <div className="stack">
        <Link href={`/pannello/sedute/${created.id}`} className="button button-block">
          Vedi la seduta
        </Link>
        <button type="button" className="button button-secondary button-block" onClick={onAgain}>
          Prenota un&apos;altra seduta
        </button>
      </div>
    </>
  );
}
