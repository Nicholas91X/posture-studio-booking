# La cancellazione da parte dello studio — guida di studio

Questa guida accompagna il **pezzo 3 del passo 11**: il professionista cancella una sua seduta futura, e il cliente non perde niente (D-075). È il pezzo più corto del passo, perché quasi tutto c'è già dal pezzo 2: la regola del credito con `CancelledBy.STUDIO`, l'evento `BookingCancelled` con il testo per lo studio, l'ascoltatore che chiude il link aperto.

```
POST /api/panel/bookings/{id}/cancel
     │
     ├─ lucchetto sulla seduta (D-046), solo le sue (404 per quella di un altro)
     ├─ CONFIRMED, non iniziata → CANCELLED_BY_STUDIO, a prescindere dal preavviso
     │     ├─ credito  → +1 sullo stesso lotto, o lotto nuovo: la validità minima SEMPRE (D-076)
     │     ├─ carta    → lotto nuovo da 1
     │     └─ in studio → niente; il link aperto si chiude dopo il commit (pezzo 2)
     ├─ PENDING_APPROVAL → il rifiuto di sempre (D-078): blocco annullato o +1
     └─ evento BookingCancelled → email al cliente ("lo studio ha dovuto cancellare"), nessuna push
```

Teoria di riferimento: D-015 e D-075 (niente rimborsi, procedura a mano), D-030 (rifiuto), D-046 (lucchetto), D-076 (validità minima, eccezione dello studio), D-077 (professionista che lascia), D-078 (lo stato, `cancelled_by`), D-079 (la cancellazione del cliente).

---

## Indice

- Parte 1 — Cosa c'è già
- Parte 2 — Prima scelta: una porta o due per le richieste in attesa
- Parte 3 — Seconda scelta: cosa risponde l'API
- Parte 4 — Terza scelta: il secondo clic
- Parte 5 — Le corse
- Parte 6 — Cosa sa il cliente, e cosa può chiedere
- Parte 7 — I test
- Parte 8 — Cosa c'è da decidere
- Parte 9 — Com'è andata (implementazione)
- Domande da colloquio

---

## Parte 1 — Cosa c'è già

- `BookingStatus`: `CONFIRMED` → `CANCELLED_BY_STUDIO` è già una freccia (V16, D-078), ed è l'unica che porta lì.
- `CreditService.restoreOnCancellation(..., CancelledBy.STUDIO)`: la validità minima **sempre** garantita, anche a un credito già allungato (D-076, provato al pezzo 2); `lotFromCancellation` per la carta.
- `BookingService.giveBack(booking, who)`: lo `switch` su come era pagata; ora con `STUDIO`.
- `BookingEmails`: per `CANCELLED_BY_STUDIO` scrive solo al cliente ("lo studio ha dovuto cancellare la tua seduta" e quello che gli resta); `PushMessages`: nessuna push (lo studio sa cosa ha fatto).
- `PaymentLinkClosing`: chiude il link aperto per **ogni** `BookingCancelled` di una seduta "in studio", qualunque sia lo stato raggiunto.
- `BookingService.reject`: il rifiuto di una richiesta in attesa, con la scadenza della risposta (`APPROVAL_DEADLINE_PASSED` dopo) e la restituzione.

Manca: l'API, la transizione nell'entità, le regole (seduta iniziata, stati), il secondo clic.

---

## Parte 2 — Prima scelta: una porta o due per le richieste in attesa

D-078 ha deciso che una richiesta in attesa cancellata dallo studio **è un rifiuto** (`REJECTED`). Resta la forma dell'API:

