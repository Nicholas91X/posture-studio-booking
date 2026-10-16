# Passo 04 — Fondamenta

> **Stato: in corso.** Le sezioni "Errori fatti" e "Domande da colloquio" si completano a fine passo.

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
| `backend/.../{auth,practitioner,customer}/package-info.java` | Claude | Package vuoti dei moduli, con i TODO |
| `backend/src/main/resources/db/migration/` | — | Cartella dove Flyway cerca le migrazioni (per ora vuota) |
| `infra/`, `frontend/` | — | Cartelle vuote (il `.gitkeep` serve solo perché git non salva cartelle vuote) |
| `infra/docker-compose.dev.yml` | Nicholas | *da fare* |
| `backend/src/main/resources/application.properties` | Nicholas | *da completare* (connessione al DB, Hibernate `validate`) |
| `V1__...sql` | Nicholas | *da fare* |
| `AppUser`, `AppUserRepository` | Nicholas | *da fare* |
| Test di integrazione | Nicholas | *da fare* |

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
- Email: tre strade (`check` + `unique`, indice su `lower()`, `citext`), scelta di Nicholas da registrare in `decisioni.md`.

### Configurazione del backend e `ddl-auto=validate`
> Spiegazione completa: [`docs/studio/03-configurazione-backend.md`](../studio/03-configurazione-backend.md).

- JDBC + driver; `DataSource` come pool di connessioni (HikariCP).
- Il `.env` della radice importato con `spring.config.import=optional:file:...[.properties]`: `optional:` perché in produzione il file non c'è e le variabili arrivano dall'ambiente, che ha comunque precedenza.
- Placeholder `${NOME:default}`: default per host e porta, mai per la password (*fail fast*).
- `validate` e non `update`: lo schema ha un solo padrone, Flyway. Boot fa partire Flyway prima di Hibernate.
- `open-in-view=false` (D-019).

### JPA: mapping dell'entità, e perché un'entità non può essere un record
*(da completare durante il passo)*

### Testcontainers e `@ServiceConnection`
*(da completare durante il passo)*

## Decisioni
- D-003 (Java 21, Spring Boot): applicata con Spring Boot 4.1.1.
- D-018: un unico `.env` nella radice, letto da Compose e da Spring.
- D-019: Open Session In View disattivato.
- *(da registrare: la scelta per l'email in minuscolo, con V1)*

## Errori fatti e come sono stati corretti

| # | Errore | Sintomo | Causa | Correzione | Lezione |
|---|---|---|---|---|---|
| 1 | Volume montato su `/var/lib/postgresql` | Nessuno, all'inizio: tutto sembrava funzionare | È il percorso di PostgreSQL **18**; con l'immagine 17 Docker crea un volume anonimo su `/var/lib/postgresql/data` e i dati finiscono lì | Montare il named volume su `/var/lib/postgresql/data` | Leggere la documentazione dell'immagine **per la versione usata**; verificare la persistenza con `down`/`up` |
| 2 | `spring.config.import=../.env` | `File extension is not known to any PropertySourceLoader` | Spring sceglie il lettore dall'estensione, e `.env` non ne ha una che conosca | Indicare il formato tra parentesi quadre: `...env[.properties]` | Il formato si dichiara con una sintassi apposita |
| 3 | `optional:file:../.env.properties` | `password authentication failed` | Senza parentesi, `.properties` diventa parte del nome: il file non esiste, `optional:` lo salta in silenzio, e i placeholder usano il default della password | Parentesi quadre; password **senza default** | `optional:` + default = errore silenzioso nel posto sbagliato. Senza default l'errore diventa `Could not resolve placeholder`, che punta alla causa vera |
| 4 | Secondo percorso `../../../.env` | Da VS Code, `.env` non trovato | Pensato come relativo alla cartella di `application.properties`, ma i percorsi `file:` sono relativi alla **cartella di lavoro** del processo | `.env` (dalla radice) accanto a `../.env` (da `backend/`) | Relativo a cosa? Sempre alla cartella da cui parte il processo |
| 5 | Spazio dopo `localhost` nel `.env` | `UnknownHostException: localhost` | Nel formato `.properties` gli spazi finali fanno parte del valore; Compose invece li toglie | Togliere lo spazio | Lo stesso file letto da due programmi può essere interpretato in modo diverso (PD-01). Nel `.env`: niente virgolette, niente `\`, niente spazi finali |

## Domande da colloquio
*(a fine passo)*
