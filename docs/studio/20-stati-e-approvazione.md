# Stati e approvazione — guida di studio

Questa guida accompagna il **pezzo 3 del passo 7**. Una prenotazione in `PENDING_PAYMENT` (pezzo 2) arriva fino alla conferma, oppure al rifiuto o al ritiro:

```
PENDING_PAYMENT ──pagamento autorizzato──► PENDING_APPROVAL ──approva──► CONFIRMED
                                                 │  ├── rifiuta ──► REJECTED
                                                 │  └── ritira ───► WITHDRAWN
                                                 └── (AUTOMATIC: approva subito, stesso percorso)
```

Le scadenze (`EXPIRED`, `APPROVAL_EXPIRED`) le fanno i lavori programmati del pezzo 4.

Contiene:
- **la parte 1**: cosa si costruisce;
- **la parte 2**: **prima scelta**: come si scrive la macchina a stati;
- **la parte 3**: **seconda scelta**: incasso e annullamento del blocco, e il lucchetto delle transizioni;
- **la parte 4**: **terza scelta**: gli eventi di dominio, e perché arrivano dopo il commit;
- **la parte 5**: **quarta scelta**: il "pagamento autorizzato" simulato, spento per default;
- **la parte 6**: le regole del tempo (scadenza della risposta, pagamento in ritardo);
- **la parte 7**: le scelte piccole;
- **la parte 8**: i test;
- **la parte 9**: cosa è stato deciso.

