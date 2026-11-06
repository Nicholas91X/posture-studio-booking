# Le API del pannello — guida di studio

Questa guida accompagna il **pezzo 4 del passo 5**: le prime API REST del progetto. Il professionista gestisce dal pannello servizi, pacchetti e impostazioni. Il percorso completo di una richiesta:

```
HTTP → controller (forma della richiesta, validazione) → servizio (regole, transazione)
     → repository → database → record → JSON
       e se qualcosa va storto: eccezione → @RestControllerAdvice → ProblemDetail
```

Contiene:
- **la parte 1**: cosa si costruisce, e una proposta di riorganizzazione dei pezzi 4 e 5;
- **la parte 2**: **la scelta** sul "professionista corrente" provvisorio. La decisione è tua;
- **la parte 3**: la forma delle URL e dei metodi HTTP (seconda scelta, più piccola);
- **la parte 4**: i DTO e la Bean Validation (terza scelta, piccola);
- **la parte 5**: 404 e non 403, e perché;
- **la parte 6**: gli errori come `ProblemDetail` (RFC 9457) con un solo `@RestControllerAdvice`;
- **la parte 7**: la sintassi su un esempio **diverso** e la checklist;
- **la parte 8**: i test delle API dall'esterno, con `MockMvcTester`.

Teoria di riferimento: guida 06, parte 5 (lo strato web: `DispatcherServlet`, annotazioni del controller, Jackson, errori); guide 09 e 10 (servizi e regole); D-026 (`NotFoundException`), D-027 (`BusinessRuleException`, 409).

---

## Indice

