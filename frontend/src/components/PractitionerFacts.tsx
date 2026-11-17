import type { PublicPractitioner } from "@/lib/directory";
import { fullName, hoursText } from "@/lib/format";
import { Notice } from "./Notice";

// What the customer must know before choosing a time (D-030, D-084, D-089): who confirms and how fast, until when a
// cancellation is free, how the session is paid (online by card, or in the studio). The values are the practitioner's settings, read
// from the backend; the notice of each booking is decided by the backend when it is made (D-079).
export function PractitionerFacts({ practitioner }: { practitioner: PublicPractitioner }) {
  return (
    <>
      {practitioner.paymentMode === "IN_STUDIO" ? (
        <Notice>
          <p>
            Con {fullName(practitioner)} le sedute <strong>si pagano in studio</strong>, il giorno della seduta: online prenoti
            senza pagare niente. Se hai un suo credito valido, si usa quello. I pacchetti si acquistano in studio.
          </p>
        </Notice>
      ) : null}
      <ul className="small muted">
        <li>
          {practitioner.bookingApproval === "MANUAL"
            ? `Ogni richiesta è approvata dal professionista entro ${hoursText(practitioner.approvalWindowHours)}. Fino ad allora puoi ritirarla senza nessun costo.`
            : "Le prenotazioni si confermano subito, senza attendere l'approvazione."}
        </li>
        <li>
          Puoi cancellare senza perdere la seduta fino a {hoursText(practitioner.cancellationNoticeHours)} prima
          dell&apos;inizio.
        </li>
      </ul>
    </>
  );
}
