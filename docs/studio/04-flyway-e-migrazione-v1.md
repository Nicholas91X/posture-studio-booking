# Flyway e la migrazione V1 — guida di studio

Questa guida accompagna il punto 5 del passo 4: la prima migrazione, con le tabelle `app_user`, `practitioner` e `customer`.

Contiene:
- **la parte 1**: come funziona Flyway, e perché una migrazione eseguita non si tocca più;
- **la parte 2**: i vincoli SQL come ultima difesa (chiavi, unicità, check, chiavi esterne, default);
- **la parte 3**: **la scelta sull'email**, con le tre alternative a confronto. La decisione è tua;
- **la parte 4**: le domande di progetto che V1 deve risolvere, con le risposte;
- **la parte 5**: la sintassi su un esempio **diverso** (una biblioteca) e la checklist di V1. L'SQL lo scrivi tu;
- **la parte 6**: come verificare, compreso un esperimento per vedere Flyway difendersi.

Fonti di riferimento nel progetto: `docs/modello-dati.md` (convenzioni e tabelle) e il brief del passo 4.

---

## Indice

1. [Flyway](#parte-1--flyway)
2. [I vincoli SQL come ultima difesa](#parte-2--i-vincoli-sql-come-ultima-difesa)
3. [La scelta sull'email](#parte-3--la-scelta-sullemail)
4. [Le domande di progetto di V1](#parte-4--le-domande-di-progetto-di-v1)
5. [La sintassi, su un esempio diverso](#parte-5--la-sintassi-su-un-esempio-diverso)
6. [Verifica ed esperimenti](#parte-6--verifica-ed-esperimenti)
7. [Domande da colloquio](#domande-da-colloquio)
8. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Flyway

### 1.1 Il problema che risolve

Lo schema del database cambia nel tempo: al passo 5 arriveranno i servizi, al passo 7 le prenotazioni. E di database ne esistono **tanti**: il tuo in Docker, quello di Giulia, quelli usa e getta dei test con Testcontainers, quello di produzione. Ognuno può essere a un punto diverso.

Servono tre cose:
1. sapere **a che versione** è ciascun database;
2. portarlo all'ultima versione applicando **solo** le modifiche mancanti, **nell'ordine giusto**;
3. avere la certezza che la stessa versione voglia dire **lo stesso schema** dappertutto.

Flyway fa esattamente questo. Ogni modifica è un file SQL numerato, versionato in git insieme al codice: lo schema ha una **storia**, come il codice.

### 1.2 Il nome dei file

```
V1__create_users.sql
│││ └─ descrizione: parole separate da _ (diventa "create users" nello storico)
││└─ DUE trattini bassi: separano la versione dalla descrizione
│└─ la versione: 1, 2, 3... (anche 1.1, 2.5)
└─ V = migrazione versionata: si esegue una volta sola
```

Gli errori più comuni: **un solo** trattino basso (Flyway non riconosce il file e lo ignora), oppure il file messo fuori da `src/main/resources/db/migration`.

Esistono anche le migrazioni **ripetibili** (`R__descrizione.sql`), rieseguite ogni volta che il contenuto cambia: servono per viste o funzioni. Per ora non ci servono.

### 1.3 La tabella di storico

Al primo avvio Flyway crea nel database la tabella **`flyway_schema_history`**. Per ogni migrazione applicata scrive una riga:

| Colonna | Contenuto |
|---|---|
| `version` | `1` |
| `description` | `create users` |
| `script` | `V1__create_users.sql` |
| `checksum` | un numero calcolato dal **contenuto** del file |
| `installed_on` | quando è stata applicata |
| `execution_time` | quanto ci ha messo |
| `success` | se è andata a buon fine |

A ogni avvio Flyway:
1. legge i file in `db/migration`;
2. legge lo storico;
3. **controlla** che i file già applicati non siano cambiati, ricalcolando il checksum;
4. applica in ordine le migrazioni nuove, e le registra.

### 1.4 Il checksum, e perché una migrazione eseguita non si modifica mai

Il **checksum** è un'impronta del contenuto del file: cambia anche se cambi un solo carattere. Se all'avvio il checksum di un file non corrisponde a quello registrato, Flyway si ferma con `Migration checksum mismatch`, e l'applicazione **non parte**.

Sembra pignolo, ma è il cuore del sistema. Immagina di modificare V1 dopo averla eseguita:
- **sul tuo database** V1 è già stata applicata nella versione **vecchia**, e non verrà rieseguita;
- **in un database nuovo** (i test, il computer di Giulia, un nuovo server) V1 verrà eseguita nella versione **nuova**.

Risultato: due database "alla versione 1" con **schemi diversi**. È la stessa malattia di `ddl-auto=update` (verifica 01, D5). Il checksum la impedisce: ti obbliga a scrivere la correzione come **nuova migrazione** (V2), che arriva in ordine a tutti i database.

**L'unica eccezione, solo in locale.** Finché una migrazione non è stata condivisa (committata e pubblicata, o applicata in produzione), puoi correggerla e ripartire con un database vuoto: `docker compose ... down -v` e poi `up`. Dal momento in cui è nel repository pubblico, vale la regola: **mai più modificata**. È anche una regola del progetto (CLAUDE.md).

### 1.5 Le transazioni sullo schema

In PostgreSQL anche le istruzioni che modificano lo schema (`create table`, `alter table`) stanno dentro una **transazione**. Flyway esegue ogni migrazione in una transazione: se a metà file c'è un errore, **tutta** la migrazione viene annullata, e il database resta com'era prima. Correggi il file e riavvii.

Non è così ovunque: in MySQL, per esempio, le modifiche allo schema non si possono annullare, e una migrazione fallita a metà lascia il database in uno stato intermedio. È una delle ragioni per cui PostgreSQL è comodo con Flyway.

---

## Parte 2 — I vincoli SQL come ultima difesa

### 2.1 Perché anche nel database, se c'è già Java?

Le regole le controlla il backend, in Java. Ma il database è **l'ultima difesa**: vale anche quando il codice Java ha un bug, quando due richieste arrivano nello stesso istante (D-013), quando qualcuno corregge un dato a mano con `psql`. Un vincolo nel database non si può dimenticare e non si può aggirare.

Regola pratica: **tutto ciò che è vero per ogni riga, sempre, va scritto come vincolo**. Le regole che dipendono dal contesto (chi sta facendo l'operazione, che ora è, cosa c'è in altre tabelle) restano in Java.

**Il caso decisivo è la concorrenza.** Java lavora in due tempi: "controllo, poi salvo". Se due richieste arrivano nello stesso istante, entrambe controllano ("l'orario è libero?", "l'email è già usata?"), entrambe ricevono "sì", entrambe salvano. Nessun controllo in Java può chiudere questa finestra; il database sì, perché vede le due scritture una dopo l'altra e rifiuta la seconda. È il motivo di D-013.

**Non è duplicazione: sono due lavori diversi.** Java valida per dare all'utente un messaggio utile, presto e in italiano, e applica le regole di contesto. Il database garantisce le **invarianti**, chiunque scriva. Le regole tra tabelle o di contesto stanno solo in Java, quindi non tutto è ripetuto; e un vincolo che cambia è una migrazione, versionata come il codice.

### 2.2 La chiave primaria e le colonne *identity*

Convenzione del progetto (modello-dati): `id bigint generated always as identity`.

| Modo | Cosa fa | Note |
|---|---|---|
| `serial` / `bigserial` | Crea una sequenza e la collega alla colonna | Il modo vecchio, specifico di PostgreSQL |
| `generated by default as identity` | Il database genera l'id, **ma** puoi anche passarlo tu | Standard SQL |
| `generated always as identity` | Il database genera l'id, e **rifiuta** un id passato a mano (salvo una clausola esplicita, `overriding system value`) | Standard SQL, **la nostra scelta** |

`always` è una difesa: nessuno può inserire per errore un id scelto da sé, che poi entrerebbe in conflitto con quelli generati. Con JPA funziona, perché Hibernate con la strategia `IDENTITY` non manda l'id: lascia che lo generi il database e se lo fa restituire. Conseguenza da ricordare per i test: neanche lì si inseriscono id a mano.

`bigint` e non `int`: costa 4 byte in più per riga ed elimina per sempre il problema di "finire gli id".

### 2.3 `not null`

Ogni colonna obbligatoria va dichiarata `not null`. In SQL una colonna senza `not null` accetta `null`, e un `null` dove non te lo aspetti è la causa di metà dei bug sui dati. Le colonne facoltative del modello (es. `customer.phone`) sono le uniche senza.

### 2.4 `unique`

Garantisce che nessuna coppia di righe abbia lo stesso valore. PostgreSQL lo implementa creando automaticamente un **indice**, quindi la ricerca su quella colonna è anche veloce.

**"Al massimo uno" si scrive con `unique` su una chiave esterna.** Se la tabella `B` ha una colonna `a_id` che punta ad `A`, normalmente molte righe di `B` possono puntare alla stessa `A` (relazione uno a molti). Con `unique (a_id)` ogni `A` può avere **al massimo una** `B`: la relazione diventa uno a uno.

### 2.5 `check`

Una condizione che ogni riga deve rispettare. Se un `insert` o un `update` la viola, il database rifiuta l'operazione.

Usi tipici: valori ammessi (`status in ('A', 'B')`), positività (`quantity > 0`), relazioni tra colonne della stessa riga (`end_time > start_time`).

Un limite importante: un `check` vede **solo la riga che sta controllando**. Non può leggere altre righe né altre tabelle.

**La trappola del `NULL`.** In SQL `NULL > 0` non è né vero né falso: è *sconosciuto*. E un `check` rifiuta una riga solo se la condizione è **falsa**. Quindi `check (x > 0)` **lascia passare `NULL`** (verificato sul nostro PostgreSQL). Per questo un `check` va quasi sempre insieme a `not null`.

**Valori ammessi: `check` o tipo `enum` di PostgreSQL?** PostgreSQL ha un tipo `enum` (`create type role as enum (...)`). Per un insieme che può cambiare, il `check` su `text` è più pratico:

| | `text` + `check` | `enum` di PostgreSQL |
|---|---|---|
| Aggiungere un valore | Nuova migrazione che sostituisce il `check` | `alter type ... add value` |
| **Togliere** un valore | Nuova migrazione che sostituisce il `check` | Non si può: bisogna ricreare il tipo |
| Mapping con JPA (`@Enumerated(EnumType.STRING)`) | Diretto: è testo | Richiede configurazione aggiuntiva |

Per questo il modello usa `text` con `check`.

### 2.6 Chiavi esterne (*foreign key*)

`foreign key (a_id) references a (id)` garantisce che `a_id` punti a una riga che **esiste**. Il database impedisce anche di cancellare la riga di `A` finché qualcuno la usa (comportamento di default).

Un dettaglio che si chiede ai colloqui: PostgreSQL crea automaticamente un indice per le chiavi primarie e per `unique`, ma **non** per le colonne con chiave esterna. Se su quella colonna si faranno ricerche o *join* frequenti, l'indice va creato a mano. (Se la colonna è anche `unique`, l'indice c'è già.)

### 2.7 Valori di default

`default 24` fa sì che, se un `insert` non specifica la colonna, il database usi quel valore. Attenzione: il default **non** impedisce di scrivere un valore sbagliato esplicito. Per quello serve il `check`. Default e check lavorano in coppia: il default dà il valore sensato, il check rifiuta quelli assurdi.

**L'ordine:** il database prima **completa** la riga con i default, poi controlla `not null` e `check`. Il default vale solo per le colonne **non nominate** nell'`insert`: un `NULL` scritto esplicitamente non lo attiva.

| `insert` su una colonna con `default 24`, `not null`, `check (> 0)` | Risultato |
|---|---|
| Colonna non nominata | Salvata con 24 |
| `NULL` esplicito | Rifiutata da `not null` (senza `not null` sarebbe salvata con `NULL`) |
| `0` o `-5` | Rifiutata da `check` |

### 2.8 Dare un nome ai vincoli

Si può scrivere `unique (email)` e lasciare che PostgreSQL inventi un nome, oppure dargliene uno:

```sql
constraint app_user_email_uk unique (email)
```

Conviene **sempre dare il nome**, per tre motivi:
1. il messaggio d'errore lo riporta: `violates unique constraint "app_user_email_uk"` si capisce subito;
2. in Java potrai riconoscere **quale** vincolo è stato violato (ci servirà al passo 7, per rispondere "orario non più disponibile");
3. una migrazione futura che deve modificarlo lo trova per nome, senza doverlo cercare.

Una convenzione diffusa: `tabella_colonna_tipo`, con `_pk` (chiave primaria), `_uk` (unique), `_ck` (check), `_fk` (foreign key).

### 2.9 `text` o `varchar(n)`?

In PostgreSQL `text` e `varchar(n)` hanno **le stesse prestazioni**: `varchar(n)` aggiunge solo un controllo sulla lunghezza. Il modello usa `text`. Se una lunghezza massima ha un significato reale, si può aggiungere un `check (char_length(colonna) <= n)`, che si cambia più facilmente.

### 2.10 `created_at`

Convenzione: `created_at timestamptz not null default now()`. Il valore lo mette il **database** al momento dell'`insert`. `timestamptz` memorizza un **istante** (in UTC); la conversione in ora italiana si fa solo per mostrarlo (CLAUDE.md). Come mapparlo nell'entità, cioè come dire a Hibernate "questa colonna la riempie il database", è un argomento della prossima guida.

---

## Parte 3 — La scelta sull'email

### 3.1 Il requisito

Dal modello dati: l'email di `app_user` è **unica** e **salvata in minuscolo**.

Perché conta: l'accesso avviene con l'email (D-014). Se `Mario.Rossi@gmail.com` e `mario.rossi@gmail.com` fossero due utenti diversi, lo stesso cliente potrebbe ritrovarsi con due account, e i suoi crediti divisi tra i due. Per le email, maiuscole e minuscole non vanno distinte.

Sono **due** requisiti: (a) l'unicità senza distinguere maiuscole e minuscole; (b) il valore salvato in minuscolo. Le tre strade li coprono in modo diverso.

### 3.2 Le tre strade

**Strada A — `check` che impone il minuscolo, più `unique` normale.**
Il `check` rifiuta qualsiasi email che non sia già tutta minuscola (`email = lower(email)`). Visto che nel database possono esistere solo email minuscole, un `unique` normale basta a garantire l'unicità.

**Strada B — indice unico su `lower(email)`.**
Si crea un indice unico non sulla colonna, ma sul **risultato di una funzione** applicata alla colonna: `create unique index ... on app_user (lower(email))`. L'email si salva come arriva, e l'unicità è controllata sulla versione minuscola.

**Strada C — il tipo `citext`.**
`citext` (*case-insensitive text*) è un tipo fornito da un'estensione di PostgreSQL (`create extension citext`). I confronti tra valori `citext` ignorano maiuscole e minuscole, quindi un `unique` normale diventa automaticamente insensibile.

### 3.3 Il confronto

| | A: `check` + `unique` | B: indice su `lower()` | C: `citext` |
|---|---|---|---|
| Unicità senza maiuscole | ✅ | ✅ | ✅ |
| **Salvata in minuscolo** (requisito b) | ✅ il database lo **impone** | ❌ salva com'è arrivata | ❌ salva com'è arrivata |
| Ricerca per email | Semplice uguaglianza, purché l'input sia già minuscolo | Bisogna scrivere `lower(email) = lower(?)` perché l'indice venga usato: in JPA serve una query scritta a mano | Semplice uguaglianza |
| Se Java dimentica di convertire in minuscolo | L'`insert` **fallisce** (errore chiaro); una ricerca con maiuscole **non trova** niente | Funziona tutto, ma lo storico ha email scritte in modi diversi | Funziona tutto |
| Cosa serve in più | Niente | Niente | Un'estensione del database; e il mapping con Hibernate in `validate` può richiedere di dichiarare il tipo esplicitamente (da verificare, se scegli questa strada) |
| Portabilità ad altri database | SQL standard | Quasi ovunque | Solo PostgreSQL |
| È un vincolo con nome? | Sì, due | È un **indice**, non un vincolo: funziona allo stesso modo, ma si dichiara diversamente | Sì |

Le strade B e C coprono il requisito (b) solo se **anche** Java converte sempre in minuscolo: il database non lo garantisce.

**Il modo più semplice per distinguerle: in quale momento intervengono.**

| Strada | Quando interviene | Cosa fa |
|---|---|---|
| A, `check` | Quando si **scrive** | Rifiuta il valore se non è minuscolo |
| B, indice su `lower()` | Quando si **scrive** | Controlla solo i **duplicati**, sulla versione minuscola. Non cambia il valore salvato e **non cambia come funziona `=`**: `email = 'mario@x.it'` non trova `Mario@x.it` |
| C, `citext` | Quando si **confronta** | È il tipo della colonna: è l'uguaglianza stessa a ignorare le maiuscole |

Con un bug in Java che dimentica il minuscolo alla registrazione (verifica 03, D2): A lo scopre **subito**, alla registrazione; B lo fa esplodere **tardi**, al login, lontano dalla causa; C lo **nasconde** per sempre.

### 3.4 In ogni caso: Java deve normalizzare, in un posto solo

Qualunque strada scegli, il backend deve convertire l'email in minuscolo **prima** di salvarla e **prima** di cercarla. Altrimenti, con la strada A, un cliente che scrive `Mario@Gmail.com` al login non verrebbe trovato.

La regola è: **in un posto solo**. Se la conversione è sparsa in dieci punti del codice, prima o poi un punto la dimentica.

**Occasione Java moderno** (dal brief): questo è un caso perfetto per un **`record`**. Un tipo `Email` con un *compact constructor* (il costruttore senza parametri ripetuti dei record) che toglie gli spazi, converte in minuscolo e rifiuta i valori non validi. Da quel momento, dove il codice riceve un `Email` e non una `String`, l'email è **sempre** normalizzata: lo garantisce il tipo, non la memoria di chi scrive il codice. È un *value object*: niente identità, conta solo il valore, immutabile. Esattamente ciò per cui sono nati i record.

Un dettaglio da colloquio: `toLowerCase()` senza argomenti usa la **lingua del computer** su cui gira. In turco, per esempio, `"I".toLowerCase()` non dà `"i"` ma `"ı"` (una i senza puntino). Per dati tecnici come le email si usa `toLowerCase(Locale.ROOT)`, che non dipende dalla lingua.

### 3.5 La decisione

La scelta è tua. Quando l'hai fatta, scrivo la scheda in `decisioni.md` con il ragionamento e le alternative scartate. Domande per decidere:
- Quale strada fa rispettare **entrambi** i requisiti al database, senza dipendere da Java?
- Quale comportamento preferisci quando il codice Java sbaglia: un errore immediato o un dato salvato "storto" in silenzio? (È lo stesso principio del *fail fast* della configurazione.)
- Quanto pesa per te dipendere da un'estensione specifica di PostgreSQL?

---

## Parte 4 — Le domande di progetto di V1

Il modello dati dice **cosa** contengono le tabelle. Scrivendo l'SQL devi decidere **come** far rispettare ogni regola. Ecco le domande, con le risposte.

**1. Quali tabelle, e in che ordine?** `app_user` prima di tutto: `practitioner` e `customer` hanno una chiave esterna verso di lei, e una chiave esterna può puntare solo a una tabella che esiste già. Le tre tabelle stanno nella **stessa** migrazione V1: nascono insieme e servono insieme. Le altre tabelle **non** si creano ora: ognuna arriva con il passo che la usa (brief).

**2. Il ruolo: come limitarlo a `CUSTOMER` o `PRACTITIONER`?** Con un `check` sui valori ammessi (2.5), su colonna `text not null`.

**3. "Un utente collegato al massimo a un professionista e a un cliente": come?** Con `unique` sulla colonna `user_id` di `practitioner` e su quella di `customer` (2.4). Così un `app_user` può avere al massimo una riga in ciascuna delle due tabelle.

**4. `user_id` può essere vuoto?** No: un professionista o un cliente senza utente non potrebbe mai accedere. Il cliente nasce alla prima prenotazione **insieme** al suo utente (D-014), quindi non c'è un momento in cui esiste da solo. `not null`.

**5. Il database può garantire che un utente con ruolo `CUSTOMER` stia in `customer` e non in `practitioner`?** **No, non con un `check`**: il `check` vede solo la propria riga (2.5), e il ruolo sta in un'altra tabella. Ci sono tecniche più complesse (trigger, o ripetere il ruolo nella tabella figlia con una chiave esterna composta), ma qui il costo supera il beneficio. Questa coerenza la garantirà il **servizio Java** che crea gli utenti. È un buon esempio del confine tra regole "per riga" (database) e regole "tra tabelle" (Java), da saper spiegare.

**6. Impostazioni del professionista: default e positività.** Le tre colonne `cancellation_notice_hours`, `single_credit_validity_days`, `slot_step_minutes` hanno un default (24, 90, 30 dal modello dati) **e** un `check` che le vuole maggiori di zero (2.7: default e check in coppia), più `not null`.

**7. `display_name`, `full_name`: basta `not null`?** `not null` accetta la stringa vuota `''`. Se vuoi impedire anche quella, serve un `check`. Valuta tu se ne vale la pena.

**8. `created_at` su tutte e tre.** Convenzione del modello (2.10).

**9. Se V1 si discosta dal modello dati?** Si aggiorna `modello-dati.md` (brief, "Fatto quando"). Esempio: se scegli la strada C, il tipo della colonna email cambia.

---

## Parte 5 — La sintassi, su un esempio diverso

Ecco una migrazione per una **biblioteca** (un dominio che non useremo): soci, tessere, prestiti. Contiene tutti gli elementi di sintassi che ti servono.

```sql
-- V1__create_library_members.sql

create table library_member (
    id          bigint      generated always as identity,
    card_code   text        not null,
    status      text        not null default 'ACTIVE',
    max_loans   int         not null default 3,
    created_at  timestamptz not null default now(),

    constraint library_member_pk           primary key (id),
    constraint library_member_card_code_uk unique (card_code),
    constraint library_member_card_code_ck check (char_length(card_code) = 8),
    constraint library_member_status_ck    check (status in ('ACTIVE', 'SUSPENDED')),
    constraint library_member_max_loans_ck check (max_loans > 0)
);

-- One locker per member at most: unique on the foreign key.
create table locker_assignment (
    id          bigint      generated always as identity,
    member_id   bigint      not null,
    locker_no   int         not null,
    created_at  timestamptz not null default now(),

    constraint locker_assignment_pk           primary key (id),
    constraint locker_assignment_member_fk    foreign key (member_id) references library_member (id),
    constraint locker_assignment_member_uk    unique (member_id),
    constraint locker_assignment_locker_no_ck check (locker_no > 0)
);
```

Note:
- i vincoli si possono scrivere accanto alla colonna (`id bigint primary key`) oppure in fondo, con un nome (`constraint ... primary key (id)`). In fondo con il nome è più leggibile e dà i vantaggi della 2.8;
- in un file Flyway i commenti SQL iniziano con `--`;
- l'ordine delle tabelle conta: `locker_assignment` punta a `library_member`, che deve esistere già;
- una funzione può stare dentro un `check` (qui `char_length`): vale anche per altre funzioni sul testo.

### Cosa deve contenere la nostra V1

- [ ] File in `backend/src/main/resources/db/migration/`, nome con `V1__` e una descrizione (due trattini bassi)
- [ ] `app_user`, poi `practitioner` e `customer`
- [ ] Su tutte: `id bigint generated always as identity` come chiave primaria, `created_at timestamptz not null default now()`
- [ ] `app_user.email`: unica e in minuscolo, **secondo la strada che hai scelto** (parte 3)
- [ ] `app_user.role`: `not null`, solo `CUSTOMER` o `PRACTITIONER`
- [ ] `practitioner.user_id` e `customer.user_id`: `not null`, chiave esterna verso `app_user`, `unique`
- [ ] `practitioner`: le tre impostazioni con default (24, 90, 30), `not null` e `check` di positività
- [ ] `customer.phone` facoltativo; le altre colonne `not null`
- [ ] Ogni vincolo con un nome
- [ ] Commenti (in inglese) solo dove spiegano un perché
- [ ] Nessuna tabella dei passi successivi

E fuori da V1:
- [ ] `modello-dati.md` aggiornato se V1 se ne discosta (lo faccio io: dimmi cosa è cambiato)
- [ ] La scheda della decisione sull'email (la scrivo io, dopo la tua scelta)

---

## Parte 6 — Verifica ed esperimenti

### La prova

1. Database acceso, poi da `backend/`: `.\mvnw.cmd spring-boot:run`.
2. Nel log di Flyway cerca: `Migrating schema "public" to version "1 - ..."` e `Successfully applied 1 migration`. Il WARN `No migrations found` deve essere sparito.
3. L'applicazione deve partire fino a `Started BookingApplication`. (Hibernate `validate` non ha ancora entità da confrontare: il controllo vero arriverà con `AppUser`.)
4. Guarda il risultato con `psql` (dalla radice del repository, il nome del container lo vedi con `docker ps`):
   ```powershell
   docker exec -it posture-studio-postgres-1 psql -U posture_studio -d posture_studio
   ```
   Dentro `psql`:
   ```
   \dt                                   -- elenco delle tabelle
   \d app_user                           -- struttura della tabella, con i vincoli
   select version, description, checksum, success from flyway_schema_history;
   ```

### Esperimento 1 — I vincoli rifiutano davvero?

Sempre in `psql`, prova a violare **ogni** vincolo a mano, uno alla volta, e leggi il messaggio d'errore: deve riportare il **nome** del vincolo. Per esempio: un ruolo inventato, un'email con maiuscole o duplicata (secondo la strada scelta), un `user_id` inesistente, un preavviso di cancellazione negativo, un secondo professionista per lo stesso utente, un `insert` con un `id` scelto da te.

Alla fine cancella le righe di prova. È esattamente ciò che il test con Testcontainers (punto 7 del brief) farà in automatico: qui lo fai a mano per **vedere** cosa succede.

### Esperimento 2 — Flyway si difende (solo adesso, solo in locale)

1. Con V1 già applicata, aggiungi a V1 una riga innocua, per esempio un commento SQL.
2. Riavvia l'applicazione: deve fermarsi con `Migration checksum mismatch for migration version 1`. Leggi i due checksum nel messaggio.
3. **Togli** la riga aggiunta e riavvia: torna tutto a posto.

È la dimostrazione pratica della 1.4. Non farlo mai dopo che V1 è stata committata e pubblicata: da quel momento la regola è "V1 non si tocca più".

### Errori tipici

| Sintomo | Causa probabile |
|---|---|
| Ancora `No migrations found` | Nome del file sbagliato (un solo `_`), estensione diversa da `.sql`, o file fuori da `db/migration` |
| `relation "app_user" does not exist` durante la migrazione | Ordine delle tabelle: una chiave esterna punta a una tabella creata più sotto |
| `syntax error at or near ...` | Errore SQL: la migrazione viene annullata per intero (1.5). Correggi e riavvia |
| `Migration checksum mismatch` | Hai modificato una migrazione già applicata (1.4) |
| `Validate failed: Detected failed migration` | Raro in PostgreSQL; succede se una migrazione è stata registrata come fallita. In locale: `down -v` e ripartire |

---

## Domande da colloquio

1. Cos'è Flyway e quale problema risolve? Cosa contiene `flyway_schema_history`?
2. Perché una migrazione già eseguita non si modifica? Cosa fai se contiene un errore?
3. `serial`, `identity by default`, `identity always`: differenze?
4. Quando metti una regola nel database e quando la lasci al codice Java? Fai un esempio di regola che un `check` non può esprimere.
5. Come rendi unica un'email senza distinguere maiuscole e minuscole in PostgreSQL? Confronta le alternative.
6. PostgreSQL crea un indice per le chiavi esterne? Perché è importante saperlo?
7. Perché un *value object* come `Email` è un buon caso per un `record`? Un'entità JPA potrebbe essere un `record`?

---

## Documentazione ufficiale

- Flyway, documentazione (sezione "Migrations"): https://documentation.red-gate.com/fd/
- PostgreSQL, vincoli: https://www.postgresql.org/docs/17/ddl-constraints.html
- PostgreSQL, colonne identity (`CREATE TABLE`, sezione `GENERATED ... AS IDENTITY`): https://www.postgresql.org/docs/17/sql-createtable.html
- PostgreSQL, indici su espressioni: https://www.postgresql.org/docs/17/indexes-expressional.html
- PostgreSQL, `citext`: https://www.postgresql.org/docs/17/citext.html
- PostgreSQL, tipi carattere (`text` e `varchar`): https://www.postgresql.org/docs/17/datatype-character.html
- Spring Boot e Flyway: https://docs.spring.io/spring-boot/how-to/data-initialization.html
