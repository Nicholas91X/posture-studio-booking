// The only dictionary from an error to an Italian sentence (steps 12 and 13). Nobody sees a technical text:
// a code this build does not know, a status without a code, no answer at all, all get a sentence from here.
// Record<ApiErrorCode, string>: when the contract gains a code, this file stops compiling until it has its sentence.
import { ApiError, type ApiErrorCode } from "./client";

const BY_CODE: Record<ApiErrorCode, string> = {
  // access (D-050, D-055)
  CODE_WRONG: "Il codice non è corretto. Controllalo e riprova.",
  CODE_EXPIRED: "Il codice non è più valido. Chiedine uno nuovo.",
  ACCOUNT_DEACTIVATED: "Questo account è disattivato. Contatta lo studio per riattivarlo.",
  SMS_UNAVAILABLE: "L'accesso con SMS al momento non è disponibile. Usa l'email.",
  PROFILE_INCOMPLETE: "Prima di continuare, indica nome e cognome.",
  CSRF_TOKEN_INVALID: "La pagina è rimasta aperta a lungo. Ricaricala e riprova.",

  // booking (D-045, D-071, D-061)
  SLOT_NOT_AVAILABLE: "Questo orario non è più disponibile. Scegline un altro.",
  BOOKING_LIMIT_REACHED:
    "Hai già il numero massimo di sedute future con questo professionista. Potrai prenotarne un'altra quando una di queste sarà iniziata.",
  CARD_PAYMENTS_UNAVAILABLE:
    "Con questo professionista non si può pagare online, e non hai un suo credito valido per questa seduta. Contatta lo studio per prenotare.",
  PAYMENT_PROVIDER_UNAVAILABLE: "Il servizio di pagamento non risponde. Non è stato addebitato niente: riprova tra qualche minuto.",
  BOOKING_TRANSITION_NOT_ALLOWED: "Questa seduta è cambiata nel frattempo. Ricarica la pagina per vedere com'è adesso.",
  BOOKING_ALREADY_STARTED: "La seduta è già iniziata: non si può più cancellare.",
  APPROVAL_DEADLINE_PASSED: "Il tempo per rispondere a questa richiesta è scaduto.",
  CARD_CAPTURE_FAILED: "Il pagamento con la carta non è andato a buon fine.",
  BOOKING_NOT_STARTED: "La seduta non è ancora iniziata.",
  ATTENDANCE_WINDOW_CLOSED: "Non è più possibile cambiare questa seduta.",

  // codes of the panel and of the admin (step 13): the practitioner and the admin read them
  SERVICE_NOT_ACTIVE: "Questo servizio non è attivo. Attivalo dal catalogo, oppure scegline un altro.",
  SCHEDULE_ALREADY_STARTED:
    "Questa versione degli orari è già in vigore e non si può più cambiare. Creane una nuova che parta da oggi o da una data futura.",
  SCHEDULE_STARTS_IN_THE_PAST: "La data di inizio è già passata. Scegli oggi o un giorno futuro.",
  SCHEDULE_DATE_TAKEN: "Esiste già una versione degli orari che parte da quel giorno. Modifica quella, oppure scegli un'altra data.",
  EMAIL_NOT_A_CUSTOMER: "Questa email appartiene a un professionista o all'amministrazione: non può essere usata per un cliente.",
  EMAIL_ALREADY_IN_USE: "Questa email è già usata da un altro account.",
  CREDIT_VALID_UNTIL_IN_THE_PAST: "La data di scadenza è già passata. Scegli oggi o un giorno futuro.",
  CREDIT_EXTENSION_NOT_LATER: "La nuova scadenza deve essere successiva a quella attuale: la proroga va solo in avanti.",
  CREDIT_BALANCE_INSUFFICIENT: "Il lotto non ha abbastanza crediti: una correzione non può portare il saldo sotto zero.",
  PAYMENT_ACCOUNT_UNKNOWN: "Questo conto di incasso non esiste nella configurazione del server.",
  PAYMENT_LINK_NOT_ALLOWED:
    "Il link di pagamento si può mandare solo per una seduta confermata, da pagare in studio, e che non inizi troppo presto per pagare online.",
  PAYMENT_LINK_ALREADY_PAID: "Il cliente ha appena pagato questa seduta: non serve un altro link.",
  PRACTITIONER_HAS_FUTURE_BOOKINGS:
    "Il professionista ha ancora sedute future o richieste in attesa. Cancellale prima, poi potrai disattivarlo.",
  DATA_CONFLICT: "Qualcun altro ha appena fatto la stessa operazione. Ricarica la pagina e riprova.",
};

