# PostgreSQL in Docker con Compose — guida di studio

Questa guida accompagna il punto 3 del passo 4: `infra/docker-compose.dev.yml`, il file che avvia PostgreSQL sul tuo computer.

Contiene:
- **la parte 1**: i concetti di Docker e Docker Compose che servono, dalla base;
- **la parte 2**: le risposte complete alle quattro domande, con il ragionamento;
- **la parte 3**: la sintassi YAML, mostrata su un esempio **diverso** (MongoDB), più l'elenco di cosa deve contenere il nostro file. Il file lo scrivi tu, traducendo le decisioni della parte 2;
- **la parte 4**: come verificare che funzioni, e gli errori tipici.

---

## Indice

1. [I concetti](#parte-1--i-concetti)
2. [Le quattro domande, risolte](#parte-2--le-quattro-domande-risolte)
3. [La sintassi, su un esempio diverso](#parte-3--la-sintassi-su-un-esempio-diverso)
4. [Verifica ed errori tipici](#parte-4--verifica-ed-errori-tipici)
5. [Domande da colloquio](#domande-da-colloquio)
6. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — I concetti

### 1.1 Perché Docker, in locale

Senza Docker dovresti installare PostgreSQL su Windows: un servizio sempre acceso, una versione legata al tuo computer, difficile da cancellare e ricreare. Con Docker:
- PostgreSQL gira **isolato** in un container, con la **versione esatta** che userà la produzione;
- si avvia e si spegne con un comando;
- si cancella e si ricrea da zero in pochi secondi (utile quando una migrazione va storta);
- chi clona il repository ottiene **lo stesso database**, con lo stesso file.

### 1.2 Immagine e container

L'analogia per un programmatore Java:

| Docker | Java |
|---|---|
| **Immagine** | Una **classe**: una definizione, che non cambia |
| **Container** | Un'**istanza** (`new`): un oggetto vivo creato da quella classe |

- Un'**immagine** è un pacchetto in sola lettura: un mini sistema operativo Linux, PostgreSQL già installato, e le istruzioni per avviarlo. Le immagini si scaricano da un registro, di solito **Docker Hub** (`hub.docker.com`).
- Un **container** è un'immagine in esecuzione. Dalla stessa immagine puoi avviare più container indipendenti.

### 1.3 Il tag: la versione dell'immagine

Un'immagine si indica con `nome:tag`. Il **tag** è un'etichetta, di solito la versione:

| Tag | Cosa ottieni |
|---|---|
| `postgres:17.11` | Esattamente la 17.11 |
| `postgres:17` | L'ultima 17.x disponibile (oggi 17.11, domani magari 17.12) |
| `postgres:17-alpine` | La 17.x costruita su Alpine Linux, più piccola |
| `postgres:latest` (o solo `postgres`) | **L'ultima versione principale uscita**, qualunque sia |

Il tag è un'etichetta **mobile**: `postgres:17` oggi e fra tre mesi possono essere immagini diverse. Questo è importante per la domanda 1.

### 1.4 Il file system del container è usa e getta

Un container ha il suo file system, ma **quando il container viene cancellato, tutto quello che c'era dentro sparisce**. Per un database è un problema: i dati vivono proprio lì.

La soluzione sono i **volumi**: uno spazio di memoria **fuori** dal container, che Docker "monta" in una cartella del container. Il container scrive in quella cartella, ma i byte finiscono nel volume, che sopravvive al container.

```
┌──────────── container postgres ────────────┐
│                                            │
│  /usr/lib/postgresql/...   (dall'immagine) │
│  /var/lib/postgresql/data  ────────────────┼──► volume  (sopravvive)
│                                            │
└────────────────────────────────────────────┘
       cancelli il container → il volume resta
```

Esistono tre tipi di "spazio esterno":

| Tipo | Cos'è | Dove stanno i byte |
|---|---|---|
| **Named volume** | Un volume con un nome, gestito da Docker | Nell'area di Docker (su Windows: dentro la macchina virtuale di Docker Desktop) |
| **Bind mount** | Una cartella del tuo computer, collegata dentro il container | Nella cartella che scegli tu (es. `C:\...\pgdata`) |
| **Anonymous volume** | Un volume senza nome, creato in automatico | Nell'area di Docker, con un nome casuale |

### 1.5 La rete e le porte

Un container ha **la sua rete**, separata dal tuo computer. PostgreSQL dentro il container ascolta sulla porta 5432, ma **del container**: dal tuo computer non è raggiungibile.

Per raggiungerlo si **pubblica** una porta: si collega una porta del tuo computer (*host*) a una porta del container.

```
 il tuo computer (host)               container
   127.0.0.1:5432  ───────────────►  :5432  PostgreSQL
   (dove si collega Spring Boot)
```

La sintassi è `"HOST:CONTAINER"`, **prima la porta del tuo computer, poi quella del container**. Si sbaglia spesso.

Attenzione a `localhost`: dentro un container, `localhost` è il container stesso, non il tuo computer. Per ora non ci tocca (il backend gira dall'IDE, fuori da Docker), ma in produzione, dove anche il backend sarà un container, il database si raggiungerà con il **nome del servizio** (es. `postgres:5432`), non con `localhost`.

### 1.6 Le variabili d'ambiente del container

Molte immagini si configurano con variabili d'ambiente (le stesse della guida 01, cap. 2.4, ma passate **dentro** il container). L'immagine `postgres` ne legge alcune **al primo avvio**:

| Variabile | Effetto | Default |
|---|---|---|
| `POSTGRES_PASSWORD` | Password dell'utente amministratore | **obbligatoria**: senza, il container non parte |
| `POSTGRES_USER` | Nome dell'utente amministratore | `postgres` |
| `POSTGRES_DB` | Nome del database creato all'inizio | uguale a `POSTGRES_USER` |
| `PGDATA` | Cartella dei dati dentro il container | dipende dalla versione (vedi domanda 2) |

**Dettaglio cruciale: "al primo avvio".** Lo script dell'immagine crea utente, password e database **solo se la cartella dei dati è vuota**. Se il volume contiene già un database, quelle variabili vengono **ignorate**. Conseguenza: se cambi la password nel `.env` dopo il primo avvio, PostgreSQL continua ad avere quella vecchia, e il backend non riesce a collegarsi. Si risolve cambiando la password con un comando SQL (`ALTER USER ... PASSWORD ...`) oppure cancellando il volume (e i dati) per ripartire da zero.

### 1.7 Docker Compose

Con il solo Docker, avviare PostgreSQL vorrebbe dire scrivere ogni volta un comando lunghissimo (`docker run -e ... -p ... -v ... postgres:17`). **Docker Compose** permette di scrivere tutto in un file YAML e avviarlo con un comando corto. Il file è:
- **dichiarativo**: descrivi **come deve essere** il risultato, non i passi per arrivarci;
- **versionato**: sta nel repository, quindi è documentazione che funziona.

Vocabolario:

| Termine | Significato |
|---|---|
| **Servizio** (*service*) | Un container descritto nel file, con un nome (es. `postgres`) |
| **Progetto** (*project*) | L'insieme di servizi, volumi e reti di un file Compose. Ha un nome, usato come prefisso per tutto ciò che crea |
| **Project directory** | La cartella di riferimento del progetto. Di default è **quella del file compose** (per noi `infra/`). Da lì Compose cerca il `.env` e risolve i percorsi relativi |

I comandi che userai:

| Comando | Cosa fa |
|---|---|
| `docker compose -f FILE up -d` | Crea e avvia i servizi. `-d` (*detached*) = in background, ti restituisce il terminale |
| `docker compose -f FILE ps` | Mostra i container del progetto e il loro stato |
| `docker compose -f FILE logs -f postgres` | Mostra i log del servizio (`-f` = continua a seguirli) |
| `docker compose -f FILE stop` | Ferma i container senza cancellarli |
| `docker compose -f FILE down` | Ferma **e cancella** container e reti. I volumi restano |
| `docker compose -f FILE down -v` | Come sopra **e cancella anche i volumi: i dati sono persi** |
| `docker exec -it CONTAINER comando` | Esegue un comando dentro un container in esecuzione (`-it` = interattivo, con terminale) |

> **Attenzione ai tutorial vecchi:** molti file Compose iniziano con `version: "3.8"`. È una riga **obsoleta**: con Compose v2 (quello installato con Docker Desktop) non serve, e produce un avviso. Non metterla.

### 1.8 Su Windows c'è una macchina virtuale in mezzo

I container sono una tecnologia Linux. Docker Desktop su Windows fa girare una piccola **macchina virtuale Linux** (tramite WSL2) e i container vivono lì dentro. Ti serve saperlo per capire la risposta alla domanda 2: una cartella di Windows "vista" da un container passa attraverso uno strato di traduzione tra due file system diversi.

---

## Parte 2 — Le quattro domande, risolte

### Domanda 1 — Quale tag? Perché non `latest`?

**Risposta: `postgres:17`.**

**Perché non `latest`.**

1. **Parità con la produzione.** In produzione useremo PostgreSQL 17 (architettura). Se in locale gira un'altra versione, una funzione SQL o un comportamento che funziona sul tuo computer può non esistere o comportarsi diversamente in produzione. Lo sviluppo deve rispecchiare la produzione.
2. **Il formato dei dati cambia tra versioni principali.** È il motivo più concreto. I file di un database creato con PostgreSQL 17 **non possono essere aperti** da PostgreSQL 18 (o viceversa) senza una procedura di aggiornamento (`pg_upgrade` o dump e ripristino). Con `latest`, il giorno in cui Docker scarica una nuova versione principale, il container non parte più sul tuo volume esistente. Il problema non nasce da una tua modifica, ma da un aggiornamento esterno, ed è difficile da capire. Già oggi `latest` non punta più alla 17.
3. **Riproducibilità.** Il file deve dare lo stesso risultato oggi, tra sei mesi e sul computer di un altro. `latest` cambia significato nel tempo.

**Perché `17` e non `17.11`.** All'interno della stessa versione principale, gli aggiornamenti *minor* (17.10 → 17.11) contengono solo correzioni di bug e di sicurezza, e **il formato dei dati non cambia**. Lasciare libero il numero minor in sviluppo è sicuro e ti dà le correzioni gratis. In produzione (passo 14) ragioneremo se fissare anche il minor, per controllare esattamente quando aggiornare.

**Perché non `17-alpine`.** È più piccola, ma Alpine usa una libreria di sistema diversa (musl invece di glibc), che può cambiare il modo in cui il testo viene ordinato e confrontato (le *collation*). Per un'applicazione italiana che ordina nomi con accenti, è meglio usare la stessa base in sviluppo e in produzione. Usiamo l'immagine standard (Debian) in entrambi.

### Domanda 2 — Dove stanno i dati? Named volume o bind mount?

**Risposta: un named volume, montato nella cartella dei dati di PostgreSQL 17, cioè `/var/lib/postgresql/data`.**

**Cosa succede senza volume.** L'immagine `postgres` dichiara al suo interno che la cartella dei dati deve stare in un volume. Se tu non ne indichi uno, Docker ne crea uno **anonimo**. Il risultato è subdolo:
- `docker compose down` cancella il container;
- `docker compose up` crea un container **nuovo**, che riceve un **nuovo** volume anonimo, vuoto;
- il database appare vuoto. I dati vecchi esistono ancora, in un volume orfano con un nome casuale, ma nessuno li usa più, e occupano spazio finché non li cancelli.

**Named volume contro bind mount, nel nostro caso:**

| | Named volume | Bind mount (cartella di Windows) |
|---|---|---|
| Prestazioni su Windows | Buone: i dati stanno nella macchina virtuale Linux | Lente: ogni lettura e scrittura attraversa lo strato tra Windows e Linux |
| Permessi | Nessun problema | PostgreSQL pretende che la cartella dei dati appartenga al suo utente con permessi stretti (`0700`). Sulle cartelle Windows questo spesso non è possibile, e il container può rifiutarsi di partire |
| Vedere i file | Non direttamente (ma non ti serve) | Sì, nel tuo file system |
| Rischio di versionarli per errore | Nessuno | La cartella sta vicino al repository: va messa in `.gitignore` |

I file interni di PostgreSQL non vanno mai letti o modificati a mano: per guardare i dati si usa SQL. Quindi il vantaggio del bind mount (vedere i file) non ci serve, e ci restano solo gli svantaggi. **Named volume.**

**Dove montarlo: attenzione alla versione.**
- Fino a PostgreSQL **17**, la cartella dei dati è `/var/lib/postgresql/data`. Il volume va montato lì.
- Da PostgreSQL **18** l'immagine ha cambiato organizzazione: i dati stanno in una sottocartella con il numero di versione e il volume va montato su `/var/lib/postgresql`.

Se monti il volume nel punto sbagliato, PostgreSQL scrive i dati in una cartella che non è nel volume, e torni al problema del volume anonimo: tutto sembra funzionare finché non cancelli il container. Per questo la pagina dell'immagine su Docker Hub va letta per la versione che usi. Lo verificherai nella parte 4 (test con `down` e `up`).

**Il nome del progetto.** Compose dà a ogni volume il prefisso del **nome del progetto**, che di default è il nome della cartella del file: `infra`. Il volume si chiamerebbe `infra_<nome>`, e il container `infra-postgres-1`. Funziona, ma `infra` è un nome generico: un altro progetto sul tuo computer con una cartella `infra` userebbe lo stesso prefisso e **gli stessi volumi**. Il file Compose permette di dichiarare il nome del progetto con la chiave `name:` in cima al file. Usane uno legato al progetto (es. `posture-studio`).

**`down` contro `down -v`.** Con il named volume, `down` è innocuo: i dati restano. `down -v` invece **cancella i volumi**: è il comando per "ripartire da zero", da usare sapendo che si perde tutto.

### Domanda 3 — Come arrivano utente e password, senza scriverli nel file?

**Risposta: nel file si scrivono dei riferimenti `${NOME_VARIABILE}`, che Compose sostituisce leggendo il `.env` nella radice del repository. A Compose si dice dove sta il `.env` con l'opzione `--env-file`.**

Qui ci sono **due meccanismi diversi**, che è facile confondere:

**Meccanismo A — l'*interpolazione*.** Prima di fare qualsiasi cosa, Compose legge il file YAML e sostituisce ogni `${NOME}` con il valore della variabile. Questo succede **sul tuo computer**, dentro Compose. I valori li cerca:
1. nelle variabili d'ambiente del terminale;
2. nel file `.env` della *project directory* (per noi `infra/`), oppure in quello indicato con `--env-file`.

**Meccanismo B — l'*ambiente del container*.** La sezione `environment:` di un servizio elenca le variabili che **il container** riceverà. Sono quelle che legge lo script di avvio di PostgreSQL.

Si usano **insieme**: in `environment:` scrivi che il container deve ricevere `POSTGRES_PASSWORD`, e come valore metti `${POSTGRES_PASSWORD}`. Compose sostituisce il riferimento con il valore letto dal `.env` (A) e lo passa al container (B). Nel file versionato c'è solo il riferimento, mai la password.

```
.env (radice, non versionato)          docker-compose.dev.yml (versionato)        container
POSTGRES_PASSWORD=la-mia-pw   ──A──►   POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}  ──B──►  POSTGRES_PASSWORD=la-mia-pw
```

**Il problema della cartella.** Compose cerca il `.env` nella project directory, cioè `infra/`. Il nostro `.env` sta nella radice. Le soluzioni possibili:

| Soluzione | Valutazione |
|---|---|
| `--env-file .env` nel comando (lanciato dalla radice) | ✅ **La nostra scelta.** Esplicito, un solo `.env` per tutto il progetto |
| `--project-directory .` nel comando | Funziona, ma cambia anche il punto di riferimento di tutti i percorsi relativi del file: più confuso |
| Spostare `.env` in `infra/` | Avremmo due posti per i segreti quando il backend ne avrà bisogno |
| `env_file: ../.env` nel servizio | ❌ Sembra comodo, ma è il **meccanismo B**, non A: passa al container **tutte** le variabili del `.env`. Quando il `.env` conterrà anche le chiavi di Stripe e del servizio email, il container di PostgreSQL le riceverebbe tutte senza motivo. Principio del *minimo privilegio*: ogni componente riceve solo i segreti che gli servono. In più, non serve per l'interpolazione (es. la porta) |

**Il comando completo**, lanciato dalla radice del repository:

```powershell
docker compose -f infra/docker-compose.dev.yml --env-file .env up -d
```

È lungo, ma lo scriverai poche volte; più avanti potremo aggiungere uno script. E vale per **tutti** i comandi (`ps`, `logs`, `down`): se in uno dimentichi `--env-file`, Compose non trova le variabili. Con la protezione descritta sotto, almeno, te lo dice chiaramente.

**Due varianti della sintassi da conoscere:**

| Sintassi | Significato | Quando usarla |
|---|---|---|
| `${NOME:?messaggio}` | Se `NOME` non è definita o è vuota, Compose **si ferma** con quel messaggio | Per le variabili obbligatorie, come la password: meglio un errore chiaro subito che un container con configurazione sbagliata |
| `${NOME:-valore}` | Se `NOME` non è definita o è vuota, usa `valore` | Per le variabili con un default sensato, come la porta (`5432`) |

Senza nessuna delle due, una variabile mancante diventa una **stringa vuota** con un semplice avviso, facile da non notare.

### Domanda 4 — Porta raggiungibile da tutta la rete o solo dal tuo computer?

**Risposta: solo dal tuo computer, pubblicando la porta su `127.0.0.1`.**

Se scrivi solo `"5432:5432"`, Docker ascolta su **tutte** le interfacce di rete del computer (`0.0.0.0`): chiunque sia sulla tua stessa rete (il Wi-Fi di casa, di un bar, di un coworking) può tentare di collegarsi al tuo database. In sviluppo la password è spesso debole, e i dati possono essere copie di dati veri.

Scrivendo `"127.0.0.1:5432:5432"`, la porta è raggiungibile **solo dal tuo computer**: lo `127.0.0.1` è l'indirizzo che vuol dire "me stesso". Spring Boot, che gira sul tuo computer, si collega senza problemi.

**Variabile per la porta del tuo computer.** Se un giorno avessi già un altro PostgreSQL sulla 5432 (installato su Windows o di un altro progetto), il container non partirebbe ("port is already allocated"). Per questo `.env.example` ha `POSTGRES_PORT`: si usa per la porta **del tuo computer**, con default 5432. La porta **del container** resta sempre 5432, fissa, perché è quella su cui PostgreSQL ascolta dentro il container.

**Collegamento con la produzione (passo 14).** In produzione la porta di PostgreSQL **non si pubblica affatto**: il backend la raggiunge sulla rete interna di Docker, e da internet il database non è visibile (architettura: "solo Caddy è esposto"). È importante anche per un motivo meno noto: su Linux, le porte pubblicate da Docker **scavalcano il firewall `ufw`**, perché Docker scrive le sue regole di rete direttamente. Un `"5432:5432"` dimenticato su un server esporrebbe il database a internet anche con il firewall attivo. È un errore reale e frequente.

---

## Parte 3 — La sintassi, su un esempio diverso

Ecco un file Compose per **MongoDB** (un altro database, che non useremo). Usa tutti gli elementi che ti servono. Leggilo, capisci ogni riga, poi scrivi il nostro.

```yaml
# Nome del progetto: prefisso di container, volumi e reti
name: inventory-app

services:
  mongo:                               # nome del servizio (lo scegli tu)
    image: mongo:8.0                   # immagine:tag
    environment:                       # variabili passate DENTRO il container
      MONGO_INITDB_ROOT_USERNAME: ${MONGO_USER:?MONGO_USER is required}
      MONGO_INITDB_ROOT_PASSWORD: ${MONGO_PASSWORD:?MONGO_PASSWORD is required}
    ports:
      - "127.0.0.1:${MONGO_PORT:-27017}:27017"   # IP:porta-host:porta-container
    volumes:
      - mongo-data:/data/db            # nome-volume:cartella-nel-container

volumes:                               # dichiarazione dei named volume usati sopra
  mongo-data:
```

Note sulla sintassi YAML:
- l'**indentazione** definisce la struttura (come le parentesi graffe in Java). Si usano **spazi, mai tab**;
- `chiave: valore` è una coppia; il trattino `-` è un elemento di una lista;
- le porte vanno **tra virgolette**: certi numeri con i due punti verrebbero altrimenti interpretati da YAML in modi strani (un retaggio della specifica YAML 1.1, che leggeva `22:22` come un numero in base 60);
- un named volume va usato nel servizio **e** dichiarato nella sezione `volumes:` in fondo.

### Cosa deve contenere il nostro `infra/docker-compose.dev.yml`

Traducendo le risposte della parte 2:

- [ ] Nessuna riga `version:`
- [ ] Nome del progetto legato allo studio (domanda 2)
- [ ] Un servizio per PostgreSQL, con immagine `postgres:17` (domanda 1)
- [ ] Le tre variabili `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` nell'ambiente del container, con valori presi dal `.env` per interpolazione; quelle obbligatorie con la sintassi che blocca se mancano (domanda 3)
- [ ] La porta pubblicata solo su `127.0.0.1`, porta host da `POSTGRES_PORT` con default 5432, porta container 5432 (domanda 4)
- [ ] Un named volume montato sulla cartella dei dati **della versione 17** (domanda 2), dichiarato nella sezione `volumes:`
- [ ] Nessuna password scritta nel file

Facoltativo, ma utile da conoscere: un **healthcheck**, cioè un comando che Docker esegue periodicamente per sapere se il servizio è davvero pronto, non solo avviato. Per PostgreSQL si usa `pg_isready`. Ci servirà in produzione, dove il backend dovrà aspettare che il database sia pronto prima di partire. Documentazione: https://docs.docker.com/reference/compose-file/services/#healthcheck

---

## Parte 4 — Verifica ed errori tipici

### La prova completa

Dalla radice del repository:

```powershell
# 1. Avvio
docker compose -f infra/docker-compose.dev.yml --env-file .env up -d

# 2. Stato: il container deve essere "running" (o "healthy" se hai l'healthcheck)
docker compose -f infra/docker-compose.dev.yml --env-file .env ps

# 3. Log: cerca "database system is ready to accept connections"
docker compose -f infra/docker-compose.dev.yml --env-file .env logs postgres

# 4. Dentro il database (il nome del container lo vedi nel comando ps)
docker exec -it <nome-container> psql -U <utente> -d <database>
```

Dentro `psql`:

```sql
select version();               -- deve dire PostgreSQL 17.x
create table prova (id int);
insert into prova values (1);
\q
```

`\q` esce da `psql`. Poi la prova della persistenza:

```powershell
docker compose -f infra/docker-compose.dev.yml --env-file .env down
docker compose -f infra/docker-compose.dev.yml --env-file .env up -d
docker exec -it <nome-container> psql -U <utente> -d <database> -c "select * from prova;"
```

Se vedi la riga con `1`, il volume funziona. Poi cancella la tabella di prova (`drop table prova;`): lo schema deve crearlo solo Flyway (D-004).

Infine `docker volume ls`: deve comparire il tuo volume con il prefisso del nome del progetto.

### Errori tipici

| Sintomo | Causa probabile |
|---|---|
| `required variable POSTGRES_PASSWORD is missing` | Hai dimenticato `--env-file .env`, oppure il `.env` non esiste (va creato da `.env.example`) |
| `port is already allocated` | Un altro programma usa già quella porta sul tuo computer: cambia `POSTGRES_PORT` nel `.env` |
| `password authentication failed` dopo aver cambiato la password nel `.env` | Le variabili valgono solo al primo avvio (1.6). Cambiala con `ALTER USER` o riparti con `down -v` |
| Dopo `down` e `up` i dati sono spariti | Il volume non è montato sulla cartella dei dati giusta per la versione 17 (domanda 2) |
| Avviso `the attribute version is obsolete` | C'è una riga `version:` da togliere |
| Errore di parsing YAML | Quasi sempre indentazione: tab al posto di spazi, o un livello sbagliato |

---

## Domande da colloquio

1. Che differenza c'è tra un'immagine e un container?
2. Perché non usare il tag `latest` per un database?
3. Named volume, bind mount, volume anonimo: differenze e quando usarli.
4. Cosa succede ai dati di un database in un container senza volume quando il container viene ricreato?
5. Che differenza c'è tra `environment:`, `env_file:` e il file `.env` in Docker Compose?
6. Perché in produzione non si pubblica la porta del database? Cosa c'entra il firewall?

---

## Documentazione ufficiale

- Immagine ufficiale PostgreSQL (variabili, PGDATA, inizializzazione): https://hub.docker.com/_/postgres
- Compose, servizi: https://docs.docker.com/reference/compose-file/services/
- Compose, volumi: https://docs.docker.com/reference/compose-file/volumes/
- Compose, interpolazione delle variabili: https://docs.docker.com/reference/compose-file/interpolation/
- Compose, variabili d'ambiente e `--env-file`: https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/
- Compose, nome del progetto: https://docs.docker.com/compose/how-tos/project-name/
- Volumi in Docker: https://docs.docker.com/engine/storage/volumes/
- Pubblicazione delle porte: https://docs.docker.com/engine/network/port-publishing/
