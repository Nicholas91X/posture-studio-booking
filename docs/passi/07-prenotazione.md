# Passo 07 — Prenotazione

> **Stato: in corso.** Pezzo 1 fatto (2026-11-06), 321 test verdi (`mvnw clean test`). Branch `feat/step-7-booking`.

## Obiettivo
Il cliente chiede un orario e il sistema lo accompagna fino alla conferma: controlli, blocco dell'orario, pagamento (ancora simulato), approvazione automatica o manuale, oppure rifiuto, scadenza o ritiro. Il database rende impossibili le doppie prenotazioni. Due lavori programmati fanno pulizia.

I pezzi (guida 18, 1.2):
1. Migrazione V4 e ciclo tra `schedule` e `booking` — ✅ fatto
2. Richiesta di prenotazione del cliente: controlli, `PENDING_PAYMENT`, limite sotto concorrenza, blocco scaduto
3. Stati e approvazione: macchina a stati, incasso e annullamento provvisori, eventi di dominio
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

## Decisioni
- D-039: notifiche push lato backend al passo 8, con l'email.
- D-040: prenotazioni dal pannello pagate in studio; link di pagamento da decidere (punto aperto 16).
- D-041: orizzonte 21 giorni, limite 4 prenotazioni future.
- D-042: schema della V4.
- D-043: il servizio di valutazione è una proprietà del servizio.
- D-044: `OccupiedTime`, il ciclo tra `schedule` e `booking`.

## Errori fatti e come sono stati corretti

1. **Notifiche push senza la loro riserva.** D-032 metteva le push al passo 7 e l'email al passo 8, ma la stessa D-032 vuole l'email come riserva delle push: al passo 7 una richiesta poteva restare senza avviso. Corretto spostando le push al passo 8 con una scheda nuova (D-039), non cambiando D-032 in silenzio.
2. **Un limite proposto senza guardare come si usa.** D-023 proponeva 2 prenotazioni future. Nicholas ha fatto il conto: con 2 sedute a settimana, 2 coprono solo la settimana in corso. Il valore giusto dipendeva da quanto è pieno il calendario e da come lavorano gli abituali: deciso 4 (D-041). Lezione: un valore di default è una regola di prodotto, va verificato su un caso d'uso reale.
3. **Due vincoli violati dalla stessa riga.** Dopo la V4, `PractitionerSchemaTest.zeroMinBookingNoticeIsRejected` falliva: un anticipo a 0 viola sia il suo `check` sia quello nuovo della finestra (`window >= 1` e `window <= notice` non possono valere insieme), e PostgreSQL ne segnala uno solo, in questo caso l'altro. Il database aveva ragione; era il test a essere diventato impreciso. Corretto accettando uno dei due nomi, con un commento. Lezione: i vincoli che legano più colonne interagiscono con quelli esistenti, e un test che controlla il nome del vincolo deve violarne uno solo, oppure dire perché non può.

## Domande da colloquio

### Domanda 1: Come impedisci in modo affidabile due prenotazioni sovrapposte?
**Risposta modello.** Con un vincolo nel database, non solo con un controllo in Java. Il controllo "è libero? allora salva" lascia una finestra in cui due transazioni vedono entrambe l'orario libero. In PostgreSQL uso un *exclusion constraint* con GiST: stesso professionista con `=`, intervalli `tstzrange` con `&&`, e un `where` sugli stati che occupano l'orario. Il controllo in Java resta, per dare un errore chiaro; il vincolo è la garanzia.

### Domanda 2: Cosa succede se due transazioni inseriscono nello stesso momento due righe che violano il vincolo?
**Risposta modello.** La seconda aspetta la fine della prima, perché il database non sa ancora se la prima riga esisterà. Se la prima conferma, la seconda fallisce con un *exclusion violation* (`23P01`); se la prima annulla, la seconda passa. È lo stesso comportamento di un `unique`. In Spring arriva come `DataIntegrityViolationException`.

### Domanda 3: Perché non metti la scadenza del blocco nel `where` del vincolo?
**Risposta modello.** Perché il predicato di un indice deve essere `IMMUTABLE`, e `now()` non lo è: un indice è salvato, e una riga ne esce solo quando qualcuno la scrive, non al passare del tempo. Quindi distinguo il vincolo, per cui la riga occupa finché qualcuno non ne cambia lo stato, dal calcolo degli orari liberi, che confronta la scadenza con l'istante attuale.

### Domanda 4: Due moduli dipendono l'uno dall'altro. Come rompi il ciclo?
**Risposta modello.** Con l'inversione delle dipendenze: il modulo che ha bisogno dell'informazione dichiara un'interfaccia con le sue parole, l'altro la implementa, e Spring inietta l'implementazione. Attenzione a non spostare il ciclo tra i bean: se l'implementazione fosse il servizio principale dell'altro modulo, che usa il primo, Spring non partirebbe. Per questo l'implementazione è una classe piccola che dipende solo dal repository.

### Domanda 5: Devi aggiungere una colonna `not null` con un vincolo che dipende da un'altra colonna, su una tabella con dati. Come scrivi la migrazione?
**Risposta modello.** In tre passi: la colonna con un default; un `update` che corregge le righe in cui il default violerebbe il vincolo; solo allora il vincolo. Tutto insieme, una sola riga anomala farebbe fallire la migrazione. Su PostgreSQL Flyway la esegue in una transazione, e da PostgreSQL 11 una colonna con un default fisso si aggiunge senza riscrivere la tabella.

### Domanda 6: Come provi in un test che un vincolo regge due richieste simultanee?
**Risposta modello.** Tolgo la transazione unica del test, che due thread non possono condividere; faccio partire due thread insieme con un `CountDownLatch`; ognuno inserisce e conferma nella propria transazione, con una pausa prima del commit perché le due si sovrappongano davvero. Verifico che una sola riesca e che ci sia una riga sola, poi cancello quello che è stato confermato. Uso PostgreSQL vero con Testcontainers: un altro database non avrebbe lo stesso vincolo né lo stesso comportamento.