// The same code can need other words for the practitioner than for the customer: the panel passes these
export const PANEL_WORDING: Partial<Record<ApiErrorCode, string>> = {
  SLOT_NOT_AVAILABLE:
    "Questo orario non si può prenotare: è nel passato, fuori dai tuoi orari di lavoro, in un'assenza, oppure si sovrappone a un'altra seduta.",
  CARD_PAYMENTS_UNAVAILABLE: "Non hai ancora un conto di incasso: l'amministrazione deve assegnartelo prima che tu possa mandare link di pagamento.",
  BOOKING_TRANSITION_NOT_ALLOWED: "Questa seduta è cambiata nel frattempo. Ricarica la pagina per vedere com'è adesso.",
  BOOKING_ALREADY_STARTED: "La seduta è già iniziata: non si può più cancellare.",
  BOOKING_NOT_STARTED: "La seduta non è ancora iniziata: potrai segnarla dopo l'inizio.",
  ATTENDANCE_WINDOW_CLOSED: "Sono passati più di 7 giorni dalla fine della seduta: non si può più cambiare.",
  CARD_CAPTURE_FAILED:
    "L'incasso dalla carta non è riuscito (blocco scaduto o carta rifiutata). La richiesta resta in attesa: puoi rifiutarla.",
};

const GENERIC = "Qualcosa non ha funzionato. Riprova tra poco.";

function knownCode(code: string | undefined): code is ApiErrorCode {
  return code !== undefined && Object.hasOwn(BY_CODE, code);
}

/** The sentence to show for any error thrown by a call (or by anything else: never a technical text). */
export function messageFor(error: unknown, wording: Partial<Record<ApiErrorCode, string>> = {}): string {
  if (!(error instanceof ApiError)) {
    return GENERIC;
  }
  if (knownCode(error.code)) {
    const own = wording[error.code];
    if (own !== undefined) {
      return own;
    }
    return BY_CODE[error.code];
  }
  switch (error.status) {
    case 0:
      return "Il servizio non è raggiungibile. Controlla la connessione e riprova.";
    case 400:
      return "Alcuni dati non sono validi. Controllali e riprova.";
    case 401:
      return "Devi accedere di nuovo.";
    case 403:
      return "Questa pagina non è disponibile per il tuo account.";
    case 404:
      return "Non abbiamo trovato quello che cercavi.";
    case 429:
      return error.retryAfterSeconds === undefined
        ? "Troppi tentativi. Riprova tra qualche minuto."
        : `Troppi tentativi. Riprova tra ${waitText(error.retryAfterSeconds)}.`;
    case 503:
      return "Il servizio è momentaneamente non disponibile. Riprova tra qualche minuto.";
    default:
      return GENERIC;
  }
}

function waitText(seconds: number): string {
  if (seconds < 60) {
    return seconds === 1 ? "un secondo" : `${seconds} secondi`;
  }
  const minutes = Math.ceil(seconds / 60);
  if (minutes < 60) {
    return minutes === 1 ? "un minuto" : `${minutes} minuti`;
  }
  const hours = Math.ceil(minutes / 60);
  return hours === 1 ? "un'ora" : `${hours} ore`;
}
