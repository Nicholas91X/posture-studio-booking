# Admin e professionisti — guida di studio

Questa guida accompagna il **pezzo 3 del passo 8**. Dopo il pezzo 2 il sistema sa chi c'è e assegna ogni area al suo ruolo, ma `/api/admin/**` è un'area vuota: nessuno può creare un professionista, se non scrivendo SQL a mano. E l'admin stesso non esiste: chi lo crea, se per creare qualcuno bisogna già essere admin?

```
GET    /api/admin/practitioners                   → 200 [ {id, email, firstName, lastName, active}, ... ]
POST   /api/admin/practitioners                   {"email", "firstName", "lastName"} → 201 + Location
GET    /api/admin/practitioners/{id}              → 200
PUT    /api/admin/practitioners/{id}              {"firstName", "lastName"} → 200
POST   /api/admin/practitioners/{id}/deactivate   → 200
POST   /api/admin/practitioners/{id}/activate     → 200
```

Teoria di riferimento: D-021 (ruolo `ADMIN`, un ruolo per utente, professionisti mai cancellati), D-026 (tra moduli solo id), D-029 ("attivo" è lo stato dell'account, si chiede ad `auth` con una domanda su un insieme), D-018 (configurazione dal `.env`), D-050 (gli account disattivati non entrano, la riattivazione è solo esplicita), D-051 (le regole per area, `SessionAccounts` che rilegge l'account a ogni richiesta).

---

## Indice

- Parte 1 — Cosa c'è già, e cosa manca
- Parte 2 — Prima scelta: come nasce il primo admin
- Parte 3 — Seconda scelta: dove sta l'API dell'admin
- Parte 4 — Creare un professionista: due righe, una transazione
- Parte 5 — Terza scelta: chi decide le impostazioni iniziali
- Parte 6 — Disattivare: cosa succede subito, e cosa no
- Parte 7 — L'elenco per l'admin: email e stato chiesti ad `auth`
- Parte 8 — Le scelte piccole
- Parte 9 — Cosa c'è da decidere
- Domande da colloquio

---

## Parte 1 — Cosa c'è già, e cosa manca

Il brief chiede: "l'admin crea un professionista e lo disattiva; un professionista disattivato **non entra** e **non è prenotabile**". Due di queste tre cose esistono già.

- **Non entra.** Il pezzo 1 rifiuta il codice di un account disattivato (`AccountDeactivated`). Il pezzo 2 rilegge l'account a ogni richiesta (`SessionAccounts`), e se lo trova disattivato chiude la sessione. Una sessione già aperta muore alla richiesta successiva.
- **Non è prenotabile.** Dal passo 5, "attivo" vuol dire "account attivo" (D-029). Ogni strada che porta a una prenotazione passa da `CatalogService.serviceOnSale`, che chiama `PractitionerService.requireActive`: gli orari liberi (`ScheduleService`), la richiesta del cliente e la prenotazione dal pannello (`BookingService`). L'elenco pubblico e il catalogo pubblico filtrano allo stesso modo.

Disattivare un professionista, quindi, vuol dire **una riga**: `app_user.active = false`. Il resto segue da solo. È il premio di aver tenuto un solo posto per la verità sullo "stato attivo" (D-029): se `practitioner` avesse avuto una sua colonna `active`, oggi dovremmo ricordarci di aggiornarle tutte e due.

Manca:
1. il **primo admin**;
2. le **API dell'admin**: creare, elencare, rinominare, disattivare e riattivare;
3. i **test** che provano tutto questo dall'esterno, con le sessioni vere del pezzo 2.

Nessuna tabella nuova: `app_user` e `practitioner` ci sono dalla V1.

---

## Parte 2 — Prima scelta: come nasce il primo admin

È il problema dell'uovo e della gallina: solo un admin può creare account speciali, ma il primo admin non lo può creare nessuno. Il brief aggiunge un vincolo: **senza segreti nel repository**.

