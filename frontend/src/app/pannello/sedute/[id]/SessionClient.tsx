"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { PANEL_WORDING } from "@/api/errors";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { RowFlags } from "@/components/panel/RowCard";
import { formatCalendarDay, formatDateTime, formatTime, romeDay } from "@/lib/format";
import { badgeClass, PAID_WITH } from "@/lib/labels";
import { customerName, loadOwnServices, PANEL_BOOKING_STATUS, serviceNameIn, useAction, useApi, type PanelBooking, type PanelRow } from "@/lib/panel";

type PaymentLink = Schemas["PaymentLinkView"];

// One session of the practitioner (step 13): its facts and what can be done with it. Which buttons appear follows
// the state the backend gives; whether the action is allowed (too late, already started) is the backend's answer
export function SessionClient() {
  const id = Number(useParams<{ id: string }>().id);
  const services = useApi(() => loadOwnServices(), []);
  // the booking, and its row in the list of its day: the customer's name and the flags are only in the lists (D-049)
  const loaded = useApi(async () => {
    const booking = await unwrap(api.GET("/api/panel/bookings/{id}", { params: { path: { id } } }));
    const day = romeDay(booking.startsAt);
    const rows = await unwrap(api.GET("/api/panel/bookings", { params: { query: { from: day, to: day } } }));
    return { booking, row: rows.find((one) => one.id === id) ?? null };
  }, [id]);
  const [outcome, setOutcome] = useState<string | null>(null);

  if (loaded.loading) {
    return <Loading />;
  }
  if (loaded.data === null) {
    return (
      <>
        <ErrorNotice error={loaded.error} />
        <Link href="/pannello">‹ Agenda</Link>
      </>
    );
  }
  const { booking, row } = loaded.data;
  const status = PANEL_BOOKING_STATUS[booking.status];
  const started = new Date(booking.startsAt).getTime() <= Date.now();
  const update = (next: PanelBooking, text: string) => {
    loaded.set((current) => (current === null ? null : { ...current, booking: next }));
    setOutcome(text);
  };

  return (
    <>
      <Link href={`/pannello?da=${romeDay(booking.startsAt)}`} className="back">
        ‹ Agenda
      </Link>
      <h1>{formatDateTime(booking.startsAt)}</h1>
      <div className="card stack">
        <div className="spread">
          <div>
            <p>
              <strong>{row === null ? "Cliente" : customerName(row)}</strong>
            </p>
            <p>
              {serviceNameIn(services.data, booking.serviceId)} · {formatTime(booking.startsAt)}–{formatTime(booking.endsAt)}
            </p>
            <p className="small muted">
              {PAID_WITH[booking.paidWith]}
              {row?.bookedBy === "PRACTITIONER" ? " · prenotata dal pannello" : ""}
            </p>
          </div>
          <span className={badgeClass(status.tone)}>{status.text}</span>
        </div>
        {row !== null ? <RowFlags row={row} /> : null}
        {booking.status === "PENDING_APPROVAL" && booking.approvalDeadline !== null ? (
          <p className="small">
            Da approvare entro {formatDateTime(booking.approvalDeadline)}. <Link href="/pannello/richieste">Rispondi</Link>
          </p>
        ) : null}
      </div>

      {outcome !== null ? (
        <Notice tone="success">
          <p>{outcome}</p>
        </Notice>
      ) : null}

      {booking.status === "CONFIRMED" && booking.paidWith === "IN_STUDIO" && !started ? <PaymentLinkCard booking={booking} /> : null}
      {(booking.status === "CONFIRMED" || booking.status === "COMPLETED" || booking.status === "NO_SHOW") && started ? (
        <AttendanceCard booking={booking} onDone={update} />
      ) : null}
      {(booking.status === "CONFIRMED" || booking.status === "PENDING_APPROVAL") && !started ? (
        <CancelCard booking={booking} row={row} onDone={update} />
      ) : null}
    </>
  );
}

// D-059, D-065: the customer pays a session "in studio" before, by card. A new link makes the old one useless
function PaymentLinkCard({ booking }: { booking: PanelBooking }) {
  const action = useAction();
  const [link, setLink] = useState<PaymentLink | null>(null);
  const [copied, setCopied] = useState(false);

  const create = () =>
    void action.run(async () => {
      setCopied(false);
      setLink(await unwrap(api.POST("/api/panel/bookings/{id}/payment-link", { params: { path: { id: booking.id } } })));
    });

  return (
    <div className="card stack">
      <h2>Pagamento anticipato</h2>
      <p className="small muted">
        Un link per pagare con la carta prima della seduta. L&apos;importo si blocca e si incassa subito se la seduta è ancora
        confermata. Un link nuovo fa scadere quello di prima.
      </p>
      <ErrorNotice error={action.error} wording={PANEL_WORDING} />
      {link === null ? (
        <button type="button" className="button button-block" disabled={action.running} onClick={create}>
          {action.running ? "Preparazione…" : "Prepara il link di pagamento"}
        </button>
      ) : (
        <>
          <p className="small">Il link vale fino a {formatDateTime(link.expiresAt)}.</p>
          <a className="button button-block" href={link.whatsappUrl} target="_blank" rel="noopener noreferrer">
            Condividi su WhatsApp
          </a>
          <button
            type="button"
            className="button button-secondary button-block"
            onClick={() =>
              void navigator.clipboard.writeText(link.paymentUrl).then(
                () => setCopied(true),
                () => setCopied(false),
              )
            }
          >
            {copied ? "Link copiato" : "Copia il link"}
          </button>
          <button type="button" className="link-button small" disabled={action.running} onClick={create}>
            Prepara un link nuovo
          </button>
        </>
      )}
    </div>
  );
}

