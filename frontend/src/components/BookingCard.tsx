"use client";

import Link from "next/link";
import { useState } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { cancellationPreview, cancellationResult } from "@/lib/cancellation";
import type { Directory } from "@/lib/directory";
import { formatCalendarDay, formatDateTime, formatPrice, formatTime } from "@/lib/format";
import { BOOKING_STATUS, PAID_WITH, badgeClass } from "@/lib/labels";
import { Dialog } from "./Dialog";
import { ErrorNotice, Notice } from "./Notice";

type Booking = Schemas["CustomerBookingView"];

// One session as the customer sees it, with what can be done on it now. What can be done follows the state the
// backend gives (D-005); the backend decides again at every click.
export function BookingCard({
  booking,
  directory,
  onChange,
  linkToDetail = true,
}: {
  booking: Booking;
  directory: Directory;
  onChange: (updated: Booking) => void;
  linkToDetail?: boolean;
}) {
  const [asking, setAsking] = useState<"cancel" | "withdraw" | null>(null);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [outcome, setOutcome] = useState<string | null>(null);

  const status = BOOKING_STATUS[booking.status];
  const notStarted = new Date(booking.startsAt).getTime() > Date.now();
  const title = `${directory.serviceName(booking.practitionerId, booking.serviceId)} con ${directory.practitionerName(booking.practitionerId)}`;

  async function act(action: "cancel" | "withdraw") {
    setSending(true);
    setError(null);
    try {
      const path = action === "cancel" ? "/api/customer/bookings/{id}/cancel" : "/api/customer/bookings/{id}/withdraw";
      const updated = await unwrap(api.POST(path, { params: { path: { id: booking.id } } }));
      setOutcome(action === "cancel" ? cancellationResult(updated) : withdrawalResult(updated));
      setAsking(null);
      onChange(updated);
    } catch (failure) {
      setError(failure);
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="card">
      <div className="spread">
        <div>
          <h3>{linkToDetail ? <Link href={`/prenotazioni/${booking.id}`}>{title}</Link> : title}</h3>
          <p className="small" style={{ marginBottom: "0.25rem" }}>
            {formatDateTime(booking.startsAt)} – {formatTime(booking.endsAt)}
          </p>
          <p className="small muted">
            {PAID_WITH[booking.paidWith]} · {formatPrice(booking.priceCents)}
          </p>
        </div>
        <span className={badgeClass(status.tone)}>{status.text}</span>
      </div>

      {outcome !== null ? (
        <Notice tone="success">
          <p>{outcome}</p>
        </Notice>
      ) : null}
      <ErrorNotice error={asking === null ? error : null} />

      {booking.status === "PENDING_PAYMENT" && booking.paymentUrl !== null ? (
        <div className="stack">
          {booking.holdExpiresAt !== null ? (
            <p className="small">L&apos;orario è tenuto per te fino alle {formatTime(booking.holdExpiresAt)}.</p>
          ) : null}
          <a className="button button-block" href={booking.paymentUrl}>
            Completa il pagamento
          </a>
        </div>
      ) : null}

      {booking.status === "PENDING_APPROVAL" ? (
        <div className="stack">
          {booking.approvalDeadline !== null ? (
            <p className="small">Il professionista risponde entro {formatDateTime(booking.approvalDeadline)}.</p>
          ) : null}
          <button type="button" className="button button-secondary button-block" onClick={() => setAsking("withdraw")}>
            Ritira la richiesta
          </button>
        </div>
      ) : null}

      {booking.status === "CONFIRMED" && notStarted ? (
        <div className="stack">
          {new Date(booking.freeCancellationUntil).getTime() >= Date.now() ? (
            <p className="small">Puoi cancellare senza perdere la seduta fino a {formatDateTime(booking.freeCancellationUntil)}.</p>
          ) : null}
          <button type="button" className="button button-secondary button-block" onClick={() => setAsking("cancel")}>
            Cancella la seduta
          </button>
        </div>
      ) : null}

      {booking.returnedCredit !== null && outcome === null ? (
        <p className="small muted">Il credito ti è tornato: valido fino al {formatCalendarDay(booking.returnedCredit.validUntil)}.</p>
      ) : null}

      {asking === "cancel" ? <CancelDialog booking={booking} sending={sending} error={error} onConfirm={() => void act("cancel")} onClose={() => setAsking(null)} /> : null}
      {asking === "withdraw" ? (
        <Dialog title="Ritirare la richiesta?" onClose={() => setAsking(null)}>
          <p>
            {booking.paidWith === "CREDIT"
              ? "Il credito che avevi usato ti torna disponibile."
              : "Non ti viene addebitato niente: l'importo bloccato sulla carta si sblocca."}
          </p>
          <ErrorNotice error={error} />
          <div className="dialog-actions">
            <button type="button" className="button button-danger" disabled={sending} onClick={() => void act("withdraw")}>
              {sending ? "Invio…" : "Sì, ritira"}
            </button>
            <button type="button" className="button button-secondary" onClick={() => setAsking(null)}>
              No, tienila
            </button>
          </div>
        </Dialog>
      ) : null}
    </div>
  );
}

function CancelDialog({
  booking,
  sending,
  error,
  onConfirm,
  onClose,
}: {
  booking: Booking;
  sending: boolean;
  error: unknown;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const preview = cancellationPreview(booking);
  return (
    <Dialog title="Cancellare la seduta?" onClose={onClose}>
      <p>{formatDateTime(booking.startsAt)}</p>
      <Notice tone={preview.inTime ? "info" : "warning"}>
        <p>
          <strong>{preview.title}.</strong> {preview.text}
        </p>
      </Notice>
      <ErrorNotice error={error} />
      <div className="dialog-actions">
        <button type="button" className="button button-danger" disabled={sending} onClick={onConfirm}>
          {sending ? "Invio…" : "Sì, cancella"}
        </button>
        <button type="button" className="button button-secondary" onClick={onClose}>
          No, tienila
        </button>
      </div>
    </Dialog>
  );
}

function withdrawalResult(booking: Booking): string {
  if (booking.status !== "WITHDRAWN") {
    return "La seduta è stata aggiornata.";
  }
  return booking.paidWith === "CREDIT"
    ? "Richiesta ritirata. Il credito che avevi usato è di nuovo disponibile."
    : "Richiesta ritirata. Non ti è stato addebitato niente.";
}
