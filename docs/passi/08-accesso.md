# Passo 08 — Accesso e notifiche

> **Stato: in corso** (dal 2026-11-09). Pezzi 1–5 di 6 fatti, 601 test verdi (`mvnw clean test`). Branch `feat/step-8-access` dal pezzo 2 (il pezzo 1 è su `main`).

## Obiettivo
Il sistema sa chi lo sta usando: accesso con un codice via email o via SMS (solo da un numero verificato), sessione con cookie, ruoli, account disattivati fuori. I sostituti provvisori (`DevHeaderCurrentPractitioner`, `DevHeaderCurrentCustomer`) spariscono. Le notifiche email e push si agganciano agli eventi del passo 7.

I pezzi (guida 24, parte 0):
1. Codice di accesso via email: richiesta, verifica, limiti — ✅ fatto (guida 24, D-050)
2. Sessione e Spring Security, ruoli, via i due DevHeader, test della riservatezza — ✅ fatto (guida 25, D-051)
3. Admin e professionisti (D-021) — ✅ fatto (guida 26, D-052)
4. Notifiche email sugli eventi del passo 7 — ✅ fatto (guida 27, D-053)
5. Notifiche push (D-032, D-039), con riserva email — ✅ fatto lato backend (guida 28, D-054)
6. Codice di accesso via SMS da numero verificato

## Criteri di "fatto" (dal brief)

