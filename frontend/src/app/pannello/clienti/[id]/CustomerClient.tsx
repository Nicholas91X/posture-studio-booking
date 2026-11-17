"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { GrantDialog } from "@/components/panel/GrantDialog";
import { creditsText, formatCalendarDay, formatDateTime, fullName, todayInRome } from "@/lib/format";
import { badgeClass, ENTRY_KIND, LOT_SOURCE, LOT_STATUS } from "@/lib/labels";
import { loadOwnServices, serviceNameIn, useAction, useApi, type ServiceOffer } from "@/lib/panel";

type Lot = Schemas["CreditLotSummary"];
type Customer = Schemas["PanelCustomerView"];

// A customer of the practitioner (D-066): only their lots with this practitioner, never the other's. The
// concessions (D-067): an extension only forward, a correction never below zero, a gift as a new lot; each with its
// note. The state of a lot (usable, used up, expired) is the backend's (D-074)
export function CustomerClient() {
  const id = Number(useParams<{ id: string }>().id);
  const customers = useApi(() => unwrap(api.GET("/api/panel/customers")), []);
  const lots = useApi(() => unwrap(api.GET("/api/panel/customers/{customerId}/credits", { params: { path: { customerId: id } } })), [id]);
  const services = useApi(() => loadOwnServices(), []);
  const [granting, setGranting] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const customer = customers.data?.find((one) => one.id === id) ?? null;

  if (customers.loading) {
    return <Loading />;
  }
  if (customer === null) {
    return (
      <>
        <ErrorNotice error={customers.error} />
        {customers.error === null ? <Notice tone="warning"><p>Questo cliente non è tra i tuoi.</p></Notice> : null}
        <Link href="/pannello/clienti">‹ Clienti</Link>
      </>
    );
  }
  const sorted = [...(lots.data ?? [])].sort((a, b) => Number(b.status === "USABLE") - Number(a.status === "USABLE") || a.validUntil.localeCompare(b.validUntil));
  const changed = (text: string) => {
    setDone(text);
    lots.reload();
  };
  const bookHere = `/pannello/prenota?email=${encodeURIComponent(customer.email)}&nome=${encodeURIComponent(customer.firstName)}&cognome=${encodeURIComponent(customer.lastName)}`;

  return (
    <>
      <Link href="/pannello/clienti" className="back">
        ‹ Clienti
      </Link>
      <h1>{fullName(customer)}</h1>
      <p className="muted">{customer.email}</p>
      <div className="row">
        <Link href={bookHere} className="button">
          Prenota per questo cliente
        </Link>
        <button type="button" className="button button-secondary" disabled={services.data === null} onClick={() => setGranting(true)}>
          Regala sedute
        </button>
      </div>
      {done !== null ? (
        <Notice tone="success">
          <p>{done}</p>
        </Notice>
      ) : null}

      <h2>Crediti con te</h2>
      <ErrorNotice error={lots.error} />
      {lots.loading ? <Loading /> : null}
      {lots.data?.length === 0 ? <p className="muted">Nessun credito.</p> : null}
      {sorted.map((lot) => (
        <LotCard key={lot.id} lot={lot} services={services.data} onChanged={changed} />
      ))}

      {granting && services.data !== null ? (
        <GrantDialog
          services={services.data}
          customer={customer satisfies Customer}
          onClose={() => setGranting(false)}
          onGranted={(lot) => {
            setGranting(false);
            changed(`Regalo registrato: ${creditsText(Number(lot.balance))}, validi fino al ${formatCalendarDay(lot.validUntil)}. Il cliente riceve un'email.`);
          }}
        />
      ) : null}
    </>
  );
}

function LotCard({ lot, services, onChanged }: { lot: Lot; services: ServiceOffer[] | null; onChanged: (text: string) => void }) {
  const status = LOT_STATUS[lot.status];
  const [open, setOpen] = useState<"history" | "extend" | "adjust" | null>(null);
  return (
    <div className="card stack">
      <div className="spread">
        <div>
          <h3>{creditsText(Number(lot.balance))}</h3>
          <p className="small">
            {serviceNameIn(services, lot.serviceId)} · {LOT_SOURCE[lot.source]}
          </p>
          <p className="small muted">Valido fino al {formatCalendarDay(lot.validUntil)}</p>
        </div>
        <span className={badgeClass(status.tone)}>{status.text}</span>
      </div>
      <div className="row">
        <button type="button" className="button button-secondary" onClick={() => setOpen(open === "history" ? null : "history")}>
          {open === "history" ? "Nascondi la storia" : "Storia e note"}
        </button>
        <button type="button" className="button button-secondary" onClick={() => setOpen("extend")}>
          Proroga
        </button>
        <button type="button" className="button button-secondary" onClick={() => setOpen("adjust")}>
          Correggi
        </button>
      </div>
      {open === "history" ? <History lotId={lot.id} /> : null}
      {open === "extend" ? (
        <ExtendDialog
          lot={lot}
          onClose={() => setOpen(null)}
          onDone={(updated) => {
            setOpen(null);
            onChanged(`Scadenza prorogata al ${formatCalendarDay(updated.validUntil)}. Il cliente riceve un'email.`);
          }}
        />
      ) : null}
      {open === "adjust" ? (
        <AdjustDialog
          lot={lot}
          onClose={() => setOpen(null)}
          onDone={(updated) => {
            setOpen(null);
            onChanged(`Correzione registrata: ora il lotto ha ${creditsText(Number(updated.balance))}. Il cliente riceve un'email.`);
          }}
        />
      ) : null}
    </div>
  );
}

