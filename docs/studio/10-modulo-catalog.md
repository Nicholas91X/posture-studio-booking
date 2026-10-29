# Il modulo `catalog` — guida di studio

Questa guida accompagna il **pezzo 3 del passo 5**: le entità dei servizi e dei pacchetti, i loro repository e `CatalogService` con le regole. Il professionista crea, modifica, disattiva e riattiva le proprie cose; il cliente legge il catalogo attivo. Le API HTTP arrivano nei pezzi 4 e 5: qui il servizio riceve l'id del professionista come parametro.

Contiene:
- **la parte 1**: cosa si costruisce, e **due scelte** per te (il nome dell'entità, l'errore per una regola violata);
- **la parte 2**: la relazione `@ManyToOne` dentro il modulo, il caricamento *lazy* e la `LazyInitializationException`;
- **la parte 3**: il problema N+1 e come si vede nel log;
- **la parte 4**: le regole del servizio, una per una;
- **la parte 5**: la sintassi su un esempio **diverso** (sale studio e abbonamenti di una biblioteca) e la checklist;
- **la parte 6**: i test.

Teoria di riferimento: guida 06, 7.2 (Spring Data, `save`), 7.3 (entità *managed*); guida 09 (confini dei moduli, *dirty checking*); D-025 (foreign key composta), D-026 (id tra moduli, `NotFoundException`).

---

## Indice

