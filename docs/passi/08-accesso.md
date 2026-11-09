# Passo 08 — Accesso e notifiche

> **Stato: in corso** (dal 2026-11-09). Pezzo 1 di 6 fatto, 478 test verdi (`mvnw clean test`).

## Obiettivo
Il sistema sa chi lo sta usando: accesso con un codice via email o via SMS (solo da un numero verificato), sessione con cookie, ruoli, account disattivati fuori. I sostituti provvisori (`DevHeaderCurrentPractitioner`, `DevHeaderCurrentCustomer`) spariscono. Le notifiche email e push si agganciano agli eventi del passo 7.

I pezzi (guida 24, parte 0):
1. Codice di accesso via email: richiesta, verifica, limiti — ✅ fatto (guida 24, D-050)
2. Sessione e Spring Security, ruoli, via i due DevHeader, test della riservatezza
3. Admin e professionisti (D-021)
4. Notifiche email sugli eventi del passo 7
5. Notifiche push (D-032, D-039), con riserva email
6. Codice di accesso via SMS da numero verificato

## Criteri di "fatto" (dal brief)

| Criterio | Stato | Dove si vede |
|---|---|---|
| Accesso via email e via SMS funzionanti, con limiti e tentativi provati | email: richiesta, verifica e limiti nel servizio; manca l'endpoint della verifica (pezzo 2) e l'SMS (pezzo 6) | `LoginCodeServiceTest`, `LoginCodeApiTest` |
| I due DevHeader rimossi; nessun test li usa più | pezzo 2 | |
| Test della riservatezza esistono e sono verdi | pezzo 2 | |
| Ogni area solo al suo ruolo; account disattivati fuori | disattivati rifiutati dalla verifica; ruoli al pezzo 2 | `LoginCodeServiceTest` |
| L'admin crea e disattiva professionisti; primo admin senza segreti | pezzo 3 | |
| Email in Mailpit per ogni evento; un invio fallito non annulla la prenotazione | Mailpit attivo e codice di accesso provato a mano; eventi al pezzo 4 | `infra/docker-compose.dev.yml` |
| Push con riserva email provata | pezzo 5 | |
| Punto aperto 7 chiuso; punto 5 spostato al passo 14; nuovo punto sul fornitore SMS | punto 5 spostato; 7 e fornitore SMS al pezzo 6 | `stato.md`, `requisiti.md` |
| `modello-dati.md` aggiornato; test verdi; branch e PR | V5 descritta; 478 verdi | `modello-dati.md` |

## Cosa è stato costruito

