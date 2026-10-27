# L'entità `AppUser`, il repository e il test con Testcontainers — guida di studio

Questa guida accompagna gli ultimi due pezzi del passo 4: il punto 6 del brief (la prima entità JPA e il suo repository) e il punto 7 (il test di integrazione con Testcontainers).

Contiene:
- **la parte 1**: l'entità JPA, dal mapping alle colonne riempite dal database, e perché un'entità non può essere un `record`;
- **la parte 2**: il value object `Email` (D-020), l'occasione di Java moderno del passo;
- **la parte 3**: il repository di Spring Data JPA;
- **la parte 4**: il test di integrazione, con `@DataJpaTest`, Testcontainers e `@ServiceConnection`;
- **la parte 5**: la sintassi su un esempio **diverso** (la biblioteca) e la checklist. Il codice lo scrivi tu;
- **la parte 6**: come verificare.

**Tutto quello che questa guida dà per vero è stato verificato** il 2026-10-19 su una copia del progetto, con le nostre versioni (Spring Boot 4.1.1, Hibernate 7.4, Testcontainers 2, PostgreSQL 17). Dove un comportamento non è stato provato, è detto.

---

## Indice

1. [L'entità JPA](#parte-1--lentità-jpa)
2. [Il value object `Email`](#parte-2--il-value-object-email)
3. [Il repository](#parte-3--il-repository)
4. [Il test di integrazione](#parte-4--il-test-di-integrazione)
5. [La sintassi, su un esempio diverso](#parte-5--la-sintassi-su-un-esempio-diverso)
6. [Verifica](#parte-6--verifica)
7. [Domande da colloquio](#domande-da-colloquio)
8. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — L'entità JPA

### 1.1 Cos'è un'entità

Un'**entità** è una classe Java che rappresenta una riga di una tabella. Ma non è solo un contenitore di dati: è un oggetto **gestito** da Hibernate.

Quando carichi un'entità dentro una transazione, Hibernate la tiene nel suo **contesto di persistenza** (*persistence context*), una specie di registro degli oggetti che sta seguendo. Se ne modifichi un campo, alla fine della transazione Hibernate se ne accorge da solo e scrive l'`update`: non c'è bisogno di chiamare `save`. Si chiama ***dirty checking***, e sorprende spesso chi viene da JDBC.

Gli stati di vita di un'entità:

| Stato | Significato |
|---|---|
| ***transient*** | Creata con `new`, Hibernate non la conosce ancora |
| ***managed*** | Salvata o caricata dentro una transazione: Hibernate la segue e salva le modifiche |
| ***detached*** | La transazione è finita: l'oggetto esiste ancora, ma Hibernate non lo segue più |
| ***removed*** | Segnata per la cancellazione |

### 1.2 Le annotazioni di base

| Annotazione | Cosa dice | Nel nostro caso |
|---|---|---|
| `@Entity` | Questa classe è un'entità | Su `AppUser` |
| `@Table(name = "...")` | Il nome della tabella | `app_user`: senza, Hibernate userebbe un nome derivato dalla classe |
| `@Id` | La chiave primaria | `id` |
| `@GeneratedValue(strategy = GenerationType.IDENTITY)` | L'id lo genera il database, con una colonna *identity* | Corrisponde a `generated always as identity` di V1 |
| `@Column(...)` | Dettagli sulla colonna: nome, `nullable`, `insertable`, `updatable` | Dove serve |
| `@Enumerated(EnumType.STRING)` | Salva l'enum come **testo** (`'CUSTOMER'`) | Il ruolo |

**I nomi delle colonne.** Spring Boot configura Hibernate per convertire i nomi Java in *snake_case*: il campo `createdAt` diventa la colonna `created_at` (verificato). Scrivere `@Column(name = "created_at")` non è necessario, ma rende esplicito il collegamento.

**`IDENTITY` e una conseguenza da sapere.** Con `IDENTITY` l'id lo conosce solo il database, e solo dopo l'`insert`. Quindi Hibernate esegue l'`insert` **subito**, nel momento del `save`, invece di aspettare la fine della transazione. Verificato: un vincolo violato fa fallire **la chiamata a `save` stessa**. Ci servirà nei test (parte 4).

**`EnumType.STRING`, mai `ORDINAL`.** Il default di JPA è `ORDINAL`: salva la **posizione** dell'enum (0, 1, 2). Se un giorno qualcuno aggiunge un valore in mezzo all'enum, o ne cambia l'ordine, tutti i ruoli salvati cambiano significato senza che nessuno se ne accorga. Con `STRING` si salva il nome, che è anche quello che il `check` di V1 si aspetta.

**`@Column(nullable = false)` non crea vincoli.** Con `ddl-auto=validate` lo schema lo crea Flyway: `nullable` è **documentazione** per chi legge l'entità. Il vincolo vero è il `not null` di V1.

### 1.3 Le colonne che riempie il database: `created_at`

`created_at` ha `default now()` in V1: la deve riempire il **database**. Ma se Hibernate scrivesse nell'`insert` anche la colonna `created_at`, con il valore `null` del campo Java, il default non scatterebbe (guida 04, 2.7: il default vale solo per le colonne non nominate), e il `not null` rifiuterebbe la riga.

Bisogna quindi dire a Hibernate due cose: **non nominare** questa colonna negli `insert` e negli `update`, e **rileggerla** dopo l'`insert`, per avere il valore nel campo Java.

Basta **una sola annotazione** di Hibernate, `@Generated` (`org.hibernate.annotations.Generated`), che fa entrambe le cose. Verificato: con la sola `@Generated` l'`insert` diventa `insert into app_user (active,email,phone,role) values (...) returning id,created_at`. `created_at` non è tra le colonne scritte, ed è nel `returning`, così il valore torna nel campo Java nella stessa istruzione.

Aggiungere `@Column(insertable = false, updatable = false)` non è un errore, ma è **ridondante**: lo si vede nei tutorial, e rende esplicito il "non scrivere". *(Corretto il 2026-10-22 dopo una verifica sul nostro stack.)*

Le alternative, per sapere che esistono:

| Modo | Chi decide l'ora | Nota |
|---|---|---|
| `@Generated` | Il **database** | La nostra scelta: una sola fonte dell'ora, quella del database |
| `@CreationTimestamp` di Hibernate | La **JVM**, al momento del `save` | Il default del database non serve più. Se l'orologio del server Java e quello del database divergono, le ore non sono confrontabili |
| Solo `insertable = false` | Il database | Funziona, ma il campo Java resta `null` finché non ricarichi l'entità |

Tipo Java per `timestamptz`: **`Instant`**, un istante preciso, senza fuso. Verificato: `validate` lo accetta.

### 1.4 `active`: il default in Java e nel database

Per `active` la situazione è più semplice: inizializza il campo in Java (`= true`). Hibernate lo scriverà nell'`insert`, e il `default true` di V1 resta come rete di sicurezza per chi inserisce senza passare da Java (per esempio da `psql`). Verificato.

### 1.5 I costruttori, e perché il campo non può essere `final`

JPA ha delle regole che sorprendono chi conosce bene Java:
- serve un **costruttore senza argomenti**. Hibernate crea l'oggetto vuoto e poi riempie i campi. Può essere `protected`: così il resto del codice non lo usa per sbaglio;
- la classe **non** deve essere `final`, e nemmeno i metodi: per il caricamento pigro (*lazy loading*) Hibernate crea a volte delle **sottoclassi** dell'entità (*proxy*);
- i campi **non** possono essere `final`: Hibernate li riempie dopo la costruzione, e li aggiorna quando l'entità cambia.

Per il resto del codice offri **un costruttore con i dati obbligatori** (ruolo ed email), e **niente setter** per i campi che non devono cambiare (`id`, `createdAt`). Se un campo deve cambiare, meglio un metodo che dica **perché** (`deactivate()`) di un generico `setActive(false)`. L'entità diventa più difficile da usare male.

### 1.6 Perché un'entità non può essere un `record`

È una domanda classica da colloquio, e la risposta viene dritta dalla 1.5. Un `record`:

| Caratteristica del `record` | Perché è incompatibile con un'entità |
|---|---|
| È `final` | Hibernate non può crearne sottoclassi (*proxy*) |
| Ha tutti i campi `final` | Hibernate non può riempirli dopo la costruzione, né aggiornarli |
| Non ha un costruttore senza argomenti | Hibernate non sa come crearlo vuoto |
| `equals` e `hashCode` confrontano **tutti** i campi | Un'entità è "la stessa" se ha lo stesso id, anche quando i dati cambiano |
| È pensato per valori **immutabili** | Un'entità ha un ciclo di vita e cambia nel tempo (1.1) |

In breve: un `record` è un **valore**, un'entità è un **oggetto con identità** che cambia nel tempo.

**Dove invece un `record` va benissimo:**
- gli **oggetti di scambio delle API** (DTO): l'architettura lo prevede già;
- le **proiezioni** di Spring Data (una query che restituisce solo alcune colonne);
- i **value object** come `Email` (parte 2);
- da Hibernate 6.2, anche come `@Embeddable`, cioè un gruppo di colonne dentro un'entità (es. un indirizzo).

**`record` non vuol dire DTO.** `record` è uno **strumento del linguaggio** (una classe immutabile compatta); DTO è un **ruolo** (un oggetto che trasporta dati attraverso un confine). Un DTO si può scrivere come `record`, ed è il modo moderno; ma un `record` può fare anche altri lavori:

| Ruolo | A cosa serve | Esempio nel progetto | Valida i dati? |
|---|---|---|---|
| **DTO** | Trasportare dati dentro e fuori dalle API | La richiesta "prenota questo orario", la risposta "le mie sedute" | Non da solo: lo fa Spring con Bean Validation (`@NotBlank`, `@Email`) all'ingresso del controller |
| **Proiezione** | Ricevere da una query solo alcune colonne | "Data e nome del cliente delle prenotazioni di oggi" | No: dati letti dal database |
| **Value object** | Un **valore** del dominio con le sue regole | `Email` | **Sì**: il *compact constructor* garantisce che sia sempre valido |

Un DTO è una **busta** (porta quello che arriva, anche dati sbagliati, finché qualcuno non li controlla); un value object è una **garanzia** (se esiste, è valido). Esempio sulla biblioteca:

```java
// DTO: carries what the client sends; validation is declared, Spring performs it
record RegisterMemberRequest(@NotBlank String cardCode, @NotBlank String fullName) {}

// Value object: validates itself, always
record CardCode(String value) {
    CardCode {
        if (value == null || !value.matches("[A-Z0-9]{8}")) {
            throw new IllegalArgumentException("Invalid card code");
        }
    }
}
```

### 1.7 Perché le entità non escono dalle API

L'architettura dice: le API ricevono e restituiscono `record` dedicati, mai entità. Quattro motivi:
1. **Sicurezza**: un'entità ricevuta da una richiesta permetterebbe al client di scrivere campi che non dovrebbe toccare, come il ruolo (*mass assignment*);
2. **Separazione**: lo schema del database e il contratto delle API cambiano per ragioni diverse, e non devono trascinarsi a vicenda;
3. **Dati nascosti**: un'entità contiene campi che il client non deve vedere;
4. **Caricamento pigro**: convertire in JSON un'entità con relazioni *lazy* lancia query nascoste, o una `LazyInitializationException` con `open-in-view=false` (D-019).

### 1.8 Il caricamento pigro (*lazy loading*)

`AppUser` oggi non ha relazioni, ma dal passo 7 le entità saranno collegate (una prenotazione ha cliente, professionista, servizio). Se caricando una riga Hibernate caricasse subito tutto ciò a cui è collegata, e così via, una riga trascinerebbe mezzo database. Il caricamento pigro carica **solo quello che chiedi**, e il resto **quando e se** lo usi.

**Il *proxy*.** Al posto del vero `Customer`, nel campo `customer` di una prenotazione Hibernate mette un **sostituto**: una sottoclasse di `Customer` che conosce **solo l'id** (sta già nella riga: `customer_id`).

```
booking.getCustomer()                → il proxy: nessuna query
booking.getCustomer().getId()        → l'id lo conosce già: nessuna query (solo se getId() è PUBBLICO, vedi sotto)
booking.getCustomer().getLastName()  → ORA il proxy esegue la query e si "riempie"
```

È il motivo per cui un'entità non può essere `final` (1.5).

**Attenzione: `getId()` deve essere pubblico.** Verificato il 2026-10-22 su Hibernate 7.4, con un proxy letto **fuori** dalla transazione:

| `getId()` dell'entità | Risultato sul proxy |
|---|---|
| `public` (anche se la classe non è pubblica) | Restituisce l'id **senza** query e senza errori |
| Non pubblico | Il proxy prova a riempirsi: `LazyInitializationException` |

Quindi: l'entità può restare non pubblica per i confini del modulo (3.3), ma il suo `getId()` va dichiarato `public`. *(Corretto il 2026-10-22 dopo una verifica sul nostro stack.)*

**Funziona solo con la sessione aperta.** Per riempirsi, il proxy ha bisogno del contesto di persistenza, cioè della transazione in corso. A transazione chiusa lancia **`LazyInitializationException`**:

```
@Transactional  ┌───────────── servizio ─────────────┐
                │ booking = repository.findById(..)   │  ← la prenotazione: 1 query
                │ booking.getCustomer().getLastName() │  ← il cliente: 2ª query, OK
                └─────────────────────────────────────┘
                  transazione chiusa: il proxy è "staccato"
  controller:     booking.getService().getName()        ← LazyInitializationException
```

*Open Session In View* teneva la sessione aperta per tutta la richiesta proprio per evitare questa eccezione, al prezzo di query nascoste. Noi l'abbiamo spento (D-019): ciò che serve si carica nel servizio.

**Il problema N+1.** 50 prenotazioni caricate con **1** query; poi, in un ciclo, il cognome di ogni cliente: ogni proxy fa la **sua** query, quindi **50** in più. Funziona, ma è lento e invisibile nel codice. Si risolve dicendo nella query cosa caricare: **`join fetch`** in JPQL, **`@EntityGraph`** su un metodo del repository, oppure una **proiezione** su un `record`.

**I default di JPA, e la trappola:**

| Relazione | Default | Cosa conviene |
|---|---|---|
| `@ManyToOne` | **EAGER** (subito) | Scrivere `fetch = FetchType.LAZY` |
| `@OneToOne` | **EAGER** | LAZY |
| `@OneToMany` | LAZY | Lasciarlo LAZY |
| `@ManyToMany` | LAZY | LAZY |

Le relazioni "verso uno" sono **subito** di default: una scelta storica della specifica, considerata un errore. Regola pratica: **tutto LAZY, e nella query carichi esplicitamente quello che serve.**

**In una frase:** il lazy loading carica le relazioni solo quando le usi, tramite un proxy che conosce solo l'id; funziona solo dentro la transazione; il rischio è N+1, che si risolve dicendo nella query cosa caricare.

---

## Parte 2 — Il value object `Email`

D-020 dice che la conversione in minuscolo avviene **in un posto solo**. Il posto giusto è un tipo: un `record` `Email`.

**Il *compact constructor*.** In un `record` si può scrivere un costruttore senza ripetere i parametri, che viene eseguito **prima** di assegnare i campi. Lì dentro puoi controllare e trasformare il valore ricevuto. Da Java 16 è il modo standard di garantire che un record sia sempre valido.

Cosa deve fare il costruttore di `Email`:
1. rifiutare `null` e le stringhe vuote;
2. togliere gli spazi all'inizio e alla fine;
3. convertire in minuscolo con `toLowerCase(Locale.ROOT)` (guida 04, 3.4: il problema della "i" turca);
4. rifiutare i valori che non sembrano un'email. Basta un controllo semplice: la validazione completa delle email è un problema notoriamente difficile, e per l'accesso con codice (D-014) la vera verifica è che il codice arrivi.

Da quel momento, dove il codice riceve un `Email` invece di una `String`, l'email **è** normalizzata: lo garantisce il tipo.

**Come collegarlo all'entità: due strade.** È una scelta tua.

| Strada | Come | Pro e contro |
|---|---|---|
| A | L'entità ha un campo `String`; il costruttore dell'entità riceve un `Email` e ne salva il valore | Semplice. L'entità accetta solo email normalizzate, ma il campo è una stringa qualsiasi |
| B | L'entità ha un campo di tipo `Email`, con un **`AttributeConverter<Email, String>`** che JPA usa per convertire da e verso la colonna | Il tipo arriva fino all'entità e al repository (`findByEmail(Email email)`): impossibile cercare con una stringa non normalizzata. Un concetto in più da imparare |

La strada B è più rigorosa, ed è un buon argomento da colloquio. Documentazione degli `AttributeConverter`: link in fondo.

---

## Parte 3 — Il repository

### 3.1 Spring Data JPA

Con Spring Data non scrivi la classe che accede al database: scrivi un'**interfaccia** che estende `JpaRepository<AppUser, Long>` (tipo dell'entità, tipo dell'id). All'avvio Spring genera l'implementazione. Ricevi già `save`, `findById`, `findAll`, `delete`, `count` e altri.

### 3.2 Le query derivate dal nome

Un metodo come `findByEmail` non va implementato: Spring **legge il nome**, capisce "cerca per la proprietà `email`" e scrive la query.

Conseguenza utile: il nome viene analizzato **all'avvio**. Se scrivi `findByEmial`, l'applicazione non parte, con un errore che dice che la proprietà `emial` non esiste. È un altro *fail fast*.

Restituisci un **`Optional<AppUser>`**: dice in modo esplicito che l'utente può non esserci, e obbliga chi chiama a gestire il caso. È esattamente ciò che servirà al login del passo 8.

Altre forme utili: `existsByEmail` (restituisce `boolean`), `countByRole`.

### 3.3 Il repository fa parte del modulo, e può restare non pubblico

D-002: un modulo non legge le tabelle di un altro, ma chiama il suo servizio. Con Java si può far rispettare dal compilatore (guida 01, 6.2): se il repository **non è `public`**, nessun altro package lo può usare. Verificato: Spring Data funziona anche con entità e repository non pubblici.

Una conseguenza per il test: deve stare nello **stesso package** (`com.posturestudio.booking.auth`, ma sotto `src/test/java`) per vedere il repository.

---

## Parte 4 — Il test di integrazione

### 4.1 Test unitario e test di integrazione

| | Test unitario | Test di integrazione |
|---|---|---|
| Cosa prova | Una classe da sola, con le dipendenze finte | Più pezzi veri insieme |
| Database | Nessuno | Un PostgreSQL **vero** |
| Velocità | Millisecondi | Secondi |
| Cosa scopre | Errori di logica | Errori di mapping, di SQL, di vincoli, di configurazione |

Un test sul repository **deve** essere di integrazione: quello che vogliamo provare (mapping, vincoli, migrazione) vive proprio nel collegamento con il database.

Perché non un database in memoria come H2? Perché H2 non è PostgreSQL: non ha lo stesso SQL, gli stessi tipi, gli stessi vincoli. Un test verde su H2 non dice niente su PostgreSQL. Per questo l'architettura ha scelto Testcontainers.

### 4.2 Testcontainers

Testcontainers avvia un **container Docker vero** durante i test e lo spegne alla fine. Un container di servizio chiamato Ryuk fa pulizia anche se i test si interrompono a metà. Ogni esecuzione parte da un database **vuoto**: Flyway applica V1, e il test lavora su uno schema identico a quello vero.

Le annotazioni:

| Annotazione | Cosa fa |
|---|---|
| `@Testcontainers` | Sulla classe di test: attiva la gestione dei container per JUnit 5 |
| `@Container` | Su un campo: questo è un container da avviare e fermare. Se il campo è **`static`**, un solo container per tutta la classe (più veloce); altrimenti uno per ogni test |
| `@ServiceConnection` | Spring Boot legge dal container indirizzo, utente e password, e li usa al posto delle proprietà `spring.datasource.*` (vedi 4.4) |

Una precisazione verificata il 2026-10-22: anche **senza** `@Testcontainers` il test funziona, perché Spring Boot avvia da solo un container dichiarato con `@ServiceConnection` quando gli servono i dati di connessione. Con `@Testcontainers` è invece JUnit a governare avvio e arresto per ogni classe di test: è il modo esplicito e più diffuso, e lo teniamo.

L'immagine va indicata con **lo stesso tag della produzione**: `postgres:17`, non `latest` (guida 02, domanda 1). Initializr genera `postgres:latest`: è il motivo per cui il suo file non è stato copiato così com'era.

Requisito: **Docker Desktop acceso** quando lanci i test.

### 4.3 `@DataJpaTest`

È una *test slice*: avvia **solo** la parte di Spring che riguarda JPA, non tutta l'applicazione. In Spring Boot 4 sta nel package `org.springframework.boot.data.jpa.test.autoconfigure` (nei tutorial vecchi trovi `org.springframework.boot.test.autoconfigure.orm.jpa`: in Boot 4 è cambiato).

Cosa include, verificato:
- la `DataSource`, Hibernate, i repository;
- **Flyway**: le migrazioni vengono applicate al database del test;
- un `TestEntityManager`, per preparare i dati del test.

Due comportamenti importanti:
1. **Ogni test è una transazione annullata alla fine** (`@DataJpaTest` è `@Transactional`). I test non si sporcano a vicenda: come il tuo `begin` … `rollback` in `psql`;
2. **Non sostituisce il database del container.** Per anni `@DataJpaTest` sostituiva in automatico la `DataSource` con un database in memoria. Oggi il comportamento di default (`Replace.NON_TEST`) la sostituisce solo se non è già una connessione "da test", come quella di `@ServiceConnection`. Verificato: il test usa PostgreSQL.

### 4.4 `@ServiceConnection` e il `.env`

Con `@ServiceConnection` le proprietà di connessione del test arrivano **dal container**, non da `application.properties` né dal `.env`. Verificato su una copia del progetto **senza** `.env`: il test passa lo stesso. È importante per il passo 14: GitHub Actions, che eseguirà i test, non avrà nessun `.env`.

### 4.5 Provare che un vincolo rifiuta

Verificato: con `IDENTITY`, `save` esegue l'`insert` subito (1.2), quindi è **la chiamata a `save`** a fallire. Spring traduce l'errore di PostgreSQL in un'eccezione sua, indipendente dal database: **`DataIntegrityViolationException`** (verifica 02, D2).

Con AssertJ (incluso negli starter di test) si scrive con `assertThatThrownBy(...)` oppure `catchThrowable(...)`, e poi si controlla il tipo dell'eccezione.

**Ma il tipo non basta.** Sia un'email duplicata (`unique`) sia un'email con maiuscole (`check`) producono `DataIntegrityViolationException`. Se il test controlla solo il tipo, potrebbe passare **per il motivo sbagliato**: è la lezione del caso 11 in `psql`. Controlla anche che il messaggio contenga il **nome del vincolo** atteso (`app_user_email_uk`). È il terzo motivo per cui i vincoli hanno un nome (guida 04, 2.8).

**Dopo un errore, la sessione e la transazione sono inutilizzabili.** Verificato: se dopo il `save` rifiutato il test chiama ancora il repository (per esempio `count()`), Hibernate prima di eseguire la query prova a scrivere le modifiche in sospeso, trova l'entità rimasta a metà, e si ferma con `org.hibernate.AssertionFailure: Entry for instance of '...AppUser' has a null identifier (this can happen if the session is flushed after an exception occurs)`. E anche lato PostgreSQL la transazione rifiuterebbe ogni altro comando fino al `rollback` (lo hai visto in `psql`). Regola: **dopo l'errore atteso non fare altre operazioni sul database** in quello stesso test. *(Corretto il 2026-10-22 dopo una verifica sul nostro stack.)*

### 4.6 Come provare il `check` sul minuscolo, se `Email` normalizza?

Se l'entità accetta solo `Email`, che converte sempre in minuscolo, **non riesci più** a mandare al database un'email con maiuscole passando da Java. È proprio lo scopo della parte 2. Ma allora come provi che il `check` di V1 funziona?

Passando **sotto** Java: in un test di integrazione puoi eseguire SQL diretto con `JdbcTemplate` (iniettabile nel test con `@Autowired`), come facevi in `psql`. È legittimo: stai provando il **database**, non Java.

### 4.7 Arrange, Act, Assert

Ogni test in tre tempi, come hai imparato con il caso 11:
1. **Preparo** (*arrange*): i dati necessari;
2. **Eseguo** (*act*): l'operazione da provare, una sola;
3. **Verifico** (*assert*): il risultato atteso.

Dai ai test nomi che dicano **cosa** provano: `duplicateEmailIsRejected` si legge meglio di `test2`. Quando un test fallisce, il nome è la prima cosa che leggi.

### 4.8 I casi da coprire

Dal brief: salvare e rileggere un utente; un'email duplicata rifiutata dal database. Casi aggiuntivi consigliati:

| # | Caso | Cosa verifica |
|---|---|---|
| 1 | Salvo un utente e lo rileggo con `findByEmail` | Mapping completo, query derivata |
| 2 | Dopo il salvataggio: id presente, `createdAt` valorizzato, `active` vero | Le colonne riempite dal database (1.3, 1.4) |
| 3 | Due utenti con la stessa email | `DataIntegrityViolationException`, con `app_user_email_uk` nel messaggio |
| 4 | Un'email con maiuscole inserita con SQL diretto | Il `check` di V1 (4.6), con `app_user_email_ck` |
| 5 | Il ruolo è salvato come testo (`'CUSTOMER'`, non `0`) | `EnumType.STRING` (letto con SQL diretto) |
| 6 | `findByEmail` con un'email che non esiste | `Optional` vuoto |

Se scegli il record `Email`, aggiungi i suoi **test unitari**: normalizzazione, spazi, rifiuto di `null` e di valori vuoti. Non serve un database: sono veloci, e sono il primo test unitario del progetto.

---

## Parte 5 — La sintassi, su un esempio diverso

La biblioteca della guida 04: un socio con un codice tessera e uno stato.

```java
// src/main/java/.../library/MemberStatus.java
enum MemberStatus { ACTIVE, SUSPENDED }

// src/main/java/.../library/LibraryMember.java
@Entity
@Table(name = "library_member")
class LibraryMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String cardCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status = MemberStatus.ACTIVE;

    // Filled by the database (default now()): never written by Hibernate, read back after insert.
    @Generated
    private Instant createdAt;

    protected LibraryMember() {
        // for JPA only
    }

    LibraryMember(String cardCode) {
        this.cardCode = cardCode;
    }

    void suspend() {
        this.status = MemberStatus.SUSPENDED;
    }

    // getters only for what the rest of the module needs
}

// src/main/java/.../library/LibraryMemberRepository.java
interface LibraryMemberRepository extends JpaRepository<LibraryMember, Long> {
    Optional<LibraryMember> findByCardCode(String cardCode);
}
```

E lo scheletro di un test:

```java
// src/test/java/.../library/LibraryMemberRepositoryTest.java
@DataJpaTest
@Testcontainers
class LibraryMemberRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    LibraryMemberRepository repository;

    @Test
    void savedMemberCanBeFoundByCardCode() {
        // arrange + act
        repository.save(new LibraryMember("ABC12345"));

        // assert
        assertThat(repository.findByCardCode("ABC12345")).isPresent();
    }
}
```

Note:
- gli `import` non sono riportati: i package delle annotazioni di test sono nella 4.3, e l'IDE li suggerisce. Attenzione a **`@Generated`**: va importata da `org.hibernate.annotations`, non da `jakarta.annotation` (esiste un'altra annotazione con lo stesso nome, che non c'entra niente). Se l'IDE importa quella sbagliata te ne accorgi subito, perché il codice non compila: `annotation @Generated is missing a default value for the element 'value'` (verificato);
- in Testcontainers 2 `PostgreSQLContainer` sta in `org.testcontainers.postgresql`. Nei tutorial vecchi trovi `org.testcontainers.containers.PostgreSQLContainer<?>`, con il parametro di tipo: è la versione 1.

### Cosa deve contenere il nostro codice

Nel modulo `auth` (`src/main/java/com/posturestudio/booking/auth/`):
- [ ] L'enum dei ruoli, con i tre valori di V1
- [ ] L'entità `AppUser` mappata su `app_user`: id `IDENTITY`, ruolo `STRING`, email, telefono facoltativo, `active` con default `true` in Java, `createdAt` riempito dal database (1.3)
- [ ] Costruttore senza argomenti `protected`; un costruttore con i dati obbligatori; nessun setter per `id` e `createdAt`
- [ ] `getId()` **pubblico**, anche se la classe non lo è (1.8)
- [ ] Il record `Email` con il *compact constructor*, e la scelta A o B della parte 2
- [ ] `AppUserRepository` con `findByEmail` che restituisce `Optional`
- [ ] Entità e repository non pubblici, se vuoi far rispettare D-002 dal compilatore (3.3)
- [ ] Il `// TODO` di `package-info.java` aggiornato o tolto

Nei test (`src/test/java/com/posturestudio/booking/auth/`):
- [ ] Il test del repository con `@DataJpaTest`, `@Testcontainers` e il container `postgres:17` con `@ServiceConnection`
- [ ] I casi della 4.8: almeno 1 e 3 (il brief), consigliati tutti
- [ ] I test unitari di `Email`, se lo crei
- [ ] Nomi dei test che dicono cosa provano

---

## Parte 6 — Verifica

1. Docker Desktop acceso. Da `backend/`:
   ```powershell
   .\mvnw.cmd test
   ```
   La prima volta scarica l'immagine di PostgreSQL: ci vuole un po' di più.
2. Nel log cerca, per il test del repository:
   - l'avvio del container (righe di Testcontainers con `postgres:17`);
   - `Successfully applied 1 migration`: Flyway ha creato lo schema nel container;
   - per i test che si aspettano un errore, un `WARN` con il vincolo violato (`app_user_email_uk`...): è normale, è il database che rifiuta come previsto.
3. Alla fine: `Tests run: N, Failures: 0, Errors: 0`, e `BUILD SUCCESS`.
4. **La prova del test che fallisce.** Un test che non hai mai visto fallire potrebbe non provare niente. V1 non si tocca più, quindi la prova si fa dal lato del test: cambia per un momento l'email del secondo utente, in modo che sia **diversa** dalla prima. Il test del duplicato deve diventare **rosso**. Poi rimettilo com'era. È lo stesso principio della riga `-- atteso` in `psql`.
5. Avvia anche l'applicazione (`spring-boot:run`): con l'entità presente, `validate` ora **controlla davvero** lo schema. Se parte, mapping e V1 sono coerenti.

### Errori tipici

| Sintomo | Causa probabile |
|---|---|
| `Could not find a valid Docker environment` | Docker Desktop spento |
| `Schema-validation: missing column [...]` | Un campo dell'entità non corrisponde a una colonna di V1 (nome o tipo) |
| `Schema-validation: wrong column type encountered` | Tipo Java incompatibile con la colonna (es. `LocalDateTime` invece di `Instant` per `timestamptz`) |
| `null value in column "created_at" ... violates not-null constraint` | Manca `@Generated` su `createdAt`: Hibernate scrive `null` e il default non scatta (1.3) |
| `annotation @Generated is missing a default value for the element 'value'` | È stata importata la `@Generated` sbagliata (`jakarta.annotation`): serve `org.hibernate.annotations.Generated` |
| `No property 'emial' found for type 'AppUser'` | Errore di battitura nel nome di un metodo del repository (3.2) |
| `AssertionFailure: ... has a null identifier (this can happen if the session is flushed after an exception occurs)` | Nel test c'è un'altra operazione sul database dopo l'errore atteso (4.5) |
| `No default constructor for entity` | Manca il costruttore senza argomenti (1.5) |

---

## Domande da colloquio

1. Cos'è il contesto di persistenza? Cosa succede se modifichi un'entità *managed* senza chiamare `save`?
2. Perché un'entità JPA non può essere un `record`? Dove invece useresti un `record`?
3. `EnumType.ORDINAL` o `STRING`? Perché?
4. Perché le entità non vanno restituite direttamente dalle API?
5. Come testi un repository? Perché non con un database in memoria?
6. Cos'è `@ServiceConnection` e cosa sostituisce? Cosa usavi prima di Spring Boot 3.1?
7. Con `GenerationType.IDENTITY`, quando viene eseguito l'`insert`? Che conseguenze ha?
8. Cos'è il lazy loading? Quando lancia `LazyInitializationException`? Cos'è il problema N+1 e come lo risolvi?
9. Che differenza c'è tra un DTO e un value object, se entrambi si possono scrivere come `record`?

---

## Documentazione ufficiale

- Jakarta Persistence 3.2 (la specifica JPA): https://jakarta.ee/specifications/persistence/3.2/
- Hibernate ORM, guida utente (mapping, `@Generated`, ciclo di vita): https://docs.jboss.org/hibernate/orm/7.0/userguide/html_single/Hibernate_User_Guide.html
- Spring Data JPA, repository e query derivate: https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html
- Spring Boot, test di JPA e test slice: https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html
- Spring Boot, Testcontainers e `@ServiceConnection`: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
- Testcontainers per Java: https://java.testcontainers.org/
- Java, i record (JEP 395): https://openjdk.org/jeps/395
- AssertJ, eccezioni: https://assertj.github.io/doc/#assertj-core-exception-assertions