// After the start (D-082): "non presentato", or back to "presente", for 7 days after the end; the backend says when
function AttendanceCard({ booking, onDone }: { booking: PanelBooking; onDone: (next: PanelBooking, text: string) => void }) {
  const action = useAction();
  const path = { params: { path: { id: booking.id } } };
  return (
    <div className="card stack">
      <h2>Presenza</h2>
      <ErrorNotice error={action.error} wording={PANEL_WORDING} />
      {booking.status === "NO_SHOW" ? (
        <button
          type="button"
          className="button button-secondary button-block"
          disabled={action.running}
          onClick={() =>
            void action.run(async () =>
              onDone(await unwrap(api.POST("/api/panel/bookings/{id}/attended", path)), "Corretto: il cliente risulta presente."),
            )
          }
        >
          Il cliente era presente
        </button>
      ) : (
        <button
          type="button"
          className="button button-secondary button-block"
          disabled={action.running}
          onClick={() =>
            void action.run(async () =>
              onDone(await unwrap(api.POST("/api/panel/bookings/{id}/no-show", path)), "Segnata: il cliente non si è presentato."),
            )
          }
        >
          Segna &laquo;non presentato&raquo;
        </button>
      )}
    </div>
  );
}

// What the customer keeps when the studio cancels (D-075), said before: always, whatever the notice
function whatTheCustomerKeeps(booking: PanelBooking): string {
  if (booking.status === "PENDING_APPROVAL") {
    return booking.paidWith === "CREDIT"
      ? "Come per un rifiuto: il credito usato torna al cliente."
      : "Come per un rifiuto: l'importo bloccato sulla carta si sblocca, niente viene addebitato.";
  }
  switch (booking.paidWith) {
    case "CREDIT":
      return "Il credito usato torna al cliente.";
    case "CARD":
      return "Il cliente riceve un credito di una seduta: il sistema non fa rimborsi.";
    case "IN_STUDIO":
      return "Non c'è niente da restituire: la seduta non era ancora pagata. Un link di pagamento aperto smette di valere.";
  }
}

function CancelCard({
  booking,
  row,
  onDone,
}: {
  booking: PanelBooking;
  row: PanelRow | null;
  onDone: (next: PanelBooking, text: string) => void;
}) {
  const action = useAction();
  const [asking, setAsking] = useState(false);
  const cancel = () =>
    void action.run(async () => {
      const result = await unwrap(api.POST("/api/panel/bookings/{id}/cancel", { params: { path: { id: booking.id } } }));
      setAsking(false);
      onDone(
        result.booking,
        result.returnedCredit === null
          ? "Seduta cancellata. Il cliente riceve un'email."
          : `Seduta cancellata. Al cliente è tornato un credito, valido fino al ${formatCalendarDay(result.returnedCredit.validUntil)}; riceve un'email.`,
      );
    });
  return (
    <div className="card stack">
      <h2>Cancella come studio</h2>
      <p className="small muted">{whatTheCustomerKeeps(booking)}</p>
      <button type="button" className="button button-secondary button-block" onClick={() => setAsking(true)}>
        Cancella la seduta
      </button>
      {asking ? (
        <Dialog title="Cancellare la seduta?" onClose={() => setAsking(false)}>
          <p>
            {formatDateTime(booking.startsAt)}
            {row === null ? "" : ` · ${customerName(row)}`}
          </p>
          <Notice>
            <p>{whatTheCustomerKeeps(booking)} Il cliente riceve un&apos;email che dice che ha cancellato lo studio.</p>
          </Notice>
          <ErrorNotice error={action.error} wording={PANEL_WORDING} />
          <div className="dialog-actions">
            <button type="button" className="button button-danger" disabled={action.running} onClick={cancel}>
              {action.running ? "Invio…" : "Sì, cancella"}
            </button>
            <button type="button" className="button button-secondary" onClick={() => setAsking(false)}>
              No, tienila
            </button>
          </div>
        </Dialog>
      ) : null}
    </div>
  );
}
