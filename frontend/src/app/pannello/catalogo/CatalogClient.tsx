"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { euroInput, formatPrice, parseEuro, sessionsText } from "@/lib/format";
import { loadOwnServices, useAction, useApi, type ServiceOffer } from "@/lib/panel";

type PackageOffer = Schemas["PackageOfferView"];

// The practitioner's catalog (step 5): services, with "valutazione" for the single room (D-043), and packages. Nothing
// is deleted: a service or a package goes out of sale and can come back. Prices typed in euro, sent in cents
export function CatalogClient() {
  const services = useApi(() => loadOwnServices(true), []);
  const packages = useApi(() => unwrap(api.GET("/api/panel/packages")), []);
  const [serviceForm, setServiceForm] = useState<ServiceOffer | "new" | null>(null);
  const [packageForm, setPackageForm] = useState<PackageOffer | "new" | null>(null);

  const refreshServices = () => {
    void loadOwnServices(true).then((list) => services.set(list), () => services.reload());
  };

  return (
    <>
      <Link href="/pannello/altro" className="back">
        ‹ Altro
      </Link>
      <h1>Servizi e pacchetti</h1>

      <h2>Servizi</h2>
      <ErrorNotice error={services.error} />
      {services.loading ? <Loading /> : null}
      {services.data?.length === 0 ? <p className="muted">Nessun servizio.</p> : null}
      {services.data?.map((service) => (
        <OfferCard
          key={service.id}
          title={service.name}
          lines={[`${service.durationMinutes} minuti · ${formatPrice(service.priceCents)}`, ...(service.evaluation ? ["Valutazione"] : [])]}
          active={service.active}
          onEdit={() => setServiceForm(service)}
          toggle={async () => {
            const path = { params: { path: { id: service.id } } };
            await unwrap(
              service.active ? api.POST("/api/panel/services/{id}/deactivate", path) : api.POST("/api/panel/services/{id}/activate", path),
            );
            refreshServices();
          }}
        />
      ))}
      <button type="button" className="button button-block" onClick={() => setServiceForm("new")}>
        Nuovo servizio
      </button>

      <h2>Pacchetti</h2>
      <ErrorNotice error={packages.error} />
      {packages.loading ? <Loading /> : null}
      {packages.data?.length === 0 ? <p className="muted">Nessun pacchetto.</p> : null}
      {packages.data?.map((offer) => (
        <OfferCard
          key={offer.id}
          title={offer.name}
          lines={[`${sessionsText(offer.sessionsCount)} di ${offer.serviceName} · ${formatPrice(offer.priceCents)}`, `Validi ${offer.validityDays} giorni dall'acquisto`]}
          active={offer.active}
          onEdit={() => setPackageForm(offer)}
          toggle={async () => {
            const path = { params: { path: { id: offer.id } } };
            await unwrap(
              offer.active ? api.POST("/api/panel/packages/{id}/deactivate", path) : api.POST("/api/panel/packages/{id}/activate", path),
            );
            packages.reload();
          }}
        />
      ))}
      <button
        type="button"
        className="button button-block"
        disabled={(services.data ?? []).length === 0}
        onClick={() => setPackageForm("new")}
      >
        Nuovo pacchetto
      </button>

      {serviceForm !== null ? (
        <ServiceForm
          service={serviceForm === "new" ? null : serviceForm}
          onClose={() => setServiceForm(null)}
          onSaved={() => {
            setServiceForm(null);
            refreshServices();
          }}
        />
      ) : null}
      {packageForm !== null ? (
        <PackageForm
          offer={packageForm === "new" ? null : packageForm}
          services={services.data ?? []}
          onClose={() => setPackageForm(null)}
          onSaved={() => {
            setPackageForm(null);
            packages.reload();
          }}
        />
      ) : null}
    </>
  );
}

function OfferCard({
  title,
  lines,
  active,
  onEdit,
  toggle,
}: {
  title: string;
  lines: string[];
  active: boolean;
  onEdit: () => void;
  toggle: () => Promise<void>;
}) {
  const action = useAction();
  return (
    <div className="card stack">
      <div className="spread">
        <h3>{title}</h3>
        <span className={active ? "badge badge-success" : "badge"}>{active ? "In vendita" : "Non in vendita"}</span>
      </div>
      {lines.map((line) => (
        <p key={line} className="small">
          {line}
        </p>
      ))}
      <ErrorNotice error={action.error} />
      <div className="row">
        <button type="button" className="button button-secondary" onClick={onEdit}>
          Modifica
        </button>
        <button type="button" className="button button-secondary" disabled={action.running} onClick={() => void action.run(toggle)}>
          {active ? "Togli dalla vendita" : "Rimetti in vendita"}
        </button>
      </div>
    </div>
  );
}

