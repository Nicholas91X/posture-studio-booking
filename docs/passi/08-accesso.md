# Passo 08 — Accesso e notifiche

> **Stato: in corso** (dal 2026-11-09). Pezzi 1–3 di 6 fatti, 534 test verdi (`mvnw clean test`). Branch `feat/step-8-access` dal pezzo 2 (il pezzo 1 è su `main`).

## Obiettivo
Il sistema sa chi lo sta usando: accesso con un codice via email o via SMS (solo da un numero verificato), sessione con cookie, ruoli, account disattivati fuori. I sostituti provvisori (`DevHeaderCurrentPractitioner`, `DevHeaderCurrentCustomer`) spariscono. Le notifiche email e push si agganciano agli eventi del passo 7.

I pezzi (guida 24, parte 0):
1. Codice di accesso via email: richiesta, verifica, limiti — ✅ fatto (guida 24, D-050)
2. Sessione e Spring Security, ruoli, via i due DevHeader, test della riservatezza — ✅ fatto (guida 25, D-051)
3. Admin e professionisti (D-021) — ✅ fatto (guida 26, D-052)
4. Notifiche email sugli eventi del passo 7
5. Notifiche push (D-032, D-039), con riserva email
6. Codice di accesso via SMS da numero verificato

## Criteri di "fatto" (dal brief)

| Criterio | Stato | Dove si vede |
|---|---|---|
| Accesso via email e via SMS funzionanti, con limiti e tentativi provati | email ✅ (richiesta, verifica, sessione); SMS al pezzo 6 | `LoginCodeServiceTest`, `LoginCodeApiTest`, `SessionApiTest` |
| I due DevHeader rimossi; nessun test li usa più | ✅ | `SessionCurrentPractitioner`, `SessionCurrentCustomer`, `TestSessions`; nessun `X-Practitioner-Id` o `X-Customer-Id` nel codice |
| Test della riservatezza esistono e sono verdi | ✅ | `ConfidentialityTest` |
| Ogni area solo al suo ruolo; account disattivati fuori | ✅ | `ConfidentialityTest`, `SessionApiTest` |
| L'admin crea e disattiva professionisti; primo admin senza segreti | ✅ (`ADMIN_EMAIL` all'avvio, solo nel `.env`) | `PractitionerAdminApiTest`, `AdminBootstrapTest`, `AdminBootstrapStartupTest` |
| Email in Mailpit per ogni evento; un invio fallito non annulla la prenotazione | Mailpit attivo e codice di accesso provato a mano; eventi al pezzo 4 | `infra/docker-compose.dev.yml` |
| Push con riserva email provata | pezzo 5 | |
| Punto aperto 7 chiuso; punto 5 spostato al passo 14; nuovo punto sul fornitore SMS | punto 5 spostato; 7 e fornitore SMS al pezzo 6 | `stato.md`, `requisiti.md` |
| `modello-dati.md` aggiornato; test verdi; branch e PR | V5 e V6 descritte, note del pezzo 3; 534 verdi; branch aperto, PR a fine passo | `modello-dati.md` |

## Cosa è stato costruito

