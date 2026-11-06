# Passo 06 — Calendario

> **Stato: chiuso** (2026-11-06). Quattro pezzi fatti, 259 test verdi (`mvnw clean test`, 7 container). Il lavoro è sul branch `feat/step-5-catalog`, che Nicholas porterà su `main` insieme al passo 5.

## Obiettivo
Il professionista imposta i suoi orari di lavoro, anche da una data futura, e le sue assenze. Il cliente vede gli orari liberi di un servizio: dentro le fasce, fuori dalle assenze, con l'anticipo minimo e l'orizzonte, senza errori al cambio dell'ora.

I pezzi (guida 13, 1.2):
1. Migrazione V3, impostazioni nuove in `practitioner`, test dei vincoli — ✅ fatto
2. Modulo `schedule` lato pannello: versioni dello schema settimanale e indisponibilità, API sotto `/api/panel` — ✅ fatto
3. Il calcolo degli orari liberi come funzione pura, con i test unitari (compresi i cambi dell'ora) — ✅ fatto
4. L'API pubblica degli orari liberi, con il `Clock`, e la chiusura del passo — ✅ fatto

## Cosa è stato costruito

| File | Chi | Cosa fa |
|---|---|---|
| `db/migration/V3__create_schedule_and_booking_settings.sql` | Claude, su richiesta di Nicholas | Estensione `btree_gist` e tipo `timerange`; in `practitioner` scatto solo 30 o 60 con default 60 (D-033), `min_booking_notice_hours` (24) e `booking_horizon_days` (21) (D-035); tabelle `working_schedule` (versioni, `unique (practitioner_id, valid_from)`, D-034), `working_hours` (fasce, cascata dalla versione, ora piena o mezz'ora, nessuna sovrapposizione con un *exclusion constraint*, D-036) e `unavailability` (fine dopo l'inizio, indice per professionista e inizio) |
| `shared/OneOf.java`, `shared/OneOfValidator.java` | Claude, su richiesta di Nicholas | Vincolo Bean Validation "uno tra questi valori" (D-037): l'errore esce sul suo campo con il codice `OneOf`; `null` è valido (lo ferma `@NotNull`) |
| `shared/Checks.java` | Claude, su richiesta di Nicholas | Aggiunto `requireOneOf`, la seconda difesa nelle entità |
| `practitioner/PractitionerSettings.java` | Claude, su richiesta di Nicholas | Da tre a cinque impostazioni: aggiunte `minBookingNoticeHours` e `bookingHorizonDays`; lo scatto con `@OneOf({30, 60})` |
| `practitioner/Practitioner.java` | Claude, su richiesta di Nicholas | Mappa le due colonne nuove; `changeSettings` accetta solo 30 o 60 (`SLOT_STEPS`) e valori positivi, sempre controllando tutto prima di cambiare qualcosa |
| `schedule/package-info.java` | Claude, su richiesta di Nicholas | Nasce il modulo `schedule`: per ora solo la descrizione, le classi arrivano nel pezzo 2 |
| `test/.../schedule/ScheduleSchemaTest.java` | Claude, su richiesta di Nicholas | 21 test con SQL diretto: versioni, fasce (un vincolo per test, con il nome), sovrapposizioni rifiutate e i casi positivi che le controllano (fasce che si toccano, stesso orario in un altro giorno o in un'altra versione), cascata, indisponibilità |
| `test/.../practitioner/PractitionerSchemaTest.java` | Claude, su richiesta di Nicholas | 5 test: valori iniziali di V3, scatto 30 accettato e 45 rifiutato, anticipo e orizzonte a 0 rifiutati |
| `test/.../shared/OneOfTest.java` | Claude, su richiesta di Nicholas | 3 test di Bean Validation senza Spring, su un `record` di prova |
| `test/.../practitioner/PractitionerTest.java`, `PractitionerServiceTest.java`, `PractitionerPanelApiTest.java` | Claude, su richiesta di Nicholas | Aggiornati alle cinque impostazioni (niente più scatto 15, valore iniziale 60). Nuovi casi: scatto 45 → 400 sul campo `slotStepMinutes` con `OneOf` (non 409 dal database); un client con le sole tre impostazioni del passo 5 → 400 sui due campi mancanti |
| **Pezzo 2** | | |
| `shared/StudioTime.java`, `shared/TimeConfiguration.java` | Claude, su richiesta di Nicholas | La zona dello studio in un posto solo, "oggi a Roma" e la conversione di un istante in ora di Roma; il bean `Clock` (`systemUTC`), unica fonte di "adesso" |
| `shared/ErrorCode.java` | Claude, su richiesta di Nicholas | Tre codici nuovi: `SCHEDULE_ALREADY_STARTED`, `SCHEDULE_STARTS_IN_THE_PAST`, `SCHEDULE_DATE_TAKEN` |
| `schedule/WorkingSchedule.java` | Claude, su richiesta di Nicholas | Entità radice dell'aggregato: `@OneToMany(mappedBy, cascade ALL, orphanRemoval)` verso le fasce; controlla tutte le fasce nuove (anche tra loro) prima di aggiungerne una; `isEditableOn(oggi)` |
| `schedule/WorkingHours.java` | Claude, su richiesta di Nicholas | Una fascia: `@ManyToOne` verso la versione, giorno con `DayOfWeekConverter`, tutto `updatable = false` (si sostituisce, non si modifica); `requireValid` con le regole di V3 |
| `schedule/Unavailability.java` | Claude, su richiesta di Nicholas | Un'assenza: istanti, fine dopo l'inizio, motivo ripulito (vuoto → `null`, massimo 200 caratteri) |
| `schedule/DayOfWeekConverter.java` | Claude, su richiesta di Nicholas | `DayOfWeek` ↔ `smallint` 1–7 |
| `schedule/WorkingScheduleRepository.java`, `UnavailabilityRepository.java` | Claude, su richiesta di Nicholas | Proprietà nella query; elenco delle versioni con `left join fetch` delle fasce (una query); controlli di data già presa; assenze non ancora finite |
| `schedule/WorkingBand.java`, `ScheduleVersionRequest.java`, `ScheduleVersionView.java`, `NewUnavailability.java`, `UnavailabilityView.java` | Claude, su richiesta di Nicholas | I `record` delle API: la fascia con `overlaps` (semiaperta) e `isOnTheHalfHour`; la versione in uscita con `current` ed `editable` calcolati dal backend; le assenze con l'offset di Roma |
| `schedule/OnTheHalfHour.java`, `ValidBands.java`, `NewUnavailability.EndsAfterStart` | Claude, su richiesta di Nicholas | Tre vincoli Bean Validation nostri: orario all'ora piena o alla mezz'ora (campo); fasce valide e non sovrapposte (classe, errore su `bands[i]`); fine dopo l'inizio (classe, errore su `endsAt`) |
| `schedule/ScheduleService.java` | Claude, su richiesta di Nicholas | La porta del modulo: elenco con versione corrente (`getLast()` delle *sequenced collections*), creazione, sostituzione (svuota, `flush`, riempi), cancellazione; assenze: elenco, creazione, cancellazione |
| `schedule/SchedulePanelController.java` | Claude, su richiesta di Nicholas | `GET/POST /api/panel/schedules`, `PUT/DELETE /api/panel/schedules/{id}`, `GET/POST /api/panel/unavailabilities`, `DELETE /api/panel/unavailabilities/{id}` |
| `test/.../FixedClockConfiguration.java` | Claude, su richiesta di Nicholas | Un `Clock` fermo su venerdì 6 novembre 2026, 10:00 a Roma, `@Primary` |
| `test/.../schedule/WorkingScheduleTest.java`, `DayOfWeekConverterTest.java`, `ScheduleValidationTest.java` | Claude, su richiesta di Nicholas | 25 test unitari: regole dell'aggregato, convertitore, i tre vincoli e i loro percorsi d'errore |
| `test/.../schedule/ScheduleServiceTest.java` | Claude, su richiesta di Nicholas | 21 test con `Clock` fisso: date passate, oggi, data presa, sostituzione con fasce identiche, versione congelata, corrente ed editabile, **una sola query** per l'elenco (statistiche di Hibernate), proprietà, assenze |
| `test/.../schedule/SchedulePanelApiTest.java` | Claude, su richiesta di Nicholas | 13 test delle API: 201 e `Location`, JSON esatto, 400 sui percorsi giusti, 409 con codice, 404 per le cose di un altro, 401 senza header, assenze in ora di Roma |
| **Pezzo 3** | | |
| `shared/TimeRange.java` | Claude, su richiesta di Nicholas | Intervallo semiaperto `[start, end)` con il controllo nel costruttore compatto e `overlaps` (`a1 < b2 && b1 < a2`): lo useranno anche le prenotazioni |
| `schedule/SlotQuery.java` | Claude, su richiesta di Nicholas | Il *parameter object* del calcolo: nove ingressi con un nome, controlli nel costruttore compatto (scatto 30 o 60, `from` non dopo `to`…), copie difensive (versioni in una `TreeMap` non modificabile) |
| `schedule/FreeSlots.java` | Claude, su richiesta di Nicholas | La funzione pura: taglio su anticipo e orizzonte prima del ciclo; versione del giorno con `floorEntry`; fasce convertite in istanti con `ZonedDateTime.of` e avanzamento in istanti; esclusione degli occupati; risultato in ordine |
| `test/.../shared/TimeRangeTest.java` | Claude, su richiesta di Nicholas | 12 test: le sovrapposizioni in una tabella `@CsvSource`, ognuna provata nei due versi; intervalli rovesciati o vuoti rifiutati |
| `test/.../schedule/FreeSlotsTest.java` | Claude, su richiesta di Nicholas | 28 test: l'esempio di Anna, griglia e durate, assenze e prenotazioni, anticipo al confine (parametrizzato), orizzonte, cambi dell'ora di marzo (anche una fascia notturna il 29) e ottobre, trappola di mezzanotte, versioni, richieste impossibili. Attesi scritti a mano, in UTC o con l'offset |

| **Pezzo 4** | | |
| `catalog/ServiceOnSale.java`, `CatalogService.serviceOnSale` | Claude, su richiesta di Nicholas | La domanda degli altri moduli al catalogo: "questo servizio è in vendita, e quanto dura?". Una query con proprietà e stato (`findByIdAndPractitionerIdAndActiveTrue`), dopo `requireActive` del professionista |
| `schedule/SlotRange.java` | Claude, su richiesta di Nicholas | `from` e `to` dalla query string (`@ModelAttribute` implicito, costruito con il costruttore), obbligatori, in ISO; vincolo di classe `FromNotAfterTo` con l'errore sul campo `to` |
| `schedule/PublicSlot.java` | Claude, su richiesta di Nicholas | L'unica cosa che esce: `start` ed `end` in ora di Roma |
| `schedule/ScheduleService.freeSlots` | Claude, su richiesta di Nicholas | Il collante: servizio in vendita e durata da `catalog`, impostazioni da `practitioner`, versioni con le fasce (una query), assenze nella finestra decisa dal server (da adesso alla fine dell'orizzonte), un solo `clock.instant()`, poi `FreeSlots` |
| `schedule/SchedulePublicController.java` | Claude, su richiesta di Nicholas | `GET /api/public/practitioners/{id}/services/{serviceId}/slots?from=&to=` |
| `schedule/UnavailabilityRepository.java`, `WorkingScheduleRepository.java` | Claude, su richiesta di Nicholas | Le assenze che si sovrappongono a una finestra (`StartsAtBefore` + `EndsAtAfter`, la fine della finestra per prima); `findForPanel` rinominato `findWithBands`, perché lo usa anche l'API pubblica |
| `test/.../schedule/ScheduleFreeSlotsTest.java` | Claude, su richiesta di Nicholas | 7 test del collante: l'esempio di Anna dal database, impostazioni e durata che arrivano davvero, assenza a cavallo dell'inizio della finestra, assenze di un altro, 404 identici per servizio disattivato, di un altro o inesistente, professionista disattivato |
| `test/.../schedule/SchedulePublicApiTest.java` | Claude, su richiesta di Nicholas | 8 test dall'esterno, senza header: JSON esatto (`start`, `end`, `+01:00`), il motivo di un'assenza non esce mai, settimana oltre l'orizzonte → `[]`, 400 su `to` e su `from`, data non valida → 400, 404 |
| `test/.../catalog/CatalogServiceTest.java` | Claude, su richiesta di Nicholas | 4 test di `serviceOnSale` |

## Concetti chiave

### Tre modi di guardare il tempo
`Instant` per i fatti unici (assenze, prenotazioni), `LocalTime`/`LocalDate` per le regole da calendario a muro (orari di lavoro), `ZonedDateTime` come ponte. Gli orari si generano in locale e si convertono un giorno alla volta: "istante + 24 ore" sbaglia di un'ora nella settimana del cambio. Guida 13, parte 2.

### Effective dating
Gli orari sono versioni con una data di inizio: per un giorno vale la più recente già iniziata. Le versioni iniziate non si toccano, così la storia resta. Guida 13, parte 4; D-034.

### *Exclusion constraint*
Generalizza `unique`: per ogni colonna si sceglie l'operatore, e due righe sono in conflitto se tutti sono veri. Qui: stessa versione (`=`), stesso giorno (`=`), intervallo orario che si sovrappone (`&&` su `timerange`). Serve un indice GiST, e `btree_gist` per usare `=` sui numeri. Il costruttore del range crea `[inizio, fine)`: fasce che si toccano non sono in conflitto. È la prova generale del vincolo sulle prenotazioni del passo 7. Guida 14, parte 2.

### Modificare una tabella che ha già dati
`alter table` in una migrazione nuova: un vincolo `check` non si modifica, si cancella e si ricrea (anche con lo stesso nome); una colonna `not null` nuova ha bisogno di un default per le righe esistenti; un nuovo default non cambia le righe esistenti. Guida 14, 1.1.

### Un vincolo Bean Validation personalizzato
Annotazione con `@Constraint(validatedBy = ...)` e i tre attributi obbligatori, più un `ConstraintValidator`. Per convenzione `null` è valido: così `@OneOf` e `@NotNull` restano indipendenti. Guida 14, parte 3; D-037.

### L'aggregato e il primo `@OneToMany`
Le fasce non hanno vita propria: si salvano, si sostituiscono e spariscono con la loro versione. Per questo, a differenza dei pacchetti di un servizio, qui il `@OneToMany` si mappa (bidirezionale, `mappedBy`, `cascade = ALL`, `orphanRemoval`), c'è un repository solo per la radice, e la regola "nessuna sovrapposizione" la fa rispettare la radice, l'unica che vede tutte le fasce. Guida 15, 5.1.

### L'ordine delle istruzioni al *flush*, verificato
Sostituendo le fasce nella stessa transazione, Hibernate 7 manda prima gli `insert` delle nuove e poi le cancellazioni delle vecchie: con fasce identiche, l'*exclusion constraint* rifiuta. Provato togliendo per un momento il `flush()` dal servizio. Svuotare, `flush()`, riempire rende l'ordine certo. Guida 15, parte 4.

### Validazione tra più campi
Un vincolo di classe riceve l'oggetto intero; con `disableDefaultConstraintViolation` e `addPropertyNode(...).addBeanNode().inIterable().atIndex(i)` l'errore esce su `bands[i]`, e il pannello sa quale fascia correggere. Le regole su "oggi" invece restano nel servizio (409 con codice, D-027). Guida 15, parte 3.

### "Oggi" a Roma, da un `Clock`
`LocalDate.now(clock.withZone(Europe/Rome))`: con l'ora del server, fra mezzanotte e l'una (le due d'estate) "oggi" sarebbe ancora ieri. Nei test un `Clock.fixed` marcato `@Primary`, perché con `@SpringBootTest` esiste anche quello vero.

### Il calcolo come funzione pura
`FreeSlots.compute` non legge database né orologio: riceve tutto in una `SlotQuery`, anche `now`. Per questo 28 casi girano in meno di un secondo, senza Spring, e ognuno si legge come un esempio. Guida 16; D-035.

### La trappola di mezzanotte
`LocalTime` gira a mezzanotte: `22:00 + 2h = 00:00`, che non è "dopo" le 23:30, e una seduta fuori fascia sembra dentro. Il calcolo converte la fascia in istanti e confronta istanti; fra un giorno e l'altro riparte dall'ora locale, così il cambio dell'ora non sposta niente. Guida 16, parte 3.

### `TreeMap.floorEntry`
"La versione più recente già iniziata" è esattamente "la chiave più grande minore o uguale al giorno". Una mappa ordinata lo fa in `O(log n)`, senza scrivere a mano il confronto dove si sbaglia un `<` con un `<=`. Guida 16, parte 4.

### Un test che morde
Per verificare che i test proteggano davvero una regola, si cambia apposta il codice e si guarda se un test diventa rosso (è l'idea del *mutation testing*). Con "almeno 24 ore" al posto di "più di 24 ore" è fallito esattamente il test del confine delle 10:00. Poi il codice è tornato com'era.

### Contare le query in un test
`SessionFactory.getStatistics()`, attivate solo nel test, con `getPrepareStatementCount()`: prova che l'elenco delle versioni con le fasce costa una query, non 1 + N.

### Validare i parametri della query string come un corpo JSON
Un `record` senza `@RequestBody` si riempie dai parametri della richiesta, attraverso il suo costruttore. Con `@Valid` e un vincolo di classe, `from` dopo `to` diventa lo stesso 400 con `errors[].field` dei corpi JSON, invece del 500 che darebbe il costruttore di `SlotQuery`. Guida 17, parte 3.

### Il lavoro lo decide il server
Sia i giorni del calcolo sia la finestra delle assenze sono tagliati sull'orizzonte prima di qualsiasi ciclo o query: una richiesta fino al 2099 costa quanto una di una settimana. Guida 16, 5.3; guida 17, parte 4.

### Dopo un errore, la transazione di PostgreSQL è finita
In un test `@DataJpaTest` tutte le istruzioni stanno nella stessa transazione. Dopo un vincolo violato PostgreSQL la segna come annullata, e ogni istruzione successiva fallisce con "current transaction is aborted". Per questo ogni test prova **una** sola violazione.

## Decisioni
- D-033: scatto 30 o 60, griglia dall'inizio della fascia, fasce all'ora piena o alla mezz'ora.
- D-034: orari di lavoro in versioni settimanali con data di inizio; precisata nel pezzo 2: congelate solo quelle iniziate prima di oggi.
- D-035: calcolo degli orari liberi come funzione pura, API pubblica, orizzonte anticipato nella V3.
- D-036: fasce non sovrapposte anche nel database, con un *exclusion constraint*.
- D-037: "uno tra questi valori" con un vincolo Bean Validation personalizzato.
- D-038: l'API pubblica degli orari liberi: domanda precisa al catalogo, parametri validati come un `record`, assenze nella finestra decisa dal server.

## Errori fatti e come sono stati corretti
- **Nella guida 13 avevo scritto che "ora piena o mezz'ora" non doveva essere un vincolo del database.** Era sbagliato: vale per ogni riga, sempre, e non dipende dal tempo né da altre tabelle, quindi è un `check` come `slot_step_minutes in (30, 60)`. Corretto nella guida 14, 1.4.
- **Nella prima stesura della guida 14 avevo scritto che Hibernate esegue sempre gli `insert` prima dei `delete`.** Vale per le cancellazioni esplicite. Per i figli tolti con l'*orphan removal* Hibernate in alcuni casi cancella prima. Corretto prima della consegna: nel pezzo 2 la sostituzione di una versione sarà provata da un test.
- **Il perimetro del pezzo 1 era sbagliato nella guida 13**, che lo descriveva come solo SQL. Tre classi di test usavano uno scatto di 15 e si aspettavano 30 come valore iniziale: con V3 sarebbero fallite. Il pezzo ha compreso anche le impostazioni in Java.
- **Un test provava due violazioni di fila** (giorno 0 e giorno 8). La seconda sarebbe fallita per la transazione annullata, non per il vincolo. Diviso in due test prima di eseguirlo.
- **Il testo di D-034 aveva un buco** (pezzo 2): una versione creata "da oggi" con un errore non si poteva più correggere, né sostituire con un'altra "da oggi" per il `unique`. Precisata: congelate solo le versioni iniziate prima di oggi (guida 15, parte 2).
- **Il test dell'API leggeva l'id con `getContentAsString()`**, che dichiara un'eccezione controllata: non compilava. Sostituito da un piccolo helper che legge i byte della risposta in UTF-8.
- **Pezzo 4, nessun errore nel codice.** Un rischio noto è stato coperto prima di scoprirlo: nella query delle assenze i parametri seguono il nome del metodo, quindi la **fine** della finestra va per prima. Un test con un'assenza a cavallo dell'inizio della finestra fallirebbe se fossero scambiati.
- **`SlotQuery` aveva un secondo costruttore inutile** (pezzo 3), che accettava una `Map` qualsiasi: il costruttore canonico copiava già tutto in una `TreeMap`. Tolto prima di compilare: due costruttori quasi uguali sono due modi di sbagliare la chiamata.

## Domande da colloquio

### Domanda 1: Che differenza c'è tra un vincolo `unique` e un *exclusion constraint*?
**Risposta modello.** `unique` vieta due righe con valori uguali sulle colonne indicate. Un *exclusion constraint* sceglie l'operatore per ogni colonna, e due righe sono in conflitto quando tutti gli operatori sono veri: con `&&` su un intervallo si vieta che due periodi si accavallino. Usa un indice GiST, con `btree_gist` per le colonne normali confrontate con `=`. È il modo standard in PostgreSQL per impedire due prenotazioni nello stesso orario anche con richieste simultanee.

### Domanda 2: Come aggiungi una colonna obbligatoria a una tabella che ha già dei dati?
**Risposta modello.** Con una migrazione nuova e un default: `add column ... not null default X`, che PostgreSQL scrive nelle righe esistenti. Se il valore giusto cambia da riga a riga: colonna nullable, `update`, poi `set not null`. E sapendo che cambiare un default più avanti non tocca le righe già scritte.

### Domanda 3: Hai mai scritto un validatore Bean Validation personalizzato?
**Risposta modello.** Sì, `@OneOf({30, 60})`: un'annotazione con `@Constraint(validatedBy = OneOfValidator.class)` e gli attributi `message`, `groups`, `payload`, e una classe `ConstraintValidator<OneOf, Integer>`. `initialize` legge i valori ammessi, `isValid` restituisce `true` su `null`, perché è compito di `@NotNull`. L'errore esce sul campo, nello stesso formato degli altri, e il client riceve un 400 invece del 409 che darebbe il `check` del database.

### Domanda 4: Perché salvi gli orari di lavoro come `time` e le assenze come `timestamptz`?
**Risposta modello.** "Il lunedì dalle 9" è una regola da calendario a muro: resta le 9 a Roma d'estate e d'inverno, mentre in UTC cambia. Un'assenza è un fatto unico in un istante preciso: si salva in UTC e si converte solo per mostrarla. Gli orari locali si trasformano in istanti solo nel calcolo, un giorno alla volta, per non sbagliare al cambio dell'ora.

### Domanda 5: In un test, dopo un vincolo violato, la query successiva fallisce con "current transaction is aborted". Perché?
**Risposta modello.** In PostgreSQL un errore dentro una transazione la rende inutilizzabile fino al `rollback`: ogni istruzione successiva viene rifiutata. In un test con `@DataJpaTest`, o comunque `@Transactional`, tutto il test è una sola transazione. Quindi un test deve provare una violazione sola, oppure isolare il tentativo con un *savepoint*.

### Domanda 6: Sostituisci tutti i figli di un'entità nella stessa transazione e il database rifiuta per un vincolo. Perché, e come lo risolvi?
**Risposta modello.** Al *flush* Hibernate raggruppa le istruzioni per tipo e, nel nostro caso con Hibernate 7, manda gli `insert` dei nuovi figli prima di cancellare i vecchi: per un attimo convivono, e un vincolo unico o di esclusione li vede in conflitto. L'ho verificato togliendo il `flush` e guardando l'SQL nel log. La soluzione affidabile è togliere i vecchi, chiamare `flush()` per mandare subito i `delete`, poi aggiungere i nuovi, con un test sul caso peggiore: figli identici ai precedenti.

### Domanda 7: Quando mappi un `@OneToMany`, e quando no?
**Risposta modello.** Quando i figli fanno parte del genitore e non hanno vita propria, come le fasce di un orario: si salvano e si cancellano con lui, e le regole su più figli le controlla il genitore. Allora bidirezionale con `mappedBy`, `cascade = ALL`, `orphanRemoval`, e metodi del genitore per modificare la lista. Non lo mappo quando i "figli" hanno un ciclo di vita proprio, come i pacchetti di un servizio: una collezione che nessuno usa porta solo caricamenti e rischi di N+1.

### Domanda 8: Scrivi un algoritmo che restituisca gli orari liberi di un calendario.
**Risposta modello.** Taglio l'intervallo richiesto sui limiti del server (anticipo minimo, orizzonte), così il lavoro non dipende dal client. Per ogni giorno trovo la versione d'orario valida (`TreeMap.floorEntry`), converto le fasce di quel giorno in istanti nella zona dello studio e, dentro ogni fascia, genero un candidato ogni "scatto" finché la seduta finisce entro la fascia, confrontando istanti. Scarto i candidati troppo vicini ad adesso e quelli che si sovrappongono a un intervallo occupato (semiaperto: `a1 < b2 && b1 < a2`), e ordino. Costo `O(candidati × occupati)`: con numeri piccoli va bene, altrimenti ordinerei le due liste e le scorrerei insieme. È una funzione pura, che riceve anche "adesso", con molti test sui confini e sui cambi dell'ora.

### Domanda 9: Come verifichi che un test protegga davvero una regola?
**Risposta modello.** Cambiandola apposta nel codice e guardando se il test diventa rosso: è l'idea del *mutation testing*, che strumenti come PIT fanno in automatico su tutto il codice. Un test che resta verde con il codice sbagliato non protegge niente. Per i confini (qui "più di 24 ore" contro "almeno 24 ore") serve un caso esattamente sul limite, e l'atteso scritto a mano, non ricalcolato con la stessa logica.

### Domanda 10: Come validi un vincolo tra due parametri della query string in Spring?
**Risposta modello.** Raccolgo i parametri in un `record` senza `@RequestBody`: Spring lo costruisce dalla query string come `@ModelAttribute`, con il suo costruttore. Ci metto `@Valid` e un vincolo di classe che segnala l'errore su uno dei due campi. L'errore diventa la stessa `MethodArgumentNotValidException` dei corpi JSON, quindi lo stesso 400 con lo stesso formato. Non lo lascio arrivare a un costruttore che lancia `IllegalArgumentException`: diventerebbe un 500, cioè un errore del client presentato come un errore del server.
