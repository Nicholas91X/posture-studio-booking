"use client";

import Link from "next/link";
import { useState } from "react";
import { api, unwrap } from "@/api/client";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { GrantDialog } from "@/components/panel/GrantDialog";
import { StudioSaleDialog } from "@/components/panel/StudioSaleDialog";
import { creditsText, formatCalendarDay, fullName } from "@/lib/format";
import { loadOwnServices, serviceNameIn, useApi } from "@/lib/panel";

// "I miei clienti" (D-066): who has a booking or a lot of credits with this practitioner, decided by the backend.
// The search only filters the list already shown; a gift goes by email, to anyone, even a new customer
export function CustomersClient() {
  const customers = useApi(() => unwrap(api.GET("/api/panel/customers")), []);
  const services = useApi(() => loadOwnServices(), []);
  const [search, setSearch] = useState("");
  const [granting, setGranting] = useState(false);
  const [selling, setSelling] = useState(false);
  const [granted, setGranted] = useState<string | null>(null);

  const words = search.trim().toLowerCase();
  const shown = [...(customers.data ?? [])]
    .sort((a, b) => a.lastName.localeCompare(b.lastName, "it") || a.firstName.localeCompare(b.firstName, "it"))
    .filter((customer) => words === "" || `${customer.firstName} ${customer.lastName} ${customer.email}`.toLowerCase().includes(words));

  return (
    <>
      <h1>I miei clienti</h1>
      <div className="stack">
        <button type="button" className="button button-block" onClick={() => setSelling(true)}>
          Registra un pacchetto venduto in studio
        </button>
        <button type="button" className="button button-secondary button-block" disabled={services.data === null} onClick={() => setGranting(true)}>
          Regala sedute a un cliente
        </button>
      </div>
      {granted !== null ? (
        <Notice tone="success">
          <p>{granted}</p>
        </Notice>
      ) : null}
      <div className="field">
        <label htmlFor="search">Cerca</label>
        <input id="search" type="search" className="input" placeholder="Nome, cognome o email" value={search} onChange={(event) => setSearch(event.target.value)} />
      </div>
      <ErrorNotice error={customers.error} />
      {customers.loading ? <Loading /> : null}
      {customers.data?.length === 0 ? <p className="muted">Ancora nessun cliente: compaiono qui dopo la prima prenotazione o il primo credito.</p> : null}
      {customers.data !== null && customers.data.length > 0 && shown.length === 0 ? <p className="muted">Nessun cliente con questo nome.</p> : null}
      {shown.map((customer) => (
        <Link key={customer.id} href={`/pannello/clienti/${customer.id}`} className="card card-link">
          <div className="spread">
            <div>
              <h2>{fullName(customer)}</h2>
              <p className="small muted">{customer.email}</p>
            </div>
            <span aria-hidden="true">›</span>
          </div>
        </Link>
      ))}
      {selling ? (
        <StudioSaleDialog
          customer={null}
          onClose={() => setSelling(false)}
          onSold={(lot) => {
            setSelling(false);
            setGranted(
              `Vendita registrata: ${creditsText(Number(lot.balance))} per ${serviceNameIn(services.data, lot.serviceId)}, validi fino al ${formatCalendarDay(lot.validUntil)}.`,
            );
            customers.reload();
          }}
        />
      ) : null}
      {granting && services.data !== null ? (
        <GrantDialog
          services={services.data}
          customer={null}
          onClose={() => setGranting(false)}
          onGranted={(lot) => {
            setGranting(false);
            setGranted(
              `Regalo registrato: ${creditsText(Number(lot.balance))} per ${serviceNameIn(services.data, lot.serviceId)}, validi fino al ${formatCalendarDay(lot.validUntil)}. Il cliente riceve un'email.`,
            );
            customers.reload();
          }}
        />
      ) : null}
    </>
  );
}
