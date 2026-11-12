"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api, unwrap, type Schemas } from "@/api/client";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { brand } from "@/brand";
import { useDirectory } from "@/lib/directory";
import { creditsText, formatCalendarDay, formatDateTime } from "@/lib/format";
import { ENTRY_KIND, LOT_SOURCE, LOT_STATUS, badgeClass } from "@/lib/labels";

type Detail = Schemas["CreditLotDetail"];
type Entry = Schemas["CreditEntry"];

// One lot with its history, in order of time (D-074). The notes of the practitioner never reach the customer:
// the backend leaves them out
export function LotClient() {
  const id = Number(useParams<{ id: string }>().id);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const directory = useDirectory(detail === null ? [] : [detail.lot.practitionerId]);

  useEffect(() => {
    unwrap(api.GET("/api/customer/credits/{id}", { params: { path: { id } } })).then(setDetail, setError);
  }, [id]);

  if (detail === null) {
    return error === null ? <Loading /> : <ErrorNotice error={error} />;
  }
  const { lot, history } = detail;
  const status = LOT_STATUS[lot.status];

  return (
    <>
      <Link href="/crediti" className="back">
        ‹ I tuoi crediti
      </Link>
      <h1>
        {creditsText(lot.balance)} · {directory.serviceName(lot.practitionerId, lot.serviceId)}
      </h1>
      <p>
        con {directory.practitionerName(lot.practitionerId)} · <span className={badgeClass(status.tone)}>{status.text}</span>
      </p>
      <p className="muted">
        {LOT_SOURCE[lot.source]} · valido fino al {formatCalendarDay(lot.validUntil)}
      </p>
      {lot.status === "PRACTITIONER_UNAVAILABLE" ? (
        <Notice tone="warning">
          <p>Il professionista non è più disponibile, quindi questo credito non si può usare per prenotare. {brand.contactHint}</p>
        </Notice>
      ) : null}

      <h2>Movimenti</h2>
      <ol className="card" style={{ listStyle: "none", paddingLeft: "1rem" }}>
        {history.map((entry, index) => (
          <li key={index} style={{ padding: "0.5rem 0", borderBottom: index < history.length - 1 ? "1px solid var(--color-border)" : "none" }}>
            <div className="spread">
              <span>{entryText(entry)}</span>
              {entry.amount !== null ? <strong>{entry.amount > 0 ? `+${entry.amount}` : `−${Math.abs(entry.amount)}`}</strong> : null}
            </div>
            <span className="small muted">{formatDateTime(entry.at)}</span>
            {entry.bookingId !== null ? (
              <span className="small">
                {" "}
                · <Link href={`/prenotazioni/${entry.bookingId}`}>la seduta</Link>
              </span>
            ) : null}
          </li>
        ))}
      </ol>
    </>
  );
}

function entryText(entry: Entry): string {
  if (entry.kind === "EXTENSION" && entry.oldValidUntil !== null && entry.newValidUntil !== null) {
    return `${ENTRY_KIND.EXTENSION}: dal ${formatCalendarDay(entry.oldValidUntil)} al ${formatCalendarDay(entry.newValidUntil)}`;
  }
  if (entry.kind === "LOT_CREATED" && entry.source !== null) {
    return `${ENTRY_KIND.LOT_CREATED} (${LOT_SOURCE[entry.source].toLowerCase()})`;
  }
  return ENTRY_KIND[entry.kind];
}