Teoria di riferimento: guida 18, parte 1.1 (il diagramma) e 8 (l'inversione delle dipendenze); guida 19, parte 4 (lucchetti); D-007, D-030, D-031, D-039, D-042.

---

## Indice

1. [Cosa si costruisce](#parte-1--cosa-si-costruisce)
2. [Prima scelta: la macchina a stati](#parte-2--prima-scelta-la-macchina-a-stati)
3. [Seconda scelta: incasso, annullamento e lucchetto](#parte-3--seconda-scelta-incasso-annullamento-e-lucchetto)
4. [Terza scelta: gli eventi di dominio](#parte-4--terza-scelta-gli-eventi-di-dominio)
5. [Quarta scelta: il pagamento simulato](#parte-5--quarta-scelta-il-pagamento-simulato)
6. [Le regole del tempo](#parte-6--le-regole-del-tempo)
7. [Le scelte piccole](#parte-7--le-scelte-piccole)
8. [I test](#parte-8--i-test)
9. [Cosa è stato deciso](#parte-9--cosa-è-stato-deciso)
10. [Domande da colloquio](#domande-da-colloquio)
11. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Cosa si costruisce

| Classe | Dove | Cosa fa |
|---|---|---|
| `BookingStatus.canMoveTo` | `booking` | La macchina a stati: quali passaggi sono permessi (parte 2) |
| `Booking.paymentAuthorized`, `approve`, `reject`, `withdraw` | `booking` | Le transizioni: ognuna passa da `canMoveTo` e scrive la sua colonna di momento |
| `CardHolds` | `booking` | L'interfaccia per incassare o annullare il blocco sulla carta (D-031) |
| `LoggingCardHolds` | `booking` | L'implementazione **provvisoria**: scrive nel log, da sostituire al passo 9 |
| `BookingEvent` e i suoi `record` | `booking` | Gli eventi di dominio, senza ascoltatori fino al passo 8 (D-039) |
| `BookingService.paymentAuthorized`, `approve`, `reject`, `withdraw` | `booking` | I casi d'uso, ognuno in una transazione con il lucchetto sulla prenotazione |
| `BookingPanelController` | `booking` | `POST /api/panel/bookings/{id}/approve` e `/reject` |
| `BookingCustomerController` | `booking` | in più `POST /api/customer/bookings/{id}/withdraw` |
| `SimulatedPaymentController` | `booking` | **Provvisorio**: `POST /api/dev/bookings/{id}/payment-authorized`, esiste solo se acceso (parte 5) |

Chi chiamerà `paymentAuthorized` al passo 9? Il webhook di Stripe, nel modulo `payment`. E chi implementerà `CardHolds`? Ancora `payment`. Le dipendenze restano in un verso solo, `payment` → `booking`: è di nuovo l'inversione delle dipendenze, come `OccupiedTime` (D-044). `booking` dichiara cosa gli serve; `payment` lo fornisce e lo chiama.

---

## Parte 2 — Prima scelta: la macchina a stati

### 2.1 Il problema

Il brief: "le transizioni permesse sono una macchina a stati esplicita: ogni passaggio non previsto è rifiutato". Senza una regola in un posto solo, ogni metodo controlla a modo suo ("se è `PENDING_APPROVAL` allora..."), e prima o poi uno dimentica il controllo: per esempio si approva una prenotazione già rifiutata.

### 2.2 Strada A — Uno `switch` esaustivo nell'`enum`

```java
boolean canMoveTo(BookingStatus next) {
    return switch (this) {
        case PENDING_PAYMENT  -> next == PENDING_APPROVAL || next == EXPIRED;
        case PENDING_APPROVAL -> next == CONFIRMED || next == REJECTED
                                 || next == APPROVAL_EXPIRED || next == WITHDRAWN;
        case CONFIRMED, EXPIRED, REJECTED, APPROVAL_EXPIRED, WITHDRAWN -> false;
    };
}
```

Uno `switch` usato come **espressione** (Java 14) deve coprire **tutti** i valori dell'`enum`, oppure avere un `default`. Senza `default`, il compilatore controlla per noi: quando il passo 11 aggiungerà `CANCELLED_IN_TIME`, il codice **non compilerà** finché qualcuno non avrà deciso le sue frecce.

- **Pro**: la regola è vicina al dato, si legge come il diagramma, e il compilatore la difende.
- **Contro**: la tabella è codice, non un dato da stampare o da leggere da un file. Qui non serve.

### 2.3 Strada B — Una tabella: `EnumMap<BookingStatus, Set<BookingStatus>>`

- **Pro**: è un dato, si può stampare o percorrere.
- **Contro**: uno stato dimenticato nella mappa non è un errore di compilazione, ma un `null` o un insieme vuoto a runtime.

### 2.4 Strada C — Il pattern *State*: una classe per ogni stato

- **Contro**: sette classi per sette stati che non hanno comportamento proprio, solo frecce. È la soluzione giusta quando ogni stato si comporta in modo diverso; qui no.

### 2.5 Strada D — Una libreria (Spring Statemachine)

- **Contro**: una dipendenza e una configurazione intera per un diagramma che sta in dieci righe.

### 2.6 Raccomandazione

**A.** E sopra la macchina, nell'entità, un metodo per ogni transizione con un **nome di dominio**:

```java
void approve(Instant now) {
    moveTo(BookingStatus.CONFIRMED);
    this.decidedAt = now;
}

private void moveTo(BookingStatus next) {
    if (!status.canMoveTo(next)) {
        throw new BusinessRuleException(ErrorCode.BOOKING_TRANSITION_NOT_ALLOWED, ...);
    }
    this.status = next;
}
```

Nessuno può scrivere `status = CONFIRMED` dall'esterno: lo stato cambia solo attraverso `moveTo`. Un passaggio vietato è un **409** (D-027), non un bug: succede davvero quando il professionista approva una richiesta che il cliente ha appena ritirato.

### 2.7 E il pattern matching di Java 21?

Lo `switch` sull'`enum` c'era già prima. Java 21 aggiunge il **pattern matching** nello `switch`: si può fare `switch` su un **tipo**, e con un'interfaccia `sealed` il compilatore sa quali sono tutti i sottotipi possibili. Lo usiamo per gli eventi (parte 4): al passo 8 chi li ascolta scriverà

```java
switch (event) {
    case ApprovalRequested requested -> notifyPractitioner(requested);
    case BookingConfirmed confirmed  -> emailCustomer(confirmed);
    case BookingRejected rejected    -> emailCustomer(rejected);
    case BookingWithdrawn withdrawn  -> notifyPractitioner(withdrawn);
}
```

senza `default`: se un giorno nasce un evento nuovo, quel codice non compila finché non lo gestisce.

---

## Parte 3 — Seconda scelta: incasso, annullamento e lucchetto

### 3.1 L'interfaccia

```java
// booking: what booking needs of the card, in its own words. Implemented by payment at step 9 (Stripe)
public interface CardHolds {
    void capture(long bookingId, long amountCents);   // all'approvazione: i soldi si incassano
    void cancel(long bookingId);                      // al rifiuto, al ritiro, alla scadenza: il blocco si scioglie
}
```

Fino al passo 9, `LoggingCardHolds` scrive solo nel log. È innocua: senza il pagamento simulato (parte 5) nessuna prenotazione arriva a `PENDING_APPROVAL`.

### 3.2 Il problema vero: una chiamata esterna dentro una transazione

L'approvazione fa due cose che non stanno nello stesso sistema:
- **chiede a Stripe di incassare**, una chiamata in rete che **non si può annullare** con un `rollback`;
- **scrive `CONFIRMED`** nel database.

D-031 vuole la conferma solo **dopo** l'incasso riuscito. Ma cosa succede se, mentre il professionista approva, il lavoro programmato del pezzo 4 fa scadere la stessa richiesta?

### 3.3 Strada A — Lucchetto ottimistico (`@Version`)

Le due transazioni leggono la prenotazione con la stessa versione. Il lavoro programmato scrive per primo. L'approvazione ha **già incassato**, poi al commit scopre che la versione è cambiata e fallisce.

```
approva: legge v3 → incassa da Stripe ✓ → commit: la versione è v4 → errore
lavoro:  legge v3 → APPROVAL_EXPIRED → commit v4 ✓
```

Risultato: soldi incassati per una prenotazione scaduta. Per rimediare servirebbe un rimborso, contro D-015. Il lucchetto ottimistico scopre il conflitto **troppo tardi**: dopo l'effetto esterno.

### 3.4 Strada B — Lucchetto pessimistico sulla riga della prenotazione

Ogni transizione inizia con `select ... for update` sulla prenotazione (`findLockedById...`). Chi arriva secondo **aspetta**, e quando riparte rilegge lo stato:

```
approva: lock ✓ → PENDING_APPROVAL → incassa ✓ → CONFIRMED → commit
lavoro:  update ... where status = 'PENDING_APPROVAL' ... aspetta il lock ... rilegge: CONFIRMED → 0 righe
```

- **Pro**: la chiamata esterna parte solo quando la transizione è sicuramente nostra.
- **Contro**: la riga resta bloccata per la durata della chiamata a Stripe (centinaia di millisecondi), e la transazione tiene occupata una connessione del pool. Con due professionisti è irrilevante; in un sistema grande si sposterebbe la chiamata fuori dalla transazione con uno stato intermedio.

### 3.5 L'ordine dentro la transazione

1. lucchetto sulla prenotazione;
2. **transizione in memoria** (`booking.approve(now)`): se non è permessa, eccezione **prima** di toccare Stripe;
3. **chiamata esterna** (`capture`): se fallisce, eccezione → `rollback` → la prenotazione resta `PENDING_APPROVAL`;
4. commit.

Resta un caso che nessun ordine risolve del tutto: l'incasso riesce e il commit fallisce (il database cade in quel preciso istante). È il problema della **doppia scrittura** (*dual write*): due sistemi, nessuna transazione comune. Al passo 9 lo chiude Stripe stesso, con l'evento `payment_intent.succeeded` del webhook, che riporta il database allo stato giusto.

### 3.6 Raccomandazione

**B**, con quell'ordine. E `@Version` resta: è la seconda difesa, e protegge qualunque scrittura futura che dimentichi il lucchetto. Lo dico chiaramente perché la guida 19 (parte 4.3) prevedeva il lucchetto ottimistico per le transizioni: ragionando sull'incasso, è la scelta sbagliata. Ottimistico va bene quando chi perde può ripetere senza danni; qui chi perde avrebbe già incassato.

L'annullamento è diverso: se fallisce, il blocco sulla carta si scioglie comunque da solo (D-031). Al passo 9 un annullamento fallito non deve fermare il rifiuto: si registra e si va avanti. Oggi `LoggingCardHolds` non fallisce mai.

---

## Parte 4 — Terza scelta: gli eventi di dominio

### 4.1 A cosa servono

Al passo 8 qualcuno dovrà avvisare il professionista ("nuova richiesta da approvare") e il cliente ("prenotazione confermata"). Se lo facesse `BookingService` chiamando direttamente `notification`, il modulo delle prenotazioni dipenderebbe da email, push e dai loro errori. Con gli eventi, `booking` **annuncia** cosa è successo e non sa chi ascolta (D-039).

```java
public sealed interface BookingEvent permits ApprovalRequested, BookingConfirmed, BookingRejected, BookingWithdrawn {
    long bookingId();
    long practitionerId();
    long customerId();
    Instant startsAt();
}

public record ApprovalRequested(long bookingId, long practitionerId, long customerId,
                                Instant startsAt, Instant approvalDeadline) implements BookingEvent {}
```

- **`sealed`** (Java 17): solo i tipi elencati possono implementare l'interfaccia. Con i `record` l'insieme è chiuso e conosciuto, e lo `switch` della parte 2.7 è esaustivo.
- **Solo id e istanti**, mai il nome del cliente: chi ascolta lo chiede al modulo che lo possiede, e il testo della notifica push non deve contenerlo (D-032).
- **Solo gli eventi che il codice produce oggi** (la regola di D-042): `ApprovalExpired` arriva con il pezzo 4.

### 4.2 Strada A — `ApplicationEventPublisher`, ascoltatori dopo il commit

Spring ha già un sistema di eventi. `BookingService` riceve un `ApplicationEventPublisher` e chiama `publishEvent(evento)` **dentro** la transazione. Il punto delicato è **quando** l'ascoltatore lo riceve:
- con `@EventListener` lo riceve **subito**, nello stesso thread, dentro la transazione. Se poi la transazione fallisce (l'incasso non riesce), il cliente avrebbe già ricevuto l'email "confermata" per una prenotazione che non lo è;
- con **`@TransactionalEventListener`** (fase di default `AFTER_COMMIT`) Spring tiene l'evento da parte e lo consegna **solo se il commit riesce**. Se la transazione fallisce, l'evento sparisce.

Quindi: si pubblica dentro la transazione, si ascolta dopo il commit. Due avvertenze per il passo 8:
- un evento pubblicato **fuori** da una transazione non arriva affatto a un `@TransactionalEventListener` (a meno di `fallbackExecution = true`). Qui si pubblica sempre dentro un metodo `@Transactional`;
- l'ascoltatore gira dopo il commit, fuori dalla transazione: se deve scrivere nel database, gli serve una transazione nuova (`REQUIRES_NEW`).

### 4.3 Strada B — Una tabella degli eventi (*transactional outbox*)

L'evento si salva in una tabella nella stessa transazione della prenotazione; un lavoro programmato lo legge e lo consegna.
- **Pro**: nessun evento perso nemmeno se l'applicazione si ferma tra il commit e l'invio dell'email.
- **Contro**: una tabella e un lavoro in più. È lo standard quando gli eventi attraversano la rete verso altri servizi; qui restano dentro la stessa applicazione.

### 4.4 Raccomandazione

**A.** Al passo 8 si potrà passare a B se perdere un'email per un riavvio diventerà un problema reale. Un test di questo pezzo dimostra la regola: un ascoltatore `@TransactionalEventListener` di prova riceve l'evento quando la transazione viene confermata, e non lo riceve quando viene annullata.

---

## Parte 5 — Quarta scelta: il pagamento simulato

### 5.1 Il problema

Il pagamento vero arriva al passo 9 con il webhook di Stripe (D-007). Fino ad allora serve un modo per dire "questa prenotazione è stata pagata", per provare l'approvazione dal pannello e dal frontend. Il brief: **non deve mai essere attivo in produzione**.

### 5.2 Strada A — Un controller acceso solo da una proprietà, spento per default

```java
@RestController
@ConditionalOnProperty(name = "booking.simulated-payments", havingValue = "true")
class SimulatedPaymentController { ... POST /api/dev/bookings/{id}/payment-authorized ... }
```

```properties
# application.properties: false unless the environment says otherwise
booking.simulated-payments=${BOOKING_SIMULATED_PAYMENTS:false}
```

Senza la variabile, il bean **non esiste**: l'indirizzo risponde 404 come qualunque URL sconosciuto. Per accenderlo in locale, `BOOKING_SIMULATED_PAYMENTS=true` nel `.env`.
- **Pro**: sicuro per default. Dimenticare qualcosa lo lascia **spento**, non acceso.
- **Pro**: il metodo che chiama, `BookingService.paymentAuthorized`, è quello che userà il webhook vero: al passo 9 si cancella solo il controller.

### 5.3 Strada B — Un profilo Spring (`@Profile("dev")`)

- **Contro**: è sicuro solo se in produzione nessuno attiva per sbaglio il profilo `dev`, e i profili tendono ad accumulare configurazioni. Una proprietà con un nome solo, che fa una cosa sola, è più facile da controllare.

### 5.4 Strada C — Sempre attivo, con un `TODO`

È quello che abbiamo fatto con `DevHeaderCurrentPractitioner` (D-028).
- **Contro**: lì l'header riguarda chi è l'utente, e il passo 8 lo sostituisce prima della messa online. Qui chiunque potrebbe **confermare una prenotazione senza pagare**: il problema da cui nasce il progetto.

### 5.5 Raccomandazione

**A**, con un test che prova che senza la proprietà l'indirizzo non esiste.

---

## Parte 6 — Le regole del tempo

### 6.1 Da quando si conta la risposta

D-030: il tempo di risposta "si conta dal momento della richiesta". La richiesta è il momento in cui il cliente ha chiesto l'orario, cioè la **creazione** della prenotazione, non il pagamento:
- così la regola `approval_window_hours ≤ min_booking_notice_hours` garantisce davvero che la scadenza venga **prima** dell'inizio della seduta. Contando dal pagamento, che può arrivare fino a 30 minuti dopo, la scadenza potrebbe cadere dopo l'inizio;
- per questo `created_at` della prenotazione lo scrive Java con il `Clock`, non il default del database: è un tempo di dominio, e nei test deve seguire l'orologio fisso. Le altre tabelle tengono il default.

`approval_deadline = created_at + approval_window_hours`, letta dalle impostazioni al momento del pagamento e poi fotografata (D-030).

### 6.2 Risposte dopo la scadenza

Se il professionista approva o rifiuta **dopo** `approval_deadline`, ma il lavoro programmato non è ancora passato: **409 `APPROVAL_DEADLINE_PASSED`**. La richiesta si è già annullata nella sostanza (D-030), e il pezzo 4 la chiuderà entro un minuto. Il cliente invece può ritirare anche in quel minuto: l'esito per lui è identico (nessun addebito).

### 6.3 Pagamento autorizzato in ritardo

- Prenotazione ancora **`PENDING_PAYMENT`**, anche con il blocco scaduto: si accetta. Nessuno ha preso l'orario, altrimenti la scadenza pigra del pezzo 2 l'avrebbe portata a `EXPIRED`.
- Prenotazione **`EXPIRED`**: si **annulla il blocco** sulla carta e la prenotazione resta com'è (D-031). Nessun errore: al passo 9 il webhook deve rispondere "ricevuto", altrimenti Stripe lo rimanda all'infinito.
- Qualunque altro stato (un secondo "pagato" per la stessa prenotazione): 409 dalla macchina a stati. Al passo 9 i doppioni li fermerà prima la tabella `stripe_event`.

### 6.4 `AUTOMATIC`: un solo percorso

```java
booking.paymentAuthorized(window);                 // PENDING_PAYMENT → PENDING_APPROVAL, scadenza
switch (settings.bookingApproval()) {
    case AUTOMATIC -> confirm(booking, now);        // lo stesso metodo dell'approvazione manuale
    case MANUAL    -> events.publishEvent(ApprovalRequested.of(booking));
}
```

In automatico la prenotazione passa da `PENDING_APPROVAL` per un istante, dentro la stessa transazione: il database vede solo `CONFIRMED`. Nessuna freccia speciale `PENDING_PAYMENT → CONFIRMED`, nessuna notifica "da approvare". La scadenza in automatico non conta: l'approvazione avviene subito.

---

## Parte 7 — Le scelte piccole

| # | Scelta | Proposta | Perché |
|---|---|---|---|
| 1 | **URL** | `POST /api/panel/bookings/{id}/approve`, `/reject`; `POST /api/customer/bookings/{id}/withdraw` | Azioni come `deactivate` del passo 5 (D-028): non si "modifica" una prenotazione, si fa un passo della macchina |
| 2 | **Risposta del pannello** | `PanelBookingView` con `id`, `customerId`, `serviceId`, orari, `status`, `approvalDeadline` | Il minimo per ora: nome del cliente e segnalazioni arrivano con le liste del pezzo 6 |
| 3 | **Risposta del cliente** | `CustomerBookingView` riceve `approvalDeadline` | Il cliente vede fino a quando aspettare (D-030) |
| 4 | **Proprietà** | Approva e rifiuta solo il professionista della prenotazione, ritira solo il suo cliente; altrimenti 404 | La regola del passo 5 |
| 5 | **Due codici nuovi** | `BOOKING_TRANSITION_NOT_ALLOWED`, `APPROVAL_DEADLINE_PASSED` | Il primo copre tutti i passaggi vietati; il secondo spiega al professionista perché non può più rispondere |
| 6 | **Ritiro prima del pagamento** | Non permesso (`PENDING_PAYMENT` → `WITHDRAWN` non è una freccia) | D-030 parla di ritiro "finché è in attesa" di approvazione; prima del pagamento il blocco scade da solo |
| 7 | **Il pagamento simulato risponde** | La `CustomerBookingView` | Il frontend vede subito il nuovo stato |

---

## Parte 8 — I test

### 8.1 La macchina, senza Spring (`BookingStatusTest`, `BookingTest`)

| # | Caso | Atteso |
|---|---|---|
| 1 | **Tutte le 49 coppie** di stati | permesse esattamente le 6 frecce del diagramma, le altre 43 rifiutate |
| 2 | `paymentAuthorized` | `PENDING_APPROVAL`, scadenza = richiesta + finestra |
| 3 | `approve`, `reject`, `withdraw` | lo stato giusto e la colonna di momento giusta |
| 4 | Un passaggio vietato | `BOOKING_TRANSITION_NOT_ALLOWED`, entità invariata |

### 8.2 I casi d'uso (`BookingTransitionsTest`, `@DataJpaTest`, eventi registrati con `@RecordApplicationEvents`)

| # | Caso | Atteso |
|---|---|---|
| 5 | Pagata, `MANUAL` | `PENDING_APPROVAL`, scadenza venerdì 22:00 (10:00 + 12 ore), nessun incasso, evento `ApprovalRequested` |
| 6 | Pagata, `AUTOMATIC` | `CONFIRMED`, incasso di 3000, solo l'evento `BookingConfirmed` |
| 7 | Pagata due volte | `BOOKING_TRANSITION_NOT_ALLOWED` |
| 8 | Pagata quando era già `EXPIRED` | resta `EXPIRED`, blocco annullato, nessun evento |
| 9 | Approvata | `CONFIRMED`, `decided_at`, incasso, `BookingConfirmed` |
| 10 | Rifiutata | `REJECTED`, `decided_at`, blocco annullato, `BookingRejected`; l'orario torna libero |
| 11 | Approvata o rifiutata da Marco | `NotFoundException`, nessun incasso |
| 12 | Approvata dopo la scadenza | `APPROVAL_DEADLINE_PASSED`, nessun incasso |
| 13 | Approvata prima del pagamento | `BOOKING_TRANSITION_NOT_ALLOWED` |
| 14 | Ritirata dal cliente | `WITHDRAWN`, `cancelled_at`, blocco annullato, `BookingWithdrawn`; ritiro da un altro cliente → 404; ritiro di una `CONFIRMED` → vietato |

### 8.3 Dopo il commit (`BookingAfterCommitTest`, senza la transazione del test)

| # | Caso | Atteso |
|---|---|---|
| 15 | Approvazione confermata | l'ascoltatore `@TransactionalEventListener` di prova riceve `BookingConfirmed` |
| 16 | Approvazione dentro una transazione esterna poi annullata: l'evento **è pubblicato**, poi il `rollback` | la riga resta `PENDING_APPROVAL`, l'ascoltatore **non** riceve niente. È il caso che distingue `@TransactionalEventListener` da `@EventListener` |
| 16b | L'incasso fallisce | eccezione, la riga resta `PENDING_APPROVAL`; l'evento, che si pubblica dopo l'incasso, non parte nemmeno |

### 8.4 API

| # | Caso | Atteso |
|---|---|---|
| 17 | Pannello: approva, rifiuta | 200 con lo stato nuovo |
| 18 | Pannello: prenotazione di un altro; stato sbagliato; senza header | 404; 409 con il codice; 401 |
| 19 | Cliente: ritira | 200 `WITHDRAWN` |
| 20 | **Senza la proprietà**, `POST /api/dev/bookings/{id}/payment-authorized` | **404**: l'indirizzo non esiste |
| 21 | Con la proprietà accesa | 200 `PENDING_APPROVAL`; prenotazione inesistente → 404 |

---

## Parte 9 — Cosa è stato deciso

Nicholas ha chiesto di procedere senza conferme se non c'erano domande: le scelte seguono le raccomandazioni di questa guida e sono registrate in D-046.

1. **Macchina a stati** (parte 2): A, `switch` esaustivo nell'`enum`, transizioni con un nome nell'entità.
2. **Incasso e annullamento** (parte 3): interfaccia `CardHolds` dichiarata da `booking`; lucchetto **pessimistico** sulla prenotazione per ogni transizione, transizione in memoria prima della chiamata esterna. Corregge l'anticipazione della guida 19, 4.3.
3. **Eventi** (parte 4): A, `ApplicationEventPublisher` dentro la transazione, `sealed interface` di `record`, ascoltatori `@TransactionalEventListener` al passo 8.
4. **Pagamento simulato** (parte 5): A, controller acceso solo da `booking.simulated-payments=true`, spento per default.

In implementazione: i quattro `record` degli eventi sono **annidati** dentro `BookingEvent` (`BookingEvent.ApprovalRequested`...), così l'insieme chiuso si legge in un file solo. E i test di questo pezzo hanno fatto emergere il *deadlock* tra due `insert` contemporanei sul vincolo di non sovrapposizione (guida 18, 3.3, correzione): `BookingService` ora traduce in `SLOT_NOT_AVAILABLE` anche `40P01`.

---

## Domande da colloquio

### Domanda 1: Come implementi una macchina a stati in Java?
**Risposta modello.** Se gli stati non hanno comportamento proprio, con un `enum` e un metodo `canMoveTo` scritto come `switch` espressione senza `default`: il compilatore obbliga a coprire ogni stato, quindi uno stato nuovo non compila finché non si decidono le sue frecce. Lo stato dell'entità cambia solo attraverso un metodo privato che chiede alla macchina, e sopra ci sono transizioni con nomi di dominio (`approve`, `reject`). Il pattern *State*, con una classe per stato, serve quando ogni stato si comporta in modo diverso.

### Domanda 2: Una transizione chiama un servizio esterno, per esempio un incasso. Lucchetto ottimistico o pessimistico?
**Risposta modello.** Pessimistico. Con l'ottimistico il conflitto si scopre al commit, cioè dopo l'incasso, che non si può annullare con un `rollback`: avrei incassato per una prenotazione che nel frattempo è scaduta. Con un `SELECT ... FOR UPDATE` all'inizio, chi arriva secondo aspetta e poi rilegge lo stato. L'ordine conta: lucchetto, controllo della transizione, chiamata esterna, commit. Resta il caso "incasso riuscito, commit fallito", la doppia scrittura, che si chiude riconciliando con il webhook del fornitore.

### Domanda 3: Cosa sono gli eventi di dominio, e perché pubblicarli dopo il commit?
**Risposta modello.** Sono messaggi che un modulo emette per dire cosa è successo ("prenotazione confermata"), senza sapere chi ascolta: il modulo non dipende da email e notifiche. In Spring li pubblico con `ApplicationEventPublisher` dentro la transazione, ma li ascolto con `@TransactionalEventListener`, che li consegna solo dopo un commit riuscito. Con un `@EventListener` normale, un'email partirebbe anche per una transazione che poi fallisce. Se non si può perdere nemmeno un evento per un riavvio, si salva l'evento in una tabella nella stessa transazione (*outbox*).

### Domanda 4: A cosa servono `sealed` e il pattern matching nello `switch` di Java 21?
**Risposta modello.** Un'interfaccia `sealed` elenca i soli tipi che la possono implementare, quindi il compilatore conosce tutti i casi. Con il pattern matching nello `switch` scrivo `case BookingConfirmed confirmed -> ...` per ogni tipo, senza `default` e senza cast: se aggiungo un evento, ogni `switch` che non lo gestisce smette di compilare. Con i `record` ottengo dati immutabili con `equals` e `toString` già fatti.

### Domanda 5: Come eviti che una funzione di sviluppo finisca accesa in produzione?
**Risposta modello.** La rendo spenta per default: il bean esiste solo se una proprietà dice esplicitamente `true` (`@ConditionalOnProperty`), e la proprietà ha default `false`. Così dimenticare qualcosa lascia la funzione spenta, non accesa. Aggiungo un test che verifica che, senza la proprietà, l'indirizzo non esiste. Un profilo `dev` è meno sicuro: basta attivarlo per errore insieme ad altri.

### Domanda 6: Il tuo sistema chiama Stripe e scrive nel database. Cosa succede se uno dei due fallisce?
**Risposta modello.** Se la chiamata a Stripe fallisce, l'eccezione annulla la transazione e lo stato non cambia. Se Stripe riesce e il commit fallisce, i due sistemi non sono d'accordo: non esiste una transazione che li comprenda entrambi. Lo risolvo con l'idempotenza e la riconciliazione: Stripe rimanda l'evento `payment_intent.succeeded` finché non rispondo, e il webhook riporta il database allo stato giusto. Per gli annullamenti, che si sciolgono da soli dopo qualche giorno, un errore si registra e non blocca il rifiuto.

---

## Documentazione ufficiale

- Java, `switch` come espressione (JEP 361) e pattern matching nello `switch` (JEP 441): https://openjdk.org/jeps/361, https://openjdk.org/jeps/441
- Java, classi `sealed` (JEP 409): https://openjdk.org/jeps/409
- Spring Framework, eventi legati alle transazioni (`@TransactionalEventListener`): https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html
- Spring Boot, `@ConditionalOnProperty`: https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html#features.developing-auto-configuration.condition-annotations.property-conditions
- Spring Framework, `@RecordApplicationEvents` nei test: https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/application-events.html
- Stripe, *place a hold on a payment method* (incasso e annullamento): https://docs.stripe.com/payments/place-a-hold-on-a-payment-method
