# La cancellazione del cliente: il confine del preavviso, il credito che torna, il link da chiudere — guida di studio

Questa guida accompagna il **pezzo 2 del passo 11**: il cliente cancella una sua seduta confermata. In tempo riceve il credito; tardi lo perde; una seduta da pagare in studio non dà niente, ma il suo link di pagamento aperto va chiuso.

```
POST /api/customer/bookings/{id}/cancel
     │
     ├─ lucchetto sulla seduta (D-046)
     ├─ in tempo?  now ≤ free_cancellation_until   (V16, fotografato alla prenotazione)
     │     ├─ credito  → +1 sullo stesso lotto, o lotto nuovo con la validità minima (D-076)
     │     ├─ carta    → lotto nuovo da 1
     │     └─ in studio → niente
     ├─ tardi → niente
     ├─ evento BookingCancelled  → email al cliente, push al professionista
     └─ dopo il commit, solo "in studio": il link aperto si fa scadere su Stripe (D-065)
```

Teoria di riferimento: D-015 (credito, mai rimborso), D-045 e D-046 (lucchetti, `READ COMMITTED`), D-053 e D-054 (email e push dopo il commit), D-059 e D-065 (link di pagamento, "il primo vince"), D-064 (compensazioni che si riprovano), D-069 (registro, `ValidUntil`), D-071 (il credito copre il giorno della seduta), D-076 (validità minima, "una volta sola"), D-078 (V16).

---

## Indice

- Parte 1 — Cosa c'è già
- Parte 2 — Prima scelta: chi può cancellare cosa
- Parte 3 — Seconda scelta: il confine del preavviso, e come si prova al secondo
- Parte 4 — Terza scelta: cosa torna, nel modulo `credit`
- Parte 5 — Quarta scelta: il link aperto, una compensazione verso Stripe
- Parte 6 — Quinta scelta: il doppio clic e l'idempotenza
- Parte 7 — Le corse: quali lucchetti
- Parte 8 — Sesta scelta: chi lo sa (email e push)
- Parte 9 — Cosa vede il cliente
- Parte 10 — I test
- Parte 11 — Cosa c'è da decidere
- Parte 12 — Che cosa c'è nel codice
- Parte 13 — Com'è andata (implementazione)
- Domande da colloquio

---

## Parte 1 — Cosa c'è già

- **Il ritiro** (`withdraw`, passo 7): il cliente rinuncia a una richiesta in attesa. È lo stampo: lucchetto sulla seduta, transizione nell'entità, restituzione (`releasePayment`), evento.
- **La V16** (pezzo 1): gli stati `CANCELLED_IN_TIME` e `CANCELLED_LATE` con le loro frecce da `CONFIRMED`, `cancelled_by`, e soprattutto `free_cancellation_until`, il preavviso già calcolato alla prenotazione.
- **Il registro dei crediti**: `CreditService.release` scrive +1 sullo stesso lotto, una volta sola; l'indice "una sola restituzione per prenotazione" vede dalla V16 anche il lotto nuovo nato da una cancellazione.
- **Il webhook del link** (D-065): un link pagato si incassa solo se la seduta è ancora `CONFIRMED` e `IN_STUDIO`, con il lucchetto sulla seduta; altrimenti il blocco si annulla.
- `practitioner.single_credit_validity_days` (90): la validità del credito nuovo e della validità minima.

---

## Parte 2 — Prima scelta: chi può cancellare cosa

- **Solo le sedute `CONFIRMED` del cliente.** Quella di un altro è un 404, come quella inesistente (D-026). Una richiesta in attesa non si cancella: si **ritira** (passo 7, gratis). Ogni altro stato: 409 `BOOKING_TRANSITION_NOT_ALLOWED`, la macchina a stati lo dice già.
- **Una seduta già iniziata non si cancella.** Lo stato è ancora `CONFIRMED` finché il lavoro del pezzo 5 non la chiude, ma "cancellare tardi" una seduta in corso, o finita da un'ora, non ha senso: è svolta o non presentata. **Raccomandazione**: 409 con un codice proprio, **`BOOKING_ALREADY_STARTED`**, dall'istante dell'inizio compreso. Il frontend può dire "la seduta è già iniziata", che è diverso da "non si può".

