# Configurazione del backend — guida di studio

Questa guida accompagna il punto 4 del passo 4: collegare Spring Boot al PostgreSQL in Docker, senza segreti nel repository, con Hibernate in modalità `validate`.

Contiene:
- **la parte 1**: i concetti, dalla base (JDBC, DataSource, pool di connessioni, JPA e Hibernate, fonti di configurazione);
- **la parte 2**: le risposte complete alle cinque domande, con il ragionamento;
- **la parte 3**: la sintassi, mostrata su un esempio **diverso** (la configurazione di un server email), più l'elenco di cosa deve contenere il nostro `application.properties`. Il file lo scrivi tu;
- **la parte 4**: come verificare che funzioni, e gli errori tipici.

---

## Indice

1. [I concetti](#parte-1--i-concetti)
2. [Le cinque domande, risolte](#parte-2--le-cinque-domande-risolte)
3. [La sintassi, su un esempio diverso](#parte-3--la-sintassi-su-un-esempio-diverso)
4. [Verifica ed errori tipici](#parte-4--verifica-ed-errori-tipici)
5. [Domande da colloquio](#domande-da-colloquio)
6. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — I concetti

### 1.1 JDBC e il driver

**JDBC** (*Java Database Connectivity*) è l'API standard di Java per parlare con un database relazionale: aprire una connessione, eseguire SQL, leggere i risultati. È fatta quasi solo di **interfacce** (`Connection`, `PreparedStatement`, `ResultSet`).

Le implementazioni concrete le fornisce il **driver**, una libreria diversa per ogni database. Il nostro è `org.postgresql:postgresql` (scope `runtime`, guida 01, 3.6). Il codice parla con le interfacce JDBC, e il driver traduce nel protocollo di rete di PostgreSQL.

Il driver sa a quale database collegarsi grazie all'**URL JDBC**:

```
jdbc:postgresql://localhost:5432/posture_studio
│    │            │         │    │
│    │            │         │    └─ nome del database
│    │            │         └─ porta
│    │            └─ host (il computer dove gira PostgreSQL)
│    └─ quale driver: PostgreSQL
└─ prefisso fisso di ogni URL JDBC
```

Dall'URL Spring Boot capisce anche **quale classe del driver** usare: non serve più scrivere `driver-class-name`, come si vede in molti tutorial vecchi.

**JDBC è un contratto.** Il nostro codice (e Hibernate, e Flyway) parla **solo** le interfacce JDBC, che fanno parte del JDK. Il driver le implementa per un database specifico. Per questo il driver ha scope `runtime`: a compilazione basta il contratto, e così nessuno può importare per errore classi del driver (es. `org.postgresql.util.PSQLException`) legando il codice a PostgreSQL. Cambiare database vorrebbe dire cambiare driver e URL; resterebbe da riscrivere solo l'SQL che usa funzioni proprie di PostgreSQL (nel nostro caso alcune migrazioni).

**Come viene scelto il driver, a runtime.** Ogni driver si annuncia con un file dentro il suo jar (`META-INF/services/java.sql.Driver`, meccanismo *ServiceLoader*). Quando arriva un URL, `DriverManager` chiede ai driver presenti chi sa gestirlo; Spring Boot, prima ancora, guarda l'inizio dell'URL (`jdbc:postgresql:`) per scegliere la classe del driver.

**Gli errori arrivano uno alla volta**, nell'ordine della catena: prima la scelta del driver (locale), poi la rete, poi PostgreSQL (credenziali, poi database). Con un URL che ha tre errori se ne vede uno; corretto quello, compare il successivo (esempio reale nella [verifica 02, D3](verifiche/02-2026-10-14-jdbc.md)):

| Errore nell'URL | Messaggio | Anello |
|---|---|---|
| `jdbc:postgres:` invece di `jdbc:postgresql:` | `Failed to determine a suitable driver class` | Scelta del driver |
| Porta sbagliata | `Connection to localhost:5433 refused` | Rete: nessuno in ascolto |
| Nome del database sbagliato (anche solo le maiuscole) | `FATAL: database "..." does not exist` | PostgreSQL |

### 1.2 DataSource e pool di connessioni

Aprire una connessione a un database è **costoso**: rete, autenticazione, allocazione di memoria sul server, decine di millisecondi. Se ogni richiesta HTTP ne aprisse e chiudesse una, l'applicazione passerebbe più tempo a collegarsi che a lavorare.

La soluzione è un **pool di connessioni**: all'avvio si aprono alcune connessioni e si tengono pronte. Quando il codice ne chiede una, il pool ne presta una libera; quando il codice la "chiude", in realtà torna nel pool.

```
         richiesta 1 ──┐                     ┌── connessione 1 ──┐
         richiesta 2 ──┼──►  pool (Hikari) ──┼── connessione 2 ──┼──► PostgreSQL
         richiesta 3 ──┘     presta e        └── connessione 3 ──┘
                             riprende
```

In Java il pool si presenta come una **`DataSource`**: l'interfaccia standard (`javax.sql.DataSource`, una delle parti di `javax` rimaste uguali) da cui si chiedono connessioni. Spring Boot usa **HikariCP**, il pool più diffuso, che arriva con lo starter JPA. Di default tiene al massimo 10 connessioni: per noi sono più che sufficienti.

Per creare la `DataSource`, all'auto-configurazione servono tre informazioni (guida 01, 4.5):

| Proprietà | Contenuto |
|---|---|
| `spring.datasource.url` | L'URL JDBC |
| `spring.datasource.username` | L'utente del database |
| `spring.datasource.password` | La password |

**Quante connessioni servono? 10 connessioni non sono 10 clienti.** Una connessione si prende in prestito solo per la durata di un'operazione sul database (una transazione, pochi millisecondi) e poi torna nel pool. Un cliente passa quasi tutto il tempo altrove. Con transazioni da 5 ms, 10 connessioni reggono migliaia di operazioni al secondo.

**Quando finiscono.** Una richiesta in più **aspetta** che una connessione si liberi, fino a `connectionTimeout` (default 30 secondi); poi Hikari rinuncia con `Connection is not available, request timed out after 30000ms`. La richiesta HTTP non viene rifiutata: la accetta Tomcat, che ha **il suo** pool di thread (200 di default), ed è il thread ad aspettare.

**Il pool è anche un limitatore.** Senza pool, ogni richiesta aprirebbe la sua connessione: autenticazione e, in PostgreSQL, **un processo del sistema operativo per connessione**. PostgreSQL ne accetta al massimo `max_connections` (100 di default); oltre, risponde `FATAL: sorry, too many clients already`. Con il pool la coda si forma ordinata nell'applicazione, invece di travolgere il database.

Cosa esaurisce il pool: tenere una connessione più del necessario. Per questo `open-in-view` va disattivato (bonus della Parte 2), e al passo 9 non si chiamerà mai Stripe **dentro** una transazione.

### 1.3 JPA, Hibernate, Spring Data JPA

Tre livelli, spesso confusi:

| Livello | Cos'è | Analogia |
|---|---|---|
| **JPA** (*Jakarta Persistence*) | Una **specifica**: interfacce e annotazioni standard (`@Entity`, `@Id`, `EntityManager`) per mappare oggetti Java su tabelle | Come JDBC: definisce il contratto |
| **Hibernate** | L'**implementazione** di JPA più diffusa, quella che fa il lavoro vero: genera l'SQL, gestisce le entità | Come il driver |
| **Spring Data JPA** | Un livello sopra JPA: scrivi un'interfaccia `AppUserRepository extends JpaRepository<...>` e Spring genera l'implementazione | Comodità in più |

Le proprietà di Hibernate in Spring Boot iniziano con `spring.jpa.`.

**JPA non sostituisce JDBC: ci sta sopra.** Nel nostro progetto non scriviamo mai `Connection` o `PreparedStatement`, ma JDBC lavora sempre, usato da altri componenti:

```
Spring Data JPA (repository)  →  Hibernate (genera SQL)  ┐
                                 Flyway (migrazioni)     ┼→ JDBC (interfacce) → HikariCP (connessioni) → driver PostgreSQL → rete → PostgreSQL
            (se servisse)        JdbcClient di Spring    ┘
```

Se servisse una query che non riguarda un'entità (un report, un'aggregazione), si potrebbe usare JDBC direttamente con `JdbcClient` di Spring, sullo stesso pool e nella stessa transazione.

### 1.4 Chi crea le tabelle? `ddl-auto`

Hibernate conosce le entità Java, quindi **potrebbe** creare da solo le tabelle corrispondenti. La proprietà `spring.jpa.hibernate.ddl-auto` decide cosa fa allo schema all'avvio:

| Valore | Cosa fa all'avvio | Dove ha senso |
|---|---|---|
| `none` | Niente | Quando lo schema è gestito da altri e non si vuole nemmeno il controllo |
| `validate` | **Controlla** che per ogni entità esistano le tabelle e le colonne, con tipi compatibili. Se qualcosa manca, l'applicazione **non parte**. Non modifica niente | **Il nostro caso** (D-004) |
| `update` | Confronta entità e database, e **aggiunge** quello che manca (tabelle, colonne). Non cancella e non rinomina mai | Prototipi usa e getta |
| `create` | Cancella e ricrea tutto lo schema a ogni avvio | Esperimenti |
| `create-drop` | Come `create`, e cancella tutto alla chiusura | Test con database in memoria |

Default di Spring Boot: `create-drop` con un database in memoria (H2), `none` con un database vero. Per questo va scritto esplicitamente.

### 1.5 Le fonti di configurazione e la loro precedenza

Lo hai visto nella guida 01 (4.6): Spring Boot raccoglie le proprietà da molte **fonti** (*property source*), e quando la stessa proprietà compare in più fonti vince quella con precedenza più alta. Più completo, dalla più debole alla più forte:

```
1. valori di default
2. application.properties dentro il jar
3. file importati con spring.config.import       (più forti del file che li importa)
4. application.properties fuori dal jar
5. variabili d'ambiente del sistema operativo
6. proprietà di sistema Java (-Dnome=valore)
7. argomenti da riga di comando (--nome=valore)
```

(L'elenco ufficiale ha più livelli: questi sono quelli che ci interessano.)

Il punto chiave: **una variabile d'ambiente batte qualsiasi file**. In produzione Docker passerà le variabili d'ambiente al container del backend, e quelle vincono su tutto.

### 1.6 I *placeholder*: `${...}`

Dentro `application.properties` il valore di una proprietà può fare riferimento a un'altra proprietà, o a una variabile d'ambiente, con un *placeholder*:

| Sintassi | Significato |
|---|---|
| `${NOME}` | Il valore di `NOME`. Se non esiste da nessuna parte, **l'applicazione non parte** ("Could not resolve placeholder") |
| `${NOME:valore}` | Il valore di `NOME`; se non esiste, usa `valore` |

Attenzione alla differenza con Compose: in Spring il default si scrive con **due punti** (`${NOME:5432}`), in Compose con **due punti e trattino** (`${NOME:-5432}`).

`NOME` viene cercato in **tutte** le fonti della 1.5: variabili d'ambiente, file importati, altre proprietà.

### 1.7 *Relaxed binding*

Spring Boot collega da solo una variabile d'ambiente a una proprietà, convertendo il nome: maiuscolo, e `_` al posto di `.` e `-`.

```
variabile d'ambiente  SPRING_DATASOURCE_URL   ──►  proprietà  spring.datasource.url
variabile d'ambiente  SERVER_PORT             ──►  proprietà  server.port
```

Quindi una proprietà si può impostare dall'ambiente **anche senza scriverla** in `application.properties`. È utile e da sapere (domanda da colloquio frequente). Nel nostro caso preferiamo i placeholder espliciti (risposta 1, più sotto spiega perché).

**Attenzione: la conversione vale solo per le variabili d'ambiente vere.** Il file `.env`, per Spring, **non è l'ambiente**: è un file che importiamo come `.properties`, quindi le sue chiavi sono nomi di proprietà normali. Verificato sul backend:

| Dove | Scritto così | Funziona? |
|---|---|---|
| Variabile d'ambiente vera (terminale, IDE, container) | `SERVER_PORT=8093` | ✅ convertita in `server.port` |
| File `.env` importato | `server.port=8092` | ✅ è già il nome della proprietà |
| File `.env` importato | `SERVER_PORT=8091` | ❌ ignorata in silenzio: per Spring è una proprietà che si chiama `SERVER_PORT`, e nessuno la usa |

(Le `POSTGRES_*` nel `.env` funzionano per un altro motivo: non le converte nessuno, le leggono i nostri placeholder `${POSTGRES_...}` con quel nome esatto.)

### 1.8 I profili (per sapere che esistono)

Un **profilo** è un insieme di configurazione attivo solo in certi ambienti: `application-dev.properties` vale solo se è attivo il profilo `dev` (`spring.profiles.active=dev`). Per ora non ci servono: la differenza tra locale e produzione la fanno le variabili d'ambiente, e un solo file resta più semplice. Li valuteremo se emergerà configurazione davvero diversa tra gli ambienti, per esempio log più dettagliati in sviluppo.

---

## Parte 2 — Le cinque domande, risolte

### Domanda 1 — Come arrivano i valori del `.env` a Spring?

**Il problema.** Compose legge il `.env` e passa le variabili **al container di PostgreSQL**. Ma Spring Boot gira **fuori** da Docker, lanciato da te (`mvnw`) o dall'IDE: è un altro processo, che non sa niente del `.env`. Le variabili d'ambiente del tuo terminale non le contengono.

**Le strade possibili:**

| Strada | Come | Valutazione |
|---|---|---|
| A. Variabili nel terminale | In PowerShell `$env:POSTGRES_USER = "..."` prima di lanciare | Funziona, ma va rifatto a ogni nuovo terminale, e le password restano nella cronologia dei comandi. Scomodo |
| B. Variabili nella configurazione di avvio dell'IDE | `launch.json` di VS Code (proprietà `envFile`) o la "Run configuration" di IntelliJ | Funziona solo dall'IDE, non da `mvnw`. E ogni IDE ha il suo modo |
| C. **Spring importa il `.env`** | `spring.config.import` con un file senza estensione | ✅ **Consigliata.** Un solo file, letto da Compose **e** da Spring, che funziona da terminale e da IDE |
| D. Supporto Docker Compose di Spring Boot | La dipendenza `spring-boot-docker-compose` avvia Compose da sola e configura la connessione | Molto comoda, ma è una dipendenza in più, esclusa dal brief. Da conoscere |

**Come funziona la strada C.** Spring Boot può importare altri file di configurazione:

```properties
spring.config.import=optional:file:percorso/del/file[.properties]
```

Pezzo per pezzo:
- `file:` → un file del file system (non dal classpath, cioè non da dentro il jar);
- `optional:` → se il file non esiste, **va avanti senza errori**. È essenziale: in produzione il `.env` non c'è, perché le variabili arrivano direttamente da Docker;
- `[.properties]` → il `.env` non ha un'estensione che Spring riconosca, quindi gli diciamo di leggerlo **come se fosse** un file `.properties`. Funziona perché il formato è lo stesso: righe `NOME=valore` e commenti con `#`.

Dopo l'importazione, `POSTGRES_USER`, `POSTGRES_PASSWORD` ecc. diventano proprietà disponibili ai placeholder.

**In produzione** (passo 14): niente `.env` sul percorso, `optional:` lo salta, e le stesse variabili arrivano come variabili d'ambiente vere, che hanno comunque precedenza più alta (1.5). **Lo stesso `application.properties` funziona in entrambi i casi, senza modifiche.**

**Attenzione, la trappola del percorso.** Un percorso relativo come `../.env` è relativo alla **cartella di lavoro** (*working directory*) del processo, cioè alla cartella da cui è stato avviato, non alla posizione di `application.properties`:

| Come avvii | Cartella di lavoro di solito | Dove sta il `.env` rispetto a lei |
|---|---|---|
| `.\mvnw.cmd spring-boot:run` dentro `backend/` | `backend/` | `../.env` |
| "Run" sulla classe `main` in VS Code | la radice del repository (la cartella aperta in VS Code) | `.env` |

E siccome hai scritto `optional:`, se il percorso è sbagliato **Spring non ti avvisa**: semplicemente non trova le variabili. Per fortuna i placeholder senza default bloccano subito l'avvio con "Could not resolve placeholder", e capisci dove guardare.

Due soluzioni:
- `spring.config.import` accetta **più file separati da virgola**: puoi elencare entrambi i percorsi, ognuno con `optional:`. Ne esiste solo uno, e viene trovato da entrambe le cartelle;
- oppure imposti la cartella di lavoro nella configurazione dell'IDE.

La prima è più robusta.

**Due regole sul contenuto del `.env`**, perché ora lo leggono due programmi diversi:
- **niente virgolette** intorno ai valori: Compose le toglie, Spring le terrebbe come parte della password;
- **niente barre rovesciate** (`\`): nei file `.properties` hanno un significato speciale;
- **niente spazi in fondo alle righe**: Compose li toglie, ma nel formato `.properties` fanno parte del valore. `localhost␣` diventa un host che non esiste (`UnknownHostException`). Errore reale del passo 4.

**Perché placeholder e non *relaxed binding* (1.7)?** Potremmo chiamare le variabili `SPRING_DATASOURCE_USERNAME` ecc. e non scrivere niente in `application.properties`. Però:
- il container di PostgreSQL vuole `POSTGRES_USER`: avremmo due nomi per lo stesso valore, da tenere allineati;
- l'URL va comunque **composto** da host, porta e nome del database;
- con i placeholder, `application.properties` documenta **quali** variabili servono: chi lo legge capisce subito da dove arriva ogni valore.

### Domanda 2 — Le proprietà, i placeholder e i default

**Le tre proprietà minime:** `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password` (1.2).

**Il riferimento a una variabile:** `${NOME}`, oppure `${NOME:default}` (1.6).

**Quali variabili possono avere un default:**

| Variabile | Default? | Perché |
|---|---|---|
| Host del database | ✅ `localhost` | In sviluppo è sempre quello; in produzione verrà dall'ambiente (risposta 3) |
| Porta | ✅ `5432` | È la porta standard di PostgreSQL |
| Nome del database | ⚠️ meglio di no | Viene già dal `.env`, e un default che non coincide sarebbe solo confusione |
| Utente | ⚠️ meglio di no | Come sopra |
| **Password** | ❌ **mai** | Un default sarebbe una password scritta nel repository pubblico. E se la variabile manca, l'applicazione deve **fermarsi** con un errore chiaro, non partire con una password inventata |

È lo stesso principio di `${...:?}` nel file Compose: **fallire subito, e in modo chiaro**, quando manca un valore obbligatorio. Si chiama *fail fast*.

### Domanda 3 — L'URL JDBC e cosa viene dall'ambiente

Formato: `jdbc:postgresql://HOST:PORTA/DATABASE` (1.1).

Cosa cambia tra il tuo computer e la produzione:

| Parte | Sul tuo computer | In produzione (passo 14) |
|---|---|---|
| Host | `localhost`: il backend gira su Windows, e Compose pubblica la porta su `127.0.0.1` | il **nome del servizio** in Compose (es. `postgres`): il backend è un container, e `localhost` sarebbe il container stesso (guida 02, 1.5) |
| Porta | quella di `POSTGRES_PORT` (5432) | 5432, sulla rete interna di Docker |
| Database | `POSTGRES_DB` | `POSTGRES_DB` |

Quindi **tutte e tre le parti** vanno composte con dei placeholder. L'host oggi non è nel `.env.example`: aggiungi una variabile `POSTGRES_HOST` (con un commento che spieghi che in locale si può omettere, grazie al default).

Nota: `POSTGRES_HOST` non è una variabile che l'immagine `postgres` conosce. Compose non la passa al container (non è nel suo `environment:`), quindi non disturba. La usa solo Spring.

### Domanda 4 — I valori di `ddl-auto`, e perché non `update`

I valori sono nella tabella della 1.4. **Usiamo `validate`.**

**Perché `update` è pericoloso in un progetto con Flyway:**

1. **Due padroni dello schema.** Flyway crea lo schema dalle migrazioni versionate. Se anche Hibernate lo modifica, il database reale non corrisponde più alle migrazioni. Un database nuovo creato solo dalle migrazioni (quello dei test, quello di produzione) sarà **diverso** dal tuo, e l'errore emerge nel posto peggiore.
2. **Fa solo aggiunte.** Rinomini un campo nell'entità? `update` crea una colonna nuova e lascia la vecchia con i dati dentro. Non cancella, non rinomina, non sposta dati. Lo schema si riempie di colonne morte.
3. **Decide lui i tipi e ignora i vincoli.** Crea colonne con i tipi che sceglie Hibernate e senza i nostri vincoli (`check`, `unique` con `lower()`, *exclusion constraint*). Le regole che vogliamo far rispettare al database (D-013, "ultima difesa") non esisterebbero.
4. **Nessuna storia.** Le modifiche di `update` non sono scritte da nessuna parte: non si possono rivedere, ripetere o versionare.

**Cosa ci dà `validate`:** all'avvio Hibernate confronta le entità con le tabelle create da Flyway. Se hai aggiunto un campo all'entità e hai dimenticato la migrazione, **l'applicazione non parte** e ti dice quale colonna manca. Errore scoperto subito, sul tuo computer.

Limiti da sapere: `validate` controlla l'esistenza di tabelle e colonne e la compatibilità dei tipi. **Non** controlla vincoli, indici, valori di default, né colonne in più nel database. Quelli si verificano con i test (punto 7 del brief).

### Domanda 5 — In che ordine partono Flyway e Hibernate?

**Prima Flyway, poi Hibernate.** Deve essere così: `validate` controlla le tabelle, che devono già essere state create dalle migrazioni. Se partisse prima Hibernate, al primo avvio su un database vuoto la validazione fallirebbe sempre.

**Chi lo garantisce?** L'auto-configurazione di Spring Boot. Quando ci sono sia Flyway sia JPA, Boot dichiara che il bean di JPA (l'`EntityManagerFactory`, il cuore di Hibernate) **dipende** dal bean che esegue le migrazioni Flyway. Il contenitore di Spring crea i bean nell'ordine delle dipendenze, quindi le migrazioni girano prima. Tu non configuri niente.

Da colloquio: è un esempio concreto di come l'auto-configurazione non si limita a creare bean, ma ne **coordina l'ordine** di avvio.

### Bonus — L'avviso su `open-in-view`

All'avvio vedrai un **WARN** simile a questo:

```
spring.jpa.open-in-view is enabled by default. Therefore, database queries may be
performed during view rendering. Explicitly configure spring.jpa.open-in-view to
disable this warning
```

**Cos'è *Open Session In View* (OSIV).** Con OSIV attivo (il default), Spring tiene aperto il contesto di Hibernate, e una connessione al database, **per tutta la durata della richiesta HTTP**, anche dopo la fine del servizio, mentre il controller prepara la risposta.

Serve a evitare la famosa `LazyInitializationException`: se un'entità ha una relazione caricata "pigramente" (*lazy*) e qualcuno la legge nel controller o durante la conversione in JSON, con OSIV la query parte comunque.

**Perché nel nostro progetto conviene disattivarlo (`false`):**
- **query nascoste**: le query partono in punti dove nessuno se le aspetta (durante la serializzazione JSON), anche decine per richiesta (il problema "N+1");
- **connessioni trattenute**: ogni richiesta tiene occupata una connessione del pool più a lungo del necessario. Con 10 connessioni, sotto carico finiscono;
- **confini poco chiari**: il lavoro sul database dovrebbe stare nel servizio, dentro la transazione (architettura: "Service: le regole del dominio e le transazioni");
- **non ci serve**: le API restituiscono record DTO costruiti nel servizio, mai entità (architettura). Tutto quello che serve viene caricato **dentro** il servizio.

Con OSIV disattivato, se dimentichi di caricare qualcosa nel servizio ottieni una `LazyInitializationException`: un errore chiaro, invece di query nascoste. È una scelta di progetto: quando la fai, va registrata in `decisioni.md`.

---

## Parte 3 — La sintassi, su un esempio diverso

Ecco la configurazione di un **server email** in un'applicazione Spring Boot qualsiasi (ci servirà davvero al passo 8, ma con valori diversi). Usa tutti gli elementi di sintassi che ti servono.

```properties
spring.application.name=newsletter

# Local secrets: read from an env file if present (absent in production,
# where the same names arrive as real environment variables).
spring.config.import=optional:file:config/mail.env[.properties]

# Placeholders: host and port have a safe default, credentials do not.
spring.mail.host=${MAIL_HOST:localhost}
spring.mail.port=${MAIL_PORT:587}
spring.mail.username=${MAIL_USERNAME}
spring.mail.password=${MAIL_PASSWORD}
```

Note:
- in un file `.properties` i commenti iniziano con `#` **a inizio riga**, come in `.gitignore`;
- non servono virgolette intorno ai valori;
- un placeholder può stare **in mezzo** a un testo: `qualcosa://${A}:${B}/${C}` è valido, ed è così che si compone un URL.

### Cosa deve contenere il nostro `application.properties`

- [ ] `spring.application.name` (c'è già)
- [ ] L'importazione del `.env` della radice, opzionale, letto come `.properties`, funzionante sia da `backend/` sia dalla radice (domanda 1)
- [ ] `spring.datasource.url` composto con placeholder per host (default `localhost`), porta (default `5432`) e database (senza default) (domande 2 e 3)
- [ ] `spring.datasource.username` e `spring.datasource.password`, **senza default** (domanda 2)
- [ ] `spring.jpa.hibernate.ddl-auto=validate` (domanda 4)
- [ ] `spring.jpa.open-in-view` impostato esplicitamente, secondo la tua decisione (bonus)
- [ ] Commenti in inglese, solo dove spiegano un **perché**
- [ ] Nessuna password, nessun valore segreto

E fuori da `application.properties`:
- [ ] `POSTGRES_HOST` aggiunta a `.env.example`, con un commento (domanda 3)

Cosa **non** serve scrivere, anche se lo vedrai in molti tutorial:
- `spring.datasource.driver-class-name`: lo ricava dall'URL;
- `spring.jpa.database-platform` o `hibernate.dialect`: da Hibernate 6 il dialetto si riconosce da solo, e impostarlo a mano produce un avviso di deprecazione;
- `spring.flyway.locations`: il default `classpath:db/migration` è già la nostra cartella;
- `spring.flyway.enabled=true`: è già il default.

---

## Parte 4 — Verifica ed errori tipici

### La prova

1. Il database deve essere acceso (dalla radice):
   ```powershell
   docker compose -f infra/docker-compose.dev.yml --env-file .env up -d
   ```
2. Da `backend/`:
   ```powershell
   .\mvnw.cmd spring-boot:run
   ```
3. Nel log cerca, in quest'ordine:
   - `HikariPool-1 - Start completed`: il pool si è collegato al database;
   - le righe di **Flyway**: la versione di PostgreSQL rilevata (17.x) e un messaggio che dice che non ci sono migrazioni da applicare. È normale: la cartella è ancora vuota;
   - `Tomcat started on port 8080`;
   - `Started BookingApplication in ... seconds`.
4. Leggi tutti i `WARN`. Con `open-in-view` impostato esplicitamente, quello della parte 2 sparisce.
5. `Ctrl+C` per fermare.
6. **Prova dall'IDE**: avvia `BookingApplication` anche da VS Code. Deve partire allo stesso modo, ed è la prova che l'importazione del `.env` funziona da entrambe le cartelle di lavoro.
7. **Prova del fail fast**: rinomina per un momento il `.env` (es. `.env.off`) e riavvia. L'applicazione deve **fermarsi** con "Could not resolve placeholder". Poi rimetti il nome giusto.

### Errori tipici

| Sintomo | Causa probabile |
|---|---|
| `Could not resolve placeholder 'POSTGRES_...'` | Il `.env` non è stato trovato: percorso sbagliato rispetto alla cartella di lavoro (domanda 1), o il file non esiste |
| `Failed to configure a DataSource: 'url' attribute is not specified` | Manca `spring.datasource.url`, o ha un nome sbagliato (attenzione agli errori di battitura: Spring ignora in silenzio le proprietà che non conosce) |
| `Connection refused` / `Connection to localhost:5432 refused` | Il container non è acceso, oppure la porta è diversa da quella pubblicata |
| `password authentication failed for user` | Password diversa da quella con cui è stato **inizializzato** il volume (guida 02, 1.6), oppure virgolette nel `.env` |
| `database "..." does not exist` | `POSTGRES_DB` diverso dal database creato al primo avvio del volume |
| `Web server failed to start. Port 8080 was already in use` | Un'altra istanza dell'applicazione è ancora accesa (magari dall'IDE) |
| Avviso sul dialetto (`HHH90000025` o simile) | Hai impostato il dialetto a mano: toglilo |

---

## Domande da colloquio

1. Cos'è un pool di connessioni e perché si usa? Quale usa Spring Boot?
2. Che differenza c'è tra JPA, Hibernate e Spring Data JPA?
3. Quali valori può avere `ddl-auto`? Quale usi in produzione e perché?
4. Come passi una password a un'applicazione Spring Boot senza scriverla nel codice? Quale fonte di configurazione vince se la stessa proprietà è definita in più posti?
5. Cos'è il *relaxed binding*?
6. Cos'è *Open Session In View*? Lo lasceresti attivo?
7. Usi Flyway e Hibernate insieme: come fai a essere sicuro che le migrazioni girino prima della validazione dello schema?

---

## Documentazione ufficiale

- Configurazione esterna (precedenza, `spring.config.import`, file senza estensione, placeholder, relaxed binding): https://docs.spring.io/spring-boot/reference/features/external-config.html
- Accesso ai dati SQL (DataSource, pool, JPA, `open-in-view`): https://docs.spring.io/spring-boot/reference/data/sql.html
- Inizializzazione del database (`ddl-auto`, Flyway): https://docs.spring.io/spring-boot/how-to/data-initialization.html
- Elenco di tutte le proprietà: https://docs.spring.io/spring-boot/appendix/application-properties/index.html
- Driver JDBC di PostgreSQL, formato dell'URL: https://jdbc.postgresql.org/documentation/use/
- HikariCP: https://github.com/brettwooldridge/HikariCP
