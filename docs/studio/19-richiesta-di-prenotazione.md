# La richiesta di prenotazione del cliente — guida di studio

Questa guida accompagna il **pezzo 2 del passo 7**. Il cliente sceglie un orario libero e lo chiede; il sistema fa i controlli e, se va tutto bene, crea la prenotazione in `PENDING_PAYMENT` con un blocco di 30 minuti (D-008). Il pagamento arriva nel pezzo 3.

```
POST /api/customer/bookings
{"practitionerId": 1, "serviceId": 3, "startsAt": "2026-11-09T09:00:00+01:00"}
```

Contiene:
- **la parte 1**: cosa si costruisce, e il percorso di una richiesta;
- **la parte 2**: **prima scelta**: l'ordine dei controlli, e quali errori vede il cliente;
- **la parte 3**: **seconda scelta**: come si chiede a `schedule` se un orario è libero;
- **la parte 4**: **terza scelta**: il limite sotto concorrenza (lucchetti e livelli di isolamento);
- **la parte 5**: **quarta scelta**: il blocco scaduto davanti al vincolo;
- **la parte 6**: la violazione del vincolo diventa un 409 leggibile;
- **la parte 7**: il cliente corrente provvisorio;
- **la parte 8**: le scelte piccole;
- **la parte 9**: i test, compreso il secondo test di concorrenza;
- **la parte 10**: cosa è stato deciso.