---

## Parte 3 — Seconda scelta: il confine del preavviso, e come si prova al secondo

### 3.1 La regola

`free_cancellation_until` = inizio − preavviso (fotografato, D-078). La domanda del brief: **≥ o >**? Cioè: chi cancella **esattamente** a 24 ore dall'inizio è in tempo?

- **"In tempo se `now ≤ free_cancellation_until`"** (compreso): "entro 24 ore prima" letto come lo leggerebbe il cliente. Chi cancella alle 9:00:00 di domenica per le 9:00 di lunedì è in tempo.
- "In tempo se `now < free_cancellation_until`" (escluso): alle 9:00:00 in punto è già tardi.

**Raccomandazione: compreso.** Il nome della colonna dice "fino a", e "fino a" in italiano comprende l'ultimo istante. In Java: `!now.isAfter(freeCancellationUntil)`. È la stessa scelta di `ValidUntil.covers` (l'ultimo giorno valido vale ancora), e diversa da `approval_deadline`, dove "alla scadenza" la richiesta è già scaduta: lì l'istante è la fine di un tempo concesso, qui l'ultimo istante di un diritto. Le due regole si scrivono diverse perché dicono cose diverse, e per questo ognuna ha un commento.

### 3.2 Come si prova un confine al secondo

Un confine si prova **da tutte e due le parti** e **sopra**, con l'orologio spostato a mano (`MovableClock`, D-047), mai con "adesso":

| Istante della cancellazione | Atteso |
|---|---|
| `free_cancellation_until` − 1 secondo | in tempo |
| `free_cancellation_until` esatto | **in tempo** (la riga che decide ≥ o >) |
| `free_cancellation_until` + 1 secondo | tardi |
| inizio della seduta | 409 `BOOKING_ALREADY_STARTED` |

Perché tre righe e non una? Con solo "−1 secondo" e "+1 secondo", `<` e `≤` passerebbero entrambi: solo la riga dell'istante esatto distingue le due regole. Una difesa tolta che nessun test vede è una difesa che non esiste (la lezione della guida 41).

Un dettaglio: un `Instant` del `Clock` vero ha i nanosecondi. "Esatto" vuol dire lo stesso istante fino al nanosecondo, ed è il test che lo fissa; nella realtà la probabilità di colpirlo è nulla, e il test serve a fissare la **regola**, non un caso frequente.

### 3.3 Il cambio dell'ora

Il preavviso è una **durata** (24 ore), non "la stessa ora del giorno prima". Verificato: le due cose differiscono solo se il cambio dell'ora cade **dentro** quelle 24 ore. Per una seduta di domenica 25 ottobre 2026 alle 9:00, 24 ore prima sono **sabato alle 10:00**, non alle 9:00. Lo studio di sabato e domenica è chiuso, e per lunedì 26 ottobre alle 9:00 le due letture coincidono (domenica alle 9:00). Va bene la durata: è quello che il cliente legge ("24 ore prima"), ed è come si conta l'anticipo minimo (D-030).

---

## Parte 4 — Terza scelta: cosa torna, nel modulo `credit`

### 4.1 La tabella degli effetti

| Pagata con | In tempo | Tardi |
|---|---|---|
| `CREDIT` | il credito torna (4.2) | niente: il −1 resta |
| `CARD` (anche dopo un link pagato) | lotto nuovo da 1, `CANCELLATION`, valido `single_credit_validity_days` dal giorno della cancellazione | niente |
| `IN_STUDIO` | niente; il link aperto si chiude (parte 5) | niente; il link si chiude lo stesso |

Con la carta l'importo è già **incassato** (una seduta confermata con carta lo è sempre: D-063, e il link incassa subito, D-065): non c'è nessun blocco da annullare, e Stripe non si chiama.