Prima una distinzione che regge tutta la scelta. **Un'email non è un segreto.** Per entrare come admin non basta conoscerla: serve il codice che arriva **in quella casella** (D-014). Il segreto vero è l'accesso alla casella email, e non sta da nessuna parte nel nostro sistema. Quindi l'email dell'admin è **configurazione**, non credenziale. Però è un dato personale, e un repository pubblico su GitHub non è il posto giusto per lei.

### 2.1 Strada A — Una migrazione Flyway

```sql
-- V7__create_first_admin.sql
insert into app_user (role, email) values ('ADMIN', 'nicholas@...');
```

- **Pro**: semplicissima, e vale in ogni ambiente.
- **Contro**:
  - l'email personale di Nicholas finisce nel repository, per sempre: una migrazione non si cancella e non si modifica (CLAUDE.md);
  - lo stesso admin in sviluppo, nei test e in produzione;
  - per cambiarlo serve un'altra migrazione;
  - ogni database di test ha una riga in più, che nessun test si aspetta.

### 2.2 Strada B — Una variabile d'ambiente letta all'avvio

```
# .env (non versionato)
ADMIN_EMAIL=nicholas@...
```

All'avvio, dopo Flyway, un pezzo di codice legge `ADMIN_EMAIL`. Se è vuota non fa niente. Se c'è, si assicura che esista un account `ADMIN` con quell'email.

- **Pro**:
  - nel repository c'è solo il nome della variabile, in `.env.example`, senza valore;
  - ogni ambiente ha il suo admin, e i test non ne hanno nessuno;
  - è lo stesso meccanismo della password del database e della chiave HMAC (D-018).
- **Contro**:
  - un po' di codice in più;
  - va deciso cosa fare nei casi strani (parte 2.5).

### 2.3 Strada C — Uno script SQL eseguito a mano sul server

La documentazione dice: "dopo il primo avvio, entra nel database ed esegui questo `insert`".

