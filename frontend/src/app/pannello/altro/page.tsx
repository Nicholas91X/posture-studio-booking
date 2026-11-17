"use client";

import Link from "next/link";
import { PanelPage } from "@/components/panel/PanelPage";
import { useSession } from "@/lib/session";

const PRACTITIONER_SECTIONS = [
  { href: "/pannello/agende", title: "Agende a confronto", text: "Occupato e libero di tutti, e gli orari liberi insieme per le sedute di coppia." },
  { href: "/pannello/orari", title: "Orari e assenze", text: "Le versioni settimanali degli orari, ferie e assenze." },
  { href: "/pannello/catalogo", title: "Servizi e pacchetti", text: "Cosa offri, a che prezzo, quali servizi sono valutazioni." },
  { href: "/pannello/impostazioni", title: "Impostazioni e notifiche", text: "Approvazione, preavvisi, crediti, le notifiche su questo telefono." },
] as const;

// "Altro": the less frequent sections, and the way out (step 13)
export default function MorePage() {
  return (
    <PanelPage role={["PRACTITIONER", "ADMIN"]}>
      <More />
    </PanelPage>
  );
}

function More() {
  const { session, signOut } = useSession();
  const practitioner = session.state === "signed-in" && session.role === "PRACTITIONER";
  return (
    <>
      <h1>Altro</h1>
      {practitioner
        ? PRACTITIONER_SECTIONS.map((section) => (
            <Link key={section.href} href={section.href} className="card card-link">
              <div className="spread">
                <h2>{section.title}</h2>
                <span aria-hidden="true">›</span>
              </div>
              <p className="small muted">{section.text}</p>
            </Link>
          ))
        : null}
      <div className="card">
        <h2>Esci</h2>
        <p className="small muted">
          Su questo dispositivo dovrai accedere di nuovo con un codice
          {practitioner ? ", e le notifiche smetteranno di arrivare qui" : ""}.
        </p>
        <button type="button" className="button button-secondary button-block" onClick={() => void signOut()}>
          Esci
        </button>
      </div>
    </>
  );
}