function ServiceForm({ service, onClose, onSaved }: { service: ServiceOffer | null; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(service?.name ?? "");
  const [duration, setDuration] = useState(String(service?.durationMinutes ?? 60));
  const [price, setPrice] = useState(service === null ? "" : euroInput(service.priceCents));
  const [evaluation, setEvaluation] = useState(service?.evaluation ?? false);
  const action = useAction();
  const cents = parseEuro(price);

  const save = (event: FormEvent) => {
    event.preventDefault();
    if (cents === null) {
      return;
    }
    void action.run(async () => {
      const body = { name: name.trim(), durationMinutes: Number(duration), priceCents: cents, evaluation };
      await unwrap(
        service === null
          ? api.POST("/api/panel/services", { body })
          : api.PUT("/api/panel/services/{id}", { params: { path: { id: service.id } }, body }),
      );
      onSaved();
    });
  };

  return (
    <Dialog title={service === null ? "Nuovo servizio" : "Modifica il servizio"} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        <div className="field">
          <label htmlFor="serviceName">Nome</label>
          <input id="serviceName" className="input" required maxLength={100} value={name} onChange={(event) => setName(event.target.value)} />
        </div>
        <div className="form-pair">
          <div className="field">
            <label htmlFor="duration">Durata (minuti)</label>
            <input id="duration" type="number" inputMode="numeric" className="input" required min={5} step={5} value={duration} onChange={(event) => setDuration(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="price">Prezzo (€)</label>
            <input id="price" inputMode="decimal" className="input" required placeholder="30,00" value={price} onChange={(event) => setPrice(event.target.value)} />
          </div>
        </div>
        {price !== "" && cents === null ? <p className="small">Scrivi il prezzo in euro, per esempio 30 oppure 30,50.</p> : null}
        <label className="check">
          <input type="checkbox" checked={evaluation} onChange={(event) => setEvaluation(event.target.checked)} />
          <span>
            È una <strong>valutazione</strong>
            <br />
            <span className="small muted">Se si sovrappone a una seduta dell&apos;altro professionista, l&apos;agenda lo segnala (stanza unica).</span>
          </span>
        </label>
        {service !== null ? (
          <p className="small muted">Le sedute già prenotate mantengono il prezzo che avevano.</p>
        ) : null}
        <ErrorNotice error={action.error} />
        <div className="dialog-actions">
          <button type="submit" className="button" disabled={action.running || cents === null}>
            {action.running ? "Salvataggio…" : "Salva"}
          </button>
          <button type="button" className="button button-secondary" onClick={onClose}>
            Annulla
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function PackageForm({
  offer,
  services,
  onClose,
  onSaved,
}: {
  offer: PackageOffer | null;
  services: ServiceOffer[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [serviceId, setServiceId] = useState(offer?.serviceId ?? services[0]?.id ?? 0);
  const [name, setName] = useState(offer?.name ?? "");
  const [sessions, setSessions] = useState(String(offer?.sessionsCount ?? 5));
  const [price, setPrice] = useState(offer === null ? "" : euroInput(offer.priceCents));
  const [validity, setValidity] = useState(String(offer?.validityDays ?? 180));
  const action = useAction();
  const cents = parseEuro(price);

  const save = (event: FormEvent) => {
    event.preventDefault();
    if (cents === null) {
      return;
    }
    void action.run(async () => {
      const details = { name: name.trim(), priceCents: cents, sessionsCount: Number(sessions), validityDays: Number(validity) };
      await unwrap(
        offer === null
          ? api.POST("/api/panel/packages", { body: { ...details, serviceId } })
          : api.PUT("/api/panel/packages/{id}", { params: { path: { id: offer.id } }, body: details }),
      );
      onSaved();
    });
  };

  return (
    <Dialog title={offer === null ? "Nuovo pacchetto" : "Modifica il pacchetto"} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        {offer === null ? (
          <div className="field">
            <label htmlFor="packageService">Servizio</label>
            <select id="packageService" className="input" value={serviceId} onChange={(event) => setServiceId(Number(event.target.value))}>
              {services.map((service) => (
                <option key={service.id} value={service.id}>
                  {service.name}
                </option>
              ))}
            </select>
          </div>
        ) : (
          <p className="small">Servizio: {offer.serviceName}</p>
        )}
        <div className="field">
          <label htmlFor="packageName">Nome</label>
          <input id="packageName" className="input" required maxLength={100} value={name} onChange={(event) => setName(event.target.value)} />
        </div>
        <div className="form-pair">
          <div className="field">
            <label htmlFor="sessions">Sedute</label>
            <input id="sessions" type="number" inputMode="numeric" className="input" required min={1} value={sessions} onChange={(event) => setSessions(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="packagePrice">Prezzo (€)</label>
            <input id="packagePrice" inputMode="decimal" className="input" required placeholder="135,00" value={price} onChange={(event) => setPrice(event.target.value)} />
          </div>
        </div>
        <div className="field">
          <label htmlFor="validity">Validità (giorni dall&apos;acquisto)</label>
          <input id="validity" type="number" inputMode="numeric" className="input" required min={1} value={validity} onChange={(event) => setValidity(event.target.value)} />
        </div>
        {price !== "" && cents === null ? <p className="small">Scrivi il prezzo in euro, per esempio 135 oppure 135,50.</p> : null}
        {offer !== null ? (
          <Notice>
            <p className="small">I pacchetti già acquistati non cambiano: valgono le condizioni di quando sono stati comprati.</p>
          </Notice>
        ) : null}
        <ErrorNotice error={action.error} />
        <div className="dialog-actions">
          <button type="submit" className="button" disabled={action.running || cents === null}>
            {action.running ? "Salvataggio…" : "Salva"}
          </button>
          <button type="button" className="button button-secondary" onClick={onClose}>
            Annulla
          </button>
        </div>
      </form>
    </Dialog>
  );
}