- **A — una porta sola**: `POST /api/panel/bookings/{id}/cancel` accetta sia una seduta confermata (→ `CANCELLED_BY_STUDIO`) sia una richiesta in attesa (→ il rifiuto di sempre, chiamando lo stesso codice di `reject`). Il pannello ha un pulsante "Cancella" su ogni seduta futura, e non deve sapere in quale stato è.
- B — due porte: `/cancel` solo per le confermate, `/reject` (che c'è già) per le richieste. Più esplicito, ma il pannello deve scegliere il pulsante giusto, e il brief dice "il professionista cancella una sua seduta futura (confermata o in attesa)".

**Raccomandazione: A**, con il rifiuto **identico** a quello di `/reject`: stessa scadenza della risposta (dopo, 409 `APPROVAL_DEADLINE_PASSED`, perché la richiesta è già annullata e il lavoro la chiude entro un minuto), stessa restituzione, stessa email. Un fatto, un codice: `/cancel` chiama `reject`, non lo ricopia.

Gli altri stati: `PENDING_PAYMENT` (il cliente sta pagando: 409, la macchina a stati lo rifiuta già) e tutti quelli finiti (409). Una seduta **iniziata** non si cancella, come per il cliente: 409 `BOOKING_ALREADY_STARTED`. "Futura" nel brief vuol dire proprio questo.

**A prescindere dal preavviso**: per lo studio `free_cancellation_until` non conta. Lo stato è sempre `CANCELLED_BY_STUDIO`, anche cinque minuti prima dell'inizio.

---

## Parte 3 — Seconda scelta: cosa risponde l'API

La procedura del rimborso (D-075, `procedure-studio.md`) comincia con "azzera il credito nato dalla cancellazione". Per farlo il professionista deve sapere **quale** lotto: oggi lo troverebbe nella lista dei crediti del cliente (D-074), cercando il lotto `CANCELLATION` con la data giusta.

- **A — la risposta dice anche cosa è tornato**: un `record` nuovo, `PanelCancellationView(booking, returnedCredit)`, con la seduta come la vede il pannello (`PanelBookingView`) e il credito tornato (`lotId`, `validUntil`, o `null`). Il pannello può offrire subito "il cliente vuole i soldi? Azzera questo credito".
- B — solo `PanelBookingView`, come approvazione e rifiuto.

**Raccomandazione: A.** Un record nuovo, e non un campo nuovo in `PanelBookingView`: quella vista è un contratto fissato campo per campo da `BookingPanelApiTest` (la lezione della guida 43, parte 13), e il credito tornato ha senso solo qui. Per una richiesta in attesa (il rifiuto) la risposta ha lo stesso formato, con il credito che è tornato al suo lotto, o `null` per la carta.

---

## Parte 4 — Terza scelta: il secondo clic

Come per il cliente (D-079): un secondo `/cancel` su una seduta **già cancellata dallo studio** risponde **200 con la stessa risposta**, senza effetti nuovi. Per una richiesta in attesa già **rifiutata**, lo stesso: il rifiuto è quello che `/cancel` produce, e il secondo clic deve vedere il suo risultato, non un errore. In ogni altro stato (cancellata dal cliente nel frattempo, scaduta, svolta) 409: lì il professionista sta chiedendo una cosa che non è successa per mano sua.

Gli effetti, una volta sola: il lucchetto sulla seduta, e l'indice "una sola restituzione per prenotazione" se un bug lo saltasse.

---

## Parte 5 — Le corse

| Corsa | Esito |
|---|---|
| studio ↔ cliente che cancella la stessa seduta | il lucchetto le mette in fila: chi arriva secondo trova la seduta già cancellata dall'altro, e riceve 409 (non è il suo risultato) |
| studio ↔ cliente che ritira la stessa richiesta | idem: un solo esito, un solo +1 o un solo annullamento |
| studio ↔ approvazione automatica | non si incontrano sulla stessa seduta in due stati diversi: il lucchetto decide l'ordine, e chi arriva dopo vede lo stato nuovo |
| studio ↔ webhook di un link | come per il cliente (D-079): il webhook prima → seduta pagata con carta → lotto da 1; lo studio prima → il pagamento si annulla |

Nessun lucchetto nuovo: la seduta, come sempre. La cancellazione dello studio non tocca il pagamento dentro la sua transazione.

---

## Parte 6 — Cosa sa il cliente, e cosa può chiedere

L'email c'è già (pezzo 2): "lo studio ha dovuto cancellare la tua seduta", e poi quello che gli resta:
- con un credito o con la carta: "Ti abbiamo restituito un credito per questa seduta: puoi usarlo per prenotarne un'altra entro …";
- in studio: "La seduta era da pagare in studio: non c'è niente da restituire.";
- per una richiesta in attesa, l'email del rifiuto: "la tua richiesta non è stata accettata", con il blocco annullato o il credito di nuovo disponibile.

**Il rimborso non è nell'email.** D-075: il sistema non lo offre, e scriverlo in ogni email lo trasformerebbe in un'opzione standard. Se il cliente lo chiede, il professionista segue la procedura (prima la correzione che azzera il credito, poi il rimborso da Stripe). Il cliente riceverà l'email della correzione (D-073) e la ricevuta del rimborso da Stripe.

**Nessuna nota del professionista per il cliente** ("mi dispiace, sono malato"): già scartata in D-078, si aggiunge se lo studio la chiede.

---

## Parte 7 — I test

- **Gli effetti**, a prescindere dal preavviso: una seduta con carta cancellata **dentro** il preavviso (sarebbe tardi per il cliente) → `CANCELLED_BY_STUDIO` e lotto da 1; con un credito → +1 sullo stesso lotto; con un credito di un lotto `CANCELLATION` che scade presto → lotto nuovo (l'eccezione dello studio, con il percorso vero); in studio → niente.
- **La risposta**: `returnedCredit` con il lotto e il giorno; `null` in studio.
- **La traccia**: `cancelled_by` è l'account del professionista.
- **Le richieste in attesa**: con la carta → `REJECTED` e il blocco annullato (sul finto Stripe); con un credito → `REJECTED` e +1; dopo la scadenza della risposta → 409 `APPROVAL_DEADLINE_PASSED`.
- **Le regole**: la seduta di un altro professionista (404), una seduta iniziata (409 `BOOKING_ALREADY_STARTED`), `PENDING_PAYMENT` (409), cancellata dal cliente nel frattempo (409).
- **Il secondo clic**: su una cancellata dallo studio e su una rifiutata, 200 con la stessa risposta e nessun effetto in più.
- **L'evento**: `BookingCancelled` con `CANCELLED_BY_STUDIO`; per una richiesta, `BookingRejected`.
- **Il link aperto** (commit veri): cancellata dallo studio, la pagina scade.
- **Le difese tolte a turno**: "a prescindere dal preavviso" (con la regola del cliente la seduta dentro il preavviso finirebbe senza credito), `STUDIO` al posto di `CUSTOMER` nel credito, "seduta iniziata", il secondo clic, la richiesta in attesa che passa da `reject`.

---

## Parte 8 — Cosa c'è da decidere

| # | Scelta | Raccomandazione |
|---|---|---|
| 1 | Le richieste in attesa | una porta sola, `/cancel`, che per una richiesta chiama il rifiuto di sempre (parte 2) |
| 2 | Una seduta iniziata | 409 `BOOKING_ALREADY_STARTED`, come per il cliente |
| 3 | La risposta | `PanelCancellationView` con il credito tornato, un record nuovo (parte 3) |
| 4 | Il secondo clic | 200 con la stessa risposta su una cancellata dallo studio o rifiutata (parte 4) |
| 5 | Il rimborso nell'email | no (parte 6) |

Nessuna cambia una decisione registrata. L'admin che cancella per conto di un professionista irraggiungibile (D-077, punto 1) è una domanda del pezzo 4.

**Scelte**: Nicholas ha chiesto di procedere senza conferme se non c'erano domande specifiche per lui; tutte e cinque seguono le raccomandazioni (D-080).

---

## Parte 9 — Com'è andata (implementazione)

**File**: `Booking.cancelByStudio`; in `BookingService` il nuovo `cancelByStudio` e il rifiuto estratto in `rejectLocked` (usato da `/reject` e da `/cancel`), più `cancellationView` (la risposta letta dal registro, anche al secondo clic); `PanelCancellationView`; `BookingPanelController` con `CurrentUser`. Nessuna migrazione, nessun cambio nei moduli `credit`, `payment` e `notification`: il pezzo 2 aveva già messo i pezzi giusti al posto giusto (`CancelledBy`, l'email dello studio, l'ascoltatore che non guarda chi ha cancellato).

**Test**: `BookingStudioCancellationApiTest` (14: dentro il preavviso con la carta, con un credito, con un credito già allungato che scade presto, in studio, la traccia e l'evento; le richieste in attesa con la carta, con un credito e dopo la scadenza; un altro professionista, la seduta iniziata, una richiesta che si sta pagando, una seduta cancellata dal cliente nel frattempo; il secondo clic su una seduta e su una richiesta) e, con commit veri, il link chiuso anche quando cancella lo studio (`BookingCancellationLinkTest`).

**Provato** togliendo cinque difese a turno: tutte prese dal loro test, ognuna per il motivo giusto (lo stato HTTP o il giorno atteso, controllati nel registro di Maven). Il test che distingue "a prescindere dal preavviso" usa una seduta cancellata **dentro** il preavviso: una seduta lontana sarebbe passata con tutte e due le regole (domanda 4).

---

## Domande da colloquio

### Domanda 1: Due azioni diverse dell'utente producono lo stesso fatto (qui "cancella una richiesta" e "rifiuta una richiesta"). Come le scrivi?
**Risposta modello.** Il fatto ha un solo codice: una porta chiama l'altra, non la ricopia. Così la regola (la scadenza della risposta, la restituzione, l'email) vive in un posto solo, e un domani una correzione la cambia per tutte e due. L'API può avere due porte per comodità di chi la usa, ma dietro c'è un metodo solo, e un test che lo dimostra (stesso stato, stessa email, stessa scadenza).

### Domanda 2: La stessa regola ha un'eccezione per un attore (qui: il preavviso non vale per lo studio). Dove la metti?
**Risposta modello.** Dove si decide chi agisce, non dentro la regola generale con un `if` nascosto. Qui ci sono due metodi con nomi diversi, la cancellazione del cliente e quella dello studio, e la differenza è esplicita: uno guarda il preavviso, l'altro no; e passano al modulo dei crediti chi ha cancellato (`CancelledBy`), che decide lui se allungare. La regola del credito resta una sola, con il suo parametro e un test per ogni valore.

### Domanda 3: Perché un `record` nuovo per la risposta invece di un campo in più nella vista esistente?
**Risposta modello.** Una vista è un contratto con il client. Un campo che ha senso solo in una risposta, aggiunto a una vista usata da tante, cambia il contratto di tutte e resta quasi sempre vuoto. Un record nuovo che contiene la vista di sempre più l'informazione nuova non tocca le altre risposte, e dice esattamente cosa restituisce questa.

### Domanda 4: Come provi che una regola "a prescindere da" funziona davvero?
**Risposta modello.** Con il caso in cui la regola normale darebbe un risultato diverso. Qui una seduta cancellata dentro il preavviso: per il cliente sarebbe tardi e senza credito; per lo studio il credito c'è. Un test con una seduta lontana passerebbe con tutte e due le regole, e non proverebbe niente.
