# Il modulo `schedule`, lato pannello — guida di studio

Questa guida accompagna il **pezzo 2 del passo 6**: il professionista imposta dal pannello le sue **versioni dello schema settimanale** (D-034) e le sue **indisponibilità**. Ci sono le entità, il repository, `ScheduleService` con le regole, e le API sotto `/api/panel`. Il calcolo degli orari liberi resta per il pezzo 3.

Contiene:
- **la parte 1**: cosa si costruisce, con le API e il JSON;
- **la parte 2**: **prima scelta**: quando una versione è ancora modificabile. Il testo di D-034 ha un buco, e lo vediamo con un esempio;
- **la parte 3**: **seconda scelta**: dove si controllano le fasce (orari, sovrapposizioni);
- **la parte 4**: **terza scelta**: come si sostituiscono le fasce di una versione senza cadere nella trappola di Hibernate (guida 14, 2.4);
- **la parte 5**: i concetti nuovi: l'aggregato con `@OneToMany`, il convertitore per `DayOfWeek`, il `Clock`;
- **la parte 6**: le scelte piccole che propongo senza chiederti;
- **la parte 7**: i test.

Teoria di riferimento: guida 10 (relazioni JPA, N+1, `join fetch`); guida 11 (API del pannello, validazione, errori); guida 13, parte 2 (i tipi del tempo, `Clock`); guida 14 (V3, `@OneOf`, la trappola dell'ordine al *flush*); D-027 (409 con codice), D-028 (professionista corrente).

---

## Indice

1. [Cosa si costruisce](#parte-1--cosa-si-costruisce)
2. [Prima scelta: quando una versione è modificabile](#parte-2--prima-scelta-quando-una-versione-è-modificabile)
3. [Seconda scelta: dove si controllano le fasce](#parte-3--seconda-scelta-dove-si-controllano-le-fasce)
4. [Terza scelta: come si sostituiscono le fasce](#parte-4--terza-scelta-come-si-sostituiscono-le-fasce)
5. [I concetti nuovi](#parte-5--i-concetti-nuovi)
6. [Le scelte piccole](#parte-6--le-scelte-piccole)
7. [I test](#parte-7--i-test)
8. [Cosa devi decidere](#parte-8--cosa-devi-decidere)
9. [Domande da colloquio](#domande-da-colloquio)
10. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Cosa si costruisce

### 1.1 I pezzi

| Classe | Visibilità | Cosa fa |
|---|---|---|
| `WorkingSchedule` | package | Entità su `working_schedule`: una versione. È la **radice dell'aggregato**: le fasce si toccano solo passando da lei (parte 5.1) |
| `WorkingHours` | package | Entità su `working_hours`: una fascia. Nessun repository suo |
| `Unavailability` | package | Entità su `unavailability` |
| `DayOfWeekConverter` | package | `DayOfWeek` ↔ `smallint` 1–7 (parte 5.2) |
| `WorkingScheduleRepository`, `UnavailabilityRepository` | package | Con la proprietà dentro la query (`...AndPractitionerId`), come in `catalog` |
| `ScheduleService` | **public** | La porta del modulo: versioni e indisponibilità del professionista che agisce |
| `SchedulePanelController` | package | Le API sotto `/api/panel` |
| `record` di ingresso e uscita | **public** | `ScheduleVersionRequest`, `WorkingBand`, `ScheduleVersionView`, `NewUnavailability`, `UnavailabilityView` |
| Un bean `Clock` e la zona dello studio | `shared` | "Oggi" a Roma, fissabile nei test (parte 5.3) |

### 1.2 Le API

| Metodo e URL | Cosa fa | Risposte |
|---|---|---|
| `GET /api/panel/schedules` | Tutte le versioni del professionista, con le fasce, in ordine di `validFrom` | 200 |
| `POST /api/panel/schedules` | Crea una versione | 201 + `Location`; 400; 409 |
| `PUT /api/panel/schedules/{id}` | Sostituisce una versione **ancora modificabile** (data e fasce) | 200; 400; 404; 409 |
| `DELETE /api/panel/schedules/{id}` | Cancella una versione ancora modificabile | 204; 404; 409 |
| `GET /api/panel/unavailabilities` | Le indisponibilità non ancora finite, in ordine di inizio | 200 |
| `POST /api/panel/unavailabilities` | Crea un'indisponibilità | 201 + `Location`; 400 |
| `DELETE /api/panel/unavailabilities/{id}` | La cancella | 204; 404 |

Le indisponibilità non hanno un `PUT`: per correggerne una si cancella e se ne crea un'altra. Sono oggetti piccoli, e un'operazione in meno è codice in meno da provare.

### 1.3 Il JSON di una versione

In ingresso:

```json
{
  "validFrom": "2027-06-01",
  "bands": [
    { "dayOfWeek": "MONDAY", "startTime": "08:00", "endTime": "14:00" },
    { "dayOfWeek": "TUESDAY", "startTime": "08:00", "endTime": "14:00" }
  ]
}
```

In uscita, con due campi calcolati dal backend:

```json
{
  "id": 11,
  "validFrom": "2027-06-01",
  "current": false,
  "editable": true,
  "bands": [ ... ]
}
```

- `current`: è la versione valida **oggi**;
- `editable`: si può ancora modificare o cancellare.

Sono **regole**, quindi le decide il backend. Il pannello si limita a mostrare o nascondere il pulsante "modifica" (CLAUDE.md: il frontend non contiene regole di business).

---

## Parte 2 — Prima scelta: quando una versione è modificabile

### 2.1 Il buco nel testo di D-034

D-034 dice: "Una versione già iniziata non si modifica: per cambiare da subito se ne crea una con `valid_from` di oggi". Proviamolo con un esempio:

> Venerdì 6 novembre, ore 9:00. Anna crea una versione "da oggi". Alle 10:00 si accorge di aver scritto 9–12 invece di 9–13.

- La versione ha `valid_from` = oggi: è **iniziata**, quindi non si può modificare.
- Una nuova versione "da oggi" non si può creare: c'è già, e il `unique (practitioner_id, valid_from)` la rifiuta.
- Resta solo una versione "da domani": per oggi l'orario resta sbagliato.

### 2.2 Strada A — Modificabile solo se `valid_from` è **dopo** oggi

È il testo di D-034 preso alla lettera, con il buco appena visto.

### 2.3 Strada B — Modificabile se `valid_from` è **oggi o dopo**

Perché è ancora coerente con lo scopo di D-034: la regola esiste per **non riscrivere la storia**, cioè l'orario che valeva nei giorni passati. Una versione che parte oggi non ha mai valso per un giorno passato: modificarla tocca solo oggi e il futuro, ed è proprio il "da subito".

Conseguenze:
- **congelate** = versioni con `valid_from` **prima** di oggi;
- **create** = solo con `valid_from` da oggi in poi (niente versioni retrodatate);
- per cambiare "da subito" mentre vale una versione vecchia, se ne crea una da oggi; se quella da oggi esiste già, la si modifica.

### 2.4 Raccomandazione

**B.** Chiude il buco e rispetta il motivo della regola. È una **precisazione** di D-034, non un cambio: la scrivo come tale nella scheda.

"Oggi" vuol dire oggi **a Roma**, chiesto al `Clock` (parte 5.3). Alle 00:30 di Roma, in UTC è ancora ieri: con `LocalDate.now()` del server, la regola sbaglierebbe per un'ora (o due d'estate) ogni notte.

---

## Parte 3 — Seconda scelta: dove si controllano le fasce

### 3.1 Cosa va controllato

Per ogni fascia: inizio prima della fine; inizio e fine all'ora piena o alla mezz'ora (D-033). Per la versione: due fasce dello stesso giorno non si sovrappongono (D-036). Il database lo controlla già (V3), ma con un 409 generico senza il nome del campo. Il pannello deve sapere **quale** fascia è sbagliata.

### 3.2 Strada A — Bean Validation sui `record` di ingresso, l'entità come seconda difesa

- **`@OnTheHalfHour`**, un vincolo nostro su `LocalTime`, come `@OneOf`: si mette su `startTime` ed `endTime`.
- **`@ValidBands`**, un vincolo **di classe** su `ScheduleVersionRequest`: riceve l'intera richiesta, controlla inizio < fine e le sovrapposizioni, e segnala l'errore sulla fascia giusta, per esempio `bands[1]`.
- L'entità `WorkingSchedule` ricontrolla le stesse regole quando riceve le fasce, e lancia `IllegalArgumentException`: un errore di programmazione, come nelle altre entità.

Un vincolo di classe si scrive come `@OneOf`, ma con `@Target(TYPE)`, e il validatore riceve l'oggetto intero. Per far uscire l'errore su un elemento della lista invece che sulla classe, il validatore costruisce il percorso a mano:

```java
// esempio diverso: un ordine con più righe, due righe con lo stesso prodotto sono un errore
context.disableDefaultConstraintViolation();
context.buildConstraintViolationWithTemplate("duplicate product")
        .addPropertyNode("lines")
        .addBeanNode().inIterable().atIndex(index)
        .addConstraintViolation();
```

Al client arriva `"field": "lines[2]"`.

- **Pro**: 400 sul campo giusto, nello stesso formato degli altri errori.
- **Pro**: è proprio la **validazione incrociata** che servirà al passo 7 (`approval_window_hours` ≤ `min_booking_notice_hours`, D-030). La impari qui su un caso concreto.
- **Contro**: tre classi in più (due annotazioni, un validatore di classe), più quella di `@OnTheHalfHour`.

### 3.3 Strada B — Nel servizio, con `BusinessRuleException`

- **Contro**: diventa un 409, ma D-027 riserva il 409 alle regole che dipendono dallo **stato** (un servizio disattivato, un orario preso). Due fasce sovrapposte nella stessa richiesta non dipendono da niente: la richiesta è sbagliata in sé, ed è un 400.

### 3.4 Strada C — Solo il database

- **Contro**: 409 `DATA_CONFLICT` generico. Il professionista non saprebbe quale fascia correggere.

### 3.5 Raccomandazione

**A.** È lo schema che usiamo già (validazione → 400, entità → seconda difesa, database → ultima difesa), applicato a una regola tra più elementi.

---

## Parte 4 — Terza scelta: come si sostituiscono le fasce

### 4.1 Il problema (guida 14, 2.4)

`PUT` di una versione futura con fasce quasi uguali alle vecchie, per esempio con lunedì 9–13 sia prima sia dopo. Se Hibernate inserisce le nuove fasce **prima** di cancellare le vecchie, il vincolo `working_hours_no_overlap_ex` vede due lunedì 9–13 nella stessa versione e rifiuta. Il caso "uguale a prima" non è raro: è il più comune, perché di solito si cambia una fascia su dieci.

### 4.2 Strada A — Svuotare, `flush()`, riempire

```java
// nel servizio
schedule.removeAllBands();       // orphanRemoval: le fasce tolte dalla lista verranno cancellate
repository.flush();              // manda ora i delete al database
schedule.addBands(newBands);     // gli insert partono al commit, a vecchie fasce già sparite
```

- **Pro**: l'ordine è **garantito**, qualunque cosa faccia Hibernate dentro.
- **Contro**: un dettaglio di persistenza (`flush`) dentro il servizio. Va spiegato con un commento.

### 4.3 Strada B — Fidarsi dell'*orphan removal*

Si svuota e si riempie la lista, senza `flush`. Hibernate cancella gli "orfani", cioè le fasce tolte dalla lista, e per questo caso prevede in alcuni casi di farlo **prima** degli `insert`.
- **Pro**: codice più pulito.
- **Contro**: dipende da un dettaglio interno di Hibernate, non da una regola della specifica JPA. Un aggiornamento potrebbe cambiarlo, e lo scopriremmo solo con un test rosso, o in produzione.

### 4.4 Strada C — Confrontare vecchie e nuove fasce

Si tengono quelle uguali, si cancellano quelle sparite, si aggiungono le nuove.
- **Contro**: molto codice per un'operazione che un professionista fa poche volte l'anno.

### 4.5 Raccomandazione

**A**, con un test che sostituisce una versione con **le stesse identiche fasce**: è il caso peggiore, e se passa quello passano tutti. Per curiosità, scriviamo anche lo stesso test senza `flush`, e guardiamo cosa fa la nostra versione di Hibernate. Ma il codice non dipenderà dal risultato.

---

## Parte 5 — I concetti nuovi

### 5.1 L'aggregato: `@OneToMany` con cascata

Nella guida 10, domanda 6, avevamo deciso di **non** mappare il `@OneToMany` da un servizio verso i suoi pacchetti. Un pacchetto ha vita propria: si crea, si modifica e si disattiva da solo. Qui è il contrario: una fascia **non esiste** senza la sua versione. Si salva con lei, si sostituisce con lei e sparisce con lei. Nel Domain-Driven Design questo si chiama **aggregato**:
- `WorkingSchedule` è la **radice**: l'unico oggetto che il repository carica e salva;
- `WorkingHours` è **interna**: nessun repository suo, nessuno la tocca da fuori;
- le regole che coinvolgono più fasce (nessuna sovrapposizione) le fa rispettare la radice, perché è l'unica che le vede tutte.

In JPA:

```java
// esempio diverso: un ordine e le sue righe
@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
@OrderBy("position")
private List<OrderLine> lines = new ArrayList<>();
```

- `mappedBy = "order"`: la chiave esterna sta nella riga (`order_line.order_id`), mappata dal `@ManyToOne` dall'altra parte. È la forma **bidirezionale**, quella consigliata: con la forma unidirezionale Hibernate può fare `update` in più per scrivere la chiave esterna.
- `cascade = ALL`: salvare o cancellare l'ordine salva o cancella le righe.
- `orphanRemoval = true`: una riga tolta dalla lista viene cancellata dal database.
- `@OrderBy`: le righe arrivano già ordinate. Per noi `"dayOfWeek, startTime"`.

Le due parti della relazione vanno tenute allineate da **metodi della radice** (`addBand`, `removeAllBands`), mai modificando la lista da fuori. Per questo il getter restituisce una copia non modificabile.

**N+1, di nuovo.** `GET /api/panel/schedules` carica le versioni, e per ognuna le fasce (`LAZY`): una query per le versioni, più una per ciascuna. Si risolve come nella guida 10, con `left join fetch` (`left` perché una versione senza fasce deve comparire comunque). Da Hibernate 6 non serve più `distinct` per non avere la stessa versione ripetuta.

### 5.2 `DayOfWeek` nel database: un convertitore

`java.time.DayOfWeek` è un `enum`: `MONDAY` … `SUNDAY`. Con `@Enumerated(EnumType.ORDINAL)` JPA salverebbe la **posizione**, da 0 a 6, e il `check` 1–7 di V3 rifiuterebbe il lunedì. Con `EnumType.STRING` salverebbe `"MONDAY"` in una colonna `smallint`: errore. La soluzione è un `AttributeConverter`, come `EmailConverter`:

```java
// DayOfWeek ↔ 1..7, la numerazione ISO di java.time
public Short convertToDatabaseColumn(DayOfWeek day) { return day == null ? null : (short) day.getValue(); }
public DayOfWeek convertToEntityAttribute(Short value) { return value == null ? null : DayOfWeek.of(value); }
```

Nel JSON, invece, il giorno viaggia come `"MONDAY"`: Jackson tratta gli `enum` per nome, e il nome è più leggibile di un numero.

### 5.3 "Oggi", e un `Clock` per tutta l'applicazione

Serve già in questo pezzo, per la regola della parte 2. Propongo, nel package `shared`:
- **un bean `Clock`** (`Clock.systemUTC()`) in una classe di configurazione;
- **la zona dello studio** come costante (`ZoneId.of("Europe/Rome")`), in un posto solo, perché la useranno `schedule`, `booking` e le email.

Il servizio calcola "oggi" con `LocalDate.now(clock.withZone(STUDIO_ZONE))`. Nei test, un `Clock.fixed(...)` su venerdì 6 novembre 2026 alle 10:00 di Roma: le regole si provano su date scelte da noi, non sul giorno in cui gira il test.

Un dettaglio Spring: nei test `@SpringBootTest` esistono due bean `Clock`, quello vero e quello fisso. Quello di test si marca `@Primary`, così Spring sa quale iniettare. Il bean vero non si sostituisce: in Spring Boot sovrascrivere un bean con lo stesso nome è vietato per default, e va bene così.

---

## Parte 6 — Le scelte piccole

Le propongo senza chiederti; dimmi se qualcuna non ti torna.

| # | Scelta | Proposta | Perché |
|---|---|---|---|
| 1 | **Codici di errore** (D-027) | `SCHEDULE_ALREADY_STARTED` (modificare o cancellare una versione congelata), `SCHEDULE_STARTS_IN_THE_PAST` (creare o spostare una versione prima di oggi), `SCHEDULE_DATE_TAKEN` (un'altra versione ha già quella data) | Dipendono dallo stato e da "oggi": 409 con codice. `SCHEDULE_DATE_TAKEN` lo controlla Java prima del database: il `unique` resta l'ultima difesa |
| 2 | **Una versione senza fasce** | Ammessa (lista vuota, non `null`) | "Da questa data non lavoro" è un'informazione valida, per esempio per un professionista in pausa lunga. Il calcolo darà zero orari |
| 3 | **Spostare la data** di una versione con `PUT` | Ammesso, con le stesse regole di creazione | Sostituire vuol dire sostituire tutto: data e fasce |
| 4 | **Indisponibilità**: regole | Fine dopo l'inizio (vincolo di classe, 400); `reason` facoltativo, al massimo 200 caratteri, vuoto → `null`; ammesse anche nel passato | Un'assenza passata non fa danni, e vietarla complicherebbe il caso "la registro a fine giornata" |
| 5 | **Indisponibilità**: cosa mostra il `GET` | Quelle che non sono ancora finite (`ends_at` dopo adesso), in ordine di inizio | È ciò che serve nel pannello. Un filtro per data, per guardare lo storico, si aggiunge se servirà |
| 6 | **Orari nel JSON delle indisponibilità** | `OffsetDateTime` con l'offset di Roma, in ingresso e in uscita (D-035) | In ingresso il frontend manda `2026-11-12T11:15:00+01:00`; il backend salva l'istante. In uscita lo riconverte in ora di Roma |
| 7 | **Proprietà** | Versione o indisponibilità di un altro → 404, come in `catalog` | Guida 11, parte 5 |
| 8 | **Indisponibilità sopra prenotazioni già fatte** | Al passo 7 | Oggi le prenotazioni non esistono. Al passo 7 il pannello le segnalerà (come le prenotazioni fuori da una nuova versione, D-034) |

---

## Parte 7 — I test

### 7.1 Unitari, senza Spring

| Cosa | Casi principali |
|---|---|
| `WorkingSchedule` | fasce sovrapposte rifiutate; fasce che si toccano accettate; dopo un errore la versione resta com'era; la lista restituita non è modificabile |
| `DayOfWeekConverter` | lunedì ↔ 1, domenica ↔ 7, `null` ↔ `null` |
| `@OnTheHalfHour` | 09:00 e 09:30 sì; 09:15 no; 09:00:30 no; `null` sì |
| `@ValidBands` | sovrapposizione → errore su `bands[i]`; inizio dopo la fine → errore sulla fascia; stessa fascia in giorni diversi sì |

### 7.2 `ScheduleService` (`@DataJpaTest`, `Clock` fisso su venerdì 6 novembre 2026, 10:00 a Roma)

| # | Caso | Atteso |
|---|---|---|
| 1 | Crea una versione da lunedì 9 novembre | salvata con le sue fasce |
| 2 | Crea una versione da oggi | ammessa |
| 3 | Crea una versione da ieri | `SCHEDULE_STARTS_IN_THE_PAST` |
| 4 | Crea due versioni con la stessa data | la seconda: `SCHEDULE_DATE_TAKEN` (da Java, non dal database) |
| 5 | **Sostituisci una versione futura con le stesse identiche fasce** | ammesso: la prova della parte 4 |
| 6 | Sostituisci la versione di oggi | ammesso (parte 2) |
| 7 | Sostituisci o cancella una versione del 1° novembre | `SCHEDULE_ALREADY_STARTED` |
| 8 | Cancella una versione futura | sparisce con le sue fasce |
| 9 | Elenco con versioni dal 1° novembre, da oggi e dal 1° giugno | in ordine; `current` solo su quella di oggi; `editable` su oggi e giugno |
| 10 | Elenco: query | una sola, grazie al `join fetch` |
| 11 | Versione di un altro professionista | `NotFoundException` |

### 7.3 API (`@SpringBootTest` + `MockMvcTester`)

- `POST` valido → 201 con `Location`; il JSON ha giorni come `"MONDAY"` e orari come `"09:00"`.
- Fascia 09:15 → 400 sul campo `bands[0].startTime`.
- Due fasce sovrapposte → 400 sul campo `bands[1]`.
- Versione congelata → 409 con il codice.
- Indisponibilità con fine prima dell'inizio → 400; `reason` di 201 caratteri → 400.
- Indisponibilità creata con `+01:00` → riletta con lo stesso istante e l'offset di Roma.
- Senza header `X-Practitioner-Id` → 401 (D-028).

---

## Parte 8 — Cosa devi decidere

1. **Quando una versione è modificabile** (parte 2): A (solo se parte dopo oggi) o B (anche se parte oggi)? Io: **B**.
2. **Dove si controllano le fasce** (parte 3): A (Bean Validation sulla richiesta, entità come seconda difesa), B (servizio, 409) o C (solo database)? Io: **A**.
3. **Come si sostituiscono le fasce** (parte 4): A (svuota, `flush`, riempi), B (fidarsi dell'*orphan removal*) o C (confronto)? Io: **A**.
4. **Le scelte piccole** (parte 6): ok su tutte, o quale no?

---

## Domande da colloquio

### Domanda 1: Quando mappi un `@OneToMany`, e quando no?
**Risposta modello.** Quando i figli fanno parte del genitore e non hanno vita propria, come le righe di un ordine o le fasce di un orario: si salvano e si cancellano con lui, e le regole su più figli le controlla il genitore. Lì uso la forma bidirezionale con `mappedBy`, `cascade = ALL` e `orphanRemoval`, con metodi del genitore per tenerla allineata. Non lo mappo quando i "figli" sono entità indipendenti, con un ciclo di vita proprio, come i pacchetti di un servizio: una collezione che nessuno usa costa solo caricamenti e rischi di N+1.

### Domanda 2: Cos'è un aggregato nel Domain-Driven Design?
**Risposta modello.** Un gruppo di oggetti trattati come un'unità per le modifiche, con una radice: dall'esterno si passa solo dalla radice, che fa rispettare le regole interne (qui: nessuna sovrapposizione tra le fasce di una versione). Un repository per aggregato, non per tabella, e una transazione modifica di norma un aggregato solo. In JPA corrisponde spesso a un `@OneToMany` con cascata e *orphan removal*.

### Domanda 3: Perché `@Enumerated(ORDINAL)` è pericoloso?
**Risposta modello.** Salva la posizione della costante nell'`enum`, da 0. Se qualcuno aggiunge o riordina le costanti, i numeri già salvati cambiano significato senza nessun errore. `STRING` è più sicuro ma salva il nome. Se la colonna ha un formato suo, come i giorni ISO da 1 a 7, si usa un `AttributeConverter`.

### Domanda 4: Come scrivi una regola di validazione che coinvolge più campi o più elementi di una lista?
**Risposta modello.** Con un vincolo Bean Validation di classe (`@Target(TYPE)`): il validatore riceve l'oggetto intero. Per indicare al client il punto preciso si disattiva la violazione di default e se ne costruisce una con il percorso, per esempio `lines[2]`, con `addPropertyNode(...).addBeanNode().inIterable().atIndex(i)`. Così l'errore arriva sul campo come gli altri, con un 400, invece che come regola di business.

### Domanda 5: In un'applicazione con utenti in Italia e server in UTC, come calcoli "oggi"?
**Risposta modello.** Con la zona dell'utente, non con quella del server: `LocalDate.now(clock.withZone(ZoneId.of("Europe/Rome")))`. Con l'ora del server, tra mezzanotte e l'una (le due d'estate) "oggi" sarebbe ancora ieri. E con un `Clock` iniettato, perché le regole su "oggi" vanno provate su date fisse.

### Domanda 6: Sostituisci tutti i figli di un'entità e il database rifiuta per un vincolo. Come lo risolvi in modo affidabile?
**Risposta modello.** Il problema è l'ordine delle istruzioni al *flush*: Hibernate può inserire i nuovi figli prima di cancellare i vecchi. Il modo affidabile è togliere i vecchi, chiamare `flush()` per mandare subito i `delete`, poi aggiungere i nuovi. Fidarsi dell'ordine interno di Hibernate funziona in certi casi, ma è un dettaglio d'implementazione: va almeno coperto da un test con il caso peggiore, cioè figli identici ai precedenti.

---

## Documentazione ufficiale

- Jakarta Persistence 3.2, relazioni, cascata e *orphan removal* (sezioni "Entity Relationships" e "Cascade"): https://jakarta.ee/specifications/persistence/3.2/
- Hibernate ORM, documentazione (nella *User Guide*: associazioni `@OneToMany`, "Flush operation order"): https://hibernate.org/orm/documentation/
- Hibernate Validator, documentazione (nella *Reference Guide*: "Class-level constraints" e "Custom property paths"): https://hibernate.org/validator/documentation/
- `java.time.DayOfWeek`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/DayOfWeek.html
- `java.time.Clock`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Clock.html
