# La migrazione V3 del calendario — guida di studio

Questa guida accompagna il **pezzo 1 del passo 6**:
- la migrazione V3, con le versioni dello schema settimanale (`working_schedule`), le fasce (`working_hours`) e le indisponibilità (`unavailability`);
- le nuove impostazioni in `practitioner`;
- i test dei vincoli con SQL diretto.

Le scelte di fondo sono già fatte nella guida 13 (D-033, D-034, D-035). Qui restano quelle che riguardano **come** il database le fa rispettare.

Contiene:
- **la parte 1**: cosa contiene V3, e perché il pezzo tocca anche il codice Java delle impostazioni;
- **la parte 2**: **prima scelta**: chi impedisce due fasce sovrapposte, Java o il database? Qui compare per la prima volta l'*exclusion constraint* del passo 7;
- **la parte 3**: **seconda scelta**: come si valida "solo 30 o 60" in un'API;
- **la parte 4**: le domande di progetto di V3, con le risposte;
- **la parte 5**: la sintassi su un esempio **diverso** (gli orari dei corsi di una palestra) e la checklist;
- **la parte 6**: i test.

Teoria di riferimento: guida 08 (vincoli e test con SQL diretto, i tre principi della 5.2); guida 04 (Flyway: una migrazione eseguita non si tocca); guida 13 (scelte del passo 6); `modello-dati.md`, "Quando".

---

## Indice