Teoria di riferimento: guida 18 (il vincolo, le due idee di "occupato"); guida 11, parte 2 (il professionista corrente, D-028); guida 17 (l'API degli orari liberi, la riservatezza); D-008, D-023, D-027, D-041, D-042, D-044.

---

## Indice

1. [Cosa si costruisce](#parte-1--cosa-si-costruisce)
2. [Prima scelta: l'ordine dei controlli](#parte-2--prima-scelta-lordine-dei-controlli)
3. [Seconda scelta: "questo orario è libero?"](#parte-3--seconda-scelta-questo-orario-è-libero)
4. [Terza scelta: il limite sotto concorrenza](#parte-4--terza-scelta-il-limite-sotto-concorrenza)
5. [Quarta scelta: il blocco scaduto davanti al vincolo](#parte-5--quarta-scelta-il-blocco-scaduto-davanti-al-vincolo)
6. [La violazione del vincolo diventa un 409](#parte-6--la-violazione-del-vincolo-diventa-un-409)
7. [Il cliente corrente provvisorio](#parte-7--il-cliente-corrente-provvisorio)
8. [Le scelte piccole](#parte-8--le-scelte-piccole)
9. [I test](#parte-9--i-test)
10. [Cosa è stato deciso](#parte-10--cosa-è-stato-deciso)
11. [Domande da colloquio](#domande-da-colloquio)
12. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Cosa si costruisce

### 1.1 I pezzi

| Classe | Dove | Cosa fa |
|---|---|---|
| `Customer`, `CustomerRepository` | `customer` | La prima entità del modulo: solo la tabella della V1 |
| `CustomerService.lockForBooking` | `customer` | Il lucchetto sulla riga del cliente (parte 4) |
| `CurrentCustomer`, `DevHeaderCurrentCustomer` | `customer` | Il cliente corrente provvisorio (parte 7) |
| `ScheduleService.requireFreeSlot` | `schedule` | "Questo orario è uno degli orari liberi?" (parte 3) |
| `BookingService.request` | `booking` | I controlli, il lucchetto, la scadenza dei blocchi vecchi, l'`insert` |
| `Booking.requestedByCustomer` | `booking` | Crea la prenotazione in `PENDING_PAYMENT`, con blocco e prezzo |
| `BookingCustomerController` | `booking` | `POST /api/customer/bookings`, `GET /api/customer/bookings/{id}` |
| `NewBooking`, `CustomerBookingView` | `booking` | I `record` delle API |
| Due codici in `ErrorCode` | `shared` | `SLOT_NOT_AVAILABLE`, `BOOKING_LIMIT_REACHED` |

Le API del cliente stanno sotto **`/api/customer`**, come quelle del professionista sotto `/api/panel`. Niente sotto `/api/public` (D-029): una prenotazione appartiene a qualcuno. Al passo 8 Spring Security chiuderà ogni prefisso con una regola sola.

### 1.2 Il percorso di una richiesta

```
cliente ──POST──► BookingCustomerController
                     │  CurrentCustomer: chi è?                           (401 se nessuno)
                     │  @Valid NewBooking                                 (400 se manca qualcosa)
                     ▼
                 BookingService.request(customerId, request)   ── una transazione ──
                     ├─► Clock: adesso (una volta sola)
                     ├─► CatalogService.serviceOnSale: durata e prezzo     (404 se non in vendita)
                     ├─► CustomerService.lockForBooking                    (aspetta se lo stesso cliente
                     │                                                     sta già prenotando)
                     ├─► PractitionerService.getSettings: limite
                     ├─► conteggio delle prenotazioni future               (409 BOOKING_LIMIT_REACHED)
                     ├─► ScheduleService.requireFreeSlot                   (409 SLOT_NOT_AVAILABLE)
                     ├─► scadenza dei blocchi vecchi sullo stesso orario   (parte 5)
                     └─► insert + flush                                    (vincolo → 409 SLOT_NOT_AVAILABLE)
                     ▼
                 201 Created, Location: /api/customer/bookings/{id}
```

Dipendenze tra moduli: `booking` → `customer`, `catalog`, `practitioner`, `schedule`. Nessuno dipende da `booking`: `schedule` lo usa solo attraverso `OccupiedTime` (D-044).

---

## Parte 2 — Prima scelta: l'ordine dei controlli

### 2.1 Il problema

Il brief elenca sei controlli: servizio in vendita, orario dentro gli orari di lavoro e sulla griglia, anticipo minimo, orizzonte, limite di prenotazioni future, orario libero. L'ordine conta per due motivi:
- **l'errore che vede il cliente**: se due controlli falliscono, vede il primo;
- **il costo e la concorrenza**: alcuni controlli vanno fatti **dopo** il lucchetto (parte 4), altri no.

### 2.2 Quattro controlli diventano uno

Griglia, orari di lavoro, anticipo, orizzonte, assenze e orari occupati: sono **esattamente** le regole del calcolo degli orari liberi (D-035). Il modo più sicuro per non avere due versioni della stessa regola è chiedere al calcolo: "questo inizio è tra gli orari liberi?" (parte 3). Per il cliente l'esito è uno solo: **`SLOT_NOT_AVAILABLE`**, "orario non disponibile, scegline un altro".

Distinguere i motivi sarebbe peggio per due ragioni:
- **l'azione del cliente è sempre la stessa**: aggiornare la pagina e scegliere un altro orario. Il frontend offre solo orari presi dal calcolo; se uno non va più bene, è perché nel frattempo è cambiato qualcosa (qualcuno l'ha preso, è passato il minuto dell'anticipo, è passata la mezzanotte dell'orizzonte);
- **riservatezza**: "occupato" e "fuori orario" con codici diversi direbbero a chiunque che a quell'ora c'è una prenotazione (guida 17, parte 5).

### 2.3 L'ordine scelto

| # | Controllo | Esito | Perché qui |
|---|---|---|---|
| 1 | Chi è il cliente | 401 | Nel controller, prima di tutto (parte 7) |
| 2 | Forma della richiesta | 400 | Bean Validation, prima del servizio |
| 3 | Servizio in vendita | 404 | La richiesta nomina qualcosa che non c'è: non dipende dal cliente, non serve il lucchetto |
| 4 | Lucchetto sul cliente | — | Da qui in poi le richieste dello stesso cliente vanno una alla volta (parte 4) |
| 5 | Limite di prenotazioni future | 409 `BOOKING_LIMIT_REACHED` | **Prima** dell'orario: se il cliente ha già 4 prenotazioni, dirgli "orario non disponibile" lo spingerebbe a provarne un altro, inutilmente |
| 6 | Orario libero | 409 `SLOT_NOT_AVAILABLE` | Il calcolo, con tutte le sue regole |
| 7 | Blocchi vecchi sullo stesso orario | — | Chiusi prima dell'`insert` (parte 5) |
| 8 | `insert` | 409 `SLOT_NOT_AVAILABLE` | Il vincolo, l'ultima difesa (parte 6) |

La regola generale: **prima** gli errori che dicono "la richiesta non ha senso" (400, 404), **poi** quelli che dipendono dallo stato (409), e tra questi prima quello che cambia di più cosa deve fare il cliente.

---

## Parte 3 — Seconda scelta: "questo orario è libero?"

### 3.1 Strada A — Riusare il calcolo: `requireFreeSlot`

```java
// schedule
public TimeRange requireFreeSlot(long practitionerId, int durationMinutes, Instant start, Instant now)
```

Calcola gli orari liberi del **giorno** dell'orario richiesto (a Roma) e cerca quello che inizia a `start`. Se c'è, restituisce l'intervallo; se no, `BusinessRuleException(SLOT_NOT_AVAILABLE)`.

- **Pro**: una sola fonte di verità. È impossibile che il cliente veda un orario libero e la richiesta lo rifiuti per una regola scritta in modo diverso, o il contrario.
- **Pro**: il calcolo è già provato da 28 test unitari, compresi i cambi dell'ora.
- **Contro**: calcola un giorno intero per controllarne un orario. Sono al massimo una ventina di orari: irrilevante.

### 3.2 Strada B — Controlli dedicati in `booking`

Griglia, fascia, anticipo, orizzonte riscritti come controlli singoli.
- **Contro**: la stessa regola in due posti, che prima o poi divergono (l'esempio classico: il cambio dell'ora, gestito con cura in `FreeSlots` e dimenticato altrove).

### 3.3 Raccomandazione

**A.** Due dettagli:
- **`now` esplicito**, come in `OccupiedTime` (D-044): `BookingService` legge l'orologio una volta e lo usa per il calcolo, il limite, il blocco e la scadenza dei blocchi vecchi. Altrimenti potrebbero vedere due "adesso" diversi a cavallo di un minuto;
- la durata la passa `booking`, che l'ha già chiesta a `catalog` insieme al prezzo: una domanda sola al catalogo. `ScheduleService.freeSlots` e `requireFreeSlot` condividono lo stesso codice privato.

---

## Parte 4 — Terza scelta: il limite sotto concorrenza

### 4.1 Il problema

Il limite (4 prenotazioni future, D-041) è un **conteggio** in Java, non un vincolo del database: nessun `check` può contare le righe di una tabella. Quindi torna la corsa della guida 18, 3.1, questa volta sullo **stesso cliente**:

```
Richiesta 1 (cliente C, lunedì 9:00)           Richiesta 2 (cliente C, martedì 9:00)
conta le prenotazioni future: 3 → sotto il limite
                                               conta le prenotazioni future: 3 → sotto il limite
insert lunedì ✓
                                               insert martedì ✓                ← 5 su 4
```

Gli orari sono diversi, quindi il vincolo di non sovrapposizione non aiuta. Succede davvero? Con un doppio clic, o con due schede aperte, sì.

### 4.2 Strada A — Lucchetto pessimistico sulla riga del cliente (`SELECT ... FOR UPDATE`)

All'inizio della transazione, si **blocca la riga del cliente**:

```java
// customer: JPA aggiunge "for no key update" (o "for update") alla select
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Customer> findLockedById(Long id);
```

La seconda richiesta dello stesso cliente **aspetta** sul lucchetto finché la prima non ha finito (commit o rollback). Quando riparte, conta di nuovo, e vede la prenotazione della prima:

```
Richiesta 1: lock cliente C ✓, conta 3, insert lunedì, commit (il lucchetto si scioglie)
Richiesta 2: lock cliente C ... aspetta ... ✓, conta 4 → BOOKING_LIMIT_REACHED
```

- **Pro**: semplice e classico. Serializza solo le richieste **dello stesso cliente**: due clienti diversi non si aspettano mai.
- **Pro**: l'attesa è breve (una richiesta dura millisecondi) e nessuna richiesta fallisce per colpa del lucchetto: semplicemente aspetta il suo turno.
- **Contro**: la riga è di un altro modulo. `booking` non può usare il repository di `customer` (D-002, D-026), quindi chiede a `CustomerService` un metodo apposta, `lockForBooking(customerId)`.

### 4.3 Strada B — Lucchetto ottimistico (`@Version`)

Ogni prenotazione **scrive** la riga del cliente (per esempio incrementa una versione); due scritture concorrenti della stessa versione fanno fallire la seconda con `OptimisticLockException`.
- **Pro**: nessuna attesa.
- **Contro**: una scrittura inutile sulla riga del cliente a ogni prenotazione, e la seconda richiesta **fallisce** invece di aspettare: andrebbe ripetuta (*retry*) o mostrata al cliente come un errore che non ha senso per lui.
- Il lucchetto ottimistico va bene quando i conflitti sono rari **e** chi perde può ripetere: lo useremo nel pezzo 3 per le transizioni (`version` di `booking`, già nella V4).

### 4.4 Strada C — Isolamento `SERIALIZABLE`

PostgreSQL, con l'isolamento più alto, si accorge che due transazioni hanno letto e scritto dati che si incrociano (*Serializable Snapshot Isolation*) e ne fa fallire una con l'errore `40001`.
- **Pro**: nessun lucchetto esplicito; protegge anche da corse che non abbiamo previsto.
- **Contro**: anche qui chi perde **fallisce** e va ripetuto, e i falsi positivi sono possibili (PostgreSQL a volte annulla transazioni che non avrebbero dato problemi). Il livello va impostato sulla transazione, e cambia il comportamento di tutto ciò che fa.

### 4.5 Strada D — Lucchetto consultivo di PostgreSQL (`pg_advisory_xact_lock`)

Un lucchetto su un **numero** scelto da noi (l'id del cliente), sciolto al commit.
- **Pro**: appartiene a `booking`, non tocca la tabella di un altro modulo.
- **Contro**: specifico di PostgreSQL, i numeri sono condivisi da tutta l'applicazione (servono convenzioni per non scontrarsi), e chi legge il codice deve conoscere una funzione poco nota.

### 4.6 Raccomandazione

**A.** È la risposta che un intervistatore si aspetta per "controlla un conteggio sotto concorrenza", e qui ha il comportamento giusto per il cliente: aspettare, non fallire. Il metodo di `customer` si chiama per quello che fa e vale **solo dentro una transazione**:

```java
@Transactional(propagation = Propagation.MANDATORY)
public void lockForBooking(long customerId)
```

`MANDATORY` vuol dire "devo partecipare a una transazione che esiste già, altrimenti lancio un'eccezione". Senza transazione il lucchetto si scioglierebbe appena finita la `select`, e il metodo non proteggerebbe niente in silenzio: meglio un errore subito.

### 4.7 Il livello di isolamento: solo quanto basta

PostgreSQL usa per default **`READ COMMITTED`**: ogni istruzione vede i dati confermati **nel momento in cui inizia**. È proprio questo che fa funzionare la strada A: il conteggio parte **dopo** che il lucchetto è stato ottenuto, cioè dopo il commit della prima richiesta, e quindi la vede.

Con **`REPEATABLE READ`** la transazione vedrebbe per tutta la sua durata la fotografia presa alla **prima** istruzione. Se la prima istruzione fosse la richiesta del lucchetto, la fotografia sarebbe presa **prima** dell'attesa: il conteggio non vedrebbe la prenotazione appena confermata, e il limite verrebbe superato lo stesso. Il lucchetto e il livello di isolamento vanno pensati **insieme**.

| Livello | Cosa vede ogni istruzione | Con il lucchetto della strada A |
|---|---|---|
| `READ COMMITTED` (default) | i dati confermati quando l'istruzione inizia | ✅ il conteggio vede la prima prenotazione |
| `REPEATABLE READ` | la fotografia della prima istruzione | ❌ il conteggio può non vederla |
| `SERIALIZABLE` | come `REPEATABLE READ`, più il controllo dei conflitti | fallisce una delle due (strada C) |

Restiamo sul default, e un commento in `BookingService` lo dice.

### 4.8 Cosa conta nel limite

Le prenotazioni **dello stesso cliente con lo stesso professionista**, con **inizio nel futuro** (D-041), negli stati che occupano l'orario, **tranne** le `PENDING_PAYMENT` con blocco scaduto (guida 18, 3.5). Contano anche quelle fatte dal pannello (D-023): il conteggio non guarda `booked_by`.

---

## Parte 5 — Quarta scelta: il blocco scaduto davanti al vincolo

### 5.1 Il problema (guida 18, 3.5)

Il calcolo considera libero un orario con un blocco scaduto, ma il vincolo del database no, finché il lavoro programmato (pezzo 4) non porta la riga a `EXPIRED`. Senza fare niente, chi chiede quell'orario nel minuto sbagliato riceve `SLOT_NOT_AVAILABLE` per un orario che gli abbiamo appena mostrato libero.

### 5.2 Strada A — Scadenza "pigra" nella stessa transazione

Prima dell'`insert`, la richiesta chiude lei i blocchi scaduti **che si sovrappongono all'orario richiesto**:

```java
@Modifying
@Query("""
        update Booking b set b.status = :expired, b.version = b.version + 1
        where b.practitionerId = :practitionerId
          and b.status = :pending and b.holdExpiresAt <= :now
          and b.startsAt < :end and b.endsAt > :start
        """)
int expireStaleHolds(...);
```

È la stessa transizione del lavoro programmato (`PENDING_PAYMENT` → `EXPIRED`), fatta prima, solo dove serve.
- **Pro**: chi chiede l'orario non vede mai la differenza tra le due idee di "occupato".
- **Pro**: sicura sotto concorrenza. Se il lavoro programmato e la richiesta chiudono la stessa riga nello stesso momento, il secondo `update` aspetta il primo e poi **rilegge** la condizione (`status = PENDING_PAYMENT`) sulla riga aggiornata: non la trova più, e aggiorna zero righe. Nessun errore.
- Due dettagli da ricordare:
  - un `update` JPQL in blocco **salta** l'entità e il suo `@Version`: per questo la versione si incrementa a mano. Una transizione concorrente sulla stessa riga (il pezzo 3, con il lucchetto ottimistico) si accorgerà del cambio;
  - la condizione `where` **è** la guardia della transizione: si chiude solo una prenotazione ancora `PENDING_PAYMENT` e davvero scaduta.

### 5.3 Strada B — Aspettare il lavoro programmato

- **Contro**: fino a un minuto di "occupato" per un orario libero. Il brief lo esclude.

### 5.4 Strada C — Provare l'`insert`, e se il vincolo scatta chiudere e riprovare

- **Contro**: dopo un errore PostgreSQL annulla **tutta** la transazione, lucchetto compreso: bisognerebbe ricominciare da capo. Più codice per arrivare allo stesso punto della strada A.

### 5.5 Strada D — Cancellare la riga scaduta

- **Contro**: si perde la storia (chi ha provato a prenotare e non ha pagato), e un pagamento arrivato tardi non troverebbe più la sua prenotazione (D-031, pagamento tardivo).

### 5.6 Raccomandazione

**A.** E per il limite non serve niente di nuovo: il conteggio usa già la stessa condizione del calcolo (parte 4.8), quindi un blocco scaduto non conta.

---

## Parte 6 — La violazione del vincolo diventa un 409

Anche con tutti i controlli, due clienti diversi possono chiedere lo stesso orario nello stesso istante: entrambi passano il controllo dell'orario libero (nessuno vede la riga non confermata dell'altro), e il secondo `insert` si ferma sul vincolo (guida 18, 3.3). L'errore va riconosciuto e trasformato in `SLOT_NOT_AVAILABLE`, lo stesso codice del controllo in Java: per il cliente è la stessa cosa.

**Dove**: in `BookingService`, non nell'`ApiExceptionHandler`. Il nome del vincolo appartiene al modulo `booking`; il gestore degli errori resta generico (oggi trasforma ogni `DataIntegrityViolationException` in un 409 `DATA_CONFLICT`).

**Come**:
- `saveAndFlush`, non `save`: l'`insert` deve partire **adesso**, dentro il `try`. Con `save`, Hibernate lo rimanderebbe al commit, fuori dal metodo, dove nessuno può più tradurre l'errore;
- si riconosce la violazione dal **codice SQLSTATE** `23P01` (*exclusion violation*) e dal **nome del vincolo**, cercati nelle cause dell'eccezione. **Correzione (2026-11-07)**: anche `40P01` (*deadlock*) vuol dire la stessa cosa, perché due `insert` contemporanei su un *exclusion constraint* possono aspettarsi a vicenda (guida 18, 3.3, correzione); arriva come `PessimisticLockingFailureException` e va tradotto anch'esso. Il driver di PostgreSQL è una dipendenza `runtime`: il codice non può nominare le sue classi, ma `java.sql.SQLException` sì;
- ogni altra violazione si rilancia com'è: è un bug, non un orario preso.

---

## Parte 7 — Il cliente corrente provvisorio

Come `CurrentPractitioner` (D-028), nel modulo **`customer`**:
- interfaccia pubblica `CurrentCustomer` con `long id()`;
- implementazione provvisoria `DevHeaderCurrentCustomer`: legge l'header **`X-Customer-Id`** e controlla che il cliente esista; altrimenti `NotAuthenticatedException` → 401;
- `TODO (step 8)` nel codice e criterio di "fatto" del passo 8 (già in `stato.md`).

Come per il professionista, l'identità è **dichiarata dal client, non verificata**: non deve mai arrivare in produzione.

---

## Parte 8 — Le scelte piccole

| # | Scelta | Proposta | Perché |
|---|---|---|---|
| 1 | **URL** | `POST /api/customer/bookings` (201 + `Location`), `GET /api/customer/bookings/{id}` | Stesso stile di `/api/panel`. Il `GET` serve subito: la pagina di ritorno da Stripe chiederà lo stato della prenotazione (D-007) |
| 2 | **Corpo della richiesta** | `NewBooking(practitionerId, serviceId, startsAt)`, `startsAt` con l'offset (`OffsetDateTime`) | La fine la calcola il backend dalla durata (il frontend non decide niente, D-005). Formato degli orari come in tutte le API (D-035) |
| 3 | **Risposta** | `CustomerBookingView`: `id`, `practitionerId`, `serviceId`, `startsAt`, `endsAt`, `status`, `priceCents`, `holdExpiresAt`, in ora di Roma | Quello che serve alla pagina di pagamento. `BookingStatus` diventa pubblico: è parte del contratto delle API |
| 4 | **Cosa del cliente di un altro** | 404, come una prenotazione inesistente | La regola di proprietà del passo 5 |
| 5 | **Blocco** | `Booking.HOLD = 30 minuti`, `hold_expires_at = now + HOLD` | D-008 |
| 6 | **Costruzione** | Metodo statico `Booking.requestedByCustomer(...)`, nessun costruttore pubblico | Il nome dice da dove nasce la prenotazione; il pezzo 5 aggiungerà `bookedFromPanel(...)` con regole diverse |
| 7 | **Doppio clic sullo stesso orario** | Nessun trattamento speciale | La seconda richiesta aspetta il lucchetto, poi trova l'orario occupato dalla prima: `SLOT_NOT_AVAILABLE`. La prima resta valida |
| 8 | **Cliente disattivato** | Non controllato ora | Come per il professionista (D-029): lo farà il login al passo 8 |
| 9 | **Pezzo 5** | Le prenotazioni dal pannello prenderanno lo **stesso lucchetto** | Non rispettano il limite, ma contano: senza lucchetto una richiesta del cliente contemporanea potrebbe non vederle |

---

## Parte 9 — I test

### 9.1 Il servizio (`BookingServiceTest`, `@DataJpaTest`, `Clock` fisso: venerdì 6 novembre 2026, 10:00)

Anna lavora dal lunedì al venerdì 9–13 e 14–19, scatto 60, seduta da 60 minuti a 30 €.

| # | Caso | Atteso |
|---|---|---|
| 1 | Lunedì 9:00 | `PENDING_PAYMENT`, fine 10:00, blocco fino alle 10:30 di oggi, prezzo 3000, `CUSTOMER`, `CARD` (letti dalla riga) |
| 2 | Servizio disattivato | `NotFoundException` |
| 3 | Lunedì 9:30 (fuori griglia), 8:00 e 13:00 (fuori fascia), sabato | `SLOT_NOT_AVAILABLE` |
| 4 | Anticipo di 72 ore: lunedì 9:00 è a 71 ore | `SLOT_NOT_AVAILABLE` |
| 5 | Lunedì 30 novembre (oltre l'orizzonte di 21 giorni) | `SLOT_NOT_AVAILABLE` |
| 6 | Un'assenza lunedì 9–10 | `SLOT_NOT_AVAILABLE` |
| 7 | Lunedì 9:00 già `CONFIRMED` per un altro cliente | `SLOT_NOT_AVAILABLE` |
| 8 | Lunedì 9:00 `PENDING_PAYMENT` di un altro, blocco scaduto | accettata; la vecchia diventa `EXPIRED` con la versione incrementata |
| 9 | Limite 2, il cliente ha già una `PENDING_APPROVAL` e una `CONFIRMED` future | `BOOKING_LIMIT_REACHED` |
| 10 | Limite 2, ma una delle due è un blocco scaduto, un'altra è finale, un'altra è passata, un'altra è di Marco | accettata: nessuna conta |
| 11 | Limite 1, il cliente ha una prenotazione fatta dal pannello | `BOOKING_LIMIT_REACHED`: conta anche quella |
| 12 | Limite raggiunto **e** orario occupato | `BOOKING_LIMIT_REACHED`: il limite viene prima |
| 13 | Il cliente legge la sua prenotazione; quella di un altro | la vista; `NotFoundException` |

### 9.2 Concorrenza (`BookingConcurrencyTest`, criterio di "fatto")

Stesso schema del pezzo 1: niente transazione del test, due thread che partono insieme, ognuno dentro una propria transazione che resta aperta per un momento dopo la richiesta, così che le due si sovrappongano davvero.

| # | Caso | Atteso |
|---|---|---|
| 14 | **Stesso cliente, limite 1, due orari diversi** | una riuscita, una `BOOKING_LIMIT_REACHED`; una riga sola. **Senza il lucchetto passerebbero tutte e due** |
| 15 | **Due clienti, stesso orario, attraverso il servizio** | una riuscita, una `SLOT_NOT_AVAILABLE` tradotta dal vincolo (parte 6); una riga sola |

### 9.3 Il cliente corrente e il lucchetto (`CustomerServiceTest`)

| # | Caso | Atteso |
|---|---|---|
| 16 | `lockForBooking` di un cliente che esiste, dentro una transazione | nessun errore |
| 17 | Cliente inesistente | `NotFoundException` |
| 18 | Fuori da una transazione | `IllegalTransactionStateException`: `MANDATORY` fa il suo lavoro |

### 9.4 API (`BookingCustomerApiTest`, `@SpringBootTest` + `MockMvcTester`)

| # | Caso | Atteso |
|---|---|---|
| 19 | Senza header, o con un cliente inesistente | 401 |
| 20 | Richiesta valida | 201, `Location`, JSON esatto con gli orari in `+01:00` |
| 21 | `startsAt` mancante | 400 sul campo |
| 22 | Orario occupato; limite raggiunto | 409 con il codice |
| 23 | `GET` della propria prenotazione; di quella di un altro | 200; 404 |

---

## Parte 10 — Cosa è stato deciso

Nicholas ha chiesto di procedere senza conferme se non c'erano domande: le scelte seguono le raccomandazioni di questa guida e sono registrate in D-045.

1. **Ordine dei controlli** (parte 2): servizio, lucchetto, limite, orario libero, blocchi vecchi, `insert`; un solo codice per tutto ciò che decide il calcolo.
2. **Orario libero** (parte 3): A, `requireFreeSlot` riusa il calcolo.
3. **Limite** (parte 4): A, lucchetto pessimistico sulla riga del cliente, con `READ COMMITTED`.
4. **Blocco scaduto** (parte 5): A, scadenza pigra nella stessa transazione.

---

## Domande da colloquio

### Domanda 1: Come fai rispettare un limite ("al massimo N prenotazioni per cliente") con richieste concorrenti?
**Risposta modello.** Il conteggio in Java da solo non basta: due transazioni possono contare insieme e inserire entrambe. Serializzo le richieste dello stesso cliente con un lucchetto pessimistico sulla sua riga (`SELECT ... FOR UPDATE`, in JPA `@Lock(PESSIMISTIC_WRITE)`) all'inizio della transazione: la seconda aspetta la fine della prima e poi conta di nuovo. Funziona con `READ COMMITTED`, perché il conteggio parte dopo l'attesa e vede i dati confermati. Alternative: `SERIALIZABLE` o un lucchetto ottimistico, ma lì chi perde fallisce e va ripetuto.

### Domanda 2: Lucchetto pessimistico o ottimistico: quando scegli l'uno o l'altro?
**Risposta modello.** Pessimistico quando i conflitti sono probabili o quando chi perde non dovrebbe fallire ma solo aspettare: blocca la risorsa e serializza. Ottimistico quando i conflitti sono rari e chi perde può ripetere o ricevere un errore sensato: nessun blocco, una colonna `version` controllata alla scrittura, `OptimisticLockException` per il secondo. Qui uso il pessimistico per il limite (il cliente deve solo aspettare) e l'ottimistico per le transizioni della prenotazione (approvare e scadere insieme è raro, e chi perde deve fallire).

### Domanda 3: Perché il livello di isolamento conta per un lucchetto?
**Risposta modello.** Perché decide cosa vede la transazione dopo l'attesa. Con `READ COMMITTED` ogni istruzione vede i dati confermati al suo inizio, quindi la query dopo il lucchetto vede ciò che l'altra transazione ha appena confermato. Con `REPEATABLE READ` la transazione vede la fotografia presa alla prima istruzione: se la fotografia è precedente all'attesa, il controllo dopo il lucchetto lavora su dati vecchi e il lucchetto non serve a niente.

### Domanda 4: Come trasformi la violazione di un vincolo del database in un errore leggibile?
**Risposta modello.** Forzo l'esecuzione dell'istruzione dove posso intercettarla (`saveAndFlush`, non al commit), riconosco la violazione dal codice SQLSTATE e dal nome del vincolo, e la trasformo nell'errore di dominio che il controllo in Java avrebbe dato: per il cliente "orario non disponibile" è la stessa cosa, chiunque l'abbia scoperto. Lo faccio nel modulo che possiede il vincolo, non in un gestore globale che dovrebbe conoscere i nomi di tutti i vincoli. Ogni altra violazione la rilancio: è un bug.

### Domanda 5: Un `update` JPQL in blocco e un'entità con `@Version`: cosa devi sapere?
**Risposta modello.** Un `update` in blocco va dritto al database: salta il *persistence context*, i *callback* e il controllo della versione. Se la tabella usa il lucchetto ottimistico, incremento la versione nello stesso `update`, così una transazione che ha letto la riga prima se ne accorge. E metto nella condizione `where` lo stato di partenza: è la guardia della transizione, e sotto concorrenza un secondo `update` sulla stessa riga la rilegge e non fa niente.

### Domanda 6: A cosa serve `Propagation.MANDATORY`?
**Risposta modello.** Dichiara che il metodo ha senso solo dentro una transazione già aperta da chi lo chiama, e lancia un'eccezione se non c'è. È il caso di un lucchetto: senza la transazione di chi chiama si scioglierebbe appena finita la query, e il metodo non proteggerebbe niente senza dare errori. Meglio un errore subito che un lucchetto finto.

---

## Documentazione ufficiale

- PostgreSQL, lucchetti a livello di riga (`FOR UPDATE`, `FOR NO KEY UPDATE`): https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS
- PostgreSQL, livelli di isolamento: https://www.postgresql.org/docs/17/transaction-iso.html
- PostgreSQL, lucchetti consultivi: https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS
- Jakarta Persistence, `LockModeType`: https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/lockmodetype
- Spring Data JPA, `@Lock` e `@Modifying`: https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html
- Spring Framework, propagazione delle transazioni: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html
