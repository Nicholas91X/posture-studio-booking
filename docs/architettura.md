# Architettura

Come è fatto il sistema e come si muovono le informazioni. Le regole del prodotto stanno in `requisiti.md`, le tabelle in `modello-dati.md`, il perché di ogni scelta in `decisioni.md`.

## I pezzi

```
                 Internet
                     │
              ┌──────▼──────┐
              │    Caddy    │  HTTPS automatico, smista le richieste
              └──┬───────┬──┘
     /           │       │  /api/*
          ┌──────▼──┐  ┌─▼─────────────┐      ┌──────────┐
          │ Next.js │  │ Spring Boot   │◄─────┤  Stripe  │ (webhook)
          │frontend │  │ backend       ├─────►│          │ (crea pagamenti)
          └─────────┘  └──┬─────────┬──┘      └──────────┘
                          │         │
                   ┌──────▼───┐  ┌──▼──────────┐  ┌─────────────────┐
                   │PostgreSQL│  │ Servizio    │  │ Servizi push dei│
                   └──────────┘  │ email       │  │ browser (Web    │
                                 └─────────────┘  │ Push, D-032)    │
                                                  └─────────────────┘
```

Tutto gira con Docker Compose su un server Hetzner. Stripe, il servizio email e i servizi push dei produttori dei browser (Apple, Google, Mozilla) sono esterni. Le notifiche push partono dal backend.

| Pezzo | Tecnologia | Chi lo scrive | Cosa fa |
|---|---|---|---|
| Backend | Java 21, Spring Boot | Nicholas | Tutta la logica: calendario, prenotazioni, crediti, regole, pagamenti |
| Database | PostgreSQL 17 | Nicholas (migrazioni) | Conserva i dati e fa rispettare alcune regole (niente sovrapposizioni) |
| Frontend | Next.js, TypeScript | Claude | Pagine per prenotare e pannello dei professionisti. Mostra e invia, non decide |
| Reverse proxy | Caddy | Nicholas | Certificato HTTPS, indirizza `/api` al backend e il resto al frontend |
| Pagamenti | Stripe Checkout | — | Pagina di pagamento ospitata da Stripe |
| Email | da scegliere (orientamento: Brevo) | — | Codici di accesso, conferme, riserva delle notifiche push |
| Notifiche push | Web Push con chiavi VAPID (D-032) | — | Avvisano il professionista delle prenotazioni da approvare |

## Struttura del repository

Un unico repository (monorepo) con tutto dentro:

```
posture-studio-booking/
├── CLAUDE.md
├── docs/            documentazione (questa cartella)
├── backend/         Spring Boot (Maven)
├── frontend/        Next.js
└── infra/           docker-compose, Caddyfile, script di backup
```

## Backend

### Moduli

Un'unica applicazione, divisa in package per area. Ogni modulo espone verso gli altri solo un "servizio" pubblico: nessun modulo legge direttamente le tabelle di un altro.

| Modulo | Responsabilità |
|---|---|
| `practitioner` | Professionisti e loro impostazioni |
| `customer` | Clienti |
| `auth` | Codici di accesso, sessioni, ruoli |
| `catalog` | Servizi e pacchetti in vendita, prezzi |
| `schedule` | Orari di lavoro, indisponibilità, calcolo degli orari liberi |
| `booking` | Prenotazioni, blocco temporaneo, approvazione (D-030), cancellazioni |
| `credit` | Lotti e movimenti dei crediti |
| `payment` | Integrazione Stripe, webhook |
| `notification` | Invio email e notifiche push, dispositivi registrati (D-032) |

### Livelli dentro ogni modulo

- **Controller**: riceve la richiesta HTTP, valida l'input, chiama il servizio. Nessuna logica.
- **Service**: le regole del dominio e le transazioni.
- **Repository**: accesso al database con Spring Data JPA.
- **Oggetti di scambio**: le API ricevono e restituiscono `record` dedicati (DTO), mai le entità JPA.

### API

- REST, JSON, tutte sotto `/api`.
- Contratto OpenAPI generato in automatico (springdoc). Il frontend genera i suoi tipi da lì.
- Errori in formato standard *Problem Details* (RFC 9457), con un codice leggibile (es. `SLOT_NOT_AVAILABLE`) che il frontend traduce in un messaggio in italiano.

