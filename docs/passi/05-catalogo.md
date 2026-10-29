# Passo 05 — Catalogo e professionisti

> **Stato: in corso** (2026-10-29). Pezzi 1–3 di 5 fatti.

## Obiettivo
Il professionista gestisce cosa vende e come lavora: servizi, pacchetti in vendita e impostazioni. Il cliente legge il catalogo attivo. Sono le prime API REST del progetto: richiesta → validazione → regole → database → risposta.

I pezzi:
1. Migrazione V2 e test dei vincoli — ✅ fatto
2. Modulo `practitioner`: entità, repository chiuso nel modulo, `PractitionerService`, le tre impostazioni — ✅ fatto
3. Modulo `catalog`: entità `ServiceOffer` e `PackageOffer`, `CatalogService` con le regole — ✅ fatto
4. "Professionista corrente" provvisorio e API del pannello, con validazione e regola di proprietà (404)
5. API pubbliche, `@RestControllerAdvice` con `ProblemDetail`, test delle API dall'esterno

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
Con tre classi di integrazione che importano configurazioni diverse (solo JPA, + `PractitionerService`, + `CatalogService`) i contesti Spring sono tre, e quindi i container. Se il numero cresce, si può importare tutti i servizi in un'unica configurazione di test comune.

## Decisioni
- D-025: foreign key composta per il servizio del pacchetto.
- D-026: tra moduli ci si riferisce per id; una sola `NotFoundException`.
- D-027: regole violate con `BusinessRuleException` e codice da `ErrorCode`, risposta 409.
- Scelte minori del pezzo 3 (guida 10, 1.4): entità `ServiceOffer` su tabella `service`; il servizio di un pacchetto non cambia; disattivare e riattivare sono idempotenti; elenchi in ordine di creazione; nessuna disattivazione a cascata.
- D-022: prezzo unico `price_cents`, applicato in V2.

## Errori fatti e come sono stati corretti

| # | Errore | Sintomo | Causa | Correzione | Lezione |
|---|---|---|---|---|---|
| 1 | Nella guida 10 (prima versione): "il compilatore rifiuta l'import di `org.springframework.stereotype.Service` se nel package c'è una classe `Service`" | Nessuno: affermazione sbagliata, trovata rileggendo | Per le regole di Java un import esplicito **copre** una classe con lo stesso nome dello stesso package: compila, e `Service` diventa l'annotazione | Guida corretta (1.2) | Le regole di visibilità dei nomi sono sottili: verificarle prima di scriverle |
| 2 | `changeDetails` e `changeSettings` assegnavano un campo alla volta | Un nome valido con un prezzo non valido lasciava in memoria il nome nuovo | Ogni controllo era fatto subito prima della sua assegnazione | Prima tutti i controlli in variabili locali, poi tutte le assegnazioni; test "una modifica non valida non cambia niente" | Scrivendo il test di un caso di errore, chiedersi in che stato resta l'oggetto |
| 3 | Record interni di `PublicCatalog` chiamati `Service` e `Package` | Nessun errore, ma la stessa confusione appena evitata con `ServiceOffer`, e `Package` copre `java.lang.Package` | Nome scelto per brevità | `ServiceItem` e `PackageItem` | Un nome che copre un tipo noto costa a ogni lettura |

## Domande da colloquio
*(a fine passo; quelle sulla V2 sono nella guida 08)*