### Pezzo 1 — Codice di accesso via email
- `V5__create_login_code.sql`: tabella `login_code` con `channel` + `destination` (pronta per l'SMS), `code_hash`, `requested_from` (`inet`), `created_at` scritto da Java, `expires_at`, `attempts` (`check` 0–5), `used_at`; tre indici.
- `auth/LoginCode`: l'entità, con le regole di D-014 come costanti (10 minuti, 5 tentativi) e il codice a 6 cifre generato con un `RandomGenerator`.
- `auth/LoginCodeHasher`: HMAC-SHA256 di `destinazione:codice` con la chiave `AUTH_CODE_HMAC_KEY`, e il confronto con `MessageDigest.isEqual`. Se la chiave manca o è corta, l'applicazione non parte.
- `auth/CodeCheck`: `sealed interface` con quattro `record` (`Valid`, `Wrong`, `Expired`, `AccountDeactivated`), l'esito della verifica.
- `auth/LoginCodeService`: `request` (limiti, riga, commit, poi spedizione; 429 e 503), `verify` (lucchetto, tentativi, account creato al primo accesso), `purgeOld`, `limitKeyOf` (rete `/64` per IPv6).
- `auth/LoginCodeRepository`: l'ultimo codice con `@Lock(PESSIMISTIC_WRITE)`, due conteggi con `select new WindowUsage(...)`, la cancellazione in blocco.
- `auth/LoginCodeProperties`: un `record` `@ConfigurationProperties` con i limiti, controllati nel costruttore compatto.
- `auth/LoginCodeController`: `POST /api/public/auth/login-code`, 202 senza corpo.
- `auth/LoginCodeJobs`: la pulizia ogni ora.
- `auth/LoginCodeSender`: l'interfaccia della spedizione. `notification/EmailLoginCodeSender` la implementa con Spring Mail (*text block* per i testi).
- `shared/TooManyRequestsException` (429 con `Retry-After`) e `shared/DeliveryFailedException` (503), nel gestore degli errori.
- `infra/docker-compose.dev.yml`: il servizio `mailpit` (SMTP sulla porta 1025, pagina web sulla 8025). `.env.example`: le righe `MAIL_*` e `AUTH_CODE_HMAC_KEY`.
- Test: `LoginCodeTest`, `LoginCodeHasherTest` (unitari), `LoginCodeSchemaTest`, `LoginCodeServiceTest` (con la concorrenza), `LoginCodeApiTest`, e `RecordingLoginCodeSender` come finto mittente.

## Concetti chiave

- **Perché l'hash da solo non basta.** I codici a 6 cifre sono un milione. Chi ruba la tabella calcola tutte le impronte SHA-256 in meno di un secondo; anche BCrypt cede in pochi minuti su una scheda grafica. Un **HMAC** con una chiave fuori dal database rende le impronte inutili senza la chiave. La difesa vera resta il limite di tentativi con la scadenza breve.
- **Confronto a tempo costante.** `equals` si ferma al primo byte diverso, e la sua durata rivela quanto dell'inizio era giusto. `MessageDigest.isEqual` confronta sempre tutto.
- **Eccezione e rollback.** Una `RuntimeException` in un metodo `@Transactional` annulla **tutto**, anche il tentativo appena contato. Un esito normale (codice sbagliato) si restituisce come valore, non si lancia. Con una `sealed interface` di `record`, lo `switch` del chiamante copre tutti i casi senza `default`.
- **Lost update e lucchetto pessimistico.** Due transazioni leggono `attempts = 3` e scrivono entrambe `4`. Con `select ... for update` la seconda aspetta la prima e rilegge il valore aggiornato. Visto davvero: senza lucchetto, 10 su 10.
- **Enumerazione degli account.** Rispondere uguale (stato, corpo, tempo) che l'email esista o no. Il modo più semplice è comportarsi davvero allo stesso modo: un'email per ogni richiesta, l'account solo alla verifica.
- **Rate limiting.** Contare nel database le righe che esistono già: valgono ai riavvii e con più istanze. Limiti larghi per IP (CGNAT, Wi-Fi), rete `/64` in IPv6, e l'IP vero da Tomcat (`forward-headers-strategy=native`), mai da `X-Forwarded-For` letto a mano.
- **Inversione delle dipendenze** (terza volta, dopo `OccupiedTime` e `CardHolds`): `auth` dichiara `LoginCodeSender`, `notification` lo implementa.
- **Prima il commit, poi la chiamata esterna.** Un'email spedita prima di un commit fallito porterebbe un codice inesistente.
- **Java moderno usato qui:** `sealed interface` e *record pattern* (21), `record` con costruttore compatto come configurazione, *text block* (15), `RandomGenerator` (17), `HexFormat` (17), `List.reversed()` delle *sequenced collection* (21, nel finto mittente dei test).

## Decisioni
- **D-050** — codice di accesso via email (precisa **D-014**: l'account nasce alla prima verifica giusta; l'impronta è un HMAC).

## Errori fatti e come sono stati corretti

1. **Un generatore non crittografico per una chiave segreta.** Nel primo `.env.example` la riga suggerita per generare `AUTH_CODE_HMAC_KEY` in PowerShell usava `Get-Random`, che non è un generatore crittografico (come `java.util.Random`). Una chiave generata così sarebbe prevedibile. Corretta con `RandomNumberGenerator` di .NET, e una riga che avvisa di non usare `Get-Random`. La lezione è la stessa della guida 24, 3.5, applicata agli strumenti fuori da Java.
2. **Test verdi al primo colpo: provato che sanno fallire.** Non è un errore del codice, ma il motivo per cui ci si fida dei test. Con l'errore del 4.1 introdotto apposta, tre test diventano rossi. Senza `@Lock`, il test delle ipotesi in parallelo fallisce con *"Expected size: 4 but was: 10"*.
3. **Porta 5432 occupata** durante la prova a mano: in locale gira già il database di un altro progetto. Il PostgreSQL di questo progetto è stato avviato sulla 5433 solo per la prova (`POSTGRES_PORT=5433` davanti al comando). Da ricordare se l'avvio di Compose fallisce con "port is already allocated".

## Domande da colloquio

Le domande del pezzo 1 sono nella guida 24 (sei domande, con le risposte modello): hash di un codice a 6 cifre, confronto a tempo costante, eccezione e rollback dei tentativi, enumerazione degli account, rate limit dietro un proxy, `SecureRandom`. Qui una in più, nata dall'implementazione.

### Domanda 1: I tuoi test sono tutti verdi. Come sai che proverebbero davvero un errore?
**Risposta modello.** Rompendo apposta il codice e guardando se diventano rossi (*mutation testing*, a mano o con uno strumento come PIT). Per esempio, tolto il lucchetto pessimistico, il test della concorrenza deve fallire. Se resta verde, quel test non prova il lucchetto. Un test che non può fallire dà una sicurezza falsa.
