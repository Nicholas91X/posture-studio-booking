# Passo 07 — Prenotazione

> **Stato: in corso.** Pezzi 1 e 2 fatti (2026-11-06), pezzo 3 fatto (2026-11-07), 390 test verdi (`mvnw clean test`). Branch `feat/step-7-booking`.

## Obiettivo
Il cliente chiede un orario e il sistema lo accompagna fino alla conferma: controlli, blocco dell'orario, pagamento (ancora simulato), approvazione automatica o manuale, oppure rifiuto, scadenza o ritiro. Il database rende impossibili le doppie prenotazioni. Due lavori programmati fanno pulizia.

I pezzi (guida 18, 1.2):
1. Migrazione V4 e ciclo tra `schedule` e `booking` — ✅ fatto
2. Richiesta di prenotazione del cliente: controlli, `PENDING_PAYMENT`, limite sotto concorrenza, blocco scaduto — ✅ fatto
3. Stati e approvazione: macchina a stati, incasso e annullamento provvisori, eventi di dominio — ✅ fatto
4. Lavori programmati: `EXPIRED` e `APPROVAL_EXPIRED` ogni minuto, con il `Clock`
5. Prenotazione dal pannello, pagata in studio (D-040)
6. Liste e segnalazioni

Fuori dal passo: crediti (passo 10), cancellazione di una prenotazione confermata (passo 11), email e push (passo 8, D-039), Stripe vero (passo 9).

## Cosa è stato costruito

