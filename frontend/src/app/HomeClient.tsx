"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { ErrorNotice, Loading } from "@/components/Notice";
import { loadPractitioners, type PublicPractitioner } from "@/lib/directory";
import { fullName } from "@/lib/format";
import { homeOf, useSession } from "@/lib/session";

export function HomeClient() {
  const [practitioners, setPractitioners] = useState<PublicPractitioner[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const { session } = useSession();
  const router = useRouter();
  // a practitioner or the admin starts from the panel (step 13): after the sign-in, and from the link of a push,
  // which is the address of the site (D-088). The panel hides what is not theirs; the backend decides
  const staff = session.state === "signed-in" && session.role !== "CUSTOMER";

  useEffect(() => {
    if (session.state === "signed-in" && session.role !== "CUSTOMER") {
      router.replace(homeOf(session.role));
    }
  }, [session, router]);

  useEffect(() => {
    loadPractitioners().then(setPractitioners, setError);
  }, []);

  if (staff || session.state === "loading") {
    return <Loading />;
  }

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