### Lavori programmati

- **Scadenza dei blocchi**: ogni minuto, le prenotazioni in attesa di pagamento con blocco scaduto passano a "scaduta" e l'orario si libera.
- **Scadenza delle approvazioni** (D-030): ogni minuto, le prenotazioni in attesa di approvazione oltre la scadenza passano ad "approvazione scaduta". Il blocco sulla carta si annulla o il credito torna, l'orario si libera e il cliente riceve un'email.
- **Sedute passate**: ogni notte, le prenotazioni confermate già avvenute passano a "svolta".

### Test

- Test unitari per le regole del dominio (calcolo disponibilità, consumo dei crediti, regole di cancellazione).
- Test di integrazione con Testcontainers: un PostgreSQL vero, avviato in Docker durante i test.
- Il webhook di Stripe si testa con eventi di esempio firmati.

## Database

- PostgreSQL, in un container Docker con i dati su un volume.
- Lo schema cambia solo con migrazioni Flyway numerate (`V1__...sql`, `V2__...sql`).
- Alcune regole le fa rispettare il database stesso, come ultima difesa: due prenotazioni attive non possono sovrapporsi per lo stesso professionista.
- Backup: dump giornaliero, copiato fuori dal server.

## Frontend

- Next.js con App Router, TypeScript.
- Due aree:
  - **Pubblica**: scelta del professionista e del servizio, calendario, accesso con codice, pagamento, "le mie sedute" e crediti.
  - **Pannello** (solo professionisti): agenda, richieste da approvare, servizi e prezzi, pacchetti, orari, indisponibilità, impostazioni. Installabile sul telefono come app (*PWA*), con un *service worker* che riceve le notifiche push (D-032).
- Nessuna regola di business: il frontend chiede al backend e mostra quello che riceve.
- Mobile prima di tutto: la maggior parte dei clienti prenoterà dal telefono.

## Accesso e sessioni

1. L'utente inserisce l'email.
2. Il backend genera un codice di 6 cifre, ne salva solo l'impronta (hash), con scadenza di 10 minuti e un massimo di 5 tentativi, e lo invia via email.
3. L'utente inserisce il codice. Se è corretto, il backend crea la sessione e la invia come cookie `HttpOnly`, `Secure`, `SameSite=Lax`.
4. Frontend e backend sono sullo stesso dominio (grazie a Caddy), quindi il cookie funziona senza configurazioni particolari.

Lo stesso meccanismo vale per i professionisti: la loro email ha il ruolo `PRACTITIONER`, che apre il pannello.

## Flussi principali

### Prenotazione con carta

1. Il cliente sceglie un orario libero (che inizia più di 24 ore dopo, D-030). Se il professionista approva a mano, il cliente vede il tempo di risposta prima di confermare.
2. Il backend crea la prenotazione in stato "in attesa di pagamento", con un blocco di 30 minuti. Se l'orario nel frattempo è stato preso, il database rifiuta e il cliente vede "orario non più disponibile".
3. Il backend crea una sessione Stripe Checkout con *manual capture* (D-031) e scadenza uguale al blocco, e restituisce l'indirizzo della pagina di pagamento.
4. Il cliente paga sulla pagina di Stripe e torna sul sito, che mostra "pagamento in verifica". Stripe ha solo **bloccato** l'importo sulla carta.
5. Stripe avvisa il backend con il webhook `checkout.session.completed`. La prenotazione passa ad "in attesa di approvazione", con la scadenza fotografata.
6. **Approvazione**:
   - automatica: il backend approva subito;
   - manuale: il backend avvisa il professionista con una notifica push (email di riserva), e lui approva o rifiuta dal pannello entro la scadenza.
7. **Approvata**: il backend chiede a Stripe l'incasso. Quando Stripe risponde che è riuscito, la prenotazione è confermata e il cliente riceve l'email di conferma.
8. **Rifiutata, scaduta o ritirata dal cliente**: il backend annulla il blocco sulla carta, l'orario si libera, e il cliente riceve un'email. Non è stato addebitato niente.