### 4.2 Il credito che torna (D-076)

Il giorno minimo: `ValidUntil.daysAfter(istante della cancellazione, single_credit_validity_days)`.

1. Il lotto da cui la seduta aveva preso il credito vale **almeno fino a quel giorno** (`valid_until` non prima) → `CANCELLATION_RESTORE` +1 sullo stesso lotto.
2. Scade prima, ed è un lotto `CANCELLATION` ("già allungato") e **cancella il cliente** → +1 sullo stesso lotto, senza allungamento.
3. Altrimenti (un pacchetto o un omaggio che scade prima; oppure un lotto `CANCELLATION` e **cancella lo studio**, D-076) → lotto nuovo da 1, `CANCELLATION`, valido fino al giorno minimo.

Al confine: un lotto che scade **esattamente** il giorno minimo vale abbastanza (caso 1). Si prova con tre lotti: il giorno prima, il giorno stesso, il giorno dopo.

### 4.3 La forma nel codice

`credit` non conosce le prenotazioni (D-002): riceve ids e numeri.

```java
// a CREDIT booking cancelled in time, or by the studio
ReturnedCredit restoreOnCancellation(long bookingId, int validityDays, CancelledBy who)
// a CARD booking cancelled in time, or by the studio
ReturnedCredit lotFromCancellation(long customerId, long practitionerId, long serviceId, long bookingId, int validityDays)
// what came back to these bookings, for the views (one query for a list, D-029)
Map<Long, ReturnedCredit> returnedTo(Collection<Long> bookingIds)
```

`ReturnedCredit(lotId, validUntil)` è un `record`. `CancelledBy` (`CUSTOMER`, `STUDIO`) è un `enum` di `credit`: il pezzo 3 lo userà con `STUDIO`. Tutti e due i metodi che scrivono in `MANDATORY`: entrano nella transazione della cancellazione, con la seduta bloccata.

**Lucchetti nel registro**: nessuno nuovo. Un movimento **positivo** non prende il lucchetto sul lotto (D-069): non può portare il saldo sotto zero. E il lotto nuovo è nuovo: nessuno lo conosce ancora.

**Due fabbriche** nell'entità del pezzo 1 che arrivano ora: `CreditLot.fromCancellation` e `CreditMovement.lotCreatedByCancellation(lotId, bookingId)` (il `LOT_CREATED` con la prenotazione, che l'indice vede) e `CreditMovement.cancellationRestore`. Il `check` della V16 pretende la loro forma; la regola tra due tabelle ("un `LOT_CREATED` con la prenotazione sta in un lotto `CANCELLATION`") la tiene questo metodo, l'unico che li crea.

---

## Parte 5 — Quarta scelta: il link aperto, una compensazione verso Stripe

Una seduta "in studio" può avere un link di pagamento aperto (D-065). Cancellata la seduta, quella pagina su Stripe deve **scadere**: altrimenti il cliente potrebbe ancora pagarla.

### 5.1 La correttezza non dipende da questo

Il webhook del link decide già da solo (D-065): se un pagamento arriva per una seduta non più `CONFIRMED` e `IN_STUDIO`, **annulla il blocco**. Quindi far scadere la pagina è una **cortesia**: evita al cliente di pagare per niente e di vedere un blocco che poi sparisce. Se non riesce, non succede niente di sbagliato. Questo decide quanto deve essere robusta la chiamata: **non deve mai far fallire la cancellazione**.

### 5.2 Dove e quando

**Strada A — dentro la transazione della cancellazione**, con un'interfaccia dichiarata da `booking` e implementata da `payment` (come `CardHolds`).
- Il problema dei lucchetti: per far scadere la pagina bisogna leggere il pagamento `PENDING`, e farlo con il lucchetto (come `PaymentLinks.invalidate`). La cancellazione tiene già il lucchetto sulla **seduta**: seduta, poi pagamento. Il webhook del link fa il contrario: pagamento, poi seduta (D-063, D-065). Due transazioni che prendono due lucchetti in ordine opposto possono aspettarsi a vicenda: **deadlock**. È esattamente il motivo per cui D-065 ha vietato di bloccare la seduta alla creazione del link.
- E la chiamata a Stripe (fino a 15 secondi nel caso peggiore, D-061) terrebbe la seduta bloccata, e la risposta al cliente in attesa.

