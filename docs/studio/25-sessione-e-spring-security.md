# Sessione e Spring Security — guida di studio

Questa guida accompagna il **pezzo 2 del passo 8**. Il pezzo 1 sa dire "questo codice è giusto, l'utente è il numero 42" (`LoginCodeService.verify`). Ora il sistema deve **ricordarsene** alle richieste successive, con una sessione e un cookie, e decidere chi può entrare in quale area. I due sostituti provvisori (`DevHeaderCurrentPractitioner`, `DevHeaderCurrentCustomer`) spariscono, e con loro gli header `X-Practitioner-Id` e `X-Customer-Id` di tutti i test.

```
POST   /api/public/auth/session   {"email": "...", "code": "482913"}  → 200 {"role": "CUSTOMER"} + cookie
GET    /api/public/auth/session                                        → 200 {"role": ...} oppure 401
DELETE /api/public/auth/session                                        → 204 (uscita)
```

Versioni verificate per Spring Boot 4.1.2: **Spring Security 7.1.2**, **Spring Session 4.1.1**. Esistono `spring-boot-starter-security`, `spring-boot-starter-security-test` e `spring-boot-starter-session-jdbc`.

Teoria di riferimento: D-014 (sessione in cookie `HttpOnly`), D-017 (un solo dominio grazie a Caddy), D-021 (tre ruoli, un ruolo per utente), D-028 e D-045 (i sostituti da togliere), D-029 (una regola per prefisso), D-050 (verifica del codice, account disattivati), requisiti "Riservatezza del calendario".

---

## Indice

- Parte 1 — Cosa si costruisce
- Parte 2 — Come ragiona Spring Security: catena di filtri, autenticazione e autorizzazione
- Parte 3 — Prima scelta: dove avviene l'accesso
- Parte 4 — Seconda scelta: cosa sta nella sessione, dove, e per quanto (la tua domanda)
- Parte 5 — Il cookie, CSRF e la *session fixation*
- Parte 6 — Terza scelta: le regole per area, e le risposte 401 e 403
- Parte 7 — Il profilo del cliente e i due "corrente"
- Parte 8 — Quarta scelta: come si autenticano i test
- Parte 9 — I test della riservatezza
- Parte 10 — Le scelte piccole
- Parte 11 — Cosa c'è da decidere
- Domande da colloquio

---

## Parte 1 — Cosa si costruisce

| Pezzo | Dove | Cosa fa |
|---|---|---|
| `SecurityConfiguration` | `auth` | La catena di filtri: regole per prefisso, CSRF, risposte 401/403 |
| `SessionAccounts` (un `SecurityContextRepository`) | `auth` | A ogni richiesta trova l'utente della sessione e controlla che sia ancora attivo (parte 4) |
| `SessionController` | `auth` | Entrata (`POST`), "chi sono" (`GET`), uscita (`DELETE`) su `/api/public/auth/session` |
| `CurrentUser` | `auth` | Per gli altri moduli: l'id e il ruolo di chi agisce |
| `SessionCurrentPractitioner` | `practitioner` | Sostituisce `DevHeaderCurrentPractitioner`: utente → `practitioner.user_id` |
| `SessionCurrentCustomer` | `customer` | Sostituisce `DevHeaderCurrentCustomer`: utente → `customer.user_id`, oppure `PROFILE_INCOMPLETE` |
| `GET` e `PUT /api/customer/profile` | `customer` | Nome e cognome dopo il primo accesso (D-050) |
| `V6__create_spring_session.sql` | — | Le due tabelle di Spring Session (parte 4) |
| Test della riservatezza | test | Il criterio di "fatto" di `stato.md` (parte 9) |

Le dipendenze tra moduli restano in un verso solo: `practitioner` → `auth`, `customer` → `auth`. `auth` continua a non dipendere da nessuno. La configurazione conosce i **prefissi** delle altre aree, ma non le loro classi.

---

## Parte 2 — Come ragiona Spring Security

### 2.1 Una catena di filtri davanti ai controller

Un **filtro** (*servlet filter*) è un pezzo di codice che vede passare ogni richiesta **prima** che arrivi al controller, e la risposta dopo. Spring Security è una catena di filtri, ognuno con un compito:

```
richiesta → [SecurityContextHolderFilter] → [CsrfFilter] → [LogoutFilter] → ... →
            [AnonymousAuthenticationFilter] → [ExceptionTranslationFilter] → [AuthorizationFilter] → controller
```

