import Link from "next/link";
import type { ReactNode } from "react";
import { formatDateTime, formatTime } from "@/lib/format";
import { badgeClass, PAID_WITH } from "@/lib/labels";
import { customerName, PANEL_BOOKING_STATUS, rowFlags, serviceNameIn, type PanelRow, type ServiceOffer } from "@/lib/panel";

/** The flags of a row as badges (D-049): "fuori orario", "valutazione nella stanza" with the shared times. */
export function RowFlags({ row }: { row: PanelRow }) {
  const flags = rowFlags(row);
  if (flags.length === 0) {
    return null;
  }
  return (
    <div className="row">
      {row.outsideWorkingHours ? <span className="badge badge-warning">⚠ Fuori orario</span> : null}
      {row.evaluationOverlaps.map((overlap) => (
        <span key={overlap.start} className="badge badge-warning">
          ⚠ Valutazione nella stanza {formatTime(overlap.start)}–{formatTime(overlap.end)}
        </span>
      ))}
    </div>
  );
}

// A session of the practitioner in a list: when, who, what, how it is paid, its state and its flags.
// With `children` (the buttons of a request), a card; without, a link to the session's page
export function RowCard({
  row,
  services,
  withDay = false,
  children,
}: {
  row: PanelRow;
  services: readonly ServiceOffer[] | null;
  withDay?: boolean;
  children?: ReactNode;
}) {
  const status = PANEL_BOOKING_STATUS[row.status];
  const body = (
    <>
      <div className="spread">
        <div>
          <p>
            <strong>
              {withDay ? formatDateTime(row.startsAt) : `${formatTime(row.startsAt)}–${formatTime(row.endsAt)}`}
            </strong>
          </p>
          <p>
            {customerName(row)} · {serviceNameIn(services, row.serviceId)}
          </p>
          <p className="small muted">
            {PAID_WITH[row.paidWith]}
            {row.bookedBy === "PRACTITIONER" ? " · prenotata dal pannello" : ""}
          </p>
        </div>
        <span className={badgeClass(status.tone)}>{status.text}</span>
      </div>
      <RowFlags row={row} />
    </>
  );
  if (children === undefined) {
    return (
      <Link href={`/pannello/sedute/${row.id}`} className="card card-link stack">
        {body}
      </Link>
    );
  }
  return (
    <div className="card stack">
      <Link href={`/pannello/sedute/${row.id}`} className="plain-link stack">
        {body}
      </Link>
      {children}
    </div>
  );
}