1. [Cosa si costruisce](#parte-1--cosa-si-costruisce)
2. [La scelta: il professionista corrente provvisorio](#parte-2--la-scelta-il-professionista-corrente-provvisorio)
3. [URL e metodi HTTP](#parte-3--url-e-metodi-http)
4. [DTO e Bean Validation](#parte-4--dto-e-bean-validation)
5. [404 e non 403](#parte-5--404-e-non-403)
6. [Gli errori come ProblemDetail](#parte-6--gli-errori-come-problemdetail)
7. [La sintassi, su un esempio diverso](#parte-7--la-sintassi-su-un-esempio-diverso)
8. [I test delle API](#parte-8--i-test-delle-api)
9. [Domande da colloquio](#domande-da-colloquio)
10. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Cosa si costruisce

### 1.1 I pezzi

| Classe | Dove | Cosa fa |
|---|---|---|
| Il "professionista corrente" (interfaccia + implementazione provvisoria) | `auth` (parte 2) | Risponde a "chi sta usando il pannello?". Un solo punto, da sostituire al passo 8 |
| `PractitionerPanelController` | `practitioner` | `GET` e `PUT` delle impostazioni |
| `CatalogPanelController` | `catalog` | Servizi e pacchetti: elenco, crea, modifica, disattiva, riattiva |
| Annotazioni di validazione sui `record` di ingresso | `catalog`, `practitioner` | `@NotBlank`, `@Positive`, `@Min(2)` (parte 4) |
| `ApiExceptionHandler` | `shared` | L'unico `@RestControllerAdvice`: ogni eccezione diventa un `ProblemDetail` (parte 6) |

I controller stanno **nel modulo** di cui espongono il servizio, come dice l'architettura ("Livelli dentro ogni modulo"). Il controller di `catalog` chiama solo `CatalogService`: non vede entità né repository, e per la visibilità di package non potrebbe nemmeno.

### 1.2 Una proposta: spostare la gestione degli errori in questo pezzo

Il piano diceva: pezzo 4 = API del pannello, pezzo 5 = API pubbliche + `@RestControllerAdvice`. Ma senza il gestore degli errori una `NotFoundException` arriva al client come **500**, e la regola di proprietà (A non tocca le cose di B → 404) non si può provare dall'esterno. Propongo:
- **pezzo 4**: professionista corrente, API del pannello, validazione, **gestore degli errori**, test delle API del pannello;
- **pezzo 5**: API pubbliche (con il problema dei professionisti "attivi", guida 09, 1.3), test delle API pubbliche, chiusura del passo.

### 1.3 Cosa resta fuori

- **Il contratto OpenAPI** (springdoc, previsto dall'architettura). Il brief del passo 5 non lo chiede, e la compatibilità di springdoc con Spring Boot 4 va verificata prima di aggiungerlo. Lo annoto come punto da decidere prima del passo 12 (frontend), dove serve davvero: i tipi del frontend si generano da lì.
- **La sicurezza vera**: passo 8.

---

## Parte 2 — La scelta: il professionista corrente provvisorio

### 2.1 Il problema

Ogni metodo del pannello deve sapere **chi** agisce: `catalog.createService(practitionerId, ...)`. Al passo 8 lo dirà la sessione (il cookie dopo il codice via email, D-014). Fino ad allora serve un sostituto, con tre requisiti dal brief:
1. **un unico punto**, sostituibile, che risponde "chi è il professionista corrente";
2. nei test deve permettere di agire come **due professionisti diversi**;
3. marcato come **provvisorio**, e da rimuovere al passo 8 (criterio di "fatto" del passo 8).

### 2.2 Strada A — Interfaccia + implementazione provvisoria che legge un header

```java
public interface CurrentPractitioner {
    long id();   // the practitioner using the panel in this request
}
```

L'implementazione provvisoria legge un **header HTTP** della richiesta, per esempio `X-Practitioner-Id: 1`. Al passo 8 la si cancella e la si sostituisce con un'implementazione che legge l'utente autenticato da Spring Security. I controller non cambiano: dipendono dall'interfaccia.

**Pro.**
- I test scelgono chi agisce richiesta per richiesta: un header con l'id di A, poi uno con l'id di B. È esattamente il test di proprietà del brief.
- Si prova anche a mano, con `curl` o dal browser con un'estensione.
- Al passo 8 cambia una classe sola.

**Contro.**
- Chiunque può scrivere un header: è un'identità **dichiarata**, non verificata. In produzione sarebbe una falla grave. Ma il sistema non va online prima del passo 14, e l'implementazione sparisce al passo 8. Va comunque scritto in grande nel codice e in `stato.md`.

**Due dettagli.**
- **Header mancante o non numerico** → **401 Unauthorized**: "non so chi sei". È la stessa risposta che darà il passo 8 senza sessione.
- **Header con l'id di un professionista che non esiste** → anche questo 401: l'identità dichiarata non corrisponde a nessuno. Serve una domanda a `PractitionerService` ("esiste?"), un metodo in più nella sua porta pubblica. Senza questo controllo, un servizio creato per un professionista inesistente farebbe scattare la foreign key, con un errore meno chiaro.

### 2.3 Strada B — Un valore fisso nella configurazione

`app.dev.practitioner-id=1` in `application.properties`. Semplice, ma un contesto Spring ha **un solo** valore: per il test "A non tocca le cose di B" bisognerebbe cambiare configurazione a metà test. Non soddisfa il requisito 2.

### 2.4 Strada C — L'id nella URL

`/api/practitioners/{id}/services`. Ogni richiesta dice di chi è. Ma è la stessa identità dichiarata della strada A, in più sparsa in ogni URL. E al passo 8 tutte le URL del pannello andrebbero cambiate, o controllate una per una contro l'utente autenticato. Il pannello è per definizione "le **mie** cose": l'id non deve stare nella URL.

### 2.5 Strada D — Spring Security subito, con un utente finto

Si anticipa una parte del passo 8: Spring Security con un utente in memoria, e nei test `@WithMockUser`. È il modo "vero", ma porta dentro adesso filtri, configurazione di sicurezza, CSRF: argomenti che il passo 8 tratta per intero. Si rischia di farli due volte, e male la prima.

### 2.6 Il confronto

| | A — Header | B — Configurazione | C — URL | D — Security ora |
|---|---|---|---|---|
| Un solo punto da sostituire | ✅ | ✅ | ❌ ogni URL | ✅ |
| Due professionisti nei test | ✅ | ❌ | ✅ | ✅ |
| Lavoro da rifare al passo 8 | una classe | una classe | tutte le URL | in parte |
| Anticipa argomenti del passo 8 | no | no | no | sì |

### 2.7 Dove vive

Rispondere a "chi è l'utente corrente" è il mestiere del modulo `auth` (architettura: "codici di accesso, sessioni, ruoli"). Propongo:
- l'interfaccia `CurrentPractitioner` **pubblica**, in `auth`;
- l'implementazione provvisoria **package-private**, in `auth`, con un nome che grida (`DevHeaderCurrentPractitioner`) e un commento `TODO (step 8): remove`.

Al passo 8 lo stesso modulo fornirà l'implementazione vera.

### 2.8 Come la legge il controller

L'implementazione è un bean normale (singleton). La richiesta corrente però cambia a ogni chiamata: come fa un singleton a leggere l'header della richiesta *di adesso*? Spring può iniettare un `HttpServletRequest` anche in un singleton. In realtà inietta un **proxy** (guida 06, parte 3), che a ogni chiamata passa alla richiesta legata al thread corrente. Con Tomcat ogni richiesta ha il suo thread (guida 06, 5.1), quindi due richieste contemporanee non si confondono.

### 2.9 La decisione

> **Decisa il 2026-10-29: strada A** (D-028), con una correzione emersa scrivendo il codice. L'interfaccia sta nel modulo **`practitioner`**, non in `auth` come proposto in 2.7. In `auth` il controller di `practitioner` dipenderebbe da `auth`, e l'implementazione di `auth` da `practitioner` (per sapere se il professionista esiste): un ciclo tra moduli. In `practitioner` il controllo di esistenza è interno. Al passo 8 sarà `practitioner` a chiedere ad `auth` l'utente autenticato. Dipendenze in un verso solo: `catalog` → `practitioner` → `auth`.

È tua. Il mio consiglio è **A**: soddisfa i tre requisiti del brief senza anticipare il passo 8. Diventa la scheda D-028, con il criterio di rimozione.

---

## Parte 3 — URL e metodi HTTP

### 3.1 La proposta

Tutto sotto `/api/panel/...`: il prefisso dice "il pannello di chi sta agendo".

| Azione | Metodo e URL | Corpo | Risposta |
|---|---|---|---|
| Elenco servizi | `GET /api/panel/services` | — | 200, lista |
| Crea servizio | `POST /api/panel/services` | dettagli | **201 Created**, header `Location`, il servizio creato |
| Modifica servizio | `PUT /api/panel/services/{id}` | dettagli completi | 200, il servizio |
| Disattiva servizio | `POST /api/panel/services/{id}/deactivate` | — | 200, il servizio |
| Riattiva servizio | `POST /api/panel/services/{id}/activate` | — | 200, il servizio |
| Pacchetti | stesse cinque, sotto `/api/panel/packages`; la creazione ha in più `serviceId` nel corpo | | |
| Leggi impostazioni | `GET /api/panel/settings` | — | 200 |
| Modifica impostazioni | `PUT /api/panel/settings` | le tre impostazioni | 200 |

### 3.2 Perché questi metodi

- **`POST` per creare**: crea una risorsa nuova, il cui indirizzo lo decide il server (l'id). Risposta **201** con `Location: /api/panel/services/7`, così il client sa dove trovarla.
- **`PUT` per modificare**: sostituisce **tutti** i dettagli. Il frontend manda il modulo completo, e il servizio ha già un metodo che cambia tutto insieme (`changeDetails`). `PUT` è **idempotente**: ripetuto, dà lo stesso risultato.
- **`PATCH`** (modifica parziale) non serve: non ci sono campi che si cambiano da soli.
- **Disattivare e riattivare.** Tre stili possibili:
  1. `POST .../deactivate` e `POST .../activate`: un'**azione** esplicita;
  2. `PATCH /services/{id}` con `{"active": false}`;
  3. `PUT /services/{id}/active` con `true` o `false`.

  Il secondo e il terzo trattano l'attivazione come un campo qualunque. Ma riattivare un pacchetto ha una **regola** (`SERVICE_NOT_ACTIVE`): è un'azione del dominio, non un campo. Consiglio il primo: è il più leggibile, e il server lo rende idempotente comunque (guida 10, 1.4). È anche il punto su cui un intervistatore ti chiederà "e perché non `PATCH`?": la risposta è questa.
- **Niente `DELETE`**: non si cancella niente (requisiti).

### 3.3 I codici di risposta

| Codice | Quando |
|---|---|
| 200 OK | lettura, modifica, disattivazione, riattivazione |
| 201 Created | creazione |
| 400 Bad Request | validazione fallita, JSON illeggibile |
| 401 Unauthorized | nessun professionista corrente (parte 2) |
| 404 Not Found | non esiste, o non è tuo (parte 5) |
| 409 Conflict | regola violata (D-027), vincolo del database |
| 500 Internal Server Error | errore inatteso, senza dettagli interni |

---

## Parte 4 — DTO e Bean Validation

### 4.1 DTO: cosa entra e cosa esce

**DTO** (*Data Transfer Object*): un oggetto che esiste solo per portare dati attraverso un confine, qui quello HTTP. Le API ricevono e restituiscono `record`, mai entità (architettura). Ripasso dal passo 4 e dalle guide 09 e 10:
- un'entità è **mutabile**, ha un'identità e uno stato seguito da Hibernate; serializzarla in JSON potrebbe toccare relazioni *lazy* (`LazyInitializationException`) o esporre campi interni;
- un `record` è **immutabile**, contiene solo i dati scelti, e Jackson lo converte in JSON e viceversa senza configurazione: usa il costruttore canonico e i nomi dei componenti.

I record d'uscita ci sono già (`ServiceOfferView`, `PackageOfferView`, `PractitionerSettings`).

### 4.2 Terza scelta (piccola): dove mettere le annotazioni di validazione

Per l'ingresso ci sono già `ServiceOfferDetails`, `PackageOfferDetails` e `PractitionerSettings`. Due strade:
- **annotare questi record** (`@NotBlank String name`, `@Positive int durationMinutes`). Le annotazioni di Bean Validation (`jakarta.validation`) non sono legate a HTTP: descrivono un dato valido, ovunque arrivi. Nessuna classe in più, nessuna conversione;
- **record di richiesta separati** nel controller (`CreateServiceRequest`), convertiti in `ServiceOfferDetails`. Più classi e conversioni, ma l'API e il servizio possono evolvere separati.

Consiglio la prima, finché i campi coincidono. Se un giorno l'API riceverà campi che il servizio non conosce (o viceversa), si separano. Solo per la creazione del pacchetto serve un record in più, perché la richiesta contiene `serviceId` oltre ai dettagli.

### 4.3 Come funziona

```java
public record ServiceOfferDetails(
        @NotBlank @Size(max = 100) String name,
        @Positive int durationMinutes,
        @Positive long priceCents) {
}

@PostMapping
ResponseEntity<ServiceOfferView> create(@Valid @RequestBody ServiceOfferDetails details) { ... }
```

1. Jackson converte il JSON nel `record`.
2. `@Valid` chiede a Hibernate Validator (l'implementazione di Bean Validation, portata da `spring-boot-starter-validation`, già nel `pom.xml`) di controllare le annotazioni.
3. Se qualcosa non va, il metodo **non viene chiamato**: Spring lancia `MethodArgumentNotValidException`, che il gestore degli errori trasforma in un 400 con l'elenco dei campi sbagliati (parte 6).

Su un `record` le annotazioni si scrivono sui componenti, e Java le porta sui campi e sul costruttore.

Le annotazioni che servono qui:

| Annotazione | Controlla | Per |
|---|---|---|
| `@NotBlank` | non `null`, non vuota, non solo spazi | nomi |
| `@Size(max = ...)` | lunghezza massima | nomi (un limite evita testi enormi) |
| `@Positive` | > 0 | durata, prezzo, validità, impostazioni |
| `@Min(2)` | ≥ 2 | sedute del pacchetto |
| `@NotNull` | non `null` | `serviceId` come `Long` |

**Il limite di lunghezza** non c'è nel database (`text`) né nelle entità. È una regola di **forma dell'input**, quindi va qui (guida 09, parte 3). Proposta: 100 caratteri per i nomi.

### 4.4 Una trappola da verificare: i campi numerici mancanti

Se il JSON non contiene `durationMinutes`, cosa riceve un componente `int`? In Jackson 2 un primitivo mancante diventava `0`, e `@Positive` lo rifiutava con "deve essere maggiore di 0": un messaggio fuorviante, perché il campo non era sbagliato, **mancava**. Jackson 3 (nel progetto c'è la 3.1.5) ha cambiato alcuni default, e non do per certo cosa succede oggi. Lo verifichiamo con un test (8.3, caso 6). Se il risultato non è chiaro per l'utente, si passa a `Integer` con `@NotNull @Positive`.

> **Verificato il 2026-10-29.** Con Jackson 3.1.5 un `int` mancante in un `record` **non** diventa `0`: la lettura del JSON fallisce per intero e la risposta è un 400 generico (`"detail": "Failed to read request"`) **senza il nome del campo**. Meglio di Jackson 2, che lasciava passare lo `0` in silenzio, ma inutile per il frontend. Per questo i `record` di **ingresso** usano `Integer` e `Long` con `@NotNull`: il campo mancante arriva come `null` e la validazione lo segnala con il suo nome e il vincolo `NotNull`. I `record` di **uscita** restano con i primitivi.
>
> Conseguenza in Java: un letterale `int` si converte da solo (*autoboxing*) solo in `Integer`, **mai** in `Long`. `new ServiceOfferDetails("Seduta", 60, 3000)` non compila più: serve `3000L`.

### 4.5 Dove sta ogni controllo, aggiornato

| Controllo | Dove | Errore |
|---|---|---|
| Forma: obbligatorio, positivo, lunghezza | Bean Validation sul `record` | 400 con i campi |
| Stato dell'oggetto | entità (`Checks`) | `IllegalArgumentException` = errore di programmazione (la validazione doveva fermarlo) → 500 |
| Proprietà | query del repository | 404 |
| Regole di stato (servizio attivo) | servizio | 409 con codice |
| Invarianti | database | 409 generico |

L'`IllegalArgumentException` dell'entità **non** diventa un 400. Se arriva, vuol dire che manca un'annotazione di validazione: è un bug, e un 500 nel log lo fa notare. I test della parte 8 provano che ogni campo è validato prima.

---

## Parte 5 — 404 e non 403

Il professionista A chiede `PUT /api/panel/services/10`, e il servizio 10 è di B.

- **403 Forbidden** vuol dire "esiste, ma non puoi". Così A **scopre** che il servizio 10 esiste.
- **404 Not Found** vuol dire "non c'è niente qui", sia che il servizio non esista, sia che sia di un altro.

Perché conta, se A scopre solo un numero:
- **Enumerazione.** Gli id sono numeri progressivi (`identity`). Provando 1, 2, 3, ..., con il 403 A saprebbe quanti servizi esistono e quali sono di altri. Con il 404 ogni id altrui è indistinguibile da un id mai usato.
- **Principio generale.** Una risposta di errore non deve dire più di quanto serve a chi ha diritto di saperlo. Per lo stesso motivo il messaggio del login non dirà mai "questa email non esiste" (passo 8).
- **Coerenza con il codice.** Non c'è niente da fare in più: `findByIdAndPractitionerId` non trova le cose degli altri, e la `NotFoundException` è la stessa (guida 10, 4.1). Il 404 è il comportamento **naturale** del modo in cui abbiamo scritto le query.

Il 403 resta giusto quando l'esistenza non è un segreto e il problema è il **ruolo**. Esempio dal passo 8: un cliente che apre `/api/panel/...`. Il pannello esiste per tutti, il cliente non ha il ruolo per usarlo.

Un dettaglio: anche il **messaggio** del 404 deve essere identico nei due casi ("Service 10 not found"). Se dicesse "not yours", il 404 non nasconderebbe più niente.

---

## Parte 6 — Gli errori come ProblemDetail

### 6.1 Il formato

**RFC 9457** (*Problem Details for HTTP APIs*, che sostituisce la 7807) definisce un JSON standard per gli errori, con content type `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Service 7 is not active",
  "instance": "/api/panel/packages",
  "code": "SERVICE_NOT_ACTIVE"
}
```

I cinque campi standard: `type` (un URI che identifica il tipo di problema; `about:blank` = nessun tipo specifico), `title`, `status`, `detail` (il caso particolare), `instance` (dove è successo). Si possono aggiungere **proprietà** proprie: qui `code` (D-027) e, per la validazione, l'elenco dei campi. Spring lo rappresenta con la classe `ProblemDetail`.

### 6.2 Un solo `@RestControllerAdvice`

Un `@RestControllerAdvice` è un bean che intercetta le eccezioni uscite da **tutti** i controller (guida 06, 5.6). Ogni metodo `@ExceptionHandler(Tipo.class)` gestisce un tipo e restituisce la risposta.

Conviene che estenda **`ResponseEntityExceptionHandler`** di Spring. Quella classe sa già trasformare in `ProblemDetail` tutte le eccezioni di Spring MVC:
- JSON illeggibile → 400;
- metodo HTTP non supportato → 405;
- content type sbagliato → 415;
- validazione fallita (`MethodArgumentNotValidException`) → 400.

Noi ridefiniamo la gestione della validazione, per aggiungere i campi, e aggiungiamo le nostre eccezioni.

| Eccezione | Stato | Cosa c'è nel `ProblemDetail` |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | proprietà `errors`: per ogni campo, nome e messaggio |
| `NotFoundException` | 404 | il messaggio |
| `BusinessRuleException` | 409 | il messaggio e `code` |
| Professionista corrente mancante (parte 2) | 401 | un messaggio generico |
| `DataIntegrityViolationException` | 409 | un messaggio **generico** e un codice (es. `DATA_CONFLICT`): mai il messaggio del database, che contiene SQL, nomi di tabelle e vincoli |
| Qualunque altra `Exception` | 500 | un messaggio generico; il dettaglio va **nel log**, non nella risposta |

Con `spring.mvc.problemdetails.enabled=true` (verificato: la proprietà esiste in Boot 4.1) anche gli errori che nascono **fuori** dai controller, come una URL inesistente, usano lo stesso formato.

### 6.3 Mai lo stack trace

Uno stack trace in una risposta rivela le librerie e le loro versioni, la struttura del codice, a volte query e dati. È un regalo a chi cerca una vulnerabilità. Nel `ProblemDetail` va solo ciò che serve al client per reagire. Il resto, compreso lo stack trace, va nel **log** del server, con il livello giusto:
- `WARN` per un vincolo violato (può capitare con due richieste simultanee);
- `ERROR` per i 500 (non dovrebbero capitare mai).

### 6.4 I messaggi della validazione

Hibernate Validator ha messaggi già tradotti, e sceglie la lingua dall'header `Accept-Language` della richiesta. Ma l'architettura dice che **il frontend** traduce i messaggi a partire dai codici. Per la validazione basta che nel `ProblemDetail` ci siano il **nome del campo** e il **tipo di vincolo** violato (es. `Positive`, `NotBlank`): il frontend sa già mostrare "il prezzo deve essere maggiore di zero" accanto al campo giusto. Il messaggio testuale si può includere per chi legge a mano, ma il frontend non deve dipenderne.

---

## Parte 7 — La sintassi, su un esempio diverso

Il pannello di una sede della biblioteca, che gestisce le proprie sale studio (l'esempio della guida 10).

```java
// auth/CurrentLibrarian.java — public: what controllers depend on
public interface CurrentLibrarian {
    long branchId();
}
```

```java
// auth/DevHeaderCurrentLibrarian.java
// TODO (step 8): remove. Identity is DECLARED by the client, not verified: never in production
@Component
class DevHeaderCurrentLibrarian implements CurrentLibrarian {

    static final String HEADER = "X-Branch-Id";

    private final HttpServletRequest request;   // a proxy: each call reaches the current request
    private final BranchService branches;

    DevHeaderCurrentLibrarian(HttpServletRequest request, BranchService branches) {
        this.request = request;
        this.branches = branches;
    }

    @Override
    public long branchId() {
        String value = request.getHeader(HEADER);
        try {
            long id = Long.parseLong(value);
            if (branches.exists(id)) {
                return id;
            }
        } catch (NumberFormatException e) {
            // missing or not a number: same answer as an unknown id
        }
        throw new NotAuthenticatedException("No current librarian");
    }
}
```

```java
// rooms/RoomPanelController.java
@RestController
@RequestMapping("/api/panel/rooms")
class RoomPanelController {

    private final RoomService rooms;
    private final CurrentLibrarian current;

    RoomPanelController(RoomService rooms, CurrentLibrarian current) {
        this.rooms = rooms;
        this.current = current;
    }

    @GetMapping
    List<StudyRoomView> list() {
        return rooms.listRooms(current.branchId());
    }

    @PostMapping
    ResponseEntity<StudyRoomView> create(@Valid @RequestBody StudyRoomDetails details) {
        StudyRoomView created = rooms.createRoom(current.branchId(), details);
        URI location = URI.create("/api/panel/rooms/" + created.id());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    StudyRoomView update(@PathVariable long id, @Valid @RequestBody StudyRoomDetails details) {
        return rooms.updateRoom(current.branchId(), id, details);
    }

    @PostMapping("/{id}/deactivate")
    StudyRoomView deactivate(@PathVariable long id) {
        return rooms.deactivateRoom(current.branchId(), id);
    }
}
```

```java
// shared/ApiExceptionHandler.java (extract)
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("code", e.getCode().name());
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataConflict(DataIntegrityViolationException e) {
        log.warn("Constraint violated", e);   // the details stay in the log
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The request conflicts with existing data");
        problem.setProperty("code", "DATA_CONFLICT");
        return problem;
    }

    // validation: Spring calls this method of ResponseEntityExceptionHandler, we override it
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = e.getBody();   // already a 400 ProblemDetail
        problem.setProperty("errors", e.getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "constraint", String.valueOf(f.getCode())))
                .toList());
        return ResponseEntity.badRequest().body(problem);
    }
}
```

Da notare:
- **Controller e metodi package-private.** Spring li trova lo stesso (component scan e reflection), e nessun altro modulo li usa.
- **`@RestController`** = `@Controller` + `@ResponseBody`: il valore restituito diventa il corpo JSON.
- **Il controller non ha logica**: legge chi agisce, passa la richiesta al servizio, sceglie il codice HTTP. Nessun `if` di dominio.
- **`@PathVariable long id`**: un id non numerico nella URL (`/rooms/abc`) diventa un 400 da solo, grazie a `ResponseEntityExceptionHandler`.
- **`current.branchId()` dentro ogni metodo**, non nel costruttore: il controller è un singleton creato all'avvio, quando non c'è nessuna richiesta.
- **`f.getCode()`** è il nome del vincolo violato (`Positive`, `NotBlank`): è quello che il frontend traduce.
- **`ResponseEntity`** solo dove serve scegliere stato o header (201 + `Location`). Altrove si restituisce il `record`, e lo stato è 200.

### Cosa deve contenere il nostro pezzo

- [ ] `auth/CurrentPractitioner` (pubblica) e l'implementazione provvisoria con l'header, `TODO (step 8): remove`
- [ ] Una nuova eccezione per il 401 (in `shared`), e `PractitionerService.exists(id)`
- [ ] Annotazioni di validazione su `ServiceOfferDetails`, `PackageOfferDetails`, `PractitionerSettings`; il record di creazione del pacchetto con `serviceId`
- [ ] `CatalogPanelController` con le dieci operazioni della tabella 3.1
- [ ] `PractitionerPanelController` con `GET` e `PUT` delle impostazioni
- [ ] `shared/ApiExceptionHandler`: 400 con i campi, 401, 404, 409 con codice, 409 generico per i vincoli, 500 generico; dettagli solo nel log
- [ ] `spring.mvc.problemdetails.enabled=true`
- [ ] In `stato.md`: il professionista corrente provvisorio va rimosso al passo 8 (criterio di "fatto")

---

## Parte 8 — I test delle API

### 8.1 Quali strumenti, su Boot 4.1 (verificati nei jar)

`spring-boot-starter-webmvc-test`, già nel `pom.xml`, porta:

| Strumento | Package | Cosa fa |
|---|---|---|
| `@WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure` | Avvia **solo** lo strato web (controller, advice, Jackson, validazione). I servizi vanno sostituiti con dei finti (`@MockitoBean`). Veloce, senza database |
| `@AutoConfigureMockMvc` | stesso package | Con `@SpringBootTest`: l'applicazione **intera**, e un client che chiama i controller senza una vera rete |
| `MockMvcTester` | `org.springframework.test.web.servlet.assertj` | Il client, con asserzioni nello stile AssertJ (`assertThat(result).hasStatus(...)`) |
| `RestTestClient`, `TestRestTemplate` | `spring-boot-resttestclient` | Chiamate HTTP vere verso un server avviato su una porta |

### 8.2 La proposta

**`@SpringBootTest` + `@AutoConfigureMockMvc` + `MockMvcTester`**, con il container condiviso. La richiesta attraversa tutto: validazione, controller, servizio, transazione, PostgreSQL vero, gestore degli errori, JSON. È quello che il brief chiede: "test delle API dall'esterno".

Perché non le altre:
- **`@WebMvcTest`** proverebbe validazione e mappatura degli errori, ma con un servizio finto il test di proprietà non proverebbe niente: sarebbe il finto a decidere se lanciare `NotFoundException`.
- **`RestTestClient` con un server vero**: la richiesta gira su un **altro thread**, quindi la transazione del test non la contiene, e non c'è rollback. I dati resterebbero tra un test e l'altro. Con `MockMvc` la richiesta gira **nello stesso thread** del test: con `@Transactional` sulla classe di test, tutto viene annullato alla fine come con `@DataJpaTest`.

Un'avvertenza sullo "stesso thread": se un test con `@Transactional` chiama due API di seguito, la seconda vede le modifiche della prima (stessa transazione) anche senza commit. Va bene per i nostri casi, ma non prova il comportamento con due transazioni separate.

### 8.3 I casi

Due professionisti inseriti con SQL, e un header diverso per ciascuno.

| # | Caso | Atteso |
|---|---|---|
| 1 | Nessun header | 401, `application/problem+json` |
| 2 | Header con un id inesistente o non numerico | 401 |
| 3 | A crea un servizio | 201, header `Location`, JSON con id e `active: true` |
| 4 | A crea un servizio con nome vuoto e prezzo 0 | 400; `errors` contiene `name` e `priceCents` |
| 5 | A crea un pacchetto da 1 seduta | 400, campo `sessionsCount` |
| 6 | A crea un servizio **senza** il campo `durationMinutes` | 400 con un errore su `durationMinutes` (verifica la trappola di 4.4) |
| 7 | JSON illeggibile | 400 |
| 8 | **A modifica il servizio di B** | **404**, e la riga di B è invariata |
| 9 | **A legge l'elenco** | solo i servizi di A |
| 10 | **A disattiva il pacchetto di B** | **404** |
| 11 | A crea un pacchetto su un suo servizio disattivato | 409, `code: SERVICE_NOT_ACTIVE` |
| 12 | A legge e modifica le impostazioni | 200; con un valore 0, 400 con il campo |
| 13 | Le impostazioni di B dopo che A le ha cambiate | invariate |
| 14 | Messaggio del 404 per un servizio di B e per un id mai usato | **identici** (parte 5) |
| 15 | Nessuna risposta di errore contiene `exception`, `trace` o testo SQL | vero per i casi sopra |

---

## Domande da colloquio

### Domanda 1: Una risorsa esiste ma appartiene a un altro utente. 403 o 404?
**Risposta modello.** 404, con lo stesso messaggio di una risorsa inesistente. Un 403 conferma che la risorsa esiste: con id progressivi, chi prova un numero dopo l'altro scopre quante risorse ci sono e quali sono di altri (enumerazione). Il 404 rende indistinguibili "non esiste" e "non è tuo". Nel codice viene naturale se la query include il proprietario (`findByIdAndOwnerId`). Il 403 resta giusto quando l'esistenza non è un segreto e manca il ruolo, per esempio un cliente sulle API del pannello.

### Domanda 2: Come restituisci gli errori di un'API REST in Spring Boot?
**Risposta modello.** Con `ProblemDetail` (RFC 9457), il formato standard `application/problem+json` con `type`, `title`, `status`, `detail`, `instance`, più proprietà proprie come un codice leggibile o l'elenco dei campi non validi. Uso un solo `@RestControllerAdvice` che estende `ResponseEntityExceptionHandler`: quella classe gestisce già le eccezioni di Spring MVC, e io aggiungo le mie (404, 409 con codice) e personalizzo la validazione. Nessuno stack trace, SQL o messaggio del database nella risposta: i dettagli vanno nel log.

### Domanda 3: Dove metti la validazione dell'input, e cosa succede quando fallisce?
**Risposta modello.** Annotazioni di Bean Validation sul DTO della richiesta (`@NotBlank`, `@Positive`, `@Size`) e `@Valid` sul parametro `@RequestBody`. Se fallisce, il metodo del controller non viene chiamato: Spring lancia `MethodArgumentNotValidException`, che il gestore trasforma in un 400 con i campi sbagliati. È la validazione della **forma**. Le regole che dipendono dallo stato restano nel servizio, e gli invarianti anche nell'entità e nel database.

### Domanda 4: `PUT` o `PATCH`? E come esponi un'azione come "disattiva"?
**Risposta modello.** `PUT` sostituisce l'intera rappresentazione ed è idempotente; `PATCH` applica una modifica parziale. Se il client manda sempre il modulo completo, `PUT` è più semplice. Per "disattiva" uso `POST /risorse/{id}/deactivate`, perché è un'azione del dominio con le sue regole (riattivare un pacchetto richiede il servizio attivo), non un campo qualunque. Con `PATCH {"active": false}` la regola resterebbe nascosta dietro un campo. Lato server l'azione è comunque idempotente.

### Domanda 5: Perché i test delle API con `MockMvc` e `@SpringBootTest` invece di `@WebMvcTest`, o di un server vero?
**Risposta modello.** `@WebMvcTest` avvia solo lo strato web con servizi finti: ottimo per validazione e formato degli errori, ma non può provare regole che stanno nel servizio e nelle query, come la proprietà. Un server vero su una porta fa girare la richiesta su un altro thread: la transazione del test non la contiene e non c'è rollback. `@SpringBootTest` con `MockMvc` attraversa tutta l'applicazione con un database vero (Testcontainers), nello stesso thread del test, quindi `@Transactional` annulla tutto alla fine.

### Domanda 6: Come fa un bean singleton a leggere un header della richiesta corrente?
**Risposta modello.** Spring inietta un proxy di `HttpServletRequest`. A ogni chiamata il proxy delega alla richiesta legata al thread corrente (`RequestContextHolder`). Con il modello "un thread per richiesta" di Tomcat, due richieste contemporanee non si confondono. Per lo stesso motivo il valore va letto nel metodo, non nel costruttore: all'avvio non c'è nessuna richiesta.

---

## Documentazione ufficiale

- RFC 9457, Problem Details for HTTP APIs: https://www.rfc-editor.org/rfc/rfc9457
- Spring Framework, risposte di errore e `ProblemDetail`: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html
- Spring Framework, `@ExceptionHandler` e `@ControllerAdvice`: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/mvc-ann-advice.html
- Spring Framework, validazione dei metodi dei controller: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/mvc-ann-validation.html
- Jakarta Bean Validation 3.1, i vincoli predefiniti: https://jakarta.ee/specifications/bean-validation/3.1/jakarta-validation-spec-3.1
- Spring Framework, `MockMvcTester` (AssertJ): https://docs.spring.io/spring-framework/reference/testing/mockmvc/assertj.html
- Spring Boot, test con `@SpringBootTest` e MockMvc: https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html
- RFC 9110, metodi HTTP e idempotenza: https://www.rfc-editor.org/rfc/rfc9110#name-method-definitions
