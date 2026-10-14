# Registro dei punti deboli

Concetti emersi come non chiari nelle [verifiche](verifiche/README.md). Ogni test apre con 1–2 domande sui punti in scadenza, poste in modo diverso dall'ultima volta. Regole complete in [`metodo-di-studio.md`](../metodo-di-studio.md).

| Stato | Significato | Quando si ripropone |
|---|---|---|
| 🔴 | Da ripassare | Al test successivo |
| 🟡 | In miglioramento (risposto bene una volta) | Dopo almeno 2 giorni |
| 🟢 | Consolidato (risposto bene due volte, in giorni diversi) | Nei ripassi misti |

⚠️ = risposta data con sicurezza alta ma sbagliata: priorità alta.

---

## Da ripassare e in miglioramento

| ID | Stato | Concetto | Cosa fissare | Dove studiarlo | Storia |
|---|---|---|---|---|---|
| PD-01 | 🔴 | Attori e file | I file (`.env`, `.yml`, `application.properties`) sono istruzioni passive; chi li legge e agisce sono i programmi (Compose, Spring, Maven). Lo stesso file può essere interpretato diversamente da due programmi (es. spazi finali nel `.env`: Compose li toglie, Spring li tiene). Per Spring il file `.env` **non è l'ambiente**: è un file di proprietà importato, quindi la conversione `SERVER_PORT` → `server.port` (relaxed binding) lì non vale | Verifica 01, D1–D2; passo 04, errore 5; guida 03, 1.7 | V01 ❌ · sessione 2026-10-14 (errore 5; `.env` scambiato per l'ambiente) |
| PD-02 | 🔴 | Inizializzazione di PostgreSQL | Le variabili `POSTGRES_*` contano solo al primo avvio su volume vuoto; la password vive come impronta nel volume; ricreare il container non la cambia | Guida 02, 1.6 | V01 ❌ |
| PD-03 | 🔴 | `JAVA_HOME` e `java.version` | `JAVA_HOME` = con quale Java si esegue Maven; `java.version` nel pom = per quale versione si compila | Guida 01, 2.6, 3.5 | V01 ❌ |
| PD-04 | 🔴 | L'auto-configurazione come regista | Tra la lettura della configurazione e l'avvio di Hikari/Flyway/Hibernate c'è l'auto-configurazione, che valuta le condizioni e decide quali bean creare (e in che ordine) | Guida 01, 4.5; guida 03, domanda 5 | V01 ❌ |
| PD-05 | 🔴 | Leggere i messaggi d'errore per livelli | `refused` = nessuno in ascolto: la catena si è rotta all'indirizzo, prima di PostgreSQL; `authentication failed` = credenziali; `does not exist` = dati. Gli errori arrivano **uno alla volta**, nell'ordine della catena (driver → rete → PostgreSQL): un errore diverso dopo una correzione è un progresso. Il riavvio spegne i processi, non i file | Verifica 01, D4; verifica 02, D1 e D3; guida 02, Parte 4 | V01 ❌ · V02 🟡 (D1) ❌ (D3) |
| PD-06 | 🟡 | Rete dei container | `localhost` dentro un container è il container stesso; tra container si usa il nome del servizio, risolto dal DNS interno di Docker | Guida 02, 1.5 | V01 ❌ · V02 ✅ (2026-10-14) |
| PD-07 | 🔴 | `update` e la deriva dello schema | `update` corregge da solo ogni database e nasconde il problema anche nei test; la migrazione scritta dopo fallisce dove Hibernate ha già agito | Guida 03, domanda 4; verifica 01, D5 | V01 ❌ |
| PD-08 | 🔴 | Cosa arriva da solo | Maven (Wrapper, versione fissata nel repository), librerie (Maven Central), PostgreSQL (immagine Docker): niente da installare | Guida 01, 3.3 e 3.8 | V01 ❌ |
| PD-09 | 🔴 | Ruoli di Caddy e del database in produzione | Caddy gestisce solo il traffico web (80/443); il database non ha porte pubblicate e lo raggiunge solo il backend sulla rete interna | Guida 02, domanda 4; architettura | V01 🟡 |
| PD-10 | 🔴 | JDBC come contratto e scope `runtime` | Il codice parla solo le interfacce JDBC (nel JDK); il driver le implementa e viene scelto a runtime dall'URL. Cambiare database = cambiare driver (più l'SQL specifico). Lo scope `runtime` impedisce di importare classi del driver (es. `PSQLException`) | Guida 01, 3.6; guida 03, 1.1; verifica 02, D2 | V02 🟡 ❌ |
| PD-11 | 🔴 | Il pool di connessioni | Prestito breve (una transazione), quindi 10 connessioni ≠ 10 clienti; chi è in eccesso aspetta fino a `connectionTimeout` (30 s), poi errore; il pool limita il carico sul database (`max_connections` = 100, un processo per connessione). OSIV e chiamate lente dentro le transazioni lo esauriscono | Guida 03, 1.2; verifica 02, D4 | V02 ✅ ❌ 🟡 |
| PD-13 | 🔴 | Percorsi relativi e importazione del `.env` | I percorsi `file:` sono relativi alla **cartella di lavoro** del processo, non alla posizione del file che li contiene; il formato si indica tra parentesi quadre (`.env[.properties]`), non fa parte del nome; `optional:` + un default nasconde il file mancante | Guida 03, domanda 1; passo 04, errori 2–4 | Sessione 2026-10-14 ❌ (errori 2, 3, 4) |
| PD-12 | 🔴 | Chi usa JDBC sotto JPA | HikariCP (connessioni), Flyway (migrazioni), Hibernate (SQL con `PreparedStatement`); il driver non usa JDBC, lo implementa. JPA è un livello sopra JDBC, non un'alternativa | Guida 03, 1.3; verifica 02, D5 | V02 🟡 |

---

## Consolidati

*(nessuno ancora)*