- **Pro**: zero codice.
- **Contro**:
  - un passo manuale, che si dimentica o si sbaglia (un'email con una maiuscola viola il `check` di D-020);
  - in sviluppo va rifatto a ogni database nuovo;
  - non è provato da nessun test.

### 2.4 Strada D — Il primo che entra diventa admin

- **Contro**: alla messa online, chiunque arrivi prima di Nicholas sulla pagina di accesso diventa padrone della piattaforma. È una *race* (gara) che non vogliamo vincere per fortuna. **Scartata.**

### 2.5 Raccomandazione: B, con regole strette

`AdminBootstrap`, nel modulo `auth`, è un `ApplicationRunner`. Spring Boot lo esegue una volta, quando il contesto è pronto, quindi dopo Flyway. Se lancia un'eccezione, l'applicazione **non parte**.

Le regole, caso per caso:

| Situazione all'avvio | Cosa fa |
|---|---|
| `ADMIN_EMAIL` vuota o assente | niente (è il caso dei test) |
| email non valida | l'avvio si ferma: errore di configurazione, meglio saperlo subito (*fail fast*, come la chiave HMAC) |
| nessun account con quell'email | crea l'account `ADMIN`, attivo |
| esiste già un account `ADMIN` con quell'email | niente (ogni riavvio successivo) |
| esiste, ma è `ADMIN` disattivato | niente, con un avviso nel log |
| esiste, ma è `CUSTOMER` o `PRACTITIONER` | l'avvio si ferma |

Due principi dietro la tabella.

1. **Crea soltanto, non modifica mai.** Il codice di avvio non cambia ruoli e non riattiva account. Un codice che "promuove" ad admin l'account esistente di quell'email trasformerebbe un errore di battitura in `.env` in un'escalation di privilegi: il cliente con quell'email diventerebbe admin. Nel dubbio, si ferma e lo dice.
2. **Sicuro con due istanze.** Se un giorno due copie dell'applicazione partono insieme, entrambe vedono "nessun admin" ed entrambe fanno l'`insert`: la seconda violerebbe il vincolo `unique` sull'email. Si scrive allora in un modo che non può fallire:

```sql
insert into app_user (role, email) values ('ADMIN', :email)
on conflict (email) do nothing
```

`on conflict ... do nothing` è PostgreSQL: se l'`insert` violerebbe il vincolo unico su `email`, non fa niente e non dà errore. Dopo, si rilegge l'account con quell'email e si controlla il ruolo. È lo stesso ragionamento di D-047 per i lavori programmati: il database decide, non chi arriva prima.

Domanda: **se l'admin vuole cambiare email?** Cambia `ADMIN_EMAIL` e riavvia: nasce un secondo admin, con l'email nuova. Quello vecchio resta finché non lo si disattiva con un `update` a mano. Succederà forse una volta: non vale un'API.

### 2.6 Perché `ApplicationRunner` e non `@PostConstruct`

- `@PostConstruct` su un bean gira **mentre** Spring sta ancora costruendo il contesto: altri bean potrebbero non essere pronti, e le transazioni con `@Transactional` non funzionano ancora.
- `ApplicationRunner` gira quando tutto è pronto, e prima che l'applicazione si dichiari "pronta" (`ApplicationReadyEvent`). Un'eccezione lì ferma l'avvio con un messaggio chiaro.

La transazione si apre con un `TransactionTemplate`, come in `LoginCodeService` (guida 24). `run()` è chiamato da Spring, ma un `@Transactional` su un metodo chiamato da `run()` dentro la stessa classe non avrebbe effetto: è un'auto-invocazione, che non passa dal *proxy*. È lo stesso tranello visto nella guida 21.

---

## Parte 3 — Seconda scelta: dove sta l'API dell'admin

### 3.1 Strada A — Nel modulo `practitioner`

`PractitionerAdminController` sotto `/api/admin/practitioners`, nuovi metodi in `PractitionerService`. La creazione chiede ad `AccountService` di creare l'account, poi salva il professionista.

- **Pro**:
  - le dipendenze non cambiano: `practitioner → auth` esiste dal passo 5 (D-029);
  - l'entità `Practitioner` resta *package-private*: solo il suo modulo la crea, che è proprio il motivo per cui oggi non ha un costruttore pubblico ("practitioners are created by the admin");
  - è lo stesso schema del pannello: un controller per area dentro il modulo che possiede i dati (`CatalogPanelController` e `CatalogPublicController` stanno entrambi in `catalog`).

### 3.2 Strada B — Un modulo `admin`

Un modulo che dipende da `auth` e `practitioner` e orchestra le due creazioni.

- **Pro**: tutto ciò che fa l'admin sta in un posto, utile se un giorno l'admin farà molte cose diverse.
- **Contro**:
  - per creare la riga `practitioner` il modulo `admin` avrebbe bisogno di un metodo pubblico "crea un professionista per questo account" in `PractitionerService`, quindi la logica sta comunque lì;
  - un modulo che non possiede dati, solo per inoltrare: oggi è un passaggio in più senza un guadagno.

### 3.3 Raccomandazione: A

L'area (`/api/admin`) è una questione di **sicurezza**, che le regole per prefisso risolvono già. Il **modulo** è una questione di **chi possiede i dati**: i professionisti sono di `practitioner`. Se un giorno l'admin gestirà dieci cose diverse, ognuna starà nel modulo dei suoi dati.

---

## Parte 4 — Creare un professionista: due righe, una transazione

Un professionista sono due righe: l'account (`app_user`, di `auth`) e il professionista (`practitioner`). Devono nascere **insieme o per niente**. Un account `PRACTITIONER` senza la sua riga `practitioner` potrebbe entrare ma non avrebbe un pannello (`SessionCurrentPractitioner` risponderebbe 401). Una riga `practitioner` senza account non può esistere: c'è la *foreign key*.

```java
@Transactional
public AdminPractitionerView create(NewPractitioner request) {
    long userId = accounts.createPractitionerAccount(request.email());   // auth, stessa transazione
    Practitioner practitioner = repository.save(new Practitioner(userId, request.firstName(), request.lastName()));
    return view(practitioner, ...);
}
```

`AccountService.createPractitionerAccount` ha `@Transactional` (propagazione `REQUIRED`, quella di default). Chiamato da dentro una transazione, **ci entra**, non ne apre una sua. Se il `save` del professionista fallisce, il *rollback* annulla anche l'account. È esattamente `customerAccountFor` del passo 7 (D-048).

### 4.1 Un'email già usata

D-021: **un ruolo per utente**. Se l'email ha già un account, di qualunque ruolo, la creazione si rifiuta con 409 `EMAIL_ALREADY_IN_USE`:

- **un cliente**: per esempio una professionista che prima era cliente dello studio. Non si "promuove": servirebbe un utente con due ruoli, che D-021 esclude. Userà un'altra email per il pannello;
- **un professionista**, anche disattivato: per farlo tornare c'è "riattiva", non "crea";
- **l'admin**: l'admin non è un professionista (D-021).

L'API dell'admin non ha il problema dell'enumerazione (guida 24): chi la usa è l'admin, e deve sapere perché la creazione non va.

E se l'admin preme due volte il pulsante? Le due richieste arrivano quasi insieme. Entrambe vedono "email libera", entrambe fanno l'`insert`, e la seconda viola il vincolo `unique`. Il database ha l'ultima parola, ed `ApiExceptionHandler` traduce già `DataIntegrityViolationException` in 409 `DATA_CONFLICT`. Nessun doppione e nessun 500: non serve altro.

### 4.2 Il professionista appena creato

Ha le impostazioni iniziali (parte 5) e **nessun orario di lavoro**. Quindi compare nell'elenco pubblico, ma non ha orari liberi: nessuno può prenotarlo finché non imposta le sue fasce dal pannello. Va bene così: è la stessa situazione di un professionista che non ha ancora lavorato.

Per entrare, il professionista chiede il codice con la sua email, come chiunque altro (pezzo 1). Il sistema trova l'account `PRACTITIONER` e lo fa entrare nel pannello. Nessuna email di benvenuto per ora: le notifiche sono del pezzo 4, e lo studio è piccolo. Glielo dice l'admin.

---

## Parte 5 — Terza scelta: chi decide le impostazioni iniziali

`practitioner` ha otto impostazioni (preavviso di cancellazione, scatto, orizzonte, limite, approvazione...), ognuna con un **default nel database** (V1, V3, V4: per esempio `slot_step_minutes default 60`, `booking_approval default 'MANUAL'`).

Il problema: nell'entità sono campi `int`. Un `new Practitioner(...)` in Java li ha tutti a **0**. Hibernate scrive nell'`insert` tutte le colonne mappate, quindi manda `0` e il default del database non scatta mai. Anzi, lo zero viola i `check` (`> 0`, `in (30, 60)`), e l'`insert` fallisce.

### 5.1 Strada A — I valori iniziali anche in Java

```java
static final PractitionerSettings INITIAL = new PractitionerSettings(24, 90, 60, 24, 21, 4, MANUAL, 12);
```

- **Pro**: chiaro, si legge in Java.
- **Contro**: **due verità** sullo stesso fatto, una nella migrazione e una in Java, che prima o poi divergono. È lo stesso motivo per cui D-029 ha scartato una colonna `active` anche in `practitioner`. Un test che le confronta le terrebbe allineate, ma sarebbe un cerotto.

### 5.2 Strada B — Il database decide, Hibernate rilegge: `@Generated`

```java
@Generated            // event = INSERT di default
@Column
private int slotStepMinutes;
```

`@Generated` di Hibernate (lo usiamo già su `createdAt`) dice due cose:
- **"questa colonna la riempie il database all'`insert`"**: Hibernate la lascia fuori dall'`insert`, e scatta il default;
- **"dopo l'`insert`, rileggila"**: Hibernate fa una `select` e mette nell'entità il valore vero, così la stessa transazione non vede degli zeri.

Il punto da verificare: `@Generated` ha un attributo `writable`, che vale `false` di default. Un campo non scrivibile potrebbe sparire anche dagli `update`, e allora cambiare le impostazioni dal pannello non scriverebbe più niente. Il test esistente `PractitionerPanelApiTest.settingsAreReadAndChanged` lo scoprirebbe subito: cambia le impostazioni e le rilegge. Si prova, e si guarda.

- **Pro**: una sola verità, nel database. Il codice dice esplicitamente "questi valori iniziali non sono miei".
- **Contro**: va verificato come si comporta Hibernate 7.4 negli `update`.

### 5.3 Strada C — Un `insert` scritto a mano

```sql
insert into practitioner (user_id, first_name, last_name) values (?, ?, ?) returning id
```

Le colonne non nominate prendono il default.

- **Pro**: una sola verità, nessuna sorpresa di Hibernate.
- **Contro**: l'entità non si crea mai con `new`. La creazione scavalca JPA, e il professionista va poi riletto.

### 5.4 Raccomandazione: B, se il test lo conferma

B è la soluzione che dice la cosa giusta nel posto giusto: "il default è del database". Se Hibernate escludesse quei campi dagli `update`, si passa a C, e la parte "Com'è andata" lo registra.

---

## Parte 6 — Disattivare: cosa succede subito, e cosa no

### 6.1 Cosa vuol dire

`POST /api/admin/practitioners/{id}/deactivate` trova il professionista e chiede ad `auth` di disattivare il suo account (`AccountService.deactivate(userId)`). Una riga, `app_user.active = false`. Il professionista non si cancella mai (D-021): prenotazioni, pagamenti e crediti restano legati a lui.

**Riattivare** è l'operazione opposta, `POST .../activate`. È l'unica strada per riattivare un professionista: il codice di accesso non riattiva mai (D-050).

Entrambe sono **idempotenti**: disattivare un professionista già disattivato non è un errore, risponde 200 con lo stato attuale. Lo stesso per i servizi del catalogo (passo 5). Il motivo: il risultato voluto ("disattivato") c'è già, e un admin che preme due volte non deve vedere un errore.

### 6.2 Cosa succede subito

| Dove | Effetto | Chi lo fa |
|---|---|---|
| Accesso | nuovo codice rifiutato (`AccountDeactivated`) | pezzo 1 |
| Sessioni aperte | chiuse alla richiesta successiva | `SessionAccounts`, pezzo 2 |
| Elenco pubblico | non compare | `PractitionerService.listActive` |
| Catalogo, orari liberi | 404 | `requireActive`, `serviceOnSale` |
| Nuove prenotazioni (cliente e pannello) | 404 | `serviceOnSale` |

Tutto questo **senza una riga di codice nuovo**: il test della parte 8 lo prova dall'esterno.

### 6.3 Cosa non succede: le prenotazioni già fatte

Un professionista disattivato può avere:
- **richieste in attesa di approvazione**: non può più approvarle, perché non entra. Scadono da sole quando arriva la loro scadenza (lavoro programmato, D-047), e il blocco sulla carta si scioglie (D-031). Il cliente non resta appeso;
- **prenotazioni confermate nel futuro**: restano. Disdirle è una "cancellazione da parte dello studio", che è un **punto aperto prima del passo 11** (requisiti): rimborso o credito? con quale avviso al cliente? Non lo inventiamo qui.

Si potrebbe **rifiutare** la disattivazione finché ci sono prenotazioni future. Raccomando di **no**, per due motivi:
1. la disattivazione serve anche in **urgenza**, per esempio un account compromesso: deve funzionare sempre, subito;
2. `practitioner` dovrebbe chiedere a `booking`, ma `booking` dipende già da `practitioner` (tramite `catalog`): servirebbe un'altra interfaccia rovesciata come `OccupiedTime` (D-044), per una regola che non abbiamo ancora deciso.

Si annota per il passo 11 e per il pannello dell'admin (passo 13): mostrare all'admin le prenotazioni future di un professionista prima che lo disattivi.

---

## Parte 7 — L'elenco per l'admin: email e stato chiesti ad `auth`

L'admin vuole vedere per ogni professionista anche l'**email** e se è **attivo**. Entrambe stanno in `app_user`, che è di `auth`: `practitioner` non la legge (D-002, D-029).

Come per `activeAmong`, si chiede **una volta sola, per un insieme di id**:

```java
// auth
public record AccountSummary(String email, boolean active) {}

public Map<Long, AccountSummary> summariesOf(Collection<Long> userIds)   // una query
```

`PractitionerService.listForAdmin()` fa due query in tutto, qualunque sia il numero dei professionisti: tutti i professionisti, poi una domanda ad `auth`. Un metodo "dammi l'email di questo id", chiamato in un ciclo, sarebbe un N+1 tra moduli (D-029).

Perché un `record` nuovo e non `AppUser`? Perché `AppUser` è un'entità: fuori da `auth` qualcuno potrebbe chiamarci `deactivate()` o `setPhone()` senza passare dalle regole del modulo. Un `record` è una **fotografia**: si legge e basta.

Lo stesso `summariesOf`, con un id solo, serve per la risposta di creazione, rinomina e disattivazione.

---

## Parte 8 — Le scelte piccole

- **Rinominare sì, cambiare email no.** `PUT /{id}` cambia nome e cognome, cioè il nome che vedono i clienti. Cambiare l'email vuol dire cambiare **come si entra**, e porta domande proprie: si verifica la nuova casella? cosa succede alle sessioni aperte? Se servirà, sarà una scelta a parte. Per ora: disattivare e creare di nuovo, o un `update` a mano.
- **`GET /{id}`** esiste perché il 201 della creazione ha un `Location` che deve portare da qualche parte.
- **404 per un id inesistente**, come nel resto del sistema (D-026).
- **Le impostazioni non stanno nell'API dell'admin.** Le cambia il professionista dal suo pannello. L'admin crea, rinomina, disattiva.
- **Niente registro "chi ha fatto cosa"**: l'admin è uno solo, Nicholas. Se gli admin diventeranno più di uno, servirà.
- **L'admin non disattiva sé stesso**: non esiste un'API per gli account admin, quindi non può succedere.
- **Validazione**: `NewPractitioner(@NotBlank @ValidEmail email, @NotBlank @Size(max = 100) firstName, @NotBlank @Size(max = 100) lastName)`, con gli stessi limiti dei nomi del cliente (`ProfileNames`). `@ValidEmail` delega a `Email` (guida 22).
- **`ConfidentialityTest`** oggi si aspetta 404 dall'admin su `/api/admin/practitioners`, perché il controller non esisteva: diventa 200.
- **`.env.example`**: `ADMIN_EMAIL=` senza valore, con un commento. Nel proprio `.env` Nicholas mette la sua email.

### Test

- `AdminBootstrapTest`: crea l'admin, non lo duplica al secondo avvio, non tocca un admin disattivato, ferma l'avvio per l'email di un cliente, non fa niente con la variabile vuota, normalizza le maiuscole.
- `AccountServiceTest`: `createPractitionerAccount` (nuova, email già usata), `summariesOf`, `deactivate` e `activate`.
- `PractitionerAdminApiTest`, con le sessioni vere di `TestSessions`:
  - la creazione: 201, `Location`, le due righe, le impostazioni iniziali lette dal pannello del nuovo professionista (la verifica di `@Generated`);
  - le email già usate (cliente, professionista, admin) e un corpo non valido;
  - l'elenco con email e stato, la rinomina, un id inesistente;
  - la disattivazione vista **da fuori**: la sessione del professionista non entra più nel pannello, e non compare in elenco pubblico, catalogo e orari liberi. Poi la riattivazione, e tutto torna;
  - cliente e professionista rifiutati con 403, anonimo con 401.

---

## Parte 9 — Cosa c'è da decidere

| # | Scelta | Raccomandazione |
|---|---|---|
| 1 | Primo admin | `ADMIN_EMAIL` letta all'avvio da `AdminBootstrap` (`ApplicationRunner`): crea soltanto, con `on conflict do nothing`; un ruolo diverso ferma l'avvio |
| 2 | Dove sta l'API | Nel modulo `practitioner` (`PractitionerAdminController`), con l'account chiesto ad `AccountService` nella stessa transazione |
| 3 | Impostazioni iniziali | I default del database, con `@Generated`; se Hibernate non scrive più gli `update`, un `insert` nativo |
| 4 | Email già usata | 409 `EMAIL_ALREADY_IN_USE` per qualunque ruolo; nessuna promozione |
| 5 | Disattivazione | Solo l'account; idempotente; le prenotazioni future restano (passo 11) |
| 6 | Elenco | `AccountService.summariesOf` su un insieme di id, con un `record` `AccountSummary` |
| 7 | Modifica | Solo nome e cognome; l'email no |

---

## Parte 10 — Com'è andata (implementazione)

- Prese tutte le raccomandazioni della parte 9 (D-052).
- **`@Generated`, provato prima di fidarsi.** Un test di prova, poi cancellato, ha creato un professionista e ne ha cambiato le impostazioni, stampando l'SQL. Con `@Generated` lasciato com'è (`event = INSERT`, `writable = false`), Hibernate 7.4:
  - toglie le otto colonne dall'`insert`, quindi scattano i default del database;
  - le rilegge **nello stesso comando**, con `insert ... returning id, approval_window_hours, ...`: PostgreSQL restituisce le colonne generate, senza una `select` in più;
  - le scrive ancora negli `update` di `changeSettings`. Il dubbio della parte 5.2 era infondato: `writable` riguarda il valore Java all'`insert`, non gli `update`.

  Due test lo tengono fermo: le impostazioni di un professionista appena creato (in memoria e nella riga) e un cambio subito dopo la creazione. Senza `@Generated` su una sola colonna, l'`insert` manda lo zero, il `check` lo rifiuta, e 7 test su 9 dell'API dell'admin falliscono.
- **`AdminBootstrap` provato due volte.** Gli esiti, caso per caso, in `AdminBootstrapTest`: il costruttore riceve l'email, quindi ogni test si costruisce il suo. Poi `AdminBootstrapStartupTest` fa un avvio vero di Spring con `auth.admin-email` impostata, e trova l'admin già nel database. Ha un contesto suo, quindi un container PostgreSQL suo: la riga dell'admin, scritta con un commit vero, non arriva agli altri test.
- **`ConfidentialityTest`**: l'admin su `/api/admin/practitioners` ora riceve 200, non più 404.
- **Prova che i test sanno fallire**: tolto `@Generated` da una colonna, tolto il controllo dell'email già usata, tolto il controllo del ruolo all'avvio, tolta la disattivazione dell'account. Ogni volta fallisce il test che protegge quella difesa.
- **Uno script che non provava niente.** La prima prova delle mutazioni l'ha fatta uno script Python, che ha risposto "nessun test fallito" per tutte e quattro. Era falso: lo script non riusciva a lanciare Maven su Windows, e "nessun fallimento" voleva dire "nessun test eseguito". Rifatta a mano dalla shell, ogni mutazione è stata presa. Vale anche per i test: un risultato verde che arriva troppo in fretta va guardato.
- 29 test nuovi, 534 in tutto (`mvnw clean test`). Nessuna prova a mano con Tomcat in questo pezzo: per l'avvio basta `AdminBootstrapStartupTest`, e l'API passa per la stessa catena di filtri del pezzo 2.

---

## Domande da colloquio

### Domanda 1: Come crei il primo amministratore di un sistema senza mettere credenziali nel codice?
**Risposta modello.** Distinguo l'identità dal segreto. Qui si entra con un codice mandato all'email, quindi l'email dell'admin è configurazione, non credenziale: la leggo da una variabile d'ambiente all'avvio e mi assicuro che l'account esista. Il codice di avvio crea soltanto e non promuove mai un account esistente, perché un errore di configurazione diventerebbe un'escalation di privilegi. Lo scrivo idempotente, con `insert ... on conflict do nothing`, così regge riavvii e istanze multiple. Con le password sarebbe diverso: si genera una password temporanea da cambiare al primo accesso, o un link monouso.

### Domanda 2: Due righe in due moduli diversi devono nascere insieme. Come lo garantisci?
**Risposta modello.** Con una sola transazione. Il servizio che orchestra ha `@Transactional`, e il servizio dell'altro modulo, con la propagazione di default `REQUIRED`, entra nella stessa transazione invece di aprirne una sua. Se la seconda scrittura fallisce, il *rollback* annulla anche la prima. Funziona perché i moduli stanno nella stessa applicazione e nello stesso database: con due microservizi servirebbero una *saga* o un'*outbox*.

### Domanda 3: Cosa fa `on conflict do nothing` in PostgreSQL, e quando lo usi?
**Risposta modello.** Rende un `insert` idempotente rispetto a un vincolo unico: se la riga violerebbe il vincolo, PostgreSQL non la scrive e non dà errore. Lo uso quando più processi possono provare a creare la stessa cosa nello stesso momento. Il classico "controllo se esiste, poi inserisco" ha una *race* tra il controllo e l'`insert`, che solo il database può chiudere. Dopo, rileggo la riga per sapere cosa c'è davvero.

### Domanda 4: Il default di una colonna sta nel database, ma l'entità JPA ha un campo `int`. Cosa succede all'`insert`?
**Risposta modello.** Hibernate scrive tutte le colonne mappate, quindi manda lo zero del campo `int`, e il default del database non scatta. Le strade: ripetere i valori in Java (due verità da tenere allineate); marcare il campo con `@Generated`, così Hibernate lo lascia fuori dall'`insert` e lo rilegge dopo; oppure un `insert` nativo. Con `@Generated` va verificato che il campo resti scrivibile negli `update`.

### Domanda 5: Perché "disattivare" invece di cancellare un utente?
**Risposta modello.** Perché la sua storia, cioè prenotazioni, pagamenti e crediti, deve restare coerente e consultabile: con una cancellazione vera, le *foreign key* o la impediscono o si portano via dati che servono, anche per obblighi fiscali. Una colonna `active` (*soft delete*) blocca l'accesso e nasconde l'utente, e si può tornare indietro. Il prezzo: ogni lettura "pubblica" deve filtrare gli attivi, quindi conviene che la domanda "è attivo?" abbia un solo posto nel codice. Per il GDPR, dove serve, si anonimizza invece di cancellare.

### Domanda 6: Perché un'eccezione in un `ApplicationRunner` ferma l'avvio, e perché lo vuoi?
**Risposta modello.** Spring Boot esegue i *runner* alla fine di `SpringApplication.run`, e un'eccezione lì fa fallire l'avvio. Per un errore di configurazione è quello che voglio (*fail fast*): un'applicazione che parte ma senza admin, o con l'admin sbagliato, è un problema scoperto tardi, magari da un utente. Meglio un avvio fallito con un messaggio chiaro, nel momento in cui qualcuno sta guardando il deploy.

---

## Documentazione ufficiale

- Spring Boot, `ApplicationRunner` e `CommandLineRunner`: https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.command-line-runner
- PostgreSQL, `INSERT ... ON CONFLICT`: https://www.postgresql.org/docs/17/sql-insert.html#SQL-ON-CONFLICT
- Hibernate ORM 7, valori generati dal database (`@Generated`): https://docs.jboss.org/hibernate/orm/7.0/userguide/html_single/Hibernate_User_Guide.html#mapping-generated
- Spring Framework, propagazione delle transazioni: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html
