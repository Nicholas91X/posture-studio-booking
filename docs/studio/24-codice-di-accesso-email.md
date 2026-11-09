# Il codice di accesso via email — guida di studio

Questa guida accompagna il **pezzo 1 del passo 8**. L'utente scrive la sua email, riceve un codice di 6 cifre e lo inserisce. In questo pezzo si costruiscono la **richiesta** del codice e la sua **verifica** nel servizio; la sessione con il cookie e Spring Security arrivano nel pezzo 2.

```
POST /api/public/auth/login-code
{"email": "mario.rossi@example.com"}
→ 202 Accepted, sempre la stessa risposta
```

Teoria di riferimento: D-014 (accesso con codice: 6 cifre, 10 minuti, 5 tentativi, solo l'hash), D-020 (email normalizzata), D-021 (un ruolo per utente), D-029 (`/api/public/**`, `auth` non dipende da nessuno), D-044 (inversione delle dipendenze), D-046 (lucchetto pessimistico), D-047 (lavori programmati).

---

## Indice

- Parte 0 — Il passo 8 in breve
- Parte 1 — Cosa si costruisce
- Parte 2 — Prima scelta: la tabella `login_code`, pensando già all'SMS
- Parte 3 — Seconda scelta: quale hash (perché l'hash da solo non basta)
- Parte 4 — Terza scelta: i tentativi sbagliati devono restare scritti
- Parte 5 — Quarta scelta: nessuno deve scoprire chi è cliente (enumerazione) e il primo accesso
- Parte 6 — Quinta scelta: il freno agli abusi (*rate limiting*)
- Parte 7 — Sesta scelta: chi spedisce il codice
- Parte 8 — Le scelte piccole
- Parte 9 — I test
- Parte 10 — Cosa c'è da decidere
- Domande da colloquio

---

## Parte 0 — Il passo 8 in breve

| Pezzo | Cosa | Dipende da |
|---|---|---|
| 1 | Codice via email: richiesta, verifica, limiti | — |
| 2 | Sessione, Spring Security, ruoli, via i due DevHeader | 1 |
| 3 | Admin e professionisti | 2 |
| 4 | Notifiche email sugli eventi del passo 7 | 1 (l'invio) |
| 5 | Notifiche push, con riserva email | 4 |
| 6 | Codice via SMS da numero verificato | 1, 2 |

**Perché Spring Security non entra nel pezzo 1.** Appena si aggiunge la dipendenza `spring-boot-starter-security`, Spring chiude **tutte** le API: tutti i 437 test che oggi usano gli header `X-Practitioner-Id` e `X-Customer-Id` diventano rossi nello stesso istante. Il pezzo 1 non ha bisogno della sessione: produce un servizio che risponde "questo codice è giusto, l'utente è il numero 42". Il pezzo 2 prende quel numero e ci costruisce sopra la sessione. Così ogni pezzo resta un commit funzionante.

---

## Parte 1 — Cosa si costruisce

| Classe | Dove | Cosa fa |
|---|---|---|
| Migrazione `V5__create_login_code.sql` | — | La tabella dei codici |
| `LoginCode` (entità), `LoginCodeRepository` | `auth` | Un codice chiesto: a chi, quando, quanti tentativi, se usato |
| `LoginCodeHasher` | `auth` | Calcola l'impronta del codice (parte 3) e la confronta a tempo costante |
| `LoginCodeService.request(email, ip)` | `auth` | Controlla i limiti, genera il codice, salva l'impronta, fa spedire il codice |
| `LoginCodeService.verify(email, code)` | `auth` | Risponde con un risultato (parte 4): valido (con l'utente), sbagliato o scaduto |
| `LoginCodeSender` (interfaccia) | `auth` | "Spedisci questo codice a questo indirizzo"; la implementa `notification` (parte 7) |
| `EmailLoginCodeSender` | `notification` | La prima email del sistema, verso Mailpit in sviluppo |
| `POST /api/public/auth/login-code` | `auth` | La richiesta del codice; 202 sempre uguale, 429 oltre i limiti |
| Pulizia dei codici vecchi | `auth` | Un lavoro programmato che cancella le righe con più di 24 ore |

L'*endpoint* della verifica (`POST /api/public/auth/session`, che crea la sessione) è del pezzo 2. Nel pezzo 1 la verifica si prova dai test del servizio.

---

## Parte 2 — Prima scelta: la tabella `login_code`, pensando già all'SMS

### 2.1 Il problema

`modello-dati.md` progetta `login_code` con una colonna `email`. Ma il pezzo 6 aggiunge il codice via **SMS**, con le stesse regole (6 cifre, 10 minuti, 5 tentativi, hash, limiti). La tabella non esiste ancora: la V1 ha creato solo `app_user`, `customer` e `practitioner`, quindi la V5 si può progettare liberamente.

### 2.2 Strada A — Una tabella sola, con canale e destinazione

```sql
create table login_code (
    id            bigint       generated always as identity,
    channel       text         not null,  -- 'EMAIL'; il pezzo 6 aggiunge 'SMS'
    destination   text         not null,  -- l'email normalizzata (D-020); poi anche il numero in E.164
    code_hash     text         not null,
    requested_from inet        not null,  -- per il limite per IP (parte 6)
    created_at    timestamptz  not null,  -- scritto da Java con il Clock, come booking (D-046)
    expires_at    timestamptz  not null,
    attempts      integer      not null default 0,
    used_at       timestamptz,

    constraint login_code_pk           primary key (id),
    constraint login_code_channel_ck   check (channel in ('EMAIL')),
    constraint login_code_attempts_ck  check (attempts between 0 and 5),
    constraint login_code_expiry_ck    check (expires_at > created_at)
);
create index login_code_destination_idx on login_code (channel, destination, created_at);
create index login_code_ip_idx on login_code (requested_ip, created_at);
```

Una sola logica per richiesta, verifica, tentativi e limiti, qualunque sia il canale. Il pezzo 6 allarga il `check` del canale con una migrazione nuova, come prevede D-042 (`check` con i soli valori che il codice sa produrre).

### 2.3 Strada B — Una tabella per canale

`email_login_code` e `sms_login_code`: due entità, due repository e la stessa logica scritta due volte, o un'astrazione in Java per riunirle. Si guadagna solo una colonna con un nome più preciso.

### 2.4 Strada C — Legare il codice all'utente (`user_id`) invece che alla destinazione

È pulita per l'SMS, dove il numero è sempre di un utente che esiste già. Ma per l'email non funziona: chi accede per la prima volta **non ha ancora un account** (parte 5), quindi non c'è un `user_id` a cui legare il codice.

### 2.5 Raccomandazione

**Strada A.** Due note:
- **Lo scopo del codice.** Nel pezzo 6 un codice SMS servirà anche a **verificare** un numero appena aggiunto al profilo, non solo ad accedere. Un codice di verifica non deve mai aprire una sessione, e viceversa. Quindi il pezzo 6 aggiungerà probabilmente una colonna `purpose` (`LOGIN`, `PHONE_VERIFICATION`) con default `LOGIN`. Non la si mette ora: oggi nessun codice la produrrebbe.
- **`inet`** è il tipo di PostgreSQL per gli indirizzi IP: valida il formato e permette di raggruppare gli indirizzi IPv6 per rete (parte 6.4). In Hibernate 7 si mappa su `java.net.InetAddress` con `@JdbcTypeCode(SqlTypes.INET)`. Va **verificato con un test di schema**, come per `tstzrange`. Se `ddl-auto=validate` rifiuta il tipo, si ripiega su `text`.

---

## Parte 3 — Seconda scelta: quale hash

### 3.1 Cos'è un hash, e perché non basta

Un **hash** è un'impronta: da `"482913"` una funzione come SHA-256 calcola una sequenza fissa di byte. Dall'impronta non si risale al codice, se non **provando tutti i codici possibili** e confrontando le impronte. È questo il punto: i codici a 6 cifre sono solo **un milione**. Chi legge la tabella (un backup rubato, un errore di configurazione) calcola un milione di SHA-256 in **meno di un secondo** e trova il codice valido in quei 10 minuti. Un semplice SHA-256 qui protegge poco.

### 3.2 Strada A — BCrypt (o Argon2), gli hash "lenti" delle password

Sono costruiti per essere lenti, circa 100 ms per calcolo. Un milione di tentativi richiede circa 28 ore su un processore, ma solo minuti su una scheda grafica. La lentezza aiuta con le password, dove le possibilità sono miliardi. Con un milione di possibilità non basta, e si pagano 100 ms a ogni verifica.

### 3.3 Strada B — HMAC con una chiave segreta (*pepper*)

**HMAC** (*Hash-based Message Authentication Code*, RFC 2104) è un hash calcolato **insieme a una chiave segreta**: `HMAC-SHA256(chiave, "mario.rossi@example.com:482913")`. Senza la chiave, le impronte nella tabella sono inutili: provare un milione di codici non serve a niente, perché non si sa calcolare l'impronta giusta.

- La chiave sta **fuori dal database**, nel `.env` (`AUTH_CODE_HMAC_KEY`, in `.env.example` senza valore). Chi ruba un backup non ce l'ha.
- Nell'impronta entra anche la **destinazione**: due persone con lo stesso codice hanno impronte diverse, e un'impronta copiata da una riga all'altra non vale.
- È veloce (microsecondi) e sta nel JDK: `javax.crypto.Mac` con `"HmacSHA256"`, senza librerie. Un `Mac` non è *thread-safe*, cioè non si può usare da più richieste contemporanee: se ne crea uno a ogni calcolo, costa pochissimo.
- **Cambiare la chiave** (rotazione) costa quasi niente: i codici in giro valgono 10 minuti, e al peggio qualcuno ne chiede uno nuovo.

### 3.4 Il confronto a tempo costante

`a.equals(b)` tra due stringhe si ferma **al primo carattere diverso**: più caratteri iniziali sono giusti, più il confronto dura. Misurando i tempi con migliaia di richieste, in teoria si indovina un'impronta un carattere alla volta. È un *timing attack* (attacco sui tempi). `MessageDigest.isEqual(byte[], byte[])` confronta **sempre tutti i byte**, quindi dura sempre lo stesso tempo.

Detto onestamente: con 5 tentativi per codice e le variazioni dei tempi di rete, qui un attacco sui tempi è praticamente impossibile. Si usa lo stesso, perché non costa niente, è la pratica corretta e diventa indispensabile dove i tentativi non sono limitati (token, firme dei webhook: al passo 9 la libreria di Stripe fa lo stesso confronto). Ai colloqui è una domanda classica.

La riga si cerca **per destinazione**, poi l'impronta si confronta in Java con `isEqual`. Non si cerca con `where code_hash = ?`, perché il confronto del database non è a tempo costante.

### 3.5 Il codice stesso

`SecureRandom`, non `Random`. `Random` è prevedibile: da pochi valori osservati si calcolano i successivi. Il codice è `String.format("%06d", random.nextInt(1_000_000))`. Il formato con lo zero davanti conta: `000123` è un codice valido, e un test lo deve provare.

### 3.6 Raccomandazione

**HMAC-SHA256 con chiave nel `.env`**, destinazione dentro l'impronta, confronto con `MessageDigest.isEqual`. In `modello-dati.md` la frase "salvato solo come hash" diventa "salvato solo come HMAC". Il segreto nuovo segue le regole di sempre: mai nel repository, e nei test una chiave fissa di prova.

---

## Parte 4 — Terza scelta: i tentativi sbagliati devono restare scritti

### 4.1 La trappola

Il modo più naturale di scrivere la verifica è sbagliato:

```java
@Transactional
public long verify(String email, String code) {
    LoginCode lc = latestFor(email);
    if (!hasher.matches(lc, code)) {
        lc.failedAttempt();                     // attempts + 1
        throw new BusinessRuleException(...);   // ← rollback: attempts + 1 viene annullato
    }
    ...
}
```

Un'eccezione non controllata (`RuntimeException`) dentro `@Transactional` provoca il **rollback**: il database torna com'era prima della transazione, **compreso il tentativo appena contato**. Il contatore resta a zero per sempre, e il limite di 5 tentativi non esiste: si possono provare tutti i milioni di codici. Il test che lo smaschera è semplice: 6 codici sbagliati di fila, poi quello giusto deve essere rifiutato.

### 4.2 Strada A — Un risultato invece di un'eccezione: `sealed interface`

La verifica non lancia, **restituisce** cosa è successo. La transazione si chiude normalmente, con un commit, e il tentativo resta scritto.

```java
public sealed interface CodeCheck {
    record Valid(long userId, boolean newAccount) implements CodeCheck {}
    record Wrong() implements CodeCheck {}            // si può riprovare
    record Expired() implements CodeCheck {}          // scaduto, usato o 5 tentativi: serve un codice nuovo
    record AccountDeactivated() implements CodeCheck {}
}
```

Chi lo riceve (il controller del pezzo 2) usa uno `switch` con i *record pattern* di Java 21, senza `default`:

```java
return switch (check) {
    case Valid(long userId, boolean newAccount) -> startSession(userId, newAccount);
    case Wrong() -> problem(UNAUTHORIZED, "CODE_WRONG");
    case Expired() -> problem(UNAUTHORIZED, "CODE_EXPIRED");
    case AccountDeactivated() -> problem(FORBIDDEN, "ACCOUNT_DEACTIVATED");
};
```

È la stessa idea di `BookingEvent` (D-046): con `sealed` il compilatore conosce tutti i casi, e se domani se ne aggiunge uno, ogni `switch` che lo dimentica smette di compilare. Il *record pattern* `Valid(long userId, boolean newAccount)` estrae i campi direttamente nel `case`, senza un `instanceof` e un cast.

È un caso da manuale per i risultati espliciti: un codice sbagliato **non è un'eccezione**, è uno degli esiti normali di una verifica.

### 4.3 Strada B — `@Transactional(noRollbackFor = WrongCodeException.class)`

Funziona, ma la correttezza dipende da un attributo dell'annotazione che chiunque può togliere "per pulizia" senza accorgersi di niente. E anche gli esiti normali restano eccezioni.

### 4.4 Strada C — Il contatore in una transazione separata (`REQUIRES_NEW`)

È corretta, ma usa due transazioni per un'operazione sola, e il lucchetto (4.5) andrebbe preso due volte.

### 4.5 Due tentativi nello stesso istante

Un attaccante può mandare 20 tentativi **in parallelo**. Senza protezione, tutte le richieste leggono `attempts = 3`, tutte scrivono `4`: un aggiornamento perso (*lost update*), e i 5 tentativi diventano molti di più. La soluzione è quella delle transizioni delle prenotazioni (D-046): **lucchetto pessimistico** sulla riga del codice (`@Lock(PESSIMISTIC_WRITE)`, cioè `select ... for update`). Le verifiche dello stesso codice vanno una alla volta, e ognuna vede il contatore aggiornato dalla precedente.

### 4.6 Quale codice si controlla

Vale solo **l'ultimo codice chiesto** per quella destinazione. Se l'ultimo è scaduto, usato o esaurito, la risposta è `Expired`, anche se un codice più vecchio sarebbe ancora valido. Chiedere un codice nuovo rende quindi inutili i precedenti, senza bisogno di aggiornarli. Così c'è sempre un solo codice "vivo" per persona.

### 4.7 Raccomandazione

**Strada A**, con il lucchetto sulla riga e solo l'ultimo codice valido. Un test prova esplicitamente la trappola del 4.1.

---

## Parte 5 — Quarta scelta: nessuno deve scoprire chi è cliente, e il primo accesso

### 5.1 L'enumerazione degli account

**Enumerare** gli account vuol dire scoprire quali email sono registrate provando a chiederle al sistema. Se `mario.rossi@example.com` riceve "codice inviato" e `nessuno@example.com` riceve "email sconosciuta", chiunque può sapere chi è cliente di uno studio di posturologia: un'informazione che tocca la salute. Il sistema deve rispondere **allo stesso modo**: stesso stato HTTP, stesso corpo e, per quanto possibile, **stesso tempo**.

Il tempo conta: se per le email registrate il sistema spedisce un'email (200 ms) e per le altre no (5 ms), la differenza si misura e rivela comunque chi è registrato.

### 5.2 Primo accesso di un'email mai vista: quando nasce l'account

D-014 dice "la prima prenotazione crea l'account", e i requisiti dicono "la prima prenotazione crea l'account in automatico". Dal punto di vista del cliente vuol dire: **nessuna registrazione separata**. Tecnicamente ci sono tre momenti possibili.

| Strada | Quando nasce `app_user` | Problema |
|---|---|---|
| A — alla **richiesta** del codice | chiunque scriva un'email crea un account | account spazzatura per ogni email digitata male o inventata; uno script ne crea migliaia |
| B — alla **verifica** del codice | quando la persona dimostra che l'email è sua | nessuno, ma l'account nasce anche se poi non prenota |
| C — alla **prima prenotazione** | dopo la verifica la sessione contiene solo "email verificata", senza utente | una sessione senza utente: ogni parte del sistema deve gestire il caso "verificato ma senza account" |

**Raccomandazione: B.** Alla verifica giusta di un'email sconosciuta nasce l'account `CUSTOMER` (sempre `CUSTOMER`: i professionisti li crea solo l'admin, pezzo 3). La richiesta del codice manda **un codice a chiunque**, registrato o no, e quindi risponde e impiega lo stesso tempo in ogni caso: la parte 5.1 è risolta senza trucchi.

Questo **precisa D-014**: l'account nasce al primo accesso verificato, non alla prima prenotazione. Per il cliente non cambia niente, perché si accede per prenotare. Va registrato con una scheda nuova.

### 5.3 Nome e cognome: quando si chiedono

`customer.first_name` e `last_name` sono `not null`, e alla verifica non li conosciamo. Non si possono chiedere **prima** del codice: per chiederli solo ai nuovi bisognerebbe dire chi è nuovo, cioè l'enumerazione del 5.1.

**Dopo** la verifica si possono chiedere: la persona ha dimostrato che l'email è sua, quindi dirle "sei nuovo" non rivela niente a un estraneo. Proposta:
- la verifica risponde con `newAccount` (vedi `CodeCheck.Valid` alla parte 4.2), e più in generale l'API dice se il profilo è completo;
- il frontend chiede nome e cognome **una volta sola**, con `POST /api/customer/profile` (pezzo 2), che crea la riga `customer`;
- finché la riga non c'è, le API del cliente rispondono 409 `PROFILE_INCOMPLETE`.

Il cliente creato dal pannello (D-048) ha già nome e cognome: entra direttamente.

### 5.4 Account disattivati

Qui ci sono due frasi da conciliare. Il brief e `modello-dati.md` dicono "un account disattivato non può accedere". Una nota di `stato.md` dice che un cliente disattivato che torna "va **riattivato**, non registrato di nuovo".

La nota parlava della **registrazione**: non si crea un secondo account, perché l'email è unica. Resta però una domanda: il login riattiva o rifiuta? Chi disattiva un cliente lo fa per una ragione: un cliente allontanato dallo studio, o una richiesta di cancellazione dei dati (punto aperto 8, che però anonimizza l'email). Se il login riattivasse da solo, la disattivazione non servirebbe a niente.

**Raccomandazione:** il login **rifiuta** gli account disattivati di ogni ruolo. La riattivazione avviene solo con un atto esplicito di qualcuno che ne ha il diritto: la prenotazione dal pannello del professionista (D-048, c'è già) o l'admin. Il "non registrato di nuovo" della nota resta vero.

Come rifiutare senza rivelare niente:
- alla **richiesta** la risposta è identica a tutte le altre. L'email spedita però è diversa: invece del codice dice "il tuo account è disattivato, contatta lo studio". La legge solo il proprietario della casella, che sa già di avere un account. Si spedisce comunque un'email, quindi anche il tempo è lo stesso;
- alla **verifica** (se il codice è giusto, perché l'account è stato disattivato nel frattempo): `AccountDeactivated`. Anche questo lo vede solo chi ha dimostrato di possedere l'email.

---

## Parte 6 — Quinta scelta: il freno agli abusi (*rate limiting*)

### 6.1 Da cosa ci si difende

**Rate limiting** vuol dire limitare quante volte si può fare un'operazione in un periodo. Qui protegge da tre cose:
1. **Indovinare il codice**: ogni codice nuovo regala altri 5 tentativi;
2. **Bombardare una casella**: qualcuno scrive l'email di un altro e la riempie di codici;
3. **Rovinare la reputazione del mittente**: troppe email non volute e il fornitore (Brevo) o i provider di posta mettono lo studio nello spam. Allora nessuno riesce più ad accedere (D-014, conseguenze).

### 6.2 I numeri

Proposta:
- **per destinazione**: al massimo **3 codici in 15 minuti** e **10 in 24 ore**;
- **per IP**: al massimo **30 richieste in un'ora**.

Il calcolo che li giustifica: 10 codici al giorno × 5 tentativi = 50 tentativi al giorno su un milione di codici possibili, cioè una probabilità di **1 su 20.000 al giorno** di indovinare. In più, ogni codice chiesto arriva nella casella della vittima, che se ne accorge. Con 5 codici l'ora la probabilità sarebbe 600 su un milione al giorno, quasi il 2% in un mese: troppo.

Il limite per IP è largo apposta. Molti telefoni escono su internet con **lo stesso indirizzo** dell'operatore (*CGNAT*), e anche il Wi-Fi dello studio è un IP solo per tutti i clienti in sala d'attesa. Questo limite serve contro chi prova molte email diverse dallo stesso posto, non contro una persona.

Oltre il limite: **429 Too Many Requests** con l'intestazione `Retry-After`. Non rivela niente sull'esistenza dell'account: il conteggio vale per qualunque email, registrata o no (parte 5.2).

### 6.3 Dove si contano le richieste

| Strada | Come | Pro | Contro |
|---|---|---|---|
| A — Nel database | `count` delle righe di `login_code` per destinazione e per IP nel periodo | le righe ci sono già; si salvano anche ai riavvii; valgono anche con due istanze (D-047); nessuna libreria | una query in più a richiesta (con gli indici, irrilevante) |
| B — In memoria (Bucket4j, Caffeine) | contatori nella JVM | velocissimo | si azzerano a ogni riavvio; con due istanze il limite raddoppia; la memoria cresce con gli IP e va svuotata |
| C — Nel proxy (Caddy) | limite sull'indirizzo `/api/public/auth/*` | ferma il traffico prima del backend | serve un modulo aggiuntivo di Caddy; non conosce le destinazioni |

**Raccomandazione: A.** La tabella dei codici **è** già il registro delle richieste. Due richieste esattamente simultanee possono superare il limite di uno (contano entrambe 2 < 3, poi entrambe inseriscono). Qui va bene: è un freno, non una regola di soldi. Per la stessa ragione non si prende un lucchetto, a differenza del limite delle prenotazioni (D-045).

### 6.4 L'IP vero dietro Caddy

In produzione il backend non vede il cliente: vede **Caddy** (D-017), che inoltra la richiesta. `request.getRemoteAddr()` restituirebbe sempre l'IP di Caddy, e il limite per IP diventerebbe un limite unico per tutto il mondo. Caddy scrive l'IP vero nell'intestazione `X-Forwarded-For`.

- In Spring Boot: `server.forward-headers-strategy=native`. Tomcat legge `X-Forwarded-For` **solo** se la richiesta arriva da un proxy fidato (per default gli indirizzi delle reti private, come la rete interna di Docker), e allora `getRemoteAddr()` restituisce l'IP vero.
- **Mai leggere `X-Forwarded-For` a mano**: è un'intestazione che chiunque può scrivere. Chi la legge senza controllare il mittente si fa dire dall'attaccante quale IP limitare. È lo stesso errore dell'identità dichiarata dei DevHeader.
- Al passo 14 va verificato che Caddy **sostituisca** l'intestazione che arriva da internet invece di accodarla (Caddy 2 lo fa per default, se non gli si dice di fidarsi di altri proxy). Da annotare in `stato.md`.
- **IPv6**: un utente ha di solito un'intera rete `/64`, cioè miliardi di indirizzi. Limitare il singolo indirizzo non serve. Proposta: per IPv6 si conta la `/64`, per IPv4 l'indirizzo.
- **Come è stato fatto** (implementazione): invece di salvare l'indirizzo intero e raggruppare in SQL, Java **azzera** gli ultimi 64 bit di un indirizzo IPv6 prima di salvarlo (`LoginCodeService.limitKeyOf`), e la colonna si chiama `requested_from`. Due vantaggi: il conteggio è una semplice uguaglianza in JPQL, e si conserva **meno** dato personale (*minimizzazione*, GDPR). Provato anche a mano: una richiesta da `::1` (localhost in IPv6) è stata salvata come `::`.

### 6.5 Gli IP sono dati personali

Per il GDPR un indirizzo IP è un dato personale, e va tenuto solo quanto serve. Il limite più lungo è di 24 ore, quindi un **lavoro programmato** (come D-047) cancella ogni ora le righe di `login_code` con più di 24 ore. Dopo, un codice non serve più a niente.

---

## Parte 7 — Sesta scelta: chi spedisce il codice

### 7.1 Il problema delle dipendenze

`auth` non dipende da nessun modulo (D-029): `practitioner`, `customer` e dal pezzo 4 `notification` dipendono da lui. Ma il codice lo deve spedire chi sa spedire email, cioè `notification`. Se `auth` chiamasse `notification`, e `notification` chiedesse ad `auth` l'email di un professionista, i due moduli dipenderebbero l'uno dall'altro: un ciclo.

### 7.2 Strada A — Interfaccia in `auth`, implementazione in `notification`

È il trucco di `OccupiedTime` (D-044) e di `CardHolds` (D-046): `auth` dichiara di cosa ha bisogno, `notification` lo fornisce.

```java
// auth: cosa serve, detto con i tipi di auth
public interface LoginCodeSender {
    void sendCode(Email to, String code, Duration validity);
    void sendAccountDeactivated(Email to);
}
```

Le dipendenze nel codice vanno in un verso solo: `notification` → `auth`.

### 7.3 Strada B — Un evento (`LoginCodeRequested`) ascoltato da `notification`

Disaccoppia allo stesso modo, ma il codice in chiaro viaggerebbe in un evento che chiunque può ascoltare. E un ascoltatore dopo il commit non può far fallire la richiesta (7.5).

### 7.4 Mailpit subito, non al pezzo 4

Per provare il pezzo 1 a mano il codice deve arrivare da qualche parte. Due possibilità:
- un'implementazione provvisoria che scrive il codice nel **log**. È di nuovo un sostituto provvisorio, proprio mentre questo passo toglie gli altri due. E un codice di accesso nel log di produzione, per un errore di configurazione, è un buco vero;
- **anticipare Mailpit al pezzo 1.** Mailpit è un finto server di posta: riceve le email via SMTP e le mostra in una pagina web (`http://localhost:8025`), senza spedirle davvero. Nel pezzo 1 si aggiungono il servizio in `infra/docker-compose.dev.yml` (lo scrivi tu, come previsto dal brief), `spring-boot-starter-mail`, le proprietà `spring.mail.*` da variabili d'ambiente, e una sola email di testo semplice. Il pezzo 4 costruisce il resto di `notification` su questa base.

**Raccomandazione: interfaccia in `auth` e Mailpit anticipato al pezzo 1.** Nei test un `LoginCodeSender` finto che ricorda l'ultimo codice spedito, per poterlo poi verificare.

### 7.5 Prima il commit, poi la spedizione

Se l'email partisse **dentro** la transazione e poi il commit fallisse, la persona riceverebbe un codice che nel database non esiste. Quindi: si salva il codice, si fa il commit, poi si spedisce, **fuori** dalla transazione. A differenza della chiamata a Stripe di D-046, qui non c'è niente da proteggere con il lucchetto mentre si aspetta il server di posta.

Se la spedizione fallisce (server di posta irraggiungibile): si registra l'errore e si risponde **503** "riprova tra poco". Non rivela niente, perché per ogni richiesta valida si spedisce un'email (parti 5.2 e 5.4). La riga resta e conta nel limite: va bene.

La spedizione resta **sincrona**: la richiesta aspetta il server di posta, qualche decina di millisecondi. Spedire in un altro thread renderebbe la risposta più veloce, ma impedirebbe di dire "riprova". Se ne riparla al pezzo 4, dove gli ascoltatori degli eventi pongono la stessa domanda con una risposta diversa.

---

## Parte 8 — Le scelte piccole

- **Indirizzo**: `POST /api/public/auth/login-code` sotto `/api/public/**` (D-029), corpo `{"email": ...}` con `@NotBlank @ValidEmail` (D-048). Risposta **202 Accepted** senza corpo: "presa in carico", che è la verità. Un'email scritta male è un 400: riguarda il formato, non l'esistenza.
- **Le regole di D-014** (6 cifre, 10 minuti, 5 tentativi) sono **costanti** in `LoginCode`, con un commento che rimanda al `check` del database (come `Practitioner.SLOT_STEPS`). Sono regole del prodotto, non impostazioni del server.
- **I limiti** della parte 6 sono invece impostazioni da poter regolare senza ricompilare: un `record` con `@ConfigurationProperties("auth.login-code")`, valori in `application.properties`. Spring costruisce il `record` dal costruttore, con valori immutabili e i tipi giusti (`Duration`, `int`). È un altro uso naturale dei `record`.
- **429**: un'eccezione nuova, `TooManyRequestsException` in `shared`, con il tempo di attesa; il gestore degli errori la trasforma in 429 con `Retry-After`. Non si riusa `BusinessRuleException`: quella è un 409, "lo stato non lo permette", mentre qui il problema è "troppe volte".
- **Codici d'errore nuovi** per il pezzo 2 (la verifica): `CODE_WRONG`, `CODE_EXPIRED`, `ACCOUNT_DEACTIVATED`, `PROFILE_INCOMPLETE`.
- **`created_at` scritto da Java** con il `Clock`, come `booking` (D-046): scadenza e limiti si provano con il `MovableClock` del passo 7, senza aspettare 10 minuti veri.

---

## Parte 9 — I test

**Unitari** (senza Spring):
- il codice ha sempre 6 cifre, anche quando inizia con zeri;
- `LoginCodeHasher`: stesso codice e stessa destinazione danno la stessa impronta; destinazione diversa, impronta diversa; chiave diversa, impronta diversa.

**Servizio** (`@DataJpaTest` con Testcontainers, `MovableClock`, `LoginCodeSender` finto):
- richiesta → una riga, e nel database **non c'è** il codice in chiaro;
- codice giusto → `Valid`, `used_at` valorizzato; lo stesso codice una seconda volta → `Expired`;
- dopo 10 minuti → `Expired`;
- **la trappola della parte 4.1**: 5 codici sbagliati, poi quello giusto → `Expired`, e `attempts` vale 5 nel database;
- un codice nuovo rende inutile il precedente;
- email mai vista: alla verifica nasce un account `CUSTOMER`, con `newAccount = true`; alla seconda volta `false`;
- account disattivato: la richiesta spedisce l'email "account disattivato" e non il codice; la verifica risponde `AccountDeactivated`;
- limiti: il quarto codice in 15 minuti per la stessa email → 429; dopo 15 minuti si può di nuovo; il trentunesimo per lo stesso IP → 429;
- pulizia: le righe con più di 24 ore spariscono, le altre restano.

**Concorrenza**: 10 verifiche sbagliate in parallelo sullo stesso codice → `attempts` arriva a 5 e non oltre, e nessuna richiesta riceve `Valid`.

**API** (`@SpringBootTest` con `MockMvcTester`):
- per un'email registrata, una sconosciuta e una disattivata la risposta è **identica**: stato, intestazioni e corpo;
- email non valida → 400 sul campo `email`;
- oltre il limite → 429 con `Retry-After`.

**Schema**: `inet` mappato correttamente (`ddl-auto=validate` si avvia); `check` su `attempts` e sul canale.

---

## Parte 10 — Cosa c'è da decidere

| # | Scelta | Raccomandazione |
|---|---|---|
| 1 | Tabella dei codici | Una sola, `channel` + `destination`; `check` solo `EMAIL`; `purpose` al pezzo 6 |
| 2 | Hash | HMAC-SHA256, chiave in `.env`, destinazione nell'impronta, `MessageDigest.isEqual` |
| 3 | Tentativi | Risultato `sealed` invece di eccezione; lucchetto pessimistico; vale solo l'ultimo codice |
| 4a | Quando nasce l'account | Alla verifica giusta, sempre `CUSTOMER`: precisa D-014 con una scheda nuova |
| 4b | Nome e cognome | Dopo la verifica, una volta, con `POST /api/customer/profile` (pezzo 2) |
| 4c | Account disattivati | Accesso rifiutato per ogni ruolo; email "account disattivato" al posto del codice; riattivazione solo esplicita |
| 5 | Limiti | Nel database: 3 in 15 min e 10 in 24 h per destinazione, 30 l'ora per IP (`/64` in IPv6); 429; pulizia dopo 24 h |
| 6 | Spedizione | `LoginCodeSender` in `auth`, implementato da `notification`; Mailpit anticipato al pezzo 1; commit e poi spedizione, 503 se fallisce |

Da annotare in `stato.md` a fine pezzo: il controllo di `X-Forwarded-For` in Caddy al passo 14; il punto aperto 5 (fornitore email) spostato a "prima del passo 14", come chiede il brief.

---

## Parte 11 — Com'è andata (implementazione)

- Le scelte della parte 10 sono state prese tutte come raccomandate (D-050). Unica differenza: `requested_from` salva già la rete per IPv6 (parte 6.4).
- **Prova che i test sanno fallire** (*mutation testing* fatto a mano): introdotto apposta l'errore del 4.1 (tentativo contato e poi eccezione), tre test diventano rossi. Tolto `@Lock` dal repository, il test delle 10 ipotesi in parallelo fallisce con *"Expected size: 4 but was: 10"*: tutte e dieci contate come `Wrong`. È il *lost update* del 4.5 visto davvero, non solo in teoria.
- La chiave di prova dei test sta in `src/test/resources/config/application.properties`: Spring Boot legge `config/application.properties` **in aggiunta** a `application.properties`, quindi lì cambia solo la chiave.
- Prova a mano con Mailpit: la richiesta per `Mario.Rossi@Example.com` risponde 202 e l'email arriva a `mario.rossi@example.com`, con il codice nel testo e non nell'oggetto.
- 41 test nuovi, 478 in tutto.

---

## Domande da colloquio

### Domanda 1: Salvi un codice di accesso a 6 cifre come hash SHA-256. È sicuro?
**Risposta modello.** No, non abbastanza. Le possibilità sono solo un milione: chi legge la tabella calcola tutte le impronte in meno di un secondo. Nemmeno un hash lento come BCrypt basta, perché un milione di tentativi su una scheda grafica richiede minuti. Serve un HMAC con una chiave segreta tenuta fuori dal database: senza la chiave le impronte sono inutili. La difesa principale resta comunque il limite di tentativi con la scadenza breve. L'hash protegge solo dal furto della tabella.

### Domanda 2: Perché un confronto a tempo costante, e come lo fai in Java?
**Risposta modello.** `equals` si ferma al primo byte diverso, quindi la sua durata rivela quanti byte iniziali sono giusti. Misurando molte richieste, un segreto si può ricostruire un pezzo alla volta. `MessageDigest.isEqual` confronta sempre tutti i byte. È indispensabile dove i tentativi non sono limitati, come i token o le firme dei webhook. Con un limite di 5 tentativi l'attacco è teorico, ma il costo è zero.

### Domanda 3: Un metodo `@Transactional` conta i tentativi sbagliati e poi lancia un'eccezione. Cosa succede?
**Risposta modello.** Una `RuntimeException` provoca il rollback, che annulla anche l'incremento del contatore: il limite non esiste più. Le soluzioni sono restituire un risultato invece di lanciare (con Java 21 una `sealed interface` di `record`, letta con uno `switch` senza `default`), oppure `noRollbackFor`, oppure il contatore in `REQUIRES_NEW`. La prima è la più robusta: un codice sbagliato è un esito normale, non un'eccezione.

### Domanda 4: Cos'è l'enumerazione degli account e come la eviti in un login senza password?
**Risposta modello.** È scoprire quali email sono registrate da come risponde il sistema. La si evita rispondendo allo stesso modo, con lo stesso stato, lo stesso corpo e lo stesso tempo. Il modo più semplice è comportarsi davvero allo stesso modo: si spedisce un codice a qualunque email e l'account nasce solo alla verifica. Le differenze vanno solo nel contenuto dell'email, che legge soltanto il proprietario della casella. Anche il limite di richieste deve contare allo stesso modo le email registrate e quelle sconosciute.

### Domanda 5: Come implementi un rate limit per IP dietro un reverse proxy?
**Risposta modello.** Dietro il proxy l'indirizzo remoto è quello del proxy, quindi va letto `X-Forwarded-For`, ma solo se la richiesta arriva da un proxy fidato. Altrimenti è un'intestazione che l'attaccante scrive come vuole. In Spring Boot c'è `server.forward-headers-strategy=native`, e il proxy deve sostituire l'intestazione che arriva da fuori. Il limite deve essere largo, perché molti utenti condividono un IP (CGNAT, Wi-Fi), e in IPv6 va applicato alla rete `/64`. Per un'applicazione piccola i contatori nel database sopravvivono ai riavvii e valgono con più istanze. I contatori in memoria no.

### Domanda 6: Perché `SecureRandom` e non `Random` per un codice?
**Risposta modello.** `Random` è un generatore lineare: da pochi valori osservati si ricostruisce il suo stato interno e si prevedono i valori successivi. `SecureRandom` usa una fonte crittografica, che non si può prevedere. Per tutto ciò che è un segreto (codici, token, identificativi di sessione) si usa sempre `SecureRandom`.

---

## Documentazione ufficiale

- OWASP, *Authentication Cheat Sheet* (enumerazione, messaggi d'errore, limiti): https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- RFC 2104, *HMAC*: https://www.rfc-editor.org/rfc/rfc2104
- Java 21, `javax.crypto.Mac`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Mac.html
- Java 21, `MessageDigest.isEqual`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/MessageDigest.html#isEqual(byte%5B%5D,byte%5B%5D)
- Java 21, *record patterns* (JEP 440): https://openjdk.org/jeps/440
- Spring Boot, dietro un proxy (`forward-headers-strategy`): https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server
- Spring Boot, `@ConfigurationProperties` con i `record`: https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.constructor-binding
- PostgreSQL 17, tipi di rete (`inet`): https://www.postgresql.org/docs/17/datatype-net-types.html
- Mailpit: https://mailpit.axllent.org/
