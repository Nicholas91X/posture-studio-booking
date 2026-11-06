# Passo 06 — Calendario

> **Stato: in corso** (2026-11-06). Pezzo 1 fatto, 141 test verdi.

## Obiettivo
Il professionista imposta i suoi orari di lavoro, anche da una data futura, e le sue assenze. Il cliente vede gli orari liberi di un servizio: dentro le fasce, fuori dalle assenze, con l'anticipo minimo e l'orizzonte, senza errori al cambio dell'ora.

I pezzi (guida 13, 1.2):
1. Migrazione V3, impostazioni nuove in `practitioner`, test dei vincoli — ✅ fatto
2. Modulo `schedule` lato pannello: versioni dello schema settimanale e indisponibilità, API sotto `/api/panel`
3. Il calcolo degli orari liberi come funzione pura, con i test unitari (compresi i cambi dell'ora)
4. L'API pubblica degli orari liberi, con il `Clock`, e la chiusura del passo

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

### Dopo un errore, la transazione di PostgreSQL è finita
In un test `@DataJpaTest` tutte le istruzioni stanno nella stessa transazione. Dopo un vincolo violato PostgreSQL la segna come annullata, e ogni istruzione successiva fallisce con "current transaction is aborted". Per questo ogni test prova **una** sola violazione.

## Decisioni
- D-033: scatto 30 o 60, griglia dall'inizio della fascia, fasce all'ora piena o alla mezz'ora.
- D-034: orari di lavoro in versioni settimanali con data di inizio.
- D-035: calcolo degli orari liberi come funzione pura, API pubblica, orizzonte anticipato nella V3.
- D-036: fasce non sovrapposte anche nel database, con un *exclusion constraint*.
- D-037: "uno tra questi valori" con un vincolo Bean Validation personalizzato.

## Errori fatti e come sono stati corretti
- **Nella guida 13 avevo scritto che "ora piena o mezz'ora" non doveva essere un vincolo del database.** Era sbagliato: vale per ogni riga, sempre, e non dipende dal tempo né da altre tabelle, quindi è un `check` come `slot_step_minutes in (30, 60)`. Corretto nella guida 14, 1.4.
- **Nella prima stesura della guida 14 avevo scritto che Hibernate esegue sempre gli `insert` prima dei `delete`.** Vale per le cancellazioni esplicite. Per i figli tolti con l'*orphan removal* Hibernate in alcuni casi cancella prima. Corretto prima della consegna: nel pezzo 2 la sostituzione di una versione sarà provata da un test.
- **Il perimetro del pezzo 1 era sbagliato nella guida 13**, che lo descriveva come solo SQL. Tre classi di test usavano uno scatto di 15 e si aspettavano 30 come valore iniziale: con V3 sarebbero fallite. Il pezzo ha compreso anche le impostazioni in Java.
- **Un test provava due violazioni di fila** (giorno 0 e giorno 8). La seconda sarebbe fallita per la transazione annullata, non per il vincolo. Diviso in due test prima di eseguirlo.

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