**Strada B — dopo il commit**, con un ascoltatore del modulo `payment` sull'evento `BookingCancelled` (`payment` dipende già da `booking`: nessun ciclo).
- Quando parte, la seduta è già cancellata e il suo lucchetto è già sciolto: l'ascoltatore prende **solo** il lucchetto del pagamento, nell'ordine del webhook. Nessun incrocio possibile.
- Legge la pagina su Stripe e la fa scadere (`open` → `expire`), e segna il pagamento `EXPIRED`; se la pagina è già `expired`, la segna e basta; se è `complete` (il cliente ha appena pagato), **non tocca niente**: il webhook sta arrivando e annullerà il blocco.
- Errori: Stripe che non risponde, o qualunque altro, si **registrano** come avviso e basta. Il pagamento resta `PENDING`: se il cliente paga, il webhook annulla; se no, la pagina scade da sola (al massimo 24 ore, D-065) e `checkout.session.expired` la chiude.
- Cosa si perde: se l'applicazione si ferma tra il commit e l'ascoltatore, la pagina resta aperta. Stesso esito di un errore: corretto, solo meno cortese.

**Raccomandazione: B.** Una compensazione che può fallire senza danni si fa **fuori** dalla transazione e **fuori** dai lucchetti della cosa principale, e il suo errore si registra invece di propagarsi.

Un dettaglio Spring, che si è rivelato **più sottile** di come l'avevo scritto (parte 13): un `@TransactionalEventListener` gira dopo il commit, ma mentre Spring sta ancora chiudendo la transazione, che resta legata al thread. Un metodo `@Transactional` normale chiamato da lì **si unisce** a quella transazione finita, che non accetta più lavoro (provato: la lettura con il lucchetto fallisce con `No active transaction`): serve **`REQUIRES_NEW`**. Un'eccezione uscita dall'ascoltatore, invece, **non** arriva al chiamante: Spring la registra e va avanti. L'ascoltatore la cattura lo stesso, per scrivere un avviso che dica cosa significa.

### 5.3 Il criterio di D-065, con il percorso vero

Al passo 9 la regola "seduta non più valida → blocco annullato" era provata scrivendo lo stato con SQL. Ora la prova completa: link creato con l'API del pannello → seduta cancellata con l'API del cliente → la pagina è scaduta su Stripe (ascoltatore) → arriva lo stesso `checkout.session.completed` (il cliente aveva pagato un attimo prima) → il blocco è annullato, niente è incassato, la seduta resta `CANCELLED_*` e `IN_STUDIO`.

E l'ordine opposto, il link pagato **prima** della cancellazione: la seduta diventa `CARD` (incassata), e una cancellazione in tempo dopo dà il lotto da 1. Il lucchetto sulla seduta mette in fila le due cose, e ognuna vede la seduta com'è davvero (parte 7).

---

## Parte 6 — Quinta scelta: il doppio clic e l'idempotenza

Due richieste di cancellazione della stessa seduta, una dietro l'altra o nello stesso istante. **Effetti**: uno solo, sempre. La seconda aspetta il lucchetto, poi trova la seduta già cancellata. E se un bug saltasse il lucchetto, l'indice del registro (V16) rifiuterebbe il secondo credito.

**La risposta** alla seconda, due strade:
- **A — 200 con la seduta com'è** (cancellata, con il credito che è già tornato), senza nessun effetto nuovo. L'operazione è **idempotente** anche nella risposta: "voglio che questa seduta sia cancellata" ha la stessa risposta la prima e la seconda volta.
- B — 409 `BOOKING_TRANSITION_NOT_ALLOWED`, come il ritiro ripetuto oggi.