- `SecurityContextHolderFilter` chiede a un `SecurityContextRepository` "chi c'è in questa richiesta?" e mette la risposta nel `SecurityContextHolder`, una variabile legata al thread della richiesta.
- `CsrfFilter` rifiuta le richieste che cambiano qualcosa senza il token CSRF (parte 5).
- `AnonymousAuthenticationFilter`: se nessuno si è presentato, la richiesta è "anonima".
- `AuthorizationFilter` applica le regole: "per `/api/panel/**` serve il ruolo `PRACTITIONER`".
- `ExceptionTranslationFilter` trasforma i rifiuti in risposte: **401** se manca l'identità, **403** se l'identità c'è ma non basta.

Il punto importante per noi: tutto questo succede **prima del controller**. L'`ApiExceptionHandler` non vede questi rifiuti, perché lavora dentro Spring MVC, più avanti. Le risposte 401 e 403 vanno quindi scritte dai gestori di Spring Security (parte 6.3).

### 2.2 Autenticazione e autorizzazione

- **Autenticazione** (*authentication*): **chi sei**. Da noi: il codice giusto, poi il cookie della sessione.
- **Autorizzazione** (*authorization*): **cosa puoi fare**. Da noi, a due livelli:
  1. **per area**, nella catena di filtri: un cliente non entra in `/api/panel/**`, e la risposta è 403;
  2. **per oggetto**, nei servizi, com'è già oggi: il professionista A che chiede una prenotazione di B riceve 404 (D-026). La catena di filtri non può saperlo, perché non conosce le prenotazioni.

I due livelli non si sostituiscono. Il primo è grossolano, ma impossibile da dimenticare. Il secondo è preciso, e c'è già.

---

## Parte 3 — Prima scelta: dove avviene l'accesso

### 3.1 Strada A — Un controller che chiama la verifica e apre la sessione

`POST /api/public/auth/session` riceve email e codice e chiama `LoginCodeService.verify`. Poi decide con uno `switch` su `CodeCheck` (D-050):

```java
return switch (codes.verify(body.email(), body.code())) {
    case Valid(long userId, boolean newAccount) -> signIn(request, userId);   // 200 + cookie
    case Wrong() -> problem(UNAUTHORIZED, "CODE_WRONG");
    case Expired() -> problem(UNAUTHORIZED, "CODE_EXPIRED");
    case AccountDeactivated() -> problem(FORBIDDEN, "ACCOUNT_DEACTIVATED");
};
```

Tutto il percorso si legge in una classe, e la logica resta nel servizio del pezzo 1, già provato.

### 3.2 Strada B — Un filtro e un `AuthenticationProvider` nostri

È il modo "classico" di Spring Security. Un filtro come `UsernamePasswordAuthenticationFilter` legge email e codice, e un `AuthenticationProvider` li controlla, lanciando `BadCredentialsException` se sono sbagliati. Si integra con gli eventi di Spring Security, ma servono tre o quattro classi in più. E i nostri quattro esiti diventerebbero eccezioni: proprio quello che il pezzo 1 ha evitato (D-050, guida 24, 4.1).

### 3.3 Strada C — Il *One-Time Token Login* di Spring Security

Da Spring Security 6.4 esiste `http.oneTimeTokenLogin()`: l'utente riceve un **link** con un token casuale e accede cliccandolo. Sembra fatto per noi, ma non lo è. Non conosce i 6 cifre da digitare, i 5 tentativi, i limiti per email e IP, l'SMS del pezzo 6, o il primo accesso che crea l'account. Andrebbe piegato fino a riscriverlo. In più un link è più scomodo di un codice da telefono a computer, e i filtri antispam delle aziende "cliccano" i link per controllarli.

### 3.4 Raccomandazione

**Strada A.** Spring Security fa ciò che sa fare meglio (filtri, regole, CSRF), il nostro dominio fa il resto. Il controller però deve fare a mano due cose che altrimenti farebbe Spring:
- **cambiare l'id della sessione** all'accesso (*session fixation*, parte 5.3);
- **salvare** l'utente nella sessione. Da Spring Security 6 il salvataggio non è più automatico (*explicit save*): chi autentica fuori dai filtri standard deve farlo da sé, altrimenti alla richiesta successiva l'utente non c'è più. È un errore classico.

---

## Parte 4 — Seconda scelta: cosa sta nella sessione, dove, e per quanto

### 4.1 Sessione o token (JWT)?

Ci sono due modi di ricordarsi di un utente.

