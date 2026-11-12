import type { PublicPractitioner } from "@/lib/directory";
import { fullName, hoursText } from "@/lib/format";
import { Notice } from "./Notice";

// What the customer must know before choosing a time (D-030, D-084): who confirms and how fast, until when a
// cancellation is free, whether the session can be paid online. The values are the practitioner's settings, read
// from the backend; the notice of each booking is decided by the backend when it is made (D-079).
export function PractitionerFacts({ practitioner }: { practitioner: PublicPractitioner }) {
  return (
    <>
      {practitioner.onlineBooking ? null : (
        <Notice tone="warning">
          <p>
            Con {fullName(practitioner)} non si può pagare online. Puoi prenotare solo se hai già un suo credito per il
            servizio (per esempio da un pacchetto o un omaggio dello studio). Altrimenti contatta lo studio.
          </p>
        </Notice>
      )}
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
