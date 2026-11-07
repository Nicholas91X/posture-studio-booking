# La migrazione V4 e il ciclo tra moduli — guida di studio

Questa guida accompagna il **pezzo 1 del passo 7**. Prepara il terreno a tutto il resto del passo:
- la tabella `booking`, con il vincolo che rende **impossibili** le doppie prenotazioni;
- le tre impostazioni nuove del professionista (limite, approvazione, tempo di risposta);
- la proprietà "valutazione" del servizio;
- il ciclo tra `schedule` e `booking`, risolto con un'interfaccia: da qui in poi gli orari liberi escludono le prenotazioni vere.

Contiene:
- **la parte 1**: il panorama del passo 7, per sapere dove va ogni pezzo;
- **la parte 2**: la tabella `booking`, colonna per colonna;
- **la parte 3**: l'*exclusion constraint* parziale, il concetto più importante del passo;
- **la parte 4**: **prima scelta**: come si registra chi ha creato la prenotazione;
- **la parte 5**: **seconda scelta**: quali valori ammettono i `check` di oggi;
- **la parte 6**: le impostazioni nuove e la validazione incrociata;
- **la parte 7**: il servizio di valutazione (già deciso);
- **la parte 8**: il ciclo tra `schedule` e `booking` (già deciso), e la trappola dei bean;
- **la parte 9**: le scelte piccole;
- **la parte 10**: i test, compreso il primo test di concorrenza;
- **la parte 11**: cosa devi decidere.

