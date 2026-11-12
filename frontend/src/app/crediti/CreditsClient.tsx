"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { brand } from "@/brand";
import { useDirectory, type Directory } from "@/lib/directory";
import { creditsText, formatCalendarDay, formatTime } from "@/lib/format";
import { LOT_SOURCE, LOT_STATUS, badgeClass } from "@/lib/labels";
import { pollUntil } from "@/lib/poll";

type Lot = Schemas["CreditLotSummary"];
type Purchase = Schemas["PackagePurchaseView"];
type Waiting = "no" | "yes" | "timed-out";

export function CreditsClient() {
  const purchaseReturn = useSearchParams().get("acquisto");
  const [lots, setLots] = useState<Lot[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [purchase, setPurchase] = useState<Purchase | null>(null);
  const [waiting, setWaiting] = useState<Waiting>("no");
  const directory = useDirectory(lots?.map((lot) => lot.practitionerId) ?? []);

  const readLots = useCallback(() => unwrap(api.GET("/api/customer/credits")).then(setLots, setError), []);

  // the purchase the customer comes back from: the latest one (the return address names no purchase)
  const latestPurchase = useCallback(async () => {
    const all = await unwrap(api.GET("/api/customer/package-purchases"));
    return all.reduce<Purchase | null>((latest, one) => (latest === null || one.id > latest.id ? one : latest), null);
  }, []);

  // back from Stripe after paying: the lot is born with the webhook, which arrives on its own (D-007, D-070)
  const waitForPurchase = useCallback(
    async (signal?: AbortSignal) => {
      setWaiting("yes");
      const outcome = await pollUntil(latestPurchase, (found) => found === null || found.status !== "PENDING", {
        everyMs: 2000,
        forMs: 60_000,
        ...(signal === undefined ? {} : { signal }),
      });
      if (signal?.aborted) {
        return;
      }
      setPurchase(outcome.value ?? null);
      setWaiting(outcome.done ? "no" : "timed-out");
      if (outcome.done) {
        void readLots();
      }
    },
    [latestPurchase, readLots],
  );

  useEffect(() => {
    void readLots();
  }, [readLots]);

  useEffect(() => {
    const abort = new AbortController();
    if (purchaseReturn === "completato") {
      void waitForPurchase(abort.signal);
    } else if (purchaseReturn === "annullato") {
      latestPurchase().then((found) => !abort.signal.aborted && setPurchase(found), () => undefined);
    }
    return () => abort.abort();
  }, [purchaseReturn, waitForPurchase, latestPurchase]);

  const open = lots?.filter((lot) => lot.status === "USABLE" || lot.status === "PRACTITIONER_UNAVAILABLE") ?? [];
  const closed = lots?.filter((lot) => lot.status === "USED_UP" || lot.status === "EXPIRED") ?? [];

  return (
    <>
      <h1>I tuoi crediti</h1>
      <p className="muted">Un credito vale una seduta di quel servizio con quel professionista, entro il suo ultimo giorno valido.</p>

      <PurchaseMessage purchaseReturn={purchaseReturn} purchase={purchase} waiting={waiting} onRetry={() => void waitForPurchase()} />
      <ErrorNotice error={error} />
      {lots === null && error === null ? <Loading /> : null}
      {lots?.length === 0 ? (
        <p>
          Non hai crediti. Puoi acquistare un pacchetto dalla pagina di un professionista. <Link href="/">Vai ai professionisti</Link>
        </p>
      ) : null}

      {open.map((lot) => (
        <LotCard key={lot.id} lot={lot} directory={directory} />
      ))}
      {closed.length > 0 ? (
        <details>
          <summary className="muted" style={{ minHeight: "var(--tap)", cursor: "pointer" }}>
            Esauriti e scaduti ({closed.length})
          </summary>
          {closed.map((lot) => (
            <LotCard key={lot.id} lot={lot} directory={directory} />
          ))}
        </details>
      ) : null}
    </>
  );
}

function LotCard({ lot, directory }: { lot: Lot; directory: Directory }) {
  const status = LOT_STATUS[lot.status];
  return (
    <Link href={`/crediti/${lot.id}`} className="card card-link">
      <div className="spread">
        <div>
          <h3>
            {creditsText(lot.balance)} · {directory.serviceName(lot.practitionerId, lot.serviceId)}
          </h3>
          <p className="small" style={{ marginBottom: "0.25rem" }}>
            con {directory.practitionerName(lot.practitionerId)}
          </p>
          <p className="small muted" style={{ marginBottom: 0 }}>
            {LOT_SOURCE[lot.source]} · valido fino al {formatCalendarDay(lot.validUntil)}
          </p>
        </div>
        <span className={badgeClass(status.tone)}>{status.text}</span>
      </div>
      {lot.status === "PRACTITIONER_UNAVAILABLE" ? (
        <p className="small" style={{ marginTop: "0.5rem", marginBottom: 0 }}>
          Il professionista non è più disponibile, quindi questo credito non si può usare per prenotare. {brand.contactHint}
        </p>
      ) : null}
    </Link>
  );
}

function PurchaseMessage({
  purchaseReturn,
  purchase,
  waiting,
  onRetry,
}: {
  purchaseReturn: string | null;
  purchase: Purchase | null;
  waiting: Waiting;
  onRetry: () => void;
}) {
  if (waiting === "yes") {
    return (
      <Notice>
        <p>
          <span className="spinner" aria-hidden="true" /> Pagamento in verifica: aspettiamo la conferma di Stripe…
        </p>
      </Notice>
    );
  }
  if (waiting === "timed-out") {
    return (
      <Notice tone="warning">
        <p>
          La conferma del pagamento non è ancora arrivata. Se hai pagato, non pagare di nuovo: a volte serve qualche minuto, e
          quando arriva ti mandiamo un&apos;email.
        </p>
        <button type="button" className="button button-secondary" onClick={onRetry}>
          Controlla di nuovo
        </button>
      </Notice>
    );
  }
  if (purchase === null || purchaseReturn === null) {
    return null;
  }
  if (purchase.status === "PAID") {
    return (
      <Notice tone="success">
        <p>
          Acquisto confermato: {creditsText(purchase.credits)}
          {purchase.validUntil !== null ? `, validi fino al ${formatCalendarDay(purchase.validUntil)}` : ""}.
        </p>
      </Notice>
    );
  }
  if (purchase.status === "PENDING" && purchase.paymentUrl !== null) {
    return (
      <Notice tone="warning">
        <p>
          Il pagamento non è stato completato.
          {purchase.pageExpiresAt !== null ? ` Puoi riprenderlo fino alle ${formatTime(purchase.pageExpiresAt)}.` : ""}
        </p>
        <a className="button" href={purchase.paymentUrl}>
          Riprendi il pagamento
        </a>
      </Notice>
    );
  }
  return (
    <Notice tone="warning">
      <p>Il pagamento non è stato completato e non ti è stato addebitato niente.</p>
    </Notice>
  );
}