| File | Chi | Cosa fa |
|---|---|---|
| **Pezzo 1** | | |
| `db/migration/V4__create_booking_and_approval_settings.sql` | Claude, su richiesta di Nicholas | In `practitioner`: `max_future_bookings` (4), `booking_approval` (`MANUAL`), `approval_window_hours` (12) aggiunto in tre tempi (colonna, `update` delle righe con anticipo più corto, poi il `check` su due colonne: da 1 all'anticipo e al massimo 48). In `service`: `evaluation`. Tabella `booking`: *foreign key* composta per il servizio del professionista, `check` con i soli valori del passo 7, `check` che legano lo stato a una colonna (blocco, scadenza), "in studio solo dal pannello", e l'*exclusion constraint* parziale `booking_no_overlap_ex`; due indici |
| `practitioner/BookingApproval.java` | Claude, su richiesta di Nicholas | `AUTOMATIC` / `MANUAL`, pubblico: `booking` lo leggerà dalle impostazioni |
| `practitioner/PractitionerSettings.java` | Claude, su richiesta di Nicholas | Da cinque a otto impostazioni; `@Max(48)` sulla finestra; vincolo di classe `WindowWithinNotice` con l'errore sul campo `approvalWindowHours` |
| `practitioner/Practitioner.java`, `shared/Checks.java` | Claude, su richiesta di Nicholas | Le tre colonne nuove; `changeSettings` controlla la finestra tra 1 e il minimo tra anticipo e 48 (`requireBetween`, nuovo in `Checks`) prima di cambiare qualunque campo |
| `catalog/ServiceOffer.java`, `ServiceOfferDetails.java`, `ServiceOfferView.java` | Claude, su richiesta di Nicholas | Il flag `evaluation` (D-043): obbligatorio in ingresso, visibile nel pannello, non nel catalogo pubblico |
| `catalog/ServiceOnSale.java` | Claude, su richiesta di Nicholas | Aggiunto `priceCents`, il prezzo che il pezzo 2 fotograferà (D-038) |
| `schedule/OccupiedTime.java` | Claude, su richiesta di Nicholas | L'interfaccia di `schedule` per il tempo occupato (D-044), `@FunctionalInterface`, con `now` passato esplicitamente |
| `schedule/ScheduleService.java` | Claude, su richiesta di Nicholas | Gli intervalli occupati si uniscono alle assenze, nella stessa finestra decisa dal server; `FreeSlots` non cambia |
| `booking/package-info.java`, `BookingStatus.java`, `BookedBy.java`, `PaidWith.java` | Claude, su richiesta di Nicholas | Nasce il modulo `booking` (package `booking.booking`, perché la radice si chiama già `booking`). I sette stati del passo 7 con `HOLDS_TIME`, gli stessi del `where` del vincolo; chi ha prenotato; carta o in studio |
| `booking/Booking.java`, `BookingRepository.java` | Claude, su richiesta di Nicholas | L'entità, che mappa la tabella (con `@Version`) senza costruttore pubblico né transizioni; la query degli intervalli occupati, in un *text block*, con l'espressione costruttore che crea direttamente i `TimeRange` |
| `booking/BookingOccupiedTime.java` | Claude, su richiesta di Nicholas | L'implementazione di `OccupiedTime`: una classe piccola che dipende solo dal repository, per non creare un ciclo tra bean |
| `test/.../booking/BookingSchemaTest.java` | Claude, su richiesta di Nicholas | 27 test con SQL diretto: sovrapposizioni rifiutate per ogni stato che occupa, prenotazioni che si toccano, due professionisti, stati finali che liberano, **blocco scaduto che occupa ancora per il vincolo**, riga che esce dall'indice con un `update`, vincolo controllato anche sull'`update`, *foreign key*, ogni `check` |
| `test/.../booking/BookingConcurrencyTest.java` | Claude, su richiesta di Nicholas | Il criterio di "fatto": due transazioni simultanee sullo stesso orario, partite insieme con un `CountDownLatch`, ognuna con `pg_sleep` prima del commit perché si sovrappongano davvero. Ne passa una sola. Fuori dalla transazione del test, con pulizia in `@AfterEach` |
| `test/.../booking/BookingFreeSlotsTest.java` | Claude, su richiesta di Nicholas | 13 test: gli orari liberi escludono `PENDING_APPROVAL`, `CONFIRMED` e `PENDING_PAYMENT` con blocco in corso; **non** escludono il blocco scaduto né quello che scade proprio adesso; stati finali; prenotazione a cavallo di due orari; un altro professionista; nessun dato del cliente nella risposta |
| `test/.../booking/BookingTestData.java` | Claude, su richiesta di Nicholas | Righe di prova con SQL (professionisti, clienti, servizi, orari, prenotazioni con le colonne che il loro stato richiede) |
| `test/.../schedule/NothingOccupied.java` | Claude, su richiesta di Nicholas | Un `OccupiedTime` finto, scritto come lambda, per i test `@DataJpaTest` di `schedule` |
| Test di `practitioner` e `catalog` | Claude, su richiesta di Nicholas | Aggiornati ai `record` nuovi. Casi nuovi: finestra oltre l'anticipo → 400 su `approvalWindowHours` con `WindowWithinNotice`; finestra 49 → 400 con `Max`; modo d'approvazione sconosciuto → 400 generico; un client con le impostazioni del passo 6 → 400 sui tre campi mancanti; `check` nuovi del database; `evaluation` salvato, corretto, obbligatorio; `serviceOnSale` con il prezzo |
| **Pezzo 2** | | |
| `customer/Customer.java`, `CustomerRepository.java` | Claude, su richiesta di Nicholas | La prima entità del modulo, sulla tabella della V1; `findLockedById` con `@Lock(PESSIMISTIC_WRITE)` |
| `customer/CustomerService.java` | Claude, su richiesta di Nicholas | La porta del modulo: `lockForBooking`, in `Propagation.MANDATORY` (senza transazione il lucchetto non proteggerebbe niente) |
| `customer/CurrentCustomer.java`, `DevHeaderCurrentCustomer.java` | Claude, su richiesta di Nicholas | Il cliente corrente **provvisorio**, header `X-Customer-Id`, 401 se manca o non esiste; `TODO (step 8)` |
| `shared/ErrorCode.java` | Claude, su richiesta di Nicholas | `SLOT_NOT_AVAILABLE` (un codice per tutte le regole del calcolo) e `BOOKING_LIMIT_REACHED` |
| `schedule/ScheduleService.java` | Claude, su richiesta di Nicholas | `requireFreeSlot`: l'orario richiesto è tra gli orari liberi del suo giorno? Il calcolo è condiviso con l'API pubblica in un metodo privato |
| `booking/Booking.java` | Claude, su richiesta di Nicholas | `HOLD` di 30 minuti; `requestedByCustomer(...)`: `PENDING_PAYMENT`, `CUSTOMER`, `CARD`, prezzo copiato, blocco fino a `now + HOLD` |
| `booking/BookingRepository.java` | Claude, su richiesta di Nicholas | Il conteggio del limite (stessa condizione di "occupato adesso" del calcolo); la scadenza pigra con un `update` in blocco che incrementa la versione a mano; la prenotazione di un cliente con la proprietà nella query |
| `booking/BookingService.java` | Claude, su richiesta di Nicholas | `request`: un solo `clock.instant()`, poi servizio, lucchetto, limite, orario, blocchi scaduti, `saveAndFlush`; la violazione `23P01` di `booking_no_overlap_ex` diventa `SLOT_NOT_AVAILABLE`. `findForCustomer` |
| `booking/BookingStatus.java` | Claude, su richiesta di Nicholas | Diventa pubblico: è nella risposta |
| `booking/NewBooking.java`, `CustomerBookingView.java`, `BookingCustomerController.java` | Claude, su richiesta di Nicholas | `POST /api/customer/bookings` (201 + `Location`) e `GET /api/customer/bookings/{id}`; orari in ora di Roma |
| `test/.../booking/BookingServiceTest.java` | Claude, su richiesta di Nicholas | 19 test: la richiesta che riesce (letta dalla riga), il prezzo del momento, servizio non in vendita, fuori griglia, fuori fascia, sabato, oltre l'orizzonte, dentro l'anticipo, assenza, orario preso, blocco scaduto chiuso (con la versione), blocco scaduto altrove lasciato al lavoro, limite raggiunto, cosa non conta nel limite, la seduta che inizia libera il posto, la prenotazione dal pannello conta, il limite prima dell'orario, la proprietà in lettura |
| `test/.../booking/BookingConcurrencyTest.java` | Claude, su richiesta di Nicholas | Due casi nuovi attraverso il servizio: **stesso cliente, limite 1, due orari** → uno riesce, l'altro `BOOKING_LIMIT_REACHED` (verificato che senza il lucchetto il test fallisce con 2 prenotazioni); **due clienti, stesso orario** → uno riesce, l'altro `SLOT_NOT_AVAILABLE` |
| `test/.../booking/BookingCustomerApiTest.java` | Claude, su richiesta di Nicholas | 8 test dall'esterno: 401, 201 con `Location` e JSON esatto, un altro offset per lo stesso istante, 400 sul campo, 409 con i due codici, 404 per il servizio, 404 per la prenotazione di un altro |
| `test/.../customer/CustomerServiceTest.java` | Claude, su richiesta di Nicholas | 3 test del lucchetto: cliente esistente, mancante, e fuori transazione → `IllegalTransactionStateException` |
| **Pezzo 3** | | |
| `booking/BookingStatus.java` | Claude, su richiesta di Nicholas | `canMoveTo`: la macchina a stati come `switch` espressione senza `default`, sei frecce |
| `booking/Booking.java` | Claude, su richiesta di Nicholas | `paymentAuthorized(window)` (scadenza = richiesta + finestra), `approve`, `reject`, `withdraw`, tutte attraverso `moveTo`; `created_at` scritto dal `Clock` |
| `booking/CardHolds.java`, `LoggingCardHolds.java` | Claude, su richiesta di Nicholas | L'interfaccia per incassare o annullare il blocco sulla carta (D-031), e l'implementazione **provvisoria** che scrive nel log; `TODO (step 9)` |
| `booking/BookingEvent.java` | Claude, su richiesta di Nicholas | `sealed interface` con quattro `record` annidati: `ApprovalRequested`, `BookingConfirmed`, `BookingRejected`, `BookingWithdrawn` |
| `booking/BookingRepository.java` | Claude, su richiesta di Nicholas | Tre letture con `@Lock(PESSIMISTIC_WRITE)`: per id, per id e professionista, per id e cliente |
| `booking/BookingService.java` | Claude, su richiesta di Nicholas | `paymentAuthorized` (un percorso per `MANUAL` e `AUTOMATIC`, pagamento tardivo su `EXPIRED`), `approve`, `reject`, `withdraw`: lucchetto, transizione, carta, evento. La traduzione del vincolo riconosce anche il *deadlock* `40P01` |
| `booking/PanelBookingView.java`, `CustomerBookingView.java` | Claude, su richiesta di Nicholas | La vista del pannello (minima, fino al pezzo 6); `approvalDeadline` anche per il cliente |
| `booking/BookingPanelController.java`, `BookingCustomerController.java` | Claude, su richiesta di Nicholas | `POST /api/panel/bookings/{id}/approve`, `/reject`; `POST /api/customer/bookings/{id}/withdraw` |
| `booking/SimulatedPaymentController.java` | Claude, su richiesta di Nicholas | **Provvisorio**: `POST /api/dev/bookings/{id}/payment-authorized`, esiste solo con `booking.simulated-payments=true`; `TODO (step 9)` |
| `application.properties`, `.env.example` | Claude, su richiesta di Nicholas | `booking.simulated-payments=${BOOKING_SIMULATED_PAYMENTS:false}`; nel modello `.env` la variabile accesa per lo sviluppo locale |
| `test/.../booking/BookingStatusTest.java`, `BookingTest.java` | Claude, su richiesta di Nicholas | 11 test senza Spring: **tutte le 49 coppie di stati** contro il diagramma scritto a mano, e le transizioni dell'entità (colonne di momento, scadenza dalla richiesta, passaggi vietati che non cambiano niente) |
| `test/.../booking/BookingTransitionsTest.java` | Claude, su richiesta di Nicholas | 13 test con `@RecordApplicationEvents`: pagamento in `MANUAL` e in `AUTOMATIC`, doppio pagamento, pagamento tardivo, approvazione, rifiuto che libera l'orario, risposta di un altro, risposta dopo la scadenza, approvazione prima del pagamento, ritiro (anche di un altro, e di una confermata) |
| `test/.../booking/BookingAfterCommitTest.java` | Claude, su richiesta di Nicholas | 3 test senza la transazione del test: l'ascoltatore `@TransactionalEventListener` riceve l'evento confermato; **non** lo riceve se la transazione viene annullata dopo la pubblicazione; un incasso rifiutato lascia `PENDING_APPROVAL` |
| `test/.../booking/BookingTestDoubles.java` | Claude, su richiesta di Nicholas | `RecordingCardHolds` (registra, e può rifiutare l'incasso) e `CommittedEvents` (l'ascoltatore di prova) |
| `test/.../booking/BookingPanelApiTest.java`, `SimulatedPaymentApiTest.java`, `BookingCustomerApiTest.java` | Claude, su richiesta di Nicholas | Approva e rifiuta (JSON esatto, 404 per un altro, 409 per due risposte o dopo la scadenza, 401); ritiro; **senza la proprietà il pagamento simulato è un 404**; con la proprietà, `PENDING_APPROVAL` con scadenza alle 22:00 |

## Concetti chiave

### *Exclusion constraint* parziale
`exclude using gist (practitioner_id with =, tstzrange(starts_at, ends_at) with &&) where (status in (...))`: niente due righe con lo stesso professionista e intervalli sovrapposti, tra quelle negli stati che occupano l'orario. Il `where` rende parziale l'indice che regge il vincolo: una prenotazione rifiutata o scaduta ne esce, e l'orario torna libero. `tstzrange` esiste già in PostgreSQL (per le ore del giorno la V3 aveva dovuto creare `timerange`). `btree_gist` serve per l'uguaglianza su `bigint` dentro un indice GiST.

### Perché il controllo in Java non basta
"È libero? Allora salva" lascia una finestra in cui due transazioni vedono entrambe l'orario libero, perché nessuna vede la riga non confermata dell'altra (*check-then-act*). Con il vincolo, la seconda transazione **aspetta** la fine della prima: se la prima conferma, la seconda fallisce con `23P01 exclusion_violation`; se la prima annulla, la seconda passa. È lo stesso comportamento di un `unique`. Il test di concorrenza lo dimostra con due thread veri.

### Due idee di "occupato"
Il predicato di un indice deve essere `IMMUTABLE`: `now()` non lo è. Più a fondo: un indice è salvato, e una riga ne entra o ne esce solo quando qualcuno la scrive, non al passare del tempo. Quindi per il **vincolo** una `PENDING_PAYMENT` con blocco scaduto occupa ancora l'orario, mentre per gli **orari liberi** (la query di `BookingOccupiedTime`, che conosce `now`) è già libera. Il pezzo 2 deve gestire la differenza quando qualcuno chiede quell'orario.

### Un `check` su due colonne, e le righe esistenti
Un `check` vede tutta la riga, quindi `approval_window_hours <= min_booking_notice_hours` si scrive nel database. Aggiungerlo insieme alla colonna farebbe fallire la migrazione su una riga con anticipo minore del default: prima la colonna, poi un `update` che corregge, poi il vincolo. Flyway su PostgreSQL esegue la migrazione in una transazione: un errore non lascia lo schema a metà.

### Inversione delle dipendenze tra moduli, e il ciclo tra bean
`schedule` dichiara `OccupiedTime` con parole sue, e `booking` la implementa: gli `import` vanno in un verso solo (`booking` → `schedule`), mentre a runtime Spring inietta in `ScheduleService` l'implementazione di `booking`. L'implementazione non è `BookingService`: dal pezzo 2 `BookingService` userà `ScheduleService`, e Spring non potrebbe creare nessuno dei due per primo (i riferimenti circolari sono vietati per default da Spring Boot 2.6).

### `@FunctionalInterface` e lambda nei test
Un'interfaccia con un solo metodo si implementa con una lambda: `(practitionerId, window, now) -> List.of()`. I test di `schedule` non possono nominare `BookingOccupiedTime`, che è package-private in un altro package, e usano questo finto.

### *Text block* ed espressione costruttore in JPQL
`"""` (Java 15) scrive la query su più righe senza `+` né `\n`. `select new ...TimeRange(b.startsAt, b.endsAt)` costruisce i `record` direttamente dalla query, senza caricare le entità.

### Test di concorrenza contro un database vero
`@DataJpaTest` avvolge ogni test in una transazione annullata alla fine: due thread non la possono condividere. Il test gira senza transazione (`Propagation.NOT_SUPPORTED`), ogni thread apre e conferma la propria con `TransactionTemplate`, un `CountDownLatch` li fa partire insieme, e una pausa prima del commit garantisce che le due transazioni si sovrappongano davvero. Siccome le righe sono confermate, `@AfterEach` le cancella: il container è condiviso con gli altri test.

### Il limite sotto concorrenza: lucchetto pessimistico sulla riga del cliente (pezzo 2)
Un conteggio non è un vincolo del database: due richieste dello stesso cliente possono contare insieme e inserire entrambe. All'inizio della transazione `select ... for update` sulla riga del cliente (`@Lock(PESSIMISTIC_WRITE)`): la seconda richiesta aspetta la fine della prima, poi conta di nuovo. Il lucchetto ottimistico e `SERIALIZABLE` farebbero **fallire** la seconda richiesta, che andrebbe ripetuta; qui il cliente deve solo aspettare il suo turno, e clienti diversi non si aspettano mai.

### Il livello di isolamento, solo quanto basta (pezzo 2)
Con `READ COMMITTED`, il default di PostgreSQL, ogni istruzione vede i dati confermati quando inizia: il conteggio, che parte dopo l'attesa, vede la prenotazione appena confermata. Con `REPEATABLE READ` la transazione lavorerebbe sulla fotografia della prima istruzione, presa prima dell'attesa, e il lucchetto non servirebbe a niente. Lucchetto e isolamento si scelgono insieme.

### `Propagation.MANDATORY` (pezzo 2)
Un lucchetto vive quanto la transazione. Fuori da una transazione si scioglierebbe appena finita la `select`, senza errori: con `MANDATORY` Spring rifiuta la chiamata se non c'è una transazione aperta da chi chiama.

### Un solo calcolo, un solo codice d'errore (pezzo 2)
L'orario richiesto si controlla rifacendo il calcolo degli orari liberi del suo giorno: impossibile che il cliente veda un orario libero e la richiesta lo rifiuti per una regola scritta diversamente. Griglia, fasce, anticipo, orizzonte, assenze e orari presi danno tutti `SLOT_NOT_AVAILABLE`: il cliente sceglie comunque un altro orario, e codici diversi direbbero a chiunque dove ci sono prenotazioni.

### Scadenza pigra e `update` in blocco (pezzo 2)
Prima dell'`insert`, la richiesta porta a `EXPIRED` i blocchi scaduti che si sovrappongono all'orario. Un `update` JPQL in blocco salta l'entità: niente `@Version` automatico (lo si incrementa a mano) e niente *persistence context*. La condizione `where` è la guardia della transizione: se il lavoro programmato chiude la stessa riga nello stesso momento, il secondo `update` la rilegge e non cambia niente.

### Tradurre la violazione di un vincolo (pezzo 2)
`saveAndFlush` fa partire l'`insert` dentro il `try`; `save` lo rimanderebbe al commit, fuori dal metodo. La violazione si riconosce da `SQLSTATE 23P01` e dal nome del vincolo, cercati nelle cause: il driver di PostgreSQL è `runtime`, ma `java.sql.SQLException` si può nominare. La traduzione sta nel modulo che possiede il vincolo.

### Un test che deve saper fallire (pezzo 2)
Un test di concorrenza che passa non dimostra niente, finché non si vede fallire nel caso sbagliato. Tolto per un momento il lucchetto, il test del limite ha dato 2 prenotazioni su 1; rimesso il lucchetto, è tornato verde (tre esecuzioni su tre).

### Macchina a stati con uno `switch` esaustivo (pezzo 3)
Uno `switch` usato come espressione su un `enum`, senza `default`, deve coprire ogni valore: uno stato nuovo non compila finché qualcuno non ne decide le frecce. Lo stato dell'entità cambia solo in `moveTo`, che chiede alla macchina; sopra ci sono transizioni con nomi di dominio. Un passaggio vietato è un 409, perché succede davvero (il cliente ritira un attimo prima che il professionista approvi). Il test controlla tutte le 49 coppie contro il diagramma scritto a mano.

### Una chiamata esterna dentro una transazione (pezzo 3)
L'incasso non si annulla con un `rollback`. Con il lucchetto ottimistico il conflitto (il lavoro programmato che fa scadere la stessa richiesta) si scoprirebbe al commit, dopo aver incassato. Per questo ogni transizione blocca prima la riga (`select ... for update`), poi cambia lo stato in memoria (un passaggio vietato non arriva mai a Stripe), poi chiama la carta, poi conferma. Resta la doppia scrittura, "incassato ma commit fallito", che si chiude riconciliando con il webhook al passo 9.

### Eventi di dominio, `sealed` e dopo il commit (pezzo 3)
`booking` annuncia cosa è successo senza sapere chi ascolta. Gli eventi sono `record` di un'interfaccia `sealed`: l'insieme è chiuso, e al passo 8 uno `switch` con pattern matching (Java 21) li gestirà tutti senza `default`. Si pubblicano dentro la transazione con `ApplicationEventPublisher`; `@TransactionalEventListener` li consegna solo dopo un commit riuscito, mentre `@EventListener` li consegnerebbe subito, anche per una transazione poi annullata. Il test lo dimostra pubblicando l'evento dentro una transazione che poi viene annullata.

### Spento per default (pezzo 3)
Il pagamento simulato esiste solo se `booking.simulated-payments` vale `true` (`@ConditionalOnProperty`), con default `false`: dimenticare qualcosa lo lascia spento. Un test prova che senza la proprietà l'indirizzo non esiste.

### *Deadlock* su un *exclusion constraint* (pezzo 3, scoperto nei test)
Un `unique` cerca i conflitti prima di inserire nell'indice; un *exclusion constraint* inserisce prima e cerca dopo. Due `insert` davvero contemporanei possono quindi vedersi a vicenda e aspettarsi a vicenda; PostgreSQL rompe l'attesa circolare dopo `deadlock_timeout` annullandone uno con `40P01`. Il risultato è lo stesso (una riga sola), ma l'errore è diverso, e il codice deve riconoscerli entrambi.

## Decisioni
- D-039: notifiche push lato backend al passo 8, con l'email.
- D-040: prenotazioni dal pannello pagate in studio; link di pagamento da decidere (punto aperto 16).
- D-041: orizzonte 21 giorni, limite 4 prenotazioni future.
- D-042: schema della V4.
- D-043: il servizio di valutazione è una proprietà del servizio.
- D-044: `OccupiedTime`, il ciclo tra `schedule` e `booking`.
- D-045: la richiesta di prenotazione (ordine dei controlli, un solo calcolo, lucchetto sul cliente, scadenza pigra, traduzione del vincolo, `/api/customer`).
- D-046: stati e approvazione (macchina nell'`enum`, lucchetto pessimistico sulle transizioni, `CardHolds`, eventi `sealed` dopo il commit, pagamento simulato spento per default, traduzione del *deadlock*).

## Errori fatti e come sono stati corretti

1. **Notifiche push senza la loro riserva.** D-032 metteva le push al passo 7 e l'email al passo 8, ma la stessa D-032 vuole l'email come riserva delle push: al passo 7 una richiesta poteva restare senza avviso. Corretto spostando le push al passo 8 con una scheda nuova (D-039), non cambiando D-032 in silenzio.
2. **Un limite proposto senza guardare come si usa.** D-023 proponeva 2 prenotazioni future. Nicholas ha fatto il conto: con 2 sedute a settimana, 2 coprono solo la settimana in corso. Il valore giusto dipendeva da quanto è pieno il calendario e da come lavorano gli abituali: deciso 4 (D-041). Lezione: un valore di default è una regola di prodotto, va verificato su un caso d'uso reale.
3. **Due vincoli violati dalla stessa riga.** Dopo la V4, `PractitionerSchemaTest.zeroMinBookingNoticeIsRejected` falliva: un anticipo a 0 viola sia il suo `check` sia quello nuovo della finestra (`window >= 1` e `window <= notice` non possono valere insieme), e PostgreSQL ne segnala uno solo, in questo caso l'altro. Il database aveva ragione; era il test a essere diventato impreciso. Corretto accettando uno dei due nomi, con un commento. Lezione: i vincoli che legano più colonne interagiscono con quelli esistenti, e un test che controlla il nome del vincolo deve violarne uno solo, oppure dire perché non può.
4. **Pezzo 2: nessun errore di implementazione.** Tutti i test verdi al primo giro. Per questo il test del limite è stato verificato al contrario, togliendo il lucchetto: se fosse passato lo stesso, sarebbe stato un test inutile, ed è l'errore più insidioso di un test di concorrenza (passa perché le due transazioni non si sono mai sovrapposte). La pausa con `pg_sleep` prima del commit è ciò che lo rende significativo.
5. **Il *deadlock* che la guida 18 non prevedeva (scoperto nel pezzo 3).** La guida 18 (3.3) diceva che, con due `insert` contemporanei sullo stesso orario, la seconda transazione aspetta la prima e poi riceve `23P01`. Durante il pezzo 3 il test di concorrenza del pezzo 1 è fallito con **`ERROR: deadlock detected` (`40P01`)**, dopo averlo passato diverse volte. Il motivo: un *exclusion constraint* inserisce la riga nell'indice **prima** di cercare i conflitti, quindi due `insert` contemporanei possono vedersi a vicenda e aspettarsi a vicenda, finché PostgreSQL ne annulla uno. Conseguenza seria: `BookingService` (pezzo 2) traduceva solo `23P01`, quindi il cliente che perdeva la corsa in quel modo avrebbe ricevuto un **500**; il test dei due clienti passava solo per i tempi. Corretto: anche `40P01` all'`insert` diventa `SLOT_NOT_AVAILABLE` (la nostra `insert` non può aspettare nient'altro), il test del pezzo 1 accetta entrambi gli esiti, e le guide 18 e 19 hanno una nota di correzione. Lezione: un test di concorrenza verde una volta non garantisce che tutti gli intrecci siano stati visti; quando fallisce "a caso", il messaggio d'errore esatto del database dice cosa è successo.
6. **Un commento di test che diceva il falso.** Nel primo `BookingAfterCommitTest` un test faceva fallire l'incasso e diceva che "l'evento è stato pubblicato e poi scartato dal rollback". Ma in `confirm()` l'evento si pubblica **dopo** l'incasso: se l'incasso fallisce, l'evento non parte proprio, e il test non dimostrava niente su `@TransactionalEventListener`. Corretto con un test che pubblica davvero l'evento dentro una transazione poi annullata, più un test separato per l'incasso rifiutato.
7. **Docker spento.** Alla ripresa della sessione la prima esecuzione dei test è fallita in tutte le classi con "Could not find a valid Docker environment": Docker Desktop non era avviato. Non era un problema del codice; il messaggio era in fondo alla catena dei `Caused by`, e va cercato lì prima di toccare qualsiasi cosa.

## Domande da colloquio

### Domanda 1: Come impedisci in modo affidabile due prenotazioni sovrapposte?
**Risposta modello.** Con un vincolo nel database, non solo con un controllo in Java. Il controllo "è libero? allora salva" lascia una finestra in cui due transazioni vedono entrambe l'orario libero. In PostgreSQL uso un *exclusion constraint* con GiST: stesso professionista con `=`, intervalli `tstzrange` con `&&`, e un `where` sugli stati che occupano l'orario. Il controllo in Java resta, per dare un errore chiaro; il vincolo è la garanzia.

### Domanda 2: Cosa succede se due transazioni inseriscono nello stesso momento due righe che violano il vincolo?
**Risposta modello.** Il database non decide finché non sa se l'altra riga esisterà. Se la prima ha già confermato quando la seconda controlla, la seconda fallisce con un *exclusion violation* (`23P01`); se annulla, la seconda passa. Ma un *exclusion constraint*, a differenza di un `unique`, inserisce nell'indice prima di cercare i conflitti: due inserimenti davvero contemporanei possono aspettarsi a vicenda, e PostgreSQL ne annulla uno con un *deadlock* (`40P01`). In Spring arrivano come `DataIntegrityViolationException` e `PessimisticLockingFailureException`: le traduco entrambe nello stesso 409.

### Domanda 3: Perché non metti la scadenza del blocco nel `where` del vincolo?
**Risposta modello.** Perché il predicato di un indice deve essere `IMMUTABLE`, e `now()` non lo è: un indice è salvato, e una riga ne esce solo quando qualcuno la scrive, non al passare del tempo. Quindi distinguo il vincolo, per cui la riga occupa finché qualcuno non ne cambia lo stato, dal calcolo degli orari liberi, che confronta la scadenza con l'istante attuale.

### Domanda 4: Due moduli dipendono l'uno dall'altro. Come rompi il ciclo?
**Risposta modello.** Con l'inversione delle dipendenze: il modulo che ha bisogno dell'informazione dichiara un'interfaccia con le sue parole, l'altro la implementa, e Spring inietta l'implementazione. Attenzione a non spostare il ciclo tra i bean: se l'implementazione fosse il servizio principale dell'altro modulo, che usa il primo, Spring non partirebbe. Per questo l'implementazione è una classe piccola che dipende solo dal repository.

### Domanda 5: Devi aggiungere una colonna `not null` con un vincolo che dipende da un'altra colonna, su una tabella con dati. Come scrivi la migrazione?
**Risposta modello.** In tre passi: la colonna con un default; un `update` che corregge le righe in cui il default violerebbe il vincolo; solo allora il vincolo. Tutto insieme, una sola riga anomala farebbe fallire la migrazione. Su PostgreSQL Flyway la esegue in una transazione, e da PostgreSQL 11 una colonna con un default fisso si aggiunge senza riscrivere la tabella.

### Domanda 6: Come fai rispettare "al massimo N prenotazioni per cliente" con richieste concorrenti?
**Risposta modello.** Un conteggio in Java non basta: due transazioni contano insieme e inseriscono entrambe. Serializzo le richieste dello stesso cliente con un lucchetto pessimistico sulla sua riga (`SELECT ... FOR UPDATE`, in JPA `@Lock(PESSIMISTIC_WRITE)`): la seconda aspetta la fine della prima e poi conta di nuovo. Funziona con `READ COMMITTED`, perché il conteggio parte dopo l'attesa e vede i dati confermati; con `REPEATABLE READ` lavorerebbe su una fotografia vecchia. `SERIALIZABLE` o un lucchetto ottimistico farebbero fallire la seconda, che andrebbe ripetuta.

### Domanda 7: Un `update` JPQL in blocco su un'entità con `@Version`: cosa devi sapere?
**Risposta modello.** Va dritto al database: salta il *persistence context* e il controllo della versione. Incremento la versione nello stesso `update`, così chi ha letto la riga prima se ne accorge, e metto lo stato di partenza nella condizione `where`: è la guardia della transizione, e sotto concorrenza un secondo `update` sulla stessa riga la rilegge e non fa niente.

### Domanda 8: Una transizione chiama un servizio esterno, per esempio un incasso. Lucchetto ottimistico o pessimistico?
**Risposta modello.** Pessimistico. Con l'ottimistico il conflitto si scopre al commit, cioè dopo l'incasso, che non si annulla con un `rollback`. Con `SELECT ... FOR UPDATE` all'inizio chi arriva secondo aspetta e poi rilegge lo stato. L'ordine: lucchetto, controllo della transizione, chiamata esterna, commit. Resta il caso "incasso riuscito, commit fallito", la doppia scrittura, che si chiude riconciliando con il webhook del fornitore.

### Domanda 9: Perché pubblicare gli eventi di dominio dentro la transazione e ascoltarli dopo il commit?
**Risposta modello.** Perché un effetto esterno, come un'email, non deve partire per una modifica che poi viene annullata. Con `@TransactionalEventListener` Spring tiene l'evento da parte e lo consegna solo se il commit riesce; con `@EventListener` lo consegnerebbe subito. Gli eventi sono `record` di un'interfaccia `sealed`, così chi li ascolta li gestisce con uno `switch` esaustivo. Se non si può perdere nemmeno un evento per un riavvio, si salva nella stessa transazione in una tabella (*outbox*).

### Domanda 10: Come provi in un test che un vincolo regge due richieste simultanee?
**Risposta modello.** Tolgo la transazione unica del test, che due thread non possono condividere; faccio partire due thread insieme con un `CountDownLatch`; ognuno inserisce e conferma nella propria transazione, con una pausa prima del commit perché le due si sovrappongano davvero. Verifico che una sola riesca e che ci sia una riga sola, poi cancello quello che è stato confermato. Uso PostgreSQL vero con Testcontainers: un altro database non avrebbe lo stesso vincolo né lo stesso comportamento.