| Criterio | Stato | Dove si vede |
|---|---|---|
| Accesso via email e via SMS funzionanti, con limiti e tentativi provati | email ✅ (richiesta, verifica, sessione); SMS al pezzo 6 | `LoginCodeServiceTest`, `LoginCodeApiTest`, `SessionApiTest` |
| I due DevHeader rimossi; nessun test li usa più | ✅ | `SessionCurrentPractitioner`, `SessionCurrentCustomer`, `TestSessions`; nessun `X-Practitioner-Id` o `X-Customer-Id` nel codice |
| Test della riservatezza esistono e sono verdi | ✅ | `ConfidentialityTest` |
| Ogni area solo al suo ruolo; account disattivati fuori | ✅ | `ConfidentialityTest`, `SessionApiTest` |
| L'admin crea e disattiva professionisti; primo admin senza segreti | ✅ (`ADMIN_EMAIL` all'avvio, solo nel `.env`) | `PractitionerAdminApiTest`, `AdminBootstrapTest`, `AdminBootstrapStartupTest` |
| Email in Mailpit per ogni evento; un invio fallito non annulla la prenotazione | ✅ (email dopo il commit in un altro thread; un invio rifiutato non tocca la prenotazione né l'altra email) | `BookingNotificationsTest`, `MailpitDeliveryTest`, `BookingEmailsTest` |
| Push con riserva email provata | ✅ con un finto servizio push e un finto browser che decifra: push accettata → niente email al professionista; nessun dispositivo, 410 o 500 → email. Servizio push vero al passo 13 | `BookingNotificationsTest`, `PushEncryptionTest` (vettore RFC 8291), `VapidTest`, `PushSubscriptionApiTest` |
| Punto aperto 7 chiuso; punto 5 spostato al passo 14; nuovo punto sul fornitore SMS | punto 5 spostato; 7 e fornitore SMS al pezzo 6 | `stato.md`, `requisiti.md` |
| `modello-dati.md` aggiornato; test verdi; branch e PR | V5 e V6 descritte, note del pezzo 3, nessuna migrazione nel pezzo 4, V7 nel pezzo 5 (descritta); 601 verdi; branch aperto, PR a fine passo | `modello-dati.md` |

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

### Pezzo 4 — Notifiche email
- `notification/BookingNotifications`: l'ascoltatore `@Async` + `@TransactionalEventListener` di `BookingEvent`; raccoglie i dati dai moduli, spedisce ogni email per conto suo, registra gli errori senza indirizzi.
- `notification/BookingEmails`: la funzione pura che da evento e dati fa le email (`OutgoingEmail`), con uno `switch` senza `default`, *text block* e date a Roma in italiano. `BookingFacts` raccoglie i dati.
- `notification/NotificationConfiguration`: `@EnableAsync` e l'executor `notificationExecutor`, un virtual thread per email.
- `booking/BookingEvent`: `serviceId` in tutti gli eventi.
- `customer/CustomerService.contactOf` (`CustomerContact`), `practitioner/PractitionerService.contactOf` (`PractitionerContact`), `catalog/CatalogService.serviceNameOf`.
- `notification.app-url` in `application.properties`, `APP_URL` in `.env.example`.
- `pom.xml`: i test girano con lingua inglese e fuso UTC, come il server (`maven-surefire-plugin`, `argLine`).
- Test: `BookingEmailsTest` (unitario), `BookingNotificationsTest` (commit veri, `RecordingMailSender`, Awaitility), `MailpitDeliveryTest` (Mailpit in un container, letto con la sua API), e casi nuovi in `CustomerServiceTest`, `PractitionerServiceTest`, `CatalogServiceTest`.

### Pezzo 5 — Notifiche push
- `V7__create_push_subscription.sql`: i dispositivi, riferiti a `practitioner`, `endpoint` unico.
- `notification/P256`: chiavi P-256 nei formati di Web Push (65 byte non compressi, 32 byte grezzi, Base64url), con il controllo che il punto stia sulla curva.
- `notification/PushEncryption`: la cifratura RFC 8291 (ECDH, HKDF, AES-128-GCM, intestazione `aes128gcm`).
- `notification/Vapid`: il JWT ES256 di RFC 8292 e l'intestazione `Authorization: vapid t=..., k=...`; fail fast sulle chiavi.
- `notification/WebPushClient`: il `POST` con `HttpClient` del JDK, `TTL`, `Urgency`, l'esito (`ACCEPTED`, `GONE`, `FAILED`).
- `notification/PushSender`: la push a ogni dispositivo di un professionista, i 404/410 cancellati.
- `notification/PushMessages` (funzione pura) e `PushMessage` con `PushPayload`.
- `notification/PushSubscriptionController`, `PushSubscriptionService`, `PushSubscriptionRepository` (`on conflict do update`, i 10 più recenti, cancellazione dei propri), `PushSubscription`, `NewPushSubscription`, i vincoli `@PushEndpoint` e `@BrowserPublicKey`.
- `notification/BookingNotifications`: prima la push, poi l'email al professionista solo come riserva.
- `tools/GenerateVapidKeys.java`; `notification.push.vapid.*` in `application.properties`; `VAPID_*` in `.env.example`; le chiavi dell'RFC nelle proprietà dei test.
- Test: `PushEncryptionTest` (vettore RFC), `VapidTest`, `PushMessagesTest`, `PushEndpointTest`, `PushSubscriptionApiTest`, casi nuovi in `BookingNotificationsTest`; `FakePushService` (server HTTP del JDK) e `FakeBrowser` (la decifratura, scritta a parte).

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
- **Eventi dopo il commit, in un altro thread.** `@TransactionalEventListener` consegna l'evento solo se il commit riesce; `@Async` lo passa a un executor. Insieme: la risposta non aspetta la posta, e un errore di posta non la raggiunge. Senza `@Async`, un'eccezione in `AFTER_COMMIT` arriva al chiamante dopo il commit: un 500 per un'operazione riuscita.
- **Virtual thread.** Thread della JVM, non del sistema operativo: quando aspettano la rete vengono parcheggiati. Adatti a compiti che aspettano molto e calcolano poco, come un'email; non si mettono in un pool.
- **Funzione pura per la parte con più casi.** I testi delle email non leggono e non spediscono niente: si provano senza Spring, in millisecondi. L'ascoltatore, che ha meno casi, si prova con Spring.
- **Un bean di test può spegnere un'autoconfigurazione.** Spring Boot crea il suo `JavaMailSender` solo se non esiste un `MailSender` (`@ConditionalOnMissingBean`): un finto `MailSender` non lo sostituisce, lo fa sparire.
- **Asincrono nei test.** Awaitility ripete un'asserzione fino a un tempo massimo; per un "non succede niente", `during(...)`: la condizione deve restare vera per tutto l'intervallo.
- **Web Push.** Browser, servizio push del produttore, nostro server. L'iscrizione è `endpoint` + chiave pubblica del browser + segreto `auth`; il server cifra per quel browser, firma con VAPID, e fa `POST` all'`endpoint`.
- **ECDH, HKDF, AES-GCM.** Due parti arrivano allo stesso segreto senza trasmetterlo; HKDF ne ricava chiavi diverse per usi diversi; AES-GCM cifra e autentica. Una coppia di chiavi nuova per ogni messaggio.
- **Vettori di prova.** Un RFC con un esempio completo permette di verificare un'implementazione byte per byte: è ciò che separa "comporre primitive standard" da "scrivere la propria crittografia".
- **Firma DER o P1363.** La stessa firma ECDSA ha due formati; JWT vuole `r | s`, 64 byte.
- **SSRF.** Se il server chiama URL dati dal client, un elenco degli host ammessi, controllato all'ingresso.
- **`on conflict do update`.** L'*upsert* in un'istruzione sola; `excluded` è la riga che si voleva inserire.
- **Una riserva decisa dall'esito.** L'email parte se nessun dispositivo ha accettato la push, non "per sicurezza".
- **Java moderno usato qui:** `sealed interface` e *record pattern* (21), `record` con costruttore compatto come configurazione, *text block* (15), `RandomGenerator` (17), `HexFormat` (17), `List.reversed()` e `getFirst()` delle *sequenced collection* (21), virtual thread (21, pezzo 4), `switch` con *pattern matching* su una `sealed interface` senza `default` (21, pezzo 4), `Thread.isVirtual()` nei test; `HttpClient` del JDK (11) chiuso come `AutoCloseable` (21); un programma di un solo file lanciato senza compilarlo (JEP 330, `tools/GenerateVapidKeys.java`).

## Decisioni
- **D-050** — codice di accesso via email (precisa **D-014**: l'account nasce alla prima verifica giusta; l'impronta è un HMAC).
- **D-051** — sessione e Spring Security.
- **D-052** — admin e professionisti.
- **D-053** — notifiche email (completa **D-039** e **D-046**: `serviceId` negli eventi, `@Async` dopo il commit, nessuna *outbox*).
- **D-054** — notifiche push (completa **D-032** e **D-039**; precisa `modello-dati.md`: dispositivi riferiti a `practitioner`).

## Errori fatti e come sono stati corretti

1. **Un generatore non crittografico per una chiave segreta.** Nel primo `.env.example` la riga suggerita per generare `AUTH_CODE_HMAC_KEY` in PowerShell usava `Get-Random`, che non è un generatore crittografico (come `java.util.Random`). Una chiave generata così sarebbe prevedibile. Corretta con `RandomNumberGenerator` di .NET, e una riga che avvisa di non usare `Get-Random`. La lezione è la stessa della guida 24, 3.5, applicata agli strumenti fuori da Java.
2. **Test verdi al primo colpo: provato che sanno fallire.** Non è un errore del codice, ma il motivo per cui ci si fida dei test. Con l'errore del 4.1 introdotto apposta, tre test diventano rossi. Senza `@Lock`, il test delle ipotesi in parallelo fallisce con *"Expected size: 4 but was: 10"*.
3. **(Pezzo 2) Le proprietà del cookie non arrivavano nei test.** `server.servlet.session.cookie.*` vale per Spring Session solo con Tomcat vero. Il test sul cookie l'ha scoperto: il cookie è diventato un bean.
4. **(Pezzo 2) La cache di Hibernate nei test `@Transactional`.** Le richieste MockMvc di un test condividono la transazione e la cache di primo livello: un `update` con SQL diretto non si vedeva. `flush()` e `clear()` intorno all'`update`.
5. **(Pezzo 2) La chiave nel `.env` tra virgolette.** Spring legge il `.env` come file di proprietà e tiene le virgolette nel valore. La chiave non era più Base64, e l'avvio si è fermato con il messaggio di `LoginCodeHasher`, come previsto. È l'errore del passo 4 (D-018). Da correggere togliendo le virgolette.
6. **(Pezzo 2, errore di Claude) Un backend lasciato acceso.** Nella prova del pezzo 1, fermando `mvnw spring-boot:run` si chiudeva Maven ma non il processo Java figlio, che è rimasto sulla porta 8080 per un'ora. Alla prova del pezzo 2 la porta era occupata. Da allora si ferma il processo che ascolta sulla porta, e il suo padre.
7. **(Pezzo 3) Un dubbio verificato invece che supposto.** Non era chiaro se `@Generated` avrebbe escluso le colonne anche dagli `update`. Invece di fidarsi della documentazione, un test di prova ha stampato l'SQL di un `insert` e di un `update`: le colonne restano negli `update`, e l'`insert` le rilegge con `returning`. Poi il test di prova è stato cancellato, e due test veri hanno preso il suo posto.
8. **(Pezzo 3, errore di Claude) Uno script che diceva "nessun fallimento" senza aver eseguito niente.** La prova che i test sanno fallire l'ha fatta prima uno script Python, che non riusciva a lanciare Maven su Windows e leggeva i risultati vecchi. Quattro mutazioni, quattro "nessun test fallito": un risultato impossibile, e il segnale per controllare. Rifatta a mano dalla shell, ogni mutazione è stata presa. La lezione: un verde troppo veloce va guardato come un rosso.
9. **(Pezzo 4) Un finto `MailSender` che ha spento quello vero.** Il primo `RecordingMailSender` implementava `MailSender`. Spring Boot crea il suo `JavaMailSender` solo se non esiste già un `MailSender`: vedendo il finto si è tirato indietro, e `EmailLoginCodeSender`, che chiede un `JavaMailSender`, è rimasto senza. Il contesto non partiva. Corretto facendo estendere al finto `JavaMailSenderImpl`: sostituisce il mittente vero per intero. La lezione: un bean di test non si aggiunge soltanto, può cambiare quali autoconfigurazioni scattano.
10. **(Pezzo 4, errore di Claude) Un totale stampato da un comando che non era partito.** Il primo `mvnw clean test` della suite completa aveva un percorso del log sbagliato: la shell ha rifiutato il comando prima di lanciarlo, e il conteggio successivo ha sommato i report vecchi rimasti in `target`, dando un totale credibile. Mancava la riga con il codice di uscita di Maven. Rilanciato con il percorso giusto e il codice di uscita stampato. È lo stesso errore del punto 8: un risultato si guarda insieme alla prova che il comando sia girato davvero.
11. **(Pezzo 4) Un test che sul PC di sviluppo non poteva fallire.** La JVM di questo PC è in italiano: togliere `Locale.ITALIAN` dai testi lasciava i test verdi, ma su un server in inglese le date sarebbero uscite in inglese ("Thursday"). Ora i test girano con lingua inglese e fuso UTC (`argLine` di Surefire), come il server: la stessa mutazione fa fallire i test.
12. **(Pezzo 5, errore di Claude) Le mutazioni che si contaminavano a vicenda.** Lo script che ripristinava il codice dopo ogni mutazione *spostava* la copia di backup al posto del file. Una copia spostata conserva la sua data di modifica, più vecchia della classe compilata dalla mutazione, e Maven poteva tenere in `target` la classe mutata. Sintomo: togliendo il controllo della curva falliva anche il test del vettore RFC, che non c'entrava (l'intestazione era giusta, il testo cifrato no: era ancora la mutazione precedente su `key_info`). Con `mvnw clean` falliva solo il test giusto. Corretto riscrivendo il file invece di spostarlo, e rifatte tutte e dieci le mutazioni con `clean`. La lezione: un risultato strano di un test va spiegato, non contato.
13. **(Pezzo 5) Un *text block* Java dentro uno script Python.** Lo script che aggiungeva i test usava le stringhe `"""` di Python, e il `"""` di un *text block* Java le chiudeva a metà: lo script si è fermato con un errore di sintassi prima di scrivere, quindi senza danni. Riscritto con `'''`.
14. **Porta 5432 occupata** durante la prova a mano: in locale gira già il database di un altro progetto. Il PostgreSQL di questo progetto è stato avviato sulla 5433 solo per la prova (`POSTGRES_PORT=5433` davanti al comando). Da ricordare se l'avvio di Compose fallisce con "port is already allocated".

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