### Prenotazione con credito

1. Il cliente sceglie un orario. Il backend vede che ha crediti validi per quel professionista e servizio.
2. In un'unica transazione: crea la prenotazione in attesa di approvazione e registra un movimento −1 sul lotto che scade prima (il credito è **impegnato**).
3. Approvazione come per la carta. Approvata: confermata. Rifiutata, scaduta o ritirata: movimento +1 sullo stesso lotto, che mantiene la sua scadenza.

Nessun passaggio su Stripe.

### Acquisto di un pacchetto

1. Il cliente sceglie il pacchetto. Il backend crea un pagamento "in attesa" e la sessione Stripe Checkout.
2. Al webhook `checkout.session.completed`: crea il lotto di crediti con la scadenza del pacchetto e il movimento +N.

### Cancellazione

1. Il backend confronta l'ora attuale con l'inizio della seduta e il preavviso del professionista.
2. **In tempo**: stato "cancellata in tempo", l'orario si libera, e:
   - se era pagata con credito: movimento +1 sullo stesso lotto (mantiene la sua scadenza);
   - se era pagata con carta: nuovo lotto da 1, con la validità prevista per le sedute singole.
3. **Tardi**: stato "cancellata tardi", l'orario si libera, nessun credito.

## Stripe

Cosa si usa e cosa va documentato oltre al webhook:

- **Stripe Checkout**: la pagina di pagamento è di Stripe. Il backend crea una *Checkout Session* con importo, descrizione e `metadata` (id della prenotazione o del pagamento interno), e riceve un indirizzo a cui mandare il cliente.
- **Scadenza della sessione**: Stripe accetta scadenze da un minimo di 30 minuti. Per questo il blocco dell'orario dura 30 minuti, allineato alla sessione.
- **Manual capture** (D-031): per le sedute, la sessione Checkout blocca l'importo senza incassarlo. Il backend chiama poi Stripe per **incassare**, all'approvazione, o per **annullare** il blocco, al rifiuto, alla scadenza o al ritiro. Il blocco dura in genere circa 7 giorni. I pacchetti si incassano subito.
- **Webhook**, gli eventi gestiti:
  - `checkout.session.completed` → seduta: importo bloccato, prenotazione in attesa di approvazione; pacchetto: crea il lotto
  - `checkout.session.expired` → prenotazione scaduta, orario libero
  - eventi di incasso e annullamento del PaymentIntent: da scegliere al passo 9
- **Verifica della firma**: ogni webhook arriva firmato. Il backend controlla la firma con il segreto del webhook prima di fidarsi.
- **Idempotenza**: Stripe può mandare lo stesso evento più volte. Il backend salva l'id di ogni evento elaborato e ignora i doppioni.
- **Pagamento arrivato tardi**: se un pagamento viene confermato per una prenotazione già scaduta, il backend **annulla il blocco** sulla carta (D-031, prima era un credito) e avvisa il cliente via email.
- **Modalità test**: in sviluppo si usano le chiavi di test e le carte di prova di Stripe. La Stripe CLI inoltra i webhook al backend in esecuzione sul computer locale.
- **Rimborsi**: non gestiti dal sistema. In casi eccezionali si fanno a mano dalla dashboard Stripe.

## Docker e messa online

### In locale
- `infra/docker-compose.dev.yml` avvia solo PostgreSQL. Backend e frontend girano dall'IDE, per avere riavvii rapidi e debug.

### In produzione (Hetzner)
- `infra/docker-compose.yml` con quattro servizi: `caddy`, `frontend`, `backend`, `postgres`.
- Solo Caddy è esposto su internet (porte 80 e 443). Gli altri parlano tra loro sulla rete interna di Docker.
- I segreti stanno in un file `.env` sul server, mai nel repository.
- Le immagini Docker si costruiscono con GitHub Actions e si pubblicano su GitHub Container Registry. Il server scarica l'ultima versione e riavvia.
- Il backend si costruisce con un `Dockerfile` a due fasi: una per compilare con Maven, una leggera con solo il JRE per eseguire.
- Backup giornaliero del database con `pg_dump`, copiato su uno spazio esterno al server.