// The history of a lot with the notes: only in the panel (D-074)
function History({ lotId }: { lotId: number }) {
  const detail = useApi(() => unwrap(api.GET("/api/panel/credit-lots/{id}", { params: { path: { id: lotId } } })), [lotId]);
  if (detail.loading) {
    return <Loading />;
  }
  return (
    <>
      <ErrorNotice error={detail.error} />
      <ul className="history">
        {detail.data?.history.map((entry, index) => (
          <li key={index}>
            <p className="small">
              <strong>{ENTRY_KIND[entry.kind]}</strong>
              {entry.amount !== null ? ` · ${entry.amount > 0 ? "+" : ""}${entry.amount}` : ""}
              {entry.kind === "EXTENSION" && entry.oldValidUntil !== null && entry.newValidUntil !== null
                ? ` · dal ${formatCalendarDay(entry.oldValidUntil)} al ${formatCalendarDay(entry.newValidUntil)}`
                : ""}
            </p>
            <p className="small muted">{formatDateTime(entry.at)}</p>
            {entry.note !== null ? <p className="small">Nota: {entry.note}</p> : null}
          </li>
        ))}
      </ul>
    </>
  );
}

function ExtendDialog({ lot, onClose, onDone }: { lot: Lot; onClose: () => void; onDone: (lot: Schemas["PanelCreditLotView"]) => void }) {
  const [validUntil, setValidUntil] = useState("");
  const [note, setNote] = useState("");
  const action = useAction();
  const submit = (event: FormEvent) => {
    event.preventDefault();
    void action.run(async () =>
      onDone(await unwrap(api.POST("/api/panel/credit-lots/{id}/extensions", { params: { path: { id: lot.id } }, body: { validUntil, note: note.trim() } }))),
    );
  };
  return (
    <Dialog title="Proroga la scadenza" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <p className="small">Oggi vale fino al {formatCalendarDay(lot.validUntil)}. La proroga va solo in avanti.</p>
        <div className="field">
          <label htmlFor="newValidUntil">Nuova scadenza</label>
          <input id="newValidUntil" type="date" className="input" required min={todayInRome()} value={validUntil} onChange={(event) => setValidUntil(event.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="extendNote">Nota (obbligatoria, il cliente non la vede)</label>
          <textarea id="extendNote" className="input" required maxLength={500} rows={3} value={note} onChange={(event) => setNote(event.target.value)} />
        </div>
        <ErrorNotice error={action.error} />
        <div className="dialog-actions">
          <button type="submit" className="button" disabled={action.running}>
            {action.running ? "Invio…" : "Proroga"}
          </button>
          <button type="button" className="button button-secondary" onClick={onClose}>
            Annulla
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function AdjustDialog({ lot, onClose, onDone }: { lot: Lot; onClose: () => void; onDone: (lot: Schemas["PanelCreditLotView"]) => void }) {
  const [amount, setAmount] = useState("-1");
  const [note, setNote] = useState("");
  const action = useAction();
  const submit = (event: FormEvent) => {
    event.preventDefault();
    void action.run(async () =>
      onDone(
        await unwrap(api.POST("/api/panel/credit-lots/{id}/adjustments", { params: { path: { id: lot.id } }, body: { amount: Number(amount), note: note.trim() } })),
      ),
    );
  };
  return (
    <Dialog title="Correggi i crediti" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <p className="small">
          Oggi il lotto ha {creditsText(Number(lot.balance))}. Un numero negativo toglie crediti, uno positivo li aggiunge; il saldo non
          scende mai sotto zero. Per un rimborso fatto a mano su Stripe, prima azzera qui il credito.
        </p>
        <div className="field">
          <label htmlFor="amount">Crediti da aggiungere o togliere</label>
          <input id="amount" type="number" inputMode="numeric" className="input" required value={amount} onChange={(event) => setAmount(event.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="adjustNote">Nota (obbligatoria, il cliente non la vede)</label>
          <textarea id="adjustNote" className="input" required maxLength={500} rows={3} value={note} onChange={(event) => setNote(event.target.value)} />
        </div>
        <ErrorNotice error={action.error} />
        <div className="dialog-actions">
          <button type="submit" className="button" disabled={action.running || Number(amount) === 0}>
            {action.running ? "Invio…" : "Registra"}
          </button>
          <button type="button" className="button button-secondary" onClick={onClose}>
            Annulla
          </button>
        </div>
      </form>
    </Dialog>
  );
}