### Domanda 6: Un test con un bean finto fa fallire l'avvio con "No qualifying bean" per un bean che non hai toccato. Cosa può essere successo?
**Risposta modello.** Il bean finto può aver spento un'autoconfigurazione. Molte autoconfigurazioni di Spring Boot hanno `@ConditionalOnMissingBean`: se trovano già un bean di quel tipo, non creano il loro. Qui un finto `MailSender` ha fatto sparire il `JavaMailSender` di Boot, che serviva a un altro componente. Si guarda il *condition evaluation report* (`debug=true`), e si fa in modo che il finto sostituisca il tipo completo, o lo si dichiara accanto a quello vero con `@Primary`.

### Domanda 7: I test passano sul tuo PC e falliscono sul server, o il contrario. Cosa controlli prima?
**Risposta modello.** Le cose che il codice prende dall'ambiente senza dirlo: lingua (`Locale` di default), fuso orario, codifica dei caratteri, separatore di riga. Il codice le deve dire in modo esplicito (`Locale.ITALIAN`, `ZoneId` del dominio, UTF-8), e i test conviene farli girare con i valori del server, così una dimenticanza si vede subito: qui lingua inglese e fuso UTC nell'`argLine` di Surefire.

### Domanda 8: Come verifichi un'implementazione crittografica scritta componendo primitive standard?
**Risposta modello.** Con i vettori di prova pubblicati dallo standard: stesse chiavi, stesso sale, stesso testo, e il risultato deve essere identico byte per byte. Poi un giro completo con un ricevitore scritto a parte, che decifra ciò che il mittente produce con valori casuali. E le mutazioni: tolgo un byte da una stringa `info`, cambio il formato della firma, e i test devono fallire. Resta da provare l'interoperabilità con i servizi veri, che si fa con un dispositivo reale.

### Domanda 9: Un utente può registrare un URL a cui il tuo server farà richieste. Come lo proteggi?
**Risposta modello.** È il caso tipico di SSRF. Accetto solo `https` sulla porta standard e solo host di un elenco chiuso (qui i servizi push dei browser e i loro sottodomini), con un confronto sull'host intero e sul suffisso preceduto da un punto, così `fcm.googleapis.com.example.com` non passa. Il controllo è all'ingresso, come vincolo di validazione: un 400 sul campo. Non scrivo l'URL intero nei log.
