# Le API pubbliche — guida di studio

Questa guida accompagna il **pezzo 5 del passo 5**, l'ultimo: le API che chiunque può chiamare **senza accesso**. Sono quelle che userà la prima pagina del sito, dove il cliente sceglie il professionista e vede cosa vende:

```
GET /api/public/practitioners                 → chi sono i professionisti attivi
GET /api/public/practitioners/{id}/catalog    → servizi e pacchetti in vendita di uno di loro
```

Contiene:
- **la parte 1**: cosa si costruisce;
- **la parte 2**: **la scelta** principale: come sapere chi è "attivo" senza violare D-002. La decisione è tua;
- **la parte 3**: la seconda scelta, più piccola: dove si controlla che il professionista del catalogo esista e sia attivo;
- **la parte 4**: cosa esce da un'API pubblica, e cosa no;
- **la parte 5**: la sintassi nuova (una query JPQL che restituisce solo gli id);
- **la parte 6**: i test;
- **la parte 7**: la chiusura del passo.

Teoria di riferimento: guida 09, 1.3 (il problema, già annunciato); D-002 (i moduli si parlano solo tramite i servizi); D-026 (riferimenti tra moduli per id); guida 11, parte 5 (404 e non 403).

---

## Indice

1. [Cosa si costruisce](#parte-1--cosa-si-costruisce)
2. [La scelta: chi è "attivo"?](#parte-2--la-scelta-chi-è-attivo)
3. [Dove si controlla il professionista del catalogo](#parte-3--dove-si-controlla-il-professionista-del-catalogo)
4. [Cosa esce da un'API pubblica](#parte-4--cosa-esce-da-unapi-pubblica)
5. [La sintassi nuova](#parte-5--la-sintassi-nuova)
6. [I test](#parte-6--i-test)
7. [La chiusura del passo](#parte-7--la-chiusura-del-passo)
8. [Domande da colloquio](#domande-da-colloquio)
9. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Cosa si costruisce

### 1.1 I pezzi

| Classe | Dove | Cosa fa |
|---|---|---|
| `AccountService` | `auth` | **Nuova**, la prima porta pubblica del modulo `auth`: dice quali utenti sono attivi (parte 2) |
| Una query in `AppUserRepository` | `auth` | Restituisce gli id degli utenti attivi tra quelli richiesti (parte 5) |
| `PractitionerService.listActive()` e `requireActive(id)` | `practitioner` | L'elenco pubblico, e il controllo "esiste ed è attivo" |
| `PublicPractitioner` | `practitioner` | `record` con solo `id`, `firstName`, `lastName` (parte 4) |
| `PractitionerPublicController` | `practitioner` | `GET /api/public/practitioners` |
| `CatalogPublicController` | `catalog` | `GET /api/public/practitioners/{id}/catalog` |
| `CatalogService.publicCatalog(id)` | `catalog` | **Cambia**: un professionista inesistente o disattivato dà 404, non più un catalogo vuoto (parte 3) |

### 1.2 Perché `/api/public` e `/api/panel` separati

Al passo 8 arriva Spring Security. La regola più semplice e più sicura da scrivere sarà per **prefisso**: tutto ciò che sta sotto `/api/public/**` è aperto, tutto il resto richiede l'accesso. Se le API pubbliche fossero mescolate alle altre (`/api/practitioners` aperta, `/api/practitioners/{id}/bookings` chiusa) servirebbe una regola per ogni URL, e ogni API nuova sarebbe aperta o chiusa a seconda di chi si ricorda di aggiungerla. Con il prefisso, **una API dimenticata è chiusa**, non aperta: l'errore va dalla parte sicura.

### 1.3 Cosa resta fuori

- **Il calendario** (orari liberi): passo 6. Sarà anch'esso sotto `/api/public`, e mostrerà solo orari, mai chi ha prenotato (requisiti, "Riservatezza del calendario").
- **Un professionista disattivato che usa ancora il pannello.** Oggi `DevHeaderCurrentPractitioner` controlla solo che il professionista esista. Al passo 8 sarà il login a rifiutare un account disattivato: lo annoto lì, non lo tocco ora.

---

## Parte 2 — La scelta: chi è "attivo"?

### 2.1 Il problema (ripreso dalla guida 09, 1.3)

La tabella `practitioner` **non ha** una colonna `active`. La disattivazione vive in `app_user.active` (D-021: si disattiva l'account, e un professionista non si cancella mai). `app_user` è del modulo `auth`. Quindi per rispondere "quali professionisti sono attivi?" il modulo `practitioner` ha bisogno di un dato che non è suo.

```
practitioner (modulo practitioner)        app_user (modulo auth)
┌────┬─────────┬────────────┐             ┌────┬──────────────────┬────────┐
│ id │ user_id │ first_name │             │ id │ email            │ active │
├────┼─────────┼────────────┤             ├────┼──────────────────┼────────┤
│  1 │      10 │ Anna       │ ──────────► │ 10 │ anna@example.com │ true   │
│  2 │      11 │ Marco      │ ──────────► │ 11 │ marco@example.com│ false  │
└────┴─────────┴────────────┘             └────┴──────────────────┴────────┘
```

### 2.2 Strada A — `practitioner` chiede ad `auth`, tramite il suo servizio

`auth` espone un servizio pubblico, `AccountService`, con un metodo che riceve **un insieme** di id utente e restituisce quelli attivi:

```java
// auth
@Service
public class AccountService {
    @Transactional(readOnly = true)
    public Set<Long> activeAmong(Collection<Long> userIds) { ... }
}

// practitioner
public List<PublicPractitioner> listActive() {
    List<Practitioner> all = repository.findAllByOrderByLastNameAscFirstNameAsc();
    Set<Long> active = accounts.activeAmong(all.stream().map(Practitioner::getUserId).toList());
    return all.stream()
            .filter(p -> active.contains(p.getUserId()))
            .map(PublicPractitioner::from)
            .toList();
}
```

- **Pro**: rispetta D-002. `practitioner` non sa com'è fatta la tabella `app_user`: sa solo che `auth` risponde alla domanda "quali di questi utenti sono attivi?". Se un giorno `auth` cambia il modo di disattivare (per esempio con una data di disattivazione invece di un booleano), `practitioner` non se ne accorge.
- **Pro**: una sola verità. `active` resta in un posto solo.
- **Contro**: due query invece di una (una su `practitioner`, una su `app_user`). Con due professionisti è irrilevante.
- **Contro**: l'ordine e il filtro si fanno in Java. Anche questo, con due righe, è irrilevante.

**Un dettaglio importante: il metodo riceve una collezione, non un id alla volta.** Se `auth` offrisse solo `isActive(long userId)`, `listActive()` lo chiamerebbe **una volta per ogni professionista**: una query per l'elenco, più una per ciascuno. È lo stesso problema N+1 della guida 10, solo che qui attraversa un confine tra moduli invece di una relazione JPA, e nessun `join fetch` lo può risolvere. Chiedere "quali di questi sono attivi?" in un colpo solo lo evita: due query, sempre, con 2 professionisti come con 200.

### 2.3 Strada B — una join su `app_user` dentro il repository di `practitioner`

```java
// in PractitionerRepository: SQL nativo, perché Practitioner non ha una relazione JPA verso AppUser (D-026)
@Query(value = """
        select p.* from practitioner p
        join app_user u on u.id = p.user_id
        where u.active
        order by p.last_name, p.first_name
        """, nativeQuery = true)
List<Practitioner> findActive();
```

- **Pro**: una query sola, filtro e ordine nel database.
- **Contro**: è esattamente ciò che D-002 vieta: `practitioner` legge una tabella di `auth`. Non si vede dal compilatore (è una stringa SQL), quindi nessuno se ne accorge finché `auth` non cambia la tabella e la query si rompe a runtime.
- **Contro**: costringe a SQL nativo, perché con D-026 `Practitioner` ha solo `Long userId` e JPQL non può fare la join su una relazione che non esiste.

### 2.4 Strada C — una colonna `active` anche in `practitioner`

Migrazione V3 con `practitioner.active`, e una query derivata `findByActiveTrueOrderBy...`.

- **Pro**: tutto dentro il modulo, una query.
- **Contro**: **due verità** sullo stesso fatto. Disattivare un professionista vorrebbe dire aggiornare due righe in due moduli; basta dimenticarne una perché un professionista disattivato resti visibile al pubblico, o un professionista visibile non possa entrare. Prima o poi divergono.
- **Contro**: una migrazione in più per un dato che c'è già.

Una precisazione onesta: esiste un caso in cui una colonna in `practitioner` **avrebbe senso**, ma è un altro concetto. Per esempio "il professionista è in ferie per un mese: non deve comparire nel sito, ma deve poter entrare nel pannello". Quello non è "account attivo", è "visibile al pubblico", e avrebbe un nome diverso (`listed`, `visible`). Nei requisiti non c'è: se arriverà, sarà una decisione nuova. Non è un motivo per scegliere C oggi.

### 2.5 Confronto

| | A — servizio di `auth` | B — join | C — colonna doppia |
|---|---|---|---|
| Rispetta D-002 | ✅ | ❌ | ✅ |
| Una sola verità su "attivo" | ✅ | ✅ | ❌ |
| Query | 2, sempre | 1 | 1 |
| Migrazione | no | no | V3 |
| Se `auth` cambia la tabella | `practitioner` non lo sa | si rompe a runtime | non si rompe, ma diverge |

### 2.6 La mia raccomandazione

> **Decisa il 2026-10-30: strada A** (D-029), insieme alle scelte delle parti 3 e 3.5.

**A.** Il costo (una query in più, un filtro in Java) è trascurabile per sempre in uno studio con due professionisti, e anche con venti. Il beneficio è quello per cui abbiamo scelto i moduli: il confine tra `practitioner` e `auth` resta un confine. È anche la stessa forma che servirà più avanti in altri punti (per esempio `booking` che chiede a `catalog` il prezzo di un servizio): vale la pena prenderci la mano qui, dove il caso è semplice.

Le dipendenze tra moduli diventerebbero:

```
catalog ──► practitioner ──► auth
```

Sempre in un verso solo. `auth` non dipende da nessuno (per questo, al pezzo 4, `CurrentPractitioner` è finito in `practitioner` e non in `auth`).

---

## Parte 3 — Dove si controlla il professionista del catalogo

### 3.1 Il problema

`GET /api/public/practitioners/{id}/catalog` con un id inesistente, o di un professionista disattivato, deve rispondere **404**. Oggi `CatalogService.publicCatalog(id)` restituisce un catalogo vuoto, e il commento dice "lo controlla l'API pubblica (pezzo 5)". Chi fa il controllo?

### 3.2 Strada A — nel servizio del catalogo

```java
// CatalogService
public PublicCatalog publicCatalog(long practitionerId) {
    practitioners.requireActive(practitionerId);   // NotFoundException se non esiste o è disattivato
    ...
}
```

- **Pro**: è una regola ("il catalogo pubblico esiste solo per un professionista attivo"), e le regole stanno nel servizio. Il controller resta senza logica, come gli altri.
- **Pro**: chiunque chiami `publicCatalog` la rispetta, anche un futuro chiamante diverso dal controller.
- **Contro**: `CatalogServiceTest` ha bisogno anche di `PractitionerService` e `AccountService`. È un `@DataJpaTest`, quindi basta aggiungerli all'`@Import`; e il test `publicCatalogOfUnknownPractitionerIsEmpty` cambia: ora si aspetta un `NotFoundException`.

### 3.3 Strada B — nel controller

```java
// CatalogPublicController
PublicCatalog catalog(@PathVariable long id) {
    practitioners.requireActive(id);
    return catalog.publicCatalog(id);
}
```

- **Pro**: `CatalogService` non dipende da `PractitionerService`.
- **Contro**: due chiamate in sequenza **sono** logica: se qualcuno aggiunge un secondo punto d'ingresso al catalogo pubblico, deve ricordarsi di ripetere il controllo. È proprio il tipo di regola che si perde.

### 3.4 La mia raccomandazione

**A.** È la conseguenza diretta di "il controller non ha logica" (architettura, "Livelli dentro ogni modulo"). E la dipendenza `catalog → practitioner` esiste già, dal pezzo 4.

### 3.5 Una terza scelta, piccola: la forma dell'elenco

Il brief dice "solo id e nome". Due forme:
- **`{ "id": 1, "firstName": "Anna", "lastName": "Rossi" }`**: i due campi separati, come nel database;
- `{ "id": 1, "name": "Anna Rossi" }`: già composto.

Raccomando i **campi separati**: comporre il nome per mostrarlo ("Anna Rossi", "Rossi A.", "Dott.ssa Rossi") è presentazione, e la presentazione la fa il frontend. È la stessa regola dei prezzi in centesimi: il backend dà il dato, il frontend lo formatta.

L'ordine dell'elenco: per **cognome, poi nome**, deciso dal backend. Un ordine stabile fa parte del contratto: senza `order by`, PostgreSQL restituisce le righe nell'ordine che gli conviene, e due chiamate di fila potrebbero rispondere in ordine diverso.

---

## Parte 4 — Cosa esce da un'API pubblica

### 4.1 La regola

Un'API pubblica la può chiamare chiunque, anche un programma che la interroga mille volte. Ogni campo che esce è **pubblicato**. Per questo i `record` pubblici sono **dedicati**, non riusano quelli del pannello:

| Esce | Non esce, e perché |
|---|---|
| id, nome e cognome del professionista | email e telefono (dati personali: servono solo all'accesso e ai messaggi) |
| servizi e pacchetti in vendita: id, nome, durata, prezzo, sedute, validità | impostazioni del professionista (preavviso, scatto): sono regole interne, il loro effetto il cliente lo vede nel calendario e nelle cancellazioni |
| | il campo `active`: nel catalogo pubblico tutto è attivo per definizione, e un campo sempre `true` è solo un indizio che esistono elementi non attivi |
| | `practitionerId` dentro servizi e pacchetti: è già nell'URL |
| | qualsiasi dato di prenotazioni o clienti (requisiti, "Riservatezza del calendario") |

`PublicCatalog` del pezzo 3 segue già questa regola. Manca `PublicPractitioner`.

### 4.2 Il pericolo del `record` riusato

Se l'elenco pubblico restituisse un `record` "generale" del professionista, il giorno in cui qualcuno gli aggiunge un campo per il pannello (per esempio l'email, per mostrarla nelle impostazioni) quel campo comparirebbe **anche** nell'API pubblica, senza che nessuno lo decida. Con un `record` dedicato per ogni uso, aggiungere un campo al pubblico è una scelta esplicita. Il test della parte 6 (caso 2) lo blocca: controlla **l'elenco esatto** dei campi.

### 4.3 404 per "disattivato", come per "inesistente"

Stessa logica della guida 11, parte 5: chi chiama il catalogo del professionista 2 non deve poter distinguere "non esiste" da "esiste ma è disattivato". Stesso stato, stesso messaggio. Viene naturale se `requireActive` lancia la stessa `NotFoundException` nei due casi.

---

## Parte 5 — La sintassi nuova

### 5.1 Una query che restituisce solo gli id

Fin qui i repository restituivano entità. Qui serve solo un insieme di numeri: caricare intere entità `AppUser` per poi buttare via tutto tranne l'id sarebbe uno spreco (piccolo, ma è il caso giusto per vedere la sintassi). In JPQL la `select` può restituire **un campo** invece dell'entità:

```java
// un esempio diverso, su una ipotetica entità Book
@Query("select b.id from Book b where b.id in :ids and b.available = true")
Set<Long> findAvailableIdsAmong(@Param("ids") Collection<Long> ids);
```

- `select b.id`: il risultato è una collezione di `Long`, non di `Book`. Spring Data la mette nel tipo dichiarato (`Set<Long>`, `List<Long>`).
- `in :ids`: un parametro che è una **collezione**; Hibernate lo espande in `in (?, ?, ?)`.
- `@Param("ids")`: lega il parametro del metodo al nome nella query. Con il compilatore configurato con `-parameters` (lo fa Spring Boot) si può omettere, ma esplicito è più leggibile.

### 5.2 Una collezione vuota

Se `practitioner` è vuota, `activeAmong` riceve una lista vuota. `in ()` non è SQL valido; Hibernate di solito lo gestisce, ma è meglio non dipendere da come lo traduce. Un controllo in testa al metodo del servizio:

```java
if (userIds.isEmpty()) {
    return Set.of();
}
```

### 5.3 Una query derivata con l'ordine su due campi

```java
List<Practitioner> findAllByOrderByLastNameAscFirstNameAsc();
```

Spring Data legge il nome: `findAll` (nessun filtro), `By` (obbligatorio anche senza condizioni, prima di `OrderBy`), `OrderByLastNameAsc`, poi `FirstNameAsc`.

### 5.4 Java moderno: `Stream.toList()` e `Set`

`stream().toList()` (Java 16) restituisce una lista **non modificabile**, a differenza di `collect(Collectors.toList())`, che non promette niente. Lo usiamo già. Qui si aggiunge il `Set` per il filtro: `contains` su un `Set` costa sempre lo stesso, su una `List` scorre tutti gli elementi. Con due professionisti non cambia niente, ma è la struttura giusta per la domanda "è tra questi?".

---

## Parte 6 — I test

### 6.1 Dove

| Test | Tipo | Cosa prova |
|---|---|---|
| `AppUserRepositoryTest` (aggiunta) | `@DataJpaTest` | La query: restituisce solo gli attivi tra quelli richiesti |
| `AccountServiceTest` | `@DataJpaTest` | La collezione vuota |
| `PractitionerServiceTest` (aggiunte) | `@DataJpaTest` | `listActive` (filtro, ordine) e `requireActive` |
| `CatalogServiceTest` (modifica) | `@DataJpaTest` | Professionista inesistente o disattivato → `NotFoundException` |
| `PublicApiTest` | `@SpringBootTest` + `MockMvcTester` | Le due API dall'esterno |

### 6.2 I casi delle API

Professionisti inseriti con SQL, uno dei due con `app_user.active = false`.

| # | Caso | Atteso |
|---|---|---|
| 1 | Elenco dei professionisti, **senza** l'header `X-Practitioner-Id` | 200: le API pubbliche non chiedono chi sei |
| 2 | I campi di ogni professionista | **esattamente** `id`, `firstName`, `lastName` (niente email, telefono, impostazioni) |
| 3 | Un professionista disattivato | non compare nell'elenco |
| 4 | Ordine | per cognome, poi nome |
| 5 | Catalogo di un professionista attivo | solo servizi attivi e pacchetti attivi su servizi attivi |
| 6 | I campi di servizi e pacchetti nel catalogo | nessun `active`, nessun `practitionerId` |
| 7 | Catalogo di un professionista disattivato | 404, anche se ha servizi attivi |
| 8 | Catalogo di un id inesistente | 404, **identico** al caso 7 |
| 9 | Catalogo con un id non numerico (`/practitioners/abc/catalog`) | 400, non 500 |

Il caso 9 è un controllo che conviene fare una volta: `@PathVariable long id` con `"abc"` fa lanciare a Spring una `MethodArgumentTypeMismatchException`. `ResponseEntityExceptionHandler` la gestisce già come 400 (è una delle eccezioni di Spring MVC). Se non la gestisse, finirebbe nel nostro gestore generico come 500, ed è un errore del client, non del server.

---

## Parte 7 — La chiusura del passo

Dopo il codice e i test verdi, per chiudere il passo 5 (CLAUDE.md, "A fine sessione", e i criteri "Fatto quando" del brief):

1. **`passi/05-catalogo.md`**: il pezzo 5, gli errori, e le **domande da colloquio con le risposte modello**.
2. **`stato.md`**: passo 5 chiuso; per il passo 8, la regola per prefisso (`/api/public/**` aperto, il resto chiuso) e il login che rifiuta un account disattivato.
3. **`decisioni.md`**: una scheda per la scelta della parte 2 (come si legge "attivo" tra moduli).
4. **Il controllo dei criteri del brief**, uno per uno.
5. **Il messaggio di commit** del pezzo 5, e la **PR** del branch `feat/step-5-catalog` verso `main`, che apri tu.
6. **Il riepilogo** per la chat di pianificazione.

---

## Domande da colloquio

### Domanda 1: In un monolite modulare, un modulo ha bisogno di un dato che appartiene a un altro. Come lo ottiene?
**Risposta modello.** Chiedendolo al servizio pubblico dell'altro modulo, che restituisce `record` e non entità. Non con una join sulle sue tabelle: funzionerebbe, ma lega il primo modulo alla struttura interna del secondo, e il legame non lo vede il compilatore. Non con una copia del dato: due verità divergono. Il servizio va progettato per ricevere **insiemi** ("quali di questi sono attivi?"), non un elemento alla volta, altrimenti si ricrea il problema N+1 attraverso il confine tra moduli, dove nessun `join fetch` può aiutare.

### Domanda 2: Perché tenere le API pubbliche sotto un prefisso separato?
**Risposta modello.** Perché la regola di sicurezza diventa una sola: tutto ciò che sta sotto `/api/public/**` è aperto, tutto il resto richiede l'accesso. Un'API nuova dimenticata nella configurazione resta chiusa, non aperta: l'errore va dalla parte sicura. Con le API mescolate servirebbe una regola per ogni URL.

### Domanda 3: Perché un DTO dedicato per le API pubbliche, invece di riusare quello del pannello?
**Risposta modello.** Perché ogni campo di un'API pubblica è pubblicato. Se il DTO è condiviso, un campo aggiunto per il pannello (un'email, un'impostazione) comparirebbe anche al pubblico senza che nessuno lo decida. Con un DTO per ogni uso, esporre un campo è una scelta esplicita; e un test che controlla l'elenco esatto dei campi lo protegge.

### Domanda 4: Il professionista è disattivato ma ha servizi attivi. Che cosa risponde il catalogo pubblico?
**Risposta modello.** 404, come per un professionista che non esiste. Nessuna disattivazione a cascata dei servizi: il filtro si fa in lettura, come per i pacchetti di un servizio disattivato. Così, se il professionista viene riattivato, il suo catalogo ricompare com'era. E il 404 identico non rivela se l'id esiste.

### Domanda 5: Cosa succede se un `@PathVariable long` riceve "abc"?
**Risposta modello.** Spring non riesce a convertire il valore e lancia `MethodArgumentTypeMismatchException` prima di chiamare il metodo. `ResponseEntityExceptionHandler` la trasforma in un 400. Va verificato con un test, perché se finisse in un gestore generico diventerebbe un 500: un errore del client presentato come un errore del server.

### Domanda 6: In una query JPQL, come restituisci solo un campo invece dell'entità?
**Risposta modello.** Con `select e.campo from Entita e ...`: il risultato è una collezione di quel tipo (`List<Long>`, `Set<Long>`) invece che di entità. Non si caricano entità nel persistence context, e dal database arriva solo la colonna. Per strutture con più campi si usa una projection: un'interfaccia, o un `record` con `select new ...`.

---

## Documentazione ufficiale

- Spring Data JPA, query derivate dal nome del metodo: https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html
- Spring Data JPA, `@Query` e parametri con nome: https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.query-methods.at-query
- Spring Data JPA, projection: https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html
- Spring Framework, eccezioni di Spring MVC gestite da `ResponseEntityExceptionHandler`: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html
- Spring Modulith (riferimento per l'idea dei moduli e delle loro porte, non lo usiamo): https://docs.spring.io/spring-modulith/reference/fundamentals.html
