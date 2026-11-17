"use client";

import { useState } from "react";
import { api, unwrap } from "@/api/client";
import { PANEL_WORDING } from "@/api/errors";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { RowCard } from "@/components/panel/RowCard";
import { formatDateTime } from "@/lib/format";
import { loadOwnServices, useAction, useApi, type PanelRow } from "@/lib/panel";

// The requests to approve (D-030), the one that expires first on top: approve or reject before the deadline.
// What happens to the money or the credit is the backend's: here only what the customer will be told
export function RequestsClient() {
  const requests = useApi(() => unwrap(api.GET("/api/panel/bookings/requests")), []);
  const services = useApi(() => loadOwnServices(), []);
  const [done, setDone] = useState<string | null>(null);

  const answered = (row: PanelRow, text: string) => {
    requests.set((list) => list?.filter((one) => one.id !== row.id) ?? null);
    setDone(text);
  };

  return (
    <>
      <h1>Richieste da approvare</h1>
      {done !== null ? (
        <Notice tone="success">
          <p>{done}</p>
        </Notice>
      ) : null}
      <ErrorNotice error={requests.error} />
      {requests.loading ? <Loading /> : null}
      {requests.data?.length === 0 ? <p className="muted">Nessuna richiesta in attesa.</p> : null}
      {requests.data?.map((row) => (
        <RowCard key={row.id} row={row} services={services.data} withDay>
          <Answer row={row} onAnswered={(text) => answered(row, text)} />
        </RowCard>
      ))}
    </>
  );
}

function Answer({ row, onAnswered }: { row: PanelRow; onAnswered: (text: string) => void }) {
  const action = useAction();
  const [rejecting, setRejecting] = useState(false);
  const path = { params: { path: { id: row.id } } };

  const approve = () =>
    void action.run(async () => {
      await unwrap(api.POST("/api/panel/bookings/{id}/approve", path));
      onAnswered(`Approvata: la seduta di ${formatDateTime(row.startsAt)} è confermata, e il cliente riceve un'email.`);
    });
  const reject = () =>
    void action.run(async () => {
      await unwrap(api.POST("/api/panel/bookings/{id}/reject", path));
      onAnswered(
        row.paidWith === "CREDIT"
          ? "Rifiutata: il credito torna al cliente, che riceve un'email."
          : "Rifiutata: l'importo bloccato sulla carta si sblocca, e il cliente riceve un'email.",
      );
    });

  return (
    <div className="stack">
      {row.approvalDeadline !== null ? <p className="small">Rispondi entro {formatDateTime(row.approvalDeadline)}.</p> : null}
      <ErrorNotice error={rejecting ? null : action.error} wording={PANEL_WORDING} />
      <div className="row">
        <button type="button" className="button" disabled={action.running} onClick={approve}>
          Approva
        </button>
        <button type="button" className="button button-secondary" disabled={action.running} onClick={() => setRejecting(true)}>
          Rifiuta
        </button>
      </div>
      {rejecting ? (
        <Dialog title="Rifiutare la richiesta?" onClose={() => setRejecting(false)}>
          <p>{formatDateTime(row.startsAt)}</p>
          <p>
            {row.paidWith === "CREDIT"
              ? "Il credito usato torna al cliente."
              : "Al cliente non viene addebitato niente: l'importo bloccato sulla carta si sblocca."}{" "}
            Il cliente riceve un&apos;email.
          </p>
          <ErrorNotice error={action.error} wording={PANEL_WORDING} />
          <div className="dialog-actions">
            <button type="button" className="button button-danger" disabled={action.running} onClick={reject}>
              {action.running ? "Invio…" : "Sì, rifiuta"}
            </button>
            <button type="button" className="button button-secondary" onClick={() => setRejecting(false)}>
              No
            </button>
          </div>
        </Dialog>
      ) : null}
    </div>
  );
}
