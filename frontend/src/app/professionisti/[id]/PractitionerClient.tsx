"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { PractitionerFacts } from "@/components/PractitionerFacts";
import { loadCatalog, loadPractitioners, type PublicCatalog, type PublicPractitioner } from "@/lib/directory";
import { creditsText, formatCalendarDay, formatPrice, fullName, sessionsText } from "@/lib/format";
import { signInPath } from "@/lib/navigation";
import { isCustomer, useSession } from "@/lib/session";

type Lot = Schemas["CreditLotSummary"];

export function PractitionerClient() {
  const id = Number(useParams<{ id: string }>().id);
  const router = useRouter();
  const { session } = useSession();
  const [practitioner, setPractitioner] = useState<PublicPractitioner | null | undefined>(undefined);
  const [catalog, setCatalog] = useState<PublicCatalog | null | undefined>(undefined);
  const [lots, setLots] = useState<Lot[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [buying, setBuying] = useState<number | null>(null);

  useEffect(() => {
    Promise.all([loadPractitioners(), loadCatalog(id)]).then(([list, found]) => {
      setPractitioner(list.find((one) => one.id === id) ?? null);
      setCatalog(found);
    }, setError);
  }, [id]);

  const customer = isCustomer(session);
  useEffect(() => {
    if (customer) {
      // the state of each lot is the backend's (D-074): only the usable ones are shown here
      unwrap(api.GET("/api/customer/credits")).then(
        (all) => setLots(all.filter((lot) => lot.practitionerId === id && lot.status === "USABLE")),
        () => setLots([]),
      );
    }
  }, [customer, id]);

  async function buy(packageOfferId: number) {
    if (!customer) {
      router.push(signInPath(`/professionisti/${id}`));
      return;
    }
    setBuying(packageOfferId);
    setError(null);
    try {
      const purchase = await unwrap(api.POST("/api/customer/package-purchases", { body: { packageOfferId } }));
      if (purchase.paymentUrl !== null) {
        // to Stripe's page; coming back proves nothing, the page of the credits asks the backend (D-007)
        window.location.assign(purchase.paymentUrl);
        return;
      }
      router.push("/crediti");
    } catch (failure) {
      setError(failure);
      setBuying(null);
    }
  }

  if (error !== null && practitioner === undefined) {
    return <ErrorNotice error={error} />;
  }
  if (practitioner === undefined || catalog === undefined) {
    return <Loading />;
  }
  if (practitioner === null || catalog === null) {
    return (
      <Notice tone="warning">
        <p>Questo professionista non è disponibile.</p>
        <p>
          <Link href="/">Torna all&apos;elenco</Link>
        </p>
      </Notice>
    );
  }

  const serviceName = (serviceId: number) => catalog.services.find((service) => service.id === serviceId)?.name ?? "Seduta";

  return (
    <>
      <Link href="/" className="back">
        ‹ Professionisti
      </Link>
      <h1>{fullName(practitioner)}</h1>
      <PractitionerFacts practitioner={practitioner} />
      <ErrorNotice error={error} />

      {lots.length > 0 ? (
        <Notice tone="success">
          <p>
            <strong>I tuoi crediti con {practitioner.firstName}</strong>
          </p>
          {lots.map((lot) => (
            <p key={lot.id} className="small">
              {creditsText(lot.balance)} per {serviceName(lot.serviceId)}, fino al {formatCalendarDay(lot.validUntil)}
            </p>
          ))}
          <p className="small">Se un credito vale per il giorno della seduta, la prenotazione lo usa da sola, senza pagare.</p>
        </Notice>
      ) : null}

      <h2>Servizi</h2>
      {catalog.services.length === 0 ? <p className="muted">Nessun servizio prenotabile al momento.</p> : null}
      {catalog.services.map((service) => (
        <Link key={service.id} href={`/professionisti/${id}/servizi/${service.id}`} className="card card-link">
          <div className="spread">
            <div>
              <h3>{service.name}</h3>
              <p className="small muted">{service.durationMinutes} minuti</p>
            </div>
            <span className="price">{formatPrice(service.priceCents)}</span>
          </div>
          <p className="small" style={{ marginBottom: 0 }}>
            Scegli giorno e ora ›
          </p>
        </Link>
      ))}

      {catalog.packages.length > 0 ? (
        <>
          <h2>Pacchetti</h2>
          {catalog.packages.map((offer) => (
            <div key={offer.id} className="card">
              <div className="spread">
                <div>
                  <h3>{offer.name}</h3>
                  <p className="small muted">
                    {sessionsText(offer.sessionsCount)} di {serviceName(offer.serviceId)}, valide {offer.validityDays} giorni
                    dall&apos;acquisto
                  </p>
                </div>
                <span className="price">{formatPrice(offer.priceCents)}</span>
              </div>
              <button
                type="button"
                className="button button-secondary button-block"
                disabled={!practitioner.onlineBooking || buying !== null}
                onClick={() => void buy(offer.id)}
              >
                {buying === offer.id ? "Apertura del pagamento…" : customer ? "Acquista" : "Accedi per acquistare"}
              </button>
            </div>
          ))}
        </>
      ) : null}
    </>
  );
}
