"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ErrorNotice, Loading } from "@/components/Notice";
import { loadPractitioners, type PublicPractitioner } from "@/lib/directory";
import { fullName } from "@/lib/format";

export function HomeClient() {
  const [practitioners, setPractitioners] = useState<PublicPractitioner[] | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    loadPractitioners().then(setPractitioners, setError);
  }, []);

  return (
    <>
      <h1>Prenota una seduta</h1>
      <p className="muted">Scegli il professionista.</p>
      <ErrorNotice error={error} />
      {practitioners === null && error === null ? <Loading /> : null}
      {practitioners?.length === 0 ? <p>Al momento non ci sono professionisti disponibili.</p> : null}
      {practitioners?.map((practitioner) => (
        <Link key={practitioner.id} href={`/professionisti/${practitioner.id}`} className="card card-link">
          <div className="spread">
            <h2>{fullName(practitioner)}</h2>
            <span aria-hidden="true">›</span>
          </div>
          {practitioner.onlineBooking ? null : (
            <p className="small muted">Pagamento online non disponibile: si prenota con un credito.</p>
          )}
        </Link>
      ))}
    </>
  );
}