1. [Cosa si costruisce, e due scelte](#parte-1--cosa-si-costruisce-e-due-scelte)
2. [La relazione tra pacchetto e servizio](#parte-2--la-relazione-tra-pacchetto-e-servizio)
3. [Il problema N+1](#parte-3--il-problema-n1)
4. [Le regole del servizio](#parte-4--le-regole-del-servizio)
5. [La sintassi, su un esempio diverso](#parte-5--la-sintassi-su-un-esempio-diverso)
6. [I test](#parte-6--i-test)
7. [Domande da colloquio](#domande-da-colloquio)
8. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Cosa si costruisce, e due scelte

### 1.1 I pezzi

| Classe | Visibilità | Cosa fa |
|---|---|---|
| Entità del servizio (nome da scegliere, 1.2) | package | Mappata su `service`. `practitionerId` come id (D-026). Protegge nome, durata e prezzo |
| `PackageOffer` | package | Mappata su `package_offer`. `practitionerId` come id, `@ManyToOne` **lazy** verso il servizio (stesso modulo) |
| Due repository | package | Uno per entità, con ricerche "per id **e** professionista" (4.1) |
| `CatalogService` | **public** | La porta del modulo: le operazioni del pannello e la lettura del catalogo pubblico |
| `record` di ingresso e di uscita | **public** | Cosa si manda per creare o modificare, cosa si riceve (pannello e pubblico) |
| Eccezione per le regole violate | **public**, in `shared` | Da scegliere (1.3) |

### 1.2 Prima scelta: il nome dell'entità del servizio

Il nome naturale sarebbe `Service`. Ma `CatalogService` deve avere l'annotazione `@Service` di Spring (`org.springframework.stereotype.Service`), e nello stesso file userebbe anche l'entità `Service` dello stesso package. Due tipi con lo stesso nome semplice nello stesso file.

Java lo permette, ma in modo traditore. Un import esplicito (`import org.springframework.stereotype.Service;`) **copre** la classe con lo stesso nome dello stesso package. Nel file, quindi, `Service` vuol dire l'annotazione di Spring, e per usare l'entità bisogna scrivere ogni volta il nome completo, `com.posturestudio.booking.catalog.Service`. Se invece l'import manca, `@Service` indica l'entità, e il compilatore si lamenta che non è un'annotazione. In entrambi i casi chi legge deve fermarsi a capire quale `Service` è quale.

Le strade:

| Strada | Come | Pro | Contro |
|---|---|---|---|
| **A — `ServiceOffer`** | Entità `ServiceOffer`, con `@Table(name = "service")` | Simmetrico con `PackageOffer` (l'offerta di un servizio, l'offerta di un pacchetto). Nessun conflitto | Il nome Java e quello della tabella sono diversi (`@Table` lo dichiara) |
| B — `Service` con nome completo | Entità `Service`; in `CatalogService` si scrive `@org.springframework.stereotype.Service` (senza import) | Stesso nome della tabella e della documentazione | Un nome lungo nell'annotazione; chi legge confonde comunque "il servizio Spring" e "il servizio dello studio" |
| C — Un altro nome di dominio | `Treatment`, `SessionType`, ... | Nessun conflitto | Un termine nuovo che non compare nei documenti |

La tabella **non si rinomina**: V2 è già nel tuo commit, e una migrazione eseguita non si tocca. Con `@Table` il nome Java può essere diverso da quello della tabella.

> **Decisa il 2026-10-29: strada A, `ServiceOffer`.** E per 1.3: una `BusinessRuleException` con codice da `enum`, risposta 409 (D-027).

Il mio consiglio è **A**. Più in generale, nel testo "servizio" resta il servizio dello studio; nel codice "service" da solo indica sempre un servizio Spring. Nel resto della guida uso `ServiceOffer`.

### 1.3 Seconda scelta: l'errore per una regola violata

Alcune richieste falliscono non perché la cosa **non esiste**, ma perché una **regola** lo impedisce:
- creare un pacchetto su un servizio disattivato;
- riattivare un pacchetto il cui servizio è disattivato (4.4).

Non è un 404: il servizio esiste ed è del professionista che chiede, quindi non c'è niente da nascondere. Non è nemmeno un 400: la richiesta è scritta bene, è lo **stato** delle cose a impedirla. Serve una seconda eccezione, accanto a `NotFoundException`.

L'architettura chiede già errori con "un codice leggibile (es. `SLOT_NOT_AVAILABLE`) che il frontend traduce in un messaggio in italiano". La proposta:

```java
// shared/BusinessRuleException.java
public class BusinessRuleException extends RuntimeException {
    private final String code;          // es. "SERVICE_NOT_ACTIVE"
    ...
}
```

Due domande per te:
1. **Una classe sola con un codice, o una classe per ogni regola?** Una sola con il codice: il gestore degli errori (pezzo 5) ne conosce una, e il codice arriva al frontend. Le regole crescono a ogni passo (orario non disponibile, credito scaduto, preavviso superato): con una classe per regola diventerebbero decine.
2. **Quale stato HTTP?** Due candidati:
   - **409 Conflict**: la richiesta è in conflitto con lo **stato attuale** di una risorsa. Il servizio è disattivato; l'orario è già preso.
   - **422 Unprocessable Content**: la richiesta è ben formata ma il **contenuto** non ha senso.

   Consiglio **409**: quasi tutte le nostre regole dipendono dallo stato (disattivato, già prenotato, scaduto), non dal contenuto. Lo stato si sceglie nel pezzo 5, ma conviene deciderlo ora insieme alla classe.

Il codice come `String` o come `enum`? Un `enum` (`ErrorCode.SERVICE_NOT_ACTIVE`) impedisce gli errori di battitura e fa vedere l'elenco completo in un posto solo. Consiglio l'`enum`, nello stesso package `shared`.

### 1.4 Le scelte che propongo senza chiederti (dimmi se non ti tornano)

- **Il servizio di un pacchetto non cambia.** Si modificano nome, sedute, prezzo e validità, non il servizio. Un pacchetto "5 sedute posturali" che diventa "5 sedute di altro" è un altro pacchetto: si disattiva il vecchio e se ne crea uno nuovo. Nel mapping: `@JoinColumn(updatable = false)`.
- **Disattivare una cosa già disattivata non è un errore** (e lo stesso per riattivare): il risultato è quello chiesto. Un'operazione che ripetuta dà lo stesso risultato si dice **idempotente**, e semplifica il frontend (un doppio clic non produce errori).
- **Gli elenchi del pannello sono ordinati per id**, cioè per data di creazione. Un ordine stabile evita che le righe "saltino" tra un caricamento e l'altro.
- **Disattivare un servizio non disattiva i suoi pacchetti** (lo dice il brief: nessuna disattivazione a cascata). Il catalogo pubblico li nasconde con un filtro (4.5). Se il servizio torna attivo, i pacchetti tornano visibili senza che nessuno debba riattivarli uno per uno.

---

## Parte 2 — La relazione tra pacchetto e servizio

### 2.1 `@ManyToOne`: molti pacchetti, un servizio

`PackageOffer` e `ServiceOffer` sono nello stesso modulo, quindi la relazione si mappa come oggetto (D-026):

```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "service_id", updatable = false)
private ServiceOffer service;
```

- `@ManyToOne`: dal lato del pacchetto, "molti pacchetti hanno un servizio". La colonna `service_id` sta in `package_offer`.
- `@JoinColumn(name = "service_id")`: quale colonna contiene la chiave. Il nome di default sarebbe proprio `service_id` (campo `service` + `_id`), ma scriverlo rende chiaro il collegamento con V2.
- `optional = false`: il pacchetto ha sempre un servizio (in V2 la colonna è `not null`).
- `updatable = false`: 1.4.

E `practitionerId`? Resta un `Long` (D-026), anche se nel database è parte della foreign key composta. Java non lo sa, il database sì: se per un bug un pacchetto venisse salvato con il professionista sbagliato, la foreign key composta lo rifiuterebbe.

**Serve anche il lato opposto** (`@OneToMany` su `ServiceOffer`, "un servizio ha molti pacchetti")? No. Una relazione bidirezionale va tenuta allineata da entrambi i lati, e al servizio non serve mai navigare verso i suoi pacchetti: quando servono, li cerca il repository. La regola pratica: si mappa solo il lato che il codice usa.

### 2.2 `LAZY`, e perché va scritto

**Attenzione: per `@ManyToOne` il default di JPA è `EAGER`**: ogni volta che carichi un pacchetto, Hibernate carica subito anche il servizio. Sembra comodo, ma:
- carichi dati che spesso non servono;
- con `EAGER` non c'è modo di dire "questa volta no";
- negli elenchi, `EAGER` produce proprio le query in più della parte 3, senza che tu le chieda.

Con `LAZY`, Hibernate mette nel campo un **proxy**: una sottoclasse di `ServiceOffer` generata a runtime, che conosce solo l'id. La prima volta che chiami un suo metodo (diverso da `getId()`), il proxy esegue la `select` e si riempie. Per questo:
- l'entità non può essere `final` (il proxy è una sottoclasse; un altro motivo per cui un `record` non può essere un'entità);
- `getId()` deve essere chiamabile senza caricare (guida 05, 1.8). Il proxy lo sa già, perché è il valore della colonna `service_id`;
- i metodi chiamati attraverso il proxy devono essere **pubblici**: è il caso che Hibernate garantisce di intercettare. Un metodo che il proxy non intercetta leggerebbe i campi vuoti del proxy stesso (`false`, `0`, `null`) invece di caricare l'entità. La **classe** resta package-private, quindi fuori dal modulo quei metodi non si vedono comunque. Nel progetto lo prova il test `reactivatingPackageLoadsItsServiceThroughTheProxy`.

Con `LAZY` si sceglie caso per caso: se una query ha bisogno del servizio, lo chiede (`join fetch`, parte 3).

### 2.3 La `LazyInitializationException`

Il proxy può caricarsi **solo finché il contesto di persistenza è aperto**, cioè dentro la transazione. Con `open-in-view=false` (D-019) il contesto si chiude quando il metodo del servizio finisce.

```
CatalogService.listPackages(...)     @Transactional(readOnly = true)
  pacchetti caricati, service = proxy non caricato
  return entità                      ← contesto chiuso
controller: pacchetto.getService().getName()
  → LazyInitializationException: could not initialize proxy - no session
```

Ecco perché il servizio costruisce i `record` **dentro** la transazione: legge `getService().getName()` mentre il contesto è aperto, e restituisce dati semplici. Con OSIV attivo questo errore non comparirebbe: la query partirebbe nel controller, di nascosto, magari una per riga. D-019 serve proprio a rendere visibile questo problema.

---

## Parte 3 — Il problema N+1

### 3.1 Cos'è

Il pannello mostra i pacchetti, ognuno con il nome del suo servizio. Il codice più semplice:

```java
List<PackageOffer> offers = packageRepository.findByPractitionerIdOrderById(practitionerId);
for (PackageOffer offer : offers) {
    offer.getService().getName();   // ← carica il proxy
}
```

Query eseguite:
1. una `select` per i pacchetti;
2. **una `select` per il servizio di ciascun pacchetto** (se i servizi sono diversi).

Con N pacchetti: **1 + N** query, da cui il nome. Con 5 pacchetti non lo noti; con 500 righe e la rete tra applicazione e database, la pagina rallenta senza che nel codice si veda niente di strano. È uno dei problemi di prestazioni più comuni con JPA, e una domanda classica nei colloqui.

Un dettaglio: se i servizi sono **già** nel contesto di persistenza (perché li hai caricati prima nella stessa transazione), Hibernate usa quelli e non rifà le query. Il problema N+1 dipende quindi anche dall'**ordine** delle letture, e per questo va verificato nel log, non dedotto.

### 3.2 Come si vede

Nelle proprietà dei test:

```properties
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
```

Nel log compaiono tutte le `select`. Se ne vedi una per i pacchetti seguita da tante uguali `select ... from service where id=?`, è N+1.

### 3.3 Come si evita

| Strumento | Come | Quando |
|---|---|---|
| `join fetch` in JPQL | `select p from PackageOffer p join fetch p.service where p.practitionerId = :id order by p.id` | Quando la query è già scritta a mano |
| `@EntityGraph` | `@EntityGraph(attributePaths = "service")` su un metodo derivato dal nome | Quando basta una query derivata (guida 06, 7.2) |
| Proiezione su `record` | `select new ...PackageView(p.id, p.name, s.name, ...) from PackageOffer p join p.service s ...` | Sola lettura, solo le colonne che servono, nessuna entità |

Tutte e tre portano a **una** query con una `join`. Per il catalogo pubblico (4.5) il `join fetch` serve comunque, perché il filtro "servizio attivo" è una condizione sul servizio.

---

## Parte 4 — Le regole del servizio

`CatalogService` riceve sempre il `practitionerId` di chi agisce. Nel pezzo 4 glielo passerà il controller, prendendolo dal "professionista corrente".

### 4.1 La regola di proprietà, in una query

Il professionista A chiede di modificare il servizio 10, che è di B. La risposta deve essere 404, come se il servizio non esistesse (il perché nel pezzo 4).

Il modo più sicuro è che il repository **non possa nemmeno trovare** le cose degli altri:

```java
Optional<ServiceOffer> findByIdAndPractitionerId(Long id, Long practitionerId);
```

- Esiste ed è di A: trovato.
- Esiste ma è di B: vuoto → `NotFoundException`.
- Non esiste: vuoto → `NotFoundException`.

Le ultime due risposte sono **identiche** per costruzione. Leggere con `findById` e poi controllare `if (!service.getPractitionerId().equals(...))` funziona, ma è un controllo che si può dimenticare in un metodo nuovo. Con la query, la proprietà è parte della ricerca stessa.

### 4.2 Creare un servizio

1. Costruire l'entità con il costruttore che controlla i valori (nome non vuoto, durata e prezzo > 0).
2. `save`: qui **serve**, perché l'entità è nuova. `save` chiama `persist` e, con `IDENTITY`, l'`insert` parte subito: l'id è disponibile appena dopo.
3. Restituire il `record` con l'id.

Il confronto con la guida 09: lì il `save` non serviva (entità *managed*), qui sì (entità nuova, sconosciuta al contesto).

### 4.3 Modificare un servizio

1. `findByIdAndPractitionerId` → `NotFoundException` se manca.
2. Un metodo dell'entità cambia nome, durata e prezzo, con i controlli.
3. Niente `save` (*dirty checking*).

Qui va il commento che chiede il brief: **cambiare prezzo o durata non tocca nulla di già prenotato o venduto**. Prenotazioni e lotti avranno la loro copia del prezzo (D-011), e ogni prenotazione salva il suo orario di inizio e fine. Il commento serve a chi leggerà il codice al passo 7 o 10 e si chiederà "e le prenotazioni già fatte?".

### 4.4 Creare, modificare, disattivare e riattivare un pacchetto

**Creare:**
1. Cercare il servizio con `findByIdAndPractitionerId(serviceId, practitionerId)`: se è di un altro, `NotFoundException`. Questo è il controllo Java di D-025: la foreign key composta resta sotto, come ultima difesa.
2. Se il servizio è disattivato: eccezione di regola (1.3), codice `SERVICE_NOT_ACTIVE`.
3. Costruire il pacchetto con il servizio e il `practitionerId`, `save`.

**Modificare:** come il servizio, senza toccare il servizio collegato (1.4).

**Disattivare:** sempre possibile.

**Riattivare:** se il servizio collegato è disattivato, la regola "un pacchetto si vende solo su un servizio attivo" vale anche qui. Un pacchetto riattivato su un servizio spento sarebbe comunque invisibile nel catalogo pubblico, e il professionista non capirebbe perché. Meglio un errore chiaro: `SERVICE_NOT_ACTIVE`.

Disattivare o riattivare un servizio, invece, non ha regole: si può sempre.

### 4.5 Il catalogo pubblico

Per un professionista:
- i servizi **attivi**;
- i pacchetti **attivi** il cui servizio è **attivo**.

```
select p from PackageOffer p join fetch p.service s
where p.practitionerId = :practitionerId and p.active = true and s.active = true
order by p.id
```

Una sola query, con la `join` che serve sia al filtro sia a evitare N+1. La query si può derivare dal nome (`findByPractitionerIdAndActiveTrueAndServiceActiveTrue...`), ma il nome diventa illeggibile e senza `join fetch`. Una `@Query` scritta a mano è più chiara (PD-19: il nome di un metodo **è** una query).

I `record` pubblici contengono solo ciò che serve al cliente: niente `active` (sono tutti attivi), niente date di creazione. Prezzi in centesimi interi: la formattazione in euro la fa il frontend.

**Cosa non sa questo modulo:** se il professionista esiste ed è attivo. Per un id inesistente il catalogo è semplicemente vuoto. Il controllo "professionista attivo" arriva nel pezzo 5, con il problema già annotato (guida 09, 1.3).

---

## Parte 5 — La sintassi, su un esempio diverso

Una biblioteca con **sale studio** prenotabili, e **abbonamenti** a N ingressi per una sala. Ogni sede gestisce le proprie. Stessa forma del nostro modulo: l'abbonamento ha un `@ManyToOne` verso la sala (stesso modulo), la sede è un id (altro modulo).

```java
// rooms/StudyRoom.java
@Entity
@Table(name = "study_room")
class StudyRoom {

    protected StudyRoom() {
        // JPA needs a no-arg constructor
    }

    StudyRoom(Long branchId, String name, int seats) {
        this.branchId = Objects.requireNonNull(branchId, "branchId must not be null");
        rename(name);
        this.seats = requirePositive(seats, "seats");
        this.active = true;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // another module: only the id (D-026 in our project)
    @Column(updatable = false)
    private Long branchId;

    @Column
    private String name;

    @Column
    private int seats;

    @Column
    private boolean active;

    // public methods in a package-private class: RoomPass reaches this entity through a lazy proxy,
    // and Hibernate guarantees to intercept (and load) public methods. The class still hides them
    public void rename(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name.strip();
    }

    public void deactivate() { this.active = false; }
    public void activate() { this.active = true; }

    public Long getId() { return id; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    // ...
}
```

```java
// rooms/RoomPass.java
@Entity
@Table(name = "room_pass")
class RoomPass {

    protected RoomPass() {
    }

    RoomPass(Long branchId, StudyRoom room, int entries) {
        this.branchId = Objects.requireNonNull(branchId, "branchId must not be null");
        this.room = Objects.requireNonNull(room, "room must not be null");
        this.entries = requireAtLeast(entries, 2, "entries");
        this.active = true;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long branchId;

    // same module: a real relation, lazy, never changed after creation
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", updatable = false)
    private StudyRoom room;

    @Column
    private int entries;

    @Column
    private boolean active;
    // ...
}
```

```java
// rooms/RoomPassRepository.java
interface RoomPassRepository extends JpaRepository<RoomPass, Long> {

    Optional<RoomPass> findByIdAndBranchId(Long id, Long branchId);

    // one query: the join filters on the room and loads it (no N+1)
    @Query("""
            select p from RoomPass p join fetch p.room r
            where p.branchId = :branchId and p.active = true and r.active = true
            order by p.id
            """)
    List<RoomPass> findOnSale(Long branchId);
}
```

```java
// rooms/RoomService.java (extract)
@Transactional
public RoomPassView createPass(long branchId, NewRoomPass request) {
    StudyRoom room = roomRepository.findByIdAndBranchId(request.roomId(), branchId)
            .orElseThrow(() -> new NotFoundException("Study room " + request.roomId() + " not found"));
    if (!room.isActive()) {
        throw new BusinessRuleException(ErrorCode.ROOM_NOT_ACTIVE, "Study room " + room.getId() + " is not active");
    }
    // new entity: save() is needed here (persist), unlike a managed one
    RoomPass pass = passRepository.save(new RoomPass(branchId, room, request.entries()));
    return RoomPassView.from(pass);
}
```

Da notare:
- **La query JPQL in un *text block*** (`"""`, Java 15+): l'SQL su più righe, leggibile, senza `+` e `\n`. È la loro occasione d'uso tipica.
- **`:branchId` senza `@Param`**: Spring Data legge il nome del parametro dal metodo, perché Spring Boot compila con l'opzione `-parameters`. Se un giorno vedi un errore sul nome del parametro, la causa è quella.
- **JPQL usa nomi Java**: `p.room`, `r.active`, `RoomPass`, non `room_pass` e `room_id`.
- **`RoomPassView.from(pass)`**: un metodo `static` dentro il `record` che costruisce la vista dall'entità. È comodo, ma il `record` è pubblico e l'entità no: il metodo deve essere package-private (`static RoomPassView from(RoomPass p)`, senza `public`). Così chi sta fuori dal modulo vede il `record`, non il modo in cui nasce.
- **`strip()`** invece di `trim()` (Java 11): toglie tutti gli spazi Unicode, non solo i caratteri ≤ U+0020.

### Cosa deve contenere il nostro modulo

- [ ] Entità del servizio (nome scelto in 1.2) su `service`: `practitionerId` come id, nome, durata, prezzo, `active`, `createdAt`; costruttore e metodo di modifica con i controlli; `activate`/`deactivate`
- [ ] Il commento su prezzo e durata che non toccano il passato (4.3)
- [ ] `PackageOffer` su `package_offer`: `practitionerId` come id, `@ManyToOne(fetch = LAZY, optional = false)` verso il servizio con `updatable = false`, sedute ≥ 2, prezzo e validità > 0
- [ ] Due repository package-private, con `findByIdAndPractitionerId`, gli elenchi del pannello ordinati per id e le due letture del catalogo pubblico (pacchetti con `join fetch`)
- [ ] L'eccezione di regola e l'`enum` dei codici in `shared` (1.3)
- [ ] `CatalogService` pubblico: per servizi e pacchetti elenco (anche disattivati), crea, modifica, disattiva, riattiva; catalogo pubblico
- [ ] `record` pubblici di ingresso (creazione, modifica) e di uscita (pannello, pubblico)
- [ ] Nessun `@OneToMany`
- [ ] `catalog/package-info.java` con la descrizione del modulo

---

## Parte 6 — I test

### 6.1 Unitari (le entità, senza Spring)

| # | Caso | Atteso |
|---|---|---|
| 1 | Servizio con nome `"  "`, durata 0 o prezzo 0 | `IllegalArgumentException` che nomina il campo |
| 2 | Pacchetto da 1 seduta, validità 0, prezzo 0 | `IllegalArgumentException` |
| 3 | Pacchetto da 2 sedute | accettato (valore limite) |
| 4 | Nome con spazi intorno | salvato senza gli spazi |

### 6.2 Di integrazione (`@DataJpaTest` + configurazione del container + `CatalogService`)

Due professionisti inseriti con SQL, come in `PractitionerServiceTest`.

| # | Caso | Atteso |
|---|---|---|
| 5 | A crea un servizio | id valorizzato, `active` vero, la riga c'è |
| 6 | A modifica il prezzo del suo servizio, `flush`, lettura con SQL | prezzo nuovo nella riga, senza `save` |
| 7 | A modifica il servizio di B | `NotFoundException`, e la riga di B è invariata |
| 8 | A crea un pacchetto sul proprio servizio attivo | accettato |
| 9 | A crea un pacchetto sul servizio di B | `NotFoundException` (il controllo Java; la foreign key è provata in `CatalogSchemaTest`) |
| 10 | A crea un pacchetto sul proprio servizio disattivato | eccezione di regola con codice `SERVICE_NOT_ACTIVE` |
| 11 | A riattiva un pacchetto il cui servizio è disattivato | `SERVICE_NOT_ACTIVE` |
| 12 | Disattivare due volte | nessun errore, resta disattivato |
| 13 | Elenco del pannello di A | contiene anche i disattivati, non contiene niente di B, in ordine di id |
| 14 | Catalogo pubblico di A | solo servizi attivi; solo pacchetti attivi con servizio attivo; niente di B |
| 15 | Catalogo pubblico: il servizio torna attivo | il suo pacchetto (mai disattivato) ricompare |

### 6.3 Due esperimenti da fare una volta

1. **N+1.** Con `show-sql`, scrivi temporaneamente la lettura dei pacchetti senza `join fetch` e conta le `select` nel log, con 3 pacchetti su 3 servizi diversi. Poi rimetti il `join fetch`: una sola `select`. Ricorda il dettaglio di 3.1: se nello stesso test hai già caricato i servizi, le query in più spariscono. Per vederle davvero, `entityManager.clear()` prima della lettura.
2. **`LazyInitializationException`.** In un test **senza** transazione (annota il metodo con `@Transactional(propagation = Propagation.NOT_SUPPORTED)`, che sospende quella del test), carica un pacchetto dal repository e chiama `getService().getName()`. Vedrai l'eccezione: è quello che succederebbe nel controller se il servizio restituisse le entità. Attenzione: senza la transazione del test **non c'è rollback**, quindi i dati inseriti restano. Fallo in un test separato, che pulisce dopo di sé, oppure solo come prova da cancellare.

---

## Domande da colloquio

### Domanda 1: Qual è il fetch di default di `@ManyToOne`, e perché lo cambi?
**Risposta modello.** `EAGER`: caricando un pacchetto, JPA carica subito anche il servizio, anche quando non serve. Negli elenchi questo produce una query per riga senza che tu la chieda, e non c'è modo di dire "questa volta no". Con `LAZY` il campo contiene un proxy che si carica al primo uso, e ogni query sceglie cosa caricare con `join fetch` o `@EntityGraph`. Regola pratica: tutte le relazioni `LAZY`, e si carica esplicitamente ciò che serve.

### Domanda 2: Cos'è il problema N+1, come lo scopri e come lo risolvi?
**Risposta modello.** Una query carica N righe, poi l'accesso alla relazione *lazy* di ciascuna ne esegue un'altra: 1 + N query invece di una. Non si vede nel codice, solo nel log SQL (`show-sql`) o con strumenti che contano le query. Si risolve caricando la relazione nella stessa query: `join fetch` in JPQL, `@EntityGraph` su un metodo del repository, o una proiezione su `record` con una `join`. Dipende anche dal contesto di persistenza: le entità già caricate nella transazione non vengono rilette.

### Domanda 3: Quando ottieni una `LazyInitializationException`, e perché con OSIV attivo non la vedresti?
**Risposta modello.** Quando si accede a una relazione *lazy* non ancora caricata dopo che il contesto di persistenza è chiuso, tipicamente nel controller o nella serializzazione JSON, dopo la fine del servizio. Con *Open Session In View* il contesto resta aperto per tutta la richiesta, quindi la query parte lo stesso, ma fuori dal servizio, di nascosto, magari una per riga, tenendo occupata una connessione. Con OSIV disattivato l'errore rende visibile il problema; la soluzione è caricare ciò che serve nel servizio e restituire DTO.

### Domanda 4: Come implementi "un utente vede solo le sue risorse" a livello di repository?
**Risposta modello.** Con ricerche che includono il proprietario: `findByIdAndPractitionerId(id, practitionerId)`. Una risorsa di un altro e una risorsa inesistente danno lo stesso risultato vuoto, quindi la stessa risposta (404), e il controllo non si può dimenticare in un metodo nuovo, perché è parte della query. Controllare dopo con un `if` funziona, ma dipende dal ricordarsene ogni volta.

### Domanda 5: Quando serve `save` e quando no?
**Risposta modello.** Serve per un'entità **nuova**: `save` chiama `persist`, e con `IDENTITY` l'`insert` parte subito e l'id è disponibile. Serve anche per un'entità *detached* (`merge`). Non serve per un'entità caricata nella transazione corrente: è *managed*, e Hibernate scrive le modifiche al flush (*dirty checking*).

### Domanda 6: Perché non mappi anche il `@OneToMany` dal servizio verso i suoi pacchetti?
**Risposta modello.** Perché il codice non lo usa. Una relazione bidirezionale va tenuta allineata dai due lati: aggiungere un pacchetto vuol dire impostare il servizio nel pacchetto **e** aggiungerlo alla lista del servizio, e il proprietario della colonna resta il lato `@ManyToOne`. In più invita a caricare collezioni intere. Quando servono i pacchetti di un servizio, una query del repository è più chiara e controllabile.

---

## Documentazione ufficiale

- Jakarta Persistence 3.2, `@ManyToOne` e i default di fetch: https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2
- Hibernate ORM 7, strategie di fetch, proxy e `join fetch`: https://docs.jboss.org/hibernate/orm/7.0/userguide/html_single/Hibernate_User_Guide.html#fetching
- Spring Data JPA, `@Query` e `@EntityGraph`: https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html
- RFC 9110, semantica di 409 Conflict e 422 Unprocessable Content: https://www.rfc-editor.org/rfc/rfc9110#name-409-conflict
- JEP 378, i *text block*: https://openjdk.org/jeps/378
