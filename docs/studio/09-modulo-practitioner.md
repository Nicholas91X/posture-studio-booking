# Il modulo `practitioner` — guida di studio

Questa guida accompagna il **pezzo 2 del passo 5**: l'entità `Practitioner`, il suo repository chiuso nel modulo e `PractitionerService`, la porta pubblica del modulo, con la lettura e la modifica delle tre impostazioni (preavviso, validità del credito singolo, scatto). Nessuna API HTTP ancora: i controller arrivano nel pezzo 4.

Contiene:
- **la parte 1**: cosa si costruisce e cosa è pubblico;
- **la parte 2**: **la scelta** su come un modulo si riferisce alle cose di un altro (oggetti o id). La decisione è tua;
- **la parte 3**: dove sta ogni controllo: validazione, regola, vincolo;
- **la parte 4**: `@Transactional` e il *dirty checking* applicati al nostro caso;
- **la parte 5**: la sintassi su un esempio **diverso** (le sedi di una biblioteca) e la checklist;
- **la parte 6**: i test, compreso il container condiviso tra le classi di test.

Teoria di riferimento, da non ripetere qui: guida 06, parte 3 (proxy), parte 6 (transazioni), 7.2–7.3 (Spring Data, entità *managed*); guida 05, 3.3 (repository non pubblici); D-002 (moduli), D-019 (*Open Session In View* disattivato).

---

## Indice

