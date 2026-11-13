import Link from "next/link";

const LABELS = {
  settimana: { previous: "Settimana precedente", next: "Settimana successiva" },
  mese: { previous: "Mese precedente", next: "Mese successivo" },
} as const;

// The title of the period shown, with "Oggi" (hidden when already there) and the two arrows: links, so that the
// browser's back button and a saved address work (D-087). Indigo: it is wayfinding, not an action (D-086)
export function PeriodNav({
  unit,
  title,
  previous,
  next,
  today,
}: {
  unit: keyof typeof LABELS;
  title: string;
  previous: string;
  next: string;
  today: string | null;
}) {
  return (
    <div className="period-nav">
      <h2 className="period-title">{title}</h2>
      {today !== null ? (
        <Link href={today} className="period-today">
          Oggi
        </Link>
      ) : null}
      <Link href={previous} className="period-arrow" aria-label={LABELS[unit].previous}>
        ‹
      </Link>
      <Link href={next} className="period-arrow" aria-label={LABELS[unit].next}>
        ›
      </Link>
    </div>
  );
}