- **Sessione**: il server conserva i dati ("la sessione `a1b2…` è dell'utente 42") e al browser dà solo un identificativo casuale, nel cookie. Ogni richiesta porta il cookie, e il server guarda chi è.
- **Token firmato (JWT)**: il server non conserva niente. Dà al browser un testo firmato, del tipo "sono l'utente 42, ruolo CUSTOMER, valido fino al…", e a ogni richiesta controlla solo la firma.

Il JWT ha senso quando molti servizi diversi devono riconoscere l'utente senza un archivio comune, o con app native e domini diversi. Da noi c'è **un solo backend su un solo dominio** (D-017), e il JWT porterebbe solo problemi:
- **non si revoca**: un account disattivato resterebbe dentro fino alla scadenza del token. Per evitarlo servono token brevi più un *refresh token* salvato sul server, cioè di nuovo uno stato sul server, con il doppio del codice;
- **dove lo tieni?** In `localStorage` lo legge qualunque script, quindi anche un attacco XSS. In un cookie torni al problema CSRF della sessione;
- con un ruolo cambiato o un account disattivato, il token dice ancora il vecchio.

**La sessione basta, ed è più sicura per questo caso.** Ai colloqui è una domanda frequente, e "dipende, ecco perché" è la risposta giusta.

### 4.2 Cosa mettere nella sessione

**Strada A — Il `SecurityContext` intero, salvato da Spring (`HttpSessionSecurityContextRepository`).** È il comportamento standard: Spring serializza l'oggetto `Authentication`, con l'utente e il suo ruolo, dentro la sessione. Ma la sessione ricorda il ruolo **del momento dell'accesso**. Un account disattivato ieri resta dentro con la sessione aperta, e serve un filtro in più che controlli a ogni richiesta. In più, oggetti di Spring Security serializzati e salvati per 30 giorni possono non rileggersi più dopo un aggiornamento di versione.

**Strada B — Nella sessione solo l'id dell'utente, e l'identità si ricostruisce a ogni richiesta.** Un nostro `SecurityContextRepository` (`SessionAccounts`) legge dalla sessione l'id 42. Poi chiede al database l'account (una query per chiave primaria) e:
- se l'account è attivo, costruisce l'`Authentication` con il ruolo **attuale**;
- se è disattivato, o non esiste più, **invalida la sessione** e la richiesta diventa anonima, quindi 401.