**Raccomandazione: A** per una seduta già cancellata **dal cliente** (in tempo o tardi): il secondo clic, o un nuovo tentativo del browser dopo una risposta persa, mostra il risultato vero e non un errore che spaventa. Per una seduta cancellata **dallo studio**, o in qualunque altro stato, 409: lì il cliente sta chiedendo qualcosa che non è successo per mano sua. Il ritiro resta com'è (cambiarlo non fa parte del passo).

Due livelli di idempotenza, da non confondere: quella degli **effetti** (un solo credito, garantita da lucchetto e indice) e quella della **risposta** (cosa vede il secondo clic). La prima è obbligatoria; la seconda è una scelta di comodità.

---

## Parte 7 — Le corse: quali lucchetti

| Corsa | Chi blocca cosa | Esito |
|---|---|---|
| cancellazione ↔ cancellazione | seduta, tutte e due | la seconda trova la seduta cancellata (parte 6) |
| cancellazione ↔ approvazione | seduta | non si incontrano: la cancellazione vuole `CONFIRMED`, l'approvazione `PENDING_APPROVAL` |
| cancellazione ↔ ritiro | seduta | come sopra, al contrario |
| cancellazione ↔ webhook di un link | cancellazione: seduta; webhook: pagamento, poi seduta | il webhook prima: seduta incassata, `CARD`, poi la cancellazione la vede pagata (credito se in tempo). La cancellazione prima: il webhook trova la seduta cancellata e annulla il blocco |
| cancellazione ↔ ascoltatore del link | ascoltatore: solo pagamento, dopo il commit | nessun incrocio (parte 5) |
| cancellazione ↔ correzione del professionista sul lotto | correzione: lotto | il +1 non prende il lucchetto sul lotto; i due movimenti si sommano |
| cancellazione ↔ lavoro "svolta" (pezzo 5) | seduta, tutte e due | la cancellazione rifiuta una seduta iniziata; il lavoro chiude solo quelle finite |

Il lucchetto di D-046 sulla seduta copre tutto quello che tocca la seduta; quello di D-065 (il pagamento prima della seduta, nel webhook) non si incrocia mai, perché la cancellazione non tocca il pagamento dentro la sua transazione.

---

## Parte 8 — Sesta scelta: chi lo sa (email e push)

**Un evento nuovo**, `BookingCancelled`, con lo stato raggiunto (`CANCELLED_IN_TIME`, `CANCELLED_LATE`; il pezzo 3 aggiungerà `CANCELLED_BY_STUDIO`), come era pagata, e l'**ultimo giorno valido del credito tornato** (o niente): un fatto del momento, che l'ascoltatore dopo il commit non deve ricostruire (D-071). Lo `switch` di `BookingEmails` e quello di `PushMessages` smettono di compilare finché non lo gestiscono.

**Al cliente**, un'email: "Seduta cancellata", con quello che gli resta:
- in tempo con un credito o con la carta: "Ti abbiamo restituito un credito per questa seduta, valido fino al …";
- tardi: "La cancellazione è arrivata meno di N ore prima della seduta: secondo le regole dello studio la seduta non è recuperabile.";
- in studio: "Non avevi pagato niente: non c'è niente da restituire."

**Al professionista**: il brief non lo dice, ma una seduta confermata che sparisce dall'agenda di domani è una cosa che vuole sapere (l'orario è di nuovo libero, magari da riempire). **Raccomandazione**: una **push** "Seduta cancellata", con giorno e ora, mai il nome né il trattamento (D-032), che vive fino all'inizio della seduta; e l'email di riserva se nessun dispositivo la prende, come per una richiesta da approvare (D-054). Si toglie con una riga se lo studio non la vuole.

---

## Parte 9 — Cosa vede il cliente

`CustomerBookingView` riceve due campi:
- `freeCancellationUntil` (a Roma): il frontend scrive "cancellabile gratis fino a …" senza calcolare niente (D-005). La decisione vera, al clic, la prende comunque il backend;
- `returnedCredit` (`lotId`, `validUntil`, o `null`): quello che è tornato a questa seduta, per una cancellazione o per un rifiuto. Nelle liste una domanda sola per tutte le sedute (`returnedTo`, D-029).