### Pezzo 1 — Codice di accesso via email
- `V5__create_login_code.sql`: tabella `login_code` con `channel` + `destination` (pronta per l'SMS), `code_hash`, `requested_from` (`inet`), `created_at` scritto da Java, `expires_at`, `attempts` (`check` 0–5), `used_at`; tre indici.
- `auth/LoginCode`: l'entità, con le regole di D-014 come costanti (10 minuti, 5 tentativi) e il codice a 6 cifre generato con un `RandomGenerator`.
- `auth/LoginCodeHasher`: HMAC-SHA256 di `destinazione:codice` con la chiave `AUTH_CODE_HMAC_KEY`, e il confronto con `MessageDigest.isEqual`. Se la chiave manca o è corta, l'applicazione non parte.
- `auth/CodeCheck`: `sealed interface` con quattro `record` (`Valid`, `Wrong`, `Expired`, `AccountDeactivated`), l'esito della verifica.
- `auth/LoginCodeService`: `request` (limiti, riga, commit, poi spedizione; 429 e 503), `verify` (lucchetto, tentativi, account creato al primo accesso), `purgeOld`, `limitKeyOf` (rete `/64` per IPv6).
- `auth/LoginCodeRepository`: l'ultimo codice con `@Lock(PESSIMISTIC_WRITE)`, due conteggi con `select new WindowUsage(...)`, la cancellazione in blocco.
- `auth/LoginCodeProperties`: un `record` `@ConfigurationProperties` con i limiti, controllati nel costruttore compatto.
- `auth/LoginCodeController`: `POST /api/public/auth/login-code`, 202 senza corpo.
- `auth/LoginCodeJobs`: la pulizia ogni ora.
- `auth/LoginCodeSender`: l'interfaccia della spedizione. `notification/EmailLoginCodeSender` la implementa con Spring Mail (*text block* per i testi).
- `shared/TooManyRequestsException` (429 con `Retry-After`) e `shared/DeliveryFailedException` (503), nel gestore degli errori.
- `infra/docker-compose.dev.yml`: il servizio `mailpit` (SMTP sulla porta 1025, pagina web sulla 8025). `.env.example`: le righe `MAIL_*` e `AUTH_CODE_HMAC_KEY`.
- Test: `LoginCodeTest`, `LoginCodeHasherTest` (unitari), `LoginCodeSchemaTest`, `LoginCodeServiceTest` (con la concorrenza), `LoginCodeApiTest`, e `RecordingLoginCodeSender` come finto mittente.

### Pezzo 2 — Sessione e Spring Security
- `V6__create_spring_session.sql`: le tabelle di Spring Session JDBC.
- `auth/SecurityConfiguration`: la catena di filtri, con le regole per area, `denyAll()` per il resto, CSRF con `csrf.spa()`, *request cache* spenta e 401/403 passati ad `ApiExceptionHandler`.
- `auth/SessionAccounts`: il `SecurityContextRepository` che rilegge l'account a ogni richiesta, con `signIn` (cambio dell'id, salvataggio esplicito) e `signOut`.
- `auth/SessionCookieConfiguration`: il cookie `SESSION`.
- `auth/SessionController`: `POST/GET/DELETE /api/public/auth/session`, uno `switch` con i *record pattern* su `CodeCheck`. `SignInRequest`, `SessionView`, `AuthErrorCode`.
- `auth/CurrentUser` (pubblica), con `SecurityContextCurrentUser` e `SignedInUser`.
- `practitioner/SessionCurrentPractitioner` e `customer/SessionCurrentCustomer` al posto dei DevHeader, cancellati.
- `customer/CustomerProfileController`: `GET/PUT /api/customer/profile`, con `CustomerProfile` e `ProfileNames`.
- `ApiExceptionHandler`: `AuthenticationException` → 401, `AccessDeniedException` → 403 (`CSRF_TOKEN_INVALID` per il CSRF). `ErrorCode.PROFILE_INCOMPLETE`.
- Test: `TestSessions` (sessioni vere per i test d'API), `SessionApiTest`, `ConfidentialityTest`; riscritti i sei test d'API che usavano gli header.

### Pezzo 3 — Admin e professionisti
- `auth/AdminBootstrap`: l'`ApplicationRunner` che crea il primo admin da `ADMIN_EMAIL`, con un `TransactionTemplate` e un esito `enum` letto da uno `switch` senza `default`.
- `auth/AppUserRepository.insertAdminIfAbsent`: `insert ... on conflict (email) do nothing`, nativo.
- `auth/AccountService`: `createPractitionerAccount`, `summariesOf` (con il `record` pubblico `AccountSummary`), `deactivate`, `activate`.
- `practitioner/Practitioner`: costruttore *package-private* e `rename`; `@Generated` sulle otto impostazioni.
- `practitioner/PractitionerService`: `listForAdmin`, `getForAdmin`, `create`, `rename`, `deactivate`, `activate`.
- `practitioner/PractitionerAdminController` sotto `/api/admin/practitioners`, con `NewPractitioner`, `PractitionerNames`, `AdminPractitionerView`.
- `ErrorCode.EMAIL_ALREADY_IN_USE`; `auth.admin-email` in `application.properties`; `ADMIN_EMAIL=` in `.env.example`.
- Test: `AdminBootstrapTest`, `AdminBootstrapStartupTest`, `PractitionerAdminApiTest`, e casi nuovi in `AccountServiceTest`, `PractitionerServiceTest`, `PractitionerTest`; `ConfidentialityTest` aggiornato.

## Concetti chiave

- **Perché l'hash da solo non basta.** I codici a 6 cifre sono un milione. Chi ruba la tabella calcola tutte le impronte SHA-256 in meno di un secondo; anche BCrypt cede in pochi minuti su una scheda grafica. Un **HMAC** con una chiave fuori dal database rende le impronte inutili senza la chiave. La difesa vera resta il limite di tentativi con la scadenza breve.
- **Confronto a tempo costante.** `equals` si ferma al primo byte diverso, e la sua durata rivela quanto dell'inizio era giusto. `MessageDigest.isEqual` confronta sempre tutto.
- **Eccezione e rollback.** Una `RuntimeException` in un metodo `@Transactional` annulla **tutto**, anche il tentativo appena contato. Un esito normale (codice sbagliato) si restituisce come valore, non si lancia. Con una `sealed interface` di `record`, lo `switch` del chiamante copre tutti i casi senza `default`.
- **Lost update e lucchetto pessimistico.** Due transazioni leggono `attempts = 3` e scrivono entrambe `4`. Con `select ... for update` la seconda aspetta la prima e rilegge il valore aggiornato. Visto davvero: senza lucchetto, 10 su 10.
- **Enumerazione degli account.** Rispondere uguale (stato, corpo, tempo) che l'email esista o no. Il modo più semplice è comportarsi davvero allo stesso modo: un'email per ogni richiesta, l'account solo alla verifica.
- **Rate limiting.** Contare nel database le righe che esistono già: valgono ai riavvii e con più istanze. Limiti larghi per IP (CGNAT, Wi-Fi), rete `/64` in IPv6, e l'IP vero da Tomcat (`forward-headers-strategy=native`), mai da `X-Forwarded-For` letto a mano.
- **Inversione delle dipendenze** (terza volta, dopo `OccupiedTime` e `CardHolds`): `auth` dichiara `LoginCodeSender`, `notification` lo implementa.
- **Prima il commit, poi la chiamata esterna.** Un'email spedita prima di un commit fallito porterebbe un codice inesistente.
- **Catena di filtri.** Spring Security è una fila di filtri davanti ai controller: chi c'è (`SecurityContextRepository`), CSRF, regole (`AuthorizationFilter`), traduzione dei rifiuti in 401/403. Tutto succede prima di Spring MVC, quindi il gestore degli errori non lo vede, a meno di riportarglielo.
- **Autenticazione e autorizzazione.** Chi sei, e cosa puoi fare. Per area nei filtri (403), per oggetto nei servizi (404).
- **Sessione contro JWT.** La sessione si revoca subito, il JWT no. Con un solo backend e un solo dominio la sessione basta, ed è più sicura.
- **Estendere invece di riscrivere.** Per decidere "chi c'è" si sostituisce un solo pezzo della catena, il `SecurityContextRepository`.
- **CSRF, SameSite, *login CSRF*.** Il cookie viaggia da solo, quindi un sito estraneo può far partire richieste a nome della vittima. Il token in un header non può copiarlo. `SameSite=Lax` è la seconda difesa.
- ***Session fixation*.** All'accesso l'id della sessione cambia.
- ***Default deny*.** `denyAll()` per tutto ciò che non è assegnato.
- **L'uovo e la gallina del primo admin.** Identità e segreto sono cose diverse: con l'accesso via codice, l'email dell'admin è configurazione. Si legge all'avvio, e il codice di avvio crea soltanto, senza mai promuovere.
- **`ApplicationRunner`.** Gira a contesto pronto, dopo Flyway, e un'eccezione ferma l'avvio (*fail fast*). `@PostConstruct` girerebbe troppo presto.
- **`on conflict do nothing`.** L'`insert` idempotente: la gara tra "controllo" e "inserisco" la chiude il database.
- **Propagazione `REQUIRED`.** Due righe di due moduli in una transazione: il servizio chiamato entra in quella del chiamante, e un *rollback* annulla tutto.
- **Default del database e JPA.** Un campo `int` vale 0 e scavalca il default; `@Generated` lascia decidere il database e rilegge il valore con `returning`.
- **Una verità sola.** Disattivare un professionista è una riga, perché "attivo" sta in un posto solo (D-029).
- **Java moderno usato qui:** `sealed interface` e *record pattern* (21), `record` con costruttore compatto come configurazione, *text block* (15), `RandomGenerator` (17), `HexFormat` (17), `List.reversed()` delle *sequenced collection* (21, nel finto mittente dei test).

## Decisioni
- **D-050** — codice di accesso via email (precisa **D-014**: l'account nasce alla prima verifica giusta; l'impronta è un HMAC).

## Errori fatti e come sono stati corretti

1. **Un generatore non crittografico per una chiave segreta.** Nel primo `.env.example` la riga suggerita per generare `AUTH_CODE_HMAC_KEY` in PowerShell usava `Get-Random`, che non è un generatore crittografico (come `java.util.Random`). Una chiave generata così sarebbe prevedibile. Corretta con `RandomNumberGenerator` di .NET, e una riga che avvisa di non usare `Get-Random`. La lezione è la stessa della guida 24, 3.5, applicata agli strumenti fuori da Java.
2. **Test verdi al primo colpo: provato che sanno fallire.** Non è un errore del codice, ma il motivo per cui ci si fida dei test. Con l'errore del 4.1 introdotto apposta, tre test diventano rossi. Senza `@Lock`, il test delle ipotesi in parallelo fallisce con *"Expected size: 4 but was: 10"*.
3. **(Pezzo 2) Le proprietà del cookie non arrivavano nei test.** `server.servlet.session.cookie.*` vale per Spring Session solo con Tomcat vero. Il test sul cookie l'ha scoperto: il cookie è diventato un bean.
4. **(Pezzo 2) La cache di Hibernate nei test `@Transactional`.** Le richieste MockMvc di un test condividono la transazione e la cache di primo livello: un `update` con SQL diretto non si vedeva. `flush()` e `clear()` intorno all'`update`.
5. **(Pezzo 2) La chiave nel `.env` tra virgolette.** Spring legge il `.env` come file di proprietà e tiene le virgolette nel valore. La chiave non era più Base64, e l'avvio si è fermato con il messaggio di `LoginCodeHasher`, come previsto. È l'errore del passo 4 (D-018). Da correggere togliendo le virgolette.
6. **(Pezzo 2, errore di Claude) Un backend lasciato acceso.** Nella prova del pezzo 1, fermando `mvnw spring-boot:run` si chiudeva Maven ma non il processo Java figlio, che è rimasto sulla porta 8080 per un'ora. Alla prova del pezzo 2 la porta era occupata. Da allora si ferma il processo che ascolta sulla porta, e il suo padre.
7. **(Pezzo 3) Un dubbio verificato invece che supposto.** Non era chiaro se `@Generated` avrebbe escluso le colonne anche dagli `update`. Invece di fidarsi della documentazione, un test di prova ha stampato l'SQL di un `insert` e di un `update`: le colonne restano negli `update`, e l'`insert` le rilegge con `returning`. Poi il test di prova è stato cancellato, e due test veri hanno preso il suo posto.
8. **(Pezzo 3, errore di Claude) Uno script che diceva "nessun fallimento" senza aver eseguito niente.** La prova che i test sanno fallire l'ha fatta prima uno script Python, che non riusciva a lanciare Maven su Windows e leggeva i risultati vecchi. Quattro mutazioni, quattro "nessun test fallito": un risultato impossibile, e il segnale per controllare. Rifatta a mano dalla shell, ogni mutazione è stata presa. La lezione: un verde troppo veloce va guardato come un rosso.
9. **Porta 5432 occupata** durante la prova a mano: in locale gira già il database di un altro progetto. Il PostgreSQL di questo progetto è stato avviato sulla 5433 solo per la prova (`POSTGRES_PORT=5433` davanti al comando). Da ricordare se l'avvio di Compose fallisce con "port is already allocated".

## Domande da colloquio

Le domande del pezzo 1 sono nella guida 24 (sei domande, con le risposte modello): hash di un codice a 6 cifre, confronto a tempo costante, eccezione e rollback dei tentativi, enumerazione degli account, rate limit dietro un proxy, `SecureRandom`. Qui una in più, nata dall'implementazione.

### Domanda 1: I tuoi test sono tutti verdi. Come sai che proverebbero davvero un errore?
**Risposta modello.** Rompendo apposta il codice e guardando se diventano rossi (*mutation testing*, a mano o con uno strumento come PIT). Per esempio, tolto il lucchetto pessimistico, il test della concorrenza deve fallire. Se resta verde, quel test non prova il lucchetto. Un test che non può fallire dà una sicurezza falsa.

### Domanda 2: Un test `@Transactional` aggiorna una riga con SQL diretto, e la richiesta successiva vede ancora il valore vecchio. Perché?
**Risposta modello.** Con `@Transactional` il test e le richieste MockMvc, nello stesso thread, condividono la stessa transazione e quindi lo stesso *persistence context* di Hibernate. Un'entità già caricata viene restituita dalla cache di primo livello, anche se una query la rilegge, senza vedere l'`update` fatto fuori da Hibernate. Si risolve con `flush()` prima e `clear()` dopo l'`update`, o con test che non girano in una transazione. In produzione ogni richiesta ha la sua transazione, e il problema non c'è.

### Domanda 3: Hai configurato una proprietà e l'applicazione parte. Come sai che la configurazione è applicata davvero?
**Risposta modello.** Con un test che osserva l'effetto, non la proprietà: qui l'header `Set-Cookie` con i suoi attributi. È così che si è scoperto che le proprietà del cookie valevano solo con Tomcat vero, e non in MockMvc. Poi una prova a mano nell'ambiente reale, perché i test girano in un ambiente un po' diverso.

### Domanda 4: Come crei il primo amministratore senza mettere credenziali nel codice?
**Risposta modello.** Separo identità e segreto. Qui si entra con un codice mandato all'email, quindi l'email dell'admin è configurazione: la leggo da una variabile d'ambiente all'avvio, con un `ApplicationRunner`, e creo l'account se manca. Il codice crea soltanto, non promuove mai un account esistente, perché un errore di battitura diventerebbe un'escalation di privilegi; nel dubbio ferma l'avvio. È idempotente con `insert ... on conflict do nothing`, così regge riavvii e più istanze.

### Domanda 5: L'entità JPA ha un campo `int` e la colonna ha un default nel database. Cosa succede al primo `insert`, e come lo risolvi?
**Risposta modello.** Hibernate scrive tutte le colonne mappate, quindi manda lo zero del campo e il default non scatta: qui lo zero violava anche i `check`. Le strade: ripetere i default in Java (due verità), un `insert` nativo, oppure `@Generated`, che esclude la colonna dall'`insert` e la rilegge. Con PostgreSQL, Hibernate 7 la rilegge nello stesso comando con `returning`. Prima di fidarmi ho verificato con un test che le colonne restassero negli `update`.