1. [Cosa contiene V3](#parte-1--cosa-contiene-v3)
2. [Prima scelta: chi impedisce le fasce sovrapposte?](#parte-2--prima-scelta-chi-impedisce-le-fasce-sovrapposte)
3. [Seconda scelta: validare "solo 30 o 60"](#parte-3--seconda-scelta-validare-solo-30-o-60)
4. [Le domande di progetto di V3](#parte-4--le-domande-di-progetto-di-v3)
5. [La sintassi, su un esempio diverso](#parte-5--la-sintassi-su-un-esempio-diverso)
6. [I test](#parte-6--i-test)
7. [Domande da colloquio](#domande-da-colloquio)
8. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Cosa contiene V3

### 1.1 Le modifiche a `practitioner`

V1 è già stata eseguita: le modifiche a `practitioner` si fanno con `alter table` dentro V3, mai riscrivendo V1.

| Colonna | Prima (V1) | Dopo (V3) | Decisione |
|---|---|---|---|
| `slot_step_minutes` | `check (> 0)`, default 30 | `check (in (30, 60))`, default **60** | D-033 |
| `min_booking_notice_hours` | — | `integer not null default 24`, `check (> 0)` | D-030, D-035 |
| `booking_horizon_days` | — | `integer not null default 21`, `check (> 0)` | D-023, anticipata da D-035 |

Due cose da sapere:
- **Aggiungere una colonna `not null` a una tabella che ha già righe** richiede un default: PostgreSQL lo scrive nelle righe esistenti. Senza default, l'`alter` fallirebbe appena trova una riga.
- **Cambiare un default non tocca le righe esistenti**: un professionista già creato con scatto 30 resta a 30. In produzione non ce ne sono ancora, e in locale non importa. Quindi niente `update`.

Prima di eseguire V3 sul tuo database locale, controlla che nessuna riga abbia uno scatto diverso da 30 o 60 (`select slot_step_minutes from practitioner`). Se ce n'è una, il nuovo `check` la rifiuta e la migrazione fallisce. È un errore istruttivo, ma è meglio saperlo prima.

### 1.2 Le tre tabelle nuove

**`working_schedule`**: una versione dello schema settimanale (D-034).

| Colonna | Tipo | Regola |
|---|---|---|
| `id` | `bigint` | identity, chiave primaria |
| `practitioner_id` | `bigint` | obbligatorio, → `practitioner` |
| `valid_from` | `date` | obbligatorio; unico per professionista |
| `created_at` | `timestamptz` | obbligatorio, default `now()` |

**`working_hours`**: le fasce di una versione.

| Colonna | Tipo | Regola |
|---|---|---|
| `id` | `bigint` | identity, chiave primaria |
| `schedule_id` | `bigint` | obbligatorio, → `working_schedule` |
| `day_of_week` | `smallint` | obbligatorio, da 1 (lunedì) a 7 (domenica) |
| `start_time` | `time` | obbligatorio, all'ora piena o alla mezz'ora |
| `end_time` | `time` | obbligatorio, dopo `start_time`, all'ora piena o alla mezz'ora |
| `created_at` | `timestamptz` | obbligatorio, default `now()` |

Più due fasce **sovrapposte nello stesso giorno della stessa versione**: chi lo impedisce è la scelta della parte 2.

**`unavailability`**: le assenze.

| Colonna | Tipo | Regola |
|---|---|---|
| `id` | `bigint` | identity, chiave primaria |
| `practitioner_id` | `bigint` | obbligatorio, → `practitioner` |
| `starts_at` | `timestamptz` | obbligatorio |
| `ends_at` | `timestamptz` | obbligatorio, dopo `starts_at` |
| `reason` | `text` | facoltativo, visibile solo al professionista |
| `created_at` | `timestamptz` | obbligatorio, default `now()` |

### 1.3 Perché il pezzo tocca anche Java

Con V3, alcuni test di oggi **fallirebbero**:
- `PractitionerPanelApiTest`, `PractitionerServiceTest` e `PractitionerTest` impostano uno scatto di **15**, che il nuovo `check` rifiuta;
- gli stessi test leggono il valore iniziale e si aspettano **30**, che diventa 60.

E le due impostazioni nuove devono essere leggibili e modificabili dal pannello. Quindi il pezzo 1 comprende anche:
- `PractitionerSettings` con cinque campi invece di tre (`minBookingNoticeHours`, `bookingHorizonDays`);
- `Practitioner` che li mappa e li protegge, compreso "solo 30 o 60" (parte 3);
- i test aggiornati.

Nella guida 13 avevo messo la validazione dello scatto nel pezzo 2: va anticipata qui, perché è il pezzo 1 a cambiare il vincolo.

### 1.4 Una correzione alla guida 13

Nella guida 13, 3.3, avevo scritto che "fasce solo all'ora piena o alla mezz'ora" è una validazione del pannello, **non** un vincolo del database. Riletta con la regola della guida 08, 1.5 ("un vincolo è per ciò che è vero per ogni riga, sempre"), è sbagliato: è vero per ogni fascia, sempre, e non dipende né dal tempo né da altre tabelle. Quindi può, e deve, essere anche un `check`. È la stessa logica di `slot_step_minutes in (30, 60)`.

---

## Parte 2 — Prima scelta: chi impedisce le fasce sovrapposte?

### 2.1 Il problema

Nella stessa versione, il lunedì 9:00–13:00 e il lunedì 12:00–15:00 sono un errore: per un'ora il calcolo vedrebbe due fasce, e offrirebbe gli stessi orari due volte. Un `check` non può impedirlo: vede **una riga alla volta**, e qui il problema è tra due righe. Un `unique` nemmeno: le due righe sono diverse, si **sovrappongono**.

### 2.2 Strada A — Solo in Java

D-034 dice che una versione si salva **intera**. `ScheduleService` riceve tutte le fasce della settimana e, prima di salvare, controlla che quelle dello stesso giorno non si sovrappongano: è la condizione `a1 < b2 && b1 < a2` della guida 13, 5.4.

- **Pro**: niente SQL nuovo. Il controllo dà un errore chiaro sul campo sbagliato.
- **Contro**: un `insert` a mano o un bug in Java passano. È la debolezza della strada D della guida 08, 2.6.
- **Contro, ma piccolo**: qui il danno di un errore è limitato (orari doppi, non due clienti nello stesso orario), e una versione la scrive un professionista alla volta.

### 2.3 Strada B — Anche nel database, con un *exclusion constraint*

Un ***exclusion constraint*** generalizza `unique`. `unique (a, b)` vuol dire "non possono esistere due righe con `a` **uguale** e `b` **uguale**". Un *exclusion constraint* permette di scegliere l'operatore per ogni colonna. Qui:

> non possono esistere due righe con `schedule_id` **uguale**, `day_of_week` **uguale** e intervallo orario che **si sovrappone**.

In PostgreSQL l'operatore "si sovrappone" è `&&` e lavora sui ***range***, i tipi intervallo. Per gli istanti esiste già `tstzrange`, che useremo al passo 7 per le prenotazioni. Per gli orari del giorno (`time`) un tipo intervallo non esiste: si crea con una riga, `create type timerange as range (subtype = time)`. È l'esempio stesso della documentazione di PostgreSQL.

Due dettagli tecnici:
- il vincolo usa un indice **GiST**, un tipo di indice che sa lavorare con le sovrapposizioni. Per usare anche `=` sui numeri dentro un indice GiST serve l'estensione **`btree_gist`**, la stessa che servirà al passo 7 (D-013);
- il costruttore `timerange(start_time, end_time)` crea di default un intervallo **semiaperto** `[inizio, fine)`. Quindi 9:00–13:00 e 13:00–14:00 **non** si sovrappongono: è la stessa convenzione del calcolo (guida 13, 5.4), senza scrivere niente.

- **Pro**: la regola vale anche con un bug o un `insert` a mano. È la filosofia di D-013 e D-025.
- **Pro**: è la **prova generale** del passo 7, su un caso semplice e con poco in gioco. Al passo 7 lo stesso meccanismo impedirà due prenotazioni nello stesso orario, dove un errore costa soldi e clienti. Meglio averlo già visto funzionare.
- **Contro**: tre cose nuove insieme (estensione, tipo, vincolo) e un errore meno leggibile. Per questo Java controlla comunque prima, come per D-025.
- **Contro**: una trappola di Hibernate da conoscere (2.4).

### 2.4 La trappola: Hibernate inserisce prima di cancellare

Quando si **sostituisce** una versione futura (D-034), Java cancella il vecchio e inserisce il nuovo nella stessa transazione. Hibernate però non manda le istruzioni nell'ordine in cui le hai scritte: al momento del *flush* le raggruppa per tipo, e le **cancellazioni esplicite** (`repository.delete(...)`) vanno **dopo** gli `insert`. Per un attimo vecchio e nuovo convivono:
- se si cancella la vecchia versione e se ne crea una nuova con la stessa data, il `unique (practitioner_id, valid_from)` vede due versioni uguali. Questa trappola esiste **con qualunque strada**;
- se si cancellano le vecchie fasce una per una e si inseriscono le nuove, il vincolo della strada B le vede sovrapposte.

Fanno eccezione i figli tolti da una collezione con *orphan removal*: per quelli Hibernate prevede, in alcuni casi, la cancellazione **prima** degli `insert`. Non conviene fidarsi di un dettaglio interno. Nel pezzo 2 sceglieremo come sostituire una versione, e un test lo proverà.

La soluzione più semplice e leggibile è un `flush()` esplicito dopo la cancellazione, così i `delete` partono prima. Esiste anche un'alternativa lato database, i vincoli `deferrable initially deferred` controllati solo alla fine della transazione. È più nascosta e ha dei limiti (per esempio, un `unique` differito non può essere la destinazione di una foreign key). La sconsiglio.

### 2.5 Confronto

| | A — solo Java | B — anche il database |
|---|---|---|
| Protetto da bug e `insert` a mano | ❌ | ✅ |
| SQL nuovo | nessuno | estensione, tipo `timerange`, vincolo |
| Errore chiaro al professionista | ✅ | ✅ (Java controlla prima) |
| Prepara il passo 7 | no | sì, stesso meccanismo |
| Trappola insert-prima-di-delete | sì (per il `unique`) | sì |

### 2.6 Raccomandazione

**B.** Non perché qui il rischio sia alto, ma perché è il posto giusto per imparare lo strumento che al passo 7 sarà indispensabile. È anche una domanda da colloquio di livello alto: pochi sviluppatori sanno che `unique` è un caso particolare di qualcosa di più generale.

---

## Parte 3 — Seconda scelta: validare "solo 30 o 60"

### 3.1 Il problema

Il database rifiuta 45 con il `check`. Ma una richiesta del pannello con `"slotStepMinutes": 45` deve dare un **400** che nomina il campo, come fanno già le altre impostazioni (guida 11, 4.3). Non deve dare un 409 per un vincolo violato, né un 500. Bean Validation ha `@Positive`, `@Min`, `@Max`, ma **non** un'annotazione "uno tra questi valori" per i numeri.

### 3.2 Strada A — Un'annotazione nostra

Bean Validation permette di scriverne una: un'annotazione (es. `@OneOf({30, 60})`) più una classe che la controlla (`ConstraintValidator`). Si usa come le altre:

```java
// esempio diverso: le taglie ammesse di un ordine di magliette
record ShirtOrder(@NotNull @OneOf({38, 40, 42, 44}) Integer size) {}
```

- **Pro**: l'errore esce sul campo giusto, nello stesso formato degli altri 400. Si riusa ovunque.
- **Pro**: è il modo standard. E al passo 7 servirà una variante "di classe", che confronta due campi (`approval_window_hours` ≤ `min_booking_notice_hours`, D-030): stessa tecnica.
- **Contro**: due classi in più, nel package `shared`.

### 3.3 Strada B — `@Min(30) @Max(60)` più il controllo nell'entità

Bean Validation lascia passare 30…60, e 45 lo ferma l'entità.
- **Contro**: l'entità lancia `IllegalArgumentException`, che oggi diventa un **500**. Dovremmo trasformarla in 400, ma allora ogni errore di programmazione diventerebbe un "errore del client".

### 3.4 Strada C — Un metodo `@AssertTrue` nel `record`

```java
@AssertTrue
boolean isSlotStepAllowed() { return slotStepMinutes == null || slotStepMinutes == 30 || slotStepMinutes == 60; }
```

- **Contro**: l'errore esce sul campo `slotStepAllowed`, che non esiste nel JSON, e un metodo che sembra un getter rischia di finire nella risposta.

### 3.5 Strada D — Un `enum` (`SlotStep.HALF_HOUR`, `SlotStep.HOUR`)

- **Contro**: un valore sbagliato fa fallire la **lettura** del JSON, non la validazione. È il 400 generico senza nome del campo che il commento di `PractitionerSettings` racconta già per i numeri primitivi.

### 3.6 Raccomandazione

**A.** È l'unica che dà l'errore giusto, sul campo giusto, senza trucchi. Ed è un pezzo di Bean Validation che ai colloqui viene chiesto ("hai mai scritto un validatore custom?").

L'entità resta la seconda difesa, come per le altre impostazioni: un helper in `Checks` (es. `requireOneOf`) e un `IllegalArgumentException` se qualcuno la chiama con 45 saltando la validazione.

---

## Parte 4 — Le domande di progetto di V3

Le propongo senza chiederti; dimmi se qualcuna non ti torna.

| Domanda | Risposta |
|---|---|
| Un file V3 o due (uno per `practitioner`, uno per `schedule`)? | **Uno**: è un pezzo solo, che nasce e si prova insieme. Dentro, due sezioni con un commento |
| `on delete cascade` da `working_hours` a `working_schedule`? | **Sì**, ed è la prima volta. La guida 08, 1.6, diceva "niente cascade" perché servizi e pacchetti non si cancellano mai. Qui una versione futura **si cancella** (D-034), e le sue fasce non hanno senso senza di lei. Da `working_schedule` e `unavailability` verso `practitioner`, invece, **nessuna** cascata: i professionisti non si cancellano |
| Chi impedisce di cancellare o modificare una versione già iniziata? | **Java**. Dipende da "oggi", e la guida 08, 1.5, lo dice: se una regola dipende dal tempo, sta in Java |
| `day_of_week`: 1–7 o 0–6? | **1–7**, come `java.time.DayOfWeek.getValue()` (lunedì = 1). Attenzione per il pezzo 2: `@Enumerated(EnumType.ORDINAL)` salverebbe 0–6, sbagliando di uno. Servirà un `AttributeConverter`, come `EmailConverter` |
| Il `check` "ora piena o mezz'ora" come si scrive? | `extract(minute from start_time) in (0, 30) and extract(second from start_time) = 0`, e lo stesso per `end_time`. I secondi vanno controllati: `time` accetta anche `09:00:30` |
| Una fascia che finisce a mezzanotte? | Non serve. Con `check (end_time > start_time)` una fascia non può attraversare la mezzanotte. Lo studio chiude alle 19 |
| Indici? | `working_schedule`: l'indice del `unique (practitioner_id, valid_from)` serve già le ricerche per professionista (la prima colonna è `practitioner_id`). `working_hours`: con la strada B l'indice GiST del vincolo comincia da `schedule_id`, e basta; con la strada A serve un indice su `schedule_id`. `unavailability`: indice su `(practitioner_id, starts_at)`, per la ricerca "le assenze di X in questo periodo" |
| Il vincolo sulle indisponibilità sovrapposte? | **Nessuno**: due assenze sovrapposte non fanno danni (D-035) |
| `reason` con un limite di lunghezza? | Nel database no (`text`), come i nomi di V2. La lunghezza la controlla Bean Validation nel pezzo 2 |
| `create extension` dentro una migrazione si può fare? | Sì: `create extension if not exists btree_gist`. Dalla versione 13 di PostgreSQL `btree_gist` è un'estensione *trusted*, che il proprietario del database può installare senza essere superutente. L'immagine Docker ufficiale di PostgreSQL la contiene già, anche nei test con Testcontainers |
| Hibernate con `validate` si lamenta del tipo `timerange`? | No: le colonne restano `time`, e `timerange` compare solo dentro il vincolo. Le entità non lo vedono |

---

## Parte 5 — La sintassi, su un esempio diverso

Una palestra pubblica gli **orari dei corsi** in versioni con una data di inizio. Ogni versione ha delle lezioni settimanali, e nella stessa sala due lezioni dello stesso giorno non possono sovrapporsi. È lo stesso problema, con un'informazione in più: la sala.

```sql
-- V9__create_timetable_and_lesson.sql  (esempio, non è il nostro progetto)

-- = for numbers inside a GiST index (needed by the exclusion constraint below)
create extension if not exists btree_gist;

-- a range of times of day; the constructor builds [start, end): start included, end excluded
create type timerange as range (subtype = time);

alter table gym
    add column max_booking_days integer not null default 14,
    add constraint gym_max_booking_days_ck check (max_booking_days > 0);

alter table gym
    drop constraint gym_lesson_minutes_ck,
    add constraint gym_lesson_minutes_ck check (lesson_minutes in (45, 90)),
    alter column lesson_minutes set default 45;

create table timetable (
    id          bigint       generated always as identity,
    gym_id      bigint       not null,
    valid_from  date         not null,
    created_at  timestamptz  not null default now(),

    constraint timetable_pk                 primary key (id),
    constraint timetable_gym_fk             foreign key (gym_id) references gym (id),
    constraint timetable_gym_valid_from_uk  unique (gym_id, valid_from)
);

create table lesson (
    id            bigint       generated always as identity,
    timetable_id  bigint       not null,
    room          smallint     not null,
    day_of_week   smallint     not null,
    start_time    time         not null,
    end_time      time         not null,
    created_at    timestamptz  not null default now(),

    constraint lesson_pk              primary key (id),
    -- a lesson does not exist without its timetable
    constraint lesson_timetable_fk    foreign key (timetable_id) references timetable (id) on delete cascade,
    constraint lesson_day_of_week_ck  check (day_of_week between 1 and 7),
    constraint lesson_times_ck        check (end_time > start_time),
    -- two lessons of the same timetable, room and day cannot overlap
    constraint lesson_no_overlap_ex   exclude using gist (
        timetable_id                   with =,
        room                           with =,
        day_of_week                    with =,
        timerange(start_time, end_time) with &&
    )
);
```

Da notare:
- **`alter table` con più azioni separate da virgole**: si applicano tutte insieme, in un colpo solo;
- **il vincolo cambiato si cancella e si ricrea**, anche con lo stesso nome: in PostgreSQL un `check` non si modifica;
- **in un *exclusion constraint* ogni riga è "colonna `with` operatore"**: due righe sono in conflitto quando **tutti** gli operatori sono veri insieme. Con solo `with =` sarebbe un `unique`;
- **la convenzione dei nomi** è quella di V1 e V2, con un suffisso nuovo, `_ex`, per gli *exclusion constraint*.

### Cosa deve contenere la nostra V3

- [ ] Nome del file: `V3__` + descrizione in inglese, `snake_case`
- [ ] `btree_gist` e `timerange` (se strada B), in testa e con un commento
- [ ] `practitioner`: nuovo `check` e nuovo default di `slot_step_minutes`; `min_booking_notice_hours` e `booking_horizon_days`, `not null`, con default e `check (> 0)`
- [ ] `working_schedule` con il `unique (practitioner_id, valid_from)`
- [ ] `working_hours` con: foreign key `on delete cascade`; `day_of_week` tra 1 e 7; fine dopo l'inizio; ora piena o mezz'ora su entrambe; il vincolo di non sovrapposizione (se strada B)
- [ ] `unavailability` con fine dopo l'inizio, e il suo indice
- [ ] Foreign key verso `practitioner` **senza** cascata
- [ ] Un commento sopra ogni vincolo non ovvio, con il rimando alla decisione (D-033, D-034, D-035)

E in Java (1.3):
- [ ] `PractitionerSettings` con cinque campi, ognuno `@NotNull`, i numeri `@Positive`, lo scatto con la validazione della parte 3
- [ ] `Practitioner` con le due colonne nuove e i controlli in `changeSettings`
- [ ] I test esistenti aggiornati: niente più 15, valore iniziale 60

---

## Parte 6 — I test

### 6.1 I principi

Gli stessi della guida 08, 5.2:
1. il **nome del vincolo** nel messaggio dell'eccezione;
2. almeno un **caso positivo** per tabella;
3. **una sola cosa sbagliata** per test.

### 6.2 I casi

**`practitioner`** (in una nuova `PractitionerSchemaTest`, o insieme ai test di `practitioner`)

| # | Caso | Atteso |
|---|---|---|
| 1 | Professionista inserito senza impostazioni | scatto 60, anticipo 24, orizzonte 21 |
| 2 | Scatto 30 | accettato |
| 3 | Scatto 45 | rifiutato, `practitioner_slot_step_minutes_ck` |
| 4 | Anticipo 0 | rifiutato, il suo `check` |
| 5 | Orizzonte 0 | rifiutato, il suo `check` |

**Schema del calendario** (nuova `ScheduleSchemaTest`, nel package `schedule`)

| # | Caso | Atteso |
|---|---|---|
| 6 | Versione valida con due fasce lun 9–13 e 14–19 | accettata |
| 7 | Due versioni dello stesso professionista con la stessa data | rifiutata, il `unique` |
| 8 | Stessa data per due professionisti diversi | accettata |
| 9 | `day_of_week` 0, e poi 8 | rifiutati |
| 10 | Fascia 13:00–09:00 | rifiutata, fine dopo l'inizio |
| 11 | Fascia 09:15–13:00 | rifiutata, ora piena o mezz'ora |
| 12 | Fascia 09:00:30–13:00 | rifiutata (i secondi) |
| 13 | Lun 9–13 e lun 12–15 nella stessa versione | rifiutata, `_ex` (strada B) |
| 14 | Lun 9–13 e lun 13–14 nella stessa versione | **accettata**: si toccano, non si sovrappongono |
| 15 | Lun 9–13 e mar 9–13 | accettata |
| 16 | Lun 9–13 in due versioni diverse | accettata: è proprio lo scopo delle versioni |
| 17 | Cancellare una versione | anche le sue fasce spariscono (cascata) |
| 18 | Indisponibilità valida | accettata |
| 19 | Indisponibilità con fine uguale all'inizio | rifiutata |
| 20 | Due indisponibilità sovrapposte | accettate |

I casi 14 e 16 sono i più importanti tra quelli positivi. Se il vincolo fosse scritto male, per esempio senza `schedule_id` o con un intervallo chiuso, un test negativo continuerebbe a passare, e solo questi due lo scoprirebbero.

**Java** (aggiornamenti)

| # | Caso | Atteso |
|---|---|---|
| 21 | `PUT /api/panel/settings` con scatto 45 | 400, campo `slotStepMinutes` |
| 22 | `PUT` con le cinque impostazioni valide | 200, e rilette uguali |
| 23 | `changeSettings` dell'entità con scatto 45 | `IllegalArgumentException`, entità invariata |
| 24 | L'annotazione della parte 3 su un `record` di prova | 30 e 60 passano, 45 no, `null` passa (lo ferma `@NotNull`) |

Il caso 24 ricorda una convenzione di Bean Validation: un validatore **non** rifiuta `null`, perché quello è compito di `@NotNull`. Così le due annotazioni restano indipendenti.

---

## Cosa devi decidere

> **Decise il 2026-11-06**: strada B, *exclusion constraint* (D-036); annotazione `@OneOf` (D-037); domande di progetto tutte accettate; il codice lo scrive Claude su richiesta di Nicholas.

1. **Fasce sovrapposte** (parte 2): A (solo Java) o B (anche il database, con l'*exclusion constraint*)? Io: **B**.
2. **Validazione di "30 o 60"** (parte 3): A (annotazione nostra), B, C o D? Io: **A**.
3. **Le domande di progetto** (parte 4): ok su tutte?
4. **Chi scrive il codice** di questo pezzo: tu, con la checklist della parte 5, oppure io su tua richiesta?

---

## Domande da colloquio

### Domanda 1: Che differenza c'è tra un vincolo `unique` e un *exclusion constraint*?
**Risposta modello.** `unique` vieta due righe con valori **uguali** sulle colonne indicate. Un *exclusion constraint* generalizza: per ogni colonna si sceglie l'operatore, e due righe sono in conflitto quando tutti gli operatori sono veri. Con `&&` (sovrapposizione) su un intervallo si vieta che due periodi si accavallino: è il modo standard in PostgreSQL per impedire due prenotazioni nello stesso orario. Usa un indice GiST; per combinare `=` su colonne normali serve l'estensione `btree_gist`.

### Domanda 2: Come aggiungi una colonna obbligatoria a una tabella che ha già dei dati?
**Risposta modello.** Con un default: `add column ... not null default X`. PostgreSQL scrive il default nelle righe esistenti (dalla versione 11 senza riscrivere la tabella, se il default non cambia da riga a riga, come un numero fisso). Senza default, l'`alter` fallisce perché le righe esistenti violerebbero il `not null`. Se il valore giusto non è uguale per tutte le righe, si fa in tre tempi: colonna nullable, `update`, poi `set not null`. E sempre con una migrazione nuova, mai cambiando quella già eseguita.

### Domanda 3: Quando usi `on delete cascade` e quando no?
**Risposta modello.** Quando la riga figlia non ha senso senza la madre ed è normale cancellare la madre: le righe di un ordine, le fasce di una versione d'orario. Non quando la madre non si cancella mai o i figli sono storia da conservare, come i servizi di un professionista o i pagamenti di una prenotazione. Lì una foreign key senza cascata è una protezione: il database rifiuta la cancellazione.

### Domanda 4: Sostituisci i figli di un'entità nella stessa transazione e ottieni una violazione di un vincolo unico. Perché?
**Risposta modello.** Perché Hibernate, al *flush*, raggruppa le istruzioni per tipo, ed esegue le cancellazioni esplicite **dopo** gli `insert`, indipendentemente dall'ordine in cui hai chiamato i metodi. Per un attimo vecchi e nuovi figli convivono, e il vincolo li vede in conflitto. Si risolve con un `flush()` esplicito dopo la cancellazione, oppure con vincoli `deferrable initially deferred`, controllati solo al commit, con i loro limiti. Con l'*orphan removal* Hibernate in alcuni casi anticipa le cancellazioni, ma è un dettaglio da verificare con un test, non da dare per scontato.

### Domanda 5: Hai mai scritto un validatore Bean Validation personalizzato? Come funziona?
**Risposta modello.** Un'annotazione con `@Constraint(validatedBy = ...)`, i tre attributi obbligatori `message`, `groups` e `payload`, e una classe che implementa `ConstraintValidator<Annotazione, Tipo>` con il metodo `isValid`. Per convenzione `isValid` restituisce `true` su `null`, che è compito di `@NotNull`. Per regole tra più campi l'annotazione si mette sulla classe, e il validatore riceve l'intero oggetto.

### Domanda 6: Perché `day_of_week` da 1 a 7, e cosa succede con `@Enumerated(ORDINAL)`?
**Risposta modello.** 1–7 è la numerazione ISO, quella di `java.time.DayOfWeek.getValue()` (lunedì = 1). `@Enumerated(ORDINAL)` salva invece la posizione nell'`enum`, da 0: lunedì diventerebbe 0, e il `check` 1–7 rifiuterebbe la domenica, oppure i giorni sarebbero spostati di uno. Serve un `AttributeConverter` che usi `getValue()` e `DayOfWeek.of()`. In generale `ORDINAL` è fragile: basta riordinare l'`enum` per corrompere i dati.

---

## Documentazione ufficiale

- PostgreSQL, *exclusion constraint*: https://www.postgresql.org/docs/17/sql-createtable.html#SQL-CREATETABLE-EXCLUDE
- PostgreSQL, tipi intervallo e definizione di un tipo nuovo (`timerange`): https://www.postgresql.org/docs/17/rangetypes.html
- PostgreSQL, `btree_gist`: https://www.postgresql.org/docs/17/btree-gist.html
- PostgreSQL, `alter table`: https://www.postgresql.org/docs/17/sql-altertable.html
- Hibernate ORM, documentazione (nella *User Guide*, sezione "Flush operation order"): https://hibernate.org/orm/documentation/
- Hibernate Validator, documentazione (nella *Reference Guide*, sezione "Creating custom constraints"): https://hibernate.org/validator/documentation/