1. [Cosa si costruisce](#parte-1--cosa-si-costruisce)
2. [La scelta: tra moduli, oggetti o id?](#parte-2--la-scelta-tra-moduli-oggetti-o-id)
3. [Dove sta ogni controllo](#parte-3--dove-sta-ogni-controllo)
4. [Transazioni e dirty checking nel nostro caso](#parte-4--transazioni-e-dirty-checking-nel-nostro-caso)
5. [La sintassi, su un esempio diverso](#parte-5--la-sintassi-su-un-esempio-diverso)
6. [I test](#parte-6--i-test)
7. [Domande da colloquio](#domande-da-colloquio)
8. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Cosa si costruisce

### 1.1 I pezzi

| Classe | Visibilità | Cosa fa |
|---|---|---|
| `Practitioner` | dipende dalla scelta della parte 2 (consigliata: **package**) | Entità mappata su `practitioner` (V1). Protegge le proprie impostazioni: non accetta valori ≤ 0 |
| `PractitionerRepository` | **package** | `JpaRepository<Practitioner, Long>`. Lo usa solo il servizio del modulo |
| `PractitionerService` | **public** | La porta del modulo: `getSettings(id)`, `updateSettings(id, settings)`. Restituisce `record`, mai l'entità |
| `PractitionerSettings` | **public** | `record` con le tre impostazioni. Esce dal modulo: lo useranno il controller (pezzo 4) e altri moduli (es. `booking` per il preavviso, passo 11) |
| `NotFoundException` | **public**, in un package condiviso | "Non esiste" (o "non è tuo", pezzo 4). Diventerà un 404 nel pezzo 5 |

Nel codice di `AppUserRepository` la visibilità di package c'è già: nessuna parola davanti a `interface`. Spring Data crea comunque il repository, e il compilatore impedisce a una classe di un altro package di usarlo (guida 05, 3.3). È il modo più semplice per **far rispettare D-002 dal compilatore** invece che dalla buona volontà.

### 1.2 Cosa **non** si fa in questo pezzo

- **Creare un professionista**: lo fa l'admin (D-021), al passo 8. Nei test i professionisti si inseriscono con SQL, come in `CatalogSchemaTest`. Quindi `Practitioner` ha solo il costruttore `protected` per Hibernate: un costruttore pubblico che nessuno usa sarebbe codice da mantenere senza motivo.
- **Orizzonte e limite di prenotazioni** (D-023): passo 7.
- **Elenco pubblico dei professionisti attivi**: pezzo 5. C'è un problema da risolvere prima (1.3).

### 1.3 Un problema per il pezzo 5: chi è "attivo"?

Il brief chiede un'API pubblica con "i professionisti attivi". Ma in V1 la tabella `practitioner` **non ha** una colonna `active`. La disattivazione vive in `app_user.active` (D-021: si disattiva l'account), che è del modulo `auth`. Per sapere chi è attivo, il modulo `practitioner` deve conoscere un dato di un altro modulo. Le strade:

1. `PractitionerService` chiede al servizio pubblico di `auth` quali utenti sono attivi, e filtra. Rispetta D-002, al costo di due interrogazioni (irrilevante con due professionisti).
2. Una query con una join su `app_user` dentro il repository di `practitioner`. Una sola interrogazione, ma il modulo legge la tabella di un altro: è proprio ciò che D-002 vieta.
3. Una colonna `active` anche in `practitioner`: due verità da tenere allineate, che prima o poi divergono.

Non serve decidere ora: lo riprendiamo nel pezzo 5. Lo segnalo perché la parte 2 è legata a questa domanda.

---

## Parte 2 — La scelta: tra moduli, oggetti o id?

### 2.1 Il problema

`practitioner.user_id` punta ad `app_user`, che è del modulo `auth`. Nel pezzo 3, `service.practitioner_id` punterà a `practitioner` da un altro modulo ancora, `catalog`. In JPA ci sono due modi di mappare una chiave esterna:

```java
// A — come oggetto: l'entità conosce l'altra entità
@OneToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id")
private AppUser user;

// B — come id: l'entità conosce solo il numero
@Column(name = "user_id", updatable = false)
private Long userId;
```

Nel database non cambia niente: la colonna e la foreign key sono le stesse. Cambia **cosa sa il codice Java**.

### 2.2 Strada A — Riferimenti come oggetti anche tra moduli

**Pro.**
- Si naviga: `practitioner.getUser().getEmail()`.
- Le query JPQL possono fare join tra entità di moduli diversi.

**Contro.**
- `AppUser` deve essere **pubblica**, e così ogni entità referenziata da un altro modulo. Il modulo `practitioner` ora dipende dall'interno di `auth`, non dal suo servizio: D-002 resta scritta, ma il compilatore non la difende più.
- Il caricamento *lazy* attraversa i confini: chi riceve un `Practitioner` può toccare `getUser()` fuori dalla transazione e prendersi una `LazyInitializationException` (D-019), o generare query senza accorgersene.
- Un modulo può **modificare** l'entità di un altro (`practitioner.getUser().deactivate()`), saltando le regole del servizio di `auth`.

### 2.3 Strada B — Tra moduli solo id, dentro un modulo anche oggetti

Tra moduli diversi si tiene solo l'**id** (`Long userId`, `Long practitionerId`). Dentro lo stesso modulo si usano liberamente le relazioni JPA: nel pezzo 3 `PackageOffer` avrà un `@ManyToOne` verso `Service`, perché sono entrambe di `catalog`.

**Pro.**
- Le entità possono restare **non pubbliche**: il confine del modulo lo controlla il compilatore.
- Nessun caricamento *lazy* che attraversa un modulo: per sapere qualcosa di un altro modulo si chiama il suo **servizio**, che restituisce un `record`.
- Ogni modulo cambia i propri dati solo tramite le proprie regole.
- Se un giorno un modulo diventasse un'applicazione separata (D-002, "i confini sono già tracciati"), gli id restano validi, gli oggetti no.

**Contro.**
- Niente navigazione: per il nome del professionista di un servizio serve una chiamata in più a `PractitionerService`.
- Niente join JPQL tra moduli: se serve unire dati, lo fa un servizio, oppure una query di sola lettura costruita apposta (da decidere caso per caso).

È lo stesso principio degli **aggregati** del *Domain-Driven Design*: dentro un aggregato si usano gli oggetti, tra aggregati diversi gli id. Spring Modulith, il progetto Spring dedicato ai monoliti modulari, consiglia la stessa cosa.

### 2.4 Cosa cambia per D-025

D-025 dice, tra le conseguenze, che le entità di `catalog` avranno "due `@ManyToOne` separati" (professionista e servizio). Con la strada B diventano un `Long practitionerId` (altro modulo) e un `@ManyToOne Service` (stesso modulo). La foreign key composta resta identica nel database: è proprio lei a garantire la coerenza tra i due, qualunque cosa sappia Java.

### 2.5 La decisione

> **Decisa il 2026-10-29: strada B, e una sola `NotFoundException`** (D-026).

È tua. Il mio consiglio è **B**: è coerente con D-002 così com'è scritta ("nessun modulo legge direttamente le tabelle di un altro"), e la rende verificabile dal compilatore. Quando hai scelto, scriviamo la scheda (D-026) e, se scegli B, aggiorniamo la conseguenza di D-025.

### 2.6 Una scelta più piccola: dove vive `NotFoundException`

Il servizio deve dire "questo professionista non esiste" in un modo che, nel pezzo 5, diventi un 404. Due possibilità:
- un'eccezione **per modulo** (`PractitionerNotFoundException`, `ServiceNotFoundException`, ...): più precise, ma il `@RestControllerAdvice` le deve conoscere tutte;
- **una sola** `NotFoundException` in un package condiviso (es. `com.posturestudio.booking.shared`), con un messaggio che dice cosa manca.

Consiglio la seconda: per chi chiama l'API non c'è differenza tra "servizio non trovato" e "pacchetto non trovato" (entrambi 404), e per la regola di proprietà (pezzo 4) "non è tuo" deve sembrare proprio "non esiste".

In entrambi i casi l'eccezione estende `RuntimeException`, per due motivi:
- con un'eccezione controllata `@Transactional` farebbe **`commit`** invece di `rollback` (guida 06, 6.3);
- non costringe ogni metodo a dichiarare `throws`.

---

## Parte 3 — Dove sta ogni controllo

Prendiamo "il preavviso deve essere maggiore di zero". Lo stesso controllo compare in **tre posti**. Non è una ripetizione, perché ognuno ha un compito diverso:

| Dove | Quando arriva | Compito | Pezzo |
|---|---|---|---|
| **Bean Validation** sul DTO della richiesta (`@Positive`) | Prima di tutto, nel controller | Rispondere all'utente con un 400 e **il campo** sbagliato, in un messaggio leggibile | 4 |
| **Entità** (`Practitioner.changeSettings`) | Ogni volta che il codice Java cambia le impostazioni, da qualunque strada | Proteggere lo **stato** dell'oggetto: un `Practitioner` con preavviso 0 non deve esistere nemmeno in memoria | **questo** |
| **Database** (`practitioner_cancellation_notice_hours_ck`, V1) | Al momento della scrittura | Ultima difesa: bug, script, `insert` a mano (PD-17) | già fatto |

La regola per scegliere dove mettere un controllo:
- **forma dell'input** (obbligatorio, positivo, lunghezza): validazione sul DTO;
- **regole che riguardano un solo oggetto** (le sue impostazioni sono valide): l'entità;
- **regole che coinvolgono altre cose** (il servizio è attivo? è di questo professionista?): il servizio;
- **ciò che è vero per ogni riga, sempre**: il database.

Nell'entità, un valore non valido è un **errore di programmazione** (la validazione avrebbe dovuto fermarlo prima): `IllegalArgumentException`, come fa già `Email`. Non è una `NotFoundException` e non va mostrato all'utente così com'è.

**Limiti superiori?** Il brief non ne chiede. Un preavviso di 10.000 ore non ha senso, ma non è "sbagliato" nei dati. Lo scatto ha già un punto aperto (requisiti, punto 3, prima del passo 6), e lì si deciderà se deve dividere l'ora. Per ora: solo `> 0`.

---

## Parte 4 — Transazioni e dirty checking nel nostro caso

### 4.1 Lettura

```
getSettings(id)    @Transactional(readOnly = true)
  findById(id)  →  select ... from practitioner where id = ?
  se manca       →  NotFoundException
  entità → record PractitionerSettings
fine: nessun flush (readOnly), la connessione torna nel pool
```

Il `record` si costruisce **dentro** il metodo: con `open-in-view=false` (D-019), dopo il `return` l'entità è *detached* e il contesto di persistenza è chiuso. Restituire l'entità funzionerebbe ancora oggi (non ha relazioni *lazy*), ma sarebbe una trappola per domani. In più esporrebbe al chiamante i metodi che la modificano.

`readOnly = true` dichiara l'intenzione e risparmia lavoro: Hibernate non conserva la copia di confronto delle entità caricate e non fa il flush. Una modifica fatta per sbaglio dentro un metodo `readOnly` non viene scritta. Dettaglio da ricordare: nel test (parte 6) il metodo del servizio partecipa alla transazione **del test**, che non è `readOnly`. Questo comportamento quindi lì non si vede.

### 4.2 Modifica, senza `save`

```
updateSettings(id, settings)    @Transactional
  findById(id)  →  select ...        l'entità ora è MANAGED
                                     Hibernate tiene una copia dei valori letti
  practitioner.changeSettings(...)   cambia i campi in memoria, controlla i valori
fine metodo → commit:
  flush: Hibernate confronta i campi con la copia,
         trova le differenze → update practitioner set ... where id = ?
  commit
```

Nessuna chiamata a `save`. Un'entità caricata nella transazione è **seguita** dal contesto di persistenza: al flush, Hibernate scrive da solo ciò che è cambiato. Questo è il *dirty checking* (guida 06, 7.3).

Chiamare `save` lo stesso non fa danni (su un'entità *managed* `merge` non fa nulla di utile), ma comunica una cosa falsa: che senza `save` la modifica andrebbe persa. In `AppUserRepositoryTest` (passo 4) c'è il caso opposto: `deactivateAndActivateMethodsChangeActiveStatus` chiama `save` su un oggetto **detached** (dopo `clear()`), dove `save` serve davvero. Saper dire perché lì serve e qui no è una tipica domanda da colloquio.

### 4.3 Quando il dirty checking **non** funziona

- **Fuori dalla transazione.** Se il controller ricevesse l'entità e la modificasse dopo il `return` del servizio, l'entità sarebbe *detached*: nessuno la segue, la modifica si perde in silenzio. Anche per questo il servizio restituisce `record`.
- **Auto-invocazione.** Se un metodo di `PractitionerService` senza `@Transactional` chiama `this.updateSettings(...)`, la chiamata **non passa dal proxy** e la transazione non si apre (guida 06, 3.4). Dentro la chiamata le entità sono quindi *detached*, e le modifiche non vengono scritte.
- **Eccezione.** Se `changeSettings` lancia `IllegalArgumentException` (una `RuntimeException`), il proxy fa `rollback`: nessun `update`, anche se un campo fosse già cambiato in memoria.

### 4.4 Un dettaglio dell'`update`

Di default Hibernate scrive **tutte** le colonne aggiornabili, non solo quelle cambiate: `update practitioner set cancellation_notice_hours=?, first_name=?, ... where id=?`. Sono escluse quelle con `updatable = false` (`user_id`, `created_at`). È voluto: la stessa istruzione SQL si riusa per ogni modifica. Esiste `@DynamicUpdate` per scrivere solo le colonne cambiate, ma qui non serve.

---

## Parte 5 — La sintassi, su un esempio diverso

Una biblioteca con più **sedi**. Ogni sede ha le sue regole di prestito: giorni di prestito, prestiti massimi per socio, giorni di preavviso del promemoria. Il modulo `branch` è costruito come quello che scriverai, con la strada B della parte 2.

```java
// branch/LibraryBranch.java — package-private: nessuno fuori dal modulo la vede
@Entity
@Table(name = "library_branch")
class LibraryBranch {

    protected LibraryBranch() {
        // JPA needs a no-arg constructor
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // another module (city): only the id, never the entity
    @Column(updatable = false)
    private Long cityId;

    @Column
    private String name;

    @Column
    private int loanDays;

    @Column
    private int maxLoans;

    @Column
    private int reminderDays;

    @Column(updatable = false)
    @Generated
    private Instant createdAt;

    // the entity protects its own state: invalid values never get in, whoever calls
    void changeLoanRules(LoanRules rules) {
        this.loanDays = requirePositive(rules.loanDays(), "loanDays");
        this.maxLoans = requirePositive(rules.maxLoans(), "maxLoans");
        this.reminderDays = requirePositive(rules.reminderDays(), "reminderDays");
    }

    LoanRules loanRules() {
        return new LoanRules(loanDays, maxLoans, reminderDays);
    }

    private static int requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive, was " + value);
        }
        return value;
    }
}
```

```java
// branch/LoanRules.java — public: leaves the module
public record LoanRules(int loanDays, int maxLoans, int reminderDays) {
}
```

```java
// branch/LibraryBranchRepository.java — package-private
interface LibraryBranchRepository extends JpaRepository<LibraryBranch, Long> {
}
```

```java
// branch/LibraryBranchService.java — the only door of the module
@Service
public class LibraryBranchService {

    private final LibraryBranchRepository repository;

    LibraryBranchService(LibraryBranchRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public LoanRules getLoanRules(long branchId) {
        return find(branchId).loanRules();
    }

    @Transactional
    public LoanRules updateLoanRules(long branchId, LoanRules rules) {
        LibraryBranch branch = find(branchId);
        branch.changeLoanRules(rules);
        // no save(): the entity is managed, the update is written at commit (dirty checking)
        return branch.loanRules();
    }

    private LibraryBranch find(long branchId) {
        return repository.findById(branchId)
                .orElseThrow(() -> new NotFoundException("Library branch " + branchId + " not found"));
    }
}
```

Da notare:
- **Il costruttore del servizio non è pubblico e non ha `@Autowired`.** Con un solo costruttore Spring lo usa da solo per l'iniezione (guida 06, 2.3). Il campo è `final`: il servizio non può esistere senza il repository.
- **`LibraryBranchService` è pubblica, i suoi metodi `public` sono la porta del modulo.** `find` è `private`: un dettaglio interno.
- **`@Transactional` sui metodi pubblici**, che vengono chiamati da fuori e quindi passano dal proxy. `find` non ce l'ha: gira dentro la transazione del metodo che lo chiama.
- **`changeLoanRules` e `loanRules` sono package-private**: solo il servizio del modulo li chiama.
- **Si importa `org.springframework.transaction.annotation.Transactional`.** Ne esiste anche una `jakarta.transaction.Transactional` che funziona in modo simile ma ha opzioni diverse (non ha `readOnly`). Stesso tipo di trappola di `@Generated` (PD-18): controlla il package dell'import.
- **Un `record` come parametro e come risultato**: niente setter, niente entità che esce, ed è facile da costruire nei test.

### Cosa deve contenere il nostro modulo

- [ ] `Practitioner`: entità su `practitioner`, id `IDENTITY`, `userId` secondo la scelta della parte 2, nome e cognome, le tre impostazioni, `createdAt` con `@Generated` + `@Column(updatable = false)`
- [ ] Solo il costruttore `protected` (nessuno crea professionisti da Java, per ora)
- [ ] Un metodo che cambia le tre impostazioni insieme e rifiuta i valori ≤ 0 con `IllegalArgumentException`
- [ ] `PractitionerSettings`: `record` pubblico con i tre valori
- [ ] `PractitionerRepository`: interfaccia package-private
- [ ] `PractitionerService`: pubblica, `getSettings` con `readOnly = true`, `updateSettings` con `@Transactional`, **senza `save`**
- [ ] `NotFoundException` (`RuntimeException`) nel package scelto (2.6)
- [ ] Aggiornare `practitioner/package-info.java`: togliere il TODO del passo 5

---

## Parte 6 — I test

### 6.1 Prima: un solo container per le classi di test

Oggi `AppUserRepositoryTest` e `CatalogSchemaTest` dichiarano ognuna il proprio campo `@Container`: PostgreSQL parte una volta per classe. Con una terza classe le copie diventano tre. La soluzione è quella che Initializr aveva generato al passo 4 (e che avevamo lasciato da parte apposta): il container diventa un **bean** di una configurazione di test.

```java
// src/test/java/com/posturestudio/booking/TestcontainersConfiguration.java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17");
    }
}
```

Ogni classe di test la importa con `@Import(TestcontainersConfiguration.class)` e perde `@Testcontainers` e il campo `@Container`. Come bean, il container lo avvia e lo ferma **Spring**, insieme al contesto.

Quanti container partono, allora? Tanti quanti i **contesti Spring diversi**. Spring tiene in memoria i contesti dei test (la *context cache*) e riusa lo stesso contesto per le classi con la stessa configurazione. `AppUserRepositoryTest` e `CatalogSchemaTest`, con le stesse annotazioni, dovrebbero condividere contesto e container. Il test del servizio importa in più `PractitionerService` (6.2), quindi ha un contesto suo e un container suo: due in tutto, invece di tre. Lo verifichi contando nel log le righe di Testcontainers che creano il container `postgres:17`.

### 6.2 `@DataJpaTest` non vede i servizi

`@DataJpaTest` avvia solo la parte JPA: entità, repository, `DataSource`, Flyway. I `@Service` **non** ci sono (PD-20: ogni annotazione accende solo qualcosa). Per provare `PractitionerService` con un database vero, lo si aggiunge a mano: `@Import(PractitionerService.class)`. L'alternativa, `@SpringBootTest`, avvierebbe l'applicazione intera: più lenta, e non serve finché non ci sono API.

### 6.3 Il dirty checking si vede solo con il flush

`@DataJpaTest` apre una transazione per ogni test e la annulla alla fine. `updateSettings` ha `@Transactional` con propagazione `REQUIRED`: **partecipa** alla transazione del test, invece di aprirne una sua. Quindi alla fine del metodo **non** c'è il commit, e nemmeno il flush.

Per vedere l'`update` serve `entityManager.flush()` nel test: fa quello che farebbe il commit. Dopo, una lettura con `JdbcTemplate` (stessa transazione, stessa connessione) trova i valori nuovi nella **riga**, non nell'oggetto.

**Esperimento.** Togli il `flush()` e rilancia: `JdbcTemplate` legge i valori **vecchi**. Hibernate non ha ancora mandato l'`update`, e una query JDBC diretta non fa scattare il flush automatico (che parte solo prima delle query di Hibernate). Con `spring.jpa.show-sql=true` nelle proprietà del test vedi nel log l'`update` comparire solo dopo il flush, **senza** nessun `save` nel codice.

### 6.4 I casi

**Unitari, senza Spring** (come `EmailTest`): l'entità da sola. Il costruttore `protected` è accessibile da una classe dello stesso package.

| # | Caso | Atteso |
|---|---|---|
| 1 | Cambio con tre valori validi | i tre valori letti dall'entità sono quelli nuovi |
| 2 | Preavviso 0 | `IllegalArgumentException`, il messaggio nomina il campo |
| 3 | Validità negativa | `IllegalArgumentException` |
| 4 | Scatto 0 | `IllegalArgumentException` |

**Di integrazione** (`@DataJpaTest` + `@Import` della configurazione e del servizio), con il professionista inserito via SQL:

| # | Caso | Atteso |
|---|---|---|
| 5 | `getSettings` di un professionista appena inserito | 24, 90, 30: i **default** del database (V1), non valori scritti da Java |
| 6 | `updateSettings`, poi `flush`, poi lettura con SQL | la riga ha i valori nuovi, senza nessun `save` (6.3) |
| 7 | `getSettings` di un id inesistente | `NotFoundException` |
| 8 | `updateSettings` di un id inesistente | `NotFoundException` |
| 9 | Due professionisti: aggiorno A | la riga di B è invariata (l'`update` ha il `where id = ?` giusto) |

Il caso 6 è il test del *dirty checking* rimasto dal passo 4.

---

## Domande da colloquio

### Domanda 1: Hai modificato un'entità dentro un metodo `@Transactional` senza chiamare `save`. La modifica viene salvata? Perché?
**Risposta modello.** Sì. L'entità caricata nella transazione è *managed*: il contesto di persistenza ne conserva una copia dei valori letti. Al flush, che avviene prima del commit, Hibernate confronta i campi con la copia (*dirty checking*) e genera l'`update` per ciò che è cambiato. `save` serve per le entità nuove (`persist`) o per quelle *detached* (`merge`), per esempio un oggetto ricevuto da fuori o rimasto dopo un `clear()`. Non funziona fuori dalla transazione, con l'auto-invocazione (la chiamata non passa dal proxy) o se il metodo lancia una `RuntimeException`, che porta al rollback.

### Domanda 2: In un test `@DataJpaTest` chiami un metodo del servizio che modifica un'entità, poi leggi la riga con `JdbcTemplate` e trovi il valore vecchio. Perché?
**Risposta modello.** Il test ha una sua transazione, annullata alla fine. Il servizio, con propagazione `REQUIRED`, partecipa a quella invece di aprirne una nuova: il commit, e quindi il flush, non avviene alla fine del metodo. L'`update` non è ancora partito. Una query JDBC diretta non fa scattare il flush automatico di Hibernate, che avviene solo prima delle sue query. Serve `entityManager.flush()` prima della lettura.

### Domanda 3: Perché il servizio restituisce un `record` e non l'entità?
**Risposta modello.** Con `open-in-view=false` il contesto di persistenza si chiude con la transazione. Un'entità restituita è *detached*: toccare una relazione *lazy* dà `LazyInitializationException`, e una modifica non verrebbe più salvata, in silenzio. Il `record` è immutabile e contiene solo i dati che servono. Non espone i metodi che cambiano lo stato e non lega chi chiama allo schema del database. È anche ciò che il controller trasformerà in JSON, senza rischio di serializzare relazioni o campi interni.

### Domanda 4: In un monolite modulare, come impedisci che un modulo usi il repository di un altro?
**Risposta modello.** Con la visibilità di package. Repository ed entità senza `public`: Spring Data crea comunque il repository, ma il compilatore impedisce a classi di altri package di usarlo. L'unica classe pubblica è il servizio, che restituisce `record`. Tra moduli ci si riferisce per id e non per entità: così nessun modulo può caricare o modificare le entità di un altro passando per una relazione JPA. Strumenti come Spring Modulith o ArchUnit verificano queste regole anche con test automatici.

### Domanda 5: Dove metti il controllo "il preavviso deve essere positivo"? Una volta sola o più volte?
**Risposta modello.** In tre posti, con tre compiti diversi. Bean Validation sul DTO della richiesta, per rispondere 400 con il campo sbagliato. L'entità, per non avere mai in memoria un oggetto in uno stato invalido, da qualunque strada arrivi la modifica. Il `check` nel database, come ultima difesa contro bug e scritture esterne. Non è duplicazione: ognuno protegge da un rischio diverso.

### Domanda 6: Perché `readOnly = true` sulle letture?
**Risposta modello.** Dichiara l'intenzione e alleggerisce Hibernate: non conserva le copie di confronto per il *dirty checking* e non fa flush, quindi una modifica accidentale non viene scritta. Il driver riceve anche l'indicazione di connessione in sola lettura. Se un metodo `readOnly` partecipa a una transazione già aperta e scrivibile (propagazione `REQUIRED`), conta la transazione esterna.

---

## Documentazione ufficiale

- Spring Framework, gestione dichiarativa delle transazioni (`@Transactional`, rollback, propagazione): https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html
- Spring Data JPA, entità e `save` (`persist` contro `merge`): https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html
- Hibernate ORM 7, contesto di persistenza e flush: https://docs.jboss.org/hibernate/orm/7.0/userguide/html_single/Hibernate_User_Guide.html#flushing
- Spring Boot, Testcontainers e `@ServiceConnection` (container come bean): https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
- Spring Framework, la cache dei contesti nei test: https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html
- Spring Modulith, i moduli applicativi e i riferimenti tra aggregati: https://docs.spring.io/spring-modulith/reference/fundamentals.html