La risposta della cancellazione è la stessa vista: si vede subito lo stato e il credito.

---

## Parte 10 — I test

**Il confine** (parte 3.2), con il `MovableClock`: −1 secondo, esatto, +1 secondo, inizio.

**Gli effetti** (parte 4.1): credito in tempo (stesso lotto), credito tardi (niente), carta in tempo (lotto da 1 con il giorno giusto, `LOT_CREATED` con la prenotazione), carta tardi, in studio in tempo e tardi; la vista con `returnedCredit`.

**La validità minima** (parte 4.2): lotto che scade il giorno prima del minimo (lotto nuovo), il giorno stesso (stesso lotto), un lotto `CANCELLATION` corto (stesso lotto, nessun allungamento), e il **secondo giro**: carta → cancellazione in tempo → prenotazione con quel credito vicino alla scadenza → cancellazione in tempo → il credito torna allo stesso lotto, nessun lotto nuovo. Nel `CreditService`, anche `STUDIO` sul lotto corto `CANCELLATION` (lotto nuovo), per il pezzo 3.

**Le regole**: la seduta di un altro (404), una richiesta in attesa (409), una seduta iniziata (409 `BOOKING_ALREADY_STARTED`), una già cancellata dallo studio (409).

**L'idempotenza**: due cancellazioni una dopo l'altra (200, stessa vista, un solo credito); **due contemporanee** con commit veri (un solo credito, due 200).

**Il link** (commit veri): cancellazione → pagina scaduta sul finto Stripe e pagamento `EXPIRED`; Stripe giù → la cancellazione riesce lo stesso, il pagamento resta `PENDING`; **il criterio di D-065 con il percorso vero** (parte 5.3), nei due ordini.

**Le notifiche**: le tre email del cliente (pura funzione, `BookingEmailsTest`), la push al professionista senza nomi.

