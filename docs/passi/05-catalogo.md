# Passo 05 — Catalogo e professionisti

> **Stato: in corso** (2026-10-29). Pezzi 1–4 di 5 fatti.

## Obiettivo
Il professionista gestisce cosa vende e come lavora: servizi, pacchetti in vendita e impostazioni. Il cliente legge il catalogo attivo. Sono le prime API REST del progetto: richiesta → validazione → regole → database → risposta.

I pezzi:
1. Migrazione V2 e test dei vincoli — ✅ fatto
2. Modulo `practitioner`: entità, repository chiuso nel modulo, `PractitionerService`, le tre impostazioni — ✅ fatto
3. Modulo `catalog`: entità `ServiceOffer` e `PackageOffer`, `CatalogService` con le regole — ✅ fatto
4. "Professionista corrente" provvisorio e API del pannello, con validazione, regola di proprietà (404) e `@RestControllerAdvice` con `ProblemDetail` (spostato qui dal pezzo 5) — ✅ fatto
5. API pubbliche (professionisti attivi, catalogo), test delle API pubbliche, chiusura del passo

## Cosa è stato costruito

| File | Chi | Cosa fa |
|---|---|---|
| `db/migration/V2__create_service_and_package_offer.sql` | Claude, su richiesta di Nicholas | Tabelle `service` e `package_offer` con vincoli con nome: nome non vuoto, durata, prezzo e validità > 0, sedute ≥ 2, foreign key composta (D-025), indici su `practitioner_id` |
| `test/.../catalog/CatalogSchemaTest.java` | Claude, su richiesta di Nicholas | 10 test di integrazione con SQL diretto (`JdbcTemplate`): due casi positivi, un valore limite (2 sedute), un caso negativo per ogni vincolo, verificato con il nome del vincolo |
| `test/.../auth/AppUserRepositoryTest.java` | Claude, su richiesta di Nicholas | Aggiunti i test rimasti dal passo 4: email in maiuscolo e ruolo inesistente inseriti con SQL, rifiutati da `app_user_email_ck` e `app_user_role_ck` |
| `shared/NotFoundException.java` | Claude, su richiesta di Nicholas | L'unica eccezione per "non esiste / non è tuo" (D-026), `RuntimeException`; diventerà un 404 |
| `practitioner/Practitioner.java` | Claude, su richiesta di Nicholas | Entità package-private su `practitioner`; `userId` come semplice id (D-026); `changeSettings` rifiuta valori ≤ 0; nessun costruttore pubblico (i professionisti li crea l'admin, passo 8) |
| `practitioner/PractitionerSettings.java` | Claude, su richiesta di Nicholas | `record` pubblico con le tre impostazioni: esce dal modulo al posto dell'entità |
| `practitioner/PractitionerRepository.java` | Claude, su richiesta di Nicholas | `JpaRepository` package-private |
| `practitioner/PractitionerService.java` | Claude, su richiesta di Nicholas | La porta del modulo: `getSettings` (`readOnly`) e `updateSettings` (senza `save`, *dirty checking*) |
| `test/.../TestcontainersConfiguration.java` | Claude, su richiesta di Nicholas | Il container PostgreSQL come bean di test, importato da tutte le classi di integrazione: un container per contesto Spring (2 in tutto invece di 3) |
| `test/.../practitioner/PractitionerTest.java` | Claude, su richiesta di Nicholas | 6 test unitari dell'entità, senza Spring (compreso: una modifica non valida non cambia niente) |
| `shared/BusinessRuleException.java`, `shared/ErrorCode.java` | Claude, su richiesta di Nicholas | Regola violata nello stato attuale, con codice leggibile (`SERVICE_NOT_ACTIVE`); diventerà un 409 (D-027) |
| `shared/Checks.java` | Claude, su richiesta di Nicholas | I controlli usati dalle entità (`requirePositive`, `requireAtLeast`, `requireNotBlank`), prima ripetuti in ogni entità |
| `catalog/ServiceOffer.java` | Claude, su richiesta di Nicholas | Entità su `service` (nome scelto per non scontrarsi con `@Service` di Spring); `practitionerId` come id; metodi pubblici perché raggiunta tramite proxy |
| `catalog/PackageOffer.java` | Claude, su richiesta di Nicholas | Entità su `package_offer`; `@ManyToOne(LAZY)` verso `ServiceOffer`, non modificabile dopo la creazione |
| `catalog/ServiceOfferRepository.java`, `catalog/PackageOfferRepository.java` | Claude, su richiesta di Nicholas | Package-private. Ricerche con il proprietario dentro la query (`findByIdAndPractitionerId`); elenchi del pannello e catalogo pubblico con `join fetch` in *text block* |
| `catalog/CatalogService.java` | Claude, su richiesta di Nicholas | La porta del modulo: per servizi e pacchetti elenco, crea, modifica, disattiva, riattiva; catalogo pubblico |
| `catalog/*Details.java`, `catalog/*View.java`, `catalog/PublicCatalog.java` | Claude, su richiesta di Nicholas | `record` di ingresso e di uscita; le viste nascono dalle entità con metodi `from` package-private |
| `test/.../catalog/ServiceOfferTest.java`, `PackageOfferTest.java` | Claude, su richiesta di Nicholas | 13 test unitari delle entità, compreso il valore limite di 2 sedute |
| `test/.../catalog/CatalogServiceTest.java` | Claude, su richiesta di Nicholas | 15 test di integrazione: proprietà (A non tocca le cose di B), `SERVICE_NOT_ACTIVE`, proxy caricato davvero, elenchi, catalogo pubblico che nasconde i disattivati e mostra di nuovo un pacchetto quando il suo servizio torna attivo |
| `test/.../practitioner/PractitionerServiceTest.java` | Claude, su richiesta di Nicholas | 5 test di integrazione: default del database, modifica scritta senza `save` (verificata leggendo la riga dopo il `flush`), nessun effetto sull'altro professionista, `NotFoundException` |
| `practitioner/CurrentPractitioner.java` | Claude, su richiesta di Nicholas | Interfaccia pubblica: "chi sta usando il pannello". I controller dipendono solo da lei (D-028) |
| `practitioner/DevHeaderCurrentPractitioner.java` | Claude, su richiesta di Nicholas | **Provvisoria, da rimuovere al passo 8**: legge l'header `X-Practitioner-Id` e controlla che il professionista esista; altrimenti 401 |
| `practitioner/PractitionerPanelController.java` | Claude, su richiesta di Nicholas | `GET` e `PUT /api/panel/settings` |
| `catalog/CatalogPanelController.java` | Claude, su richiesta di Nicholas | `/api/panel/services` e `/api/panel/packages`: elenco, `POST` (201 + `Location`), `PUT`, `POST .../deactivate` e `.../activate` |
| `catalog/NewPackageOffer.java` | Claude, su richiesta di Nicholas | Richiesta di creazione di un pacchetto, piatta: `serviceId` più i dettagli |
| `*Details.java`, `PractitionerSettings.java` (modificati) | Claude, su richiesta di Nicholas | Annotazioni di Bean Validation; numeri come `Integer`/`Long` con `@NotNull` (Jackson 3) |
| `shared/ApiExceptionHandler.java` | Claude, su richiesta di Nicholas | L'unico `@RestControllerAdvice`, estende `ResponseEntityExceptionHandler`: 400 con `errors` (campo + vincolo), 401, 404, 409 con `code`, 409 `DATA_CONFLICT` per i vincoli del database, 500 generico; dettagli solo nel log |
| `shared/NotAuthenticatedException.java` | Claude, su richiesta di Nicholas | Nessuno sta agendo → 401 |
| `application.properties` (modificato) | Claude, su richiesta di Nicholas | `spring.mvc.problemdetails.enabled=true` |
| `test/.../catalog/CatalogPanelApiTest.java` | Claude, su richiesta di Nicholas | 15 test delle API dall'esterno (`@SpringBootTest` + `MockMvcTester`, PostgreSQL vero): 401, 201 + `Location`, 400 con i campi, campo mancante, JSON illeggibile, **A non tocca le cose di B** (404 e riga invariata), 404 identico per "di un altro" e "inesistente", 409 `SERVICE_NOT_ACTIVE`, nessun dettaglio interno nelle risposte |
| `test/.../catalog/CatalogPanelErrorsTest.java` | Claude, su richiesta di Nicholas | 2 test `@WebMvcTest` con servizio finto (`@MockitoBean`): vincolo del database → 409 senza il messaggio del database; errore inatteso → 500 senza dettagli |
| `test/.../practitioner/PractitionerPanelApiTest.java` | Claude, su richiesta di Nicholas | 4 test delle API delle impostazioni: lettura e modifica, 400 con il campo, l'altro professionista invariato, 401 |

## Concetti chiave

> Spiegazione completa: [`docs/studio/08-migrazione-v2-catalogo.md`](../studio/08-migrazione-v2-catalogo.md).

### Regole tra due tabelle
Un `check` vede solo la propria riga. Per garantire che il servizio di un pacchetto sia dello stesso professionista serve una **foreign key composta**: la coppia (`service_id`, `practitioner_id`) deve esistere in `service`. PostgreSQL la accetta solo verso colonne coperte da un `unique` su esattamente quelle colonne, quindi in `service` serve un `unique (id, practitioner_id)`, logicamente ridondante. Con colonne `NULL` la composta non viene controllata (`MATCH SIMPLE`): per questo entrambe sono `not null`.

### Cosa diventa un vincolo e cosa no
Nel database va ciò che è vero per ogni riga, sempre. Non diventano vincoli le regole che valgono al momento di un'azione ("si crea un pacchetto solo su un servizio attivo": domani il servizio può essere disattivato) o che dipendono da dati che cambiano in un'altra tabella (lo scatto del professionista). Nemmeno i filtri in lettura (il catalogo pubblico) sono vincoli.

### `not null` e `check` insieme
`check (btrim(name) <> '')` rifiuta `''` e `'   '`, ma con un `NULL` il risultato è "sconosciuto" e il `check` passa (PD-14). Servono entrambi.

### Testare il database con SQL diretto
Gli `insert` con `JdbcTemplate` saltano ogni validazione Java: provano che il database si difende da solo. `JdbcTemplate` traduce le violazioni in `DataIntegrityViolationException`. Per non far passare un test per il motivo sbagliato: il nome del vincolo nel messaggio, un solo valore sbagliato per test, almeno un caso positivo, un valore limite. Dopo l'errore atteso, nessun'altra istruzione: in PostgreSQL la transazione è ormai annullata.

Dettaglio: il driver PostgreSQL restituisce `timestamptz` come `OffsetDateTime`, non come `Instant`, quando lo si legge con `JdbcTemplate`.

### Confini dei moduli
> Spiegazione completa: [`docs/studio/09-modulo-practitioner.md`](../studio/09-modulo-practitioner.md).

Entità e repository senza `public`: Spring Data crea comunque il repository, ma un altro modulo non può usarlo, e lo impedisce il compilatore. La porta del modulo è il servizio, che restituisce `record`. Tra moduli ci si riferisce per id (D-026): nessun caricamento *lazy* che attraversa un confine, e nessun modulo che modifica le entità di un altro.

### Dirty checking e `@Transactional`
Un'entità caricata dentro una transazione è *managed*: al flush Hibernate confronta i campi con la copia letta e scrive da solo l'`update`. Niente `save`. Nei test `@DataJpaTest` il servizio partecipa alla transazione del test (propagazione `REQUIRED`), quindi non c'è commit alla fine del metodo: serve `entityManager.flush()`. Verificato: senza flush, la lettura con `JdbcTemplate` trova ancora i valori vecchi, perché una query JDBC diretta non fa scattare il flush automatico di Hibernate.

### Un container per contesto
Con il container come bean (`TestcontainersConfiguration`), Spring lo avvia con il contesto. Le classi di test con la stessa configurazione condividono il contesto in cache, e quindi il container. Una classe che importa in più un servizio ha un contesto suo. Verificato: 2 container per 3 classi di integrazione.

### `@ManyToOne`, proxy e N+1
> Spiegazione completa: [`docs/studio/10-modulo-catalog.md`](../studio/10-modulo-catalog.md).

Per `@ManyToOne` il default di JPA è `EAGER`: va scritto `LAZY`. Il campo contiene allora un proxy, una sottoclasse generata da Hibernate che si carica al primo metodo chiamato. Il proxy si carica solo a contesto aperto: fuori dalla transazione arriva la `LazyInitializationException`, per questo il servizio restituisce `record` costruiti dentro la transazione. I metodi raggiunti tramite proxy sono pubblici: è il caso che Hibernate garantisce (la classe resta non pubblica). Il test `reactivatingPackageLoadsItsServiceThroughTheProxy` lo prova: con un proxy che non si carica, `isActive()` leggerebbe `false`.

**N+1 verificato nel log**: l'elenco dei pacchetti del pannello con `join fetch` fa una `select` con la `join`; senza, una `select` per i pacchetti più una su `service` per ciascuno (1 + 3 con 3 pacchetti). Per vederlo serve `entityManager.clear()` prima della lettura: i servizi già nel contesto non vengono riletti.

### La proprietà dentro la query
`findByIdAndPractitionerId`: la cosa di un altro e la cosa inesistente danno lo stesso `Optional` vuoto, quindi la stessa `NotFoundException`. Il controllo non si può dimenticare in un metodo nuovo.

### Una modifica non valida non lascia l'oggetto a metà
Le entità controllano **tutti** i valori prima di assegnare il primo campo. Altrimenti un nome valido con un prezzo non valido lascerebbe in memoria il nome nuovo e il prezzo vecchio. Il database non lo vedrebbe (la `RuntimeException` porta al rollback), ma un oggetto incoerente in memoria è un difetto.

### Container dei test
Ogni configurazione di test diversa è un contesto Spring diverso, e quindi un container: oggi 4 (solo JPA, + `PractitionerService`, + `CatalogService`, applicazione intera con `@SpringBootTest`). Le due classi `@SpringBootTest` condividono lo stesso contesto. Se il numero cresce, si può importare tutti i servizi in un'unica configurazione di test comune.

### Le API: controller, validazione, errori
> Spiegazione completa: [`docs/studio/11-api-pannello.md`](../studio/11-api-pannello.md).

Il controller non ha logica: legge chi agisce, fa validare la forma (`@Valid`), chiama il servizio, sceglie lo stato HTTP. Se la validazione fallisce il metodo non viene nemmeno chiamato: `MethodArgumentNotValidException` → 400 con un elenco di campi e vincoli (`Positive`, `NotBlank`, `NotNull`), che il frontend traduce. Ogni altra eccezione diventa un `ProblemDetail` (RFC 9457) in un solo `@RestControllerAdvice`: mai stack trace, SQL o messaggi del database nella risposta, che vanno solo nel log.

**404 e non 403**: la cosa di un altro e la cosa inesistente danno la stessa risposta con lo stesso messaggio. Con un 403, chi prova gli id uno dopo l'altro scoprirebbe quali esistono (enumerazione). Il test `someoneElsesServiceAndMissingOneLookTheSame` lo verifica.

**Jackson 3 e i numeri mancanti** (verificato): un `int` mancante in un `record` fa fallire la lettura dell'intero JSON con un 400 generico senza il nome del campo. Con `Integer`/`Long` + `@NotNull` il campo mancante è segnalato per nome.

### Due modi di testare le API
- `@SpringBootTest` + `@AutoConfigureMockMvc` + `MockMvcTester`: applicazione intera e database vero; la richiesta gira nel thread del test, quindi `@Transactional` annulla tutto. È l'unico modo di provare la proprietà, che sta nelle query.
- `@WebMvcTest` + `@MockitoBean`: solo lo strato web, con servizi finti, senza database. Serve per gli errori che dalle API vere non si possono provocare (un vincolo del database, un errore inatteso).

### Professionista corrente e dipendenze tra moduli
`CurrentPractitioner` sta in `practitioner`, non in `auth`: altrimenti il controller di `practitioner` dipenderebbe da `auth` e `auth` da `practitioner`. Senza cicli: `catalog` → `practitioner` → `auth` (D-028). Un bean singleton legge l'header della richiesta corrente tramite un proxy di `HttpServletRequest`, legato al thread.

## Decisioni
- D-025: foreign key composta per il servizio del pacchetto.
- D-026: tra moduli ci si riferisce per id; una sola `NotFoundException`.
- D-027: regole violate con `BusinessRuleException` e codice da `ErrorCode`, risposta 409.
- D-028: professionista corrente provvisorio (interfaccia + header), da rimuovere al passo 8; con le scelte collegate su URL, metodi HTTP e posizione delle annotazioni di validazione.
- Scelte minori del pezzo 3 (guida 10, 1.4): entità `ServiceOffer` su tabella `service`; il servizio di un pacchetto non cambia; disattivare e riattivare sono idempotenti; elenchi in ordine di creazione; nessuna disattivazione a cascata.
- D-022: prezzo unico `price_cents`, applicato in V2.

## Errori fatti e come sono stati corretti

| # | Errore | Sintomo | Causa | Correzione | Lezione |
|---|---|---|---|---|---|
| 1 | Nella guida 10 (prima versione): "il compilatore rifiuta l'import di `org.springframework.stereotype.Service` se nel package c'è una classe `Service`" | Nessuno: affermazione sbagliata, trovata rileggendo | Per le regole di Java un import esplicito **copre** una classe con lo stesso nome dello stesso package: compila, e `Service` diventa l'annotazione | Guida corretta (1.2) | Le regole di visibilità dei nomi sono sottili: verificarle prima di scriverle |
| 2 | `changeDetails` e `changeSettings` assegnavano un campo alla volta | Un nome valido con un prezzo non valido lasciava in memoria il nome nuovo | Ogni controllo era fatto subito prima della sua assegnazione | Prima tutti i controlli in variabili locali, poi tutte le assegnazioni; test "una modifica non valida non cambia niente" | Scrivendo il test di un caso di errore, chiedersi in che stato resta l'oggetto |
| 3 | Record interni di `PublicCatalog` chiamati `Service` e `Package` | Nessun errore, ma la stessa confusione appena evitata con `ServiceOffer`, e `Package` copre `java.lang.Package` | Nome scelto per brevità | `ServiceItem` e `PackageItem` | Un nome che copre un tipo noto costa a ogni lettura |
| 4 | Nella guida 11, `CurrentPractitioner` proposta nel modulo `auth` | Nessuno: trovato ragionando sulle dipendenze prima di scrivere il codice | Il controller di `practitioner` avrebbe dipeso da `auth`, e `auth` da `practitioner` per controllare l'esistenza: un ciclo tra moduli | Interfaccia e implementazione provvisoria in `practitioner` (D-028) | Prima di scegliere dove mettere una classe, disegnare chi chiama chi |
| 5 | `record` di ingresso con numeri `int`/`long` | Un campo numerico mancante dava 400 `"Failed to read request"`, senza il nome del campo (scoperto dal test `missingNumberIsReportedOnItsField`) | Jackson 3 fa fallire l'intera lettura se manca un primitivo in un `record` (Jackson 2 metteva `0`) | `Integer`/`Long` con `@NotNull` nei `record` di ingresso | Un test scritto per una domanda aperta ("cosa succede se...?") è il modo giusto di rispondere |
| 6 | Dopo il passaggio a `Long`, i test con `3000` come prezzo | Prima corsa: errori a **runtime** in 28 test; con `clean`: errori di compilazione `int cannot be converted to java.lang.Long` | L'autoboxing trasforma `int` solo in `Integer`, mai in `Long`. E Maven non aveva ricompilato i test non modificati: giravano con il bytecode vecchio | `3000L` nei test; `mvnw clean test` | Dopo un cambio di firma, `clean`: un errore a runtime può nascondere un errore di compilazione |

## Domande da colloquio
*(a fine passo; quelle sulla V2 sono nella guida 08)*
