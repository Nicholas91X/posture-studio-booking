# La migrazione V2 del catalogo — guida di studio

Questa guida accompagna il **primo pezzo del passo 5**: la migrazione V2, con le tabelle `service` (i servizi) e `package_offer` (i pacchetti in vendita), e i test dei vincoli con SQL diretto. Nessuna entità Java ancora: quelle arrivano nei pezzi successivi.

Contiene:
- **la parte 1**: cosa contiene V2 e perché ogni vincolo è lì;
- **la parte 2**: **la scelta** su come garantire che il servizio di un pacchetto appartenga allo stesso professionista. Ci sono quattro strade a confronto e la decisione è tua;
- **la parte 3**: le domande di progetto di V2, con le risposte;
- **la parte 4**: la sintassi su un esempio **diverso** (una biblioteca con più sedi) e la checklist di V2. L'SQL lo scrivi tu;
- **la parte 5**: i test dei vincoli con SQL diretto, compreso quello rimasto indietro dal passo 4.

Fonti nel progetto: `docs/modello-dati.md` (tabelle `service`, `package_offer`), D-022 (prezzo unico), `requisiti.md` ("Servizi e prezzi", "Pacchetti"), guida 04 (vincoli, parte 2).

---

## Indice

1. [Cosa contiene V2](#parte-1--cosa-contiene-v2)
2. [La scelta: il servizio del pacchetto è dello stesso professionista?](#parte-2--la-scelta-il-servizio-del-pacchetto-è-dello-stesso-professionista)
3. [Le domande di progetto di V2](#parte-3--le-domande-di-progetto-di-v2)
4. [La sintassi, su un esempio diverso](#parte-4--la-sintassi-su-un-esempio-diverso)
5. [I test dei vincoli](#parte-5--i-test-dei-vincoli)
6. [Domande da colloquio](#domande-da-colloquio)
7. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Cosa contiene V2

### 1.1 Perché una migrazione nuova e non una modifica a V1

V1 è già stata eseguita sul tuo database e su quello di ogni test: non si tocca più (guida 04, 1.4; PD-07). Le tabelle nuove vanno in `V2__...sql`. Flyway la applica sopra V1, nello stesso ordine ovunque.

### 1.2 Le due tabelle

Dal modello dati, dopo D-022 (un solo prezzo, niente IVA):

**`service`**: un servizio che un professionista offre.

| Colonna | Tipo | Regola |
|---|---|---|
| `id` | `bigint` identity | chiave primaria |
| `practitioner_id` | `bigint` | obbligatorio, → `practitioner` |
| `name` | `text` | obbligatorio, non vuoto |
| `duration_minutes` | `integer` | obbligatorio, > 0 |
| `price_cents` | `bigint` | obbligatorio, > 0 |
| `active` | `boolean` | obbligatorio, default `true` |
| `created_at` | `timestamptz` | obbligatorio, default `now()` |

**`package_offer`**: un pacchetto "in vendita". Quello **comprato** non è qui: diventerà un lotto di crediti (D-010).

| Colonna | Tipo | Regola |
|---|---|---|
| `id` | `bigint` identity | chiave primaria |
| `practitioner_id` | `bigint` | obbligatorio, → `practitioner` (vedi parte 2) |
| `service_id` | `bigint` | obbligatorio, → `service` |
| `name` | `text` | obbligatorio, non vuoto |
| `sessions_count` | `integer` | obbligatorio, ≥ 2 |
| `price_cents` | `bigint` | obbligatorio, > 0, prezzo dell'**intero** pacchetto |
| `validity_days` | `integer` | obbligatorio, > 0 |
| `active` | `boolean` | obbligatorio, default `true` |
| `created_at` | `timestamptz` | obbligatorio, default `now()` |

### 1.3 Il nome "non vuoto": `not null` e `check` servono entrambi

Un nome può essere sbagliato in tre modi: assente (`NULL`), vuoto (`''`) o fatto solo di spazi (`'   '`).

- `not null` rifiuta il primo.
- Un `check` sul testo ripulito dagli spazi rifiuta gli altri due.

Ripasso di **PD-14**: un `check` con dentro un `NULL` non è falso ma "sconosciuto", e **passa**. Quindi il `check` da solo non basta: servono tutti e due.

Per il `check` ci sono due forme equivalenti: `btrim(name) <> ''` oppure `length(btrim(name)) > 0`. `btrim` toglie gli spazi all'inizio e alla fine. Attenzione: toglie solo gli **spazi**, non tabulazioni o a capo. Il controllo completo lo farà la validazione Java (`@NotBlank`, pezzo delle API). Il database qui è l'ultima difesa, non il primo filtro.

### 1.4 I numeri: perché `> 0` e perché `≥ 2`

- **Durata > 0** e **validità > 0**: una seduta di zero minuti o un pacchetto che scade subito non hanno senso.
- **Prezzo > 0**: un servizio gratuito non è previsto. Gli omaggi esistono, ma sono **crediti** concessi (requisiti, punto aperto 6), non servizi a prezzo zero.
- **Sedute ≥ 2**: un "pacchetto" da una seduta è una seduta singola con un altro nome. In più, avrebbe una scadenza e un prezzo diversi da quelli della seduta, e la stessa cosa venduta in due modi è solo confusione.

### 1.5 Cosa **non** diventa un vincolo, e perché

Un vincolo è per ciò che è vero **per ogni riga, sempre** (guida 04, 2.1). Alcune regole del brief non lo sono:

- **"Un pacchetto si crea solo su un servizio attivo."** Vale **al momento della creazione**. Domani il servizio può essere disattivato, e il pacchetto creato oggi resta valido: non è una contraddizione nei dati. È una regola del **servizio Java** (`CatalogService`), non un invariante.
- **"La durata deve stare nello scatto del professionista"** (es. multipla di 30 minuti). Dipende da un'altra tabella che **cambia** (`practitioner.slot_step_minutes`, e lo scatto stesso è ancora un punto aperto). Se ne riparla al passo 6, in Java.
- **"Il catalogo pubblico mostra un pacchetto solo se è attivo e il suo servizio è attivo."** È un filtro in **lettura**, non una regola sui dati: nel database possono convivere un pacchetto attivo e un servizio disattivato.

La regola pratica: se la condizione dipende dal **tempo** (vale al momento di un'azione) o da dati che **cambiano** in un'altra tabella, sta in Java.

### 1.6 Niente `on delete cascade`

Servizi e pacchetti non si cancellano mai: si disattivano (requisiti; come per i professionisti, D-021). Le chiavi esterne restano senza `on delete`: se qualcuno provasse a cancellare un professionista con dei servizi, il database rifiuterebbe. È quello che vogliamo.

---

## Parte 2 — La scelta: il servizio del pacchetto è dello stesso professionista?

### 2.1 Il problema

`package_offer` ha due chiavi esterne: `practitioner_id` e `service_id`. Ognuna, presa da sola, è valida. Ma guarda questa riga:

```
practitioner:  id=1 (Anna)          id=2 (Marco)
service:       id=10, practitioner_id=1  "Seduta posturale" di Anna
package_offer: practitioner_id=2, service_id=10
```

Il pacchetto è di **Marco**, ma i suoi crediti valgono per un servizio di **Anna**. Entrambe le chiavi esterne sono soddisfatte (Marco esiste, il servizio 10 esiste), eppure i dati sono incoerenti. Il professionista del pacchetto e quello del servizio devono coincidere.

### 2.2 Perché un `check` non può farlo

Un `check` vede **solo la propria riga** (guida 04, parte 2). Per sapere di chi è il servizio 10 bisogna leggere un'**altra** tabella, e un `check` non può. Servono altre strade.

### 2.3 Strada A — Foreign key composta

Una foreign key può puntare a **più colonne insieme**. Invece di dire "`service_id` deve esistere in `service`", si dice "la **coppia** (`service_id`, `practitioner_id`) deve esistere in `service` come (`id`, `practitioner_id`)".

Con l'esempio di prima: la coppia (10, 2) in `service` non esiste, perché il servizio 10 ha `practitioner_id = 1`. Il database rifiuta la riga.

Servono due pezzi:
1. **In `service`**: un `unique (id, practitioner_id)`. PostgreSQL accetta una foreign key solo verso colonne coperte da una chiave primaria o da un `unique` su **esattamente quelle colonne**. Che `id` sia già unico da solo non basta: il vincolo deve esistere sulla coppia. È un `unique` logicamente ridondante (se `id` è unico, a maggior ragione lo è la coppia), messo lì perché la foreign key ne ha bisogno.
2. **In `package_offer`**: `foreign key (service_id, practitioner_id) references service (id, practitioner_id)`.

Un dettaglio sul `NULL` (di nuovo PD-14). Di default una foreign key composta **non controlla** la riga se anche una sola delle sue colonne è `NULL` (`MATCH SIMPLE`). Qui entrambe sono `not null`, quindi il controllo scatta sempre. Senza `not null`, un `practitioner_id` nullo farebbe passare qualunque servizio.

**Pro.**
- Dichiarativa: una riga di SQL, nessun codice da eseguire. La regola è **impossibile** da violare, anche con un bug in Java o con un `insert` a mano.
- Lo stesso schema servirà di nuovo: `credit_lot` (passo 10) ha anche lui `practitioner_id` e `service_id`, con lo stesso rischio.

**Contro.**
- Un `unique` in più su `service`, con il suo indice (a questi volumi, irrilevante).
- È un costrutto meno comune: va spiegato a chi legge lo schema (un commento nella migrazione basta).
- JPA non lo vede: in Java avrai due `@ManyToOne` separati (professionista e servizio), e la coerenza tra i due la garantisce solo il database. Con `ddl-auto=validate` non è un problema, perché Hibernate controlla tabelle, colonne e tipi, non i vincoli.

**Da decidere dentro questa strada.** La foreign key semplice `service_id → service(id)` diventa superflua: la composta implica già che il servizio esista. Puoi tenerla per chiarezza o toglierla. Lo stesso vale per `practitioner_id → practitioner(id)`, che è implicata passando per `service`. Tenerle non costa nulla e rende ogni relazione leggibile da sola; toglierle evita vincoli doppi. Nessuna delle due è sbagliata.

### 2.4 Strada B — Togliere `practitioner_id` da `package_offer`

Se il pacchetto non ha un proprio professionista, l'incoerenza **non si può nemmeno scrivere**: il professionista si ricava dal servizio. È la **normalizzazione**: ogni fatto sta in un posto solo.

**Pro.**
- Il problema sparisce per costruzione, senza vincoli speciali.
- Una colonna in meno.

**Contro.**
- Ogni domanda "quali sono i pacchetti di Anna?" passa per una **join** con `service`. In SQL è banale; in JPA vuol dire `offer.getService().getPractitioner()`. Con il caricamento *lazy* questo può diventare una query in più per ogni pacchetto: è il problema **N+1**, che vedremo nel pezzo del catalogo.
- Diverge da `modello-dati.md`, che va aggiornato.
- Il controllo di proprietà ("questo pacchetto è di chi sta chiedendo?") passa sempre dal servizio.
- Per `credit_lot` la stessa scelta andrà rifatta al passo 10. Lì `practitioner_id` serve anche per le ricerche frequenti ("i crediti di questo cliente con Anna").

### 2.5 Strada C — Un trigger

Un *trigger* è una funzione nel database (in PL/pgSQL) che parte da sola prima di ogni `insert` o `update` su `package_offer`. Legge il servizio, confronta i professionisti e, se non coincidono, rifiuta.

**Pro.** Può esprimere qualunque regola, anche complessa.

**Contro.**
- Logica **procedurale** nascosta nel database: chi legge il codice Java non la vede.
- Va scritta, testata e mantenuta in un secondo linguaggio.
- In generale soffre di concorrenza: due transazioni possono leggere uno stato che l'altra sta cambiando. Qui il professionista di un servizio non cambia mai, quindi il rischio non c'è, ma è un'abitudine da non prendere.
- Per questa regola la strada A fa lo stesso lavoro in modo dichiarativo.

### 2.6 Strada D — Solo in Java

`CatalogService`, prima di creare un pacchetto, carica il servizio e controlla che sia del professionista corrente.

Questo controllo va fatto **in ogni caso**, qualunque strada si scelga, per due motivi:
- dare un errore chiaro (un 404, vedi il pezzo sulla proprietà) invece di un'eccezione del database;
- controllare anche che il servizio sia **attivo**, che non è un invariante (1.5).

La domanda è se basti **da solo**. Ripasso di PD-17: Java non protegge da un bug, da una scrittura fuori dall'applicazione (uno script, un `insert` a mano), né da due richieste in parallelo. Per una regola che deve essere vera **sempre**, il database è l'ultima difesa.

### 2.7 Il confronto

| | A — FK composta | B — Niente `practitioner_id` | C — Trigger | D — Solo Java |
|---|---|---|---|---|
| Garanzia nel database | ✅ sì | ✅ sì, per costruzione | ✅ sì | ❌ no |
| Codice da scrivere | una riga di SQL | nessuno | una funzione PL/pgSQL | un controllo Java |
| Visibile a chi legge lo schema | sì | sì | poco | no |
| Ricerche "pacchetti di Anna" | dirette | con una join | dirette | dirette |
| Modifica a `modello-dati.md` | no (solo il vincolo) | sì | no | no |
| Riutilizzabile per `credit_lot` | sì | da rivalutare | sì | sì |

Il controllo in Java (D) c'è comunque: la scelta è **cosa aggiungere sotto**.

### 2.8 La decisione

> **Decisa il 2026-10-29: strada A** (D-025).

È tua. Quando hai scelto, scriviamo la scheda in `decisioni.md` (sarà D-025), con le alternative scartate e il perché, come per D-020.

Se scegli B, aggiorniamo anche `modello-dati.md` prima che tu scriva la migrazione.

---

## Parte 3 — Le domande di progetto di V2

| Domanda | Risposta |
|---|---|
| Il nome di un servizio deve essere unico per professionista? | Non è nel brief. Due servizi con lo stesso nome confonderebbero il cliente, ma potrebbe esistere un servizio disattivato con lo stesso nome di uno nuovo. Se lo vuoi, un `unique (practitioner_id, name)` vale anche per i disattivati; la versione "solo tra gli attivi" richiede un *indice unico parziale* (`create unique index ... where active`). Proposta: non metterlo ora e annotarlo come domanda per lo studio. Decidi tu |
| `price_cents` è `bigint` o `integer`? | `bigint`, come da convenzione (`CLAUDE.md`, "Soldi"): in Java è un `long` |
| Serve un `updated_at`? | Non è nel modello dati. Lo storico dei prezzi non serve, perché ogni prenotazione e ogni lotto fotografano il prezzo (D-011). Non serve |
| Indici sulle chiavi esterne? | PostgreSQL **non** li crea da solo (PD-16). La ricerca più frequente sarà "i servizi (o pacchetti) del professionista X": un indice su `practitioner_id` in entrambe le tabelle. Con due professionisti e pochi servizi la differenza non si misura, ma è l'abitudine giusta. Attenzione: con la strada A l'indice del `unique (id, practitioner_id)` **non** aiuta, perché la prima colonna è `id`. Un indice serve le ricerche che partono dalla sua prima colonna |
| Cosa succede a un pacchetto se il suo servizio viene disattivato? | Niente nei dati. Il catalogo pubblico non lo mostra (filtro in lettura, 1.5). Chi l'ha già comprato ha un lotto di crediti e lo usa fino alla scadenza (passo 10) |
| Modificare prezzo o durata di un servizio cambia qualcosa di già venduto? | No. Prenotazioni e lotti hanno la loro copia del prezzo (D-011). Per la durata: ogni prenotazione salva `starts_at` e `ends_at`, quindi una seduta già prenotata mantiene la sua durata |

---

## Parte 4 — La sintassi, su un esempio diverso

Una biblioteca con più **sedi**. Ogni **scaffale** sta in una sede. Ogni **copia** di un libro sta in una sede e su uno scaffale, e lo scaffale deve essere **della stessa sede** della copia. È lo stesso problema di `package_offer`, con nomi diversi.

```sql
-- V7__create_shelf_and_book_copy.sql  (esempio, non è il nostro progetto)

create table shelf (
    id          bigint       generated always as identity,
    branch_id   bigint       not null,
    label       text         not null,
    capacity    integer      not null,
    active      boolean      not null default true,
    created_at  timestamptz  not null default now(),

    constraint shelf_pk               primary key (id),
    constraint shelf_branch_fk        foreign key (branch_id) references branch (id),
    -- label must contain something other than spaces
    constraint shelf_label_ck         check (btrim(label) <> ''),
    constraint shelf_capacity_ck      check (capacity > 0),
    -- needed as target of the composite foreign key in book_copy
    constraint shelf_id_branch_id_uk  unique (id, branch_id)
);

create index shelf_branch_id_ix on shelf (branch_id);

create table book_copy (
    id          bigint       generated always as identity,
    branch_id   bigint       not null,
    shelf_id    bigint       not null,
    isbn        text         not null,
    created_at  timestamptz  not null default now(),

    constraint book_copy_pk               primary key (id),
    constraint book_copy_branch_fk        foreign key (branch_id) references branch (id),
    -- the shelf must belong to the same branch as the copy
    constraint book_copy_shelf_branch_fk  foreign key (shelf_id, branch_id)
                                          references shelf (id, branch_id)
);
```

Da notare:
- l'**ordine** delle colonne nella foreign key composta deve corrispondere a quello del `unique` di destinazione: `(shelf_id, branch_id)` → `(id, branch_id)`;
- i nomi seguono la convenzione di V1: `tabella_colonne_tipo`, con i suffissi `_pk`, `_uk`, `_ck`, `_fk`, e `_ix` per gli indici;
- un indice si crea con `create index`, fuori dal `create table`. Non è un vincolo: non rifiuta niente, rende veloci le ricerche.

### Cosa deve contenere la nostra V2

- [ ] Nome del file: `V2__` + descrizione in inglese, `snake_case`
- [ ] `service` con le colonne della tabella 1.2, nell'ordine che preferisci
- [ ] `package_offer` con le colonne della tabella 1.2 (meno `practitioner_id`, se scegli la strada B)
- [ ] `not null` su tutte le colonne obbligatorie
- [ ] `check` con nome: nome non vuoto (entrambe le tabelle), durata > 0, prezzo > 0 (entrambe), sedute ≥ 2, validità > 0
- [ ] Foreign key con nome: `service` → `practitioner`; `package_offer` → `service` (e → `practitioner`, secondo la tua scelta)
- [ ] La regola della parte 2, secondo la strada scelta
- [ ] Indici sulle chiavi esterne che userai per cercare
- [ ] Nessun `on delete cascade`
- [ ] Un commento sopra ogni vincolo non ovvio (come in V1)

Quando la migrazione c'è, si avvia l'applicazione: Flyway applica V2 e `validate` non si lamenta, perché non ci sono ancora entità per queste tabelle. Poi la verifichi in `psql` con `\d service` e `\d package_offer`, e infine con i test.

---

## Parte 5 — I test dei vincoli

### 5.1 Perché con SQL diretto

Le entità `Service` e `PackageOffer` non esistono ancora, ma soprattutto non è Java che vuoi provare: è il **database**. Con `JdbcTemplate` (lo usi già in `AppUserRepositoryTest`) mandi `insert` scritti a mano, come in `psql`. Se un vincolo manca o è scritto male, il test lo scopre anche quando Java (domani) avrà le sue validazioni davanti.

`JdbcTemplate` traduce gli errori di PostgreSQL nelle eccezioni di Spring. Un vincolo violato (`check`, `unique`, foreign key, `not null`) diventa una `DataIntegrityViolationException`, la stessa che conosci dal test dell'email duplicata.

### 5.2 I tre principi, dal passo 4

1. **Il nome del vincolo nel messaggio.** Una `DataIntegrityViolationException` può nascere da **qualunque** vincolo. Se il tuo `insert` di prova dimentica una colonna obbligatoria, fallisce sul `not null` e il test "passa" per il motivo sbagliato. Controlla che il messaggio contenga il nome del vincolo che vuoi provare (es. `service_price_cents_ck`).
2. **Un caso positivo.** Almeno un test inserisce una riga **valida** e verifica che venga accettata. Se manca, un errore nei dati di partenza (un professionista che non esiste) farebbe fallire tutti gli `insert`, e tutti i test negativi passerebbero.
3. **Una sola riga sbagliata per test**, con un solo motivo per essere sbagliata. Tutto il resto valido.

### 5.3 I dati di partenza

Per inserire un servizio serve un professionista, e per il professionista serve un `app_user` (V1). Per provare la regola della parte 2 servono **due** professionisti. Ti conviene un metodo di supporto nella classe di test (es. `insertPractitioner(String email)`) che inserisce utente e professionista con SQL e restituisce l'id del professionista.

Un aiuto per gli id: `insert ... returning id` restituisce l'id generato. Con `JdbcTemplate` si legge con `queryForObject(sql, Long.class, parametri...)`.

Ogni test di `@DataJpaTest` viene annullato a fine metodo (`rollback`), quindi i dati non passano da un test all'altro.

### 5.4 I casi

**V2**

| # | Caso | Atteso |
|---|---|---|
| 1 | Servizio valido | accettato; `active` vale `true` e `created_at` è valorizzato |
| 2 | Servizio con nome `'   '` | rifiutato, nome del `check` sul nome |
| 3 | Servizio con durata 0 | rifiutato, `check` sulla durata |
| 4 | Servizio con prezzo 0 | rifiutato, `check` sul prezzo |
| 5 | Servizio di un professionista inesistente | rifiutato, foreign key verso `practitioner` |
| 6 | Pacchetto valido | accettato |
| 7 | Pacchetto da 1 seduta | rifiutato, `check` sulle sedute |
| 8 | Pacchetto con validità 0 | rifiutato, `check` sulla validità |
| 9 | Pacchetto di Marco sul servizio di Anna | rifiutato, foreign key composta (strada A). Con la strada B il caso non si può scrivere: annotalo nel test come commento |

Prova anche un **valore limite**: un pacchetto da **2** sedute è accettato. Se per sbaglio hai scritto `> 2` invece di `>= 2`, solo questo test lo scopre.

**Rimasto dal passo 4**

| # | Caso | Atteso |
|---|---|---|
| 10 | `app_user` con email in maiuscolo, inserita con SQL | rifiutato, `app_user_email_ck` |

Era nella lista della guida 05 (caso 4) ma non è in `AppUserRepositoryTest`. Il test che c'è oggi passa da Java, dove `Email` normalizza prima di salvare: prova Java, non il `check`. Solo l'`insert` diretto prova che il database si difende da solo. Se vuoi completare V1, aggiungi anche un ruolo inesistente (`app_user_role_ck`) e uno dei tre `check` di `practitioner`.

L'altro test rimandato, il *dirty checking* su un'entità *managed*, arriva nel pezzo successivo, con l'entità `Practitioner` e la modifica delle impostazioni.

### 5.5 Dove mettere i test

Due possibilità:
- i test di V2 in una classe nuova (es. `CatalogSchemaTest` nel package `catalog`) e il caso 10 in `AppUserRepositoryTest`;
- oppure una sola classe per tutti i test "di schema".

La prima segue i moduli (D-002): ogni modulo prova le sue tabelle. Con due classi, però, avrai **due volte** la dichiarazione del container. Quando succede, guardiamo insieme come condividerla, invece di copiarla. È l'occasione per i file di test di Initializr che avevi lasciato da parte al passo 4.

---

## Domande da colloquio

### Domanda 1: Un `check` può verificare che un valore esista in un'altra tabella? Se no, come si garantisce nel database una regola che coinvolge due tabelle?
**Risposta modello.** No: un `check` vede solo la riga che si sta scrivendo. Per le regole tra tabelle ci sono le foreign key, anche **composte** quando la regola riguarda più colonne insieme (es. "il servizio del pacchetto è dello stesso professionista"). In alternativa si cambia lo schema perché l'incoerenza non sia scrivibile (normalizzazione), oppure si usa un trigger, che però è logica procedurale nascosta. Il controllo in Java resta, per dare un errore chiaro, ma da solo non protegge da bug, scritture esterne e concorrenza.

### Domanda 2: Perché per una foreign key composta serve un `unique` sulla coppia di colonne di destinazione, anche se una delle due è già chiave primaria?
**Risposta modello.** PostgreSQL accetta una foreign key solo verso colonne coperte da una chiave primaria o da un `unique` su **esattamente quelle colonne**. Logicamente la coppia è già unica, ma il vincolo deve esistere in modo esplicito. Crea anche un indice, che però serve solo alle ricerche che partono dalla sua prima colonna.

### Domanda 3: Una colonna `text not null` con `check (btrim(name) <> '')`: servono davvero entrambi?
**Risposta modello.** Sì. Il `check` con un `NULL` non è falso ma sconosciuto, e un vincolo `check` lo lascia passare: senza `not null` un nome nullo verrebbe accettato. Il `not null` da solo invece accetterebbe `''` e `'   '`. Ognuno copre un caso diverso.

### Domanda 4: Quali regole metti nel database e quali in Java? Fai un esempio dal catalogo.
**Risposta modello.** Nel database ciò che è vero per ogni riga, sempre: prezzo positivo, almeno due sedute, il servizio del pacchetto dello stesso professionista. In Java ciò che dipende dal momento o da dati che cambiano: "un pacchetto si crea solo su un servizio **attivo**" vale alla creazione, ma domani il servizio può essere disattivato e il pacchetto resta valido. Anche i filtri in lettura, come il catalogo pubblico, sono in Java: non sono regole sui dati.

### Domanda 5: Come provi che un vincolo del database funziona, e come eviti che il test passi per il motivo sbagliato?
**Risposta modello.** Con un test di integrazione su un PostgreSQL vero (Testcontainers) che manda un `insert` diretto con `JdbcTemplate`, saltando le validazioni Java. Per evitare falsi positivi: si controlla il **nome** del vincolo nel messaggio dell'eccezione, si cambia un solo valore alla volta rispetto a una riga valida, e c'è almeno un caso positivo che dimostra che la riga valida viene accettata. Più un caso limite (2 sedute) per scoprire un `>` scritto al posto di `>=`.

---

## Documentazione ufficiale

- PostgreSQL 17, vincoli (`check`, `unique`, foreign key, `MATCH SIMPLE`): https://www.postgresql.org/docs/17/ddl-constraints.html
- PostgreSQL 17, `CREATE TABLE` (sintassi completa dei vincoli): https://www.postgresql.org/docs/17/sql-createtable.html
- PostgreSQL 17, indici su più colonne e ordine delle colonne: https://www.postgresql.org/docs/17/indexes-multicolumn.html
- PostgreSQL 17, funzioni sulle stringhe (`btrim`): https://www.postgresql.org/docs/17/functions-string.html
- Spring Framework, traduzione delle eccezioni JDBC (`DataAccessException`): https://docs.spring.io/spring-framework/reference/data-access/dao.html
