# La migrazione V16: gli stati del "dopo" e i crediti che tornano — guida di studio

Questa guida accompagna il **pezzo 1 del passo 11**: la migrazione **V16**. Nessuna seduta si cancella ancora (pezzi 2–5): qui si decide **la forma dei dati** di tutto il passo, e quali regole il database fa rispettare da solo.

```
booking          cinque stati nuovi; chi e quando ha cancellato; il confine del preavviso
                 fotografato; chi ha segnato "non presentato"
credit_lot       l'origine CANCELLATION (il credito nuovo da 1)
credit_movement  il motivo CANCELLATION_RESTORE (+1 allo stesso lotto); il "credito nuovo"
                 legato alla sua prenotazione, così "un solo credito per cancellazione" è una regola sola
```

Teoria di riferimento: D-010 e D-076 (lotti, validità minima), D-011 (fotografare), D-015 e D-075 (niente rimborsi, cancellazione dello studio), D-030 (approvazione), D-042 (nei `check` solo i valori prodotti, `drop` + `add`), D-046 (macchina a stati nell'`enum`), D-065 (link di pagamento), D-069 (registro dei crediti, trigger), D-071 (il credito copre il giorno della seduta), D-073 (chi firma una concessione).

---

## Indice

- Parte 1 — Cosa c'è già, e cosa manca
- Parte 2 — Una macchina a stati che cresce senza rompersi
- Parte 3 — Prima scelta: i nomi degli stati
- Parte 4 — Seconda scelta: la richiesta in attesa cancellata dallo studio
- Parte 5 — Terza scelta: chi ha cancellato, e quando
- Parte 6 — Quarta scelta: il confine del preavviso, letto o fotografato
- Parte 7 — Quinta scelta: i crediti che tornano nello schema
- Parte 8 — Sesta scelta: "già allungato", e il giro che una regola permette
- Parte 9 — Settima scelta: svolta e non presentato
- Parte 10 — La migrazione intera (bozza)
- Parte 11 — I test
- Parte 12 — Cosa c'è da decidere
- Domande da colloquio
- Documentazione ufficiale

---

## Parte 1 — Cosa c'è già, e cosa manca

**C'è:**
- `BookingStatus` con i **sette** stati del passo 7, e `canMoveTo`, uno `switch` espressione **senza `default`**: `CONFIRMED` oggi non va da nessuna parte ("final in step 7: cancelling a confirmed booking arrives with step 11", c'è scritto nel codice);
- `booking.cancelled_at` (V4), scritto oggi solo dal ritiro (`WITHDRAWN`);
- `booking_status_ck` con i sette stati, e il vincolo di non sovrapposizione `booking_no_overlap_ex` sui tre stati che occupano l'orario;
- in `practitioner`, dalla V1: `cancellation_notice_hours` (default 24) e `single_credit_validity_days` (default 90), **mai usati finora** dal codice delle prenotazioni;
- `credit_lot.source` con `PACKAGE` e `GRANT`; `credit_movement.reason` con `LOT_CREATED`, `BOOKING`, `BOOKING_RELEASE`, `ADJUSTMENT`. I nomi `CANCELLATION` e `CANCELLATION_RESTORE` sono già stati scelti al passo 10 (D-069) e lasciati fuori dai `check` apposta (D-042);
- l'indice `credit_movement_one_return_per_booking_uq`: **una sola restituzione** (movimento positivo con una prenotazione) per prenotazione.

**Manca:** gli stati del "dopo", chi ha cancellato, il confine del preavviso, i crediti nati da una cancellazione.

---

## Parte 2 — Una macchina a stati che cresce senza rompersi

Il passo 11 aggiunge stati a una macchina che funziona già, e che ha clienti veri sopra. Tre cose devono restare vere: le **frecce esistenti** non cambiano, le **righe esistenti** restano valide, e **nessuno stato nuovo** resta senza frecce decise.

Le frecce nuove (le scelte delle parti 3, 4 e 9 le precisano):

```
                      ┌──────────────► CANCELLED_IN_TIME      (il cliente, in tempo)
                      ├──────────────► CANCELLED_LATE         (il cliente, tardi)
   CONFIRMED ─────────┼──────────────► CANCELLED_BY_STUDIO    (lo studio, quando vuole)
                      └──(finita)────► COMPLETED ◄──────► NO_SHOW   (il professionista corregge)
```

**Il compilatore come rete di sicurezza.** `canMoveTo` è uno `switch` espressione su un `enum`, senza `default`. Da Java 14 (JEP 361) uno `switch` **espressione** deve coprire tutti i valori: aggiungi `COMPLETED` all'`enum` e **il codice non compila** finché qualcuno non scrive le frecce di `COMPLETED`. È il motivo per cui D-046 l'ha voluto così. Con un `default -> false` il nuovo stato compilerebbe in silenzio, senza frecce, e lo scopriresti solo quando un test (se c'è) prova a usarlo. Stessa cosa per gli eventi `sealed` (`BookingEvent`): un evento nuovo, per esempio la cancellazione, fa smettere di compilare lo `switch` di `BookingEmails` finché la sua email non c'è (pezzo 2).

**Le righe esistenti.** Nel database ci sono solo i sette stati vecchi, e il `check` nuovo li contiene tutti: nessuna riga lo viola. Il `check` si cambia con `drop constraint` + `add constraint` **nella stessa migrazione** (D-042). In PostgreSQL anche le istruzioni DDL (`alter table`) stanno in una transazione, e Flyway esegue ogni migrazione in una transazione: tra il `drop` e l'`add` nessun'altra transazione vede la tabella senza vincolo. Se l'`add` fallisse, tornerebbe anche il vincolo vecchio. (In MySQL non è così: il DDL fa commit da solo.)

**Il vincolo di non sovrapposizione non cambia.** Nessuno stato nuovo occupa l'orario: una seduta cancellata libera l'ora (è lo scopo), una svolta o non presentata è nel passato. Il `where` di `booking_no_overlap_ex` e `BookingStatus.HOLDS_TIME` restano i tre stati di oggi. Vale la pena dirlo in un commento, perché è la prima cosa che un lettore si chiede.

**Una conseguenza da non dimenticare**: chi conta le prenotazioni "aperte" (il limite di D-041) usa `HOLDS_TIME` e l'inizio nel futuro: una seduta cancellata smette di contare da sola, e il cliente può prenotarne un'altra. Niente da cambiare.

---

## Parte 3 — Prima scelta: i nomi degli stati

`CANCELLED_IN_TIME`, `CANCELLED_LATE`, `COMPLETED`, `NO_SHOW` sono nel modello dei dati dal passo 3. Resta il nome dello stato della cancellazione dello studio (D-075).

| Nome | Pro | Contro |
|---|---|---|
| **`CANCELLED_BY_STUDIO`** | la parola dei requisiti e di D-075; vale anche se un giorno cancella l'admin (D-077) | — |
| `CANCELLED_BY_PRACTITIONER` | preciso su chi oggi preme il pulsante | falso se cancella l'admin |
| `STUDIO_CANCELLED` | corto | rompe lo schema `CANCELLED_*` degli altri due |

**Raccomandazione: `CANCELLED_BY_STUDIO`.** I tre stati di cancellazione si leggono insieme (`CANCELLED_IN_TIME`, `CANCELLED_LATE`, `CANCELLED_BY_STUDIO`), e il nome dice il fatto che conta per il cliente: non l'ha deciso lui. Ricorda che è un nome del contratto delle API (`BookingStatus` è pubblico): non si cambia più.

---

## Parte 4 — Seconda scelta: la richiesta in attesa cancellata dallo studio

D-075: lo studio può cancellare una seduta confermata **o in attesa**, e per una richiesta in attesa l'effetto è "come un rifiuto". La domanda per lo schema: in che **stato** finisce la richiesta?

**Strada A — `REJECTED`.** La richiesta in attesa cancellata dallo studio **è** un rifiuto: stesso effetto (blocco annullato, o +1 `BOOKING_RELEASE`), stessa email ("la tua richiesta non è stata accettata, non ti è stato addebitato niente / il credito è di nuovo disponibile"), stessa freccia che esiste già. `CANCELLED_BY_STUDIO` arriva solo da `CONFIRMED`.
- Pro: un'azione per un fatto; nessuna freccia nuova da `PENDING_APPROVAL`; e una regola semplice da ricordare e da provare: **"una `CANCELLED_BY_STUDIO` era confermata"**.
- Contro: nelle liste, "rifiutata" e "cancellata dallo studio" non si distinguono per una richiesta. Ma per il cliente sono la stessa cosa: non era ancora una seduta.

**Strada B — `CANCELLED_BY_STUDIO` anche da `PENDING_APPROVAL`.**
- Pro: l'email può dire "ha cancellato lo studio" anche per una richiesta.
- Contro: due stati diversi per lo stesso effetto; uno `switch` sul tipo di pagamento in più (carta → annullare il blocco, invece della restituzione di un incasso che non c'è stato); e la regola della validità minima (D-076) diventerebbe ambigua: vale per un rifiuto chiamato in un altro modo?

**Raccomandazione: A.** L'API della cancellazione dello studio (pezzo 3) può accettare anche una richiesta in attesa ed eseguire il rifiuto, se al pannello serve un pulsante solo: è una scelta del pezzo 3, non dello schema.

Due note:
- `PENDING_PAYMENT` resta fuori: il cliente sta pagando, e il blocco scade da solo entro 32 minuti (D-061). Lo studio non ha niente da cancellare.
- Con A la validità minima non vale per la richiesta in attesa: il credito torna al suo lotto come in ogni rifiuto (D-071), e quel lotto copre il giorno della seduta. Il caso "lotto che scade due giorni dopo il rifiuto" esiste già oggi per ogni rifiuto, ed è stato accettato al passo 10.

---

## Parte 5 — Terza scelta: chi ha cancellato, e quando

**Quando**: `cancelled_at` c'è già, e il ritiro lo usa. I tre stati di cancellazione lo scrivono anche loro, con il `Clock`.

**Chi**, tre strade:

| Strada | Cosa salva | Giudizio |
|---|---|---|
| **A** `cancelled_by bigint → app_user` | l'**account** che ha agito: il cliente, il professionista, domani l'admin | come `granted_by` e `created_by` dei crediti (D-073): la persona vera |
| B `cancelled_by text` (`CUSTOMER`, `PRACTITIONER`) | il ruolo, come `booked_by` | ridondante: lo stato lo dice già (`CANCELLED_BY_STUDIO` contro gli altri due) |
| C niente | — | se l'admin potrà cancellare per un professionista (D-077, punto 1), non si saprebbe chi è stato |

**Raccomandazione: A**, con un `check`: i tre stati di cancellazione hanno sempre `cancelled_at` **e** `cancelled_by`. Il ritiro resta com'è (solo `cancelled_at`): le righe `WITHDRAWN` esistenti non hanno un "chi", e un `check` che lo pretendesse le rifiuterebbe. (Si potrebbe scrivere `cancelled_by` anche nei ritiri nuovi, ma una colonna piena a metà per lo stesso stato è più confusa di una regola chiara.)

Perché A non è ridondante come `booked_by` (D-042 aveva scartato `created_by_user_id`)? Perché lì "chi" si ricavava sempre da `customer_id` o `practitioner_id`; qui, se domani cancella l'admin, no.

**Una nota per il cliente nella cancellazione dello studio?** ("Mi dispiace, sono malato.") Non è nel brief: un testo libero che finisce in un'email al cliente apre domande (lunghezza, cosa ci si scrive) che non servono ora. Si aggiunge con una colonna facoltativa se lo studio lo chiede.

---

## Parte 6 — Quarta scelta: il confine del preavviso, letto o fotografato

La regola: in tempo se il cliente cancella almeno `cancellation_notice_hours` prima dell'inizio. Ma **quale** preavviso: quello di oggi o quello del giorno della prenotazione? Il preavviso è un'impostazione che il professionista **cambia** dal pannello.

L'esempio che decide: Maria prenota il 1° per il 20, con un preavviso di 24 ore. Il 10 il professionista lo porta a 48. Il 18 a mezzogiorno Maria cancella una seduta del 20 alle 10: sono 46 ore prima.
- con il preavviso **letto alla cancellazione**: tardi. Maria perde la seduta per una regola cambiata dopo che aveva prenotato;
- con il preavviso **fotografato alla prenotazione**: in tempo. Maria ha la regola che ha visto.

È lo stesso ragionamento del prezzo (D-011: "cambiare un prezzo non altera lo storico") e della scadenza della risposta (D-030: `approval_deadline` fotografata).

**Strada A — letto ogni volta** dal professionista. Nessuna colonna; ma una modifica dell'impostazione cambia le regole delle sedute già prenotate.

**Strada B — fotografato come istante**: `booking.free_cancellation_until timestamptz`, calcolato alla creazione = `starts_at` − preavviso. La cancellazione confronta `now` con quella colonna, e basta.
- La vista del cliente può dire "cancellabile gratis fino a…" senza calcolare niente (D-005: il frontend non decide).
- Le righe esistenti si riempiono nella migrazione, con il preavviso di oggi del loro professionista (l'unico che c'è: nessuno l'ha mai cambiato prima che contasse). Tre tempi, come la V4 per `approval_window_hours`: colonna, `update`, poi `not null`.

**Raccomandazione: B.** Nota sul nome: "fino a" vuol dire che **quell'istante** è ancora in tempo? Questa è la domanda "≥ o >" del pezzo 2, che va decisa e provata al secondo. Qui fissiamo solo il nome; la guida del pezzo 2 scrive la regola esatta e il commento della colonna la ripete.

Un caso da sapere (D-030, già accettato): una richiesta approvata tardi può arrivare `CONFIRMED` quando `free_cancellation_until` è già passato. Da quel momento il cliente può solo cancellare tardi. La fotografia non lo cambia: lo rende visibile.

---

## Parte 7 — Quinta scelta: i crediti che tornano nello schema

Una cancellazione che restituisce qualcosa (in tempo, o dello studio) ha **tre** esiti possibili:

| Pagata con | Lotto originale | Cosa si scrive |
|---|---|---|
| credito | vale ancora ≥ 90 giorni dal giorno della cancellazione | movimento `CANCELLATION_RESTORE` +1 **sullo stesso lotto** |
| credito | scade prima, e non è "già allungato" (parte 8) | **lotto nuovo** da 1, `source = CANCELLATION` |
| carta | — | **lotto nuovo** da 1, `source = CANCELLATION` |

(Per "in studio" non si scrive niente.)

### 7.1 Il doppio clic, e il buco tra due regole

Il brief chiede: due cancellazioni contemporanee della stessa seduta danno **un solo** credito. Java lo garantisce con il lucchetto sulla prenotazione (D-046): la seconda, dopo l'attesa, trova la seduta già cancellata. Ma il passo 10 ci ha insegnato a mettere una seconda difesa nel database, per il bug che salta il lucchetto.

Oggi c'è `credit_movement_one_return_per_booking_uq`: un solo movimento **positivo con `booking_id`** per prenotazione. Copre il primo caso (`CANCELLATION_RESTORE` ha la prenotazione). **Non copre il lotto nuovo**: il suo movimento `LOT_CREATED` oggi non ha `booking_id` (lo vieta il `check` della forma). Un bug potrebbe creare due lotti nuovi per la stessa cancellazione, o un +1 **e** un lotto nuovo.

**Strada A — una colonna nel lotto**, `credit_lot.origin_booking_id` (la prenotazione da cui nasce), **unica**. Ferma due lotti per la stessa prenotazione. Non ferma "+1 e lotto nuovo": sono due tabelle diverse, e un `check` vede una riga sola.

**Strada B — la prenotazione nel `LOT_CREATED`** del lotto nuovo: il `check` della forma ammette `booking_id` in un `LOT_CREATED` da 1. Allora **l'indice che c'è già** vede anche il lotto nuovo come "la restituzione di quella prenotazione": +1, lotto nuovo, o due lotti nuovi, **uno solo** passa. Una regola sola per un fatto solo ("una cancellazione restituisce al massimo un credito").

**Strada C — tutte e due.** Lo stesso fatto in due posti, che potrebbero non essere d'accordo (l'argomento di `initial_credits`, guida 36, 4.2).

**Raccomandazione: B.** Nessuna colonna nuova in `credit_lot`, nessun indice nuovo. Il lotto nuovo si riconosce dall'origine (`CANCELLATION`), la sua prenotazione dal suo `LOT_CREATED`. Il `check` della forma diventa:

```sql
   (reason = 'LOT_CREATED' and amount > 0 and (booking_id is null or amount = 1))
or (reason = 'BOOKING'              and amount = -1 and booking_id is not null)
or (reason = 'BOOKING_RELEASE'      and amount = 1  and booking_id is not null)
or (reason = 'CANCELLATION_RESTORE' and amount = 1  and booking_id is not null)
or (reason = 'ADJUSTMENT'           and booking_id is null)
```

Quello che il database **non** controlla, e che resta a Java (con un test): che un `LOT_CREATED` con la prenotazione stia proprio in un lotto `CANCELLATION`, e che un lotto `CANCELLATION` abbia il suo `LOT_CREATED` con la prenotazione. Sono regole tra due tabelle; un trigger potrebbe farlo, ma per un metodo solo che crea quei lotti (D-069 aveva scelto lo stesso per "il lotto è dello stesso cliente della prenotazione").

### 7.2 Una trappola, se si scegliesse A

Il trigger `credit_lot_only_extend` (V15) permette un `update` del lotto solo se **tutte le altre colonne** restano uguali, e le elenca **una per una**. Una colonna nuova in `credit_lot` (come `origin_booking_id`) non sarebbe nell'elenco: si potrebbe cambiare con un `update` a mano, in silenzio, e il trigger non direbbe niente. Aggiungere una colonna a una tabella difesa da un trigger così vuol dire **riscrivere il trigger** (`create or replace function`) nella stessa migrazione, e provarlo con un test. Con B il problema non c'è; ma è una cosa da ricordare per ogni migrazione futura su `credit_lot` (lo scrivo anche nel commento della V16).

---

## Parte 8 — Sesta scelta: "già allungato", e il giro che una regola permette

D-076: un credito restituito vale almeno 90 giorni dal giorno della cancellazione, **una volta sola per credito**: "un credito che viene da un lotto già allungato torna al suo lotto senza nuovo allungamento". Il brief chiede di decidere **come si riconosce** un lotto già allungato.

### 8.1 Come si cerca un giro

Una regola che regala qualcosa (qui: giorni di validità) va guardata come la guarderebbe un cliente furbo: **c'è una sequenza di azioni permesse che si può ripetere, e che ogni volta regala un po' di più?** Il metodo: si parte da uno stato, si applicano solo azioni permesse, e si controlla se si torna a uno stato **uguale a quello di partenza, ma con più di prima**. Se sì, il giro si ripete all'infinito.

Le azioni del cliente qui: prenotare con un credito, cancellare in tempo. Proviamo.

**Giro 1, dal pacchetto** (quello del brief). Pacchetto valido fino al giorno 30. Giorno 25: prenota per il 29, cancella il 26 in tempo. Il lotto scade prima di 26 + 90: nasce un lotto nuovo fino al giorno 116. Giorno 110: prenota per il 115, cancella il 111. Se il lotto nuovo potesse allungarsi di nuovo: fino al 201. E così via. **Chiuso** dalla regola "una volta sola": il lotto nuovo è già allungato.

**Giro 2, dalla carta.** Seduta pagata con carta, cancellata in tempo il giorno 0: lotto nuovo da 1 fino al giorno 90. Giorno 85: prenota per l'88 con quel credito, cancella l'86 in tempo. Il lotto scade il 90, prima di 86 + 90 = 176. **Se "già allungato" volesse dire solo "nato dall'allungamento di un credito"**, questo lotto (nato dalla carta) non lo sarebbe: nascerebbe un lotto nuovo fino al 176, poi uno fino al 262… **Il giro è aperto**, e la regola del brief non lo vede, perché guarda solo uno dei due modi in cui nasce un credito da una cancellazione.

**Giro 3, dall'omaggio.** Un lotto `GRANT` che scade presto: il primo credito cancellato passa in un lotto `CANCELLATION`, che poi non si allunga più. Chiuso, come il giro 1.

**Giro 4, con la proroga del professionista.** Il professionista può prorogare a mano anche un lotto `CANCELLATION`: è una concessione, con la sua traccia (D-067). Non è un giro del cliente.

**Giro 5, il pacchetto da 10.** Ogni credito di un pacchetto può allungarsi una volta: dieci allungamenti al massimo. Finito, non infinito: va bene.

### 8.2 Le strade

**Strada A — "già allungato" = ogni lotto con `source = CANCELLATION`.** Un credito che torna a un lotto nato da una cancellazione (dalla carta o dall'allungamento) torna lì, +1, senza allungarsi. Chiude i giri 1 e 2. Nessuna colonna: l'origine c'è già.
- In parole per lo studio: *"un credito si allunga al massimo una volta: quando nasce da una cancellazione"*. Il credito della carta riceve i suoi 90 giorni alla nascita; se il cliente lo usa e lo cancella di nuovo, torna con la scadenza che aveva.

**Strada B — due origini**, `CANCELLATION` (carta) e un'altra per l'allungamento (per esempio `CANCELLATION_RENEWAL`), e solo la seconda conta come "già allungato". È la lettura letterale del brief, e lascia aperto il giro 2.

**Strada C — una colonna nel lotto** ("allungato: sì/no"), o il lotto da cui viene il credito (`origin_lot_id`), seguendo la catena. Più dati per la stessa risposta di A.

**Raccomandazione: A.** Chiude tutti i giri, non aggiunge niente allo schema, e la regola si dice in una frase.

### 8.3 Una domanda in più: e se cancella lo studio?

Con A, una seduta pagata con un credito di un lotto `CANCELLATION`, cancellata **dallo studio**, torna al suo lotto anche se scade dopodomani. Il cliente perde il credito per una decisione **non sua**, ed è proprio quello che D-075 voleva evitare ("il cliente non perde niente").

Il giro però ha bisogno che sia **il cliente** a cancellare: lo studio che cancella non è un'azione che il cliente controlla, quindi non può essere un passo di un giro. Proposta: **la cancellazione dello studio garantisce sempre la validità minima**, anche a un credito già allungato (nasce un altro lotto `CANCELLATION`, che a sua volta non si allunga più per le cancellazioni del cliente). Il limite "una volta sola" resta per le cancellazioni del cliente, che sono quelle dove il giro esiste.

**Questa proposta cambia il testo del brief** ("una sola volta per credito", senza eccezioni): decide Nicholas. Se la risposta è no, la regola resta quella del brief, ed è più semplice da provare.

### 8.4 Il giorno da cui si conta

"Almeno 90 giorni dal giorno della cancellazione": `ValidUntil.daysAfter(istanteDellaCancellazione, single_credit_validity_days)`, la regola unica di D-069 (giorno di Roma + N). E il confronto "il lotto scade prima?" è tra due `LocalDate`: `lotto.valid_until` prima di quella data. Il valore di `single_credit_validity_days` si legge al momento della cancellazione (è la validità del credito **nuovo**, non una regola che il cliente ha accettato prenotando; diversamente dal preavviso della parte 6). Da confermare nella guida del pezzo 2.

---

## Parte 9 — Settima scelta: svolta e non presentato

Il pezzo 5 porta un lavoro programmato (`CONFIRMED` finita → `COMPLETED`) e il professionista che segna `NO_SHOW`, e può correggere, entro un tempo da decidere (nel pezzo 5). Per lo schema servono le frecce e la traccia.

**Frecce**: `CONFIRMED` → `COMPLETED` (il lavoro), `CONFIRMED` → `NO_SHOW` (il professionista, se segna prima che passi il lavoro: il lavoro gira ogni minuto, ma la regola non deve dipendere dalla gara), `COMPLETED` → `NO_SHOW` e `NO_SHOW` → `COMPLETED` (la correzione). La finestra di tempo è una regola Java, non una freccia.

**Traccia**, due strade:
- **A — due colonne**, `attendance_marked_by` (→ `app_user`) e `attendance_marked_at`: chi e quando ha segnato l'ultima volta. `null` per una `COMPLETED` del lavoro. Un `check`: `NO_SHOW` le ha sempre.
- **B — una tabella della storia** (`booking_attendance_mark`, a sola aggiunta): ogni segno e ogni correzione. D-042 aveva scartato una tabella della storia "finché nessun requisito la chiede".

**Raccomandazione: A.** "Non presentato" non ha effetti sui crediti (brief): una correzione sbagliata non costa niente a nessuno, e la tabella si può aggiungere dopo senza toccare queste colonne.

---

## Parte 10 — La migrazione intera (bozza)

Con le raccomandazioni di tutte le parti. È una **bozza da discutere**, non il file finale.

```sql
-- V16__cancellations_and_attendance.sql

-- ***** 1. BOOKING: the states after the confirmation *****

alter table booking drop constraint booking_status_ck;
alter table booking add constraint booking_status_ck check (status in (
    'PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED',
    'EXPIRED', 'REJECTED', 'APPROVAL_EXPIRED', 'WITHDRAWN',
    'CANCELLED_IN_TIME', 'CANCELLED_LATE', 'CANCELLED_BY_STUDIO',
    'COMPLETED', 'NO_SHOW'));
-- booking_no_overlap_ex does not change: no new state holds the time

-- who cancelled: the account that acted (customer, practitioner, admin), as granted_by (D-073)
alter table booking
    add column cancelled_by          bigint,
    add column attendance_marked_by  bigint,
    add column attendance_marked_at  timestamptz,
    add constraint booking_cancelled_by_fk         foreign key (cancelled_by) references app_user (id),
    add constraint booking_attendance_marked_by_fk foreign key (attendance_marked_by) references app_user (id),
    add constraint booking_cancellation_trace_ck check (
        status not in ('CANCELLED_IN_TIME', 'CANCELLED_LATE', 'CANCELLED_BY_STUDIO')
        or (cancelled_at is not null and cancelled_by is not null)),
    add constraint booking_no_show_trace_ck check (
        status <> 'NO_SHOW'
        or (attendance_marked_by is not null and attendance_marked_at is not null));

-- the notice photographed at booking time (part 6), in three steps like V4
alter table booking add column free_cancellation_until timestamptz;
update booking b
   set free_cancellation_until = b.starts_at - make_interval(hours => p.cancellation_notice_hours)
  from practitioner p
 where p.id = b.practitioner_id;
alter table booking alter column free_cancellation_until set not null;


-- ***** 2. CREDITS: the lot born from a cancellation, the credit that comes back *****

alter table credit_lot drop constraint credit_lot_source_ck;
alter table credit_lot add constraint credit_lot_source_ck
    check (source in ('PACKAGE', 'GRANT', 'CANCELLATION'));

alter table credit_movement drop constraint credit_movement_reason_ck;
alter table credit_movement add constraint credit_movement_reason_ck
    check (reason in ('LOT_CREATED', 'BOOKING', 'BOOKING_RELEASE', 'ADJUSTMENT', 'CANCELLATION_RESTORE'));

-- a CANCELLATION lot is born with the booking it comes from: the index "one return per booking" then
-- sees +1 on the same lot and a new lot as the same thing (part 7.1)
alter table credit_movement drop constraint credit_movement_shape_ck;
alter table credit_movement add constraint credit_movement_shape_ck check (
       (reason = 'LOT_CREATED'          and amount > 0  and (booking_id is null or amount = 1))
    or (reason = 'BOOKING'              and amount = -1 and booking_id is not null)
    or (reason = 'BOOKING_RELEASE'      and amount = 1  and booking_id is not null)
    or (reason = 'CANCELLATION_RESTORE' and amount = 1  and booking_id is not null)
    or (reason = 'ADJUSTMENT'           and booking_id is null));

-- REMINDER for every future migration on credit_lot: credit_lot_only_extend lists the columns that must
-- not change one by one; a new column must be added there too (part 7.2)
```

Domande per te leggendola:
- perché il `drop` del `check` di stato non lascia mai la tabella senza vincolo? (parte 2);
- perché l'`update` delle righe esistenti viene **prima** del `not null`? E cosa succederebbe con un `default` invece dell'`update`? (Un `default` è un valore uguale per tutte; qui ogni riga ha il suo.);
- quale indice, già esistente, impedisce un secondo credito per la stessa cancellazione, e perché funziona anche per il lotto nuovo? (parte 7.1).

---

## Parte 11 — I test

Come al passo 10, ogni regola dello schema ha il suo test, e il test si prova **togliendo la regola** (la difesa tolta a turno: il test deve fallire).

1. **Stati**: una riga con ognuno dei cinque stati nuovi passa (con la sua traccia); uno stato inventato è rifiutato.
2. **Traccia della cancellazione**: `CANCELLED_BY_STUDIO` senza `cancelled_by` rifiutata; un `WITHDRAWN` esistente senza `cancelled_by` resta valido.
3. **Traccia del "non presentato"**: `NO_SHOW` senza chi e quando rifiutato; `COMPLETED` senza va bene.
4. **Un solo credito per cancellazione**: per la stessa prenotazione, `CANCELLATION_RESTORE` poi un `LOT_CREATED` con la prenotazione → il secondo è rifiutato dall'indice; due `LOT_CREATED` con la stessa prenotazione → idem.
5. **Forma**: un `LOT_CREATED` con la prenotazione e importo 2 rifiutato; `CANCELLATION_RESTORE` senza prenotazione rifiutato.
6. **Il vincolo di non sovrapposizione**: una `CANCELLED_*` sovrapposta a una nuova `CONFIRMED` sullo stesso orario passa (l'orario si è liberato).
7. **Il riempimento delle righe esistenti**: è la parte nuova. I test di oggi partono da un database vuoto, migrato fino all'ultima versione, quindi l'`update` della V16 non tocca mai una riga. Per provarlo serve una tecnica che nel progetto non c'è ancora: **Flyway fino alla V15** (`target`), una prenotazione scritta con SQL, **poi la V16**, e il controllo che `free_cancellation_until` sia `starts_at` meno il preavviso del suo professionista. Proposta: un test solo, in un database suo (un container a parte, o uno schema a parte), perché gli altri test vogliono il database all'ultima versione.

In Java (anche se la migrazione non lo richiede, lo cambia lo stesso commit):
8. `BookingStatus`: i cinque valori, le frecce della parte 2; un test per ogni freccia permessa e un test che le vecchie frecce **non** sono cambiate (lo stesso test di oggi, che deve restare verde senza modifiche).
9. `LotSource.CANCELLATION`, `MovementReason.CANCELLATION_RESTORE`: compaiono nella storia del cliente (D-074) senza rompere le viste.

---

## Parte 12 — Cosa c'è da decidere

| # | Scelta | Raccomandazione |
|---|---|---|
| 1 | Nome dello stato della cancellazione dello studio | `CANCELLED_BY_STUDIO` (parte 3) |
| 2 | Richiesta in attesa cancellata dallo studio | `REJECTED`, la freccia che c'è già; `CANCELLED_BY_STUDIO` solo da `CONFIRMED` (parte 4) |
| 3 | Chi ha cancellato | `cancelled_by` → `app_user`, preteso dai tre stati di cancellazione (parte 5) |
| 4 | Il preavviso | fotografato: `free_cancellation_until`, riempito per le righe esistenti (parte 6) |
| 5 | Un solo credito per cancellazione nel database | la prenotazione nel `LOT_CREATED` del lotto nuovo, l'indice che c'è già (parte 7) |
| 6 | "Già allungato" | ogni lotto `CANCELLATION`, carta compresa: chiude il giro della carta (parte 8.2) |
| 7 | Cancellazione dello studio e credito già allungato | **domanda per te**: validità minima sempre garantita quando cancella lo studio? Raccomandazione sì; cambia il testo del brief (parte 8.3) |
| 8 | Svolta e non presentato | due colonne per l'ultimo segno; nessuna tabella della storia (parte 9) |
| 9 | Prova del riempimento | un test con Flyway fino alla V15, una riga, poi la V16 (parte 11, punto 7) |

Fuori da questa guida, già registrato per i pezzi dopo: la regola "≥ o >" del preavviso (pezzo 2), il lucchetto tra disattivazione e nuove prenotazioni e l'admin che cancella per un professionista (pezzo 4, D-077), la finestra per correggere un "non presentato" (pezzo 5).

**Una cosa che il brief non nomina e che D-067 aveva promesso al passo 11**: il credito **concesso** dal professionista su una cancellazione **tardiva**. D-073 diceva: "userà lo stesso omaggio, legato alla prenotazione". Due strade: l'omaggio di oggi (`GRANT`), senza legame con la prenotazione, con la nota che dice quale seduta; oppure un omaggio legato alla prenotazione (una colonna in più, e quindi la trappola della parte 7.2). Raccomandazione: **l'omaggio di oggi, senza schema nuovo**; il legame non serve a nessuna regola, e la nota è obbligatoria. Da confermare.

**Scelte di Nicholas** (2026-11-12): "segui le raccomandazioni", per tutte e nove e per le quattro domande aperte (il giro della carta, la validità minima garantita quando cancella lo studio, prima la correzione poi il rimborso, l'omaggio di oggi per la cancellazione tardiva). Registrate in D-075, D-076 e D-078.

---

## Parte 13 — Com'è andata (implementazione)

**File**: `V16__cancellations_and_attendance.sql`; in Java `BookingStatus` (cinque valori, le frecce), `Booking` (`freeCancellationUntil`, fotografato da `photographNotice` in tutte e tre le nascite), `BookingService` (passa il preavviso del professionista, anche dal pannello, che prima non leggeva le impostazioni), `LotSource.CANCELLATION`, `MovementReason.CANCELLATION_RESTORE`, `CreditEntry.Kind` e lo `switch` di `CreditViews`. Le fabbriche dei movimenti e dei lotti di cancellazione arrivano con il pezzo 2, che le usa.

**Differenze dalla bozza della parte 10**, tutte difese in più:
- `booking_free_cancellation_ck`: il preavviso fotografato è sempre **prima** dell'inizio. Il preavviso minimo è un'ora (V1), quindi è sempre vero, salvo un bug o un `update` a mano;
- `booking_cancellation_trace_ck` scritto come uguaglianza: `cancelled_by` c'è **se e solo se** lo stato è una cancellazione (la bozza diceva solo "se"). Così un `cancelled_by` su una seduta confermata è un errore;
- `booking_attendance_trace_ck`: chi e quando insieme, e un segno solo su `COMPLETED` o `NO_SHOW` (una seduta futura non si segna).

**Il compilatore ha fatto il suo lavoro.** Aggiunto `CANCELLATION_RESTORE` all'`enum`, lo `switch` sulle note della storia (`CreditViews`) non compilava più finché non ha avuto il suo `case`. Aggiunti i cinque stati, `canMoveTo` idem (parte 2).

**Un dettaglio Java trovato nei test.** `BookingStatusTest` descrive il diagramma con una `Map`: con dodici stati `Map.of(...)` non compila più, perché ha versioni solo fino a **10 coppie**. Oltre si usa `Map.ofEntries(Map.entry(k, v), ...)`. Il test ora controlla 144 coppie (12 × 12) e 13 frecce, e le frecce del passo 7 sono rimaste quelle.

**I dati dei test.** Sette `insert` SQL di prenotazioni (in `BookingTestData`, `CreditTestData`, `PaymentTestData`, `BookingSchemaTest`) non dicevano `free_cancellation_until` e il `not null` li rifiutava: ora lo scrivono come l'applicazione, 24 ore prima dell'inizio. Due test vecchi pretendevano il rifiuto di `CANCELLATION` e `CANCELLATION_RESTORE` ("arrivano con il passo 11"): ora provano valori davvero sconosciuti.

**Un errore trovato dalla suite completa, e un vincolo che ha fatto il suo lavoro.** I test toccati erano tutti verdi, ma la suite completa ha dato 2 errori su 1036, in `PaymentLinkApiTest`: un aiuto del test sposta l'inizio di una seduta con un `update` (per provare "una seduta che inizia tra meno di 32 minuti") e lasciava `free_cancellation_until` al vecchio orario, cioè **dopo** il nuovo inizio. `booking_free_cancellation_ck` l'ha rifiutato. Nell'applicazione non succede (`starts_at` è `updatable = false`); il test ora sposta anche la fotografia. Due lezioni: lanciare **sempre** la suite completa, non solo i test che si pensa di aver toccato; e un vincolo che lega due colonne ferma anche chi ne cambia una sola, compreso chi scrive SQL a mano.

**La tecnica nuova**: `V16BackfillTest`, un container suo senza Spring, Flyway con `target("15")`, due prenotazioni scritte come al passo 10 (professionisti con preavviso 48 e 24), poi `target("16")`: ognuna ha il preavviso del suo professionista, e la colonna è `not null`.

**Provato** togliendo le difese a turno (D-078): sette su sette, ognuna fallita per il motivo giusto (controllato nel registro di Maven, non solo "è fallito qualcosa").

---

## Domande da colloquio

### Domanda 1: Aggiungi uno stato a una macchina a stati che è già in produzione. Cosa può rompersi, e come te ne accorgi?
**Risposta modello.** Tre cose: le transizioni esistenti, le righe esistenti, e lo stato nuovo lasciato senza transizioni. Le transizioni le proteggono i test che c'erano già, che devono restare verdi senza modifiche. Le righe esistenti: il vincolo nuovo deve contenere tutti i valori vecchi, e in PostgreSQL `drop` + `add` del `check` nella stessa migrazione sono atomici. Lo stato senza frecce lo trova il compilatore se le transizioni sono uno `switch` espressione senza `default` sull'`enum`: con un valore nuovo il codice non compila finché qualcuno non decide le sue frecce.

### Domanda 2: Un'impostazione dell'utente cambia una regola (qui il preavviso di cancellazione). La leggi al momento in cui serve, o la salvi quando nasce la cosa a cui si applica?
**Risposta modello.** Dipende da chi ha "accettato" la regola. Se è parte dell'accordo con il cliente, come il prezzo o il preavviso visto prenotando, si fotografa: cambiarla dopo non deve peggiorare la posizione di chi ha già prenotato. Se è un parametro interno che vale per il futuro, come la durata di un credito nuovo, si legge quando serve. La fotografia ha anche un vantaggio pratico: la vista mostra la regola già calcolata, e il client non deve sapere niente.

### Domanda 3: Come impedisci nel database che una cancellazione restituisca due crediti, se il credito può tornare in due modi diversi (un +1 su un lotto o un lotto nuovo)?
**Risposta modello.** Con una regola sola che veda i due modi come la stessa cosa: entrambi scrivono un movimento positivo con l'id della prenotazione (il +1 e il movimento di nascita del lotto nuovo), e un indice unico parziale su `booking_id` dei movimenti positivi ne accetta uno solo. Due vincoli separati, uno per tipo, lascerebbero passare un +1 e un lotto nuovo insieme, perché un `check` o un `unique` vede una tabella sola.

### Domanda 4: Come cerchi un abuso in una regola che regala qualcosa?
**Risposta modello.** Cerco un giro: una sequenza di azioni permesse che riporta allo stato di partenza con qualcosa in più, e che quindi si può ripetere all'infinito. Elenco le azioni che l'utente controlla, parto da ogni modo in cui la cosa regalata può nascere (qui: dalla carta, dal pacchetto, dall'omaggio) e provo a ripetere. Qui la regola "si allunga una volta" chiudeva il giro del pacchetto ma non quello della carta, perché guardava solo uno dei due modi di nascere. Poi guardo le azioni che l'utente non controlla: non possono far parte di un giro, e lì il limite si può allentare.

### Domanda 5: In PostgreSQL una migrazione con `alter table` fallisce a metà. Cosa resta nel database? E in MySQL?
**Risposta modello.** In PostgreSQL il DDL è transazionale: Flyway esegue la migrazione in una transazione, e un errore riporta tutto com'era, vincoli compresi; la migrazione risulta fallita e si può correggere e rilanciare. In MySQL ogni istruzione DDL fa un commit implicito: le istruzioni prima dell'errore restano, e bisogna rimettere a posto a mano prima di rilanciare.

---

## Documentazione ufficiale

- PostgreSQL, `ALTER TABLE` (aggiungere e togliere vincoli): https://www.postgresql.org/docs/17/sql-altertable.html
- PostgreSQL, indici parziali: https://www.postgresql.org/docs/17/indexes-partial.html
- PostgreSQL, DDL transazionale (wiki): https://wiki.postgresql.org/wiki/Transactional_DDL_in_PostgreSQL:_A_Competitive_Analysis
- Flyway, documentazione (cercare l'impostazione `target`): https://documentation.red-gate.com/flyway
- JEP 361, `switch` espressione: https://openjdk.org/jeps/361