È esattamente il criterio "account disattivati fuori anche nelle sessioni già aperte" del brief, ottenuto per costruzione e non con un controllo aggiunto. Funziona anche se qualcuno disattiva un account a mano con SQL. La sessione contiene due numeri (l'id e l'istante dell'accesso, parte 4.4), quindi niente da serializzare di delicato.

Il costo è una query in più per ogni richiesta autenticata. Con due professionisti e qualche decina di clienti al giorno è irrilevante.

**Raccomandazione: B.** È anche un buon esempio di come si estende Spring Security: non si riscrive la catena, si sostituisce il **pezzo** che risponde a "chi c'è?".

### 4.3 Dove vive la sessione

| Strada | Dove | Problema |
|---|---|---|
| A — Memoria di Tomcat (il default) | nella JVM | a ogni riavvio, cioè a ogni nuova versione, **escono tutti**; con due istanze, ognuna ha le sue sessioni |
| B — **Spring Session JDBC** | due tabelle in PostgreSQL | una query in più per richiesta; la pulizia delle sessioni scadute la fa Spring Session da solo, ogni minuto |
| C — Spring Session con Redis | un container Redis | un servizio in più da installare, salvare e aggiornare, per pochi utenti |

**Raccomandazione: B.** Per sessioni di 30 giorni la memoria non va bene. Spring Session sostituisce la sessione di Tomcat in modo trasparente: il codice usa sempre `HttpSession`, ma i dati finiscono nel database. Due regole del progetto da rispettare:
- lo schema **non** lo crea Spring all'avvio (`spring.session.jdbc.initialize-schema=never`): lo crea una **migrazione Flyway**, la V6, copiando lo `schema-postgresql.sql` di Spring Session 4.1.1 (tabelle `spring_session` e `spring_session_attributes`). È verificato che il file c'è;
- le tabelle sono di Spring Session, non nostre: nomi e colonne restano i suoi, e `ddl-auto=validate` non le controlla, perché non sono entità.

### 4.4 Per quanto: la tua domanda

Sì, il cliente resta dentro senza rifare il codice. Le durate proposte:

- **Inattività: 30 giorni.** Ogni richiesta sposta in avanti la scadenza (`spring.session.timeout=30d`). Chi torna almeno una volta al mese non rivede il codice.
- **Durata massima: 90 giorni dall'accesso**, anche per chi usa il sito tutti i giorni. È la raccomandazione OWASP (*absolute timeout*): una sessione rubata non vale per sempre. La controlla `SessionAccounts` con l'istante dell'accesso salvato nella sessione.
- **Il cookie deve durare.** Per default il cookie di sessione **sparisce alla chiusura del browser**. Con `Max-Age` di 90 giorni resta sul telefono anche dopo un riavvio. Spring Session lo scrive all'accesso, quindi scade proprio 90 giorni dopo: coincide con la durata massima.

Per i professionisti vale lo stesso. Il pannello sarà un'app installata sul telefono (D-032), e un professionista che deve rifare il codice ogni settimana smette di usarla.

---

## Parte 5 — Il cookie, CSRF e la *session fixation*

### 5.1 Gli attributi del cookie (architettura, "Accesso e sessioni")

- **`HttpOnly`**: JavaScript non può leggerlo. Uno script malevolo iniettato nella pagina (XSS) non può rubare la sessione.
- **`Secure`**: il cookie viaggia solo su HTTPS. In sviluppo i browser trattano `http://localhost` come sicuro, quindi funziona anche lì.
- **`SameSite=Lax`**: il browser **non** manda il cookie nelle richieste che cambiano qualcosa (`POST`, `PUT`, `DELETE`) partite da un altro sito. Lo manda solo quando l'utente apre un link verso il nostro sito.

### 5.2 CSRF: perché serve con i cookie

**CSRF** (*Cross-Site Request Forgery*): un sito malevolo contiene un modulo nascosto che fa `POST /api/panel/bookings/7/reject` sul nostro dominio. Il browser del professionista, già collegato, **aggiunge il cookie da solo**, e la richiesta parte a suo nome senza che lui lo sappia. Il problema esiste proprio perché il cookie viaggia in automatico. Con un token in un header scritto dal codice JavaScript, non ci sarebbe.

`SameSite=Lax` blocca già il caso più comune. Ma OWASP lo considera una **seconda** difesa, non l'unica:
- "stesso sito" non vuol dire "stessa origine": un sottodominio dello stesso dominio (un domani `blog.studio.it`) conta come stesso sito, e le sue richieste portano il cookie;
- dipende dal browser e dalle sue regole, che sono cambiate negli anni.

La difesa principale è il **token CSRF** (*synchronizer token*): il server dà al frontend un valore casuale, e ogni richiesta che cambia qualcosa lo deve rimandare in un **header**. Un sito estraneo non può leggerlo, quindi non può copiarlo. Spring Security lo fa **già per default**, e in Spring Security 7 c'è una configurazione pronta per le applicazioni a pagina singola come la nostra in Next.js:

```java
http.csrf(csrf -> csrf.spa());
```

Il token arriva in un cookie `XSRF-TOKEN` leggibile da JavaScript (solo dal nostro dominio). Il frontend lo rimanda nell'header `X-XSRF-TOKEN`. **Verificato** che `spa()` esiste nella 7.1.2.

Anche l'**accesso** è protetto. C'è un attacco meno noto, il *login CSRF*: un sito estraneo fa entrare la vittima nell'account **dell'attaccante**, e ciò che la vittima scrive dopo finisce nell'account sbagliato. Quindi niente eccezioni per `/api/public/**`. L'unica eccezione arriverà al passo 9, per il webhook di Stripe, che si protegge con la firma.

**Raccomandazione:** CSRF acceso ovunque con `csrf.spa()`, più `SameSite=Lax`. Due difese indipendenti.

### 5.3 *Session fixation*

L'attaccante ottiene un id di sessione valido, per esempio aprendo il sito, e riesce a farlo usare alla vittima. Quando la vittima accede, se l'id non cambia, l'attaccante si ritrova con una sessione autenticata. La difesa: **all'accesso l'id della sessione cambia**. Il controller chiama `request.changeSessionId()` se una sessione c'era già, altrimenti ne crea una nuova. All'uscita la sessione si invalida, e il cookie si cancella.

Un test lo prova: id prima dell'accesso, id dopo, diversi; il vecchio id non vale più.

---

## Parte 6 — Terza scelta: le regole per area, e le risposte

### 6.1 Le regole (D-029)

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/public/**").permitAll()
        .requestMatchers("/api/panel/**").hasRole("PRACTITIONER")
        .requestMatchers("/api/customer/**").hasRole("CUSTOMER")
        .requestMatchers("/api/admin/**").hasRole("ADMIN")
        .requestMatchers("/api/dev/**").permitAll()      // esiste solo con booking.simulated-payments=true
        .anyRequest().denyAll());
```

- **`denyAll()` e non `authenticated()`** per il resto. Con `authenticated()`, un'API messa per sbaglio fuori dai prefissi (`/api/bookings`) sarebbe aperta a **chiunque abbia un account**, cioè a qualunque cliente. Con `denyAll()` è chiusa a tutti, e il test lo scopre subito. È "una regola sola" di D-029 presa alla lettera.
- **`/api/dev/**` aperto.** Il pagamento simulato (D-046) fa la parte di Stripe, che non ha una sessione. Il controller esiste solo con la proprietà accesa. Senza, l'indirizzo dà 404, e comunque la regola si toglie al passo 9 insieme al controller.
- **Il ruolo nel codice.** `hasRole("PRACTITIONER")` cerca l'autorità `ROLE_PRACTITIONER`. `SessionAccounts` la costruisce dal `Role` dell'account (D-021: un ruolo solo).

### 6.2 Una trappola di Spring Security 6+: gli errori interni

Da Spring Security 6 le regole valgono anche per le richieste "interne", come l'inoltro a `/error` quando un controller fallisce. Con `denyAll()`, un 500 dentro un'API aperta potrebbe diventare un 401 o un 403, nascondendo l'errore vero. Da noi succede di rado, perché `ApiExceptionHandler` risponde già dentro Spring MVC. Ma la regola `.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` lo evita del tutto. Da verificare con un test.

### 6.3 401, 403, 404: chi risponde cosa

| Caso | Risposta | Chi la scrive |
|---|---|---|
| Nessuna sessione su un'area chiusa | **401** | `AuthenticationEntryPoint` di Spring Security |
| Sessione con il ruolo sbagliato (cliente nel pannello) | **403** | `AccessDeniedHandler` |
| Token CSRF mancante o sbagliato | **403**, codice `CSRF_TOKEN_INVALID` | `AccessDeniedHandler` (per Spring è un rifiuto come gli altri) |
| La prenotazione di un altro | **404** | il servizio, com'è oggi (D-026) |
| Indirizzo fuori da tutti i prefissi | 401 da anonimo, 403 con sessione | `denyAll()` |

Le prime tre vanno scritte come `ProblemDetail` (RFC 9457), come tutte le altre risposte d'errore. Lo fanno due piccole classi nostre, perché `ApiExceptionHandler` non le vede (parte 2.1).

Perché 401 e non 404 per l'anonimo? Il 404 per "non tuo" serve a non rivelare che una **cosa** esiste. Che esista l'**area** del pannello non è un segreto. Al frontend serve sapere "accedi" (401) o "qui non puoi" (403).

---

## Parte 7 — Il profilo del cliente e i due "corrente"

### 7.1 `CurrentUser` in `auth`

```java
public interface CurrentUser {
    long id();      // NotAuthenticatedException (401) se nessuno è entrato
    Role role();
}
```

Legge il `SecurityContextHolder` riempito da `SessionAccounts`. Gli altri moduli non toccano mai Spring Security direttamente: chiedono ad `auth`.

### 7.2 I sostituti dei DevHeader

- `SessionCurrentPractitioner` (in `practitioner`): `currentUser.id()` → `practitioner` con quel `user_id` → il suo id. Un account `PRACTITIONER` senza riga `practitioner` non dovrebbe esistere: dal pezzo 3 l'admin li crea insieme. Se succede, 401.
- `SessionCurrentCustomer` (in `customer`): `currentUser.id()` → `customer` con quel `user_id`. Se la riga non c'è, il cliente è entrato ma non ha ancora dato nome e cognome: **409 `PROFILE_INCOMPLETE`** (D-050).

Le interfacce `CurrentPractitioner` e `CurrentCustomer` **non cambiano**: era il motivo per cui le avevamo create (D-028). Nessun controller va toccato.

### 7.3 Il profilo

- `GET /api/customer/profile` → nome, cognome ed email, oppure 409 `PROFILE_INCOMPLETE`;
- `PUT /api/customer/profile {firstName, lastName}` → crea la riga `customer` al primo accesso, e poi permette al cliente di **correggere** i propri dati. `PUT` perché è idempotente: ripetuto, dà lo stesso risultato.

Queste due API non passano da `CurrentCustomer`: leggono `CurrentUser`, altrimenti `PUT` non potrebbe mai creare il profilo che manca.

Il cliente creato dal pannello (D-048) ha già il profilo. La risposta dell'accesso dice solo il ruolo. Il frontend del passo 12, dopo l'accesso di un cliente, chiede `GET /api/customer/profile`, e se riceve 409 mostra il modulo "nome e cognome".

---

## Parte 8 — Quarta scelta: come si autenticano i test

Oggi 8 classi di test mandano gli header provvisori, una cinquantina di volte. Vanno tutte riscritte.

**Strada A — `spring-security-test`, con `.with(user(...))`.** Mette nel contesto un utente finto, senza passare dalla sessione. Ma salta proprio `SessionAccounts`, cioè il controllo dell'account attivo e del ruolo attuale: i test proverebbero una cosa diversa da ciò che gira in produzione.

**Strada B — Una sessione vera, creata dal test.** Un aiutante di test crea una sessione in Spring Session, ci scrive l'id dell'utente e restituisce il cookie:

```java
mvc.get().uri("/api/panel/bookings/requests").cookie(sessions.signedIn(annaUserId))
```

Passa da tutta la catena come in produzione: cookie, `SessionAccounts`, ruolo, regole. Le richieste che cambiano qualcosa aggiungono anche `.with(csrf())` di `spring-security-test`, che mette un token CSRF valido.

**Strada C — Accesso completo con il codice in ogni test.** È la più realistica: richiesta del codice, `RecordingLoginCodeSender`, verifica. Ma sono due richieste in più per ogni test, e i limiti del pezzo 1 entrerebbero in test che parlano d'altro.

**Raccomandazione: B** per tutti i test esistenti, e **C** solo nei test dell'accesso (`SessionApiTest`), dove il percorso completo è l'oggetto della prova.

`BookingTestData.practitioner(email)` oggi restituisce l'id del professionista. Per il cookie serve l'id dell'**utente**: l'aiutante lo cerca da sé, oppure `BookingTestData` lo restituisce. È un dettaglio di implementazione.

---

## Parte 9 — I test della riservatezza (criterio di "fatto")

Una classe dedicata, `ConfidentialityTest`, che traduce in test le regole di "Riservatezza del calendario" e delle aree:

| Chi | Cosa | Atteso |
|---|---|---|
| anonimo | liste e dettagli delle prenotazioni di pannello e cliente | 401 |
| anonimo | orari liberi, professionisti, catalogo | 200 (vede solo gli orari liberi, mai chi ha prenotato) |
| professionista A | una prenotazione di B, approvarla, rifiutarla | 404 |
| professionista A | la lista del periodo | solo le proprie |
| cliente | una prenotazione di un altro cliente, ritirarla | 404 |
| cliente | la propria lista | solo le proprie |
| cliente | `/api/panel/**`, `/api/admin/**` | 403 |
| professionista | `/api/customer/**`, `/api/admin/**` | 403 |
| admin | `/api/panel/**`, `/api/customer/**` | 403 (un ruolo per utente, D-021) |
| chiunque | un indirizzo fuori dai prefissi | 401 o 403, mai 200 |
| sessione aperta di un account poi disattivato | qualunque area | 401, e la sessione non vale più |
| richiesta senza token CSRF | un `POST` del pannello | 403 |

Molti 404 "di un altro" esistono già nei test dei pezzi 5–7 con gli header: riscritti con le sessioni, valgono come prova. La classe nuova raccoglie i casi per **ruolo e area**, che oggi non esistono.

In `SessionApiTest`, oltre alla riservatezza:
- accesso completo con codice → 200, cookie con `HttpOnly`, `Secure`, `SameSite=Lax` e `Max-Age`;
- l'id della sessione cambia all'accesso (*session fixation*);
- `GET` dopo l'accesso → il ruolo; `DELETE` → 204, e poi `GET` → 401;
- codice sbagliato → 401 `CODE_WRONG`; scaduto → 401 `CODE_EXPIRED`; account disattivato → 403 `ACCOUNT_DEACTIVATED`;
- inattività oltre 30 giorni e durata oltre 90 giorni → 401 (con il `MovableClock`; per l'inattività si controlla la configurazione di Spring Session);
- primo accesso di un cliente → `GET /api/customer/profile` 409, `PUT` 200, poi le prenotazioni funzionano.

---

## Parte 10 — Le scelte piccole

- **`formLogin` e `httpBasic` spenti.** Non ci sono password. Va evitato anche l'utente con password generata che Spring Boot crea quando non trova una configurazione: si toglie con la configurazione esplicita.
- **Codici d'errore nuovi**: `CODE_WRONG`, `CODE_EXPIRED` (401), `ACCOUNT_DEACTIVATED` (403), `PROFILE_INCOMPLETE` (409, in `ErrorCode`), `CSRF_TOKEN_INVALID` (403). `ErrorCode` oggi è l'elenco dei 409 (D-027). I codici di accesso hanno stati diversi, ma il frontend li traduce allo stesso modo. Proposta: un `enum` separato `AuthErrorCode` in `auth`, per non mescolare stati HTTP diversi nello stesso elenco.
- **Il corpo della verifica**: `email` con `@NotBlank @ValidEmail`, `code` con `@Pattern("\\d{6}")`. Un codice scritto male è un 400 e non consuma un tentativo: non poteva essere giusto.
- **Nessun limite in più sulla verifica**: i tentativi sono già 5 per codice, e i codici sono limitati per email e per IP (D-050).
- **Il nome del cookie**: `SESSION`, il default di Spring Session. Non dice niente sulla tecnologia. `JSESSIONID` avrebbe detto "Java".
- **`DevHeaderCurrentPractitioner`, `DevHeaderCurrentCustomer`** e le loro costanti `HEADER` si cancellano, e nessun file del repository deve più contenere `X-Practitioner-Id` o `X-Customer-Id` (un `grep` lo verifica).

---

## Parte 11 — Cosa c'è da decidere

| # | Scelta | Raccomandazione |
|---|---|---|
| 1 | Dove avviene l'accesso | Un controller sopra `verify`, con `changeSessionId` e il salvataggio esplicito |
| 2a | Cosa sta nella sessione | Solo l'id dell'utente e l'istante dell'accesso; identità ricostruita a ogni richiesta da `SessionAccounts`, che butta fuori gli account disattivati |
| 2b | Dove vive la sessione | Spring Session JDBC, tabelle con la migrazione V6 |
| 2c | Durata | 30 giorni di inattività, 90 al massimo, cookie con `Max-Age` di 90 giorni |
| 3 | CSRF | Acceso ovunque con `csrf.spa()`, più `SameSite=Lax` |
| 4 | Regole | Una per prefisso, `denyAll()` per il resto, `/api/dev/**` aperto fino al passo 9, errori interni permessi |
| 5 | Profilo | `GET`/`PUT /api/customer/profile`; `PROFILE_INCOMPLETE` da `SessionCurrentCustomer` |
| 6 | Test | Sessione vera creata dall'aiutante + `csrf()`; accesso completo solo in `SessionApiTest` |

---

## Parte 12 — Com'è andata (implementazione)

- Prese tutte le raccomandazioni della parte 11 (D-051).
- **Il cookie, una sorpresa.** Con le proprietà `server.servlet.session.cookie.*` i test mostravano un cookie **senza** `HttpOnly`, `Secure`, `SameSite` e `Max-Age`. Spring Boot le applica al cookie di Spring Session solo quando c'è un Tomcat incorporato vero, e MockMvc non ne ha uno. In produzione avrebbero funzionato, ma senza prova. Soluzione: il cookie dichiarato come bean (`SessionCookieConfiguration`), uguale nei test e in produzione, con `Max-Age` preso da `SessionAccounts.MAX_DURATION`. La lezione: un test che controlla la configurazione può scoprire che la configurazione non arriva dove credi.
- **Una cache che inganna i test.** Due test sugli account disattivati fallivano: l'account risultava ancora attivo. Non era un errore del codice. Il test è `@Transactional`, quindi tutte le sue richieste MockMvc condividono **una** transazione e **una** cache di primo livello di Hibernate. Un `AppUser` letto da una richiesta precedente tornava com'era, senza vedere l'`update` fatto con SQL diretto. In produzione ogni richiesta ha la sua transazione. Soluzione nel test: `flush()` prima dell'`update`, `clear()` dopo.
- **Gli `@Import` non sono serviti.** `TestSessions` è un `@Component` nei sorgenti dei test: `@SpringBootTest` lo trova da solo, perché scandisce il package dell'applicazione, test compresi. Così nessun contesto in cache è cambiato.
- **Il `@WebMvcTest`** (`CatalogPanelErrorsTest`) gira senza filtri (`addFilters = false`): la catena di sicurezza legge gli account dal database, e lì il database non c'è.
- **Una richiesta anonima che cambia qualcosa senza token CSRF** riceve 403 (CSRF), non 401: il controllo CSRF viene prima della domanda "chi sei". I test che provano il 401 anonimo mandano un token valido.
- **Prova che i test sanno fallire**: tolto il controllo dell'account attivo, tolto `changeSessionId`, messo `authenticated()` al posto di `denyAll()`: ogni volta fallisce il test che protegge quella difesa.
- **Prova a mano con Tomcat vero**: `XSRF-TOKEN` alla prima richiesta, 403 senza token, codice da Mailpit, accesso con `Set-Cookie: SESSION=…; Max-Age=7776000; …; Secure; HttpOnly; SameSite=Lax`, poi 409 sul profilo, `PUT`, prenotazioni 200, pannello 403, uscita 204 e poi 401.
- 27 test nuovi, 505 in tutto.

---

## Domande da colloquio

### Domanda 1: Sessione con cookie o JWT? Quando scegli l'uno o l'altro?
**Risposta modello.** La sessione tiene lo stato sul server e dà al client solo un identificativo; il JWT mette lo stato nel token firmato. Il JWT conviene quando servizi diversi devono riconoscere l'utente senza un archivio comune, o con client e domini diversi. Ma non si revoca: un account disattivato resta dentro fino alla scadenza, a meno di token brevi con un *refresh token*, che è di nuovo uno stato sul server. Con un solo backend su un solo dominio la sessione è più semplice e si revoca subito.

### Domanda 2: Cos'è il CSRF, e perché con i cookie serve una protezione?
**Risposta modello.** È una richiesta partita da un sito estraneo che il browser accompagna in automatico con il cookie della vittima, quindi eseguita a suo nome. Si difende con un token che il server dà al frontend e che va rimandato in un header: un sito estraneo non può leggerlo. `SameSite=Lax` aiuta come seconda difesa, ma "stesso sito" comprende anche i sottodomini, e il comportamento dipende dal browser. Va protetto anche l'accesso, contro il *login CSRF*.

### Domanda 3: Cos'è la *session fixation* e come la eviti?
**Risposta modello.** L'attaccante fa usare alla vittima un id di sessione che conosce. Se l'id resta lo stesso dopo l'accesso, l'attaccante si ritrova autenticato. Si evita cambiando l'id della sessione all'accesso (`changeSessionId` in Servlet), e invalidandola all'uscita. Spring Security lo fa da solo nei suoi filtri di accesso; se autentichi in un controller tuo, lo devi fare tu.

### Domanda 4: Che differenza c'è tra autenticazione e autorizzazione, e dove le metti in Spring?
**Risposta modello.** L'autenticazione stabilisce chi sei, l'autorizzazione cosa puoi fare. In Spring Security l'autenticazione riempie il `SecurityContext`, e l'`AuthorizationFilter` applica le regole per indirizzo e ruolo. Le regole per oggetto ("questa prenotazione è tua?") non le può sapere un filtro: stanno nei servizi. I due livelli si completano.

### Domanda 5: Come butti fuori un utente disattivato che ha una sessione aperta?
**Risposta modello.** Se la sessione contiene l'identità intera, resta valida fino alla scadenza. Allora o si cercano e si cancellano le sue sessioni (Spring Session sa trovarle per utente), o si ricontrolla l'account a ogni richiesta. Io tengo nella sessione solo l'id, e un `SecurityContextRepository` rilegge l'account a ogni richiesta: se è disattivato invalida la sessione, e il ruolo è sempre quello attuale. Costa una query per chiave primaria per richiesta.

### Domanda 6: Perché `denyAll()` come ultima regola invece di `authenticated()`?
**Risposta modello.** Con `authenticated()` un endpoint nuovo messo fuori dai prefissi previsti sarebbe aperto a qualunque utente con un account. Con `denyAll()` è chiuso a tutti finché qualcuno non lo assegna a un'area. È il principio del *default deny*: ciò che non è permesso esplicitamente è vietato.

---

## Documentazione ufficiale

- Spring Security, architettura dei filtri: https://docs.spring.io/spring-security/reference/servlet/architecture.html
- Spring Security, persistenza dell'autenticazione e salvataggio esplicito: https://docs.spring.io/spring-security/reference/servlet/authentication/persistence.html
- Spring Security, CSRF e applicazioni a pagina singola: https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
- Spring Security, *session fixation* e gestione delle sessioni: https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html
- Spring Security, autorizzazione delle richieste HTTP: https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html
- Spring Security, *One-Time Token Login*: https://docs.spring.io/spring-security/reference/servlet/authentication/onetimetoken.html
- Spring Session JDBC: https://docs.spring.io/spring-session/reference/
- OWASP, *Session Management Cheat Sheet* (durate, cookie, fixation): https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html
- OWASP, *CSRF Prevention Cheat Sheet*: https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html
