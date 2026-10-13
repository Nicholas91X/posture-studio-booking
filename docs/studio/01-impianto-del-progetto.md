# Impianto del progetto — guida di studio

Questa guida spiega **tutto quello che è stato preparato finora nel passo 4**, dall'installazione di Java fino all'ultimo file del repository. È pensata per essere letta con calma e riletta prima dei colloqui.

Ogni capitolo segue lo stesso schema:
- **cosa è**, in parole semplici;
- **perché** si fa così (e cosa succederebbe altrimenti);
- **nel nostro progetto**: dove lo trovi, concretamente;
- in fondo al capitolo, una o due domande per verificare di aver capito.

I termini tecnici restano in inglese, come li sentirai nei colloqui. C'è un glossario alla fine.

---

## Indice

1. [La mappa: cosa c'è nel repository](#1-la-mappa-cosa-cè-nel-repository)
2. [Java sul computer: JDK, PATH, JAVA_HOME](#2-java-sul-computer-jdk-path-java_home)
3. [Maven](#3-maven)
4. [Spring Boot](#4-spring-boot)
5. [Da Spring Boot 2 a Spring Boot 4](#5-da-spring-boot-2-a-spring-boot-4)
6. [I moduli e i package](#6-i-moduli-e-i-package)
7. [Git e i file di servizio](#7-git-e-i-file-di-servizio)
8. [Glossario](#8-glossario)
9. [Verifica finale](#9-verifica-finale)
10. [Documentazione ufficiale](#10-documentazione-ufficiale)

---

## 1. La mappa: cosa c'è nel repository

```
posture-studio-booking/
├── .gitignore                 quali file git deve ignorare (es. .env)
├── .env.example               modello delle variabili d'ambiente, senza segreti veri
├── CLAUDE.md                  istruzioni per Claude Code
├── docs/                      documentazione (requisiti, decisioni, passi, questa guida)
├── frontend/                  vuoto per ora (passo 12)
├── infra/                     vuoto per ora; qui andrà docker-compose.dev.yml
└── backend/
    ├── pom.xml                descrizione del progetto per Maven
    ├── mvnw, mvnw.cmd         Maven Wrapper (Linux/Mac e Windows)
    ├── .mvn/wrapper/          configurazione del Wrapper (quale Maven scaricare)
    ├── .gitattributes         regole sui fine riga per gli script mvnw
    ├── target/                prodotto da Maven quando compili (ignorato da git)
    └── src/
        └── main/
            ├── java/com/posturestudio/booking/
            │   ├── BookingApplication.java     punto di avvio
            │   ├── auth/package-info.java       modulo accesso (TODO)
            │   ├── customer/package-info.java   modulo clienti (TODO)
            │   └── practitioner/package-info.java  modulo professionisti (TODO)
            └── resources/
                ├── application.properties      configurazione di Spring
                └── db/migration/               migrazioni Flyway (per ora vuota)
```

Manca ancora `src/test/`: lo creerai tu con il primo test.

---

## 2. Java sul computer: JDK, PATH, JAVA_HOME

### 2.1 JVM, JRE, JDK

Tre scatole una dentro l'altra:

```
┌───────────────────────── JDK ─────────────────────────┐
│  javac (compilatore), jar, jshell, jdb, ...           │
│  ┌──────────────────── JRE ────────────────────────┐  │
│  │  librerie standard (java.lang, java.util, ...)  │  │
│  │  ┌──────────────── JVM ──────────────────────┐  │  │
│  │  │  esegue il bytecode (.class)              │  │  │
│  │  └───────────────────────────────────────────┘  │  │
│  └─────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────┘
```

- **JVM** (*Java Virtual Machine*): il programma che esegue il *bytecode*, cioè i file `.class` prodotti dalla compilazione. È il motivo per cui lo stesso `.jar` gira su Windows e su Linux.
- **JRE** (*Java Runtime Environment*): JVM + librerie standard. Basta per **eseguire** un programma Java.
- **JDK** (*Java Development Kit*): JRE + strumenti per **sviluppare**, primo fra tutti il compilatore `javac`.

**Nel nostro progetto:** sul tuo computer serve il JDK, perché devi compilare. In produzione (passo 14) il container del backend avrà solo un JRE: deve solo eseguire il jar già compilato, e un'immagine più piccola è più veloce da scaricare e ha meno software da tenere aggiornato.

### 2.2 Versioni e LTS

Java esce ogni sei mesi. Ogni due anni una versione è **LTS** (*Long Term Support*): riceve aggiornamenti di sicurezza per molti anni, ed è quella che le aziende adottano. Le LTS: 8, 11, 17, **21**, 25.

Abbiamo scelto 21 (D-003): è la LTS più diffusa nelle aziende oggi, e porta tutte le novità dopo la 17 che vale la pena saper usare (record, sealed class, pattern matching, virtual thread).

### 2.3 Temurin, OpenJDK, Oracle JDK

**OpenJDK** è il codice sorgente di Java, open source. Diverse organizzazioni lo compilano e distribuiscono: **Eclipse Temurin** (della fondazione Adoptium, quello che hai installato), Amazon Corretto, Microsoft Build of OpenJDK, e anche Oracle. Sono tecnicamente equivalenti. Cambiano licenza e durata del supporto. Temurin è gratuito anche per uso commerciale: per questo è la scelta più comune.

> **Da colloquio:** "Che differenza c'è tra Oracle JDK e OpenJDK?" → Stesso codice di base. La differenza è nella licenza e nel supporto commerciale. Molte aziende sono passate a distribuzioni OpenJDK gratuite quando Oracle ha cambiato le condizioni di licenza.

### 2.4 Le variabili d'ambiente

Una **variabile d'ambiente** è una coppia nome = valore che il sistema operativo mette a disposizione di ogni programma. Esempi: `PATH`, `JAVA_HOME`, `USERNAME`.

Il punto da capire bene: **ogni programma riceve una copia delle variabili nel momento in cui parte**, e le eredita dal programma che lo ha avviato. Se cambi una variabile nelle impostazioni di Windows, i programmi già aperti non lo sanno: hanno la loro copia vecchia.

```
Windows (impostazioni)  ──copia all'avvio──►  VS Code  ──copia──►  terminale di VS Code  ──copia──►  mvnw
```

**È per questo che hai dovuto chiudere e riaprire VS Code**: aprire un nuovo terminale non bastava, perché il terminale copia le variabili da VS Code, che aveva ancora quelle di prima dell'installazione.

Le useremo molto: è il modo in cui passeremo password e chiavi all'applicazione senza scriverle nel codice (capitolo 7.2).

### 2.5 PATH

Quando scrivi `java` in un terminale, Windows deve capire **quale** file eseguire. Lo cerca nelle cartelle elencate nella variabile `PATH`, **nell'ordine**, e usa il primo che trova.

Sul tuo computer c'erano due Java:

```
PATH = ...;C:\Program Files (x86)\Common Files\Oracle\Java\java8path;...;C:\Program Files\Eclipse Adoptium\jdk-21...\bin;...
                    ▲ contiene java.exe (Java 8)                              ▲ contiene java.exe (Java 21)
```

Se la cartella di Java 8 viene prima, vince Java 8, anche se il 21 è installato. Due dettagli utili:
- in Windows il `Path` finale è quello **di sistema seguito da quello dell'utente**: a parità di condizioni, le voci di sistema vincono;
- per vedere quale `java` viene davvero usato: `where.exe java` (PowerShell) o `where java` (cmd). Il primo della lista è quello che parte.

### 2.6 JAVA_HOME

`PATH` serve a te quando scrivi comandi. `JAVA_HOME` serve **agli altri programmi**: è la convenzione con cui un programma chiede "dov'è installato il JDK?". Lo usano Maven (e il Wrapper), gli IDE, Gradle, molti script.

`JAVA_HOME` punta alla **cartella principale** del JDK, non a `bin`:

```
JAVA_HOME = C:\Program Files\Eclipse Adoptium\jdk-21.0.x-hotspot      ✅
JAVA_HOME = C:\Program Files\Eclipse Adoptium\jdk-21.0.x-hotspot\bin  ❌
```

Lo strumento poi aggiunge da solo `\bin\java.exe`.

> **Verifica 2:** Hai due JDK installati. `java -version` dice 21, ma Maven dice "Java version: 1.8". Com'è possibile, e dove guardi?

---

## 3. Maven

### 3.1 Cosa fa Maven

Maven è un **build tool**: lo strumento che trasforma il codice sorgente in un programma pronto. Si occupa di:
1. **scaricare le librerie** di cui il progetto ha bisogno (le *dipendenze*);
2. **compilare** il codice;
3. **eseguire i test**;
4. **impacchettare** il risultato (un file `.jar`).

Funziona per **convenzione**: non devi dirgli dove sta il codice, perché si aspetta una struttura standard.

| Cartella | Contenuto |
|---|---|
| `src/main/java` | Codice Java dell'applicazione |
| `src/main/resources` | File non Java che finiscono nell'applicazione (configurazione, migrazioni SQL) |
| `src/test/java` | Codice dei test |
| `src/test/resources` | File usati solo dai test |
| `target/` | Tutto ciò che Maven produce. Si può cancellare in qualsiasi momento, si rigenera |

Tutto quello che Maven deve sapere del progetto sta in un file: **`pom.xml`** (*Project Object Model*).

### 3.2 Le coordinate

Ogni libreria nel mondo Maven ha un "indirizzo" di tre parti, le **coordinate**:

```xml
<groupId>com.posturestudio</groupId>     <!-- chi la produce (di solito un dominio rovesciato) -->
<artifactId>booking</artifactId>         <!-- il nome del progetto -->
<version>0.0.1-SNAPSHOT</version>        <!-- la versione -->
```

Lo stesso schema identifica le nostre dipendenze: `org.postgresql:postgresql:42.x.x`.

**SNAPSHOT** indica una versione in sviluppo, che può cambiare. Una versione senza `-SNAPSHOT` (es. `1.0.0`) è una *release*: una volta pubblicata non cambia mai più.

### 3.3 I repository

Dove le trova, Maven, le librerie?

```
                                ┌─────────────────────────────┐
  mvnw compile  ──── 1 ────────►│ repository locale           │  C:\Users\Principale\.m2\repository
                                │ (cache sul tuo computer)    │
                                └──────────┬──────────────────┘
                                           │ 2. se non c'è
                                ┌──────────▼──────────────────┐
                                │ Maven Central               │  repo.maven.apache.org
                                │ (repository pubblico)       │
                                └─────────────────────────────┘
```

La prima compilazione è lenta perché scarica tutto in `.m2\repository`. Dalla seconda in poi è tutto in locale. Nelle aziende di solito c'è anche un repository interno (Nexus, Artifactory) in mezzo: in banca l'avrai quasi certamente usato.

### 3.4 Le dipendenze transitive

Nel nostro `pom.xml` ci sono una dozzina di dipendenze, ma Maven ne scarica più di cento. Perché? Ogni libreria ha a sua volta le sue dipendenze, che Maven porta dentro in automatico: sono le **dipendenze transitive**.

```
spring-boot-starter-data-jpa
├── spring-boot-starter-jdbc
│   └── HikariCP                 (pool di connessioni)
├── hibernate-core
│   ├── jakarta.persistence-api
│   └── ...
└── spring-data-jpa
    └── ...
```

Puoi vedere l'albero completo con:

```powershell
.\mvnw.cmd dependency:tree
```

Fallo almeno una volta: è istruttivo vedere quanto codice c'è dietro poche righe di `pom.xml`.

**Il problema dei conflitti.** Se due librerie chiedono versioni diverse della stessa dipendenza, Maven ne sceglie una sola. La regola è "**vince la più vicina**" nell'albero, e a parità di distanza la prima dichiarata. Può portare a versioni incompatibili, con errori che compaiono solo a runtime (es. `NoSuchMethodError`). La soluzione è il prossimo punto.

### 3.5 Il parent e il dependency management

Il nostro `pom.xml` inizia così:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
</parent>
```

Il progetto **eredita** dal pom di Spring Boot, come una classe Java eredita da un'altra. Cosa eredita:

1. **Il dependency management** (*gestione delle versioni*). Il parent contiene un lungo elenco: "se usi Hibernate, usa la versione X; se usi Jackson, la versione Y" e così via per centinaia di librerie, testate insieme dal team di Spring. Per questo **nelle nostre dipendenze non scriviamo mai `<version>`**: la decide il parent. Ed è anche la soluzione ai conflitti del punto precedente.
2. **La configurazione dei plugin**: versione del compilatore, codifica UTF-8, ecc.
3. **Proprietà** che possiamo sovrascrivere, come `java.version`.

`<relativePath/>` vuoto vuol dire: "non cercare il parent nella cartella sopra, scaricalo dal repository".

Una distinzione che spesso si chiede ai colloqui:

| Sezione del pom | Cosa fa |
|---|---|
| `<dependencies>` | **Aggiunge** davvero una libreria al progetto |
| `<dependencyManagement>` | **Non aggiunge niente**: dice solo "*se* questa libreria viene usata, usa questa versione" |

Il parent di Spring Boot è un grande `<dependencyManagement>`. Un elenco di versioni pubblicato in questo modo si chiama **BOM** (*Bill of Materials*, "distinta base").

> Il parent non è l'unico modo: se un'azienda ha già un suo pom parent, si può importare il BOM di Spring Boot dentro `<dependencyManagement>` con `<scope>import</scope>`. Te lo possono chiedere.

### 3.6 Gli scope

Lo **scope** dice a Maven **quando** serve una dipendenza.

| Scope | Serve per compilare? | Serve per eseguire? | Finisce nel jar? | Nel nostro pom |
|---|---|---|---|---|
| `compile` (default) | sì | sì | sì | starter web, JPA, Flyway, validation |
| `runtime` | no | sì | sì | driver `postgresql` |
| `test` | solo i test | solo i test | no | tutti i `*-test`, Testcontainers |
| `provided` | sì | lo fornisce l'ambiente | no | (non lo usiamo) |

**Perché il driver PostgreSQL è `runtime`?** Il nostro codice non deve mai usare direttamente le classi del driver: parla con JPA e JDBC, che sono interfacce standard. Il driver è solo l'implementazione concreta, scelta all'avvio. Con lo scope `runtime`, se per errore importassi una classe del driver, la compilazione fallirebbe. È una protezione che costa zero.

`provided` lo incontravi quando le applicazioni si installavano in un application server (Tomcat, WebSphere, JBoss) che forniva già le librerie servlet. Con Spring Boot il server è incorporato nel jar, quindi è raro.

### 3.7 Ciclo di vita, fasi, plugin

Maven organizza la build in **fasi** ordinate. Quando chiedi una fase, Maven esegue **anche tutte quelle precedenti**.

```
validate → compile → test → package → verify → install → deploy
            │          │        │                  │
            │          │        │                  └─ copia il jar nel repository locale (.m2)
            │          │        └─ crea il jar in target/
            │          └─ esegue i test (src/test/java)
            └─ compila src/main/java in target/classes
```

(Le fasi reali sono di più, es. `process-resources` prima di `compile`, ma queste sono quelle da ricordare.)

Quindi:
- `.\mvnw.cmd compile` → compila e basta;
- `.\mvnw.cmd test` → compila ed esegue i test;
- `.\mvnw.cmd package` → compila, testa, crea il jar;
- `.\mvnw.cmd verify` → come package, più i controlli dei test di integrazione. È quello che userà la pipeline di GitHub Actions al passo 14.

**Chi fa il lavoro?** Maven da solo non sa fare niente: ogni lavoro lo fa un **plugin**. A ogni fase sono collegati uno o più *goal* di plugin: `compile` → `maven-compiler-plugin`, `test` → `maven-surefire-plugin`, ecc.

Si può anche lanciare un goal direttamente, senza passare dalle fasi: `.\mvnw.cmd spring-boot:run` esegue il goal `run` del plugin `spring-boot`.

**Nel nostro pom c'è un solo plugin dichiarato**, `spring-boot-maven-plugin`. Fa due cose:
- in fase `package` **riconfeziona** il jar in un *fat jar* (o *executable jar*): un unico file con dentro il nostro codice **e tutte le dipendenze**, avviabile con `java -jar booking.jar`. È quello che metteremo nel container Docker.
- aggiunge goal come `spring-boot:run` (avvia l'applicazione) e `spring-boot:build-image`.

**Cosa è successo quando hai lanciato `.\mvnw.cmd compile`:**
1. `mvnw.cmd` ha letto `.mvn/wrapper/maven-wrapper.properties` e scaricato Maven (vedi 3.8).
2. Maven ha letto `pom.xml`, scaricato il parent e tutte le dipendenze in `.m2\repository`.
3. Fase `process-resources`: ha copiato `src/main/resources` in `target/classes`.
4. Fase `compile`: ha compilato `src/main/java` in `target/classes`, producendo i `.class`.

### 3.8 Il Maven Wrapper

**Problema:** se ognuno ha una versione diversa di Maven installata, la stessa build può comportarsi in modo diverso su computer diversi. E chi arriva nuovo deve prima installare Maven.

**Soluzione:** il **Wrapper**. Sono pochi file nel repository:

| File | Cosa fa |
|---|---|
| `mvnw` | Script per Linux/Mac (e per Docker, al passo 14) |
| `mvnw.cmd` | Script per Windows |
| `.mvn/wrapper/maven-wrapper.properties` | Dice **quale versione** di Maven usare e da dove scaricarla |

Al primo avvio lo script scarica quella versione esatta di Maven in `C:\Users\Principale\.m2\wrapper\` e la usa. Dal secondo avvio la trova già lì.

Nel nostro file c'è `distributionType=only-script`: il Wrapper è fatto solo dagli script, senza un piccolo `.jar` di supporto che le versioni vecchie mettevano nel repository. Meno file binari versionati.

Risultato: **chiunque cloni il repository compila con la stessa versione di Maven**, senza installare nulla tranne il JDK. Vale anche per il server di GitHub Actions.

> **Verifica 3:** Che differenza c'è tra mettere una libreria in `<dependencies>` e metterla in `<dependencyManagement>`? E perché nel nostro pom non scriviamo mai le versioni?

---

## 4. Spring Boot

### 4.1 Ripasso: Spring Framework

Spring Framework è, al suo cuore, un **contenitore IoC** (*Inversion of Control*). Invece di creare tu gli oggetti con `new` e collegarli a mano, li dichiari, e Spring:
- li **crea** (si chiamano **bean**);
- li **collega** tra loro: se `BookingService` ha bisogno di un `BookingRepository`, Spring glielo passa nel costruttore. È la **dependency injection**;
- ne gestisce il **ciclo di vita** (creazione, chiusura).

L'insieme dei bean e la loro configurazione vivono nell'**ApplicationContext**.

Tu marchi le classi con `@Component`, `@Service`, `@Repository`, `@Controller`, oppure scrivi metodi `@Bean` in una classe `@Configuration`.

### 4.2 Cosa aggiunge Spring Boot

Spring Framework da solo richiede molta configurazione: definire la `DataSource`, configurare Hibernate, il server web, Jackson... Spring Boot **la fa al posto tuo** con valori ragionevoli, e ti lascia cambiare solo quello che ti serve.

Tre idee:
1. **Starter**: dipendenze "pacchetto" (4.4).
2. **Auto-configurazione**: Spring Boot guarda cosa hai nel progetto e configura di conseguenza (4.5).
3. **Server incorporato**: Tomcat è dentro l'applicazione. Non installi l'applicazione in un server: l'applicazione **è** il server. Si avvia con `java -jar`.

### 4.3 `BookingApplication`, riga per riga

```java
package com.posturestudio.booking;

@SpringBootApplication
public class BookingApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookingApplication.class, args);
    }
}
```

**`@SpringBootApplication`** è un'abbreviazione di tre annotazioni:

| Annotazione | Cosa fa |
|---|---|
| `@SpringBootConfiguration` | Questa classe è una classe di configurazione (può contenere metodi `@Bean`) |
| `@EnableAutoConfiguration` | Attiva l'auto-configurazione (4.5) |
| `@ComponentScan` | Cerca le classi annotate (`@Service`, `@Repository`...) **in questo package e in tutti i sotto-package** |

L'ultima riga è importante per la struttura del progetto: la classe principale sta in `com.posturestudio.booking`, e **tutti i moduli devono stare sotto quel package** (`com.posturestudio.booking.auth`, `...booking.customer`). Una classe messa in `com.posturestudio.altro` non verrebbe trovata, e Spring non creerebbe il suo bean. È un errore classico.

**`SpringApplication.run(...)`** all'avvio:
1. legge la configurazione (`application.properties`, variabili d'ambiente, argomenti);
2. crea l'`ApplicationContext`;
3. esegue la scansione dei componenti e l'auto-configurazione, creando tutti i bean;
4. (nel nostro caso) esegue le migrazioni Flyway, prepara Hibernate, avvia Tomcat sulla porta 8080.

### 4.4 Gli starter

Uno **starter** è una dipendenza che non contiene quasi codice: serve a portarne dentro altre, già scelte e compatibili, più la loro auto-configurazione. Invece di elencare Spring MVC, Tomcat, Jackson e le loro versioni, scrivi una riga sola.

| Starter nel nostro pom | Cosa porta |
|---|---|
| `spring-boot-starter-webmvc` | Spring MVC (controller REST), Tomcat incorporato, Jackson (conversione Java ↔ JSON) |
| `spring-boot-starter-data-jpa` | Hibernate (l'implementazione di JPA), Spring Data JPA (i repository), HikariCP (pool di connessioni) |
| `spring-boot-starter-flyway` | Flyway (migrazioni del database) e la sua auto-configurazione |
| `spring-boot-starter-validation` | Hibernate Validator: `@NotNull`, `@Email`, `@Size` sugli oggetti in ingresso |
| `spring-boot-starter-*-test` | Strumenti di test per ogni tecnologia, più JUnit, AssertJ, Mockito |
| `spring-boot-testcontainers` | Il collegamento tra Spring Boot e Testcontainers (`@ServiceConnection`) |

Altre dipendenze nel pom che non sono starter:
- `flyway-database-postgresql`: da Flyway 10, il supporto per ogni database è un modulo separato. Senza, Flyway non sa parlare con PostgreSQL;
- `postgresql`: il driver JDBC;
- `testcontainers-junit-jupiter` e `testcontainers-postgresql`: Testcontainers e il suo modulo PostgreSQL.

### 4.5 L'auto-configurazione

È il "trucco" di Spring Boot, e una domanda da colloquio quasi certa.

Spring Boot contiene centinaia di **classi di auto-configurazione**, ognuna capace di configurare una tecnologia. Ognuna ha delle **condizioni**: si attiva solo se sono vere.

| Condizione | Significato |
|---|---|
| `@ConditionalOnClass(X.class)` | Solo se la classe X è presente nel progetto (cioè la libreria è tra le dipendenze) |
| `@ConditionalOnMissingBean` | Solo se **tu** non hai già definito un bean di quel tipo |
| `@ConditionalOnProperty` | Solo se una certa proprietà ha un certo valore |

Esempio (semplificato) di cosa succede nel nostro progetto:

```
C'è un driver JDBC tra le dipendenze?          sì
Hai definito tu una DataSource?                no
   → Spring Boot crea una DataSource (HikariCP) leggendo spring.datasource.url, username, password

C'è Flyway tra le dipendenze? C'è una DataSource?   sì, sì
   → all'avvio Spring Boot esegue le migrazioni in classpath:db/migration

C'è Hibernate? C'è una DataSource?                   sì, sì
   → Spring Boot crea l'EntityManagerFactory e i repository JPA
```

Due conseguenze importanti:
- **Aggiungere una dipendenza cambia il comportamento dell'applicazione**, anche senza scrivere codice. È comodo, ma bisogna sapere cosa si aggiunge.
- **Tu hai sempre l'ultima parola** (`@ConditionalOnMissingBean`): se definisci un tuo bean, Spring Boot si fa da parte. Si dice che l'auto-configurazione fa *back-off*.

**Come vedere cosa è stato configurato e perché:** avvia con `debug=true` in `application.properties`, oppure con l'argomento `--debug`. Nel log compare il *Conditions Evaluation Report*: per ogni auto-configurazione, se si è attivata e quale condizione l'ha decisa.

> **Esercizio consigliato, adesso:** da `backend/` lancia `.\mvnw.cmd spring-boot:run`. L'applicazione **non parte**, e l'errore è istruttivo: Spring Boot vede il driver PostgreSQL, quindi prova a creare una `DataSource`, ma non trova l'indirizzo del database. Leggi il messaggio e collegalo a questo capitolo. È esattamente il problema che risolverai al punto 4 del brief (configurazione del backend).

### 4.6 La configurazione: `application.properties` e l'ambiente

Oggi `application.properties` contiene una sola riga:

```properties
spring.application.name=booking
```

Spring Boot può leggere la configurazione da molte fonti, con un **ordine di precedenza**: alcune sovrascrivono le altre. Semplificando, dalla più debole alla più forte:

```
valori di default di Spring Boot
   < application.properties dentro il jar
      < application.properties fuori dal jar
         < variabili d'ambiente
            < argomenti da riga di comando (--server.port=9090)
```

Questo permette di avere **un solo jar** che gira in locale, nei test e in produzione: cambia solo l'ambiente intorno. Le password non sono mai nel file, arrivano dall'ambiente.

Da studiare nella documentazione (link in fondo, *Externalized Configuration*), perché ti servirà per il punto 4 del brief:
- come si fa riferimento a una variabile d'ambiente dentro `application.properties`;
- come Spring Boot collega da solo il nome di una variabile d'ambiente a una proprietà (*relaxed binding*).

> **Verifica 4:** Aggiungi al progetto una libreria e, senza scrivere una riga di codice, l'applicazione si comporta diversamente. Spiega il meccanismo. E come fai a sapere cosa ha deciso Spring Boot?

---

## 5. Da Spring Boot 2 a Spring Boot 4

Tu hai lavorato con la 2.x. In mezzo ci sono due versioni principali. Ecco cosa cambia per noi, in ordine di importanza pratica.

### 5.1 Boot 3.0 (novembre 2022): il salto grande

**`javax.*` → `jakarta.*`.** Le specifiche "enterprise" di Java (JPA, Bean Validation, Servlet...) sono passate da Oracle alla Eclipse Foundation, che non aveva il diritto di usare il nome `javax`. Quindi i package sono stati rinominati:

| Prima (Boot 2) | Ora (Boot 3 e 4) |
|---|---|
| `javax.persistence.Entity` | `jakarta.persistence.Entity` |
| `javax.validation.constraints.NotNull` | `jakarta.validation.constraints.NotNull` |
| `javax.servlet.http.HttpServletRequest` | `jakarta.servlet.http.HttpServletRequest` |
| `javax.transaction.Transactional` | `jakarta.transaction.Transactional` (ma in Spring si usa `org.springframework.transaction.annotation.Transactional`) |

È **l'errore più comune** quando si copia codice da un tutorial vecchio: "cannot find symbol javax.persistence...". Se vedi `javax.persistence`, l'esempio è pre-2022.

Attenzione: non tutto `javax` è cambiato. Le parti che fanno parte di Java stesso (`javax.sql.DataSource`, `javax.crypto`) restano `javax`.

Altre novità di Boot 3.0:
- **Java 17 minimo**;
- **Hibernate 6**: cambi nella generazione degli id e nelle query;
- **Spring Security 6**: non esiste più `WebSecurityConfigurerAdapter`. Si dichiara un bean `SecurityFilterChain`. Lo vedremo al passo 8;
- supporto agli errori in formato **Problem Details** (classe `ProblemDetail`), che useremo per le risposte di errore delle API;
- *observability* con Micrometer, e compilazione nativa con GraalVM (non ci servono).

### 5.2 Boot 3.1 e 3.2

- **3.1 — `@ServiceConnection`.** Con Testcontainers dichiari il container, e Spring Boot ricava da solo indirizzo, utente e password del database. In Boot 2 dovevi scrivere a mano un metodo con `@DynamicPropertySource`. Lo userai nel test di questo passo.
- **3.2 — virtual thread** attivabili con una proprietà; **`RestClient`** e **`JdbcClient`**, versioni moderne di `RestTemplate` e `JdbcTemplate`.

### 5.3 Boot 4.0 (novembre 2025)

Spring Framework 7, Jakarta EE 11, Hibernate 7. La novità che ti tocca subito è la **modularizzazione**.

In Boot 2 e 3 tutta l'auto-configurazione stava in un unico grande jar (`spring-boot-autoconfigure`) che "sapeva" configurare qualsiasi libreria trovasse nel progetto. In Boot 4 è divisa in **moduli, uno per tecnologia**, ognuno con il suo starter.

Conseguenze concrete:

| Boot 2 | Boot 4 |
|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| Aggiungevi `flyway-core` e Flyway partiva da solo | Serve **`spring-boot-starter-flyway`**. Con solo `flyway-core` Flyway **non parte e nessun messaggio te lo segnala**: le tabelle non vengono create |
| Un solo `spring-boot-starter-test` | Uno starter di test per tecnologia (`spring-boot-starter-data-jpa-test`, ...) |

Altre novità:
- **Jackson 3**: i package passano da `com.fasterxml.jackson` a `tools.jackson`. Le annotazioni come `@JsonProperty` restano dov'erano;
- **Testcontainers 2**: artifact con prefisso `testcontainers-` e classi spostate (es. `org.testcontainers.postgresql.PostgreSQLContainer`);
- annotazioni **JSpecify** (`@Nullable`, `@NullMarked`) in tutto il framework, per dichiarare cosa può essere `null`.

> **Verifica 5:** Trovi su internet un esempio con `import javax.persistence.Id;`. Cosa ti dice sull'età dell'esempio, e cosa cambi?

---

## 6. I moduli e i package

### 6.1 Monolite modulare (D-002)

Il backend è **una sola applicazione**, divisa in **moduli**: uno per area del dominio (`auth`, `customer`, `practitioner`, poi `booking`, `credit`...). Ogni modulo è un package Java.

Regola: **un modulo non legge le tabelle di un altro**, ma chiama il suo servizio pubblico. Esempio futuro: `booking` non usa `PractitionerRepository`, ma chiede a `PractitionerService`.

### 6.2 Package per area, non per livello

Ci sono due modi classici di organizzare i package:

```
Per livello (package-by-layer)        Per area (package-by-feature)  ← il nostro
├── controller/                       ├── auth/
│   ├── BookingController             │   ├── AppUser
│   └── CustomerController            │   ├── AppUserRepository
├── service/                          │   └── AuthService
│   ├── BookingService                ├── booking/
│   └── CustomerService               │   ├── BookingController
└── repository/                       │   ├── BookingService
    ├── BookingRepository             │   └── BookingRepository
    └── CustomerRepository            └── customer/ ...
```

**Perché per area:**
- tutto ciò che riguarda le prenotazioni sta in un posto: modificare una funzione tocca una cartella sola;
- i confini tra moduli sono visibili;
- e soprattutto: si può usare la **visibilità di package** di Java. Se `BookingRepository` non è `public` (visibilità di package, senza modificatore), **il compilatore impedisce** a un altro modulo di usarlo. Solo il servizio è `public`. La regola di D-002 diventa un controllo automatico e non dipende solo dalla disciplina.

Con il package-by-layer questo è impossibile: il repository deve essere `public` per essere visto dal service, che sta in un altro package.

### 6.3 `package-info.java`

È un file speciale che Java permette in ogni package. Non contiene una classe: serve a
- scrivere la **documentazione del package** (Javadoc);
- applicare **annotazioni a tutto il package** in un colpo solo. Esempio: `@NullMarked` di JSpecify dice "in questo package niente è `null`, salvo dove scritto `@Nullable`".

Nel nostro progetto lo usiamo per descrivere ogni modulo e annotare i TODO. Ha anche un effetto pratico: git non salva cartelle vuote, e con questo file il package esiste nel repository.

> **Verifica 6:** Perché la classe `BookingApplication` deve stare nel package "più in alto" di tutti? Cosa succede a un `@Service` messo fuori da quel package?

---

## 7. Git e i file di servizio

### 7.1 `.gitignore`

Elenca i file che git deve **ignorare**: non li propone per il commit e non li mostra in `git status`.

```gitignore
# il file con i segreti veri
.env
# le sue varianti (.env.local, .env.prod)...
.env.*
# ...tranne questo: il "!" crea un'eccezione
!.env.example
# tutto ciò che Maven produce
target/
# librerie del frontend (centinaia di MB, si riscaricano)
node_modules/
# impostazioni personali degli IDE
.idea/
```

Regole da ricordare:
- `#` è un commento **solo a inizio riga**. Scritto dopo un nome (`.env  # segreti`), diventa parte del nome da ignorare e la regola non funziona più.
- `target/` con la barra finale significa "una **cartella** che si chiama target, a qualsiasi livello".
- L'ordine conta: la regola con `!` deve venire **dopo** quella che esclude.
- **`.gitignore` non ha effetto su file già versionati.** Se un file è già stato committato, aggiungerlo a `.gitignore` non basta: va tolto con `git rm --cached nomefile`. Con i segreti è grave: se una password finisce in un commit e il repository è pubblico, va considerata compromessa e cambiata, anche se poi il file viene cancellato. La storia di git la conserva.

**Cosa si versiona e cosa no:** si versiona tutto ciò che serve a ricostruire il progetto (sorgenti, configurazione, script). Non si versiona ciò che **si rigenera** (`target/`, `node_modules/`), ciò che è **personale** (impostazioni dell'IDE) e ciò che è **segreto** (`.env`).

### 7.2 `.env` e `.env.example`

Il repository è **pubblico** (D-016): chiunque può leggerlo. Le password non possono stare lì.

La soluzione, molto diffusa:
- **`.env`**: un file con le variabili d'ambiente vere (`POSTGRES_PASSWORD=...`). Sta solo sul tuo computer, o sul server. È in `.gitignore`.
- **`.env.example`**: stessa struttura, valori finti. È versionato. Serve a far sapere a chi arriva **quali** variabili servono.

Per partire: copi `.env.example` in `.env` e metti i valori veri.

Il principio dietro è uno dei *Twelve-Factor App* (link in fondo): **la configurazione che cambia tra un ambiente e l'altro sta nell'ambiente, non nel codice**. Lo stesso codice gira in locale e in produzione: cambiano solo le variabili.

Il file `.env` è un **formato**, non una magia: qualcuno lo deve leggere. Docker Compose lo legge in automatico (dalla sua *project directory*). Spring Boot di suo no. Come far arrivare quei valori al backend è una delle cose da decidere al punto 4 del brief.

### 7.3 `.gitattributes` e i fine riga

Un dettaglio che fa perdere ore, la prima volta.

Il "a capo" alla fine di ogni riga di un file di testo è scritto in modo diverso:
- **Windows**: due caratteri, `CR LF` (`\r\n`);
- **Linux / Mac**: un carattere, `LF` (`\n`).

Git su Windows di solito converte automaticamente. Per la maggior parte dei file non importa. Per gli **script** sì: `mvnw` è uno script shell che girerà su Linux (dentro Docker, al passo 14, e su GitHub Actions). Se arriva con `CR LF`, Linux legge la prima riga come `#!/bin/sh\r` e si rifiuta di eseguirlo, con un errore incomprensibile come `/bin/sh^M: bad interpreter`.

`backend/.gitattributes` lo impedisce:

```gitattributes
/mvnw text eol=lf
*.cmd text eol=crlf
```

Prima riga: `mvnw` ha sempre fine riga LF, anche quando lo scarichi su Windows. Seconda riga: gli script Windows (`.cmd`) hanno sempre CRLF.

### 7.4 `.gitkeep`

Git versiona **file**, non cartelle: una cartella vuota non esiste per git. `.gitkeep` è un file vuoto messo lì solo per far esistere la cartella (`infra/`, `frontend/`, `db/migration/`). Non è una funzione di git, è solo una convenzione: il nome è scelto per farsi capire. Quando nella cartella arriva un file vero, `.gitkeep` si può cancellare.

> **Verifica 7:** Hai committato per errore `.env` con la password del database, poi l'hai aggiunto a `.gitignore` e cancellato. Il repository è pubblico. Sei a posto? Cosa devi fare?

---

## 8. Glossario

| Termine | Significato |
|---|---|
| **ApplicationContext** | Il contenitore di Spring che crea e conserva tutti i bean |
| **Artifact** | Un pacchetto identificato da coordinate Maven (di solito un jar) |
| **Auto-configurazione** | Configurazione che Spring Boot applica da solo in base alle dipendenze presenti e ai bean già definiti |
| **Back-off** | Quando l'auto-configurazione si fa da parte perché hai definito tu il bean |
| **Bean** | Un oggetto creato e gestito da Spring |
| **BOM** | *Bill of Materials*: elenco di versioni di librerie compatibili tra loro |
| **Build tool** | Strumento che compila, testa e impacchetta (Maven, Gradle) |
| **Bytecode** | Il codice compilato (`.class`) che la JVM esegue |
| **Classpath** | L'insieme di classi e librerie visibili all'applicazione quando gira |
| **Component scan** | La ricerca automatica delle classi annotate (`@Service`, ...) nei package |
| **Coordinate** | `groupId:artifactId:version`, l'indirizzo di una libreria |
| **Dependency injection** | Spring passa a un oggetto le dipendenze di cui ha bisogno, invece che sia l'oggetto a crearle |
| **Dipendenza transitiva** | Una libreria che entra nel progetto perché è dipendenza di un'altra |
| **Fat jar** | Jar che contiene anche tutte le dipendenze, avviabile con `java -jar` |
| **Fase (Maven)** | Un passaggio del ciclo di vita (`compile`, `test`, `package`...) |
| **Goal (Maven)** | Un'azione di un plugin (`spring-boot:run`) |
| **IoC** | *Inversion of Control*: il framework crea e collega gli oggetti, non il tuo codice |
| **JAVA_HOME** | Variabile d'ambiente che indica ai programmi dov'è installato il JDK |
| **JDK / JRE / JVM** | Kit di sviluppo / ambiente di esecuzione / macchina virtuale (cap. 2.1) |
| **LTS** | *Long Term Support*: versione supportata a lungo |
| **Parent (pom)** | Pom da cui il nostro eredita configurazione e versioni |
| **PATH** | Variabile d'ambiente con l'elenco delle cartelle in cui cercare i comandi |
| **Plugin (Maven)** | Componente che fa il lavoro vero di una fase |
| **Repository locale** | La cache delle librerie scaricate, in `~/.m2/repository` |
| **Scope** | Quando serve una dipendenza: `compile`, `runtime`, `test`, `provided` |
| **SNAPSHOT** | Versione in sviluppo, che può cambiare |
| **Starter** | Dipendenza "pacchetto" di Spring Boot con librerie e auto-configurazione |
| **Variabile d'ambiente** | Coppia nome = valore che il sistema operativo passa ai programmi |
| **Wrapper (Maven)** | Script che scarica e usa una versione fissa di Maven |

---

## 9. Verifica finale

Prova a rispondere a voce, come in un colloquio, senza guardare sopra. Se ti blocchi, rileggi il capitolo indicato.

1. Cosa succede, passo per passo, quando lanci `.\mvnw.cmd package` su un computer che non ha mai compilato il progetto? *(cap. 3)*
2. Cos'è il parent `spring-boot-starter-parent` e quale problema risolve? *(3.5)*
3. Perché il driver di PostgreSQL ha scope `runtime`? *(3.6)*
4. Cosa fa `@SpringBootApplication`? *(4.3)*
5. Spiega l'auto-configurazione di Spring Boot. Come fai a sovrascriverla? *(4.5)*
6. Come porti la password del database all'applicazione senza scriverla nel repository? *(4.6, 7.2)*
7. Cosa è cambiato tra Spring Boot 2 e 3? E con la 4? *(cap. 5)*
8. Package per area o per livello: quale preferisci e perché? *(6.2)*

---

## 10. Documentazione ufficiale

**Java**
- Eclipse Temurin: https://adoptium.net/

**Maven**
- Introduzione al ciclo di vita: https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html
- Meccanismo delle dipendenze (scope, transitive, dependency management): https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html
- Maven Wrapper: https://maven.apache.org/tools/wrapper/

**Spring Boot**
- Documentazione di riferimento: https://docs.spring.io/spring-boot/reference/
- Auto-configurazione: https://docs.spring.io/spring-boot/reference/using/auto-configuration.html
- Configurazione esterna (*Externalized Configuration*): https://docs.spring.io/spring-boot/reference/features/external-config.html
- Guida di migrazione a Boot 3.0: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide
- Guida di migrazione a Boot 4.0: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide

**Git**
- `.gitignore`: https://git-scm.com/docs/gitignore
- `.gitattributes`: https://git-scm.com/docs/gitattributes

**Principi**
- The Twelve-Factor App, configurazione: https://12factor.net/config