Teoria di riferimento: guida 14, parte 2 (l'*exclusion constraint* delle fasce, D-036); guida 08, parte 2 (la *foreign key* composta, D-025); guida 15, parte 3 (vincoli di classe); guida 16, 6.1 (il ciclo); D-013, D-023, D-024, D-030, D-034, D-035.

---

## Indice

1. [Il panorama del passo 7](#parte-1--il-panorama-del-passo-7)
2. [La tabella `booking`](#parte-2--la-tabella-booking)
3. [L'*exclusion constraint* parziale](#parte-3--lexclusion-constraint-parziale)
4. [Prima scelta: chi ha creato la prenotazione](#parte-4--prima-scelta-chi-ha-creato-la-prenotazione)
5. [Seconda scelta: quali valori ammettono i `check`](#parte-5--seconda-scelta-quali-valori-ammettono-i-check)
6. [Le impostazioni nuove e la validazione incrociata](#parte-6--le-impostazioni-nuove-e-la-validazione-incrociata)
7. [Il servizio di valutazione](#parte-7--il-servizio-di-valutazione)
8. [Il ciclo tra `schedule` e `booking`](#parte-8--il-ciclo-tra-schedule-e-booking)
9. [Le scelte piccole](#parte-9--le-scelte-piccole)
10. [I test](#parte-10--i-test)
11. [Cosa devi decidere](#parte-11--cosa-devi-decidere)
12. [Domande da colloquio](#domande-da-colloquio)
13. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Il panorama del passo 7

### 1.1 Gli stati della prenotazione in questo passo

```
                    richiesta del cliente (pezzo 2)
                              │
                              ▼
                       PENDING_PAYMENT ──── blocco scaduto (lavoro, pezzo 4) ───► EXPIRED
                              │
                  pagamento autorizzato (simulato fino al passo 9)
                              │
             ┌────────────────┴────────────────┐
         MANUAL                            AUTOMATIC
             ▼                                 │
      PENDING_APPROVAL ──── approva ──────────►├──► CONFIRMED
         │    │    │
         │    │    └── scadenza (lavoro, pezzo 4) ───► APPROVAL_EXPIRED
         │    └─────── rifiuta ───────────────────────► REJECTED
         └──────────── ritira (cliente) ──────────────► WITHDRAWN
```

Le frecce sono **le uniche** permesse: il pezzo 3 le scrive come una macchina a stati esplicita, e ogni passaggio non previsto viene rifiutato. Le cancellazioni di una prenotazione confermata (`CANCELLED_IN_TIME`, `CANCELLED_LATE`) arrivano al passo 11, `COMPLETED` e `NO_SHOW` dopo.

### 1.2 I pezzi

| # | Pezzo | Cosa produce |
|---|---|---|
| 1 | **Migrazione V4 e ciclo tra moduli** (questa guida) | Schema, impostazioni, servizio di valutazione, orari liberi che escludono le prenotazioni |
| 2 | Richiesta di prenotazione del cliente | I controlli, `PENDING_PAYMENT`, il limite sotto concorrenza, il blocco scaduto |
| 3 | Stati e approvazione | La macchina a stati, incasso e annullamento provvisori, eventi di dominio |
| 4 | Lavori programmati | `EXPIRED` e `APPROVAL_EXPIRED` ogni minuto, con il `Clock` |
| 5 | Prenotazione dal pannello | Solo se lo studio ha risposto al punto aperto 10; altrimenti va al passo 10 |
| 6 | Liste e segnalazioni | Le prenotazioni del professionista e del cliente, le due segnalazioni |

Fuori dal passo: crediti (passo 10), cancellazione di una confermata (passo 11), email e push (passo 8, D-039), Stripe vero (passo 9).

---

## Parte 2 — La tabella `booking`

La progettazione è in `modello-dati.md`. Qui ogni colonna con il suo perché.

| Colonna | Tipo | Vincoli | Perché |
|---|---|---|---|
| `id` | `bigint` | identity, chiave primaria | Come tutte le tabelle |
| `customer_id` | `bigint` | `not null`, → `customer` | Il cliente per cui è la seduta |
| `practitioner_id` | `bigint` | `not null`, → `practitioner` | Serve al vincolo di non sovrapposizione |
| `service_id` | `bigint` | `not null`, → `service`, **e** `(service_id, practitioner_id)` → `service (id, practitioner_id)` | Lo stesso problema di `package_offer`: il servizio deve essere **dello stesso professionista** della prenotazione. Stessa soluzione, D-025: il `unique (id, practitioner_id)` di `service` c'è già |
| `starts_at`, `ends_at` | `timestamptz` | `not null`, `ends_at > starts_at` | Istanti, in UTC. La fine si salva: se il servizio cambia durata, la seduta già prenotata non cambia |
| `status` | `text` | `not null`, `check` sui valori (parte 5) | Lo stato, come `enum` in Java (`@Enumerated(EnumType.STRING)`) |
| `price_cents` | `bigint` | `not null`, `> 0` | Il prezzo **fotografato** (D-011, D-022): cambiare il prezzo del servizio non tocca le prenotazioni fatte |
| `hold_expires_at` | `timestamptz` | obbligatoria se `PENDING_PAYMENT` | Fine del blocco di 30 minuti (D-008) |
| `approval_deadline` | `timestamptz` | obbligatoria se `PENDING_APPROVAL` | Richiesta + `approval_window_hours`, fotografata (D-030) |
| `decided_at` | `timestamptz` | facoltativa | Quando il professionista (o il sistema, in `AUTOMATIC`) ha approvato o rifiutato |
| `cancelled_at` | `timestamptz` | facoltativa | Quando il cliente ha rinunciato: ritiro (`WITHDRAWN`) adesso, cancellazione al passo 11 |
| `version` | `bigint` | `not null default 0` | Per il lucchetto ottimistico di JPA (`@Version`): lo spieghiamo nel pezzo 3, dove serve davvero |
| `created_at` | `timestamptz` | `not null default now()` | È anche il momento della richiesta |

Le colonne `paid_with` (carta o credito) e "chi l'ha creata" dipendono dalle due scelte delle parti 4 e 5.

### 2.1 Due `check` che legano due colonne

Un `check` vede **una riga intera**, quindi può legare lo stato a un'altra colonna:

```sql
-- a pending payment always has its hold, a pending approval always has its deadline
constraint booking_hold_ck      check (status <> 'PENDING_PAYMENT'  or hold_expires_at   is not null),
constraint booking_deadline_ck  check (status <> 'PENDING_APPROVAL' or approval_deadline is not null),
```

Si legge come un'implicazione: "se è `PENDING_PAYMENT`, allora il blocco c'è". In logica `A → B` equivale a `non A oppure B`, ed è la forma che SQL capisce.

Le colonne **non si svuotano** quando lo stato cambia: una prenotazione `CONFIRMED` conserva `hold_expires_at` e `approval_deadline`. Sono storia, non fanno danno, e permettono di sapere a posteriori "quanto tempo aveva il professionista per rispondere".

### 2.2 Perché le colonne di momento, e non una tabella della storia

Due strade per sapere "quando è successo cosa":
- **una colonna per ogni momento che conta** (`decided_at`, `cancelled_at`): quello che c'è nel modello dei dati;
- **una tabella della storia** (`booking_status_change`: da, a, quando, chi), una riga per ogni transizione: è quello che si fa in banca per l'*audit*.

La seconda è più completa, ma è una tabella in più da scrivere a ogni transizione, e nessun requisito la chiede. Le scadenze non hanno bisogno di una colonna: un `EXPIRED` è scaduto a `hold_expires_at`, un `APPROVAL_EXPIRED` ad `approval_deadline`. Propongo le colonne di momento (scelte piccole, parte 9). Se un giorno servirà la storia completa, la si aggiunge senza toccare queste.

### 2.3 Gli indici

```sql
create index booking_practitioner_id_starts_at_ix on booking (practitioner_id, starts_at);
create index booking_customer_id_starts_at_ix     on booking (customer_id, starts_at);
```

- Il primo serve le liste del professionista (pezzo 6) e gli intervalli occupati (parte 8).
- Il secondo serve il limite delle prenotazioni future (pezzo 2) e "le mie prenotazioni" del cliente.

PostgreSQL **non** crea da solo un indice sulle *foreign key*. L'indice del vincolo (parte 3) non basta per le liste: contiene solo le prenotazioni che occupano l'orario, e le liste mostrano anche quelle rifiutate o scadute.

---

## Parte 3 — L'*exclusion constraint* parziale

### 3.1 Il problema: il controllo in Java non basta

Il pezzo 2 controllerà in Java che l'orario sia libero. Ma tra il controllo e l'`insert` passa del tempo, e due richieste possono stare tutte e due in quella finestra:

```
Cliente A                                   Cliente B
"lunedì 9:00 è libero?"  → sì
                                            "lunedì 9:00 è libero?"  → sì
insert lunedì 9:00–10:00 ✓
                                            insert lunedì 9:00–10:00 ✓   ← doppia prenotazione
```

È la *race condition* "controlla, poi agisci" (*check-then-act*). Nessun controllo in Java la chiude da solo, perché ognuna delle due transazioni non vede la riga **non ancora confermata** dell'altra.

### 3.2 Il vincolo

```sql
-- two bookings that hold the time cannot overlap for the same practitioner (D-013).
-- Half-open ranges [start, end): back-to-back sessions (9-10, 10-11) are allowed.
-- Same states as BookingStatus.HOLDS_TIME in Java
constraint booking_no_overlap_ex exclude using gist (
    practitioner_id                 with =,
    tstzrange(starts_at, ends_at)   with &&
) where (status in ('PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED'))
```

Si legge: "non possono esistere due righe con **lo stesso** professionista (`=`) e intervalli che **si sovrappongono** (`&&`), tra le righe che soddisfano il `where`".

Rispetto al vincolo delle fasce della V3 (D-036), due differenze:
- **`tstzrange` è già in PostgreSQL**: un intervallo di `timestamptz`. Per le ore del giorno (`time`) non esisteva, ed è per questo che la V3 ha creato il tipo `timerange`. Il costruttore con due argomenti crea `[inizio, fine)`, la stessa convenzione di `TimeRange` (D-035).
- **Il `where`** rende il vincolo **parziale**: l'indice GiST che lo regge contiene solo le righe negli stati che occupano l'orario. Quando una prenotazione passa a `EXPIRED` o `REJECTED`, esce dall'indice, e l'orario torna prenotabile. Senza il `where`, un orario rifiutato una volta resterebbe occupato per sempre.

`btree_gist` serve per `practitioner_id with =`: l'uguaglianza su un `bigint` dentro un indice GiST. C'è già dalla V3.

### 3.3 Cosa succede con due richieste davvero simultanee

```
A: begin; insert lunedì 9:00–10:00            (non ancora confermata)
B: begin; insert lunedì 9:00–10:00            → ASPETTA: nell'indice c'è una riga in conflitto,
                                                ma la sua transazione non è finita
A: commit
B: errore 23P01 exclusion_violation           → la transazione di B fallisce
```

Se A avesse fatto `rollback`, B sarebbe passato. È lo stesso comportamento di un `unique`: il database **non decide** finché non sa se la prima riga esiste davvero. Per questo il vincolo chiude la finestra che il controllo in Java lascia aperta: in qualunque ordine arrivino, **una sola** delle due righe può esistere.

> **Correzione (2026-11-07, scoperta nel pezzo 3).** Il quadro qui sopra è incompleto. Un `unique` cerca i conflitti **prima** di inserire la riga nell'indice: la seconda transazione vede la prima e aspetta, la prima non vede la seconda, e non c'è attesa circolare. Un *exclusion constraint* invece **inserisce prima** la riga nel suo indice e **poi** cerca i conflitti. Se i due `insert` sono davvero contemporanei, ognuno trova la riga non confermata dell'altro e **aspetta l'altro**: è un *deadlock*. Dopo `deadlock_timeout` (1 secondo di default) PostgreSQL ne annulla uno con l'errore **`40P01` deadlock detected**, e l'altro prosegue. Una riga sola anche in questo caso, ma con un errore diverso da `23P01`, che in Java arriva come `PessimisticLockingFailureException` e non come `DataIntegrityViolationException`. Il test di concorrenza l'ha mostrato dopo averlo passato per caso diverse volte: `passi/07-prenotazione.md`, "Errori", 5.

In Java l'errore arriva come `DataIntegrityViolationException`, come per gli altri vincoli (provato in V3 da `ScheduleSchemaTest`). Come riconoscerlo e trasformarlo in un 409 con il codice `SLOT_NOT_AVAILABLE` è un pezzo del pezzo 2.

### 3.4 Perché è "l'ultima difesa"

Il controllo in Java resta (pezzo 2): dà al cliente un errore chiaro **prima** di provare, e copre il caso normale. Il vincolo copre i casi che il codice non vede: la corsa tra due richieste, un bug, un `insert` a mano. È la stessa divisione di D-025 e D-036, ma qui è **indispensabile**: è l'unico punto in cui la regola più importante del sistema (D-013) è garantita.

### 3.5 La trappola del blocco scaduto

C'è un caso in cui il vincolo dice "occupato" e non dovrebbe:

```
10:00:00  A chiede lunedì 9:00 → PENDING_PAYMENT, blocco fino alle 10:30
10:30:00  il blocco scade. Il lavoro programmato (pezzo 4) gira alle 10:31
10:30:20  B vede lunedì 9:00 tra gli orari liberi e lo chiede
          → per il vincolo la riga di A è ancora PENDING_PAYMENT → violazione
```

Viene spontaneo mettere la scadenza nel `where`:

```sql
where (status in (...) and (status <> 'PENDING_PAYMENT' or hold_expires_at > now()))   -- NON FUNZIONA
```

PostgreSQL lo rifiuta: *functions in index predicate must be marked IMMUTABLE*. `now()` cambia da una transazione all'altra (è `STABLE`, non `IMMUTABLE`). Il motivo di fondo: un indice è una struttura **salvata**. Una riga entra o esce dall'indice solo quando qualcuno la scrive. Con `now()` nel `where` dovrebbe uscirne da sola, al passare del tempo, senza che nessuno la tocchi: impossibile.

Quindi esistono **due idee di "occupato"**, ed è importante tenerle distinte:

| | `PENDING_PAYMENT` con blocco valido | `PENDING_PAYMENT` con blocco scaduto | `PENDING_APPROVAL` | `CONFIRMED` | tutti gli altri |
|---|---|---|---|---|---|
| **Per il vincolo** (database) | occupa | **occupa**, finché qualcuno non la chiude | occupa | occupa | no |
| **Per gli orari liberi** (Java, parte 8) | occupa | **libera** | occupa | occupa | no |

Come si risolve la differenza quando B prenota, nel limite e nel vincolo, è uno dei due problemi del pezzo 2. Qui serve solo sapere che il vincolo **non può** risolverla da solo.

---

## Parte 4 — Prima scelta: chi ha creato la prenotazione

### 4.1 Il problema

D-024 chiede di sapere se una prenotazione l'ha creata il cliente o il professionista dal pannello. A cosa serve davvero?
- **Orizzonte, limite, anticipo, approvazione**: valgono solo quando prenota il cliente. Ma questo si sa **al momento della creazione**, dalla strada da cui arriva la richiesta (API del cliente o del pannello), non da una colonna. E le prenotazioni dal pannello **contano** comunque nel limite.
- **Traccia**: sapere a posteriori chi l'ha inserita, e mostrarlo ("prenotata dallo studio").

Quindi la colonna è una **traccia**, non un ingresso delle regole.

### 4.2 Strada A — Un riferimento all'utente: `created_by_user_id` → `app_user`

- **Pro**: dice **quale persona**, e regge anche un attore nuovo (una segreteria, un admin).
- **Contro**: per sapere "l'ha creata il cliente?" bisogna confrontare con `customer.user_id`, cioè chiedere a un altro modulo. Al passo 7 non c'è ancora un utente autenticato: il valore verrebbe dai sostituti provvisori.
- **Contro**: ridondante oggi. Dal pannello prenota **solo il professionista della prenotazione** (D-024, "a nome di un suo cliente"), e lui è già in `practitioner_id`.

### 4.3 Strada B — Un `enum` di chi ha prenotato: `booked_by` (`CUSTOMER` o `PRACTITIONER`)

```sql
booked_by  text  not null,
constraint booking_booked_by_ck check (booked_by in ('CUSTOMER', 'PRACTITIONER'))
```

- **Pro**: con le regole di oggi non perde informazioni. `CUSTOMER` vuol dire il cliente in `customer_id`, `PRACTITIONER` il professionista in `practitioner_id`.
- **Pro**: leggibile, nessuna dipendenza da altri moduli, nessun confronto.
- **Contro**: se arriva un terzo attore, serve una colonna nuova, con una decisione nuova.

### 4.4 Strada C — Tutte e due

Somma i contro: due colonne da tenere coerenti, per un'informazione che oggi ne richiede una.

### 4.5 Raccomandazione

**B.** Risponde esattamente alla domanda, senza attraversare moduli. L'utente preciso si ricava dalle regole di oggi.

**Quando nasce la colonna**: nella V4, perché il pezzo 5 resta nel passo 7 (punto aperto 10 chiuso, D-040).

Se il pezzo 5 fosse andato al passo 10, la colonna sarebbe nata lì, con `add column booked_by text not null default 'CUSTOMER'`: tutte le righe già esistenti le avrebbe create il cliente, quindi il default le avrebbe descritte **correttamente**. Da PostgreSQL 11 aggiungere una colonna con un default fisso è immediato, anche su una tabella piena: il database non riscrive le righe. È la tecnica che userà `paid_with` per `CREDIT` (parte 5.3).

---

## Parte 5 — Seconda scelta: quali valori ammettono i `check`

### 5.1 Il problema

Il modello dei dati elenca **undici** stati e due modi di pagamento (`CARD`, `CREDIT`, forse `IN_STUDIO`). Il passo 7 ne produce **sette**, e un solo modo di pagamento: la carta (simulata). Il `check` del database e l'`enum` Java vanno tenuti uguali. Ma uguali a cosa?

### 5.2 Strada A — Tutto il modello subito

Undici stati nel `check` e nell'`enum`, `paid_with in ('CARD', 'CREDIT')`.

- **Pro**: una migrazione sola per lo schema della prenotazione. I passi 10 e 11 non toccano più i `check`.
- **Contro**: quattro stati che nessun codice può raggiungere. La macchina a stati del pezzo 3 deve comunque elencarli, perché uno `switch` completo su un `enum` copre tutti i valori: sarebbero stati senza frecce in entrata.
- **Contro**: `CREDIT` resterebbe un valore che nessun codice produce fino al passo 10.

### 5.3 Strada B — Solo quello che il passo 7 produce

- `status`: i sette stati del passo 7 (parte 1.1).
- `paid_with`: nella V4 con **`CARD` e `IN_STUDIO`**, perché il pezzo 5 resta nel passo 7 e produce le prenotazioni pagate in studio (D-040). `CREDIT` si aggiunge al passo 10, insieme ai crediti.
- `booked_by`: nella V4 con `CUSTOMER` e `PRACTITIONER` (parte 4.5).
- I passi 10 e 11 allargano il `check` dello stato con `drop constraint` + `add constraint`, come ha fatto la V3 con lo scatto.

- **Pro**: lo schema dice **cosa può succedere oggi**. Niente stati morti nella macchina a stati, niente valori che nessuno scrive.
- **Pro**: ogni migrazione futura cambia lo schema insieme al codice che usa le novità: lo stesso commit, la stessa revisione.
- **Contro**: tre migrazioni future toccano `booking` invece di una. Ognuna è di poche righe.
- **Contro**: `modello-dati.md` va aggiornato per dire cosa c'è davvero nella V4 (lo chiede comunque il brief).

### 5.4 Raccomandazione

**B.** La regola da ricordare: *il database e l'`enum` contengono quello che il codice sa produrre*. Aggiungere un valore a un `check` è una migrazione banale e sicura (le righe esistenti lo rispettano già); toglierlo, se nel frattempo qualcuno l'ha usato, no.

Con la strada A sarebbe comunque una scelta sensata: l'importante è decidere una regola e tenerla.

---

## Parte 6 — Le impostazioni nuove e la validazione incrociata

### 6.1 Le tre colonne

| Colonna | Tipo | Default | Vincolo | Riferimento |
|---|---|---|---|---|
| `max_future_bookings` | `integer` | 4 | `> 0` | D-023, D-041 |
| `booking_approval` | `text` | `'MANUAL'` | `in ('AUTOMATIC', 'MANUAL')` | D-030 |
| `approval_window_hours` | `integer` | 12 | da 1 a `min_booking_notice_hours` (e forse ≤ 48, parte 9) | D-030; requisiti, proposta 12 ore |

### 6.2 Un `check` su due colonne della stessa riga

Il brief chiede la regola "nel database se possibile". È possibile: un `check` vede tutta la riga.

```sql
constraint practitioner_approval_window_hours_ck
    check (approval_window_hours >= 1 and approval_window_hours <= min_booking_notice_hours)
```

Il motivo della regola (D-030): con una finestra non più lunga dell'anticipo, la risposta arriva sempre **prima** dell'inizio della seduta. Richiesta alle 10:00 di lunedì, anticipo 24 ore, finestra 24: la seduta inizia **dopo** le 10:00 di martedì, la scadenza è alle 10:00 di martedì.

### 6.3 La trappola delle righe esistenti

```sql
alter table practitioner
    add column approval_window_hours integer not null default 12,
    add constraint practitioner_approval_window_hours_ck check (... <= min_booking_notice_hours);
```

Se esiste già un professionista con `min_booking_notice_hours = 6` (la V3 accetta qualsiasi valore positivo), la sua riga riceve 12 e il vincolo la rifiuta: **la migrazione fallisce**, e Flyway si ferma. In locale oggi non succede, ma una migrazione va scritta per il database che potrebbe trovare, non per quello che si spera di trovare. L'ordine giusto:

```sql
-- 1. the column, with the default for the existing rows
alter table practitioner add column approval_window_hours integer not null default 12;

-- 2. existing rows with a notice shorter than the default window: the window cannot exceed the notice
update practitioner set approval_window_hours = least(approval_window_hours, min_booking_notice_hours);

-- 3. only now the constraint, which every row already satisfies
alter table practitioner add constraint practitioner_approval_window_hours_ck check (...);
```

Flyway esegue ogni migrazione in **una transazione** su PostgreSQL: se il passo 3 fallisse, anche i passi 1 e 2 verrebbero annullati. Il database non resta mai a metà.

### 6.4 In Java: tre livelli, come per lo scatto

1. **Richiesta** (400 sul campo): `PractitionerSettings` riceve tre campi nuovi. La regola tra due campi è un **vincolo di classe**, la stessa tecnica di `FromNotAfterTo` (guida 17, D-038): un'annotazione sul `record`, con l'errore segnalato sul campo `approvalWindowHours`. D-037 l'aveva già previsto.
2. **Entità**: `Practitioner.changeSettings` controlla di nuovo (seconda difesa), prima di cambiare qualunque campo.
3. **Database**: il `check` della parte 6.2.

Attenzione a un caso che la validazione deve coprire: il professionista **abbassa l'anticipo** sotto la finestra (anticipo 24 → 6, finestra 12). Il vincolo di classe guarda i due campi **insieme**, quindi lo vede comunque, qualunque dei due sia cambiato.

### 6.5 `booking_approval` in Java

Un `enum` `BookingApproval { AUTOMATIC, MANUAL }` pubblico nel modulo `practitioner`: `booking` lo leggerà dalle impostazioni nel pezzo 3. Lo scatto (D-037) non era un `enum` perché è un **numero** (minuti) che il calcolo usa come tale; l'approvazione invece è proprio una scelta tra nomi.

C'è un costo, già visto in D-037: un valore sbagliato nel JSON (`"MANUALE"`) fa fallire la lettura con un 400 generico, senza il nome del campo. Qui lo accetto: il pannello manda uno dei due valori da un interruttore, e un valore diverso è un bug del frontend, non un errore dell'utente (scelte piccole, parte 9).

---

## Parte 7 — Il servizio di valutazione

**Già deciso** dal brief (punto aperto 12): una **proprietà del servizio**, impostata dal professionista.

```sql
-- an evaluation visit: an overlap with a session of the other practitioner is flagged in the panel,
-- never prevented (one room, requirements "Calendario")
alter table service add column evaluation boolean not null default false;
```

- **Booleano o `enum`?** D-030 ha scelto un `enum` per l'approvazione perché si vedeva un terzo valore possibile. Qui la domanda è davvero sì/no ("è una valutazione?"), e non si vede un terzo tipo di servizio. Se un giorno arrivasse, da booleano a `enum` si passa con una migrazione.
- **Default `false`**: i servizi esistenti sono sedute. Il professionista segna a mano la sua visita di valutazione.
- **In Java**: un campo in `ServiceOffer`, in `ServiceOfferDetails` (`@NotNull Boolean evaluation`, wrapper per lo stesso motivo delle impostazioni) e in `ServiceOfferView`. Non nel catalogo pubblico: al cliente basta il nome del servizio.
- **Non si fotografa** nella prenotazione, al contrario del prezzo. Il prezzo è denaro e storia; "è una valutazione" descrive cos'è il servizio, e se il professionista corregge il flag la correzione deve valere anche per le sedute già prenotate. Il pezzo 6 lo chiederà a `catalog` al momento della segnalazione.

Nello stesso pezzo, una piccola aggiunta promessa da D-038: `ServiceOnSale` riceve anche `priceCents`, il prezzo che il pezzo 2 fotograferà.

---

## Parte 8 — Il ciclo tra `schedule` e `booking`

### 8.1 Il problema (guida 16, 6.1)

- `booking` deve chiedere a `schedule` "questo orario è libero?" (pezzo 2): `booking` → `schedule`.
- `schedule`, per calcolare gli orari liberi, deve sapere quali orari sono prenotati, cioè chiedere a `booking`: `schedule` → `booking`.

Due moduli che dipendono l'uno dall'altro non sono più due moduli: non si possono capire, testare né separare uno senza l'altro.

### 8.2 La soluzione decisa: l'inversione delle dipendenze

`schedule` **dichiara** di cosa ha bisogno, con parole sue; `booking` lo **fornisce**.

```java
// schedule: what the free slot calculation needs to know, in its own words.
// schedule does not know who fills these ranges (bookings, from step 7): it only knows they are taken
@FunctionalInterface
public interface OccupiedTime {

    // ranges of the practitioner that overlap the window and are taken at the instant now
    List<TimeRange> between(long practitionerId, TimeRange window, Instant now);
}
```

```java
// booking: the implementation. Package-private, a Spring bean: schedule never sees this class
@Component
class BookingOccupiedTime implements OccupiedTime {

    private final BookingRepository bookings;
    // ...
}
```

Le dipendenze **nel codice** (gli `import`) vanno in un verso solo: `booking` → `schedule`, perché `booking` importa l'interfaccia di `schedule`. A **runtime** la chiamata va da `schedule` a `booking`, ma `schedule` non lo sa: conosce solo l'interfaccia, e Spring gli inietta l'unico bean che la implementa. È la stessa idea di `CurrentPractitioner` (D-028): chi usa una cosa ne dichiara la forma, chi la sa fare la implementa.

L'altra strada (guida 16, 6.1) era spostare l'API pubblica degli orari liberi in `booking`. Scartata dal brief: `schedule` resterebbe un modulo che calcola orari liberi senza conoscere tutto ciò che li occupa, e ogni nuovo uso del calcolo dovrebbe ricordarsi di aggiungere le prenotazioni.

### 8.3 Cosa cambia in `ScheduleService.freeSlots`

```java
List<TimeRange> busy = new ArrayList<>(absencesIn(window));          // come oggi
busy.addAll(occupiedTime.between(practitionerId, window, now));     // nuovo
```

`FreeSlots` **non cambia**: riceve già una lista generica di intervalli occupati (guida 16, scelta piccola 3). Non sa perché un intervallo è occupato, e quindi l'API pubblica non può farlo trapelare (guida 17, parte 5).

### 8.4 La query, con un *text block*

```java
// the ranges that hold the time for whoever looks at instant now: an expired hold is free (part 3.5)
@Query("""
        select new com.posturestudio.booking.shared.TimeRange(b.startsAt, b.endsAt)
        from Booking b
        where b.practitionerId = :practitionerId
          and b.startsAt < :windowEnd and b.endsAt > :windowStart
          and (b.status in :holding or (b.status = :pending and b.holdExpiresAt > :now))
        """)
List<TimeRange> findOccupied(...);
```

Due cose di Java e JPA da notare:
- il **text block** (`"""`, Java 15): una stringa su più righe, senza `+` e senza `\n`. Le query diventano leggibili come in un editor SQL. L'indentazione comune viene tolta in automatico;
- l'**espressione costruttore** (`select new ...`): JPQL costruisce direttamente il `record` `TimeRange`, senza caricare le entità. Si legge solo ciò che serve.

La condizione di sovrapposizione è la stessa della guida 17, parte 4 (`inizio < fineFinestra e fine > inizioFinestra`).

### 8.5 La trappola dei bean: dove **non** implementare l'interfaccia

Verrebbe naturale far implementare `OccupiedTime` a `BookingService`, il servizio del modulo. Ma nel pezzo 2 `BookingService` dipenderà da `ScheduleService` per chiedere se un orario è libero:

```
ScheduleService ──ha bisogno di──► OccupiedTime = BookingService ──ha bisogno di──► ScheduleService
```

Con l'iniezione dal costruttore, Spring non può creare nessuno dei due per primo: l'avvio si ferma con *The dependencies of some of the beans in the application context form a cycle*. Da Spring Boot 2.6 i riferimenti circolari sono vietati per default, ed è una buona cosa: un ciclo tra bean è quasi sempre un ciclo di responsabilità.

Per questo l'implementazione è una **classe piccola e separata**, `BookingOccupiedTime`, che dipende solo dal repository. Il ciclo tra moduli lo risolve l'interfaccia; quello tra bean lo evita questa separazione.

### 8.6 Un effetto sui test di `schedule`

`ScheduleFreeSlotsTest` e `ScheduleServiceTest` sono `@DataJpaTest`: Spring crea solo i bean che il test importa. Da ora `ScheduleService` ha bisogno di un `OccupiedTime`, ma `BookingOccupiedTime` non è pubblica: un test del package `schedule` non la può nemmeno nominare.

Qui torna utile `@FunctionalInterface`: un'interfaccia con un solo metodo si può implementare con una **lambda**. I test di `schedule` ricevono un bean finto, "niente di occupato":

```java
@TestConfiguration
class NothingOccupied {
    @Bean
    OccupiedTime occupiedTime() {
        return (practitionerId, window, now) -> List.of();
    }
}
```

La prova vera, "le prenotazioni tolgono orari liberi", sta nel package `booking`, che può importare sia `ScheduleService` (pubblico) sia la sua implementazione (parte 10). I test `@SpringBootTest` invece trovano il bean vero da soli.

---

## Parte 9 — Le scelte piccole

Le propongo senza chiederti; dimmi se qualcuna non ti torna.

| # | Scelta | Proposta | Perché |
|---|---|---|---|
| 1 | **Colonne di momento** | `decided_at` e `cancelled_at`, nessuna tabella della storia | Parte 2.2. `cancelled_at` serve già per `WITHDRAWN` e il passo 11 la riusa |
| 2 | **Gli stati che occupano, in Java** | `BookingStatus.HOLDS_TIME`, un `EnumSet` con i tre stati, con un commento che rimanda al `where` del vincolo | Stessa regola in due posti (database e Java), come lo scatto in D-037: ognuno rimanda all'altro |
| 3 | **Tetto assoluto della finestra di approvazione** | `approval_window_hours <= 48`, nel `check` e con `@Max(48)` | D-030 lo proponeva: con un anticipo alzato a 200 ore, la finestra potrebbe superare la durata del blocco sulla carta (circa 7 giorni, D-031), e l'incasso fallirebbe |
| 4 | **`booking_approval` come `enum`** | Sì, `BookingApproval` in `practitioner`, con il 400 generico per un valore sbagliato | Parte 6.5 |
| 5 | **Nome e firma dell'interfaccia** | `OccupiedTime.between(long practitionerId, TimeRange window, Instant now)` | `now` esplicito: `ScheduleService` legge l'orologio **una volta sola**, e "scaduto rispetto a quando" non resta una scelta nascosta dell'implementazione |
| 6 | **`evaluation` nel catalogo pubblico** | No | Esce solo ciò che serve al cliente (guida 12) |
| 7 | **Entità `Booking` già nel pezzo 1** | Sì, con tutte le colonne della V4, `BookingStatus` e il repository; nessun costruttore pubblico finché il pezzo 2 non crea prenotazioni | `BookingOccupiedTime` deve leggere la tabella, e con `ddl-auto=validate` l'entità deve corrispondere allo schema. Nei test le righe si inseriscono con SQL |
| 8 | **Indici** | I due della parte 2.3; nessun indice per i lavori programmati | Le righe in attesa sono poche decine: un indice parziale per i lavori servirebbe solo con volumi che lo studio non avrà |

---

## Parte 10 — I test

### 10.1 Lo schema (`BookingSchemaTest`, SQL puro, come `ScheduleSchemaTest`)

Una violazione per test: dopo un errore PostgreSQL annulla la transazione del test (lo stesso commento di `ScheduleSchemaTest`).

| # | Caso | Atteso |
|---|---|---|
| 1 | Due prenotazioni `CONFIRMED` di Anna, lunedì 9–10 e 9:30–10:30 | `booking_no_overlap_ex` |
| 2 | Anna 9–10 e Anna 10–11 (si toccano) | accettate |
| 3 | Anna 9–10 e Marco 9–10 | accettate: il vincolo è per professionista |
| 4 | Anna 9–10 `REJECTED` (o `EXPIRED`, `WITHDRAWN`, `APPROVAL_EXPIRED`), poi Anna 9–10 `PENDING_PAYMENT` | accettata: gli stati finali non occupano |
| 5 | Anna 9–10 `PENDING_PAYMENT` con blocco **già scaduto**, poi Anna 9–10 | **rifiutata**: documenta la trappola della parte 3.5 |
| 6 | Una `PENDING_PAYMENT` passa a `EXPIRED` con un `update`, poi un'altra sullo stesso orario | accettata: la riga è uscita dall'indice |
| 7 | Servizio di Marco su una prenotazione di Anna | `booking_service_practitioner_fk` |
| 8 | `PENDING_PAYMENT` senza `hold_expires_at`; `PENDING_APPROVAL` senza `approval_deadline` | i due `check` |
| 9 | `ends_at` non dopo `starts_at`; stato sconosciuto (anche `CANCELLED_LATE`, che arriva al passo 11); prezzo 0; `paid_with = 'CREDIT'`; `booked_by` sconosciuto | i `check` relativi |
| 10 | Finestra di approvazione 12 con anticipo 6; finestra 0; finestra 49 | `practitioner_approval_window_hours_ck` |
| 11 | `booking_approval = 'MAYBE'` | `practitioner_booking_approval_ck` |

### 10.2 Il primo test di concorrenza (criterio di "fatto")

Il caso 1 prova il vincolo con due `insert` **in fila**, nella stessa transazione. Il brief chiede di più: due prenotazioni **simultanee**, in due transazioni diverse. È lì che un controllo in Java fallirebbe.

Il problema: `@DataJpaTest` apre **una** transazione per test e alla fine la annulla. Due thread non possono condividerla, e una riga non confermata non la vede nessun altro. Quindi:
- la classe di test gira **fuori** dalla transazione del test (`@Transactional(propagation = NOT_SUPPORTED)`);
- ogni thread apre e conferma la propria transazione (`TransactionTemplate`, o una `Connection` presa dal `DataSource`);
- le righe restano davvero nel database: un `@AfterEach` le cancella, altrimenti finirebbero negli altri test che usano lo stesso container.

Due versioni, complementari:

**A — "Partenza insieme, vince uno solo"**
```
due thread, un CountDownLatch che li fa partire insieme
ognuno: begin; insert Anna lunedì 9–10; commit
atteso: un successo e un'eccezione di violazione; nel database una sola riga
```
Prova il risultato, qualunque sia l'ordine reale.

**B — "La seconda aspetta la prima"**
```
thread A: begin; insert; (non conferma, aspetta un segnale)
thread B: begin; insert           → resta bloccato
il test controlla che B non sia ancora finito, poi fa confermare A
atteso: B finisce con la violazione
```
Mostra il meccanismo della parte 3.3. Ha però un punto debole: "B non è ancora finito" si controlla con un'attesa (es. mezzo secondo), cioè con il tempo. Propongo **A** come test del criterio, e B solo se vuoi vedere il blocco con i tuoi occhi.

### 10.3 Gli orari liberi escludono le prenotazioni (criterio di "fatto")

Nel package `booking`, `@DataJpaTest` con `ScheduleService`, `BookingOccupiedTime` e il `Clock` fisso (venerdì 6 novembre 2026, 10:00 a Roma):

| # | Caso, lunedì 9 novembre | Atteso |
|---|---|---|
| 12 | `CONFIRMED` 9–10 | le 9:00 spariscono |
| 13 | `PENDING_APPROVAL` 9–10 | le 9:00 spariscono |
| 14 | `PENDING_PAYMENT` 9–10 con blocco fino alle 10:30 di oggi | le 9:00 spariscono |
| 15 | `PENDING_PAYMENT` 9–10 con blocco scaduto alle 9:45 di oggi | le 9:00 **restano** |
| 16 | `REJECTED`, `EXPIRED`, `WITHDRAWN`, `APPROVAL_EXPIRED` 9–10 | le 9:00 restano |
| 17 | `CONFIRMED` di Marco 9–10 | gli orari di Anna non cambiano |

### 10.4 Impostazioni e catalogo

| # | Caso | Atteso |
|---|---|---|
| 18 | `PUT` delle impostazioni con finestra 12 e anticipo 6 | 400 sul campo `approvalWindowHours` |
| 19 | Finestra mancante, finestra 0, finestra 49, limite 0 | 400 sul campo |
| 20 | `bookingApproval` mancante | 400 sul campo; `"MAYBE"` dà un 400 generico (parte 6.5) |
| 21 | `Practitioner.changeSettings` con finestra oltre l'anticipo | `IllegalArgumentException`, entità invariata |
| 22 | Servizio creato con `evaluation = true`, letto dal pannello | `evaluation` vale `true`; il catalogo pubblico non ha il campo |
| 23 | `serviceOnSale` | restituisce anche il prezzo |

I test esistenti delle impostazioni e dei servizi vanno aggiornati: i `record` ricevono campi nuovi.

---

## Parte 11 — Cosa devi decidere

> **Decise il 2026-11-06**: `booked_by` (B); nei `check` solo i valori del passo 7, con `paid_with` in `CARD` e `IN_STUDIO` (B); scelte piccole tutte accettate, compreso il tetto di 48 ore; test di concorrenza solo nella versione A (D-042). Già decisi dal brief e registrati: servizio di valutazione (D-043) e ciclo tra moduli (D-044). Lo studio ha risposto: pagamento in studio (D-040, il pezzo 5 resta nel passo 7) e limite di **4** prenotazioni future (D-041). Codice scritto da Claude su richiesta di Nicholas, 321 test verdi (`passi/07-prenotazione.md`). Aggiunto in implementazione: `booking_in_studio_ck`, "in studio" solo per le prenotazioni dal pannello.

**Già deciso dal brief** (te li spiego, non li devi scegliere): il servizio di valutazione come proprietà del servizio (parte 7); il ciclo risolto con un'interfaccia di `schedule` implementata da `booking` (parte 8). Li registro in `decisioni.md` alla chiusura del pezzo, insieme alle tue scelte.

1. **Chi ha creato la prenotazione** (parte 4): A (`created_by_user_id`), B (`booked_by`, `enum`) o C (tutte e due)? Io: **B**.
2. **Valori nei `check`** (parte 5): A (tutto il modello subito) o B (solo quello che il passo 7 produce; `paid_with` al passo 10, `booked_by` con il pezzo 5)? Io: **B**.
3. **Le scelte piccole** (parte 9): vanno bene? In particolare il tetto di 48 ore (scelta 3).
4. **Test di concorrenza** (parte 10.2): solo A, o anche B?

E una domanda per lo studio, che decide il pezzo 5 e quindi anche la V4:
- **punto aperto 10**: lo studio ha confermato il pagamento in studio per le prenotazioni dal pannello?
- **punto aperto 11**: ha confermato 21 giorni e 2 prenotazioni future? (I default della V3 e della V4 li seguono; cambiarli dopo è una migrazione da una riga.)

---

## Domande da colloquio

### Domanda 1: Come impedisci in modo affidabile due prenotazioni sovrapposte?
**Risposta modello.** Con un vincolo nel database, non solo con un controllo in Java. Il controllo "è libero? allora salva" lascia una finestra in cui due transazioni vedono entrambe l'orario libero, perché nessuna vede la riga non confermata dell'altra. In PostgreSQL uso un *exclusion constraint* con GiST: stesso professionista con `=`, intervalli `tstzrange` con `&&`, limitato con un `where` agli stati che occupano l'orario. Il controllo in Java resta, per dare un errore chiaro; il vincolo è la garanzia.

### Domanda 2: Cosa succede se due transazioni inseriscono nello stesso momento due righe che violano il vincolo?
**Risposta modello.** Il database non decide finché non sa se l'altra riga esisterà. Con un indice `unique` la seconda aspetta la prima: se la prima conferma, la seconda fallisce; se annulla, passa. Con un *exclusion constraint* c'è una differenza: PostgreSQL inserisce la riga nell'indice prima di cercare i conflitti, quindi due inserimenti contemporanei possono aspettarsi a vicenda, e il rilevatore di *deadlock* ne annulla uno (`40P01`). Nell'altro caso l'errore è un *exclusion violation* (`23P01`). Una riga sola in entrambi i casi, ma il codice deve riconoscere tutte e due le risposte: in Spring arrivano come `DataIntegrityViolationException` e `PessimisticLockingFailureException`, e le traduco nello stesso 409.

### Domanda 3: Vuoi che una prenotazione con il pagamento scaduto non occupi più l'orario. Perché non metti `now()` nel `where` del vincolo?
**Risposta modello.** Perché il predicato di un indice deve essere `IMMUTABLE`, e `now()` non lo è. Il motivo di fondo è che un indice è salvato: una riga ci entra o ne esce solo quando viene scritta, non al passare del tempo. Quindi distinguo due idee di "occupato": per il vincolo la riga occupa finché qualcuno non ne cambia lo stato (un lavoro programmato, o la richiesta successiva nella sua transazione); per il calcolo degli orari liberi un blocco scaduto è già libero.

### Domanda 4: Due moduli dipendono l'uno dall'altro. Come rompi il ciclo?
**Risposta modello.** Con l'inversione delle dipendenze: il modulo che ha bisogno di un'informazione dichiara un'interfaccia con le sue parole ("gli intervalli occupati in un periodo"), e l'altro la implementa. Le dipendenze nel codice vanno allora in un verso solo; a runtime Spring inietta l'implementazione. Attenzione a non spostare il ciclo tra i bean: se l'implementazione fosse il servizio principale del secondo modulo, che a sua volta usa il primo, Spring non riuscirebbe ad avviarsi. Per questo l'implementazione è una classe piccola che dipende solo dal repository.

### Domanda 5: Devi aggiungere a una tabella con dati una colonna `not null` con un vincolo che dipende da un'altra colonna. Come scrivi la migrazione?
**Risposta modello.** In tre passi: aggiungo la colonna con un default, così le righe esistenti hanno un valore; correggo con un `update` le righe in cui il default violerebbe il vincolo; solo allora aggiungo il vincolo. Se aggiungessi tutto insieme, una sola riga anomala farebbe fallire la migrazione. Su PostgreSQL Flyway esegue la migrazione in una transazione, quindi un errore non lascia lo schema a metà; e da PostgreSQL 11 una colonna con un default fisso si aggiunge senza riscrivere la tabella.

### Domanda 6: Come provi davvero, in un test, che un vincolo regge due richieste simultanee?
**Risposta modello.** Il test non deve girare dentro una sola transazione annullata alla fine, come fanno per default i test di `@DataJpaTest`: due thread non la possono condividere. Quindi tolgo la transazione del test, faccio partire due thread insieme con un `CountDownLatch`, e ognuno inserisce e conferma nella propria transazione. Verifico che uno solo riesca e che nel database ci sia una riga sola. Siccome le righe sono confermate davvero, le cancello dopo il test. Uso PostgreSQL vero con Testcontainers: un database in memoria diverso non avrebbe lo stesso vincolo né lo stesso comportamento.

---

## Documentazione ufficiale

- PostgreSQL, `EXCLUDE` in `CREATE TABLE`: https://www.postgresql.org/docs/17/sql-createtable.html#SQL-CREATETABLE-EXCLUDE
- PostgreSQL, tipi intervallo e vincoli sugli intervalli: https://www.postgresql.org/docs/17/rangetypes.html
- PostgreSQL, indici parziali: https://www.postgresql.org/docs/17/indexes-partial.html
- PostgreSQL, `btree_gist`: https://www.postgresql.org/docs/17/btree-gist.html
- PostgreSQL, codici d'errore (`23P01`): https://www.postgresql.org/docs/17/errcodes-appendix.html
- PostgreSQL, `ALTER TABLE ... ADD COLUMN` con default: https://www.postgresql.org/docs/17/sql-altertable.html
- Spring Framework, dipendenze circolari: https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html
- Spring Data JPA, `@Query` ed espressioni costruttore: https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html
- Java, *text block* (JEP 378): https://openjdk.org/jeps/378