**Le difese tolte a turno**: `≤` diventato `<`; il controllo "già iniziata"; la validità minima; "già allungato" ignorato (il giro riparte); il controllo dello stato già cancellato (doppio credito, fermato dall'indice); l'ascoltatore che non cattura gli errori; il "solo se in studio" dell'ascoltatore.

---

## Parte 11 — Cosa c'è da decidere

| # | Scelta | Raccomandazione |
|---|---|---|
| 1 | Seduta già iniziata | 409 `BOOKING_ALREADY_STARTED`, dall'inizio compreso (parte 2) |
| 2 | Il confine | in tempo se `now ≤ free_cancellation_until`, compreso (parte 3) |
| 3 | La forma in `credit` | `restoreOnCancellation`, `lotFromCancellation`, `returnedTo`; `CancelledBy` per il pezzo 3 (parte 4) |
| 4 | Il link aperto | ascoltatore di `payment` dopo il commit, solo il lucchetto del pagamento, errori registrati (parte 5) |
| 5 | Il secondo clic | 200 con la seduta com'è se l'ha già cancellata il cliente; 409 altrimenti (parte 6) |
| 6 | Il professionista | push "Seduta cancellata" senza nomi, email di riserva (parte 8) |
| 7 | La vista | `freeCancellationUntil` e `returnedCredit` (parte 9) |

Nessuna di queste cambia una decisione registrata; la 6 aggiunge un avviso che il brief non chiedeva.

**Scelte**: Nicholas ha chiesto di procedere senza conferme se non c'erano domande specifiche per lui; tutte e sette seguono le raccomandazioni (D-079).

---

## Parte 12 — Che cosa c'è nel codice

- `Booking`: `cancelByCustomer` (lo stato raggiunto, chi, quando), `freeToCancelAt`, `hasStartedAt`, `isCancelledByCustomer`, la colonna `cancelledBy`.
- `BookingService.cancel`: lucchetto, secondo clic, seduta iniziata, transizione, `giveBack` (uno `switch` espressione su `paidWith`), evento; `customerView` con il credito tornato, anche nella lista e nel ritiro.
- `credit`: `CancelledBy`, `ReturnedCredit`, `restoreOnCancellation`, `lotFromCancellation`, `returnedTo`; le fabbriche `CreditLot.fromCancellation`, `CreditMovement.lotCreatedByCancellation` e `cancellationRestore`; un solo metodo privato (`newLot`) crea i lotti `CANCELLATION`.
- `payment`: `PaymentLinkClosing` (l'ascoltatore) e `PaymentLinks.closeAfterCancellation`, che con `invalidate` ora condivide `expireOnStripe` (leggi, fai scadere, rileggi).
- `notification`: `BookingCancelled` in `BookingEmails` (al cliente sempre, al professionista solo se ha cancellato il cliente) e in `PushMessages`.
- `ErrorCode.BOOKING_ALREADY_STARTED`.

---

## Parte 13 — Com'è andata (implementazione)

**Un'affermazione sbagliata della guida, trovata togliendo una difesa.** La parte 5.2 diceva che un'eccezione uscita dall'ascoltatore dopo il commit sarebbe arrivata al cliente come un 500. Tolto il `catch`, con Stripe giù, la cancellazione rispondeva **200** lo stesso: il test non vedeva la difesa. Il registro di Maven ha spiegato perché: in Spring 7 un `@TransactionalEventListener` in fase `AFTER_COMMIT` gira dentro `afterCompletion`, e lì Spring **registra** l'eccezione (`TransactionSynchronization.afterCompletion threw exception`, livello ERROR, con lo stack) e va avanti. Il `catch` resta, ma per un motivo diverso: un avviso che dice cosa significa, invece di un errore che sembra un guasto. La parte 5.2 e la domanda 5 sono corrette. La lezione: **una difesa che nessun test vede va capita, non solo segnalata**; a volte vuol dire che il test manca, a volte che la difesa non serviva a quello che si pensava.

**Una cosa che invece si è vista proprio come detto, e più netta.** Con la propagazione di default al posto di `REQUIRES_NEW`, il metodo che chiude il link si unisce alla transazione che Spring sta chiudendo, e la lettura del pagamento con il lucchetto fallisce con `TransactionRequiredException: No active transaction`. L'ascoltatore lo registra come avviso, il link resta aperto, e il test lo vede.

**Un `flush` nei test.** Un test che leggeva `cancelled_by` con SQL dentro la sua transazione trovava `null`: la modifica dell'entità era ancora solo in memoria, e arriva al database al `flush` (al commit, fuori da un test). Gli altri valori si vedevano perché i movimenti nuovi si scrivono subito (l'id viene dal database), e una query JPQL sui movimenti fa un `flush` da sola. Il test ora fa `flush` prima di leggere, e così scatta anche il `check` della traccia.

**Una difesa della parte 10 non provata, e perché.** Il "solo se in studio" dell'ascoltatore: tolto, per una seduta pagata con carta o con un credito l'ascoltatore cerca un pagamento `PENDING` della seduta e non lo trova (non può esistere). Nessun comportamento cambia, quindi nessun test può vederlo: è un risparmio di una query, non una regola.

**Il contratto JSON, visto dalla suite completa.** I test toccati erano verdi; la suite completa ha fermato `BookingCustomerApiTest`, che confronta la risposta della richiesta **campo per campo** (`isStrictlyEqualTo`) e ha trovato tre campi in più (`freeCancellationUntil`, `cancelledAt`, `returnedCredit`). È lo scopo di quel test: un campo nuovo nella risposta è un cambio del contratto con il frontend, e deve essere una scelta, non una sorpresa. Aggiornato il contratto atteso. Seconda volta in questo passo che la suite completa trova quello che i test scelti a mano non vedono (la prima: guida 42, parte 13).

**I test**: `BookingCancellationApiTest` (18: il confine al secondo, gli effetti, il secondo giro della carta, le regole, il secondo clic, la traccia, l'evento, la lista), `CreditCancellationServiceTest` (8: il giorno minimo prima, uguale, dopo; "già allungato" per il cliente e per lo studio; la carta; le restituzioni di una lista; il doppione fermato dal database), `BookingCancellationLinkTest` (6, con commit veri: il link chiuso, Stripe giù, il criterio di D-065 nei due ordini, il link pagato appena prima, due cancellazioni nello stesso istante), più i casi nuovi in `BookingEmailsTest` e `PushMessagesTest`.

---

## Domande da colloquio

### Domanda 1: Una regola dice "entro 24 ore prima". Come la scrivi e come la provi?
**Risposta modello.** Decido prima se l'istante esatto è dentro o fuori, e lo scrivo in un posto solo (qui: in tempo se `now ≤ limite`, perché "fino a" comprende l'ultimo istante). Poi la provo con un orologio che controllo io, in tre punti: un secondo prima, l'istante esatto, un secondo dopo. Il punto esatto è l'unico che distingue `<` da `≤`: senza, tutti e due gli operatori passerebbero i test. E il limite lo calcolo una volta e lo salvo, così il confine non cambia se cambia l'impostazione.

### Domanda 2: Dopo un'operazione devi annullare qualcosa su un servizio esterno, e la chiamata può fallire. Dove la metti?
**Risposta modello.** Prima chiedo se il sistema resta corretto anche se la chiamata non avviene. Se sì (qui un pagamento arrivato tardi viene comunque annullato dal webhook), la chiamata è una compensazione di cortesia: la faccio dopo il commit, fuori dai lucchetti della transazione principale, e un suo errore lo registro senza propagarlo. Dentro la transazione allungherebbe i lucchetti e potrebbe creare un deadlock con chi prende gli stessi lucchetti in ordine opposto. Se invece la correttezza dipende dalla chiamata, serve una garanzia più forte: un nuovo tentativo programmato (come gli annullamenti di D-064) o un'outbox.

### Domanda 3: Due transazioni prendono due lucchetti in ordine opposto. Cosa succede, e come lo eviti?
**Risposta modello.** Può nascere un deadlock: la prima tiene A e aspetta B, la seconda tiene B e aspetta A; PostgreSQL se ne accorge e ne annulla una (`40P01`). Si evita stabilendo un ordine unico dei lucchetti per tutto il sistema, oppure facendo in modo che un'operazione non prenda mai il secondo lucchetto mentre tiene il primo: qui la cancellazione blocca solo la seduta, e il pagamento lo tocca un ascoltatore dopo il commit, quando la seduta è già libera.

### Domanda 4: Cosa vuol dire che una `POST` è idempotente, e come lo garantisci?
**Risposta modello.** Che ripeterla ha lo stesso effetto di farla una volta. Gli effetti si garantiscono in due livelli: un lucchetto sulla riga, per cui la seconda richiesta, dopo l'attesa, trova il lavoro già fatto; e un vincolo del database che rifiuta il doppione se un bug salta il lucchetto. La risposta è una scelta a parte: posso restituire lo stesso risultato invece di un errore, così un nuovo tentativo dopo una risposta persa non sembra un fallimento.

### Domanda 5: Un `@TransactionalEventListener` deve scrivere nel database. Cosa devi sapere?
**Risposta modello.** Gira dopo il commit, ma mentre Spring sta ancora chiudendo quella transazione, che è ancora legata al thread: un metodo `@Transactional` con la propagazione di default si unirebbe a lei, che non accetta più lavoro (qui la lettura con il lucchetto falliva con `No active transaction`). Serve `REQUIRES_NEW`, su un altro bean (perché passi dal proxy). Un'eccezione dell'ascoltatore non annulla niente e non arriva al chiamante: Spring la registra come errore e va avanti; se è un caso previsto, conviene catturarla e registrarla con un messaggio che dica cosa significa. L'ho verificato togliendo le due difese: senza `REQUIRES_NEW` il test fallisce, senza il `catch` no.
