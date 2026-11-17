"use client";

import { useState, type FormEvent } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice } from "@/components/Notice";
import { todayInRome } from "@/lib/format";
import { useAction, type ServiceOffer } from "@/lib/panel";

type Lot = Schemas["PanelCreditLotView"];
type Customer = { email: string; firstName: string; lastName: string };

// A gift of sessions (D-067, D-073): a new lot of credits for a service of the practitioner, to any customer by email,
// found or created as for a booking (D-066). The note is required: it is the trace of why; the customer never reads it
export function GrantDialog({
  services,
  customer,
  onClose,
  onGranted,
}: {
  services: ServiceOffer[];
  customer: Customer | null;
  onClose: () => void;
  onGranted: (lot: Lot) => void;
}) {
  const active = services.filter((service) => service.active);
  const [email, setEmail] = useState(customer?.email ?? "");
  const [firstName, setFirstName] = useState(customer?.firstName ?? "");
  const [lastName, setLastName] = useState(customer?.lastName ?? "");
  const [serviceId, setServiceId] = useState(active[0]?.id ?? 0);
  const [credits, setCredits] = useState("1");
  const [validUntil, setValidUntil] = useState("");
  const [note, setNote] = useState("");
  const action = useAction();

  const grant = (event: FormEvent) => {
    event.preventDefault();
    void action.run(async () => {
      onGranted(
        await unwrap(
          api.POST("/api/panel/credit-grants", {
            body: {
              customer: { email: email.trim(), firstName: firstName.trim(), lastName: lastName.trim() },
              serviceId,
              credits: Number(credits),
              note: note.trim(),
              ...(validUntil === "" ? {} : { validUntil }),
            },
          }),
        ),
      );
    });
  };

  return (
    <Dialog title="Regala sedute" onClose={onClose}>
      <form className="stack" onSubmit={grant}>
        {customer === null ? (
          <>
            <div className="field">
              <label htmlFor="grantEmail">Email del cliente</label>
              <input id="grantEmail" type="email" className="input" required autoComplete="off" value={email} onChange={(event) => setEmail(event.target.value)} />
            </div>
            <div className="form-pair">
              <div className="field">
                <label htmlFor="grantFirstName">Nome</label>
                <input id="grantFirstName" className="input" required maxLength={100} autoComplete="off" value={firstName} onChange={(event) => setFirstName(event.target.value)} />
              </div>
              <div className="field">
                <label htmlFor="grantLastName">Cognome</label>
                <input id="grantLastName" className="input" required maxLength={100} autoComplete="off" value={lastName} onChange={(event) => setLastName(event.target.value)} />
              </div>
            </div>
          </>
        ) : (
          <p>
            Per <strong>{customer.firstName} {customer.lastName}</strong> · {customer.email}
          </p>
        )}
        <div className="field">
          <label htmlFor="grantService">Servizio</label>
          <select id="grantService" className="input" value={serviceId} onChange={(event) => setServiceId(Number(event.target.value))}>
            {active.map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </select>
        </div>
        <div className="form-pair">
          <div className="field">
            <label htmlFor="grantCredits">Sedute</label>
            <input id="grantCredits" type="number" inputMode="numeric" className="input" required min={1} value={credits} onChange={(event) => setCredits(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="grantValidUntil">Valide fino al</label>
            <input id="grantValidUntil" type="date" className="input" min={todayInRome()} value={validUntil} onChange={(event) => setValidUntil(event.target.value)} />
          </div>
        </div>
        <p className="hint">Senza data valgono quanto un credito singolo, come nelle impostazioni.</p>
        <div className="field">
          <label htmlFor="grantNote">Nota (obbligatoria, il cliente non la vede)</label>
          <textarea id="grantNote" className="input" required maxLength={500} rows={3} value={note} onChange={(event) => setNote(event.target.value)} />
        </div>
        <ErrorNotice error={action.error} />
        <div className="dialog-actions">
          <button type="submit" className="button" disabled={action.running || active.length === 0}>
            {action.running ? "Invio…" : "Regala"}
          </button>
          <button type="button" className="button button-secondary" onClick={onClose}>
            Annulla
          </button>
        </div>
      </form>
    </Dialog>
  );
}
