# Passo 04 — Fondamenta

> **Stato: completo** (2026-10-29). Restano le risposte di Nicholas alle domande da colloquio e la verifica di fine passo.

## Obiettivo
Creare l'impianto del backend: un progetto Spring Boot che si avvia, si collega a PostgreSQL in Docker e crea le prime tabelle (`app_user`, `practitioner`, `customer`) con Flyway. Nessuna funzionalità per lo studio ancora.

## Prerequisiti sul computer
- **JDK 21** installato e `JAVA_HOME` che punta lì (oggi sul PATH c'è solo Java 8). Consigliato Eclipse Temurin 21: https://adoptium.net/
- **Maven non serve installarlo**: il progetto contiene il *Maven Wrapper* (`mvnw` / `mvnw.cmd`), che scarica da solo la versione giusta di Maven al primo avvio.
- **Docker Desktop** avviato (serve sia per PostgreSQL in locale sia per Testcontainers).

## Cosa è stato costruito

| File | Chi | Cosa fa |
|---|---|---|
| `.gitignore` | Claude | Esclude dal repository `.env`, output di compilazione, `node_modules`, file degli IDE |
| `.env.example` | Claude | Modello delle variabili d'ambiente. Si copia in `.env` (ignorato da git) con i valori veri |
| `backend/pom.xml` | Claude (Initializr) | Progetto Maven: Spring Boot 4.1.1, Java 21, dipendenze |
| `backend/mvnw`, `mvnw.cmd`, `.mvn/` | Initializr | Maven Wrapper |
| `backend/.gitattributes` | Initializr | Forza i fine riga corretti per `mvnw` (LF) e `mvnw.cmd` (CRLF), altrimenti lo script si rompe passando tra Windows e Linux |
| `backend/.../BookingApplication.java` | Initializr | Punto di avvio dell'applicazione |
| `backend/.../{practitioner,customer}/package-info.java` | Claude | Package dei moduli non ancora scritti, con i TODO (quello di `auth` è stato tolto: il package ora ha le sue classi) |
| `frontend/` | — | Cartella vuota (il `.gitkeep` serve solo perché git non salva cartelle vuote) |
| `infra/docker-compose.dev.yml` | Nicholas | PostgreSQL 17 in locale: porta solo su `127.0.0.1`, named volume sui dati, segreti dal `.env` della radice (D-018) |
| `backend/src/main/resources/application.properties` | Nicholas | Importa il `.env`, connessione con placeholder (password senza default), `ddl-auto=validate`, `open-in-view=false` |
| `db/migration/V1__create_app_user_customer_practitioner.sql` | Nicholas | Tabelle `app_user`, `customer`, `practitioner` con vincoli con nome (D-020, D-021) |
| `auth/AppUser.java` | Nicholas | Prima entità: costruttore `protected` per Hibernate e pubblico con i controlli; `createdAt` scritto dal database |
| `auth/Email.java`, `auth/EmailConverter.java` | Nicholas | Value object `record` che normalizza e valida l'email; converter JPA verso la colonna `text` |
| `auth/Role.java` | Nicholas | `CUSTOMER`, `PRACTITIONER`, `ADMIN`, salvati come testo |
| `auth/AppUserRepository.java` | Nicholas | `JpaRepository` con `findByEmail` (`Optional`) ed `existsByEmail` |
| `test/.../auth/EmailTest.java` | Nicholas | 12 test unitari, senza Spring: normalizzazione, spazi, uguaglianza, valori rifiutati (~0,07 s) |
| `test/.../auth/AppUserRepositoryTest.java` | Nicholas | 9 test di integrazione su PostgreSQL 17 con Testcontainers: lettura dalla riga, colonne generate, ruolo come testo, `unique` sull'email (~7 s) |

Initializr genera anche tre file di test (`TestcontainersConfiguration`, `TestBookingApplication`, `BookingApplicationTests`). **Non sono stati copiati di proposito**: contengono proprio `@ServiceConnection` e la configurazione di Testcontainers, che sono un argomento di questo passo e li scrive Nicholas.

## Concetti chiave

> Spiegazione completa, con esempi e domande di verifica: [`docs/studio/01-impianto-del-progetto.md`](../studio/01-impianto-del-progetto.md). Qui sotto solo il riassunto.

### Java sul computer
JDK (per sviluppare) contiene il JRE (per eseguire), che contiene la JVM. `PATH` decide quale `java` parte quando scrivi il comando; `JAVA_HOME` dice agli altri programmi (Maven, IDE) dov'è il JDK. Le variabili d'ambiente si copiano all'avvio di ogni programma: per questo, dopo l'installazione, serviva riavviare VS Code.

### Maven e il `pom.xml`
- Coordinate `groupId:artifactId:version`; librerie scaricate da Maven Central nella cache `~/.m2/repository`.
- Il parent `spring-boot-starter-parent` fornisce le versioni di tutte le librerie (BOM): per questo nel pom non scriviamo `<version>`.
- Scope: `compile`, `runtime` (il driver PostgreSQL: il codice non deve dipenderne), `test`.
- Ciclo di vita: `validate → compile → test → package → verify → install → deploy`; ogni fase esegue anche le precedenti, il lavoro lo fanno i plugin.
- `spring-boot-maven-plugin` produce il *fat jar* avviabile con `java -jar`.
- Maven Wrapper: versione di Maven fissata nel repository, nessuna installazione.

### Spring Boot e auto-configurazione
> Come funziona Spring dentro (contenitore, bean, proxy): [`docs/studio/06-come-funziona-spring.md`](../studio/06-come-funziona-spring.md).

`@SpringBootApplication` = configurazione + auto-configurazione + component scan dal package della classe principale in giù. L'auto-configurazione si attiva in base a condizioni (`@ConditionalOnClass`, `@ConditionalOnMissingBean`, ...) e si fa da parte se definisci tu il bean. Report con `--debug`.

### Da Boot 2.x a 4.x
- Boot 3: `javax.*` → `jakarta.*`, Java 17 minimo, Hibernate 6, Spring Security senza `WebSecurityConfigurerAdapter`, `ProblemDetail`.
- Boot 3.1–3.2: `@ServiceConnection`, virtual thread, `RestClient`, `JdbcClient`.
- Boot 4: auto-configurazione divisa per tecnologia, ogni tecnologia ha il suo starter. Senza `spring-boot-starter-flyway` Flyway non parte e nessun messaggio te lo segnala. Jackson 3, Testcontainers 2, JSpecify.

### Docker Compose: servizi, volumi, variabili d'ambiente
> Spiegazione completa: [`docs/studio/02-docker-compose-postgres.md`](../studio/02-docker-compose-postgres.md).

- Immagine `postgres:17`, non `latest`: parità con la produzione, e il formato dei dati cambia tra versioni principali.
- Named volume sulla cartella dei dati della 17 (`/var/lib/postgresql/data`); senza, Docker crea un volume anonimo e dopo `down`/`up` il database appare vuoto.
- Segreti dal `.env` della radice tramite interpolazione `${...}` e `--env-file .env`; non `env_file:`, che passerebbe al container tutti i segreti.
- Porta pubblicata solo su `127.0.0.1`. In produzione la porta del database non si pubblica affatto.

### Flyway: versioni, storico, checksum
> Spiegazione completa: [`docs/studio/04-flyway-e-migrazione-v1.md`](../studio/04-flyway-e-migrazione-v1.md).

- File `V<versione>__<descrizione>.sql` in `db/migration`; storico in `flyway_schema_history`.
- Il checksum impedisce di modificare una migrazione già applicata: altrimenti database "alla stessa versione" avrebbero schemi diversi. Le correzioni sono migrazioni nuove.
- In PostgreSQL ogni migrazione è una transazione: se fallisce, viene annullata per intero.

### Vincoli SQL come ultima difesa
- Tutto ciò che è vero per ogni riga, sempre, diventa un vincolo: `not null`, `unique`, `check`, `foreign key`, identity `always`.
- "Al massimo uno" = `unique` sulla chiave esterna. Un `check` vede solo la propria riga: le regole tra tabelle restano in Java.
- Vincoli sempre con un nome (`tabella_colonna_tipo`).
- Email: tre strade (`check` + `unique`, indice su `lower()`, `citext`). Scelta la prima (D-020): il database impone il minuscolo, Java normalizza in un posto solo.

### Configurazione del backend e `ddl-auto=validate`
> Spiegazione completa: [`docs/studio/03-configurazione-backend.md`](../studio/03-configurazione-backend.md).

- JDBC + driver; `DataSource` come pool di connessioni (HikariCP).
- Il `.env` della radice importato con `spring.config.import=optional:file:...[.properties]`: `optional:` perché in produzione il file non c'è e le variabili arrivano dall'ambiente, che ha comunque precedenza.
- Placeholder `${NOME:default}`: default per host e porta, mai per la password (*fail fast*).
- `validate` e non `update`: lo schema ha un solo padrone, Flyway. Boot fa partire Flyway prima di Hibernate.
- `open-in-view=false` (D-019).

### JPA: mapping dell'entità, e perché un'entità non può essere un record
> Spiegazione completa: [`docs/studio/05-entita-jpa-e-test.md`](../studio/05-entita-jpa-e-test.md).

- Entità = oggetto gestito dal contesto di persistenza (*dirty checking*). `IDENTITY` → l'`insert` parte subito al `save`. `EnumType.STRING`, mai `ORDINAL`.
- `created_at` riempito dal database: `@Generated` di Hibernate (`org.hibernate.annotations`) la toglie dall'`insert` e la rilegge con `returning` (errore 6); `@Column(updatable = false)` la toglie dagli `update`, che altrimenti la riscriverebbero.
- Un'entità non può essere un `record` (final, campi final, niente costruttore vuoto, uguaglianza per valore); un `record` va bene per DTO, proiezioni, value object come `Email`.

### I costruttori
> Spiegazione completa: [`docs/studio/07-costruttori.md`](../studio/07-costruttori.md).

- Il costruttore di default esiste solo se non se ne dichiara nessuno: aggiunto `AppUser(Email, Role)`, quello vuoto per Hibernate va scritto a mano, `protected` (errore 7).
- Due porte: il costruttore pubblico per il nostro codice (con i controlli), quello vuoto per Hibernate, che poi riempie i campi con la reflection senza passare dai controlli.
- Ordine di inizializzazione: `static` una volta, poi dall'alto (genitore) verso il basso, inizializzatori dei campi prima del corpo del costruttore.

### Testcontainers e `@ServiceConnection`
- `@DataJpaTest` (Boot 4: `org.springframework.boot.data.jpa.test.autoconfigure`): solo JPA + Flyway, ogni test annullato con `rollback`, non sostituisce il database del container.
- `@Container @ServiceConnection static PostgreSQLContainer` con `postgres:17`: connessione presa dal container, nessun `.env` necessario (anche in CI).
- Un vincolo violato → `DataIntegrityViolationException` al `save`; verificare anche il **nome** del vincolo, per non far passare il test per il motivo sbagliato.
- Per leggere davvero dal database dopo un `save`: `flush()` + `clear()`, altrimenti torna l'oggetto in memoria (errore 7). Per sapere cosa c'è **nella colonna**: SQL con `JdbcTemplate`.
- Un test di un oggetto Java puro (`Email`) non ha annotazioni sulla classe: niente Spring, niente Docker (errore 9, PD-20).
- Criterio per ogni test: deve poter diventare rosso se il codice si rompe. Un test che legge l'oggetto creato da noi invece della riga non prova niente.

## Decisioni
- D-003 (Java 21, Spring Boot): applicata con Spring Boot 4.1.1.
- D-018: un unico `.env` nella radice, letto da Compose e da Spring.
- D-019: Open Session In View disattivato.
- D-020: email in minuscolo imposta dal database (`check` + `unique`), normalizzata in Java da un record `Email`.

## Errori fatti e come sono stati corretti

| # | Errore | Sintomo | Causa | Correzione | Lezione |
|---|---|---|---|---|---|
| 1 | Volume montato su `/var/lib/postgresql` | Nessuno, all'inizio: tutto sembrava funzionare | È il percorso di PostgreSQL **18**; con l'immagine 17 Docker crea un volume anonimo su `/var/lib/postgresql/data` e i dati finiscono lì | Montare il named volume su `/var/lib/postgresql/data` | Leggere la documentazione dell'immagine **per la versione usata**; verificare la persistenza con `down`/`up` |
| 2 | `spring.config.import=../.env` | `File extension is not known to any PropertySourceLoader` | Spring sceglie il lettore dall'estensione, e `.env` non ne ha una che conosca | Indicare il formato tra parentesi quadre: `...env[.properties]` | Il formato si dichiara con una sintassi apposita |
| 3 | `optional:file:../.env.properties` | `password authentication failed` | Senza parentesi, `.properties` diventa parte del nome: il file non esiste, `optional:` lo salta in silenzio, e i placeholder usano il default della password | Parentesi quadre; password **senza default** | `optional:` nasconde il file mancante, e il sintomo arriva lontano dalla causa (`password authentication failed`). Senza default almeno nessuna password inventata è nel repository. *(Corretto il 2026-10-19: prima diceva che senza default l'errore diventa `Could not resolve placeholder`; verificato che resta `authentication failed`)* |
| 4 | Secondo percorso `../../../.env` | Da VS Code, `.env` non trovato | Pensato come relativo alla cartella di `application.properties`, ma i percorsi `file:` sono relativi alla **cartella di lavoro** del processo | `.env` (dalla radice) accanto a `../.env` (da `backend/`) | Relativo a cosa? Sempre alla cartella da cui parte il processo |
| 5 | Spazio dopo `localhost` nel `.env` | `UnknownHostException: localhost` | Nel formato `.properties` gli spazi finali fanno parte del valore; Compose invece li toglie | Togliere lo spazio | Lo stesso file letto da due programmi può essere interpretato in modo diverso (PD-01). Nel `.env`: niente virgolette, niente `\`, niente spazi finali |
| 6 | `createdAt` di `AppUser` mappato prima senza annotazioni, poi con `@GeneratedValue(strategy = AUTO)`; importata `jakarta.annotation.Generated` | Senza annotazioni: il `save` fallirebbe sul `not null` di `created_at`. Con `@GeneratedValue`: l'applicazione non parte, `AnnotationException: Property '...createdAt' is annotated '@GeneratedValue' but is not part of an identifier` (verificato su Hibernate 7.4) | `@GeneratedValue` vale solo per la chiave primaria (insieme a `@Id`). `jakarta.annotation.Generated` è un'etichetta per il codice generato dai tool, non c'entra con JPA | `org.hibernate.annotations.Generated` (guida 05, 1.3) | Tre nomi simili, tre cose diverse: `@GeneratedValue` (id), `@Generated` di Hibernate (colonne scritte dal database), `@Generated` di `jakarta.annotation` (nessun effetto). Controllare sempre il package dell'import (PD-18). Il terzo tentativo, `import jakarta.annotation.*`, ha portato di nuovo la `@Generated` sbagliata: con l'import `*` non si vede da quale package arriva un nome |
| 7 | Aggiunto il costruttore pubblico `AppUser(email, role)` e tolto quello senza argomenti | All'avvio solo un `INFO`: `HHH000182: No default (no-argument) constructor for class [...AppUser]`. L'errore arriva solo quando Hibernate deve **creare** l'oggetto leggendo una riga: `InstantiationException: No default constructor for entity` (verificato su Hibernate 7.4) | JPA crea l'oggetto con il costruttore senza argomenti, poi riempie i campi. Se in Java esiste un altro costruttore, quello senza argomenti non viene più creato in automatico | Costruttore `protected` senza argomenti, accanto a quello pubblico | Un `save` seguito da `findById` nella stessa transazione non lo scopre, perché restituisce l'oggetto già in memoria: per leggere davvero dal database servono `flush()` e `clear()` |
| 8 | `findByEmailOrPhoneAndActiveTrue` (e il gemello `existsBy...`) nel repository | Nessun errore: il metodo funziona, ma un utente **disattivato** viene trovato per email | Nei nomi dei metodi Spring Data `And` lega più forte di `Or`, come in SQL: il nome diventa `(email = ?) OR (phone = ? AND active = true)` (verificato con il parser di Spring Data, `PartTree`) | Non combinare `Or` e `And` nel nome; se serve, una `@Query` scritta a mano con le parentesi | Un nome di metodo è una query: va letto con le regole di precedenza, e provato con un test sul caso "disattivato" (PD-19) |
| 9 | `EmailTest` (test unitario di un record) annotato con `@DataJpaTest` e `@Testcontainers`, copiate dal test del repository | Tutti i test della classe falliscono prima di partire: `Failed to load ApplicationContext`, in fondo alla catena `Failed to replace DataSource with an embedded database for tests` | `@DataJpaTest` avvia una parte di Spring con JPA, Flyway e un `DataSource`. Senza un container con `@ServiceConnection` prova a sostituire il database con uno in memoria (H2), che non c'è | Nessuna annotazione sulla classe: `Email` è Java puro, bastano JUnit e AssertJ | Ogni annotazione di test **accende** qualcosa: prima di copiarla chiedersi cosa serve al test. Leggere la causa in fondo alla catena (PD-05, PD-20) |

## Domande da colloquio

### Domanda 1: In un progetto con Flyway, perché `spring.jpa.hibernate.ddl-auto=validate` e non `update`? E cosa succede se modifichi una migrazione già applicata?
**La mia risposta:**

**Correzione:**

### Domanda 2: Come gestisci configurazione e segreti tra sviluppo e produzione in Spring Boot? Perché la password non ha un valore di default?
**La mia risposta:**

**Correzione:**

### Domanda 3: Perché un'entità JPA ha bisogno di un costruttore senza argomenti, e perché lo fai `protected`? Perché un'entità non può essere un `record`, mentre `Email` sì?
**La mia risposta:**

**Correzione:**

### Domanda 4: La colonna `created_at` ha `default now()` nel database. Come la mappi in Hibernate, e cosa succede con il mapping sbagliato all'`insert` e all'`update`?
**La mia risposta:**

**Correzione:**

### Domanda 5: Perché test di integrazione con Testcontainers e non con H2? Cosa fa `@ServiceConnection`? E perché il test di `Email` non ha `@DataJpaTest`?
**La mia risposta:**

**Correzione:**

### Domanda 6: L'email deve essere unica. La controlli in Java con `existsByEmail` o nel database? Come scrivi un test che lo provi senza farlo passare per il motivo sbagliato?
**La mia risposta:**

**Correzione:**
