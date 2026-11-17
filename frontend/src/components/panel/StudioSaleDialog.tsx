"use client";

import { useState, type FormEvent } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { formatPrice, sessionsText } from "@/lib/format";
import { useAction, useApi } from "@/lib/panel";

type Lot = Schemas["PanelCreditLotView"];
type Customer = { email: string; firstName: string; lastName: string };

// A package sold in the studio, recorded here (D-089): one of the practitioner's packages on sale, for a customer by
// email (found or created, D-048). Sessions, validity and price are those of the package now; the system does not
// record the cash, only the credits it gives
export function StudioSaleDialog({
  customer,
  onClose,
  onSold,
}: {
  customer: Customer | null;
  onClose: () => void;
  onSold: (lot: Lot) => void;
}) {
  const packages = useApi(() => unwrap(api.GET("/api/panel/packages")), []);
  const onSale = packages.data?.filter((offer) => offer.active) ?? [];
  const [packageId, setPackageId] = useState<number | null>(null);
  const [email, setEmail] = useState(customer?.email ?? "");
  const [firstName, setFirstName] = useState(customer?.firstName ?? "");
  const [lastName, setLastName] = useState(customer?.lastName ?? "");
  const action = useAction();
  const chosen = onSale.find((offer) => offer.id === packageId) ?? onSale[0];

  const sell = (event: FormEvent) => {
    event.preventDefault();
    if (chosen === undefined) {
      return;
    }
    void action.run(async () =>
      onSold(
        await unwrap(
          api.POST("/api/panel/package-sales", {
            body: {
              packageOfferId: chosen.id,
              customer: { email: email.trim(), firstName: firstName.trim(), lastName: lastName.trim() },
            },
          }),
        ),
      ),
    );
  };

  return (
    <Dialog title="Pacchetto venduto in studio" onClose={onClose}>
      {packages.loading ? <Loading /> : null}
      <ErrorNotice error={packages.error} />
      {packages.data !== null && onSale.length === 0 ? (
        <Notice tone="warning">
          <p>Non hai pacchetti in vendita. Creane uno da Altro › Servizi e pacchetti.</p>
        </Notice>
      ) : null}
      {chosen !== undefined ? (
        <form className="stack" onSubmit={sell}>
          <div className="field">
            <label htmlFor="salePackage">Pacchetto</label>
            <select id="salePackage" className="input" value={chosen.id} onChange={(event) => setPackageId(Number(event.target.value))}>
              {onSale.map((offer) => (
                <option key={offer.id} value={offer.id}>
                  {offer.name} · {sessionsText(offer.sessionsCount)} · {formatPrice(offer.priceCents)}
                </option>
              ))}
            </select>
            <p className="hint">
              {sessionsText(chosen.sessionsCount)} di {chosen.serviceName}, valide {chosen.validityDays} giorni da oggi.
            </p>
          </div>
          {customer === null ? (
            <>
              <div className="field">
                <label htmlFor="saleEmail">Email del cliente</label>
                <input id="saleEmail" type="email" className="input" required autoComplete="off" value={email} onChange={(event) => setEmail(event.target.value)} />
              </div>
              <div className="form-pair">
                <div className="field">
                  <label htmlFor="saleFirstName">Nome</label>
                  <input id="saleFirstName" className="input" required maxLength={100} autoComplete="off" value={firstName} onChange={(event) => setFirstName(event.target.value)} />
                </div>
                <div className="field">
                  <label htmlFor="saleLastName">Cognome</label>
                  <input id="saleLastName" className="input" required maxLength={100} autoComplete="off" value={lastName} onChange={(event) => setLastName(event.target.value)} />
                </div>
              </div>
            </>
          ) : (
            <p>
              Per <strong>{customer.firstName} {customer.lastName}</strong> · {customer.email}
            </p>
          )}
          <p className="small muted">Registra i crediti dopo aver ricevuto il pagamento: il sistema non registra i contanti.</p>
          <ErrorNotice error={action.error} />
          <div className="dialog-actions">
            <button type="submit" className="button" disabled={action.running}>
              {action.running ? "Invio…" : "Registra la vendita"}
            </button>
            <button type="button" className="button button-secondary" onClick={onClose}>
              Annulla
            </button>
          </div>
        </form>
      ) : null}
    </Dialog>
  );
}
